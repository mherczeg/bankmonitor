import { defineConfig, devices } from '@playwright/test'

/**
 * The browser test seam: a real browser against a network the spec scripts.
 *
 * **One browser, deliberately.** These specs assert what the app does, not what a
 * rendering engine does, and the one thing a second engine would genuinely catch — a CSS
 * or layout difference — is not what any of them look at. Three engines would triple the
 * run and the browser download for no assertion that could tell them apart.
 *
 * **No retries, deliberately.** The point of the fake event source is that every sequence
 * is ordered by the spec rather than by a timer, so a spec that only passes on a second
 * attempt is a spec with a race in it, and a retry would hide that.
 *
 * **The dev server, not a preview of the build.** Nothing here touches the backend — the
 * whole network is scripted — so what a production bundle would add is a build step before
 * every run. It also lets the smoke spec's subject page live outside `src/`, where the
 * production build cannot reach it.
 */

const PORT = 5173

const baseURL = `http://localhost:${PORT}`

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  use: { baseURL, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: devices['Desktop Chrome'] }],
  webServer: {
    command: 'npm run dev',
    url: baseURL,
    reuseExistingServer: !process.env.CI,
  },
})
