// @ts-check
const { test, expect } = require('@playwright/test');

test.describe('Device discovery', () => {
  test('two devices on the same network see each other in the device list', async ({ browser }) => {
    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    try {
      await pageA.goto('/');
      await pageB.goto('/');

      await expect(pageA.locator('#connectionStatus')).toContainText('Connected');
      await expect(pageB.locator('#connectionStatus')).toContainText('Connected');

      const nameA = await pageA.locator('#deviceIdSpan').textContent();
      const nameB = await pageB.locator('#deviceIdSpan').textContent();

      await expect(pageA.locator('#deviceList')).toContainText(nameB);
      await expect(pageB.locator('#deviceList')).toContainText(nameA);
    } finally {
      await contextA.close();
      await contextB.close();
    }
  });

  test('a device that disconnects is removed from the other device\'s list', async ({ browser }) => {
    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    try {
      await pageA.goto('/');
      await pageB.goto('/');

      const nameB = await pageB.locator('#deviceIdSpan').textContent();
      await expect(pageA.locator('#deviceList')).toContainText(nameB);

      await contextB.close();

      await expect(pageA.locator('#deviceList')).not.toContainText(nameB);
    } finally {
      await contextA.close();
    }
  });

  test('the page exposes a parsed ICE server config to the WebRTC layer', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#connectionStatus')).toContainText('Connected');

    const iceServersJson = await page.evaluate(() => window.ICE_SERVERS_JSON);
    const parsed = JSON.parse(iceServersJson);
    expect(Array.isArray(parsed)).toBe(true);
    expect(parsed[0].urls).toBe('stun:stun.l.google.com:19302');
  });
});
