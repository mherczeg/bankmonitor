import { expect, it } from 'vitest'

/**
 * Design decision 24's extraction rule as two `it`s, emitted into the enclosing
 * `describe`: that the module imports only what it is allowed to, and that React is not
 * in it in any form an import can take. They go into the caller's `describe` rather than
 * one of their own so a caller with a further rule about the same source can add it
 * alongside.
 *
 * The subject is the module's **source text**, read with Vite's `?raw`, because that is
 * what the rule is about — a normal import sees the module's exports and cannot see
 * what it reached for to produce them.
 */
export const plainModuleRules = (source: string, importing: readonly string[]): void => {
  it(`imports ${importing.length === 0 ? 'nothing at all' : `nothing but ${importing.join(', ')}`}`, () => {
    const specifiers = [...source.matchAll(/\bimport\b[^;\n]*?['"]([^'"]+)['"]/g)]

    expect(specifiers.map(([, specifier]) => specifier)).toEqual(importing)
  })

  it('does not name React at all, in any form an import can take', () => {
    expect(source).not.toMatch(/react/i)
  })
}
