import type { NewTransfer, Transfer } from './types'

/**
 * Requesting a Transfer: the one request this application makes that moves money, and so
 * the only one that carries an Idempotency Key.
 *
 * The endpoint answers `201` with the Transfer in the `PENDING` status it opens in, and a
 * `Location` header pointing at that Transfer's own resource. Nothing here reads the
 * header — the body already carries the identifier, and the screen navigates by route
 * rather than by URL — but it is why the status is a `201` and not a `200`.
 *
 * **The key is a parameter and is never minted here.** `idempotency.ts` decides what a key
 * identifies, and a key minted inside this function would be a fresh one on every attempt:
 * the backend would see a new Transfer each time, and the retry the mechanism exists to
 * make safe would be the double charge it was built to prevent. The header name is the
 * backend's — `TransferController` reads `X-Idempotency-Key` and refuses a request without
 * one.
 *
 * The amount goes on the wire as the whole count of Minor Units it arrives as; the decimal
 * an operator typed was converted by `transferSchema.ts`, which is the module that knows
 * how many decimals the source Account's Currency has.
 *
 * A refusal is thrown as the parsed body rather than wrapped in an `Error`, as
 * `accounts.ts` throws one and for the same reason: `problem.ts` reads its `type` URN to
 * choose the heading and whether a retry is worth offering, `validation.ts` reads its
 * `errors` to put a sentence beside each field, and an `Error` would hide all of it behind
 * a message.
 */
export const requestTransfer = async (
  transfer: NewTransfer,
  idempotencyKey: string,
): Promise<Transfer> => {
  const response = await fetch('/api/transfers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Idempotency-Key': idempotencyKey },
    body: JSON.stringify(transfer),
  })
  const body: unknown = await response.json()

  if (!response.ok) throw body

  return body as Transfer
}
