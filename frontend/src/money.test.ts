import { describe, expect, it } from 'vitest'
import type { Currency } from './api/types'
import { CURRENCIES, decimalPlacesIn, formatAmount, parseAmount } from './money'
import moneySource from './money.ts?raw'
import { plainModuleRules } from './testsupport/plainModule'

const rejects = (input: string, currency: Currency) => {
  const parsed = parseAmount(input, currency)

  return parsed.ok ? `accepted as ${parsed.minorUnits}` : parsed.reason
}

describe('the decimals a Currency is written with', () => {
  it('is two for the decimal Currencies and none for the forint', () => {
    expect(decimalPlacesIn('EUR')).toBe(2)
    expect(decimalPlacesIn('USD')).toBe(2)
    expect(decimalPlacesIn('HUF')).toBe(0)
  })

  it('is known for every Currency the backend quotes', () => {
    expect([...CURRENCIES].sort()).toEqual(['EUR', 'HUF', 'USD'])
  })
})

describe('formatting a Minor Unit count', () => {
  it('writes a decimal Currency with its two places', () => {
    expect(formatAmount(10_050, 'EUR')).toBe('100.50')
    expect(formatAmount(10_000, 'USD')).toBe('100.00')
  })

  it('writes the forint with none, so a count of fillér is never implied', () => {
    expect(formatAmount(10_050, 'HUF')).toBe('10050')
  })

  it('pads an amount smaller than one major unit', () => {
    expect(formatAmount(5, 'EUR')).toBe('0.05')
    expect(formatAmount(50, 'EUR')).toBe('0.50')
    expect(formatAmount(0, 'EUR')).toBe('0.00')
    expect(formatAmount(0, 'HUF')).toBe('0')
  })

  it('keeps the sign of a difference between two amounts', () => {
    expect(formatAmount(-5, 'EUR')).toBe('-0.05')
    expect(formatAmount(-10_050, 'HUF')).toBe('-10050')
  })
})

describe('parsing what an operator typed', () => {
  it('reads the familiar decimal form back as Minor Units', () => {
    expect(parseAmount('100.50', 'EUR')).toEqual({ ok: true, minorUnits: 10_050 })
    expect(parseAmount('0.05', 'USD')).toEqual({ ok: true, minorUnits: 5 })
    expect(parseAmount('10050', 'HUF')).toEqual({ ok: true, minorUnits: 10_050 })
  })

  it('fills in the places the operator left off', () => {
    expect(parseAmount('100.5', 'EUR')).toEqual({ ok: true, minorUnits: 10_050 })
    expect(parseAmount('100', 'EUR')).toEqual({ ok: true, minorUnits: 10_000 })
  })

  it('ignores surrounding whitespace', () => {
    expect(parseAmount('  100.50  ', 'EUR')).toEqual({ ok: true, minorUnits: 10_050 })
  })

  it('refuses more decimal places than the Currency is written with', () => {
    expect(rejects('100.505', 'EUR')).toBe('too-many-decimals')
    expect(rejects('100.5', 'HUF')).toBe('too-many-decimals')
    expect(rejects('100.0', 'HUF')).toBe('too-many-decimals')
  })

  it('refuses input that is not a plain decimal number', () => {
    for (const input of ['', '   ', 'abc', '1e5', '1,50', '100.', '.50', '100 50', '0x64']) {
      expect(rejects(input, 'EUR')).toBe('not-a-number')
    }
  })

  it('refuses a negative amount, however it is spelled', () => {
    expect(rejects('-1', 'HUF')).toBe('negative')
    expect(rejects('-100.50', 'EUR')).toBe('negative')
    expect(rejects('-0', 'HUF')).toBe('negative')
    expect(rejects('-0.00', 'EUR')).toBe('negative')
  })

  /**
   * Zero is an amount, and whether it is a *usable* one is the asking form's rule rather
   * than this module's: ticket 09 opens an Account with nothing in it deliberately, while
   * a Transfer of nothing is refused by the form that requests one.
   */
  it('reads zero as the amount it is, leaving what it means to the form asking', () => {
    expect(parseAmount('0', 'HUF')).toEqual({ ok: true, minorUnits: 0 })
    expect(parseAmount('0.00', 'EUR')).toEqual({ ok: true, minorUnits: 0 })
    expect(parseAmount('0.0', 'USD')).toEqual({ ok: true, minorUnits: 0 })
  })

  it('refuses an amount too large to count exactly', () => {
    expect(rejects('99999999999999999', 'HUF')).toBe('too-large')
    expect(rejects('999999999999999.99', 'EUR')).toBe('too-large')
    expect(rejects('90071992547409.92', 'EUR')).toBe('too-large')
    expect(parseAmount('90071992547409.91', 'EUR')).toEqual({
      ok: true,
      minorUnits: Number.MAX_SAFE_INTEGER,
    })
  })
})

describe('formatting and parsing as inverses', () => {
  const amounts = [0, 1, 5, 50, 99, 100, 101, 999, 1_000, 10_050, 123_456_789]

  for (const currency of CURRENCIES) {
    it(`round-trips every ${currency} amount losslessly`, () => {
      for (const minorUnits of amounts) {
        expect(parseAmount(formatAmount(minorUnits, currency), currency)).toEqual({
          ok: true,
          minorUnits,
        })
      }
    })
  }
})

/**
 * Design decision 24's rule, asserted rather than trusted — why it is worth a test
 * is in `docs/design-decisions/33-money-format-module.md`.
 */
describe('the module itself', () => {
  plainModuleRules(moneySource, ['./api/types'])
})
