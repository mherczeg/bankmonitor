import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { NewTransferForm } from './-newTransferForm'
import { listAccounts } from '../../api/accounts'
import { problemToMessage } from '../../api/problem'
import { queryKeys } from '../../api/queryKeys'

export const Route = createFileRoute('/transfers/new')({ component: NewTransferScreen })

/**
 * Requesting a Transfer. The screen is the Accounts query and the form it feeds: a
 * Transfer is denominated by the Account the money leaves, so the rules cannot be written
 * until the Accounts are known, and the form is therefore mounted only once they are.
 *
 * That is not only a loading state. It is what makes the form's Idempotency Key supply
 * open once per screen rather than once per render of a spinner — see
 * `docs/design-decisions/40-transfer-form.md`.
 *
 * The list is the same `queryKeys.accounts()` the Accounts screen reads, so arriving here
 * from that screen shows what it showed, refetched because nothing in this app declares a
 * `staleTime`.
 */
function NewTransferScreen() {
  const accounts = useQuery({ queryKey: queryKeys.accounts(), queryFn: listAccounts })

  return (
    <>
      <h1 className="h3">New transfer</h1>
      <p className="text-body-secondary">
        Submitting navigates to the transfer, so pending state lives in the URL.
      </p>

      {accounts.isPending && <LoadingAccounts />}
      {accounts.isError && (
        <FailedToLoad failure={accounts.error} onRetry={() => void accounts.refetch()} />
      )}
      {accounts.isSuccess &&
        (accounts.data.length < 2 ? <TooFewAccounts /> : <NewTransferForm accounts={accounts.data} />)}
    </>
  )
}

function LoadingAccounts() {
  return (
    <div
      className="d-flex align-items-center gap-2 text-body-secondary"
      role="status"
      data-testid="new-transfer-accounts-loading"
    >
      <span className="spinner-border spinner-border-sm" aria-hidden="true" />
      <span>Loading accounts…</span>
    </div>
  )
}

/**
 * Fewer than two Accounts is not an empty state to draw a form over: every field of the
 * form would be a choice with nothing to choose, and the only rule it could report is one
 * the operator cannot satisfy here.
 */
function TooFewAccounts() {
  return (
    <p className="text-body-secondary" data-testid="new-transfer-too-few-accounts">
      A transfer moves money between two different accounts, and the service is holding
      fewer than two. Open another on the accounts screen first.
    </p>
  )
}

/** The Accounts screen's failure rendering, for the same query and the same reasons. */
function FailedToLoad({ failure, onRetry }: { failure: unknown; onRetry: () => void }) {
  const { title, body, retryable } = problemToMessage(failure)

  return (
    <div className="alert alert-danger" role="alert" data-testid="new-transfer-accounts-error">
      <h2 className="alert-heading h6" data-testid="new-transfer-accounts-error-title">
        {title}
      </h2>
      <p className="mb-0" data-testid="new-transfer-accounts-error-body">
        {body}
      </p>
      {retryable && (
        <button
          type="button"
          className="btn btn-sm btn-outline-danger mt-3"
          data-testid="new-transfer-accounts-retry"
          onClick={onRetry}
        >
          Try again
        </button>
      )}
    </div>
  )
}
