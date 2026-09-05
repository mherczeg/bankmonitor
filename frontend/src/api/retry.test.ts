import { describe, expect, it } from 'vitest'
import { MAX_QUERY_ATTEMPTS, shouldRetryQuery } from './retry'

const failedWith = (status: number) => Object.assign(new Error(`HTTP ${status}`), { status })

describe('shouldRetryQuery', () => {
  it('retries a server-side failure', () => {
    for (const status of [500, 502, 503, 504]) {
      expect(shouldRetryQuery(0, failedWith(status))).toBe(true)
    }
  })

  it('does not retry a 422, which would repeat a rejected request unchanged', () => {
    expect(shouldRetryQuery(0, failedWith(422))).toBe(false)
  })

  it('does not retry a 409, whose Retry-After sets the schedule', () => {
    expect(shouldRetryQuery(0, failedWith(409))).toBe(false)
  })

  it('does not retry any other client-side failure', () => {
    for (const status of [400, 401, 403, 404]) {
      expect(shouldRetryQuery(0, failedWith(status))).toBe(false)
    }
  })

  it('stops once the attempt cap is reached', () => {
    expect(shouldRetryQuery(MAX_QUERY_ATTEMPTS - 2, failedWith(500))).toBe(true)
    expect(shouldRetryQuery(MAX_QUERY_ATTEMPTS - 1, failedWith(500))).toBe(false)
    expect(shouldRetryQuery(MAX_QUERY_ATTEMPTS, failedWith(500))).toBe(false)
  })

  it('does not retry a failure that carries no status', () => {
    expect(shouldRetryQuery(0, new Error('Failed to fetch'))).toBe(false)
    expect(shouldRetryQuery(0, { status: '500' })).toBe(false)
    expect(shouldRetryQuery(0, null)).toBe(false)
    expect(shouldRetryQuery(0, 'boom')).toBe(false)
  })
})
