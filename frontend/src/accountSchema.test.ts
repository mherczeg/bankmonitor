import { describe, expect, it } from 'vitest'
import { newAccountSchema } from './accountSchema'
import schemaSource from './accountSchema.ts?raw'
import { plainModuleRules } from './testsupport/plainModule'

/** What the form holds before anything is converted: a Currency, and a typed decimal string. */
const aForm = (currency: string, openingBalance: string) => ({ currency, openingBalance })

/** The message the schema attached to a member, or what it did instead of attaching one. */
const refusalOn = (member: string, currency: string, openingBalance: string): string => {
  const result = newAccountSchema.safeParse(aForm(currency, openingBalance))

  if (result.success) return `accepted as ${JSON.stringify(result.data)}`

  const issue = result.error.issues.find(({ path }) => path.join('.') === member)

  return issue?.message ?? `refused, but nothing was said about ${member}`
}

describe('reading a filled-in form', () => {
  it('hands back the request the API takes, with the amount already in Minor Units', () => {
    expect(newAccountSchema.parse(aForm('EUR', '100.50'))).toEqual({
      currency: 'EUR',
      openingBalanceMinorUnits: 10_050,
    })
  })

  it('reads the forint as the whole number it is written as', () => {
    expect(newAccountSchema.parse(aForm('HUF', '10050'))).toEqual({
      currency: 'HUF',
      openingBalanceMinorUnits: 10_050,
    })
  })

  /** Ticket 09 allows it on purpose: an Account with nothing in it is one an operator may want. */
  it('opens an Account with nothing in it', () => {
    expect(newAccountSchema.parse(aForm('EUR', '0'))).toEqual({
      currency: 'EUR',
      openingBalanceMinorUnits: 0,
    })
  })

  it('ignores whitespace around the amount', () => {
    expect(newAccountSchema.parse(aForm('USD', '  12.34  '))).toEqual({
      currency: 'USD',
      openingBalanceMinorUnits: 1_234,
    })
  })
})

/**
 * The rule that makes the Currency a field rather than a setting: the same string is a
 * valid amount in one Currency and a refusal in another, so a schema that judged the
 * amount on its own would accept fillér that do not exist.
 */
describe('the decimal places the chosen Currency is written with', () => {
  it('accepts two places in a Currency that has them', () => {
    expect(newAccountSchema.safeParse(aForm('EUR', '100.50')).success).toBe(true)
    expect(newAccountSchema.safeParse(aForm('USD', '100.50')).success).toBe(true)
  })

  it('refuses the same string in a Currency that has none', () => {
    expect(refusalOn('openingBalance', 'HUF', '100.50')).toBe(
      'HUF is written without decimal places, so its amounts are whole numbers.',
    )
  })

  it('refuses more places than even a decimal Currency has', () => {
    expect(refusalOn('openingBalance', 'EUR', '100.505')).toBe('EUR is written with 2 decimal places.')
  })
})

describe('an amount that is not one', () => {
  it('asks for an amount when the field is empty', () => {
    expect(refusalOn('openingBalance', 'EUR', '')).toBe('Enter the amount this Account opens with.')
    expect(refusalOn('openingBalance', 'EUR', '   ')).toBe('Enter the amount this Account opens with.')
  })

  /** The example is the formatter's own output, so it cannot describe a form it would not produce. */
  it('shows the shape it wanted, in the Currency that was chosen', () => {
    expect(refusalOn('openingBalance', 'EUR', 'a lot')).toBe('Enter an amount in figures, like 100.50.')
    expect(refusalOn('openingBalance', 'HUF', 'a lot')).toBe('Enter an amount in figures, like 10050.')
  })

  it('refuses a negative opening balance', () => {
    expect(refusalOn('openingBalance', 'EUR', '-1.00')).toBe('An Account cannot open owing money.')
  })

  it('refuses an amount too large to count exactly', () => {
    expect(refusalOn('openingBalance', 'HUF', '99999999999999999')).toBe(
      'That is larger than this service can count exactly. Enter a smaller amount.',
    )
  })
})

describe('the Currency', () => {
  it('is refused when it is not one this service quotes', () => {
    expect(refusalOn('currency', 'GBP', '100.50')).toBe('Choose one of the Currencies this service quotes.')
  })
})

/**
 * Design decision 24's rule, asserted rather than trusted: the schema is a plain module,
 * so the form's rules are testable without rendering the form.
 */
describe('the module itself', () => {
  plainModuleRules(schemaSource, ['zod', './api/types', './money'])
})
