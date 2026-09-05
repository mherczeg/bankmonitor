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

## Deferred decisions

Anything consciously left out goes in `docs/deferred.md` with its reasoning. That file feeds the README's TODO section, which the task grades as heavily as the code.
