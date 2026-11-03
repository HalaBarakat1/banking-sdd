# Feature Specification: Effectively-Once Distributed Debit

**Feature Branch**: `001-effectively-once-debit`

**Created**: 2026-11-03

**Status**: Draft

**Input**: User description: "Track 1 — Fault-Tolerant Distributed Banking Transaction. An Order Service
(client) asks a Banking Service (server) over the network to debit an amount from a user's account. The
connection may break or the server may crash before replying, or right after the debit and before the
confirmation reaches the client. Build a client/server system that guarantees each debit is executed
effectively once: never lost, never applied twice. Use at-least-once delivery with client retries
(timeouts/backoff), artificial idempotency through a client-generated monotonically increasing sequence
key plus an `is_retry` flag, an atomic check-and-update of the account's `last_sequence_key`, and a
configurable fault injector covering four failure scenarios."

## Clarifications

### Session 2026-11-03

- Q: What happens to a key smaller than the account's last key (a stale key)? → A: The server looks the
  key up in the transaction records. If it was decided before, the original outcome is replayed;
  otherwise the request is rejected as a stale key and is guaranteed never to execute.
- Q: Must keys be consecutive (last + 1)? → A: No. Any key greater than the last key is new; gaps are
  allowed so that a client crash after reserving a key never blocks the account.
- Q: Does a debit rejected for insufficient funds consume its key? → A: Yes. The rejection is recorded
  as the final decision for that key, and every retry receives the same rejection.
- Q: Which failures does the client retry? → A: No response (timeout, connection failure) and any
  "server temporarily unable" outcome. Every business or validation outcome is final.
- Q: May a client have several unresolved transactions on one account? → A: No. At most one unresolved
  transaction per account; the next one starts only after the previous one reached a final outcome.
- Q: Are deposits in scope? → A: No. Only debits; accounts are created with a starting balance.
- Q: How does the client survive its own crash? → A: It records the pending transaction durably before
  sending and resends it (as a retry) after restart.
- Q: How does a client cancel a transaction whose request may still arrive late? → A: With a cancel
  request that permanently burns the key; a late copy of the debit can then never execute.
- Q (found while writing this spec): Is it enough to remember only the last outcome per account? → A:
  No. A stale copy of an older rejected or cancelled key would then get a different answer than the
  original. Every decided key gets its own transaction record, so any copy of any key always gets the
  original answer.

### Amendment 2026-11-03 (found while planning)

- FR-011 conflicted with User Story 5: a late debit copy of a cancelled key is a replay of `CANCELLED`,
  not a `KEY_CONFLICT`. FR-011 now limits conflicts to a debit with a different amount.
- FR-026 conflicted with FR-027: resending every unresolved entry on start would re-send a debit the
  user is about to cancel. FR-026 now excludes that entry.
- FR-003 now names the two cancel-only outcomes so every outcome is listed in one place.

### Amendment 2026-11-03 (found while reviewing the previous amendment)

- Excluding the cancelled entry from recovery kept the user's cancel only in memory for one run: a
  client crash before the cancel was sent left the journal saying "debit", and the next start resent
  that debit. FR-026 is restored to "resend every unresolved transaction", and FR-027 now requires the
  switch from debit to cancel to be saved in the journal before anything is sent for that key, so
  recovery always resends the user's latest intention.

### Amendment 2026-11-03 (found by /speckit-analyze)

- FR-022 and NFR-003 add the connect limit and the total time limit the constitution requires
  (Principle IV); retries stop at whichever of attempts or total time comes first.
- FR-021 defines `is_retry` from a durable attempt counter, so a first cancel attempt is not marked as a
  retry and recovery only marks requests that may really have been sent before.
- FR-008 defines "the same answer" (equal outcome, amount and balance after; one `duplicate = false`),
  and EC-003 no longer calls a debit on a cancelled key a conflict.
