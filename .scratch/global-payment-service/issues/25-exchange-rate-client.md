# 25: The Exchange Rate client that survives a flaky provider

**What to build:** The `ExchangeRateProvider` port and its HTTP client — one of the three
public ports in the system, and a substitution seam. It reads a configurable base URL, so
it points at the stand-in provider in development and at a real one later without a code
change.

The resilience policy, deliberately lean:

- **Connect and read timeouts**, so a slow provider cannot park a request forever holding
  an in-progress Idempotency Key.
- **Retry `5xx` responses and timeouts**, a bounded number of times, so an intermittent
  fault is invisible to the caller.
- **Never retry `4xx`** — a deterministic error turned into four of them is just four
  errors.
- **No cache and no circuit breaker.** A breaker protects a scarce thread pool, and virtual
  threads mean threads are not scarce. Both are deferred with reasoning recorded.

**Two details every tutorial predates:** Spring Framework 7 has native retry, so neither
`spring-retry` nor Resilience4j is needed; the enabling annotation is
`@EnableResilientMethods`, and the attempt setting is `maxRetries`, not `maxAttempts`.

**The test double sits below the port, on the wire.** Bind a mock server to the client
builder and script `503, 503, 200`. Doubling the Java interface instead would put the
failure simulation *above* the HTTP client, so the retries being demonstrated would never
run. And **assert the retry actually happened** — Framework 7 publishes a retry event a
listener can observe, so resilience is tested rather than claimed.

**Known gap:** a mock server bound above the transport cannot exercise the read timeout.
That is deferred with its reopening condition named — if the timeout path needs real
coverage while writing the suite, add a wire-level mock for that single test.

**Blocked by:** 01

**Status:** done

- [x] A one-method port returns a rate for a Currency pair, with the transport behind it
- [x] Connect and read timeouts are configured and documented
- [x] `5xx` and timeouts are retried a bounded number of times; `4xx` is never retried
- [x] A test scripting `503, 503, 200` succeeds, and asserts via the retry event that a
      retry occurred
- [x] Exhausted retries surface as a distinct failure the caller can map, not a generic one
