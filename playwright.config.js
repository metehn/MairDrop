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
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: './mvnw -q -Dcheckstyle.skip=true -Djacoco.skip=true spring-boot:run',
    url: 'http://localhost:8080',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
