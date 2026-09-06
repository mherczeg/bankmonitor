import { z } from 'zod'
import type { Currency, NewAccount } from './api/types'
import { type AmountRejection, CURRENCIES, decimalPlacesIn, formatAmount, parseAmount } from './money'

/**
 * The rules the new-Account form is judged by, and the one place the decimal form an
 * operator types becomes the Minor Unit count the API takes.
 *
 * The Currency is a field here rather than something the server derives, which is what
 * makes the amount rule a **cross-field** one: `100.50` is an amount in EUR and is fillér
 * that do not exist in HUF, so the scale check has to see the chosen Currency. It
 * therefore sits on the object rather than on the amount, and names `openingBalance` in
 * its issue path so the form shows it against the field the operator can fix.
 *
 * **The conversion is a transform, and a transform does not reach the form.** TanStack
 * Form validates through Standard Schema, which reports issues and discards the parsed
 * value, so `onSubmit` is handed {@link NewAccountForm} — what was typed — and calls
 * `newAccountSchema.parse` itself to get {@link NewAccount}. That one explicit line is
 * what keeps the rule and the conversion in the same place instead of leaving the form to
 * multiply by a hundred on its own. See `docs/design-decisions/39-create-account-form.md`.
 */

const openingBalanceIsAnAmount = (
  form: { readonly currency: Currency; readonly openingBalance: string },
  ctx: z.RefinementCtx,
): NewAccount | undefined => {
  const parsed = parseAmount(form.openingBalance, form.currency)

  if (parsed.ok) return { currency: form.currency, openingBalanceMinorUnits: parsed.minorUnits }

  ctx.addIssue({
    code: 'custom',
    path: ['openingBalance'],
    message: whyItIsNotAnAmount(parsed.reason, form.currency),
  })

  return undefined
}

/**
 * What each of {@link parseAmount}'s four refusals means to the person who typed it.
 *
 * The wording is written here rather than in `money.ts`, which owns the rule and not the
 * copy — ticket 33 exports `decimalPlacesIn` for exactly this, so a form can write its own
 * sentence from the same source of truth. The example amount is the formatter's own
 * output, so the shape it asks for cannot drift from the shape it accepts.
 */
const whyItIsNotAnAmount = (reason: AmountRejection, currency: Currency): string => {
  const places = decimalPlacesIn(currency)

  switch (reason) {
    case 'not-a-number':
      return `Enter an amount in figures, like ${formatAmount(100_50, currency)}.`
    case 'too-many-decimals':
      return places === 0
        ? `${currency} is written without decimal places, so its amounts are whole numbers.`
        : `${currency} is written with ${places} decimal places.`
    case 'negative':
      return 'An Account cannot open owing money.'
    case 'too-large':
      return 'That is larger than this service can count exactly. Enter a smaller amount.'
  }
}

/**
 * The schema the form validates against and submits through.
 *
 * Its input is what the fields hold and its output is what the API takes, which are
 * deliberately different shapes — `openingBalance` is a string an operator typed and
 * `openingBalanceMinorUnits` is a whole count. `z.infer` aliases the *output*, so
 * {@link NewAccountForm} is spelled with `z.input` rather than inferred.
 */
export const newAccountSchema = z
  .object({
    currency: z.enum(CURRENCIES, { error: 'Choose one of the Currencies this service quotes.' }),
    openingBalance: z.string().trim().min(1, 'Enter the amount this Account opens with.'),
  })
  .transform((form, ctx) => openingBalanceIsAnAmount(form, ctx) ?? z.NEVER)

/** What the form's fields hold: a chosen Currency, and the decimal string beside it. */
export type NewAccountForm = z.input<typeof newAccountSchema>
