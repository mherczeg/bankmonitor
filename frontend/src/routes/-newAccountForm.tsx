import { useForm } from '@tanstack/react-form'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { type NewAccountForm as FormValues, newAccountSchema } from '../accountSchema'
import { openAccount } from '../api/accounts'
import { problemToMessage } from '../api/problem'
import { queryKeys } from '../api/queryKeys'
import { rejectedFieldsIn } from '../api/validation'
import { CURRENCIES } from '../money'

/**
 * The create half of the Accounts screen: a Currency, an opening balance in the decimal
 * form that Currency is written in, and the Account the service opens from them.
 *
 * Everything the form is judged by lives in `accountSchema.ts`, including the
 * decimal → Minor Unit conversion. What happens here is the one thing a schema cannot do:
 * because Standard Schema reports issues and discards the parsed value, `onSubmit` is
 * handed what was typed and calls `newAccountSchema.parse` itself. That line is the only
 * place the form touches the amount, and it does not know how many decimals anything has.
 *
 * See `docs/design-decisions/39-create-account-form.md`.
 */

const NEW_ACCOUNT: FormValues = { currency: 'EUR', openingBalance: '' }

/**
 * Which field a member of the request belongs to on this screen.
 *
 * The two names differ on purpose — the wire carries `openingBalanceMinorUnits` because
 * ticket 09 made the unit part of the name, while the field holds the decimal an operator
 * typed — so a refusal naming the wire member has to be walked back to the input that can
 * be corrected. A member with no field here is not dropped: it is shown with the refusal
 * instead, since a message nobody sees is worse than one in the wrong place.
 */
const FIELD_FOR_MEMBER: Readonly<Partial<Record<string, keyof FormValues>>> = {
  currency: 'currency',
  openingBalanceMinorUnits: 'openingBalance',
}

/** What the service refused, sorted into the fields that can be corrected and the rest. */
interface ServerRefusal {
  readonly perField: Readonly<Partial<Record<keyof FormValues, string>>>
  readonly unattached: readonly string[]
}

const NOTHING_REFUSED: ServerRefusal = { perField: {}, unattached: [] }

const serverRefusalIn = (failure: unknown): ServerRefusal => {
  const { byField, overall } = rejectedFieldsIn(failure)
  const perField: Partial<Record<keyof FormValues, string>> = {}
  const unattached = [...overall]

  for (const [member, message] of byField) {
    const field = FIELD_FOR_MEMBER[member]

    if (field === undefined) unattached.push(`${member}: ${message}`)
    else perField[field] = message
  }

  return { perField, unattached }
}

