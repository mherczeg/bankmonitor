# 33: Money formatting and parsing, as a plain module

**What to build:** The one place in the frontend that knows a Currency's decimals. It
converts a Minor Unit count into the familiar decimal form for display (2 places for EUR
and USD, 0 for HUF) and parses operator input back into Minor Units.

**Per-Currency decimals live only at the edges**, and this module is that edge. Nothing
else in the frontend divides or multiplies by a hundred.

Pure TypeScript, no React. This is the rule the whole frontend test strategy rests on:
extract the logic, unit test it, and write as few component tests as possible — integration
confidence comes from a real browser, not from a simulated DOM.

**Blocked by:** 31, and in practice 32 — see the comments

**Status:** done

- [x] Formatting renders a Minor Unit count with the right number of decimals per Currency
- [x] Parsing rejects more decimal places than the Currency allows
- [x] Parsing rejects non-numeric input, negatives and zero
- [x] Round-tripping format → parse is lossless for every supported Currency
- [x] The module imports nothing from React

## Comments

**The ticket says blocked by 31; it is really blocked by 32.** The decimals table is a
`Record<Currency, number>` and `Currency` comes from the generated types, which is what
makes a fourth Currency on the backend a compile error here rather than a silently
unformatted amount. Written against 31 alone it would have needed a hand-written union,
which is the copy ticket 32 exists to delete. Nothing was blocked in practice — 32 was
already done — but the dependency line was wrong.

**The one decision worth deliberating was what the formatted string is for.**
`Intl.NumberFormat(locale, { style: 'currency' })` is the reflex answer and it cannot
satisfy the fourth checkbox: it renders `10 050 Ft` or `HUF 10,050` depending on a
locale the app never chose, and `parseAmount` would have to strip a group separator
that is a comma in one locale and a full stop in another. The output is therefore a
bare decimal — no symbol, no grouping — which is also what the create-Account and
transfer forms need, because on both screens the formatted amount goes into a field an
operator then edits. Design decision 33 has the argument and the accepted consequence
(a large HUF balance reads `10050`, not `10 050`).

**Neither direction does arithmetic.** Both work on the digits of the string, because
`Number('100.50') * 100` is `10049.999999999998` and the `Math.round` that rescues it is
a rounding rule living in the one module whose contract is that it does not round.

**A fifth rejection reason the checkboxes did not ask for: `too-large`.** §16 counts
Minor Units in a `long`; a JavaScript number is exact only to 2^53 − 1. There is a range
the backend can hold and the browser cannot count, and an amount past it would reach the
server as a different figure than the one submitted. Guarded on input, deliberately not
on output — a balance past that bound would already have been mangled by `JSON.parse`
before `formatAmount` saw it, so a guard there would be theatre at the wrong end of the
pipe.

**The fifth checkbox is a test, not a convention.** `money.test.ts` imports its own
subject's source with Vite's `?raw` and asserts the import list is exactly
`['./api/types']`. Unusual, and it earns its place: design decision 24's rule erodes one
module at a time, and the erosion looks like a hook that moves a case such as
`too-many-decimals` out of a 2 ms unit test into a component test that has to simulate a
DOM to reach it. `?raw` rather than `node:fs` because `tsconfig.app.json` deliberately
keeps Node's types out of application code.

**The two-axis review changed four things and answered three more.** Changed: the
no-React assertion was blind to `import 'react'`, `import("react")` and double quotes,
and now pins the import list against all of them plus asserts the word is absent from
the file; four copies of the same rejected-alternative prose were trimmed out of the
TSDoc, which AGENTS.md allocates to the design record rather than the code; the README
claimed four exports where there are six; `magnitude` became `minorUnits`, since the
sign is checked separately and the name promised an abstraction the code did not have.

Answered rather than changed, all three recorded in decision 33: format's domain is
wider than parse's, so the round-trip checkbox holds over positive counts only and the
two checkboxes that appear to conflict are named as such; there is no frontend `Money`
type despite CONTEXT.md's rule, because the API sends the count and the Currency
separately and ticket 43 is the screen that would earn one; and the sign branch in
`formatAmount` stays, because it is the other half of the `Math.abs` the padding needs
rather than a feature — without it a debit renders as a credit.

**Noticed while updating the README, and left alone:** "What is built so far" listed
tickets 01–12, 31 and 32 while 13 and 16 are also done. The enumeration is corrected
here; the prose describing what those two built is still missing and belongs to them or
to ticket 44.
