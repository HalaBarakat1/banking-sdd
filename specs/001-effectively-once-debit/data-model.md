# Data Model: Effectively-Once Distributed Debit

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-11-03

Two independent stores: the **server store** (`bank.db`, owned by the Banking Service) and the
**client store** (`client.db`, owned by the Order Service). They never read each other's file; they
only exchange the messages in [contracts/http-api.md](contracts/http-api.md).

---

## Server store (`bank.db`)

### `accounts` — Account (spec: Key Entities)

| Column | Type | Rules |
|---|---|---|
| `account_id` | TEXT, primary key | 1–64 chars, `[A-Za-z0-9_-]` |
| `initial_balance` | INTEGER NOT NULL | ≥ 0; set when seeded, never changed (used by the invariant, FR-031) |
| `balance` | INTEGER NOT NULL | `CHECK (balance >= 0)` (FR-014); minor units |
| `last_sequence_key` | INTEGER NOT NULL DEFAULT 0 | `CHECK (last_sequence_key >= 0)`; only increases (FR-006, FR-016) |

### `transaction_records` — Transaction Record (one per decided key)

| Column | Type | Rules |
|---|---|---|
| `account_id` | TEXT NOT NULL | foreign key → `accounts.account_id` |
| `sequence_key` | INTEGER NOT NULL | > 0 |
| `operation` | TEXT NOT NULL | `DEBIT` or `CANCEL` |
| `amount` | INTEGER NULL | > 0 for `DEBIT`; `NULL` for `CANCEL` |
| `outcome` | TEXT NOT NULL | `EXECUTED`, `INSUFFICIENT_FUNDS` or `CANCELLED` |
| `balance_after` | INTEGER NOT NULL | account balance right after this decision |
| `decided_at` | TEXT NOT NULL | ISO-8601 UTC, for logs only (no decision depends on it) |

- **Primary key** `(account_id, sequence_key)`: a key has at most one record, ever (spec: Key
  Entities; research R2 second line of defence).
- `CHECK ((operation = 'DEBIT' AND amount > 0) OR (operation = 'CANCEL' AND amount IS NULL))`
- `CHECK ((operation = 'CANCEL') = (outcome = 'CANCELLED'))`
- Records are insert-only: never updated, never deleted.

### Invariants checked by tests

- `balance = initial_balance − SUM(amount WHERE outcome = 'EXECUTED')` per account (Money Invariant).
- `last_sequence_key = MAX(sequence_key)` of the account's records, or 0 if it has none.

### Key decision state (per account and key)

```text
                 ┌──────────── debit, enough funds ──────────► EXECUTED
                 │
UNDECIDED ───────┼──────────── debit, not enough funds ──────► INSUFFICIENT_FUNDS
 (no record)     │
                 └──────────── cancel ────────────────────────► CANCELLED

UNDECIDED and key < last_sequence_key  ⇒  can never be decided (answer STALE_KEY).
All three decided states are terminal: no transition out of them (constitution, Principle II).
```

### Decision table (one `BEGIN IMMEDIATE` transaction — FR-005…FR-018)

Validation (FR-001, FR-015, EC-004) happens before the transaction and never touches the database.

| # | Condition (in this order) | Debit request → answer | Cancel request → answer | Writes |
|---|---|---|---|---|
| 1 | account not found | `UNKNOWN_ACCOUNT` | `UNKNOWN_ACCOUNT` | none |
| 2 | record exists, op `DEBIT`, request is debit with **different amount** | `KEY_CONFLICT` | — | none |
| 3 | record exists | replay record outcome, `duplicate=true` (`EXECUTED`, `INSUFFICIENT_FUNDS` or `CANCELLED`) | `EXECUTED` → `ALREADY_EXECUTED`; `INSUFFICIENT_FUNDS` → `ALREADY_REJECTED`; `CANCELLED` → `CANCELLED` (`duplicate=true`) | none |
| 4 | no record and `key ≤ last_sequence_key` | `STALE_KEY` | `STALE_KEY` | none |
| 5 | new key, debit, `balance ≥ amount` | `EXECUTED` | — | balance −= amount; last key = key; insert record |
| 6 | new key, debit, `balance < amount` | `INSUFFICIENT_FUNDS` | — | last key = key; insert record |
| 7 | new key, cancel | — | `CANCELLED` | last key = key; insert record |

Rows 5–7 are the only writes; they update `accounts` and insert into `transaction_records` in the
same transaction, then commit before the answer is written (FR-013). `ALREADY_EXECUTED` and
`ALREADY_REJECTED` replay a decision, so they carry `duplicate = true`.

### Log decision per answer (NFR-004)

The `decision` field of the request log line says what this request did; `outcome` gives the detail.

| This request… | Outcomes | `decision` |
|---|---|---|
| wrote a record that executed or cancelled (rows 5, 7) | `EXECUTED`, `CANCELLED` | `EXECUTED` |
| replayed an existing record (row 3) | any replayed outcome, `ALREADY_EXECUTED`, `ALREADY_REJECTED` | `DUPLICATE` |
| wrote a refusal record (row 6) or wrote nothing (rows 1, 2, 4, invalid, unavailable) | `INSUFFICIENT_FUNDS`, `STALE_KEY`, `KEY_CONFLICT`, `INVALID_REQUEST`, `UNKNOWN_ACCOUNT`, `UNAVAILABLE` | `REJECTED` |
| was dropped by FS-1 / FS-2 before deciding | — (no answer) | `DROPPED` with `fault=` set |

