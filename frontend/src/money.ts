import type { Currency } from './api/types'

/**
 * The one place in the frontend that knows how many decimals a Currency is written
 * with, and so the only place that turns a count of Minor Units into the decimal
 * form an operator reads, or their typing back into a count.
 *
 * Every amount crossing the API is a whole count of Minor Units — cents, fillér —
 * because the backend holds money that way. Per-Currency decimals live only at the
 * edges, and this module is the edge: nothing else in the frontend divides or
 * multiplies by a hundred.
 *
 * Neither direction does arithmetic on the value; both work on the digits of the
 * string. Reaching for `/ 100` or `* 100` here is the obvious edit and the wrong
 * one — see `docs/design-decisions/33-money-format-module.md`.
 */

const DECIMAL_PLACES: Record<Currency, number> = {
  EUR: 2,
  USD: 2,
  HUF: 0,
}

/**
 * The Currencies this service quotes, at runtime.
 *
 * The `Currency` type is generated and so exists only at compile time; this list
 * is derived from the table above rather than written a second time, which is what
 * keeps a Currency from being offered in a form the formatter cannot render.
 */
export const CURRENCIES = Object.keys(DECIMAL_PLACES) as readonly Currency[]

/** How many decimal places a Currency is written with: 2 for EUR and USD, 0 for HUF. */
export const decimalPlacesIn = (currency: Currency): number => DECIMAL_PLACES[currency]

/** Why a typed amount could not be read as a count of Minor Units. */
export type AmountRejection = 'not-a-number' | 'too-many-decimals' | 'not-positive' | 'too-large'

/** The outcome of reading operator input, which is either an amount or a reason it is not one. */
export type ParsedAmount =
  | { readonly ok: true; readonly minorUnits: number }
  | { readonly ok: false; readonly reason: AmountRejection }

/**
 * Renders a Minor Unit count in the decimal form its Currency is written with:
 * `10050` is `100.50` in EUR and `10050` in HUF.
 *
 * Digits only — no symbol, no thousands separator and no locale — so the output is
 * exactly what {@link parseAmount} reads back and the same string can fill a form
 * field and be submitted unchanged. Screens name the Currency themselves.
 *
 * The count must be a whole number in the safe-integer range, as every amount the
 * API reports is. A negative one renders with its sign, so a negative is never shown
 * as a positive; anything else outside the contract renders as visible nonsense
 * rather than as a plausible wrong amount.
 */
export const formatAmount = (minorUnits: number, currency: Currency): string => {
  const places = decimalPlacesIn(currency)
  const digits = String(Math.abs(minorUnits)).padStart(places + 1, '0')
  const point = digits.length - places
  const sign = minorUnits < 0 ? '-' : ''

  return places === 0 ? `${sign}${digits}` : `${sign}${digits.slice(0, point)}.${digits.slice(point)}`
}

const DECIMAL_FORM = /^(-?)(\d+)(?:\.(\d+))?$/

/**
 * Reads what an operator typed as a count of Minor Units in the given Currency, or
 * says which of four things is wrong with it.
 *
 * Only a positive amount is one, since this parses a sum to transfer or to open an
 * Account with. {@link formatAmount}'s output therefore reads back through here for
 * positive counts only, which is the one place the two are not inverses.
 *
 * The checks run in the order the string is taken apart — shape, then scale, then
 * value. The scale check has to stay ahead of the conversion, which would otherwise
 * pad an over-long fraction down into a valid-looking amount.
 */
export const parseAmount = (input: string, currency: Currency): ParsedAmount => {
  const form = DECIMAL_FORM.exec(input.trim())

  if (!form) return { ok: false, reason: 'not-a-number' }

  const [, sign, whole, fraction = ''] = form
  const places = decimalPlacesIn(currency)

  if (fraction.length > places) return { ok: false, reason: 'too-many-decimals' }

  const minorUnits = Number(`${whole}${fraction.padEnd(places, '0')}`)

  if (sign === '-' || minorUnits === 0) return { ok: false, reason: 'not-positive' }
  if (!Number.isSafeInteger(minorUnits)) return { ok: false, reason: 'too-large' }

  return { ok: true, minorUnits }
}
