import type { Account, ProblemDocument, ProblemType, Transfer } from '../../src/api/types'

/**
 * The shapes the API answers with, built from the generated types so that a spec cannot
 * invent one.
 *
 * That is what makes a browser spec worth running: without it, a spec proves the frontend
 * handles shapes its own author made up. Every builder here returns a whole generated
 * type, so a member the backend adds, drops or renames fails `tsc` on this file — a
 * partial fixture would type-check forever and answer requests with a shape the backend
 * cannot send.
 *
 * Overrides are named, not merged blindly, where two members have to agree with each
 * other.
 */

/**
 * One Account, whose Available Balance is derived rather than given: the backend
 * guarantees it is the balance less what is reserved, so a fixture free to contradict that
 * would let a screen be tested against an Account that cannot exist.
 *
 * A spec that wants the impossible one — for a rendering that has to survive it — says so
 * by overriding `availableBalanceMinorUnits` explicitly.
 */
export const anAccount = ({
  balanceMinorUnits = 100_00,
  reservedAmountMinorUnits = 0,
  ...overrides
}: Partial<Account> = {}): Account => ({
  id: 1,
  currency: 'EUR',
  balanceMinorUnits,
  reservedAmountMinorUnits,
  availableBalanceMinorUnits: balanceMinorUnits - reservedAmountMinorUnits,
  ...overrides,
})

/**
 * One Transfer, `PENDING` unless a spec says otherwise, moving a figure that both
 * Accounts agree on: this service cannot convert between Currencies yet, so a fixture
 * whose credited and debited sides differed would be one the backend refuses outright.
 *
 * A spec asserting a settlement overrides `status` and nothing else — the amounts do not
 * move when a Transfer finishes, which is what makes the Accounts endpoint rather than
 * this one the place a balance change shows up.
 */
export const aTransfer = ({
  debitedAmountMinorUnits = 25_00,
  debitedAmountCurrency = 'EUR',
  ...overrides
}: Partial<Transfer> = {}): Transfer => ({
  id: 7,
  fromAccountId: 1,
  toAccountId: 2,
  status: 'PENDING',
  debitedAmountMinorUnits,
  debitedAmountCurrency,
  creditedAmountMinorUnits: debitedAmountMinorUnits,
  creditedAmountCurrency: debitedAmountCurrency,
  createdAt: '2026-01-01T00:00:00Z',
  ...overrides,
})

/**
 * One refusal, named by the URN a client branches on.
 *
 * `title` and `detail` are the backend's own wording — a status reason phrase and a
 * sentence written for whoever reads the response — and are deliberately not advice, so a
 * spec asserting on rendered advice is asserting on `problem.ts` rather than on a string
 * the fixture handed it.
 */
export const aProblem = (
  type: ProblemType,
  { status = 422, ...overrides }: Partial<ProblemDocument> = {},
): ProblemDocument => ({
  type,
  title: 'Unprocessable Entity',
  status,
  detail: 'The request was understood and could not be acted on.',
  instance: '/api',
  ...overrides,
})