---

## Client store (`client.db`)

### `key_counter` — Client Key Counter

| Column | Type | Rules |
|---|---|---|
| `id` | INTEGER primary key | `CHECK (id = 1)` (single row) |
| `last_key` | INTEGER NOT NULL | only increases (FR-019) |

### `journal` — Client Journal Entry

| Column | Type | Rules |
|---|---|---|
| `sequence_key` | INTEGER primary key | the key reserved for this transaction |
| `account_id` | TEXT NOT NULL | |
| `operation` | TEXT NOT NULL | `DEBIT` or `CANCEL`; may change `DEBIT` → `CANCEL` once (FR-027) |
| `amount` | INTEGER NOT NULL | > 0; kept after a switch to `CANCEL` so the history stays readable |
| `state` | TEXT NOT NULL | `PENDING`, `UNKNOWN` or `RESOLVED` |
| `outcome` | TEXT NULL | final outcome from the server once `RESOLVED` |
| `balance_after` | INTEGER NULL | from the answer, when present |
| `attempts` | INTEGER NOT NULL | attempts of the **current** operation; incremented and committed before each send; reset to 0 by the switch to `CANCEL`; `is_retry = attempts_before_this_one > 0` (FR-021). Used **only** for `is_retry`; the retry limits count per send run (FR-022) |
| `created_at`, `updated_at` | TEXT NOT NULL | ISO-8601 UTC, logs only |

- Partial unique index `UNIQUE (account_id) WHERE state <> 'RESOLVED'` (FR-025, EC-009).
- **Reserve** = one transaction: `last_key += 1`, insert journal row with that key and `PENDING`. If the
  unique index rejects the insert, the whole transaction rolls back and the key is not consumed.

### Journal entry state machine

```text
          reserve (FR-019/020)
   ─────────────────────────────► PENDING ──── final outcome ────► RESOLVED (terminal)
                                     │  ▲
                 retries exhausted   │  │  resend on start (FR-026)
                                     ▼  │
                                   UNKNOWN

   cancel (FR-027), allowed from UNKNOWN, or from PENDING when no thread of this client is sending it:
       operation := CANCEL, attempts := 0, state := PENDING   (one committed update)
```

- A `PENDING` entry found at start-up was interrupted by a client crash; it is resent using its
  current `operation`, with `is_retry = true` if an attempt was already counted (it may have reached
  the server) and `false` otherwise.
- The switch `DEBIT` → `CANCEL` is a single journal update committed **before** anything is sent for
  that key (FR-027), so recovery always sends the latest intention, never an outdated debit.
- Client-side result of a resolved entry:

| Operation | Server outcome | Client result |
|---|---|---|
| DEBIT | `EXECUTED` | `SUCCEEDED` |
| DEBIT | `INSUFFICIENT_FUNDS`, `KEY_CONFLICT`, `INVALID_REQUEST`, `UNKNOWN_ACCOUNT` | `REJECTED` |
| DEBIT | `CANCELLED`, `STALE_KEY` | `NOT_EXECUTED` |
| CANCEL | `CANCELLED`, `ALREADY_REJECTED`, `STALE_KEY` | `CANCELLED` (no money moved) |
| CANCEL | `ALREADY_EXECUTED` | `SUCCEEDED` (cancel too late; money was debited) |
| CANCEL | `INVALID_REQUEST`, `UNKNOWN_ACCOUNT` | `REJECTED` |
| any | `4xx` with an unparseable body | `REJECTED` (journal `outcome` = `NULL`, raw status logged) |
| any | retries exhausted (attempts or total time) | `UNKNOWN` (entry stays unresolved) |
| — | refused locally (EC-009, or cancel of a resolved key, a key not in the journal, or a key being sent right now) | `REFUSED` (nothing sent, no journal change) |

---

## Fault configuration (server memory only, never persisted)

`FaultConfig` = ordered list of `FaultRule` records; empty list by default (FR-030).

| Field | Type | Rules |
|---|---|---|
| `scenario` | enum | `DROP_REQUEST` (FS-1), `CRASH_BEFORE_EXECUTE` (FS-2), `DROP_RESPONSE` (FS-3), `DELAY_BEFORE_EXECUTE` (FS-4a), `DELAY_AFTER_EXECUTE` (FS-4b) |
| `trigger` | enum | `NEXT_N` or `PROBABILITY` |
| `count` | int | required for `NEXT_N`, 1–10,000; decremented atomically |
| `probability` | double | required for `PROBABILITY`, 0 < p ≤ 1 |
| `account_id` | string, optional | limits the rule to one account |
| `operation` | enum, optional | `DEBIT` or `CANCEL`; limits the rule to one operation |
| `delay_ms` | int | required for `DELAY_*`, 1–60,000 |
| `downtime_ms` | int | for `CRASH_BEFORE_EXECUTE`, 0–60,000, default 1000 |
| `seed` | long, optional, config-level | seed of the draw sequence for `PROBABILITY` rules (research R5) |

- Invalid rules are refused as a whole; the previous config stays active (EC-012).
- The probabilities of `PROBABILITY` rules that can match the same request (overlapping account and
  operation filters) MUST add up to at most 1; a request triggers at most one scenario (FR-029).
- The config and its `NEXT_N` counters survive an FS-2 crash-restart (FR-029).
