import { describe, expect, it } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { invalidationsFor, TRANSFER_EVENT_TYPES } from './events'
import { queryKeys } from './queryKeys'
import eventsSource from './events.ts?raw'
import queryKeysSource from './queryKeys.ts?raw'
import { plainModuleRules } from '../testsupport/plainModule'

/** What the backend puts on the wire: one frame of JSON carrying a type and an ID. */
const streamed = (message: object): string => JSON.stringify(message)

const THE_THREE_KEYS = [queryKeys.transfer('7'), queryKeys.transfers(), queryKeys.accounts()]

describe('what a Transfer reaching a terminal state invalidates', () => {
  it('settling: that Transfer, the transfers list and the accounts list', () => {
    expect(invalidationsFor(streamed({ type: 'TRANSFER_SETTLED', transferId: 7 }))).toEqual(THE_THREE_KEYS)
  })

  it('rejection: the same three, because releasing the reservation moves a balance too', () => {
    expect(invalidationsFor(streamed({ type: 'TRANSFER_REJECTED', transferId: 7 }))).toEqual(THE_THREE_KEYS)
  })

  it('expiry: the same three, for the same reason', () => {
    expect(invalidationsFor(streamed({ type: 'TRANSFER_EXPIRED', transferId: 7 }))).toEqual(THE_THREE_KEYS)
  })

  it('and those are the three names, which nothing generates and ticket 30 has to emit', () => {
    expect(TRANSFER_EVENT_TYPES).toEqual(['TRANSFER_SETTLED', 'TRANSFER_REJECTED', 'TRANSFER_EXPIRED'])
  })
})

describe('the Transfer a message names', () => {
  it('is keyed as the URL spells it, so the page and the event agree on one key', () => {
    const [transfer] = invalidationsFor(streamed({ type: 'TRANSFER_SETTLED', transferId: 7 }))

    expect(transfer).toEqual(queryKeys.transfer('7'))
  })

  it('is taken as it arrives when the backend sends the ID as a name', () => {
    const [transfer] = invalidationsFor(streamed({ type: 'TRANSFER_SETTLED', transferId: '7' }))

    expect(transfer).toEqual(queryKeys.transfer('7'))
  })
})

describe('a message this app can make nothing of', () => {
  const nothing: never[] = []

  it('an event type added to the backend since this build', () => {
    expect(invalidationsFor(streamed({ type: 'TRANSFER_AMENDED', transferId: 7 }))).toEqual(nothing)
  })

  it('an event type naming a member every object has', () => {
    expect(invalidationsFor(streamed({ type: 'toString', transferId: 7 }))).toEqual(nothing)
  })

  it('a known event type carrying no Transfer', () => {
    expect(invalidationsFor(streamed({ type: 'TRANSFER_SETTLED' }))).toEqual(nothing)
  })

  it('a Transfer ID that is neither a number nor a name', () => {
    expect(invalidationsFor(streamed({ type: 'TRANSFER_SETTLED', transferId: null }))).toEqual(nothing)
  })

  it('an empty Transfer ID, which would key a query nothing can answer', () => {
    expect(invalidationsFor(streamed({ type: 'TRANSFER_SETTLED', transferId: '' }))).toEqual(nothing)
  })

  it('JSON that is not an object at all', () => {
    expect([invalidationsFor('7'), invalidationsFor('null'), invalidationsFor('[]')]).toEqual([
      nothing,
      nothing,
      nothing,
    ])
  })

  it('a frame that is not JSON, which a proxy or a stray keep-alive can deliver', () => {
    expect(invalidationsFor(': keep-alive')).toEqual(nothing)
  })

  it('an empty frame', () => {
    expect(invalidationsFor('')).toEqual(nothing)
  })
})

describe('what those keys reach in the query cache', () => {
  const seeded = (): QueryClient => {
    const client = new QueryClient()

    const loaded = [queryKeys.transfer('7'), queryKeys.transfer('8'), queryKeys.transfers(), queryKeys.accounts()]
    for (const key of loaded) client.setQueryData(key, 'as it was first loaded')

    return client
  }

  const afterSettling = async (): Promise<QueryClient> => {
    const client = seeded()

    await Promise.all(
      invalidationsFor(streamed({ type: 'TRANSFER_SETTLED', transferId: 7 })).map((queryKey) =>
        client.invalidateQueries({ queryKey }),
      ),
    )

    return client
  }

  const wasInvalidated = (client: QueryClient, key: readonly unknown[]): boolean =>
    client.getQueryState(key)?.isInvalidated === true

  it('the Transfer the message named, the transfers list and the accounts list', async () => {
    const client = await afterSettling()

    expect([
      wasInvalidated(client, queryKeys.transfer('7')),
      wasInvalidated(client, queryKeys.transfers()),
      wasInvalidated(client, queryKeys.accounts()),
    ]).toEqual([true, true, true])
  })

  it('and no other Transfer, because the list key is not a prefix of a Transfer key', async () => {
    const client = await afterSettling()

    expect(wasInvalidated(client, queryKeys.transfer('8'))).toBe(false)
  })
})

/**
 * Design decision 24's rule, asserted rather than trusted — the reasoning is in
 * `docs/design-decisions/36-sse-event-module.md`.
 *
 * The rule is transitive and the assertion is not: a module that reads only `events.ts`
 * would still pass with the query library imported one level down, into `queryKeys.ts`,
 * which is the one place it would be tempting to reach for `QueryKey` from.
 */
describe('the modules themselves', () => {
  describe('events.ts', () => {
    plainModuleRules(eventsSource, ['./queryKeys', './records'])
  })

  describe('queryKeys.ts', () => {
    plainModuleRules(queryKeysSource, [])
  })
})
