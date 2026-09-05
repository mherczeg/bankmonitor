/**
 * The stand-in Exchange Rate provider: a real HTTP endpoint, profile-gated, deliberately
 * slow and flaky.
 *
 * <p>It depends on nothing in this application and nothing depends on it. That isolation
 * is the point — it stands in for a third party, so it is reached over HTTP like one and
 * stays outside the security, error-handling and CORS layers that belong to us. A stub
 * bean would sit above the HTTP client and mock out the very resilience being
 * demonstrated.
 */
package hu.bankmonitor.payments.mockfx;
