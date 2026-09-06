import type { Page } from '@playwright/test'

/**
 * Puts the page in one visibility state and tells the app it moved — the one input TanStack
 * Query's focus manager reads before deciding to refetch, and the half {@link refocus}
 * cannot express, since asserting that a tab which stayed away does *not* refetch means
 * stopping at `hidden`.
 *
 * The event goes to `window` rather than `document` because that is where the manager
 * registers its listener, and the state moves before the dispatch because `isFocused()`
 * reads `document.visibilityState` at the moment the event arrives.
 *
 * The override does not survive a navigation, so a spec that navigates between a hidden
 * half and a visible half silently tests nothing.
 * See `docs/design-decisions/38-accounts-list-screen.md`.
 */
export const setVisibility = (page: Page, state: DocumentVisibilityState): Promise<void> =>
  page.evaluate((visibilityState: DocumentVisibilityState) => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibilityState,
    })
    window.dispatchEvent(new Event('visibilitychange'))
  }, state)

/**
 * A tab left and returned to, which is what a screen relying on refetch-on-focus is
 * compensating for.
 *
 * Both halves are needed: the manager only notices a return if it saw the leaving, since
 * `visible → visible` moves nothing it reads. It does not dedupe, so each round trip within
 * one spec drives a fresh refetch.
 */
export const refocus = async (page: Page): Promise<void> => {
  await setVisibility(page, 'hidden')
  await setVisibility(page, 'visible')
}
