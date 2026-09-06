import { describe, expect, it } from 'vitest'
import { listAccounts } from './accounts'
import accountsSource from './accounts.ts?raw'
import { plainModuleRules } from '../testsupport/plainModule'
import type { Account } from './types'

const TWO_ACCOUNTS: readonly Account[] = [
  { id: 1, currency: 'EUR', balanceMinorUnits: 10050, reservedAmountMinorUnits: 50, availableBalanceMinorUnits: 10000 },
  { id: 2, currency: 'HUF', balanceMinorUnits: 250000, reservedAmountMinorUnits: 0, availableBalanceMinorUnits: 250000 },
]

const SERVER_FAILED = {
  type: 'urn:problem:internal-error',
  title: 'Internal Server Error',
  status: 500,
  detail: 'The service failed to answer.',
}

/** The path the call {@link whileFetchAnswers} is running asked for, and no earlier one. */
let requestedPath: string | undefined

/**
 * Runs one call with `globalThis.fetch` stubbed to answer it, putting the real one back
 * afterwards so an assertion that fails mid-call cannot leave the rest of the suite talking
 * to a stub.
 */
const whileFetchAnswers = async <T>(status: number, body: unknown, call: () => Promise<T>): Promise<T> => {
  const realFetch = globalThis.fetch

  requestedPath = undefined

  globalThis.fetch = (path: RequestInfo | URL) => {
    requestedPath = String(path)

    return Promise.resolve({ ok: status < 400, json: () => Promise.resolve(body) } as Response)
  }

  try {
    return await call()
  } finally {
    globalThis.fetch = realFetch
  }
}

describe('listAccounts', () => {
  it('reads the Accounts endpoint and hands back the array it parsed', async () => {
    await expect(whileFetchAnswers(200, TWO_ACCOUNTS, listAccounts)).resolves.toEqual(TWO_ACCOUNTS)
    expect(requestedPath).toBe('/api/accounts')
  })

  it('throws the parsed problem document itself when the API refuses', async () => {
    await expect(whileFetchAnswers(500, SERVER_FAILED, listAccounts)).rejects.toEqual(SERVER_FAILED)
  })

  it('throws it unwrapped, since an Error would hide the URN and the status read downstream', async () => {
    const thrown: unknown = await whileFetchAnswers(500, SERVER_FAILED, listAccounts).catch(
      (failure: unknown) => failure,
    )

    expect(thrown).not.toBeInstanceOf(Error)
  })
})

/** Design decision 24's rule, asserted rather than trusted. */
describe('the module itself', () => {
  plainModuleRules(accountsSource, ['./types'])
})
