## Agent skills

### Issue tracker

Issues and specs live as markdown files under `.scratch/<feature>/` (no GitHub/GitLab remote configured). See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Code style

**Self-documenting code over comments.** Reach for a clearer name, a smaller function or
an extracted variable before reaching for a comment that explains the unclear version.
Docstrings (Javadoc, TSDoc) on types and non-trivial methods are welcome and are the
right place for intent.

Write an inline comment only when the code genuinely cannot carry the information itself:

- a non-obvious **why** — a workaround, an ordering constraint, a deliberate omission
- a fact about the outside world — a library behaving surprisingly, a spec quirk, a
  version trap
- a warning where the obvious edit is the wrong one

Do not comment *what* the code does, restate a name, or narrate a decision. If an
explanation needs a paragraph, it belongs in `docs/design-decisions.md` with at most a
one-line pointer from the code. A comment that repeats the design record is a second copy
to keep in sync, and it will drift.

Configuration files are the exception: a setting cannot be renamed to explain itself, so
`application.properties` and similar may carry a short line of intent per non-obvious key.

## Design decisions

Settled architectural decisions live in `docs/design-decisions.md` — what was chosen, why, and what was rejected. Read it before proposing an approach; several options in there were considered and rejected for reasons that are not obvious from the code.

**It is scaffolding, not a deliverable.** `docs/design-decisions.md`, `docs/deferred.md`
and `.scratch/` exist to carry reasoning between tickets while the thing is being built,
and are disposable once it is. What a maintainer inherits is `README.md`, the READMEs that
sit next to the code they govern, `CONTEXT.md`, and Javadoc.

So when the same fact belongs in both, **the durable document gets the full statement and
the design record gets the reasoning and the rejected alternatives.** Deduplicating the
other way — trimming a surviving document down to a pointer at `design-decisions.md` —
reads as removing a duplicate and is actually removing the copy that was going to survive.
The one-line-pointer rule under *Code style* is about comments in code, which sit beside
the thing they describe; it does not apply to a document that has to stand on its own.

## Deferred decisions

Anything consciously left out goes in `docs/deferred.md` with its reasoning. That file feeds the README's TODO section, which the task grades as heavily as the code.
