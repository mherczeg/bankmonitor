# 32: Frontend types generated from the backend's OpenAPI document

**What to build:** A build step that generates the frontend's API types from the OpenAPI
document the backend serves, and a checked-in generated file the app imports from.

This is not a convenience. It is the only thing standing between the frontend test suite
and self-congratulation. The browser tests mock the network, so without generated types
they prove the frontend handles shapes **the test author invented**. With them, a mock
returning a shape the backend no longer produces is a failed build rather than a green
test.

It also puts the problem-type URNs in exactly one place, shared by both runtimes.

**The named risk, recorded rather than solved:** the generated file can go stale if nobody
regenerates it. Make regeneration a documented command, and treat a diff in the generated
file as a signal worth reading, not noise to commit past.

**Blocked by:** 10, 31

**Status:** done

- [x] A documented command regenerates the types from a running backend
- [x] The frontend imports its API types from the generated file only
- [x] A deliberately wrong hand-written shape fails the build, demonstrated once
- [x] The problem-type URNs are available to the frontend from the generated output
- [x] How and when to regenerate is written down where the next slice will look