export function NewAccountForm() {
  const queryClient = useQueryClient()

  const opening = useMutation({
    mutationFn: openAccount,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.accounts() }),
  })

  const form = useForm({
    defaultValues: NEW_ACCOUNT,
    // The schema is registered once, though submitting is also a moment it has to run.
    // TanStack runs the change validator on submit as well, so registering it under
    // `onSubmit` too puts the same sentence under the field twice.
    validators: { onChange: newAccountSchema },

    listeners: {
      // Both the refusal and the note about the last Account opened are verdicts on a
      // payload the operator has now changed, so editing anything voids them. Without this
      // the field would carry a sentence contradicting what is in the box beside it.
      onChange: () => {
        if (!opening.isIdle) opening.reset()
      },
    },

    onSubmit: async ({ value, formApi }) => {
      try {
        // The one explicit conversion: validation hands back what was typed, never the
        // transformed value, so the output shape is asked for here. It cannot throw — the
        // same schema has just accepted these values.
        await opening.mutateAsync(newAccountSchema.parse(value))
        formApi.reset()
      }
      catch {
        // The refusal is already on `opening`, which this form renders. Letting it out
        // would surface the same failure a second time, as an unhandled rejection.
      }
    },
  })

  const refusal = opening.isError ? serverRefusalIn(opening.error) : NOTHING_REFUSED

  return (
    <form
      className="card mb-4"
      data-testid="new-account-form"
      onSubmit={(submission) => {
        submission.preventDefault()
        void form.handleSubmit()
      }}
    >
      <div className="card-body">
        <h2 className="card-title h6">Open an account</h2>

        <div className="row g-3 align-items-start">
          <div className="col-sm-3">
            <form.Field name="currency">
              {(field) => (
                <>
                  <label className="form-label" htmlFor={field.name}>
                    Currency
                  </label>
                  <select
                    id={field.name}
                    name={field.name}
                    className={`form-select ${invalidWhen(shownFor(field.state.meta.errors, refusal.perField.currency))}`}
                    data-testid="new-account-currency"
                    value={field.state.value}
                    onBlur={field.handleBlur}
                    onChange={(edit) => field.handleChange(edit.target.value as FormValues['currency'])}
                  >
                    {CURRENCIES.map((currency) => (
                      <option key={currency} value={currency}>
                        {currency}
                      </option>
                    ))}
                  </select>
                  <FieldRefusals
                    testId="new-account-currency-error"
                    messages={shownFor(field.state.meta.errors, refusal.perField.currency)}
                  />
                </>
              )}
            </form.Field>
          </div>

          <div className="col-sm-5">
            <form.Field name="openingBalance">
              {(field) => (
                <>
                  <label className="form-label" htmlFor={field.name}>
                    Opening balance
                  </label>
                  <div className="input-group has-validation">
                    <input
                      id={field.name}
                      name={field.name}
                      className={`form-control ${invalidWhen(shownFor(field.state.meta.errors, refusal.perField.openingBalance))}`}
                      data-testid="new-account-opening-balance"
                      inputMode="decimal"
                      autoComplete="off"
                      value={field.state.value}
                      onBlur={field.handleBlur}
                      onChange={(edit) => field.handleChange(edit.target.value)}
                    />

                    {/*
                      The Currency again, one field along from the select that chose it. It is
                      what makes a refusal over decimal places readable — the rule and the
                      denomination it comes from are then in the same glance.
                    */}
                    <span className="input-group-text" data-testid="new-account-denomination">
                      <form.Subscribe selector={(state) => state.values.currency}>
                        {(currency) => currency}
                      </form.Subscribe>
                    </span>

                    <FieldRefusals
                      testId="new-account-opening-balance-error"
                      messages={shownFor(field.state.meta.errors, refusal.perField.openingBalance)}
                    />
                  </div>
                </>
              )}
            </form.Field>
          </div>

          <div className="col-sm-4">
            <form.Subscribe selector={(state) => state.isSubmitting}>
              {(isSubmitting) => (
                // Enabled whatever the form's state, so a first press on a form nobody has
                // touched reveals every rule at once. A button disabled until valid says
                // only that something is wrong, and never which field.
                <button
                  type="submit"
                  className="btn btn-primary mt-4"
                  data-testid="new-account-submit"
                  disabled={isSubmitting}
                >
                  {isSubmitting ? 'Opening…' : 'Open account'}
                </button>
              )}
            </form.Subscribe>
          </div>
        </div>

        {opening.isError && <RefusedToOpen failure={opening.error} unattached={refusal.unattached} />}
        {opening.isSuccess && <Opened id={opening.data.id} />}
      </div>
    </form>
  )
}

/**
 * What the operator is shown against a field: what the schema said about what is in the
 * box now, and what the service said about what was last sent.
 *
 * The client-side messages come first because they are about the current value, while a
 * server one describes a payload that may already have been corrected — and is cleared as
 * soon as it has been.
 */
const shownFor = (errors: readonly unknown[], fromServer: string | undefined): readonly string[] => [
  ...errors.flatMap(messageIn),
  ...(fromServer === undefined ? [] : [fromServer]),
]

const messageIn = (error: unknown): string[] => {
  if (typeof error === 'string') return [error]
  if (typeof error === 'object' && error !== null && 'message' in error) return [String(error.message)]

  return []
}

const invalidWhen = (messages: readonly string[]): string => (messages.length === 0 ? '' : 'is-invalid')

function FieldRefusals({ testId, messages }: { testId: string; messages: readonly string[] }) {
  if (messages.length === 0) return null

  return (
    <div className="invalid-feedback" data-testid={testId}>
      {messages.join(' ')}
    </div>
  )
}

/**
 * The refusal as a whole: `problem.ts`'s heading and advice, which branch on the URN, and
 * beneath them anything the service refused that names no field on this form.
 */
function RefusedToOpen({ failure, unattached }: { failure: unknown; unattached: readonly string[] }) {
  const { title, body } = problemToMessage(failure)

  return (
    <div className="alert alert-danger mt-3 mb-0" role="alert" data-testid="new-account-error">
      <h3 className="alert-heading h6" data-testid="new-account-error-title">
        {title}
      </h3>
      <p className={unattached.length === 0 ? 'mb-0' : ''} data-testid="new-account-error-body">
        {body}
      </p>
      {unattached.length > 0 && (
        <ul className="mb-0" data-testid="new-account-error-unattached">
          {unattached.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Which Account was opened, since the list it joined is ordered by age and not by news. */
function Opened({ id }: { id: number }) {
  return (
    <div className="alert alert-success mt-3 mb-0" role="status" data-testid="new-account-created">
      Account {id} is open.
    </div>
  )
}
