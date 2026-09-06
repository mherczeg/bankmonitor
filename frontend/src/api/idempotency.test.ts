import { describe, expect, it } from 'vitest'
import { startIntent } from './idempotency'
import idempotencySource from './idempotency.ts?raw'
import { plainModuleRules } from '../testsupport/plainModule'

/** Version 4, variant 1 — what `crypto.randomUUID` mints and what the backend accepts. */
const WELL_FORMED_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

interface TransferIntent {
  readonly fromAccountId: string
  readonly toAccountId: string
  readonly amountMinor: number
}

const A_TRANSFER: TransferIntent = {
  fromAccountId: '018f3a7c-0000-7000-8000-000000000001',
  toAccountId: '018f3a7c-0000-7000-8000-000000000002',
  amountMinor: 50_000,
}

describe('the key an intent is submitted under', () => {
  it('is a well-formed UUID, which is all the backend accepts', () => {
    expect(startIntent().keyFor(A_TRANSFER)).toMatch(WELL_FORMED_UUID)
  })

  it('is the same on every read, so reading it is not what mints it', () => {
    const keys = startIntent()

    expect(keys.keyFor(A_TRANSFER)).toBe(keys.keyFor(A_TRANSFER))
  })

  it('is held across repeated failed attempts of the same intent', () => {
    const keys = startIntent()
    const firstAttempt = keys.keyFor(A_TRANSFER)

    const laterAttempts = [keys.keyFor(A_TRANSFER), keys.keyFor(A_TRANSFER), keys.keyFor(A_TRANSFER)]

    expect(laterAttempts).toEqual([firstAttempt, firstAttempt, firstAttempt])
  })

  it('is held across an intent rebuilt member by member rather than reused', () => {
    const keys = startIntent()
    const firstAttempt = keys.keyFor(A_TRANSFER)

    expect(keys.keyFor({ ...A_TRANSFER })).toBe(firstAttempt)
  })

  it('does not depend on the order the intent names its members in', () => {
    const keys = startIntent()
    const firstAttempt = keys.keyFor(A_TRANSFER)

    const reordered: TransferIntent = {
      amountMinor: A_TRANSFER.amountMinor,
      toAccountId: A_TRANSFER.toAccountId,
      fromAccountId: A_TRANSFER.fromAccountId,
    }

    expect(keys.keyFor(reordered)).toBe(firstAttempt)
  })

  it('does not depend on member order nested inside the intent either', () => {
    const keys = startIntent()
    const firstAttempt = keys.keyFor({ transfer: A_TRANSFER })

    const reordered = {
      transfer: {
        amountMinor: A_TRANSFER.amountMinor,
        toAccountId: A_TRANSFER.toAccountId,
        fromAccountId: A_TRANSFER.fromAccountId,
      },
    }

    expect(keys.keyFor(reordered)).toBe(firstAttempt)
  })
})

describe('what makes the next read a new intent', () => {
  it('a success, so the same Transfer requested deliberately twice is two Transfers', () => {
    const keys = startIntent()
    const submitted = keys.keyFor(A_TRANSFER)

    keys.succeeded()

    expect(keys.keyFor(A_TRANSFER)).not.toBe(submitted)
  })

  it('a corrected amount, which the backend would otherwise refuse as a reused key', () => {
    const keys = startIntent()
    const refused = keys.keyFor(A_TRANSFER)

    expect(keys.keyFor({ ...A_TRANSFER, amountMinor: 10_000 })).not.toBe(refused)
  })

  it('a corrected destination Account', () => {
    const keys = startIntent()
    const refused = keys.keyFor(A_TRANSFER)

    const elsewhere = { ...A_TRANSFER, toAccountId: '018f3a7c-0000-7000-8000-000000000003' }

    expect(keys.keyFor(elsewhere)).not.toBe(refused)
  })

  it('a correction undone, because the key it would return to may already be spent', () => {
    const keys = startIntent()
    const first = keys.keyFor(A_TRANSFER)

    keys.keyFor({ ...A_TRANSFER, amountMinor: 10_000 })

    expect(keys.keyFor(A_TRANSFER)).not.toBe(first)
  })

  it('a form opened again, so two screens never submit under one key', () => {
    expect(startIntent().keyFor(A_TRANSFER)).not.toBe(startIntent().keyFor(A_TRANSFER))
  })
})

describe('a failure', () => {
  it('cannot reset the key, because the module is never told one happened', () => {
    expect(Object.keys(startIntent())).toEqual(['keyFor', 'succeeded'])
  })
})

/**
 * Design decision 24's rule, asserted rather than trusted — the reasoning is in
 * `docs/design-decisions/35-idempotency-key-module.md`.
 */
describe('the module itself', () => {
  plainModuleRules(idempotencySource, ['./records'])
})
