import type { Page } from '@playwright/test'

import type { Account, ApiPath, ProblemDocument, Transfer } from '../../src/api/types'

/**
 * The network a browser spec runs against: one route handler over `/api`, and a table of
 * answers the spec rewrites as it goes.
 *
 * **Route-level mocking rather than a service worker.** No second build mode, nothing to
 * register before the app boots, and the answers live in the spec that depends on them
 * instead of in a shared fixture file nobody can safely change.
 *
 * **One handler over a mutable table, rather than a `page.route` call per answer.** The
 * central move of a live-update spec is that *truth changes mid-test* — the same endpoint
 * answers differently after an event than before it — and re-registering a route to do
 * that means reasoning about which of two handlers Playwright reaches first and when an
 * `unroute` has taken effect. Here, scripting the same path twice is one map entry being
 * overwritten, and the request after it sees the new answer.
 *
 * Every path is annotated {@link ApiPath}, so it is a key of the generated OpenAPI
 * document and every body is a generated shape: a mock the backend would never send, or a
 * path it no longer publishes, fails `tsc` rather than passing a spec.
 */

/**
 * Every request the app makes to the API, whatever origin the page is served from.
 *
 * A predicate rather than the obvious glob, which matches `api` anywhere in a URL and so
 * also swallows the app's own modules under `src/api/`. Answered from this table instead
 * of by the dev server — as a `404`, or as JSON that fails the browser's MIME check for a
 * module script — the page never boots, and every spec fails on an element that is missing
 * for a reason nothing in it names.
 */
const anApiRequest = (url: URL): boolean => url.pathname.startsWith('/api/')

const ACCOUNTS: ApiPath = '/api/accounts'

const TRANSFERS: ApiPath = '/api/transfers'

const TRANSFER: ApiPath = '/api/transfers/{id}'

/** The methods this API answers on. */
export type HttpMethod = 'GET' | 'POST'

/**
 * What a templated path's `{…}` placeholders stand for, when a spec names one.
 *
 * A path is spelled the way the OpenAPI document spells it — `/api/transfers/{id}` — so
 * that {@link ApiPath} can check it, and the URL a request actually carries is built from
 * that spelling and these. Filing an answer under the template itself would be an answer
 * no request could ever match, and the spec would get the unscripted `404` instead.
 */
export type PathParams = Readonly<Record<string, string | number>>

/** The answers scripted for one page, and what the page has asked for so far. */
export type ScriptedApi = {
  /**
   * What `GET /api/accounts` answers with, from the next request onwards. Calling it
   * again replaces the answer, which is how a spec makes truth change mid-test.
   */
  accounts: (accounts: readonly Account[]) => void

  /**
   * What `POST /api/accounts` answers with — the Account the service opened, as a `201`.
   *
   * The list it joins is scripted separately, on purpose: the screen learns about the new
   * Account by refetching, so a spec that wants to see the row appear scripts
   * {@link ScriptedApi.accounts} again after this. Answering both from one call would
   * hide the invalidation the screen depends on.
   */
  opensAccount: (account: Account) => void

  /** What `GET /api/transfers` answers with — the Transactions list. */
  transfers: (transfers: readonly Transfer[]) => void

  /**
   * What `POST /api/transfers` answers with — the `PENDING` Transfer the service opened,
   * as the `201` the endpoint sends.
   *
   * The Transfer's own page is scripted separately with {@link ScriptedApi.transfer}, on
   * {@link ScriptedApi.opensAccount}'s reasoning: the screen the form navigates to fetches
   * the Transfer for itself, so a spec that answered both from one call would hide the
   * fetch it is there to make.
   */
  requestsTransfer: (transfer: Transfer) => void

  /**
   * What `GET /api/transfers/{id}` answers with, filed under the ID the Transfer itself
   * carries. Taking the whole Transfer rather than an ID beside it is what stops a spec
   * scripting a Transfer at an address that reports a different one.
   */
  transfer: (transfer: Transfer) => void

  /**
   * What a path answers with instead of succeeding. The document carries its own status,
   * because a problem document that disagreed with the status it arrived under would be
   * a shape the backend cannot produce.
   */
  refuses: (method: HttpMethod, path: ApiPath, problem: ProblemDocument, params?: PathParams) => void

  /**
   * How many times the browser has asked for a path since the spec began — the assertion
   * that a re-render came from a refetch rather than from something the page did anyway.
   */
  timesAsked: (method: HttpMethod, path: ApiPath, params?: PathParams) => number

  /**
   * The body of the last request the browser sent to a path, parsed.
   *
   * It is what a form's conversion is asserted through: an amount is only a Minor Unit
   * count once it has left the browser as one, and a screen rendering `100.50` correctly
   * proves nothing about the `10050` it was supposed to send. `undefined` where the path
   * has not been asked for, or where the request carried no body.
   */
  bodySent: (method: HttpMethod, path: ApiPath, params?: PathParams) => unknown

  /**
   * The headers of the last request the browser sent to a path, **named in lower case** —
   * Playwright normalises them, so an Idempotency Key is read as `['x-idempotency-key']`.
   *
   * It is what makes the idempotency rules observable at all: whether two attempts went out
   * under one key, and whether a corrected payload went out under a new one, is a fact
   * about the wire and about nothing the screen renders. `{}` where the path has not been
   * asked for.
   */
  headersSent: (method: HttpMethod, path: ApiPath, params?: PathParams) => Readonly<Record<string, string>>
}

