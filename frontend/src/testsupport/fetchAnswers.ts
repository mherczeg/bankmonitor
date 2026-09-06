/**
 * One call run against a `fetch` that answers it, and a record of how it asked.
 *
 * Every module under `api/` is a `fetch` and a refusal rule, so what its unit tests need
 * is the same three things: an answer to run the call against, the path it asked for, and
 * the request it put on the wire — the last of which is where a header or a converted
 * amount is asserted.
 *
 * Written out in `accounts.test.ts` and again in `transfers.test.ts` before it was moved
 * here, on the precedent ticket 35 set with `plainModuleRules`.
 */

let path: string | undefined

let sent: RequestInit | undefined

/** The path the call {@link whileFetchAnswers} last ran asked for, and no earlier one. */
export const requestedPath = (): string | undefined => path

/** How that call asked — the method, the headers and the body it put on the wire. */
export const requestSent = (): RequestInit | undefined => sent

/**
 * Runs one call with `globalThis.fetch` stubbed to answer it, putting the real one back
 * afterwards so an assertion that fails mid-call cannot leave the rest of the suite talking
 * to a stub.
 */
export const whileFetchAnswers = async <T>(
  status: number,
  body: unknown,
  call: () => Promise<T>,
): Promise<T> => {
  const realFetch = globalThis.fetch

  path = undefined
  sent = undefined

  globalThis.fetch = (asked: RequestInfo | URL, init?: RequestInit) => {
    path = String(asked)
    sent = init

    return Promise.resolve({ ok: status < 400, json: () => Promise.resolve(body) } as Response)
  }

  try {
    return await call()
  } finally {
    globalThis.fetch = realFetch
  }
}
