import { z } from 'zod'
import type { Account, Currency, NewTransfer } from './api/types'
import { type FieldForMember, type ServerRefusal, serverRefusalIn as formRefusalIn } from './formRefusal'
import { type AmountRejection, decimalPlacesIn, formatAmount, parseAmount } from './money'

/**
 * The rules a requested Transfer is judged by, and the one place the decimal an operator
 * types becomes the Minor Unit count the API takes.
 *
 * **The schema is a function of the accounts list**, which is what makes the amount rule
 * follow the *source* Account: a Transfer is denominated by the Account the money leaves,
 * so `100.50` is an amount out of a EUR Account and fillér that do not exist out of a HUF
 * one. Nothing on the form says which — the source does — so the check cannot live on the
 * amount field, and switching the source re-runs it with no dependency wiring, because an
 * object-level validator re-runs on any change.
 *
 * There is no Currency input, for the reason `CreateTransferRequest` has no Currency
 * member: a payload naming one could claim EUR out of a HUF Account, and something would
 * then have to decide which of the two to believe.
 *
 * **A Transfer of nothing is refused here.** Ticket 39 moved that rule out of
 * {@link parseAmount}, which now reads zero as the amount it is, because an Account may be
 * opened with nothing in it. This is the form that needs it back — see
 * `docs/design-decisions/40-transfer-form.md`.
 */

const CHOOSE_A_SOURCE = 'Choose the Account the money leaves.'

const CHOOSE_A_DESTINATION = 'Choose the Account the money arrives at.'

const ENTER_AN_AMOUNT = 'Enter the amount to transfer.'

const NOT_ONE_OF_THE_ACCOUNTS = 'That is not an Account this service holds. Reload the page and choose again.'

const TWO_DIFFERENT_ACCOUNTS = 'A Transfer moves money between two different Accounts.'

const MORE_THAN_NOTHING = 'A Transfer has to move more than nothing.'

const NOT_A_NEGATIVE =
  'A Transfer cannot move a negative amount. Swap the two Accounts to send it the other way.'

/**
 * What the fields hold on their own, before the accounts list has been consulted: two
 * Accounts named the way a `<select>` names them, and the decimal string beside them.
 *
 * Every rule that needs an Account is added by {@link transferSchemaFor}, so what is left
 * here is the one thing true of each field alone — that it was filled in.
 */
const transferFields = z.object({
  fromAccountId: z.string().min(1, CHOOSE_A_SOURCE),
  toAccountId: z.string().min(1, CHOOSE_A_DESTINATION),
  amount: z.string().trim().min(1, ENTER_AN_AMOUNT),
})

/** What the form's fields hold, which is deliberately not what the API takes. */
export type NewTransferForm = z.input<typeof transferFields>

/**
 * The schema this form validates against and submits through, judged over the Accounts the
 * service was last known to hold.
 *
 * Its input is what the fields hold and its output is what the API takes — string
 * identifiers and a typed decimal become whole numbers and a Minor Unit count — and the
 * transform that converts them does not reach the form. TanStack validates through Standard
 * Schema, which reports issues and discards the parsed value, so the submit handler is given
 * what was typed and calls `parse` itself.
 */
export const transferSchemaFor = (accounts: readonly Account[]) =>
  transferFields.transform((form, ctx) => requestedTransfer(accounts, form, ctx) ?? z.NEVER)

/**
 * The Currency a Transfer out of this source is denominated in, or nothing while no source
 * has been chosen — which is what the amount field is adorned with, so the denomination and
 * the rule it produces are in one glance.
 */
export const sourceCurrencyIn = (
  accounts: readonly Account[],
  fromAccountId: string,
): Currency | undefined => accountNamed(accounts, fromAccountId)?.currency

/**
 * The Account a `<select>` value names. Identifiers are whole numbers on the wire and
 * strings in the DOM, and this is the one place the two are compared.
 */
const accountNamed = (accounts: readonly Account[], chosen: string): Account | undefined =>
  accounts.find((account) => String(account.id) === chosen)

