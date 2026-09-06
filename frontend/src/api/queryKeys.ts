/**
 * The names every cached answer from the API is filed under, in one place because two
 * sides have to agree on them: the screen that files an answer, and `events.ts`, which
 * says which of them a stream message makes stale. A key written out at each of those
 * would be a key that can drift, and a drifted key fails silently — the screen keeps
 * showing what it loaded and nothing refetches.
 *
 * **A key is a path, and invalidating one invalidates everything beneath it.** That is
 * why the list and the detail sit on separate branches — `['transfers', 'list']` and
 * `['transfers', 'detail', id]` — rather than under `['transfers']` and
 * `['transfers', id]`, where invalidating the list would refetch every Transfer ever
 * opened. See `docs/design-decisions/36-sse-event-module.md`.
 *
 * **A Transfer is named the way the URL names it.** `/transfers/$transferId` hands the
 * screen a string, so the key takes a string and whatever arrives as a number is
 * converted to one. Converting the other way is the version that can fail: `Number(id)`
 * of a URL somebody mistyped is `NaN`, which keys a query nothing will ever answer.
 */

/** One path into the query cache. */
export type QueryKey = readonly (string | number)[]

export const queryKeys = {
  /** Every Account, with its balances — the list the Accounts screen reads. */
  accounts: (): QueryKey => ['accounts'],

  /** Every Transfer — the Transactions list. */
  transfers: (): QueryKey => ['transfers', 'list'],

  /** One Transfer, as its own page reads it. */
  transfer: (transferId: string): QueryKey => ['transfers', 'detail', transferId],
}
