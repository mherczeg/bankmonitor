import { rejectedFieldsIn } from './api/validation'

/**
 * The step between a refusal of a request and the form that has to show it: which of a
 * form's own fields the service named, and what appears under one of them.
 *
 * The two halves are here rather than in `api/validation.ts` because they are about a
 * form and not about a document — the wire member a refusal names and the input an
 * operator can correct are deliberately different names, and only the form knows the map
 * between them.
 *
 * The wording is not: what each form says about its own rules is written in that form's
 * schema, on ticket 33's reasoning that a module owning a rule does not own the copy.
 */

/** What the service refused, sorted into the fields that can be corrected and the rest. */
export interface ServerRefusal<TField extends string> {
  /** One message per field of the form that the service named. */
  readonly perField: Readonly<Partial<Record<TField, string>>>

  /**
   * What the service refused that no field can show. A member with no field is not
   * dropped — a message nobody sees is worse than one in the wrong place — so it is
   * listed with the refusal instead, under the member name the service used.
   */
  readonly unattached: readonly string[]
}

/** Which field of a form a member of its request belongs to. */
export type FieldForMember<TField extends string> = Readonly<Partial<Record<string, TField>>>

/**
 * Reads whatever a request failed with and says which of a form's fields it refused.
 *
 * Anything that is not a problem document naming members — including the `null` a
 * mutation that has not failed carries — is nothing refused.
 */
export const formRefusalIn = <TField extends string>(
  failure: unknown,
  fieldForMember: FieldForMember<TField>,
): ServerRefusal<TField> => {
  const { byField, overall } = rejectedFieldsIn(failure)
  const perField: Partial<Record<TField, string>> = {}
  const unattached = [...overall]

  for (const [member, message] of byField) {
    const field = fieldForMember[member]

    if (field === undefined) unattached.push(`${member}: ${message}`)
    else perField[field] = message
  }

  return { perField, unattached }
}

/**
 * What is shown under one field: what the schema said about what is in the box now, and
 * what the service said about what was last sent.
 *
 * The client-side messages come first because they describe the current value, while a
 * server one describes a payload that may already have been corrected.
 *
 * The first argument is a form library's error list rather than a shape named here,
 * because a Standard Schema issue reaches the field as whatever that library kept of it.
 */
export const messagesUnder = (
  errors: readonly unknown[],
  fromServer: string | undefined,
): readonly string[] => [
  ...errors.flatMap(messageIn),
  ...(fromServer === undefined ? [] : [fromServer]),
]

const messageIn = (error: unknown): string[] => {
  if (typeof error === 'string') return [error]
  if (typeof error === 'object' && error !== null && 'message' in error) return [String(error.message)]

  return []
}
