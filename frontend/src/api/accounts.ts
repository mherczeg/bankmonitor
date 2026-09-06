import type { Account, NewAccount } from './types'

/**
 * The one request the Accounts screen makes: every Account the service holds, with its
 * balance, its Reserved Amount and its Available Balance already worked out by the
 * backend.
 *
 * The endpoint answers a bare array in the order it was written — oldest Account first —
 * and that order is part of the contract, so nothing here or downstream sorts it. Every
 * figure arrives as a whole count of Minor Units and stays one until a screen formats it.
 *
 * A refusal is thrown as the parsed body rather than wrapped in an `Error`, because
 * `problem.ts` reads its `type` URN and `retry.ts` its `status`, and an `Error` would hide
 * both behind a message. See `docs/design-decisions/38-accounts-list-screen.md`.
 */
export const listAccounts = async (): Promise<Account[]> => {
  const response = await fetch('/api/accounts')
  const body: unknown = await response.json()

  if (!response.ok) throw body

  return body as Account[]
}

/**
 * Opens an Account: one Currency, one opening balance, and the Account the service made
 * comes back with an identifier and the three figures the list shows.
 *
 * The amount goes on the wire as the whole count of Minor Units it arrives as. The
 * decimal an operator typed was converted before this was called — by `accountSchema.ts`,
 * which is the only module that knows how many decimals a Currency has — so nothing here
 * multiplies anything.
 *
 * A refusal is thrown as the parsed body, for the reason {@link listAccounts} throws one:
 * `problem.ts` reads its URN to choose the heading and `validation.ts` reads its `errors`
 * to put a sentence beside each field, and an `Error` would hide both behind a message.
 *
 * There is no Idempotency Key on this request, and the endpoint takes none. §3 puts
 * idempotency around the transaction that moves money, which this is not: a second
 * `POST` opens a second Account, which is what pressing the button twice asked for.
 */
export const openAccount = async (account: NewAccount): Promise<Account> => {
  const response = await fetch('/api/accounts', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(account),
  })
  const body: unknown = await response.json()

  if (!response.ok) throw body

  return body as Account
}