export const scriptApi = async (page: Page): Promise<ScriptedApi> => {
  const answers = new Map<string, Answer>()
  const asked = new Map<string, number>()
  const sent = new Map<string, unknown>()
  const headers = new Map<string, Record<string, string>>()

  await page.route(anApiRequest, async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const asking = requestFor(request.method(), path)

    asked.set(asking, (asked.get(asking) ?? 0) + 1)
    sent.set(asking, request.postDataJSON())
    headers.set(asking, await request.allHeaders())

    await route.fulfill(answers.get(asking) ?? unscripted(request.method(), path))
  })

  return {
    accounts: (accounts) => answers.set(requestFor('GET', ACCOUNTS), succeeds(accounts)),
    opensAccount: (account) => answers.set(requestFor('POST', ACCOUNTS), created(account)),
    transfers: (transfers) => answers.set(requestFor('GET', TRANSFERS), succeeds(transfers)),
    requestsTransfer: (transfer) => answers.set(requestFor('POST', TRANSFERS), created(transfer)),
    transfer: (transfer) =>
      answers.set(requestFor('GET', urlFor(TRANSFER, { id: transfer.id })), succeeds(transfer)),
    refuses: (method, path, problem, params) =>
      answers.set(requestFor(method, urlFor(path, params)), fails(problem)),
    timesAsked: (method, path, params) => asked.get(requestFor(method, urlFor(path, params))) ?? 0,
    bodySent: (method, path, params) => sent.get(requestFor(method, urlFor(path, params))),
    headersSent: (method, path, params) => headers.get(requestFor(method, urlFor(path, params))) ?? {},
  }
}

/** What one entry in the table is filed under: a method and a path together name a request. */
const requestFor = (method: string, path: string): string => `${method} ${path}`

/**
 * The URL a request carries, from the path as the OpenAPI document spells it.
 *
 * A placeholder the caller left unnamed stays in the URL rather than becoming `undefined`,
 * so the answer is filed somewhere no request matches and the spec reports the unscripted
 * `404` — which names the omission, where a `/api/transfers/undefined` would not.
 */
const urlFor = (path: ApiPath, params: PathParams = {}): string =>
  path.replace(/\{(\w+)\}/g, (placeholder, name: string) =>
    name in params ? String(params[name]) : placeholder,
  )

type Answer = { status: number; contentType: string; body: string }

const succeeds = (body: unknown): Answer => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify(body),
})

/** The `201` the API answers a `POST` that made something with. */
const created = (body: unknown): Answer => ({ ...succeeds(body), status: 201 })

const fails = (problem: ProblemDocument): Answer => ({
  status: problem.status,
  contentType: 'application/problem+json',
  body: JSON.stringify(problem),
})

/**
 * A path the spec forgot to script answers the way the backend answers an address that
 * names nothing, and says so in the one member a person reads. Letting the request through
 * to the real network instead would make a spec pass or fail on whether a backend happened
 * to be running.
 */
const unscripted = (method: string, path: string): Answer =>
  fails({
    type: 'urn:problem:not-found',
    title: 'Not Found',
    status: 404,
    detail: `No answer is scripted for ${requestFor(method, path)} in this spec.`,
    instance: path,
  })
