// @ts-check
const { defineConfig, devices } = require('@playwright/test');

module.exports = defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  // The backend keeps all device/room state in shared in-memory singletons with no per-test
  // tenant isolation, so concurrent test runs interfere with each other (e.g. broadcasts meant
  // for one test's devices land while another test's room-creation round trip is in flight).
  workers: 1,
  retries: 0,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:8080',
    // Required for the multi-file receive tests, which assert the first file's
    // auto-download and the per-file "Save" button downloads.
    acceptDownloads: true,
    trace: 'retain-on-failure',
    // A fixed viewport (rather than null) is required: with a null viewport the page has no
    // defined size and elements never become "actionable", so every click times out — headless
    // in CI and headed under a virtual display alike.
    viewport: { width: 1280, height: 900 },
    launchOptions: {
      // SLOWMO=500 npx playwright test --headed to watch actions happen at a human pace.
      ...(parseInt(process.env.SLOWMO, 10) > 0 ? { slowMo: parseInt(process.env.SLOWMO, 10) } : {}),
      args: [
        // Chrome obfuscates host ICE candidates behind a .local mDNS name by default; two
        // browser contexts on the same machine can't resolve each other's, so WebRTC file
        // transfer tests need real local IPs to find a direct candidate pair.
        '--disable-features=WebRtcHideLocalIpsWithMdns',
      ],
    },
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } } },
  ],
  webServer: {
    command: './mvnw -q -Dcheckstyle.skip=true -Djacoco.skip=true spring-boot:run',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
