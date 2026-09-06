/**
 * Whether a value is one whose members can be read by name.
 *
 * Three modules in this folder start by asking it, of values that arrive with no type
 * behind them: `retry.ts` of whatever a query function threw, `problem.ts` of a parsed
 * response body, `idempotency.ts` of each member it walks. Written out at each of them
 * it is one line with two traps in it — `typeof null` is `'object'`, and so is an
 * array, which has no members worth reading by name and must not be walked as though
 * it had.
 */
export const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
