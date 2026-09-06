import { isRecord } from './records'

/**
 * How many times a query is attempted in total, the first try included.
 */
export const MAX_QUERY_ATTEMPTS = 3

/**
 * Decides whether a failed query is worth attempting again.
 *
 * Only a `5xx` is: it says the server could not answer, so the same question
 * asked again may get one. Every other outcome is an answer already, and the
 * narrowing exists to prevent two retries in particular: a `422`, the server's
 * considered rejection, which an unchanged request will simply collect again;
 * and a `409`, which carries a `Retry-After` the client is meant to obey rather
 * than race with a backoff timer of its own.
 *
 * @param failureCount how many attempts have already failed
 * @param error whatever the query function threw
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  const status = httpStatusOf(error)
  return failureCount < MAX_QUERY_ATTEMPTS - 1 && status !== undefined && status >= 500
}

function httpStatusOf(error: unknown): number | undefined {
  if (!isRecord(error)) return undefined
  const { status } = error
  return typeof status === 'number' ? status : undefined
}
