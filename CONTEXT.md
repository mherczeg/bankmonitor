# Global Payment Service

A payment gateway that holds account balances and moves money between accounts, with currency conversion, idempotent retries, and downstream notification of other domain services.

## Language

### Accounts and money

**Account**:
An entity holding a balance in a single Currency, identified by an ID. It is the atomic entity of this context — nothing owns it.
_Avoid_: Wallet, User account

**User**:
A person who owns an Account. **Deliberately unmodelled** — named here so its absence reads as a decision rather than an oversight. Nothing in this context references a caller or an owner.
_Avoid_: Customer, Owner, Account holder

**Currency**:
One of the three denominations an Account's balance is held in: EUR, USD, HUF. Fixed at Account creation and immutable thereafter.

**Money**:
An amount paired with the Currency it is denominated in. There is no bare amount in this context; a number without a Currency is not Money.

**Minor Units**:
The indivisible sub-unit a Currency is counted in — cents for EUR and USD, fillér for HUF. All Money is counted in whole Minor Units, so a fraction of one is not a representable quantity.
_Avoid_: Cents, Smallest unit

**Reserved Amount**:
The portion of an Account's balance committed to Transfers that have not yet reached a terminal state. It is still held by the Account but cannot be spent again.
_Avoid_: Hold, Frozen funds, Pending balance

**Available Balance**:
What an Account can actually spend: its balance minus its Reserved Amount. This is the figure an overdraft check tests against.
_Avoid_: Spendable balance, Free balance

### Transfers and their lifecycle

**Transfer**:
A request to move an amount of Money from one Account to another, possibly across Currencies. It has a lifecycle: `PENDING` while its Checks are outstanding, then terminally `SETTLED`, `REJECTED` or `EXPIRED`.
_Avoid_: Payment, Utalás (Hungarian source term)

**Transaction**:
_Not an entity._ The label of the screen that lists Transfers in every state. A recorded Transfer is still a Transfer — this context has one word for it.
_Avoid_: using this as a name for a persisted or executed Transfer

**Settlement**:
The point at which a `PENDING` Transfer's money actually moves: the source Account's balance and Reserved Amount both fall, the destination Account's balance rises. Reserving funds at request time is not Settlement.
_Avoid_: Execution, Clearing, Completion

**Check**:
A condition a Transfer must satisfy before it can settle — fraud screening, manual approval — answered by a party outside this context. A human approver and an automated service are the same kind of thing here.
_Avoid_: Approval, Gate, Rule

**Check Ledger**:
The per-Transfer record of which Checks it requires and how each has been answered. It is what makes "why is this Transfer still pending" a question with an answer.
_Avoid_: Checklist, Approval log

**Verdict**:
A Check's answer for one Transfer: approved or rejected. A Transfer settles when every Check has approved and rejects the moment any one rejects.
_Avoid_: Result, Decision, Outcome

**Expiry**:
The terminal state of a `PENDING` Transfer whose Checks were not all answered before its deadline. It releases the Reserved Amount without moving money.
_Avoid_: Timeout, Cancellation

### Integration

**Exchange Rate**:
The rate used to convert Money from the source Account's Currency to the destination Account's, obtained from an external provider when the Transfer is requested and fixed for that Transfer's life. The provider is unreliable — it returns errors and responds slowly — so requesting a Transfer must tolerate that.
_Avoid_: Rate, FX rate

**Idempotency Key**:
A client-supplied value (sent as `X-Idempotency-Key`) that identifies a single Transfer *intent*, so that retrying the same request never moves money twice. It identifies what the user meant to do, not an individual HTTP attempt.
_Avoid_: Request ID, dedup key

**Claim**:
The hold one request has on an Idempotency Key while the work behind that key is
its to do. A Claim is in progress, or succeeded and holding the response every
repeat of the key is owed, or failed — and a failed one can be *reclaimed*, by
exactly one of the retries that want it.
_Avoid_: Lock, lease, reservation

**Outbox Event**:
A record that something happened to a Transfer, written as part of the same change that caused it and delivered to other services afterwards. Its existence is guaranteed by the change it describes.
_Avoid_: Message, Notification, Domain event
