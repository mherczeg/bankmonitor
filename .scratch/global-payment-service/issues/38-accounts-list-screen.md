# 38: The Accounts screen — seeing what there is

**What to build:** The first required screen. Every Account with its balance **and** its
Available Balance, so an operator can pick a source that can actually afford the Transfer
they have in mind, and can see how much of an Account is already committed to in-flight
Transfers.

Amounts render in the familiar decimal form for their Currency via the formatting module —
an operator never has to think in Minor Units.

The query client owns this data. **Refetch on window focus** is on, so a list nobody was
looking at is not silently out of date — this is deliberately the compensation for the
Accounts screen being outside the live-update scope.

Carries its own browser spec.

**Blocked by:** 32, 33, 37

**Status:** done

- [x] Accounts render with balance and Available Balance, correctly formatted per Currency
- [x] Loading, empty and error states are all rendered, the error via the problem module
- [x] Returning focus to the window refetches
- [x] A browser spec covers the rendered list, the empty state and the focus refetch
