import {defineConfig, devices} from "@playwright/test";

/**
 * Pruebas de humo de punta a punta. Esperan el frontend en E2E_BASE_URL (por defecto
 * http://localhost:3000) con el backend detrás. En CI ambos se levantan antes de correrlas.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["list"]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL || "http://localhost:3000",
    trace: "off",
  },
  projects: [
    {name: "desktop", use: {...devices["Desktop Chrome"]}},
    {name: "mobile", use: {...devices["Pixel 7"]}},
  ],
});