- FR-012 forbids rounding or defaulting values: `"amount": 2.5` must be invalid, never a debit of 2.
- FR-026 applies to commands that send; FR-029 adds an operation filter, one scenario per request,
  and fault configuration that survives an FS-2 restart.
- Assumptions record that accounts exist before clients use them, because `UNKNOWN_ACCOUNT` is not a
  decision.
- FR-022 states that the attempt and time limits apply per send run (each command or recovery pass
  starts them fresh). Otherwise an `UNKNOWN` entry, whose durable counter already reached the
  maximum, could never be resent; the durable counter of FR-021 decides only `is_retry`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Debit executes exactly once despite retries (Priority: P1)

The Order Service asks the Banking Service to debit an amount from an account. If it gets no answer, it
sends the same request again (same key, marked as a retry) until it gets a definite answer. Whatever
happens on the network, the account is debited once, and the Order Service ends up knowing it.

**Why this priority**: This is the core guarantee of the assignment. Without it nothing else has value.

**Independent Test**: Seed an account, send one debit with no faults, then send the same request again
as a retry; check that the balance dropped once and both answers are the same answer (FR-008).

**Acceptance Scenarios**:

1. **Given** account A with balance 1000 and last key 0, **When** the client sends a debit of 100 with a
   new key 1, **Then** the debit is executed, the balance is 900, the last key is 1, and the client
   receives "executed, balance after 900".
2. **Given** key 1 was already executed, **When** a copy of the same debit with key 1 arrives (retry or
   not), **Then** no money moves and the client receives the original answer marked as a duplicate.
3. **Given** account A with balance 50, **When** the client sends a debit of 100 with a new key 2,
   **Then** no money moves, key 2 is recorded as "insufficient funds", and every later copy of key 2
   receives the same answer.
4. **Given** account A with last key 1, **When** a new debit arrives with key 5, **Then** it is treated
   as new (gaps are allowed) and the last key becomes 5.

---

### User Story 2 - Faults can be injected and the system survives them (Priority: P1)

A tester (or the person demonstrating the project) switches on one of four failure scenarios and
watches the system recover: the final balance is still correct and no debit is lost or doubled.

**Why this priority**: The assignment requires proof under failure, not only on the happy path. The
injector is what makes the four scenarios repeatable.

**Independent Test**: For each scenario, enable it for the next request only, run one debit through the
client, and check the final balance, the number of executions of that key (exactly one) and the
client's final answer (executed).

**Acceptance Scenarios**:

1. **FS-1 Request lost** — **Given** the injector drops the next request before it is processed,
   **When** the client sends a debit, **Then** the client gets no answer, retries with the same key and
   `is_retry = true`, and the debit is executed once.
2. **FS-2 Server crash before execution** — **Given** the server stops after receiving the next request
   but before executing it, **When** the server comes back and the client retries, **Then** the debit is
   executed once and nothing from the interrupted attempt remains.
3. **FS-3 Executed and saved, response lost** — **Given** the server executes and durably saves the next
   debit and then the response is not delivered, **When** the client retries, **Then** the server does
   not debit again and replies with the original answer marked as a duplicate.
4. **FS-4 Response slower than the client timeout** — **Given** the server delays the next request
   longer than the client's timeout (variant a: delay before executing; variant b: delay after
   executing), **When** the client times out and retries while the first attempt is still in progress,
   **Then** exactly one of the two attempts executes and both get the same answer.
5. **Given** no scenario is enabled, **When** the system runs, **Then** no fault is ever injected.

---

### User Story 3 - Concurrent and late copies are harmless (Priority: P2)

Several copies of the same request may arrive at the same time (client retry racing a slow original),
or very late, after newer transactions were already decided. None of them may cause a second debit or
a different answer.

**Why this priority**: These are the race conditions the assignment names explicitly; they appear
naturally under FS-4.

**Independent Test**: Send many identical copies of one debit at the same moment and count executions;
then send an old key after a newer key was executed and check it is not executed.

**Acceptance Scenarios**:

