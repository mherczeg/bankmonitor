import { describe, expect, it } from 'vitest'
import { listAccounts, openAccount } from './accounts'
import accountsSource from './accounts.ts?raw'
import { plainModuleRules } from '../testsupport/plainModule'
import type { Account, NewAccount } from './types'

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

const A_NEW_EUR_ACCOUNT: NewAccount = { currency: 'EUR', openingBalanceMinorUnits: 10_050 }

const THE_OPENED_ACCOUNT: Account = {
  id: 3,
  currency: 'EUR',
  balanceMinorUnits: 10_050,
  reservedAmountMinorUnits: 0,
  availableBalanceMinorUnits: 10_050,
}

/** The path the call {@link whileFetchAnswers} is running asked for, and no earlier one. */
let requestedPath: string | undefined

/** How that call asked — the method, the headers and the body it put on the wire. */
let requestSent: RequestInit | undefined

/**
 * Runs one call with `globalThis.fetch` stubbed to answer it, putting the real one back
 * afterwards so an assertion that fails mid-call cannot leave the rest of the suite talking
 * to a stub.
 */
const whileFetchAnswers = async <T>(status: number, body: unknown, call: () => Promise<T>): Promise<T> => {
  const realFetch = globalThis.fetch

  requestedPath = undefined
  requestSent = undefined

  globalThis.fetch = (path: RequestInfo | URL, init?: RequestInit) => {
    requestedPath = String(path)
    requestSent = init

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

describe('openAccount', () => {
  it('posts the request as JSON and hands back the Account the API opened', async () => {
    await expect(
      whileFetchAnswers(201, THE_OPENED_ACCOUNT, () => openAccount(A_NEW_EUR_ACCOUNT)),
    ).resolves.toEqual(THE_OPENED_ACCOUNT)

    expect(requestedPath).toBe('/api/accounts')
    expect(requestSent?.method).toBe('POST')
    expect(requestSent?.headers).toEqual({ 'Content-Type': 'application/json' })
  })

  /**
   * The amount leaves as a whole count of Minor Units, which is the property the form's
   * conversion exists for: a body carrying `100.50` would be a hundred fillér to the
   * backend's `long` and is the one mistake no shape check downstream would catch.
   */
  it('puts the amount on the wire as the Minor Unit count it was handed', async () => {
    await whileFetchAnswers(201, THE_OPENED_ACCOUNT, () => openAccount(A_NEW_EUR_ACCOUNT))

    expect(JSON.parse(String(requestSent?.body))).toEqual({
      currency: 'EUR',
      openingBalanceMinorUnits: 10_050,
    })
  })

  it('throws the parsed problem document itself when the API refuses', async () => {
    await expect(
      whileFetchAnswers(500, SERVER_FAILED, () => openAccount(A_NEW_EUR_ACCOUNT)),
    ).rejects.toEqual(SERVER_FAILED)
  })

  it('throws it unwrapped, since an Error would hide the URN and the members it refused', async () => {
    const thrown: unknown = await whileFetchAnswers(500, SERVER_FAILED, () =>
      openAccount(A_NEW_EUR_ACCOUNT),
    ).catch((failure: unknown) => failure)

    expect(thrown).not.toBeInstanceOf(Error)
  })
})

/** Design decision 24's rule, asserted rather than trusted. */
describe('the module itself', () => {
  plainModuleRules(accountsSource, ['./types'])
})
