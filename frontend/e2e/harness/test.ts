import { test as playwright } from '@playwright/test'

import { scriptApi, type ScriptedApi } from './api'
import { useFakeEventSource } from './stream'

/**
 * The `test` every browser spec imports: Playwright's, with the two substitutions this
 * app's specs always want already made.
 *
 * Both are installed for every spec whether or not it names them, because both are
 * substitutions rather than tools. A spec that left the real `EventSource` in place would
 * have the app open a connection to a stream nothing answers; a spec that registered no
 * route would have its requests answered by the dev server, which proxies `/api/` through
 * to whatever backend happens to be running — and a spec that passes for that reason is
 * the one thing this seam exists to make impossible.
 */
export const test = playwright.extend<{ api: ScriptedApi; fakeEventSource: void }>({
  fakeEventSource: [
    async ({ page }, use) => {
      await useFakeEventSource(page)
      await use()
    },
    { auto: true },
  ],

  api: [
    async ({ page }, use) => {
      await use(await scriptApi(page))
    },
    { auto: true },
  ],
})

export { expect } from '@playwright/test'
export { eventStream } from './stream'
export { refocus, setVisibility } from './focus'
export { anAccount, aProblem, aTransfer } from './fixtures'
