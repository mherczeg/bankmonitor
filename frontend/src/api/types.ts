import type { components, paths } from './schema.gen'

/**
 * The API's shapes under the names the app calls them, and the only module that
 * reads `schema.gen.ts`.
 *
 * Nothing here is written by hand: every alias resolves to the generated file, so
 * a member the backend renames or a URN it stops emitting arrives as a type error
 * at the screen that used it. What the indirection buys is readable names — the
 * generated shapes are reached through `components['schemas'][...]`, which reads
 * badly at a call site and would put the generated file's path in every module
 * that touches the API.
 *
 * The set mirrors what the API publishes, not what today's screens happen to
 * import — an alias with no caller yet is one line naming a shape the backend
 * already sends, and leaving it out would only mean the next screen reaches past
 * this module for it.
 *
 * Regenerating is `npm run api-types`, against a running backend. See the README.
 */

/** One Account as the API reports it, balances included. */
export type Account = components['schemas']['AccountResponse']

/** What opening an Account asks for. */
export type NewAccount = components['schemas']['CreateAccountRequest']

/** The three denominations this service quotes, as the backend declares them. */
export type Currency = Account['currency']

/** One Transfer as the API reports it, in whatever status it has reached. */
export type Transfer = components['schemas']['TransferResponse']

/** What requesting a Transfer asks for. */
export type NewTransfer = components['schemas']['CreateTransferRequest']

/**
 * Where a Transfer has got to. A Transfer leaves `PENDING` exactly once and never
 * moves again, which is what makes the stream's three terminal events the complete
 * set — see `events.ts`.
 */
export type TransferStatus = Transfer['status']

/** The one shape every failure of the API arrives as (RFC 9457). */
export type ProblemDocument = components['schemas']['ProblemDocument']

/**
 * The URN naming what went wrong, and the only member of a problem document a
 * client may branch on. Two `409`s mean opposite things and the status cannot
 * tell them apart, which is why the backend made this the sole discriminator.
 */
export type ProblemType = components['schemas']['ProblemType']

/** One rejected value, against the field that carried it. */
export type ValidationError = components['schemas']['ValidationError']

/**
 * Every path the API publishes, spelled the way the document spells it —
 * `/api/transfers/{id}`, not a URL with an identifier already in it.
 *
 * It exists for the browser harness, which scripts one answer per path: a path
 * annotated with this type stops compiling the day the backend renames or drops
 * it, so a mock still answering a URL nothing calls is a build failure rather
 * than a spec that passes for the wrong reason.
 */
export type ApiPath = keyof paths
