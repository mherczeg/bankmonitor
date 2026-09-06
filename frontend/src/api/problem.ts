import type { ProblemType } from './types'

/**
 * The one place that turns a failure of the API into something an operator can read
 * and act on, and the only module that decides whether retrying is worth their time.
 *
 * It branches on the `type` URN and on nothing else — not on the response code beside
 * it. The backend made that URN the sole discriminator because two discriminators
 * drift and one of them eventually lies, and a client that reconciles the two would
 * hand the problem straight back. `urn:problem:request-in-progress` and
 * `urn:problem:idempotency-key-reused` are the pair that makes the rule concrete: the
 * same `409`, opposite advice.
 *
 * `retry.ts` also looks at a failure and also answers a question about retrying. It is
 * a different question — whether the query client should silently ask again, over a
 * transport error that may carry no document at all — and the two are allowed to
 * disagree. See `docs/design-decisions/34-problem-document-module.md`.
 *
 * The copy is written here rather than taken from the document's own `title` and
 * `detail`, which are written for whoever is reading the response and name things an
 * operator cannot act on.
 */

/** What a screen shows about a failure, and what it should tell the operator to do. */
export interface ProblemMessage {
  /** A short line naming what happened, in the words of the screen rather than the API. */
  readonly title: string
  /** A sentence or two saying what it means and what to do next. */
  readonly body: string
  /** Whether making the same request again could succeed. */
  readonly retryable: boolean
}

/**
 * The three refusals that are this app calling the API wrongly rather than the operator
 * entering something wrong. They share their copy because they are one situation to the
 * person reading them, and they keep separate entries because a shared default is how a
 * URN added later would silently inherit advice nobody chose for it.
 *
 * `urn:problem:client-error` is deliberately not one of them, though it looks like it
 * should be — see `docs/design-decisions/34-problem-document-module.md`.
 */
const APP_SENT_SOMETHING_WRONG: ProblemMessage = {
  title: 'This app sent a request the service refused',
  body: 'The refusal is in how the request was formed, not in anything entered on this screen, so there is nothing here to correct. Reloading the page is worth one attempt; if it comes back, it needs reporting.',
  retryable: false,
}

const MESSAGES: Record<ProblemType, ProblemMessage> = {
  'urn:problem:validation-failed': {
    title: 'Some of what was entered was refused',
    body: 'The service checked the values submitted and would not accept all of them. Correct the fields it flagged and submit again.',
    retryable: false,
  },

  'urn:problem:malformed-request': APP_SENT_SOMETHING_WRONG,
  'urn:problem:unsupported-media-type': APP_SENT_SOMETHING_WRONG,
  'urn:problem:method-not-allowed': APP_SENT_SOMETHING_WRONG,

  'urn:problem:not-found': {
    title: 'That is not there',
    body: 'What was asked for does not exist — it may have been removed, or the address may be wrong. Go back to the list and pick it again.',
    retryable: false,
  },

  'urn:problem:request-in-progress': {
    title: 'This request is already being processed',
    body: 'An identical request is still running. Wait a moment and try again — it carries the same Idempotency Key, so it cannot go through twice.',
    retryable: true,
  },

  'urn:problem:idempotency-key-reused': {
    title: 'This request was already used for something else',
    body: 'Its Idempotency Key belongs to a different Transfer that was already requested. Trying again can never succeed. Start again from the form, which takes a key of its own.',
    retryable: false,
  },

  'urn:problem:fx-provider-unavailable': {
    title: 'The exchange rate could not be fetched',
    body: 'The exchange rate provider did not answer, so the amount could not be converted and no money was moved. Try again in a moment.',
    retryable: true,
  },

  'urn:problem:client-error': {
    title: 'The service refused the request',
    body: 'It did not say more precisely why, so there is no advice to give beyond one more attempt. If it comes back the same way, it needs reporting.',
    retryable: true,
  },

  'urn:problem:internal-error': {
    title: 'The service failed to answer',
    body: 'Something broke on the server, and nothing about the request needs changing. Try again in a few moments; if it keeps failing, it needs reporting.',
    retryable: true,
  },
}

/**
 * What a failure this app has no wording for is shown as.
 *
 * It advises retrying, which is the safe way to be wrong: a request made twice is a
 * request the Idempotency Key already covers, whereas advising against a retry strands
 * an operator on a failure that would have cleared. Most of what reaches here is not a
 * problem document at all — a gateway's own error page, a dropped connection — and
 * those clear.
 */
export const UNRECOGNISED_PROBLEM: ProblemMessage = {
  title: 'Something went wrong',
  body: 'The service failed in a way this app does not recognise, so there is no more specific advice to give. Trying again is safe; if it keeps failing, it needs reporting.',
  retryable: true,
}

/**
 * Every URN this module has wording for, at runtime.
 *
 * The `ProblemType` union is generated and so exists only at compile time, while
 * recognising an arriving URN is a runtime question. Deriving the list from the table
 * above is what keeps the two from being written twice and disagreeing.
 */
export const PROBLEM_TYPES = Object.keys(MESSAGES) as readonly ProblemType[]

/**
 * Reads whatever a failed request produced and says what to show for it.
 *
 * The argument is `unknown` because that is what a parsed response body is: casting it
 * to `ProblemDocument` at the call site would assert a shape nobody checked, which is
 * the hand-written trust the generated types exist to delete. Anything that is not a
 * document with a URN this app knows — a gateway's HTML, a thrown `Error`, a URN added
 * to the backend since this build — becomes {@link UNRECOGNISED_PROBLEM}.
 */
export const problemToMessage = (failure: unknown): ProblemMessage => {
  const type = recognisedTypeOf(failure)

  return type === undefined ? UNRECOGNISED_PROBLEM : MESSAGES[type]
}

const recognisedTypeOf = (failure: unknown): ProblemType | undefined => {
  if (typeof failure !== 'object' || failure === null) return undefined

  const { type } = failure as { type?: unknown }

  // Object.hasOwn, not `in`: `{ type: 'toString' }` would otherwise be recognised and
  // hand back a function off the prototype chain.
  return typeof type === 'string' && Object.hasOwn(MESSAGES, type) ? (type as ProblemType) : undefined
}
