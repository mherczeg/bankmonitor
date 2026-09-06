import { useMemo, useRef } from 'react'
import { useForm } from '@tanstack/react-form'
import { useMutation } from '@tanstack/react-query'
import { useNavigate } from '@tanstack/react-router'
import { problemToMessage } from '../../api/problem'
import { startIntent } from '../../api/idempotency'
import { requestTransfer } from '../../api/transfers'
import type { Account, NewTransfer } from '../../api/types'
import { messagesUnder } from '../../formRefusal'
import { formatAmount } from '../../money'
import { type NewTransferForm as FormValues, serverRefusalIn, sourceCurrencyIn, transferSchemaFor } from '../../transferSchema'

/**
 * The form that requests a Transfer: a source Account, a destination Account, an amount in
 * the source's Currency, and the `PENDING` Transfer the service opens from them.
 *
 * It takes the loaded Accounts rather than fetching them, which is what makes
 * "`startIntent()` once when the form becomes ready" true rather than aspirational — the
 * component only mounts once the list has arrived, so the ref below is opened once per
 * screen and not once per loading state.
 *
 * Every rule lives in `transferSchema.ts`, built over those Accounts: the decimal rule
 * follows the source Account's Currency, and the self-Transfer refusal is a cross-field
 * one. This file holds markup, the mutation, and the one line that asks for the transform
 * validation discarded.
 *
 * See `docs/design-decisions/40-transfer-form.md`.
 */

const NOTHING_CHOSEN: FormValues = { fromAccountId: '', toAccountId: '', amount: '' }

/** The adornment while no source has been chosen, since there is no Currency to name yet. */
const NO_DENOMINATION = '—'

export function NewTransferForm({ accounts }: { accounts: readonly Account[] }) {
  const navigate = useNavigate()

  // A ref, not state: a new key is never something to re-render over. Opened once per
  // mount, which is once per screen — the route renders this only after the list arrives.
  const keys = useRef(startIntent())

  const schema = useMemo(() => transferSchemaFor(accounts), [accounts])

  const requesting = useMutation({
    // The key is derived from the mutation's own variables, which is what makes a retry
    // go out under the key its first attempt used: TanStack re-invokes this with the
    // variables `mutate` was called with, never with whatever the form holds now.
    mutationFn: (transfer: NewTransfer) => requestTransfer(transfer, keys.current.keyFor(transfer)),

    onSuccess: (transfer) => {
      // Redundant in practice — navigating unmounts this form and the ref with it — and
      // called anyway, because the module's contract is that a success ends an intent and
      // a screen that skipped it would be relying on the unmount to say so.
      keys.current.succeeded()

      void navigate({ to: '/transfers/$transferId', params: { transferId: String(transfer.id) } })
    },
  })

  const form = useForm({
    defaultValues: NOTHING_CHOSEN,
    // Registered under `onChange` only: TanStack runs the change validator on submit too,
    // and a second registration puts every sentence under its field twice.
    validators: { onChange: schema },

    listeners: {
      // A verdict is about the payload that produced it, and the operator has just changed
      // that payload.
      onChange: () => {
        if (!requesting.isIdle) requesting.reset()
      },
    },

    onSubmit: async ({ value }) => {
      try {
        // Validation hands back what was typed, never the transformed value, so the
        // converted shape is asked for here. It cannot throw — the same schema has just
        // accepted these values.
        await requesting.mutateAsync(schema.parse(value))
      }
      catch {
        // Already rendered from `requesting`; rethrowing would only repeat it as an
        // unhandled rejection.
      }
    },
  })

  const refusal = serverRefusalIn(requesting.error)

  return (
    <form
      className="card mb-4"
      data-testid="new-transfer-form"
      onSubmit={(submission) => {
        submission.preventDefault()
        void form.handleSubmit()
      }}
    >
      <div className="card-body">
        <h2 className="card-title h6">Request a transfer</h2>

        <div className="row g-3 align-items-start">
          <div className="col-sm-4">
            <form.Field name="fromAccountId">
              {(field) => {
                const messages = messagesUnder(field.state.meta.errors, refusal.perField.fromAccountId)

                return (
                  <>
                    <label className="form-label" htmlFor={field.name}>
                      From
                    </label>
                    <select
                      id={field.name}
                      name={field.name}
                      className={`form-select ${invalidWhen(messages)}`}
                      data-testid="new-transfer-source"
                      value={field.state.value}
                      onBlur={field.handleBlur}
                      onChange={(edit) => field.handleChange(edit.target.value)}
                    >
                      <AccountOptions accounts={accounts} />
                    </select>
                    <FieldRefusals testId="new-transfer-source-error" messages={messages} />
                  </>
                )
              }}
            </form.Field>
          </div>

          <div className="col-sm-4">
            <form.Field name="toAccountId">
              {(field) => {
                const messages = messagesUnder(field.state.meta.errors, refusal.perField.toAccountId)

                return (
                  <>
                    <label className="form-label" htmlFor={field.name}>
                      To
                    </label>
                    <select
                      id={field.name}
                      name={field.name}
                      className={`form-select ${invalidWhen(messages)}`}
                      data-testid="new-transfer-destination"
                      value={field.state.value}
                      onBlur={field.handleBlur}
                      onChange={(edit) => field.handleChange(edit.target.value)}
                    >
                      <AccountOptions accounts={accounts} />
                    </select>
                    <FieldRefusals testId="new-transfer-destination-error" messages={messages} />
                  </>
                )
              }}
            </form.Field>
          </div>

          <div className="col-sm-4">
            <form.Field name="amount">
              {(field) => {
                const messages = messagesUnder(field.state.meta.errors, refusal.perField.amount)

                return (
                  <>
                    <label className="form-label" htmlFor={field.name}>
                      Amount
                    </label>
                    <div className="input-group has-validation">
                      <input
                        id={field.name}
                        name={field.name}
                        className={`form-control ${invalidWhen(messages)}`}
                        data-testid="new-transfer-amount"
                        inputMode="decimal"
                        autoComplete="off"
                        value={field.state.value}
                        onBlur={field.handleBlur}
                        onChange={(edit) => field.handleChange(edit.target.value)}
                      />

                      {/* The only place the Currency appears on this screen: there is no
                          Currency input, because the source Account is what denominates a
                          Transfer. */}
                      <span className="input-group-text" data-testid="new-transfer-denomination">
                        <form.Subscribe selector={(state) => state.values.fromAccountId}>
                          {(fromAccountId) => sourceCurrencyIn(accounts, fromAccountId) ?? NO_DENOMINATION}
                        </form.Subscribe>
                      </span>

                      <FieldRefusals testId="new-transfer-amount-error" messages={messages} />
                    </div>
                  </>
                )
              }}
            </form.Field>
          </div>
        </div>

        <form.Subscribe selector={(state) => state.isSubmitting}>
          {(isSubmitting) => (
            // Never disabled for being invalid: a first press on an untouched form has to
            // reveal every rule at once.
            <button
              type="submit"
              className="btn btn-primary mt-3"
              data-testid="new-transfer-submit"
              disabled={isSubmitting}
            >
              {isSubmitting ? 'Requesting…' : 'Request transfer'}
            </button>
          )}
        </form.Subscribe>

        {requesting.isError && (
          <RefusedToTransfer
            failure={requesting.error}
            unattached={refusal.unattached}
            onRetry={() => {
              // The same variables the failed attempt carried, so the Idempotency Key the
              // module hands out is the same one. Reading the form here instead would mint
              // a new key for an edit the operator has not submitted.
              if (requesting.variables !== undefined) requesting.mutate(requesting.variables)
            }}
          />
        )}
      </div>
    </form>
  )
}

