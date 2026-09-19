import { defineConfig, devices } from "@playwright/test";

const applicationEnvironment = {
  AUTH0_DOMAIN: "auth.example.invalid",
  AUTH0_CLIENT_ID: "m13g-browser-client",
  AUTH0_CLIENT_SECRET: "m13g-browser-client-secret",
  AUTH0_SECRET: "11".repeat(32),
  AUTH0_AUDIENCE: "https://api.example.invalid",
  APP_BASE_URL: "https://127.0.0.1:3100",
  BACKEND_BASE_URL: "https://backend.example.invalid",
};

export default defineConfig({
  testDir: "./e2e",
  timeout: 20_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  outputDir: "test-results",
  use: {
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  webServer: [
    {
      command: "npm run build && npm run start -- --hostname 127.0.0.1 --port 3100",
      url: "http://127.0.0.1:3100",
      env: applicationEnvironment,
      reuseExistingServer: false,
      timeout: 180_000,
    },
    {
      command: "tsx e2e/fixture-server.tsx",
      url: "http://127.0.0.1:3101/__health",
      env: { M13G_FIXTURE_PORT: "3101" },
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
  projects: [
    { name: "desktop-chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile-chromium", use: { ...devices["Pixel 7"] } },
  ],
});
