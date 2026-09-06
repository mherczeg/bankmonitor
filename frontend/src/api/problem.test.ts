import { describe, expect, it } from 'vitest'
import { PROBLEM_TYPES, problemToMessage, UNRECOGNISED_PROBLEM } from './problem'
import problemSource from './problem.ts?raw'
import schemaSource from './schema.gen.ts?raw'
import { plainModuleRules } from '../testsupport/plainModule'
import type { ProblemDocument, ProblemType } from './types'

/**
 * The expected advice for every URN the backend can emit, typed against the generated
 * union so that a URN added or removed on the backend fails to compile here.
 */
const RETRYING_HELPS: Record<ProblemType, boolean> = {
  'urn:problem:validation-failed': false,
  'urn:problem:malformed-request': false,
  'urn:problem:unsupported-media-type': false,
  'urn:problem:method-not-allowed': false,
  'urn:problem:not-found': false,
  'urn:problem:request-in-progress': true,
  'urn:problem:idempotency-key-reused': false,
  'urn:problem:fx-provider-unavailable': true,
  'urn:problem:client-error': true,
  'urn:problem:internal-error': true,
}

/**
 * The URNs as the generated schema declares them, read out of that file so that `npm
 * test` on its own catches a backend that has grown or dropped one. The compiler catches
 * it too, through `Record<ProblemType, …>`, but only under `npm run build`.
 */
const urnsInGeneratedSchema = (): string[] => {
  const union = /^\s*ProblemType:\s*(.+);$/m.exec(schemaSource)?.[1] ?? ''

  return [...union.matchAll(/"([^"]+)"/g)].map(([, urn]) => urn).sort()
}

const documentOf = (type: ProblemType, status: number): ProblemDocument => ({
  type,
  title: 'Conflict',
  status,
  detail: 'A sentence written for whoever is reading the response.',
  instance: '/api/transfers',
})

describe('the advice a problem document carries', () => {
  it('is known for every URN the generated schema declares, and for no URN it does not', () => {
    expect([...PROBLEM_TYPES].sort()).toEqual(urnsInGeneratedSchema())
  })

  it('is expected below for all of them, so the table that follows leaves none out', () => {
    expect([...PROBLEM_TYPES].sort()).toEqual(Object.keys(RETRYING_HELPS).sort())
  })

  for (const type of PROBLEM_TYPES) {
    it(`says whether retrying helps for ${type}`, () => {
      const message = problemToMessage(documentOf(type, 409))

      expect(message.retryable).toBe(RETRYING_HELPS[type])
      expect(message.title).not.toBe('')
      expect(message.body).not.toBe('')
    })
  }

  it('gives the two 409s opposite advice, which is why the URN is the discriminator', () => {
    const inProgress = problemToMessage(documentOf('urn:problem:request-in-progress', 409))
    const keyReused = problemToMessage(documentOf('urn:problem:idempotency-key-reused', 409))

    expect(inProgress.retryable).toBe(true)
    expect(keyReused.retryable).toBe(false)
    expect(inProgress.title).not.toBe(keyReused.title)
  })

  it('reads the URN and nothing else, so an implausible HTTP code changes no answer', () => {
    for (const type of PROBLEM_TYPES) {
      const answers = [200, 400, 409, 418, 500].map((code) => problemToMessage(documentOf(type, code)))

      expect(new Set(answers.map((answer) => JSON.stringify(answer))).size).toBe(1)
    }
  })
})

describe('a failure that is not a problem document this app knows', () => {
  it('degrades to the generic message rather than to a blank screen', () => {
    const unrecognised = [
      { ...documentOf('urn:problem:not-found', 404), type: 'urn:problem:account-frozen' },
      { type: 'urn:problem:not-found ' },
      { type: 'toString' },
      { type: 42 },
      {},
      [],
      '<html><body>502 Bad Gateway</body></html>',
      null,
      undefined,
      new Error('Failed to fetch'),
    ]

    for (const failure of unrecognised) {
      expect(problemToMessage(failure)).toEqual(UNRECOGNISED_PROBLEM)
    }
  })

  it('tells the operator to try again, since a held Idempotency Key makes that safe', () => {
    expect(UNRECOGNISED_PROBLEM.retryable).toBe(true)
  })

  it('is not reachable by a URN the backend does emit', () => {
    for (const type of PROBLEM_TYPES) {
      expect(problemToMessage(documentOf(type, 400))).not.toEqual(UNRECOGNISED_PROBLEM)
    }
  })
})

/**
 * Design decision 24's rule and this ticket's second requirement, asserted rather than
 * trusted. The third assertion is why the module's own doc comment says "response code" —
 * see `docs/design-decisions/34-problem-document-module.md`.
 */
describe('the module itself', () => {
  plainModuleRules(problemSource, ['./records', './types'])

  it('does not name the one member of the document it must not branch on', () => {
    expect(problemSource).not.toMatch(/status/i)
  })
})
