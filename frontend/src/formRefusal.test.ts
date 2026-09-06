import { describe, expect, it } from 'vitest'
import refusalSource from './formRefusal.ts?raw'
import { formRefusalIn, messagesUnder } from './formRefusal'
import { plainModuleRules } from './testsupport/plainModule'

const aRefusal = (errors: { field: string | null; message: string }[]) => ({
  type: 'urn:problem:validation-failed',
  errors,
})

/** A form with one field whose wire member is named differently — the case this exists for. */
const FIELD_FOR_MEMBER = { amountMinorUnits: 'amount' } as const

describe('where a refusal of the request lands on a form', () => {
  it('puts a refused wire member against the field that can be corrected', () => {
    const { perField, unattached } = formRefusalIn(
      aRefusal([{ field: 'amountMinorUnits', message: 'must be greater than 0' }]),
      FIELD_FOR_MEMBER,
    )

    expect(perField).toEqual({ amount: 'must be greater than 0' })
    expect(unattached).toEqual([])
  })

  /** Dropping it would leave an operator with a heading and no reason. */
  it('keeps a refusal the form has no field for, under the name the service used', () => {
    const { perField, unattached } = formRefusalIn(
      aRefusal([
        { field: null, message: 'the request was refused as a whole' },
        { field: 'somethingElse', message: 'must be smaller' },
      ]),
      FIELD_FOR_MEMBER,
    )

    expect(perField).toEqual({})
    expect(unattached).toEqual(['the request was refused as a whole', 'somethingElse: must be smaller'])
  })

  /** What a mutation that has not failed carries, and what a gateway's error page is. */
  it('is nothing refused for a failure that named no members, and for no failure at all', () => {
    for (const failure of [null, undefined, new Error('offline'), aRefusal([])]) {
      expect(formRefusalIn(failure, FIELD_FOR_MEMBER)).toEqual({ perField: {}, unattached: [] })
    }
  })
})

describe('what is shown under one field', () => {
  it('reads the message out of whatever the form library kept of an issue', () => {
    expect(messagesUnder([{ message: 'not an amount' }, 'a bare string'], undefined)).toEqual([
      'not an amount',
      'a bare string',
    ])
  })

  /** The current value's verdict first; the server's describes a payload already replaced. */
  it('puts what the schema said ahead of what the service said', () => {
    expect(messagesUnder([{ message: 'not an amount' }], 'must not be null')).toEqual([
      'not an amount',
      'must not be null',
    ])
  })

  it('is empty when neither said anything, which is what leaves the field unmarked', () => {
    expect(messagesUnder([], undefined)).toEqual([])
  })

  it('skips an error carrying no message rather than rendering it as an object', () => {
    expect(messagesUnder([null, 42, {}], undefined)).toEqual([])
  })
})

describe('the module itself', () => {
  plainModuleRules(refusalSource, ['./api/validation'])
})
