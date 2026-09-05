# 34: Turning a problem document into something readable

**What to build:** A pure function mapping a problem document onto what the UI needs:
a title, a body, and **whether retrying will help**. That last field is the point — an
operator seeing an error should know whether to try again or to change something.

It branches on the `type` URN and on nothing else. The backend made that URN the sole
discriminator precisely so no client has to reconcile two fields that can disagree; the
frontend must not reintroduce the problem by also inspecting status codes.

The URNs come from the generated types, so a URN the backend stopped emitting — or a new
one it started emitting — shows up as a type error rather than as a silently unhandled
case.

A table test over every URN the backend can emit, including the two `409`s that mean
opposite things: request-in-progress is retryable, key-reused is never.

**Blocked by:** 32

**Status:** ready-for-agent

- [ ] A pure function maps a problem document to title, body and retryability
- [ ] It branches on the type URN only
- [ ] Both `409` URNs are handled and have opposite retryability
- [ ] An unrecognised problem document degrades to a readable generic message
- [ ] A table test covers every URN the backend emits, sourced from the generated types