/**
 * One entry per Account, showing what it can still spend: the refusal an operator meets
 * most often here is an Available Balance that does not cover the amount, and the figure
 * that decides it belongs in the list they are choosing from.
 */
function AccountOptions({ accounts }: { accounts: readonly Account[] }) {
  return (
    <>
      <option value="">Choose an account</option>
      {accounts.map(({ id, currency, availableBalanceMinorUnits }) => (
        <option key={id} value={id}>
          {id} — {formatAmount(availableBalanceMinorUnits, currency)} {currency}
        </option>
      ))}
    </>
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
 * The refusal as a whole: `problem.ts`'s heading and advice, anything the service refused
 * that names no field on this form, and a retry only where that module says one could
 * clear — an Available Balance moves on its own as other Transfers settle, and a
 * self-Transfer never will.
 */
function RefusedToTransfer({
  failure,
  unattached,
  onRetry,
}: {
  failure: unknown
  unattached: readonly string[]
  onRetry: () => void
}) {
  const { title, body, retryable } = problemToMessage(failure)

  return (
    <div className="alert alert-danger mt-3 mb-0" role="alert" data-testid="new-transfer-error">
      <h3 className="alert-heading h6" data-testid="new-transfer-error-title">
        {title}
      </h3>
      <p className={unattached.length === 0 && !retryable ? 'mb-0' : ''} data-testid="new-transfer-error-body">
        {body}
      </p>
      {unattached.length > 0 && (
        <ul className={retryable ? '' : 'mb-0'} data-testid="new-transfer-error-unattached">
          {unattached.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      )}
      {retryable && (
        <button
          type="button"
          className="btn btn-sm btn-outline-danger"
          data-testid="new-transfer-retry"
          onClick={onRetry}
        >
          Try again
        </button>
      )}
    </div>
  )
}
