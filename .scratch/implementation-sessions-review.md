# Implementation sessions, by ticket

Scope: only sessions that did real ticket work — invoked `/mattpocock-skills:implement`,
or picked up a `handoff-*.md` file and continued/fixed a ticket. Pure `/land-worktree`
sessions and general planning/grilling/domain-modeling sessions are excluded (listed at
the bottom for reference).

Two directory families hold these, both under `~/.claude/projects/`:
- **[main]** = `-data-mherczeg-projects-bankmonitor/<uuid>.jsonl`
- **[wt:<name>]** = `-data-mherczeg-projects-bankmonitor--claude-worktrees-<name>/<uuid>.jsonl`
  (a genuinely separate `claude` process started with cwd inside `.claude/worktrees/<name>`)

Important: several sessions' `ai-title` is flat-out wrong for that session — most visibly,
all 3 sessions in `wt:ticket-30-sse-stream` are auto-titled **"Agents tickets 21, 26, 39"**,
which is actually the title that belongs to an unrelated main-dir status-check session.
Do not trust `ai-title` alone; I've labeled below by what the session actually did.

---

## Ticket 01 — project-skeleton
1. `[main] 5bc4b6c0-0b58-412e-a5ab-e07bc499c240.jsonl` — `/mattpocock-skills:implement 01`, **interrupted immediately, 0 edits.** Aborted attempt, superseded by #2.
2. `[main] e7a1d34a-dd52-46af-8e0d-008d0feb84f2.jsonl` — real implementation, 24 edits. ai-title: "Global dependencies" (misleading).

