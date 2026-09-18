import { defineConfig, devices } from '@playwright/test'

/**
 * e2e del SPA (FEAT-0009..0016).
 *
 * Los escenarios corren contra el **gateway con el SPA construido** (`web/dist` + profile `con-spa`,
 * o la imagen Docker): es el único artefacto donde el hosting mismo-origen, la CSP y el
 * enrutamiento del cliente se comportan como en producción. Como el stack real necesita Postgres,
 * RabbitMQ y el simulador, el `webServer` de acá levanta **sólo el gateway** con el stub de backend
 * de `e2e/stubs/`, que implementa el contrato de los endpoints usados y expone además un canal WS
 * para lecturas y alertas.
 *
 * Uso:
 *   cd web && npm run build && npm run e2e            # levanta gateway + stub y corre los escenarios
 *   E2E_BASE_URL=http://localhost:8084 npm run e2e    # contra un stack ya levantado (sin webServer)
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8085',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.E2E_BASE_URL
    ? undefined
    : {
        command: 'node e2e/stubs/servidor.mjs',
        url: 'http://127.0.0.1:8085/',
        reuseExistingServer: !process.env.CI,
        timeout: 60_000,
      },
})