1. **Given** account A, **When** 50 identical copies of a debit with a new key arrive at the same time,
   **Then** exactly one executes and all 50 receive the same answer.
2. **Given** key 5 was executed and then key 6 was executed, **When** a late copy of key 5 arrives,
   **Then** no money moves and the original answer of key 5 is replayed.
3. **Given** the last key is 6 and key 4 was never decided, **When** a request with key 4 arrives,
   **Then** it is rejected as a stale key, no money moves, and key 4 can never execute later.
4. **Given** several different accounts, **When** debits for all of them arrive concurrently, **Then**
   each account's balance is correct and work on one account does not corrupt another.

---

### User Story 4 - The client recovers from its own crash (Priority: P2)

The Order Service may itself crash at any moment: before sending, while waiting, or after the debit
executed but before it read the answer. After restart it finishes the pending transaction without
debiting twice and without reusing a key for a different payment.

**Why this priority**: Effectively-once must hold end to end, not only while the client stays up.

**Independent Test**: Make the client record a pending debit and stop before (or after) sending; start
a new client on the same records and check the transaction resolves with exactly one execution.

**Acceptance Scenarios**:

1. **Given** the client recorded a pending debit with key 7 and crashed before sending it, **When** it
   restarts, **Then** it resends key 7 (with `is_retry` set as FR-021 defines), the debit executes once,
   and the record is resolved.
2. **Given** the debit with key 7 was executed but the client crashed before reading the answer,
   **When** it restarts and resends key 7, **Then** it receives the original answer as a duplicate and no
   second debit happens.
3. **Given** the client reserved key 7, **When** it restarts, **Then** its next new transaction uses a key
   greater than 7 (a key is never handed out twice).

---

### User Story 5 - Cancel a transaction that might still arrive (Priority: P3)

The Order Service decides a payment must not happen (for example the order was cancelled) while its
debit request may still be stuck in the network. It sends a cancel for that key. After the cancel is
decided, a late copy of the debit can never execute.

**Why this priority**: It closes the gap where "giving up" on a stuck request could still lead to a
debit later; it is less central than the four required scenarios.

**Independent Test**: Hold a debit request back, cancel its key, then release the held request and check
the balance is unchanged.

**Acceptance Scenarios**:

1. **Given** key 8 was never decided, **When** the client cancels key 8, **Then** key 8 is recorded as
   cancelled, the last key becomes 8, and no money moves.
2. **Given** key 8 is cancelled, **When** a late copy of the debit with key 8 arrives, **Then** no money
   moves and it receives "cancelled" marked as a duplicate.
3. **Given** key 8 was already executed, **When** the client cancels key 8, **Then** the cancel reports
   "already executed" with the original debit details and no money moves.
4. **Given** key 8 is cancelled, **When** the cancel is sent again, **Then** it receives the same answer.

---

### Edge Cases

- **EC-001** A retry (`is_retry = true`) arrives with a key the server has never seen, because the first
  attempt was lost (FS-1). It is treated as new and executed; the flag never decides the outcome.
- **EC-002** A request arrives with `is_retry = false` but a key that was already decided (for example a
  client bug or a network-level duplicate). It is treated as a duplicate; no money moves.
- **EC-003** A debit copy of a key decided by a debit arrives with a different amount than the
  original. It is rejected as a key conflict; no money moves and the original decision stands.
  (A debit copy of a cancelled key replays `CANCELLED`, and a cancel of a decided debit answers per
  FR-017; neither is a conflict.)
- **EC-004** The amount is zero, negative, or not a whole number of minor units. The request is rejected
  as invalid and does not consume the key.
- **EC-005** The account does not exist. The request is rejected as unknown account and does not consume
  a key.
- **EC-006** The debit equals the whole balance. It executes and the balance becomes exactly 0; the
  balance never becomes negative.
- **EC-007** Two copies of the same new key arrive at exactly the same time. Only one executes (see
  User Story 3).
