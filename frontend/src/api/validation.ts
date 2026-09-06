import { isRecord } from './records'

/**
 * The one place that reads a refusal's `errors` member — what the service said about
 * each part of the request it named, so a form can put the sentence beside the field it
 * is about instead of showing one line above everything.
 *
 * `problem.ts` answers the other half of the same failure: what happened, and whether
 * asking again would help. It branches on the URN and this module reads neither that
 * nor the response code, which is why a form shows both — the URN chooses the heading,
 * and this chooses what appears under each field. Reading the URN here would grow a
 * second discriminator over the same document, which is exactly what the backend made
 * one URN to prevent.
 *
 * The messages are the service's own words, verbatim. They are the field-level detail
 * the document exists to carry, and rewording them here would mean holding a copy of
 * every constraint the backend declares.
 */

/** What a refusal said about the request, sorted into the parts it named and the rest. */
export interface RejectedFields {
  /** One message per member the document named, in the order the document listed them. */
  readonly byField: ReadonlyMap<string, string>

  /** What was said without naming a member — a rule holding across two of them, say. */
  readonly overall: readonly string[]
}

/** One entry of the `errors` member, once it has been checked rather than assumed. */
interface Rejection {
  readonly field: string | null
  readonly message: string
}

/**
 * Reads whatever a failed request produced and says which parts of it were refused.
 *
 * The argument is `unknown` for `problem.ts`'s reason: a parsed response body is
 * `unknown`, and naming a shape at the call site would assert one nobody checked.
 * Anything that is not a document carrying `errors` — a gateway's HTML, a thrown
 * `Error`, a refusal that named no members — reads as nothing refused, which leaves the
 * form showing the heading alone rather than showing nothing.
 */
export const rejectedFieldsIn = (failure: unknown): RejectedFields => {
  const byField = new Map<string, string>()
  const overall: string[] = []

  for (const { field, message } of rejectionsIn(failure)) {
    if (field === null) overall.push(message)
    else byField.set(field, alongside(byField.get(field), message))
  }

  return { byField, overall }
}

/**
 * Two constraints on one member are two things to correct, so the second is added to the
 * first rather than replacing it — otherwise an operator fixes one and is told about the
 * next on the round trip after.
 */
const alongside = (said: string | undefined, message: string): string =>
  said === undefined ? message : `${said}, ${message}`

const rejectionsIn = (failure: unknown): Rejection[] => {
  if (!isRecord(failure) || !Array.isArray(failure.errors)) return []

  return failure.errors.filter(isRejection)
}

/**
 * Both members are checked rather than assumed. What arrives here has been through
 * `response.json()` and nothing else, so an entry shaped otherwise is a `[object Object]`
 * rendered against a field if it is not dropped here.
 */
const isRejection = (entry: unknown): entry is Rejection =>
  isRecord(entry) &&
  (typeof entry.field === 'string' || entry.field === null) &&
  typeof entry.message === 'string'
