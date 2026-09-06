import { queryKeys, type QueryKey } from './queryKeys'
import { isRecord } from './records'

/**
 * The whole of what this app does with the event stream: it turns one message into the
 * list of cache entries that message makes stale, and nothing else. Whatever holds the
 * subscription iterates that list and invalidates each key; the query client refetches;
 * the screen renders the API's answer.
 *
 * That is the payoff of a message carrying only a type and a Transfer ID — *the stream
 * carries hints, the endpoint carries truth*. There is nothing here that merges a
 * message into cached state, reconciles it against what is already there, or cares what
 * order two messages arrived in, because a hint has no content to merge.
 *
 * It takes the frame rather than a parsed message, which is what makes a frame that is
 * not JSON this module's problem instead of the subscription's. What that buys, and what
 * the event types below oblige the backend's stream endpoint to emit, is in the
 * [frontend README](../../README.md) and in
 * `docs/design-decisions/36-sse-event-module.md`.
 */

/**
 * The three messages the stream carries, one per way a Transfer can finish.
 *
 * A Transfer leaves `PENDING` exactly once and never moves again, so this is the
 * complete set: there is no message for a Transfer being requested, which is the one
 * state change the operator's own browser already knows about.
 */
export const TRANSFER_EVENT_TYPES = ['TRANSFER_SETTLED', 'TRANSFER_REJECTED', 'TRANSFER_EXPIRED'] as const

export type TransferEventType = (typeof TRANSFER_EVENT_TYPES)[number]

const NOTHING: readonly QueryKey[] = []

/**
 * Reads one message off the stream and says which cache entries it makes stale.
 *
 * All three event types invalidate the same three keys, which looks like a table that
 * has not been written yet and is not one: **every terminal state releases the source
 * Account's reservation**, so a balance moves whether the Transfer settled or was
 * refused, and only settling moves it in the way an operator was hoping for.
 *
 * Anything this app cannot read — an event type added to the backend since this build,
 * a message with no Transfer in it, a frame that is not JSON — yields no keys. Refetching
 * nothing is the harmless failure: the page keeps the state it loaded, and the next
 * message it does understand, or the refetch on the stream reopening, converges it.
 */
export const invalidationsFor = (frame: string): readonly QueryKey[] => {
  const transferId = finishedTransferIn(frame)

  if (transferId === undefined) return NOTHING

  return [queryKeys.transfer(transferId), queryKeys.transfers(), queryKeys.accounts()]
}

const finishedTransferIn = (frame: string): string | undefined => {
  const message = parsedOrNothing(frame)

  if (!isRecord(message) || !isEventType(message.type)) return undefined

  return transferIdForKey(message.transferId)
}

const parsedOrNothing = (frame: string): unknown => {
  try {
    return JSON.parse(frame)
  } catch {
    return undefined
  }
}

// `some`, not `includes`, which does not accept an `unknown` against a literal union.
const isEventType = (type: unknown): type is TransferEventType =>
  TRANSFER_EVENT_TYPES.some((known) => known === type)

/**
 * The Transfer's ID as a query key spells it.
 *
 * The backend's identifier is a whole number and arrives as one; the string form is what
 * the URL, and so the page's own key, is built from.
 */
const transferIdForKey = (transferId: unknown): string | undefined => {
  if (typeof transferId === 'number') return String(transferId)

  return typeof transferId === 'string' && transferId !== '' ? transferId : undefined
}
