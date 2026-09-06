import { describe, expect, it } from 'vitest'
import type { Account } from './api/types'
import { serverRefusalIn, sourceCurrencyIn, transferSchemaFor } from './transferSchema'
import schemaSource from './transferSchema.ts?raw'
import { plainModuleRules } from './testsupport/plainModule'

/** An Account as the list screen's endpoint reports it, with only what this schema reads named. */
const anAccount = (id: number, currency: Account['currency']): Account => ({
  id,
  currency,
  balanceMinorUnits: 100_00,
  reservedAmountMinorUnits: 0,
  availableBalanceMinorUnits: 100_00,
})

/** Two Accounts an operator can pick between, in two different Currencies. */
const ACCOUNTS = [anAccount(1, 'EUR'), anAccount(2, 'EUR'), anAccount(3, 'HUF')]

/** What the form holds: two Accounts named the way a `<select>` names them, and a typed decimal. */
const aForm = (fromAccountId: string, toAccountId: string, amount: string) => ({
  fromAccountId,
  toAccountId,
  amount,
})

const schema = transferSchemaFor(ACCOUNTS)

/** The message the schema attached to a field, or what it did instead of attaching one. */
const refusalOn = (field: string, form: ReturnType<typeof aForm>): string => {
  const result = schema.safeParse(form)

  if (result.success) return `accepted as ${JSON.stringify(result.data)}`

  const issue = result.error.issues.find(({ path }) => path.join('.') === field)

  return issue?.message ?? `refused, but nothing was said about ${field}`
}

describe('reading a filled-in form', () => {
  it('hands back the request the API takes, with the amount already in Minor Units', () => {
    expect(schema.parse(aForm('1', '2', '25.50'))).toEqual({
      fromAccountId: 1,
      toAccountId: 2,
      amountMinorUnits: 25_50,
    })
  })

  /** The identifiers are numbers on the wire and strings in a `<select>`, and this is the walk back. */
  it('sends the Accounts as the whole numbers the API names them by', () => {
    const { fromAccountId, toAccountId } = schema.parse(aForm('3', '1', '2500'))

    expect([fromAccountId, toAccountId]).toEqual([3, 1])
  })
})

/**
 * The rule that makes this schema a function of the accounts list: the same string is an
 * amount out of one Account and fillér that do not exist out of another, and nothing on
 * the form says which — the source Account does.
 */
describe('the decimal places the source Account is written with', () => {
  it('accepts two places out of an Account holding a Currency that has them', () => {
    expect(schema.safeParse(aForm('1', '2', '100.50')).success).toBe(true)
  })

  it('refuses the same string out of an Account holding a Currency that has none', () => {
    expect(refusalOn('amount', aForm('3', '1', '100.50'))).toBe(
      'HUF is written without decimal places, so its amounts are whole numbers.',
    )
  })

  it('refuses more places than even a decimal Currency has', () => {
    expect(refusalOn('amount', aForm('1', '2', '100.505'))).toBe('EUR is written with 2 decimal places.')
  })

  /** The example is the formatter's own output, so it cannot describe a form it would not accept. */
  it('shows the shape it wanted, in the Currency the source Account holds', () => {
    expect(refusalOn('amount', aForm('1', '2', 'a lot'))).toBe('Enter an amount in figures, like 100.50.')
    expect(refusalOn('amount', aForm('3', '1', 'a lot'))).toBe('Enter an amount in figures, like 10050.')
  })
})

/**
 * Ticket 39 moved this rule out of `parseAmount`, which now reads zero as the amount it
 * is. An Account may be opened with nothing in it; a Transfer of nothing is not a Transfer,
 * and this form is where that is said.
 */
describe('an amount that is not one to move', () => {
  it('refuses zero, which the parser reads as an amount and this form does not', () => {
    expect(refusalOn('amount', aForm('1', '2', '0'))).toBe('A Transfer has to move more than nothing.')
    expect(refusalOn('amount', aForm('1', '2', '0.00'))).toBe('A Transfer has to move more than nothing.')
  })

  it('refuses a negative, and says what an operator meant to do instead', () => {
    expect(refusalOn('amount', aForm('1', '2', '-25.50'))).toBe(
      'A Transfer cannot move a negative amount. Swap the two Accounts to send it the other way.',
    )
  })

  it('asks for an amount when the field is empty', () => {
    expect(refusalOn('amount', aForm('1', '2', ''))).toBe('Enter the amount to transfer.')
    expect(refusalOn('amount', aForm('1', '2', '   '))).toBe('Enter the amount to transfer.')
  })

  it('refuses an amount too large to count exactly', () => {
    expect(refusalOn('amount', aForm('3', '1', '99999999999999999'))).toBe(
      'That is larger than this service can count exactly. Enter a smaller amount.',
    )
  })
})

