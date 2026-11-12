// @ts-check
const { test, expect } = require('@playwright/test');
const fs = require('fs');
const os = require('os');
const path = require('path');

// Big enough that the transfer stays visible for a few seconds instead of
// completing in a single instant data-channel message.
const LARGE_FILE_SIZE_BYTES = 20 * 1024 * 1024;

test.describe('File transfer', () => {
  test('sending a file completes and shows a direct (non-relay) connection', async ({ browser }) => {
    const largeFilePath = path.join(os.tmpdir(), 'mairdrop-e2e-large-sample.bin');
    fs.writeFileSync(largeFilePath, Buffer.alloc(LARGE_FILE_SIZE_BYTES, 'm'));

    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    try {
      await pageA.goto('/');
      await pageB.goto('/');

      const nameB = await pageB.locator('#deviceIdSpan').textContent();
      const deviceIdB = await pageB.evaluate(() => sessionStorage.getItem('deviceId'));
      await expect(pageA.locator('#deviceList')).toContainText(nameB);

      await pageA.setInputFiles('#fileInput', largeFilePath);
      await pageA.getByRole('button', { name: '📤 Send' }).click();

      await pageB.getByRole('button', { name: '✓ Accept' }).click();

      // Check relay status while the transfer is in progress (after ICE connects, before the
      // DOM is rebuilt on completion). On localhost WebRTC must always find a direct host
      // candidate — relay fallback indicates a misconfigured ICE server list.
      await expect(pageA.locator('#progress-fill-' + deviceIdB)).not.toHaveClass(/via-relay/, { timeout: 15000 });

      await expect(pageA.locator('#alertContainer')).toContainText('All files sent', { timeout: 30000 });
    } finally {
      await contextA.close();
      await contextB.close();
      fs.rmSync(largeFilePath, { force: true });
    }
  });

  test('multi-file select sends all files', async ({ browser }) => {
    // Three small distinct files: 16KB, 24KB, 40KB.
    const runId = Date.now();
    const specs = [
      { name: 'alpha', size: 16 * 1024, fill: 'a' },
      { name: 'bravo', size: 24 * 1024, fill: 'b' },
      { name: 'charlie', size: 40 * 1024, fill: 'c' },
    ];
    const tempFiles = specs.map((spec) => {
      const filePath = path.join(os.tmpdir(), `mairdrop-e2e-multi-${runId}-${spec.name}.bin`);
      fs.writeFileSync(filePath, Buffer.alloc(spec.size, spec.fill));
      return filePath;
    });
    const fileNames = tempFiles.map((filePath) => path.basename(filePath));

    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    try {
      await pageA.goto('/');
      await pageB.goto('/');

      const nameB = await pageB.locator('#deviceIdSpan').textContent();
      await expect(pageA.locator('#deviceList')).toContainText(nameB);

      // The receive panel must stay hidden until the batch is complete.
      await expect(pageB.locator('#receivedFilesPanel')).toBeHidden();

      await pageA.setInputFiles('#fileInput', tempFiles);
      await expect(pageA.locator('#fileCountLabel')).toHaveText('Selected files (3)');
      await pageA.getByRole('button', { name: '📤 Send' }).click();

      // Arm the receiver's download listener BEFORE accepting: the first file is
      // auto-downloaded mid-batch, before the panel becomes visible, so waiting for
      // the event only afterwards would miss it.
      const autoDownloadPromise = pageB.waitForEvent('download', { timeout: 25000 });
      await pageB.getByRole('button', { name: '✓ Accept' }).click();

      // Sender shows its usual completion alert.
      await expect(pageA.locator('#alertContainer')).toContainText('All files sent', { timeout: 30000 });

      // Receiver: batch-complete alert, then the panel with one entry per file.
      // The alert currently appends "from <device>"; match the contract prefix so
      // both wordings pass.
      await expect(pageB.locator('#alertContainer')).toContainText(/All 3 files received/, { timeout: 30000 });
      await expect(pageB.locator('#receivedFilesPanel')).toBeVisible({ timeout: 30000 });
      await expect(pageB.locator('#receivedFilesPanel .received-file-item')).toHaveCount(3);
      await expect(pageB.locator('#receivedFilesPanel .received-file-save')).toHaveCount(3);

      const nameEls = pageB.locator('#receivedFilesPanel .received-file-name');
      await expect(nameEls).toHaveCount(3);
      for (const fileName of fileNames) {
        await expect(nameEls.filter({ hasText: fileName })).toHaveCount(1);
      }

      // First file was auto-downloaded on the receiver. The listener is armed before
      // Accept so the mid-batch download event is captured; both the event itself and
      // its suggested filename are mandatory — a missing or misnamed download fails.
      const firstDownload = await autoDownloadPromise;
      expect(firstDownload.suggestedFilename()).toBe(fileNames[0]);

      // Clicking the second file's Save button downloads exactly that file.
      const secondItem = pageB.locator('#receivedFilesPanel .received-file-item').filter({ hasText: fileNames[1] });
      await expect(secondItem).toHaveCount(1);
      const [savedDownload] = await Promise.all([
        pageB.waitForEvent('download', { timeout: 15000 }),
        secondItem.locator('.received-file-save').click(),
      ]);
      expect(savedDownload.suggestedFilename()).toBe(fileNames[1]);
    } finally {
      await contextA.close();
      await contextB.close();
      tempFiles.forEach((filePath) => fs.rmSync(filePath, { force: true }));
    }
  });

  test('multi-file drag-and-drop sends all files', async ({ browser }) => {
    // Three small distinct in-page files, dropped on the #fileInputWrapper drop target.
    const runId = Date.now();
    const specs = [
      { name: 'delta', size: 12 * 1024 },
      { name: 'echo', size: 20 * 1024 },
      { name: 'foxtrot', size: 28 * 1024 },
    ];
    const fileNames = specs.map((spec) => `mairdrop-e2e-drop-${runId}-${spec.name}.bin`);

    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    try {
      await pageA.goto('/');
      await pageB.goto('/');

      const nameB = await pageB.locator('#deviceIdSpan').textContent();
      await expect(pageA.locator('#deviceList')).toContainText(nameB);

      // Playwright can't drag real OS files; instead build a DataTransfer holding real
      // in-page File objects and dispatch a 'drop' on the element app.js wires up.
      const dataTransfer = await pageA.evaluateHandle((payloads) => {
        const dt = new DataTransfer();
        payloads.forEach((p) => {
          const bytes = Uint8Array.from(atob(p.base64), (c) => c.charCodeAt(0));
          dt.items.add(new File([bytes], p.name, { type: 'application/octet-stream' }));
        });
        return dt;
      }, specs.map((spec, i) => ({
        name: fileNames[i],
        base64: Buffer.alloc(spec.size, 0x61 + i).toString('base64'),
      })));
      await pageA.dispatchEvent('#fileInputWrapper', 'drop', { dataTransfer });

      // Drop registered: the selection UI shows all three files.
      await expect(pageA.locator('#fileCountLabel')).toHaveText('Selected files (3)');
      await pageA.getByRole('button', { name: '📤 Send' }).click();
      await pageB.getByRole('button', { name: '✓ Accept' }).click();

      await expect(pageA.locator('#alertContainer')).toContainText('All files sent', { timeout: 30000 });
      await expect(pageB.locator('#alertContainer')).toContainText(/All 3 files received/, { timeout: 30000 });
      await expect(pageB.locator('#receivedFilesPanel .received-file-item')).toHaveCount(3);

      const nameEls = pageB.locator('#receivedFilesPanel .received-file-name');
      await expect(nameEls).toHaveCount(3);
      for (const fileName of fileNames) {
        await expect(nameEls.filter({ hasText: fileName })).toHaveCount(1);
      }
    } finally {
      await contextA.close();
      await contextB.close();
    }
  });
});
