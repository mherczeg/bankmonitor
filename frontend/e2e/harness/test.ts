import { test as playwright } from '@playwright/test'

import { scriptApi, type ScriptedApi } from './api'
import { useFakeEventSource } from './stream'

/**
 * The `test` every browser spec imports: Playwright's, with the two substitutions this
 * app's specs always want already made.
 *
 * The fake event source is installed for every spec whether or not it drives one, because
 * it is a substitution rather than a tool — a spec that left the real `EventSource` in
 * place would have the app open a connection to a stream nothing answers, on a page whose
 * network is otherwise entirely scripted.
 */
export const test = playwright.extend<{ api: ScriptedApi; fakeEventSource: void }>({
  fakeEventSource: [
    async ({ page }, use) => {
      await useFakeEventSource(page)
      await use()
    },
    { auto: true },
  ],

  api: async ({ page }, use) => {
    await use(await scriptApi(page))
  },
})

export { expect } from '@playwright/test'
export { eventStream } from './stream'
export { anAccount, aProblem, aTransfer } from './fixtures'
