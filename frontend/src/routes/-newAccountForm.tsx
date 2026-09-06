import { useForm } from '@tanstack/react-form'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { type NewAccountForm as FormValues, newAccountSchema, serverRefusalIn } from '../accountSchema'
import { openAccount } from '../api/accounts'
import { problemToMessage } from '../api/problem'
import { queryKeys } from '../api/queryKeys'
import { messagesUnder } from '../formRefusal'
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

export function NewAccountForm() {
  const queryClient = useQueryClient()

  const opening = useMutation({
    mutationFn: openAccount,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.accounts() }),
  })

  const form = useForm({
    defaultValues: NEW_ACCOUNT,
    // Registered once, though submitting is also a moment it runs: TanStack runs the
    // change validator on submit too, and a second registration under `onSubmit` puts
    // every sentence under its field twice.
    validators: { onChange: newAccountSchema },

    listeners: {
      // A verdict is about the payload that produced it, and the operator has just
      // changed that payload. A request still in flight is not a verdict, and resetting
      // one detaches the observer: the refusal would arrive at nothing, leaving the
      // operator with no alert for a request that did go out.
      onChange: () => {
        if (opening.isError || opening.isSuccess) opening.reset()
      },
    },

    onSubmit: async ({ value, formApi }) => {
      try {
        // Validation hands back what was typed, never the transformed value, so the
        // converted shape is asked for here. It cannot throw — the same schema has just
        // accepted these values.
        await opening.mutateAsync(newAccountSchema.parse(value))
        formApi.reset()
      }
      catch {
        // Already rendered from `opening`; rethrowing would only repeat it as an
        // unhandled rejection.
      }
    },
  })

  const refusal = serverRefusalIn(opening.error)

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
              {(field) => {
                const messages = messagesUnder(field.state.meta.errors, refusal.perField.currency)

                return (
                <>
                  <label className="form-label" htmlFor={field.name}>
                    Currency
                  </label>
                  <select
                    id={field.name}
                    name={field.name}
                    className={`form-select ${invalidWhen(messages)}`}
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
                  <FieldRefusals testId="new-account-currency-error" messages={messages} />
                </>
                )
              }}
            </form.Field>
          </div>

          <div className="col-sm-5">
            <form.Field name="openingBalance">
              {(field) => {
                const messages = messagesUnder(field.state.meta.errors, refusal.perField.openingBalance)

                return (
                <>
                  <label className="form-label" htmlFor={field.name}>
                    Opening balance
                  </label>
                  <div className="input-group has-validation">
                    <input
                      id={field.name}
                      name={field.name}
                      className={`form-control ${invalidWhen(messages)}`}
                      data-testid="new-account-opening-balance"
                      inputMode="decimal"
                      autoComplete="off"
                      value={field.state.value}
                      onBlur={field.handleBlur}
                      onChange={(edit) => field.handleChange(edit.target.value)}
                    />

                    {/* Beside the box rather than only in the select above: it is what makes
                        "HUF is written without decimal places" readable. */}
                    <span className="input-group-text" data-testid="new-account-denomination">
                      <form.Subscribe selector={(state) => state.values.currency}>
                        {(currency) => currency}
                      </form.Subscribe>
                    </span>

                    <FieldRefusals testId="new-account-opening-balance-error" messages={messages} />
                  </div>
                </>
                )
              }}
            </form.Field>
          </div>

          <div className="col-sm-4">
            <form.Subscribe selector={(state) => state.isSubmitting}>
              {(isSubmitting) => (
                // Never disabled for being invalid: a first press on an untouched form
                // has to reveal every rule at once.
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
