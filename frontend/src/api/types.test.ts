import { describe, expect, it } from 'vitest'
import type { Account, ProblemDocument } from './types'

/**
 * The demonstration ticket 32 exists for: a shape written by hand that the backend
 * does not produce does not compile.
 *
 * Every `@ts-expect-error` below is an assertion in both directions. TypeScript
 * reports an unused directive, so if a mistake here ever stops being one — the
 * backend widening `currency` to a plain string, say, or dropping a member from
 * the response — `npm run build` fails on this file. Which is the whole point:
 * the browser tests mock the network, so without this the suite proves only that
 * the app handles shapes its own author invented.
 */

const anAccount: Account = {
  id: 1,
  currency: 'EUR',
  balanceMinorUnits: 10_050,
  reservedAmountMinorUnits: 50,
  availableBalanceMinorUnits: 10_000,
}

const aProblem: ProblemDocument = {
  type: 'urn:problem:validation-failed',
  title: 'Bad Request',
  status: 400,
  detail: 'Invalid request content.',
  instance: '/api/accounts',
  errors: [{ field: 'currency', message: 'must be one of: EUR, USD, HUF' }],
}

describe('a hand-written shape the backend does not produce', () => {
  it('is refused when an amount arrives as a string', () => {
    // @ts-expect-error every amount is a whole count of Minor Units, and a mock copied
    // out of a captured response by hand is where the quotes come from
    const drifted: Account = { ...anAccount, balanceMinorUnits: '10050' }

    expect(drifted.balanceMinorUnits).toBe('10050')
  })

  it('is refused when a member the backend always sends is left out', () => {
    // @ts-expect-error the API sends all five, so a fixture missing one describes a
    // response that cannot arrive
    const drifted: Account = {
      id: 1,
      currency: 'EUR',
      reservedAmountMinorUnits: 50,
      availableBalanceMinorUnits: 10_000,
    }

    expect(drifted.balanceMinorUnits).toBeUndefined()
  })

  it('is refused when the currency is one this service does not quote', () => {
    // @ts-expect-error the backend declares three denominations and GBP is not among them
    const drifted: Account = { ...anAccount, currency: 'GBP' }

    expect(drifted.currency).toBe('GBP')
  })
})

describe('the problem-type vocabulary', () => {
  it('refuses a URN this API never emits', () => {
    // @ts-expect-error a client inventing a URN is the failure the shared vocabulary
    // of design decision 18 exists to prevent
    const drifted: ProblemDocument = { ...aProblem, type: 'urn:problem:teapot' }

    expect(drifted.type).toBe('urn:problem:teapot')
  })
})
