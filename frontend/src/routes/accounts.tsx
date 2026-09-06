import { createFileRoute } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { NewAccountForm } from './-newAccountForm'
import { listAccounts } from '../api/accounts'
import { problemToMessage } from '../api/problem'
import { queryKeys } from '../api/queryKeys'
import type { Account } from '../api/types'
import { formatAmount } from '../money'
import styles from './accounts.module.css'

export const Route = createFileRoute('/accounts')({ component: AccountsScreen })

/**
 * Every Account the service holds, with what it has and what it can still spend, so an
 * operator can pick a source that will actually cover the Transfer they have in mind.
 *
 * The three figures are shown as the API reports them: none of them is worked out here,
 * and the Available Balance in particular is the server's answer rather than a
 * subtraction this screen could get out of step with. The rows keep the order the
 * endpoint sent, which is oldest Account first and part of its contract.
 *
 * Nothing here links anywhere — there is no per-Account endpoint behind a row.
 *
 * The form above the list opens an Account and invalidates this query, so the row it
 * created arrives from the endpoint rather than from anything the form knew.
 */
function AccountsScreen() {
  const accounts = useQuery({ queryKey: queryKeys.accounts(), queryFn: listAccounts })

  return (
    <>
      <h1 className="h3">Accounts</h1>
      <p className="text-body-secondary">Every account, its balance and what it can spend.</p>

      <NewAccountForm />

      {accounts.isPending && <LoadingAccounts />}
      {accounts.isError && <FailedToLoad failure={accounts.error} onRetry={() => void accounts.refetch()} />}
      {accounts.isSuccess &&
        (accounts.data.length === 0 ? <NoAccounts /> : <AccountsTable accounts={accounts.data} />)}
    </>
  )
}

function LoadingAccounts() {
  return (
    <div className="d-flex align-items-center gap-2 text-body-secondary" role="status" data-testid="accounts-loading">
      <span className="spinner-border spinner-border-sm" aria-hidden="true" />
      <span>Loading accounts…</span>
    </div>
  )
}

function NoAccounts() {
  return (
    <p className="text-body-secondary" data-testid="accounts-empty">
      The service is holding no accounts yet, so there is nothing to show here.
    </p>
  )
}

/**
 * The failure as an operator can act on it, which is `problem.ts`'s wording and never the
 * document's own `title` and `detail` — those are written for whoever reads the response.
 * The retry button appears only for a failure that asking again could clear.
 */
function FailedToLoad({ failure, onRetry }: { failure: unknown; onRetry: () => void }) {
  const { title, body, retryable } = problemToMessage(failure)

  return (
    <div className="alert alert-danger" role="alert" data-testid="accounts-error">
      <h2 className="alert-heading h6" data-testid="accounts-error-title">
        {title}
      </h2>
      <p className="mb-0" data-testid="accounts-error-body">
        {body}
      </p>
      {retryable && (
        <button
          type="button"
          className="btn btn-sm btn-outline-danger mt-3"
          data-testid="accounts-retry"
          onClick={onRetry}
        >
          Try again
        </button>
      )}
    </div>
  )
}

function AccountsTable({ accounts }: { accounts: readonly Account[] }) {
  return (
    <div className="table-responsive">
      <table className="table table-sm align-middle" data-testid="accounts-table">
        <thead>
          <tr>
            <th scope="col">Account</th>
            <th scope="col">Currency</th>
            <th scope="col" className="text-end">
              Balance
            </th>
            <th scope="col" className="text-end">
              Reserved Amount
            </th>
            <th scope="col" className="text-end">
              Available Balance
            </th>
          </tr>
        </thead>
        <tbody>
          {accounts.map((account) => (
            <AccountRow key={account.id} account={account} />
          ))}
        </tbody>
      </table>
    </div>
  )
}

/**
 * One Account's row. Each amount cell holds the formatted figure and nothing else — the
 * Currency is named once in its own column rather than repeated beside three numbers.
 */
function AccountRow({ account }: { account: Account }) {
  const { id, currency, balanceMinorUnits, reservedAmountMinorUnits, availableBalanceMinorUnits } = account
  const amountCell = `text-end ${styles.amount}`

  return (
    <tr data-testid={`account-${id}`}>
      <th scope="row">{id}</th>
      <td data-testid={`currency-${id}`}>{currency}</td>
      <td className={amountCell} data-testid={`balance-${id}`}>
        {formatAmount(balanceMinorUnits, currency)}
      </td>
      <td className={amountCell} data-testid={`reserved-${id}`}>
        {formatAmount(reservedAmountMinorUnits, currency)}
      </td>
      <td className={amountCell} data-testid={`available-${id}`}>
        {formatAmount(availableBalanceMinorUnits, currency)}
      </td>
    </tr>
  )
}