- **EC-008** The client exhausts its retries. The transaction is reported as UNKNOWN (never as failed),
  stays recorded with its key, and blocks new transactions on that account until it is resolved by a
  later resend or a cancel.
- **EC-009** The client tries to start a second transaction on an account that already has an
  unresolved one. It is refused locally before anything is sent.
- **EC-010** The client asks to cancel a transaction whose debit was rejected for insufficient funds.
  The cancel reports "already rejected"; no money moves.
- **EC-011** The server crashes in the middle of executing a debit (after starting, before finishing).
  After restart, the debit is either fully applied with its record, or not applied at all.
- **EC-012** The injector is configured with an unknown scenario or invalid settings. The configuration
  is refused and the previous configuration remains active.

## Requirements *(mandatory)*

### Functional Requirements

**Debit contract**

- **FR-001**: A debit request MUST carry: account identifier, sequence key (positive whole number),
  amount (positive whole number of minor units), and the `is_retry` flag.
- **FR-002**: Every answer MUST carry: account identifier, sequence key, outcome, and a `duplicate`
  marker (true when the answer replays an earlier decision). An executed debit MUST also carry the
  amount and the balance right after that debit.
- **FR-003**: The outcomes of a debit MUST be exactly: `EXECUTED`, `INSUFFICIENT_FUNDS`, `CANCELLED`
  (key was burnt by a cancel), `STALE_KEY`, `KEY_CONFLICT`, `INVALID_REQUEST`, `UNKNOWN_ACCOUNT`, and
  `UNAVAILABLE` (server temporarily unable to process). A cancel may additionally answer
  `ALREADY_EXECUTED` or `ALREADY_REJECTED` (FR-017).
- **FR-004**: Outcomes MUST be classified for the client: `UNAVAILABLE` and "no answer" are
  **retryable**; all other outcomes are **final**.

**Server decision rules**

- **FR-005**: For each account the server MUST keep the balance and the last sequence key, and for each
  decided key a transaction record (key, operation, amount, outcome, balance after).
- **FR-006**: When a request's key is greater than the account's last key, the server MUST decide it as
  new: execute the debit if the balance covers the amount, otherwise decide `INSUFFICIENT_FUNDS`; in both
  cases record the decision and set the last key to this key.
- **FR-007**: The check of the key, the balance change, the last-key update and the transaction record
  MUST happen as one indivisible step: either all of them take effect or none does.
- **FR-008**: When a request's key already has a transaction record, the server MUST NOT move money and
  MUST reply with the recorded outcome marked `duplicate = true`, except as FR-011 (conflict) and
  FR-017 (cancel of a decided key) define. "The same answer" means equal outcome, amount and balance
  after; among all copies of a key exactly one answer, the one that made the decision, has
  `duplicate = false`.
- **FR-009**: When a request's key is smaller than the last key and has no transaction record, the
  server MUST reply `STALE_KEY` and MUST NOT move money. Such a key can never execute later.
- **FR-010**: The server MUST decide using stored state only; the `is_retry` flag MUST NOT change the
  decision and is used for logging and diagnostics.
- **FR-011**: When a debit copy of a key decided by a debit carries a different amount than the record,
  the server MUST reply `KEY_CONFLICT` and MUST NOT change anything. A debit copy of a key burnt by a
  cancel is not a conflict: it replays `CANCELLED` (FR-008, User Story 5).
- **FR-012**: Invalid requests (EC-004) and unknown accounts (EC-005) MUST be rejected without consuming
  a key and without changing anything. These rejections are not decisions: no transaction record is
  written. Fractional, non-numeric or missing values are invalid; they are never rounded or defaulted.
- **FR-013**: The server MUST send a success answer only after the decision is durably saved.
- **FR-014**: A balance MUST never become negative.

**Cancel contract**

- **FR-015**: A cancel request MUST carry: account identifier, the sequence key to cancel, and the
  `is_retry` flag. It follows the same retry rules as a debit.