## Ticket 02 — flyway-wiring
1. `[main] fd001ac2-f03b-42ea-b134-3b0d45826804.jsonl` — 28 edits. ai-title: "Design decision file vs README documentation" (misleading — that's a side effect, not the topic).

## Ticket 03 — package-skeleton-archunit
1. `[main] ccad384d-2681-43d7-a24f-b06f4b50ec05.jsonl` — 27 edits, no ai-title.

## Ticket 04 — security-chain-cors
1. `[main] 5cf28da6-1534-4464-b16c-a355d8294c0f.jsonl` — 15 edits, no ai-title.

## Ticket 05 — problem-detail-contract
1. `[main] 149301d6-9b8c-43e9-9c9b-6ec96abc8cdd.jsonl` — 0 edits captured before a model switch. ai-title: "Model switching to opus" (misleading — that happened mid-ticket).
2. `[main] b7cd714c-bf6f-4783-8b30-a9a1d5d1823d.jsonl` — 23 edits, the real work. No ai-title.

## Ticket 06 — money-and-currency
1. `[main] dc0284f7-232b-4ea4-a64f-9f69265a7c5f.jsonl` — start, 22 edits, then `/mattpocock-skills:handoff` ("context is getting bloated"). ai-title: "mattpocock-skills handoff".
2. `[main] c82b07af-17fb-467d-a1a3-29b3484ad052.jsonl` — reads `handoff-ticket-06.md`, continues, 9 edits. ai-title: "Bankmonitor handoff ticket 06".

## Ticket 07 — conversion-function
1. `[main] 50acc61a-8655-484a-9662-1153191a4056.jsonl` — told to "work on a worktree copy" but did it inline in main, 14 edits. ai-title: "Git push worktree" (misleading).

## Ticket 08 — account-entity
1. `[main] a50a91f7-74b2-4588-81b3-2e004421278d.jsonl` — also told to use a worktree, worked inline, 12 edits. ai-title: "Long vs UUID comparison" (a sub-topic, not the whole session).

## Ticket 09 — create-account-endpoint
1. `[wt:09-create-account-endpoint] bad8ee19-b4a6-4087-9b47-e6449493f6c3.jsonl` — implement start, 17 edits, then handoff. ai-title: "Handoff mattpocock-skills implementation".
2. `[wt:09-create-account-endpoint] 3e6363b6-0813-43a3-8332-7a7c6b6127ae.jsonl` — reads `bankmonitor-ticket-09-handoff.md`, continues, 44 edits. ai-title: "Bankmonitor ticket 09 handoff".

## Ticket 10 — list-accounts-and-seed
1. `[wt:10-list-accounts-and-seed] c520ebd7-4f7f-4f67-a75e-56a560bc16a0.jsonl` — implement start, 21 edits, "context blown" handoff. ai-title: "Context blown".
2. `[wt:10-list-accounts-and-seed] 7bf2be31-12b0-40a9-9541-6949f183af86.jsonl` — continuation, 30 edits. ai-title: "Handoff ticket 10 list accounts and seed".
   *(Landed separately by `[main] 6b0c8b79...` — land-only, excluded here.)*

## Ticket 11 — transfer-entity
1. `[wt:11-transfer-entity] 9cf9f8a8-dcc8-468c-af6f-88ba279b4bff.jsonl` — implement + handoff in one session, 14 edits.
2. `[main] a1f88b91-b6a6-4e83-997e-89cfecb3bd1e.jsonl` — cwd is actually the worktree (started via cd, not a fresh `claude` launch), reads `handoff-11-transfer-entity.md`, continues, 27 edits. ai-title: "Handoff 11 transfer entity implementation".

## Ticket 12 — ordered-account-locking
1. `[wt:12-ordered-account-locking] 8f3c594e-0436-4ed9-a4dc-c3befb5feb72.jsonl` — implement + handoff, 17 edits.
2. `[main] 7919b91f-cec8-43ec-9f20-de7114b63af3.jsonl` — reads `handoff-ticket-12-ordered-account-locking.md`, continues, 14 edits. ai-title: "Ordered account locking implementation".

## Ticket 13 — reserve-funds
1. `[main] aab5bc1f-3991-4418-955d-36566a104800.jsonl` — `/mattpocock-skills:implement 13...`, **interrupted immediately, 0 edits.** Aborted attempt.
2. `[wt:13-reserve-funds] 75792cac-e07a-416d-b77d-7c5d35abf5ec.jsonl` — real implement start, 14 edits, then handoff.
3. `[wt:13-reserve-funds] 1d40d205-4543-417d-8193-a89da7684f4a.jsonl` — reads `handoff-13-reserve-funds.md`, continues, 36 edits. ai-title: "Handoff 13 reserve funds implementation".

## Ticket 14 — request-transfer-endpoint
1. `[wt:14-request-transfer-endpoint] 981ff6b9-43c9-4324-8a18-00b6bb7b81ea.jsonl` — implement start, 28 edits, then handoff ("context ballooned"). ai-title: "mattpocock-skills handoff".
2. `[wt:14-request-transfer-endpoint] 7fef0d2d-3cf9-4947-bda9-04f9d622687e.jsonl` — reads `bankmonitor-ticket-14-handoff.md`, only 4 edits (mostly investigation), hands off again. ai-title: "Bankmonitor ticket 14 handoff".
3. `[wt:14-request-transfer-endpoint] 64ea3160-93d5-453e-b7b1-861d18a24986.jsonl` — reads `bankmonitor-ticket-14-handoff-2.md`, 19 edits. ai-title: "Bankmonitor ticket 14 handoff" (same title as #2 — mislabel collision).

## Ticket 15 — list-transfers
1. `[wt:ticket-15-list-transfers] 97d88eaa-87be-4022-ba39-fb8a4ae5dc24.jsonl` — implement start, 17 edits, then handoff.
2. `[wt:ticket-15-list-transfers] dd3d84ec-48ce-4e61-8677-5bf149f9572a.jsonl` — reads `handoff-ticket-15-list-transfers.md`, continues, 12 edits. ai-title: "Handoff ticket 15 list transfers".

## Ticket 16 — idempotent-execution
1. `[wt:16-idempotent-execution] 77d8e953-a4a1-43af-adb9-9065cabb612e.jsonl` — implement start, 9 edits, then handoff.
2. `[wt:16-idempotent-execution] e43b711c-b9f7-4c05-99c2-c9a950145223.jsonl` — reads `handoff-ticket-16-review-findings.md`, **0 edits** (review/investigation only, no fixes applied here). ai-title: "Handoff ticket 16 review findings".

## Ticket 17 — duplicate-resolution
1. `[wt:ticket-17-duplicate-resolution] 475bdabe-6d4e-4258-ad6d-aaf5eb68f4c6.jsonl` — implement start, 4 edits, then handoff.
2. `[wt:ticket-17-duplicate-resolution] 3966ab48-4b08-413c-a18c-c16c8cc42df3.jsonl` — continuation, 14 edits, hands off again. ai-title: "Handoff ticket 17 duplicate resolution".
3. `[wt:ticket-17-duplicate-resolution] d39f0ef0-5483-409d-9f0f-17885018c441.jsonl` — reads `handoff-ticket-17-code-review.md`, 10 edits (review fixes). ai-title: "Ticket 17 code review".

## Ticket 18 — idempotency-concurrency
1. `[wt:ticket-18-idempotency-concurrency] 870353c8-f2fc-4b0a-8fee-e93d39069837.jsonl` — implement start, 4 edits, then handoff.
2. `[wt:ticket-18-idempotency-concurrency] 0ad0618c-a9bb-42eb-888f-f0cba5da4308.jsonl` — reads `bankmonitor-ticket-18-handoff.md`, **1 edit** (mostly delegated to a spawned Agent). ai-title: "Bankmonitor ticket 18 handoff".

## Ticket 19 — check-ledger-and-policy
1. `[wt:19-check-ledger-and-policy] 30db7a54-2a55-4708-8dcf-769cbf48d39d.jsonl` — implement start, 47 edits, then handoff.
2. `[wt:19-check-ledger-and-policy] 3c33bd66-ea4e-41e9-9a75-491559017e82.jsonl` — reads `handoff-19-check-ledger-review-fixes.md`, 23 edits. ai-title: "Ledger review fixes".

## Ticket 20 — record-verdict
1. `[main] cdaf7590-9eb6-437d-a9ff-1a3bd769d45b.jsonl` — kickoff only (0 edits, spawned the worktree work). ai-title: "Clean up worktree" (refers to a *different*, prior worktree it also cleaned up in the same session).
2. `[wt:20-record-verdict] 7c971104-82a0-4c58-a879-d021b1aaafbb.jsonl` — implement start, 1 edit (mostly reading), then handoff.
3. `[wt:20-record-verdict] fec07eef-927d-497d-8a71-c037eb592130.jsonl` — continuation, 20 edits, hands off again.
4. `[wt:20-record-verdict] b1b44636-ec0f-4b69-9b78-258c4c19fc69.jsonl` — **empty stub, 3.2KB, just `/clear`. Noise — skip.**
5. `[wt:20-record-verdict] f6060423-fc66-426c-965e-d9f15552a826.jsonl` — reads `handoff-ticket-20-remaining-work.md`, 26 edits. ai-title: "Implement skill handoff ticket remaining work".
6. `[wt:20-record-verdict] fe0b3808-b24d-46ed-ac94-b1f39b787697.jsonl` — reads `handoff-ticket-20-docs-remaining.md`, 24 edits (docs cleanup). ai-title: "Handoff ticket docs remaining".

## Ticket 21 — internal-verdict-endpoint
1. `[wt:ticket-21-internal-verdict-endpoint] fcc2a9ee-a76f-4c45-99aa-58ca5ede95a2.jsonl` — implement start, 19 edits, then handoff.
2. `[wt:ticket-21-internal-verdict-endpoint] d0604fcd-5f89-433a-8786-64b8e7ac40f7.jsonl` — continuation, 35 edits, hands off again. ai-title: "Handoff ticket 21".
3. `[wt:ticket-21-internal-verdict-endpoint] ae611aa4-f7fe-4f07-8a90-2a04bf48454c.jsonl` — reads `handoff-ticket-21-remaining.md`, 28 edits. ai-title: "Ticket 21 internal verdict endpoint".
   *(NOT part of this chain: `[main] 0e52c9ea...` and `[main] f1f30bc5...`, both auto-titled "Agents tickets 21, 26, 39" — those are separate main-dir status-check/orchestration sessions with zero code edits, not ticket-21 implementation. See "excluded" list below.)*

## Ticket 22 — check-ledger-on-transfer-detail
1. `[wt:ticket-22] 780dba52-9514-47d9-b130-0e3aa7c9e597.jsonl` — implement start, 27 edits, then handoff.
2. `[wt:ticket-22] a9eb78fd-a287-4b0b-9451-02716d6cbb8b.jsonl` — continuation, 26 edits. No ai-title on either.

## Ticket 24 — mock-fx-provider
1. `[wt:24-mock-fx-provider] ba3e9e06-c5cc-4aea-806e-e06632ddc22d.jsonl` — implement start, 20 edits, then handoff.
2. `[wt:24-mock-fx-provider] e0ca4b0e-31e6-448a-829e-11ad89170a8d.jsonl` — reads `handoff-24-mock-fx-provider.md`, 44 edits. ai-title: "Handoff 24 mock FX provider".

## Ticket 25 — exchange-rate-client
1. `[wt:ticket-25-exchange-rate-client] 9fa9b0ec-190c-4edf-b580-860164bb291c.jsonl` — implement start, 21 edits ("pause after the reviews are in").
2. `[main] 4c8b5a7f-a7d5-481d-b9b0-e977a3a21bad.jsonl` — reads `bankmonitor-ticket-25-handoff.md`, continues (cwd is the worktree), 22 edits. ai-title: "Bankmonitor ticket 25 handoff".

## Ticket 26 — cross-currency-transfers
1. `[wt:26-cross-currency-transfers] a1e3c590-1296-4eff-aa3a-b274126d5d01.jsonl` — implement start, 74 edits, then handoff. First message just "resume" — no ai-title.
2. `[wt:26-cross-currency-transfers] d5332e71-85f2-46aa-92e5-1e601700c72b.jsonl` — continuation, 26 edits, hands off again. ai-title: "Cross-currency handoff ticket 26".
3. `[wt:26-cross-currency-transfers] 88f543b8-037b-4433-8aa5-7b97c78bebd5.jsonl` — continuation, 30 edits ("pause after the reviews are in"), hands off again. ai-title: "Pause after the reviews are in".
4. `[wt:26-cross-currency-transfers] 97f1dac8-792e-43dc-ae34-a1b608eb5f68.jsonl` — reads `handoff-ticket-26-review-fixes.md`, 23 edits. ai-title: "Handoff ticket 26 review fixes".
   *(Landed separately by `[main] c82e9fe1...` — land-only, excluded here.)*

## Ticket 27 — outbox
1. `[wt:ticket-27-outbox] ee3c3df4-6296-4d8f-b3f4-f41ebc015ef2.jsonl` — implement start, 14 edits ("let it finish"), then handoff.
2. `[wt:ticket-27-outbox] 4bad7233-e3d3-4725-93bf-a34dc3f11a36.jsonl` — reads `handoff-ticket-27-outbox.md`, 9 edits. ai-title: "Handoff ticket 27 outbox".

## Ticket 30 — sse-stream
1. `[wt:ticket-30-sse-stream] 13d70b38-4e86-4c31-8299-88ab1b4655c8.jsonl` — implement start, 13 edits, then handoff.
2. `[wt:ticket-30-sse-stream] 65b80d4e-e81b-4200-9302-9f60dae706c6.jsonl` — continuation, 12 edits ("pause after the reviews are in").
3. `[wt:ticket-30-sse-stream] 6ffa9797-887a-4650-a1c1-e1b231365d85.jsonl` — reads `handoff-bankmonitor-ticket-30-review-concerns.md`, 14 edits.
   **All 3 are mis-titled "Agents tickets 21, 26, 39"** — that ai-title belongs to an unrelated main-dir session; ignore it for this worktree.

## Ticket 31 — frontend-toolchain
1. `[wt:31-frontend-toolchain] 5e14f6be-c107-4467-ad5d-452fb0277cb3.jsonl` — the whole ticket in one session, 41 edits, ends with a handoff for review. No ai-title.
2. `[main] 3df257d6-b385-4363-9217-ef31c38919ac.jsonl` — reads `handoff-31-frontend-toolchain-review.md`, discussion/recap ("recap 8 and 9 for me"), 2 code edits — mostly a review conversation, not further ticket work. ai-title: "Handoff 31 frontend toolchain review".

## Ticket 32 — openapi-type-generation
1. `[wt:openapi-types] 131d7da2-5e42-4e08-aeec-c922aa0c6773.jsonl` — implement start, 31 edits, then handoff.
2. `[wt:openapi-types] 0a5bbbd6-ecbd-4e83-a0a4-e2afb8b99029.jsonl` — reads `handoff-ticket-32-fixes.md`, 18 edits. ai-title: "Handoff ticket 32 fixes".
   *(Not part of implementation: `[main] c956e6a0...` — this is where the ticket **files themselves** were authored via `/mattpocock-skills:to-tickets`, not where ticket 32 was implemented.)*

## Ticket 33 — money-format-module
1. `[wt:33-money-format-module] ac696b4d-d276-435b-9ddd-cace7c97f6e8.jsonl` — 13 edits, single session, no ai-title.

## Ticket 34 — problem-document-module
1. `[wt:ticket-34-problem-document-module] 440b4d07-7486-4a34-b665-937c3e268a8a.jsonl` — 13 edits, single session, no ai-title.

## Ticket 35 — idempotency-key-module
1. `[wt:ticket-35-idempotency-key] fc3bacfa-62bc-4238-aef6-198ef26eb5a4.jsonl` — implement start, 28 edits, then handoff.
2. `[wt:ticket-35-idempotency-key] 350faae2-8e13-4562-954d-df027276dcbf.jsonl` — reads `handoff-ticket-35-review-followup.md`, 20 edits. ai-title: "Handoff ticket 35 review followup".

## Ticket 36 — sse-event-module
1. `[wt:36-sse-event-module] 653298d6-caa8-49c5-b20d-f91e0fd0697a.jsonl` — 14 edits, single session, no ai-title.

## Ticket 37 — playwright-harness
1. `[wt:37-playwright-harness] 8903051e-9dbf-4fab-8e07-29f14c46c8cc.jsonl` — **empty stub, 3.2KB, just `/clear`. Noise — skip.**
2. `[wt:37-playwright-harness] c6a129ac-0ef4-4dab-89c6-1b993f0e8206.jsonl` — implement start, 19 edits, then handoff. ai-title: "Handoff 37 playwright harness".
3. `[wt:37-playwright-harness] c8645641-5a77-47ed-aee3-fcfb6022d5a8.jsonl` — reads `handoff-37-playwright-harness.md`, continues, 18 edits. Same ai-title as #2 (collision).
4. `[main] 95d8deb9-bd68-4fa9-a632-21b11873fc76.jsonl` — reads `handoff-37-review-followup.md`, 11 edits, further review-driven fixes. ai-title: "Handoff 37 review followup".

## Ticket 38 — accounts-list-screen
1. `[main] a40d786c-eea0-4b17-ac94-ad47c27efb2e.jsonl` — `/mattpocock-skills:implement 38...`, **interrupted immediately, 0 edits.** Aborted attempt.
2. `[wt:ticket-38-accounts-list] 114ea8f7-6c53-4631-bf67-b15625c3ba58.jsonl` — 0 direct edits itself; spawned 10 further `Agent` calls and read their output (`TaskOutput` x7) rather than editing inline. The actual edits likely happened in agent runs not captured as their own top-level session files — **worth a closer look if you're auditing this ticket specifically.**

## Ticket 39 — create-account-form
1. `[wt:issue-39-create-account-form] b6258aae-7ac3-4ece-b09e-0c138c8f2893.jsonl` — implement session, first message "resume", 10 direct edits + 2 spawned `Agent` calls (heavy `Bash`, 124 calls). No ai-title.

## Ticket 40 — transfer-form
1. `[wt:ticket-40-transfer-form] f765aa64-094b-4b55-ada7-9623f055095b.jsonl` — implement start, 5 edits, then handoff. No ai-title.
2. `[wt:ticket-40-transfer-form] 261f4233-f680-4425-962a-5ba21d4591a3.jsonl` — continuation, 40 edits. No ai-title.

---

## Not found as implementation sessions (no worktree dir, no main-dir hit)
Tickets **23 (expiry-reaper), 28, 29, 41, 42, 43, 44** — no session (main or worktree) shows
up as having done implementation work on these. Either not started yet, or done somewhere
I haven't located (worth double-checking the issue tracker / repo state before assuming).

## Explicitly excluded (checked, and genuinely not implementation)
- **Pure `/land-worktree` sessions** (~20 of them, one per landed ticket) — these only rebase/merge/verify/remove a worktree; no ticket logic written there. Full list available on request.
- **Ticket authoring**: `[main] c956e6a0...` (`/mattpocock-skills:to-tickets`, `/mattpocock-skills:ask-matt`) — wrote the ticket files themselves, not an implementation.
- **General planning/grilling/domain-modeling handoffs** (not tied to one ticket): `[main] b00fd871...`, `[main] 68c01720...`, `[main] 6f507f8d...`, `[main] 4eab3580...` (git init + handoff for grilling).
- **Status-check / orchestration, no edits**: `[main] 0e52c9ea...` and `[main] f1f30bc5...` — both auto-titled "Agents tickets 21, 26, 39", but neither edits code; they read the issue tracker, check in-flight ticket state, and manage worktrees (`ExitWorktree`, `AskUserQuestion`). Not implementation of any of 21/26/39.
- **Unrelated troubleshooting**: `[main] e650588e...` ("Port conflicts with multiple agents") — ops question, no ticket edits.
- **Doc reorg, not ticket work**: `[main] d3b1a659...` ("Design decisions folder refactoring") — touches ticket 01/02 docs but 0 code edits.
- **Design-decision meta discussion**: `[main] 413bfda2...` ("Idempotency generation plans") — discusses idempotency key generation design, doesn't touch a specific ticket's code.
- `[main] cb4b385a...` — a 222-byte stub, title/agent-name registration only, no conversation.