/**
 * A cross-field rule, which is why it lives on the object rather than on either side: no
 * `<select>` can judge it, since either one alone is a perfectly good Account.
 */
describe('the two Accounts', () => {
  it('refuses a Transfer from an Account to itself, against the side that would be changed', () => {
    expect(refusalOn('toAccountId', aForm('1', '1', '25.50'))).toBe(
      'A Transfer moves money between two different Accounts.',
    )
  })

  it('asks for each side that has not been chosen', () => {
    expect(refusalOn('fromAccountId', aForm('', '2', '25.50'))).toBe('Choose the Account the money leaves.')
    expect(refusalOn('toAccountId', aForm('1', '', '25.50'))).toBe('Choose the Account the money arrives at.')
  })

  /** The list this schema was built over can be older than the Accounts the service holds. */
  it('refuses an Account the list it was built over does not hold', () => {
    expect(refusalOn('fromAccountId', aForm('900', '2', '25.50'))).toBe(
      'That is not an Account this service holds. Reload the page and choose again.',
    )
    expect(refusalOn('toAccountId', aForm('1', '900', '25.50'))).toBe(
      'That is not an Account this service holds. Reload the page and choose again.',
    )
  })

  /**
   * Every rule at once, which is what a first press on an untouched form is spent on. The
   * amount is judged too, because the source it is denominated by was chosen.
   */
  it('says everything that is wrong rather than the first thing', () => {
    const refused = schema.safeParse(aForm('1', '1', '100.505'))

    expect(refused.success).toBe(false)
    expect(refused.error?.issues.map(({ path }) => path.join('.')).sort()).toEqual(['amount', 'toAccountId'])
  })

  /** With no source there is no Currency, so there is nothing to judge the amount against. */
  it('says nothing about the amount while the source it is denominated by is unchosen', () => {
    expect(refusalOn('amount', aForm('', '2', '100.505'))).toBe(
      'refused, but nothing was said about amount',
    )
  })
})

describe('the Currency the amount is denominated in', () => {
  it('is the source Account’s, which is what the field is adorned with', () => {
    expect(sourceCurrencyIn(ACCOUNTS, '3')).toBe('HUF')
    expect(sourceCurrencyIn(ACCOUNTS, '1')).toBe('EUR')
  })

  it('is nothing at all until a source is chosen, or for one the list does not hold', () => {
    expect(sourceCurrencyIn(ACCOUNTS, '')).toBeUndefined()
    expect(sourceCurrencyIn(ACCOUNTS, '900')).toBeUndefined()
  })
})

const aRefusal = (errors: { field: string | null; message: string }[]) => ({
  type: 'urn:problem:validation-failed',
  errors,
})

describe('where a refusal of the request lands on this form', () => {
  /** The wire names the unit and the field holds the decimal, so the two differ here too. */
  it('walks the refused amount back to the field that can be corrected', () => {
    expect(serverRefusalIn(aRefusal([{ field: 'amountMinorUnits', message: 'must be greater than 0' }]))
      .perField).toEqual({ amount: 'must be greater than 0' })
  })

  it('passes the two Accounts through under the names the form shares with the wire', () => {
    expect(
      serverRefusalIn(
        aRefusal([
          { field: 'fromAccountId', message: 'must not be null' },
          { field: 'toAccountId', message: 'must not be null' },
        ]),
      ).perField,
    ).toEqual({ fromAccountId: 'must not be null', toAccountId: 'must not be null' })
  })
})

/**
 * Design decision 24's rule: the form's rules, including the one that follows the source
 * Account, are testable without a form being rendered.
 */
describe('the module itself', () => {
  plainModuleRules(schemaSource, ['zod', './api/types', './formRefusal', './money'])
})
