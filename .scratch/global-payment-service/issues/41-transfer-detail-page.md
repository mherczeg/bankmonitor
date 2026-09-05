# 41: A Transfer's own page

**What to build:** The page an operator lands on after submitting, addressable by URL and
survivable across a refresh. It shows the Transfer's status, both amounts with their
Currencies, the locked Exchange Rate and when it was fetched for a cross-Currency Transfer,
and **which Checks it is still waiting on**.

The Check Ledger rendering is the point: "why is this stuck" gets an answer on the screen
rather than in a log. An outstanding Check must be visually distinct from an answered one,
and a rejected Transfer should show *which* Check rejected it.

Static here — live updating is ticket 42. A refresh already shows the current truth,
because the state is in the URL and the data comes from the API.

Carries its own browser spec.

**Blocked by:** 22, 40

**Status:** ready-for-agent

- [ ] The page renders status, both amounts, and the locked rate with its timestamp where
      one applies
- [ ] Outstanding Checks are distinguishable from answered ones
- [ ] A rejected Transfer shows which Check rejected it
- [ ] The page survives a refresh with no loss of context
- [ ] An unknown Transfer ID renders a readable not-found state
- [ ] A browser spec covers pending, settled, rejected and expired renderings
