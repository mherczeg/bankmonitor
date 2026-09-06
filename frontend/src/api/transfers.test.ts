import { describe, expect, it } from 'vitest'
import { requestTransfer } from './transfers'
import transfersSource from './transfers.ts?raw'
import { plainModuleRules } from '../testsupport/plainModule'
import { requestSent, requestedPath, whileFetchAnswers } from '../testsupport/fetchAnswers'
import type { NewTransfer, Transfer } from './types'

const A_KEY = '3f1c9d2e-4b7a-4c6d-9e21-0a5b8c7d6e4f'

const A_TRANSFER_OF_25_EUR: NewTransfer = { fromAccountId: 1, toAccountId: 2, amountMinorUnits: 25_00 }

const THE_REQUESTED_TRANSFER: Transfer = {
  id: 7,
  fromAccountId: 1,
  toAccountId: 2,
  status: 'PENDING',
  debitedAmountMinorUnits: 25_00,
  debitedAmountCurrency: 'EUR',
  creditedAmountMinorUnits: 25_00,
  creditedAmountCurrency: 'EUR',
  createdAt: '2026-01-01T00:00:00Z',
}

const INSUFFICIENT_FUNDS = {
  type: 'urn:problem:insufficient-funds',
  title: 'Unprocessable Entity',
  status: 422,
  detail: "The source Account's Available Balance does not cover this Transfer.",
}

const requestingIt = (key = A_KEY) => () => requestTransfer(A_TRANSFER_OF_25_EUR, key)

describe('requestTransfer', () => {
  it('posts the request and hands back the PENDING Transfer the service opened', async () => {
    await expect(whileFetchAnswers(201, THE_REQUESTED_TRANSFER, requestingIt())).resolves.toEqual(
      THE_REQUESTED_TRANSFER,
    )

    expect(requestedPath()).toBe('/api/transfers')
    expect(requestSent()?.method).toBe('POST')
  })

  /**
   * The header name is the backend's, spelled the way `TransferController` reads it. A
   * request that carried the key under any other name would be a request with no key at
   * all, which the endpoint refuses — and every retry after it would be a second Transfer.
   */
  it('carries the Idempotency Key it was handed, under the name the endpoint reads', async () => {
    await whileFetchAnswers(201, THE_REQUESTED_TRANSFER, requestingIt())

    expect(requestSent()?.headers).toEqual({
      'Content-Type': 'application/json',
      'X-Idempotency-Key': A_KEY,
    })
  })

  it('sends the key it was given rather than one of its own, so two attempts can share one', async () => {
    await whileFetchAnswers(201, THE_REQUESTED_TRANSFER, requestingIt())
    const first = requestSent()?.headers

    await whileFetchAnswers(201, THE_REQUESTED_TRANSFER, requestingIt())

    expect(requestSent()?.headers).toEqual(first)
  })

  /** The amount is a whole count of Minor Units by the time it gets here; nothing converts. */
  it('puts the Transfer on the wire as the shape the API takes', async () => {
    await whileFetchAnswers(201, THE_REQUESTED_TRANSFER, requestingIt())

    expect(JSON.parse(String(requestSent()?.body))).toEqual({
      fromAccountId: 1,
      toAccountId: 2,
      amountMinorUnits: 25_00,
    })
  })

  it('throws the parsed problem document itself when the API refuses', async () => {
    await expect(whileFetchAnswers(422, INSUFFICIENT_FUNDS, requestingIt())).rejects.toEqual(
      INSUFFICIENT_FUNDS,
    )
  })

  it('throws it unwrapped, since an Error would hide the URN the screen branches on', async () => {
    const thrown: unknown = await whileFetchAnswers(422, INSUFFICIENT_FUNDS, requestingIt()).catch(
      (failure: unknown) => failure,
    )

    expect(thrown).not.toBeInstanceOf(Error)
  })
})

/** Design decision 24's rule, asserted rather than trusted. */
describe('the module itself', () => {
  plainModuleRules(transfersSource, ['./types'])
})
