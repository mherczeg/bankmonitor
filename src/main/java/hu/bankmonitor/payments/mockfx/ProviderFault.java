package hu.bankmonitor.payments.mockfx;

/**
 * How the stand-in provider says no.
 *
 * <p>Deliberately not an RFC 9457 problem document. This application's error contract is
 * one shape with one discriminator, and a client that has parsed one of ours has parsed
 * all of them — a third party that answered in that shape would make the contract a lie
 * and would let a caller branch on a {@code type} URN it should never see. The members
 * here are the plainest thing a JSON API returns instead, and ticket 25's client is
 * expected to map them onto our vocabulary rather than pass them through.
 *
 * @param error   a stable machine-readable code, this provider's own vocabulary
 * @param message a sentence for whoever is reading the response by hand
 */
record ProviderFault(String error, String message) {
}