- **FR-016**: When the key is new (greater than the last key), the server MUST record it as `CANCELLED`
  without moving money and set the last key to it, in the same indivisible step as FR-007.
- **FR-017**: When the key is already decided, the cancel MUST NOT move money and MUST reply:
  `CANCELLED` (already cancelled, `duplicate = true`), `ALREADY_EXECUTED` (with the original debit
  details), or `ALREADY_REJECTED` (debit was refused for insufficient funds).
- **FR-018**: When the key is stale and undecided, the cancel MUST reply `STALE_KEY`, which the client
  treats as "will never execute".

**Client behaviour**

- **FR-019**: The client MUST generate sequence keys that strictly increase and are never handed out
  twice, even across client restarts. The next key MUST be durably reserved before the request is sent.
- **FR-020**: Before sending, the client MUST durably record the pending transaction (account, key,
  operation, amount, state `PENDING`).
- **FR-021**: Every attempt of a transaction MUST reuse the same key and amount. The client MUST durably
  count each attempt of the current operation before sending it; `is_retry` is `true` exactly when an
  earlier attempt of the same operation for that key was counted (so the first debit attempt, and the
  first cancel attempt after a switch, are sent with `is_retry = false`, also when sent by recovery).
- **FR-022**: Each attempt MUST have a connect time limit and a response time limit. On a retryable
  outcome the client MUST wait before the next attempt, with the wait growing exponentially and
  including a random component, up to a maximum wait. Retrying MUST stop at a maximum number of
  attempts or a maximum total time, whichever comes first. Both limits apply to one send run (a
  command, or one recovery pass over an entry) and start fresh on each run, so an `UNKNOWN`
  transaction can always be resent later; the durable attempt count of FR-021 is used only for
  `is_retry`. All these values MUST be configurable.
- **FR-023**: On a final outcome the client MUST stop retrying, record the outcome, and report it. An
  `EXECUTED` answer (duplicate or not) means success.
- **FR-024**: When retries are exhausted, the client MUST record and report the transaction as `UNKNOWN`
  and keep its key for a later resend or cancel.
- **FR-025**: The client MUST refuse to start a new transaction on an account that has an unresolved
  (`PENDING` or `UNKNOWN`) transaction (EC-009).
- **FR-026**: Whenever the client starts in order to send requests (any command that sends), it MUST
  first resend every unresolved transaction, using its stored key, operation and amount, with
  `is_retry` as FR-021 defines. Read-only commands do not send.
- **FR-027**: When the user asks to cancel an unresolved debit, the client MUST first durably change the
  recorded operation for that key from debit to cancel (state `PENDING`), before any request for that
  key is sent, including start-up recovery (FR-026). From then on the client MUST never send that debit
  again; recovery resends the cancel instead.

**Fault injection**

- **FR-028**: The system MUST provide a fault injector with these scenarios: `FS-1` drop the request
  before processing; `FS-2` stop the server after receiving and before executing; `FS-3` execute and
  save, then do not deliver the response; `FS-4a` delay before executing; `FS-4b` delay after executing.
  The delay length MUST be configurable.
- **FR-029**: Each scenario MUST be enabled either for the next N requests or with a probability, and
  optionally only for a given account and/or operation (debit or cancel). A request triggers at most
  one scenario; the probabilities of rules that can match the same request MUST add up to at most 1.
  Configuration MUST be changeable while the server runs, without a restart, MUST survive an FS-2
  crash-restart, and invalid configuration MUST be refused (EC-012).
- **FR-030**: The fault injector MUST be off by default and MUST NOT change any decision rule; it only
  drops, stops or delays.

**Inspection**

- **FR-031**: It MUST be possible to read an account's balance and last key, and the list of its
  transaction records, so tests and demonstrations can check the money invariant.

### Non-Functional Requirements

- **NFR-001 Durability**: Balances, last keys and transaction records MUST survive a server crash and
  restart; the client's key counter and pending transactions MUST survive a client crash and restart.
