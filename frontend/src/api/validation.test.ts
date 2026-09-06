import { describe, expect, it } from 'vitest'
import { rejectedFieldsIn } from './validation'
import validationSource from './validation.ts?raw'
import { plainModuleRules } from '../testsupport/plainModule'
import type { ProblemDocument } from './types'

const aRefusal = (errors: ProblemDocument['errors']): ProblemDocument => ({
  type: 'urn:problem:validation-failed',
  title: 'Unprocessable Entity',
  status: 422,
  detail: 'The request was understood and could not be acted on.',
  instance: '/api/accounts',
  errors,
})

describe('the members a refusal named', () => {
  it('gives back each one with what the service said about it', () => {
    const { byField } = rejectedFieldsIn(
      aRefusal([
        { field: 'currency', message: 'must not be null' },
        { field: 'openingBalanceMinorUnits', message: 'must be greater than or equal to 0' },
      ]),
    )

    expect([...byField]).toEqual([
      ['currency', 'must not be null'],
      ['openingBalanceMinorUnits', 'must be greater than or equal to 0'],
    ])
  })

  /**
   * Two constraints on one field are two things to fix, and showing only the first would
   * send an operator round the loop once per constraint.
   */
  it('joins two refusals of the same member rather than losing one', () => {
    const { byField } = rejectedFieldsIn(
      aRefusal([
        { field: 'openingBalanceMinorUnits', message: 'must not be null' },
        { field: 'openingBalanceMinorUnits', message: 'must be greater than or equal to 0' },
      ]),
    )

    expect(byField.get('openingBalanceMinorUnits')).toBe('must not be null, must be greater than or equal to 0')
  })

  it('keeps what the service said about no member in particular', () => {
    const { byField, overall } = rejectedFieldsIn(
      aRefusal([
        { field: null, message: 'the two accounts must differ' },
        { field: 'currency', message: 'must not be null' },
      ]),
    )

    expect(overall).toEqual(['the two accounts must differ'])
    expect([...byField.keys()]).toEqual(['currency'])
  })
})

describe('a failure that named no members', () => {
  it('is empty for a refusal carrying no errors at all', () => {
    const { byField, overall } = rejectedFieldsIn(aRefusal(undefined))

    expect(byField.size).toBe(0)
    expect(overall).toEqual([])
  })

  /**
   * Whatever a failed request produced arrives here, and most of what can arrive is not a
   * problem document — a gateway's HTML, a dropped connection, a thrown `Error`.
   */
  it('is empty for anything that is not a problem document', () => {
    for (const failure of [undefined, null, 'a string', 42, [], new Error('offline'), { errors: 'not a list' }]) {
      expect(rejectedFieldsIn(failure).byField.size).toBe(0)
      expect(rejectedFieldsIn(failure).overall).toEqual([])
    }
  })

  it('skips an entry whose members are not the two the contract promises', () => {
    const { byField, overall } = rejectedFieldsIn({
      errors: [{ field: 'currency' }, { message: 42 }, { field: 7, message: 'nonsense' }, 'not an entry'],
    })

    expect(byField.size).toBe(0)
    expect(overall).toEqual([])
  })
})

/**
 * Design decision 24's rule, asserted rather than trusted. The absence of `type` and
 * `status` from the source is the point: this module says which members were refused and
 * never what the refusal was, which is `problem.ts`'s question and its alone.
 */
describe('the module itself', () => {
  plainModuleRules(validationSource, ['./records'])

  it('reads neither the URN nor the response code, which are another module’s to branch on', () => {
    expect(validationSource).not.toMatch(/\btype\b/)
    expect(validationSource).not.toMatch(/\bstatus\b/)
  })
})
