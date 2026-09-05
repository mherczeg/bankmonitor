# 44: The README and the handover

**What to build:** The half of the deliverable that is not code, and is graded as heavily
as the code.

The README must answer, in the submitter's own words rather than by linking away:

- **Architecture and decisions** — what was chosen on the backend and the frontend, and
  crucially **what was rejected and why**. The design record has the long form; the README
  needs the argument, not a table of contents.
- **Approach** — whether there was a plan before the first line of code, where it was kept,
  which layer the work started with and why. The session logs and the ticket files under
  `.scratch/` are the evidence.
- **Edge cases** — how idempotency, concurrency and the flaky provider were actually handled,
  including the reservation model and the ordered locking.
- **The TODO list** — drawn from `docs/deferred.md`, in a defended order. The task states
  this is weighted as heavily as the code, so the ordering rationale matters more than the
  length of the list. Include the test coverage consciously not attempted, and mark
  **verifying the locking design against a production database as a prerequisite**, not a
  nice-to-have: H2's lock timeout differs from Postgres's, so a passing test here can fail
  there on timing alone, and it is the check on the design's central integrity claim.
- **Production readiness** — what a real deployment needs, and what the next sprint would be.
- **Running it** — build, run and test commands for both processes, with no Docker required.

**The AI-use documentation is not a `PROMPTS.md` in the form the task describes.** It is the
committed session logs plus a workflow write-up: the skills, agents and configuration files
built around the work, where the AI's suggestion was accepted, where it was corrected, and
where it was thrown away. Say plainly why that form was chosen over a transcript of prompts.

Also fold in anything the build discovered: spikes that failed, decisions amended
mid-flight (the migration-per-slice change is already one), and gaps found while testing.

**Blocked by:** every other ticket

**Status:** ready-for-agent

- [ ] Architecture, decisions and rejections are argued, not linked
- [ ] The approach section points at the tickets and session logs as evidence
- [ ] Edge cases cover idempotency, concurrency and the flaky provider concretely
- [ ] The TODO list is ordered and each item's ordering is defended
- [ ] Postgres lock verification is listed as a production prerequisite
- [ ] Build, run and test instructions work on a clean clone with no Docker
- [ ] The AI-workflow write-up exists and explains its form
- [ ] Decisions amended during the build are reflected in the record, not only in the README
