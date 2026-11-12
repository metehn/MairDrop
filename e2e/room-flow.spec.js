// @ts-check
const { test, expect } = require('@playwright/test');

async function enterRoomCode(page, code) {
  const inputs = page.locator('.room-char-input');
  for (let i = 0; i < code.length; i++) {
    await inputs.nth(i).fill(code[i]);
  }
}

test.describe('Public room flow', () => {
  test('create, join, and leave a public room updates both devices and restores network visibility', async ({ browser }) => {
    const contextA = await browser.newContext();
    const contextB = await browser.newContext();
    const pageA = await contextA.newPage();
    const pageB = await contextB.newPage();

    try {
      await pageA.goto('/');
      await pageB.goto('/');
      await expect(pageA.locator('#connectionStatus')).toContainText('Connected');
      await expect(pageB.locator('#connectionStatus')).toContainText('Connected');

      // A creates a public room
      await pageA.locator('#openRoomBtn').click();
      await expect(pageA.locator('#roomModal')).toBeVisible();
      const roomCode = (await pageA.locator('#roomCodeDisplay').textContent()).trim();
      expect(roomCode).toHaveLength(5);

      // Joining a room auto-hides this device from the plain network badge
      await expect(pageA.locator('#netBadge')).toHaveClass(/disc-hidden/);

      // B creates its own room first (default button behavior), then joins A's room by code
      await pageB.locator('#openRoomBtn').click();
      await enterRoomCode(pageB, roomCode);
      await pageB.locator('#roomJoinBtn').click();

      await expect(pageB.locator('#roomModal')).toBeHidden();
      await expect(pageB.locator('#roomDiscCode')).toHaveText(roomCode);

      const nameA = await pageA.locator('#deviceIdSpan').textContent();
      const nameB = await pageB.locator('#deviceIdSpan').textContent();

      // both now see each other in their room-scoped device list
      await expect(pageA.locator('#deviceList')).toContainText(nameB);
      await expect(pageB.locator('#deviceList')).toContainText(nameA);

      // A leaves the room
      await pageA.locator('#roomDiscBadge').click();
      await pageA.locator('#roomLeaveBtn').click();
      await expect(pageA.locator('#roomDiscBadge')).toBeHidden();

      // network visibility restored for A after leaving the room it auto-hid for
      await expect(pageA.locator('#netBadge')).toHaveClass(/disc-active/);
    } finally {
      await contextA.close();
      await contextB.close();
    }
  });

  test('joining with an invalid code shows an error and leaves the modal open', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      await page.goto('/');
      await expect(page.locator('#connectionStatus')).toContainText('Connected');

      await page.locator('#openRoomBtn').click();
      await expect(page.locator('#roomModal')).toBeVisible();

      await enterRoomCode(page, 'ZZZZZ');
      await page.locator('#roomJoinBtn').click();

      await expect(page.locator('#alertContainer')).toContainText('Room not found');
      await expect(page.locator('#roomModal')).toBeVisible();
    } finally {
      await context.close();
    }
  });
});