/**
 * Everything about this form that no single field can judge, and the request when nothing
 * is wrong.
 *
 * Every rule is reported, not just the first: a press on an untouched form is a click spent
 * on an answer, and it should be the whole answer. The amount is the one that can go
 * unjudged, because with no source Account there is no Currency to judge its scale against.
 */
const requestedTransfer = (
  accounts: readonly Account[],
  form: NewTransferForm,
  ctx: z.RefinementCtx,
): NewTransfer | undefined => {
  const source = accountNamed(accounts, form.fromAccountId)
  const destination = accountNamed(accounts, form.toAccountId)
  const amount = source === undefined ? undefined : amountToTransfer(form.amount, source.currency)
  const refusedDestination = whyTheDestinationIsRefused(source, destination)

  refuse(ctx, 'fromAccountId', source === undefined ? NOT_ONE_OF_THE_ACCOUNTS : undefined)
  refuse(ctx, 'toAccountId', refusedDestination)
  refuse(ctx, 'amount', amount?.ok === false ? amount.message : undefined)

  if (source === undefined || destination === undefined || refusedDestination !== undefined) return undefined
  if (amount === undefined || !amount.ok) return undefined

  return { fromAccountId: source.id, toAccountId: destination.id, amountMinorUnits: amount.minorUnits }
}

const refuse = (ctx: z.RefinementCtx, field: keyof NewTransferForm, message: string | undefined): void => {
  if (message !== undefined) ctx.addIssue({ code: 'custom', path: [field], message })
}

/**
 * The self-Transfer rule, and the side it is shown against.
 *
 * It is the destination that carries the sentence because it is the side an operator
 * changes: the money is already leaving the Account they chose first.
 */
const whyTheDestinationIsRefused = (
  source: Account | undefined,
  destination: Account | undefined,
): string | undefined => {
  if (destination === undefined) return NOT_ONE_OF_THE_ACCOUNTS
  if (source !== undefined && source.id === destination.id) return TWO_DIFFERENT_ACCOUNTS

  return undefined
}

/** What was typed, read as a count of Minor Units, or the reason it is not one to move. */
type TransferAmount =
  | { readonly ok: true; readonly minorUnits: number }
  | { readonly ok: false; readonly message: string }

const amountToTransfer = (typed: string, currency: Currency): TransferAmount => {
  const parsed = parseAmount(typed, currency)

  if (!parsed.ok) return { ok: false, message: whyItIsNotAnAmount(parsed.reason, currency) }
  if (parsed.minorUnits === 0) return { ok: false, message: MORE_THAN_NOTHING }

  return { ok: true, minorUnits: parsed.minorUnits }
}

/**
 * What each of {@link parseAmount}'s refusals means to the person who typed it.
 *
 * Three of the four sentences match the new-Account form's word for word and are written
 * out again rather than shared, on ticket 33's rule: `money.ts` owns the rule and not the
 * copy. The fourth is where the two forms differ — an Account cannot open owing money,
 * and a Transfer of a negative amount is a Transfer the other way round.
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
      return NOT_A_NEGATIVE
    case 'too-large':
      return 'That is larger than this service can count exactly. Enter a smaller amount.'
  }
}

/**
 * Which field of this form a member of the request belongs to.
 *
 * Both Accounts are named the same way on the wire and in the form; the amount is not,
 * because the wire carries the unit in the name and the field holds the decimal that was
 * typed.
 */
const FIELD_FOR_MEMBER: FieldForMember<keyof NewTransferForm> = {
  fromAccountId: 'fromAccountId',
  toAccountId: 'toAccountId',
  amountMinorUnits: 'amount',
}

/** Reads whatever the `POST` failed with and says which of this form's fields it refused. */
export const serverRefusalIn = (failure: unknown): ServerRefusal<keyof NewTransferForm> =>
  formRefusalIn(failure, FIELD_FOR_MEMBER)
