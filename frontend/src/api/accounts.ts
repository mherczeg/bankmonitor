import type { Account } from './types'

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