- **NFR-002 Concurrency**: The server MUST process requests for different accounts in parallel and stay
  correct with at least 50 simultaneous requests, including simultaneous copies of the same key.
- **NFR-003 Bounded waiting**: With default settings the client MUST reach a final outcome or `UNKNOWN`
  within 30 seconds. Defaults: connect limit 1 s, response limit 2 s per attempt, 6 attempts, total
  limit 30 s, waits starting at 100 ms, doubling, capped at 2 s, with random jitter.
- **NFR-004 Observability**: The server MUST write one log line per request with: account, key,
  `is_retry`, decision (`EXECUTED` / `DUPLICATE` / `REJECTED`, or `DROPPED` when an injected fault
  prevented any decision), outcome, injected fault (if any) and processing time. The client MUST log every attempt and its result.
- **NFR-005 Isolation of faults**: Fault injection code MUST be separate from the decision rules and
  must be removable without changing them.

### Key Entities

- **Account**: identifier, balance (whole minor units, never negative), last sequence key.
- **Transaction Record**: one per decided key of an account: account, key, operation (debit / cancel),
  amount, outcome (`EXECUTED` / `INSUFFICIENT_FUNDS` / `CANCELLED`), balance after, decision time.
  A key has at most one record, ever.
- **Debit Request / Cancel Request**: the messages defined in FR-001 and FR-015.
- **Answer**: the message defined in FR-002, with the outcomes of FR-003 and FR-017.
- **Client Key Counter**: the last key handed out by the client; only increases.
- **Client Journal Entry**: account, key, operation, amount, state (`PENDING`, `UNKNOWN`, `RESOLVED`),
  final outcome, attempt count.
- **Fault Configuration**: scenario, trigger (next N requests or probability), optional account, delay.

### Money Invariant

For every account, at any moment after requests complete:
`balance = initial balance − sum of amounts of its EXECUTED transaction records`, and every key has at
most one transaction record.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a run of 1,000 debits across several accounts with faults injected at random (each
  scenario active for about 10% of requests), the money invariant holds for 100% of accounts, with zero
  double debits and zero lost debits among transactions the client reported as executed.
- **SC-002**: Each of FS-1, FS-2, FS-3, FS-4a and FS-4b has a repeatable automated test that passes on
  every run.
- **SC-003**: 50 simultaneous copies of one debit produce exactly 1 execution and 50 copies of the same
  answer (FR-008), exactly one of them with `duplicate = false`.
- **SC-004**: After the server is stopped and restarted, 100% of previously answered decisions are still
  present and are replayed unchanged.
- **SC-005**: After the client is stopped mid-transaction and restarted, 100% of pending transactions
  resolve with exactly one execution or a recorded cancellation.
- **SC-006**: With default settings, every client transaction reaches a final outcome or `UNKNOWN` within
  30 seconds (the NFR-003 bound, measured end to end).
- **SC-007**: A cancelled key followed by a late copy of its debit leaves the balance unchanged in 100%
  of trials.

## Assumptions

- One Order Service instance owns each account (keys are not shared between clients). A single client
  counter is used for all its accounts; keys still increase per account, and gaps are allowed.
- One Banking Service instance runs at a time; it may crash and restart on the same stored data.
- Money is expressed as whole minor units (for example cents) to avoid rounding.
- Accounts are created with a starting balance for testing and demonstration; account opening, deposits,
  refunds and transfers are out of scope.
- Accounts are seeded before any client sends requests for them and are never created while such
  requests may still be in flight. Otherwise a delayed copy of a request first answered
  `UNKNOWN_ACCOUNT` could execute later, giving one key two different answers.
- Client and server trust each other; authentication and encryption are out of scope.
- Clocks of client and server are not trusted or compared; no decision depends on time of day.
- A cancel that arrives after the debit executed cannot undo it; a refund would need a credit
  operation, which is out of scope and is reported to the client as `ALREADY_EXECUTED`.
