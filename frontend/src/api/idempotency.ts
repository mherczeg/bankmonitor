import { isRecord } from './records'

/**
 * The one place in the frontend that decides what an Idempotency Key identifies, and
 * so the only place a key is ever minted.
 *
 * A key names what the operator meant to do, not an HTTP attempt at it, and an intent
 * is its payload: an unchanged payload reads back the held key, a changed one mints a
 * new key. Keys are version 4 UUIDs, which is all the backend accepts, and
 * `crypto.randomUUID` mints them, so the app needs a secure context.
 *
 * What either half costs to get wrong is in the [frontend README](../../README.md) and
 * in `docs/design-decisions/35-idempotency-key-module.md`.
 */

/**
 * The Idempotency Keys one form mints over its life, one per intent.
 *
 * Held for the lifetime of the screen — in a ref, not in state, since a new key is
 * never something to re-render over.
 */
export interface IdempotencyKeys {
  /**
   * The key this intent is submitted under, stable for as long as the intent is.
   *
   * Safe to call during a render: it returns the held key whenever the payload
   * matches, so repeated reads of one intent neither mint nor change anything. The
   * payload passed here has to be the one the attempt actually sends — a retry reading
   * a form the operator has since edited would mint a key mid-flight.
   */
  keyFor(intent: object): string

  /**
   * Records that the intent went through, so the next read starts a new one.
   *
   * There is deliberately no counterpart for a failure: a module that cannot be told
   * about one cannot reset on one.
   */
  succeeded(): void
}

/** Opens a form's supply of Idempotency Keys. Nothing is minted until one is read. */
export const startIntent = (): IdempotencyKeys => {
  let held: { readonly intent: string; readonly key: string } | undefined

  return {
    keyFor(intent) {
      const canonical = canonicalise(intent)

      if (held?.intent !== canonical) held = { intent: canonical, key: crypto.randomUUID() }

      return held.key
    },

    succeeded() {
      held = undefined
    },
  }
}

/**
 * Renders an intent as a string that changes when its values do and at no other time.
 *
 * Member order is normalised because the comparison must never report a change that did
 * not happen: a false "changed" mints a new key mid-retry and reopens the double charge,
 * where a false "unchanged" only earns the refusal the operator would have got anyway.
 * Plain `JSON.stringify` preserves the order the members were written in, so an intent
 * rebuilt member by member would look like a different intent.
 */
const canonicalise = (intent: unknown): string =>
  JSON.stringify(intent, (_name, member: unknown) =>
    isRecord(member) ? sortedByMemberName(member) : member,
  )

// Not `localeCompare`, whose ordering depends on the runtime's locale data.
const sortedByMemberName = (member: Record<string, unknown>): Record<string, unknown> =>
  Object.fromEntries(Object.entries(member).sort(([one], [other]) => (one < other ? -1 : 1)))
