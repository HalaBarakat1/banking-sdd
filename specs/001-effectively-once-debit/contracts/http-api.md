# HTTP Contract: Banking Service

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-11-03

All bodies are JSON (`Content-Type: application/json; charset=utf-8`), field names in `snake_case`.
Parsing is strict (research R8): unknown fields, missing fields, `null`, fractional numbers
(`2.5`) and numbers sent as strings (`"250"`) are all `INVALID_REQUEST` — nothing is rounded or
defaulted (FR-012). Every response body except plain inspection results has an `outcome` field, which
is the machine-readable error code (constitution, Principle IV).

The status depends on the operation **and** the outcome (tables below), computed by
`StatusMapper.statusFor(operation, outcome)`. The client decides by **status class**: `2xx` final
(in effect), `4xx` final (rejected), `5xx` / no answer / unparseable `2xx` body → retryable, resend
with the same key. A `4xx` is final even if its body cannot be parsed (research R4).

---

## 1. Debit — `POST /accounts/{account_id}/debits` (FR-001…FR-014)

**Request**

```json
{ "sequence_key": 7, "amount": 250, "is_retry": false }
```

| Field | Type | Rules |
|---|---|---|
| `sequence_key` | integer | > 0 |
| `amount` | integer | > 0, minor units |
| `is_retry` | boolean | required; logged only (FR-010) |

**Answer** (FR-002)

```json
{
  "account_id": "A",
  "sequence_key": 7,
  "outcome": "EXECUTED",
  "duplicate": false,
  "amount": 250,
  "balance_after": 750,
  "message": "debit executed"
}
```

`amount` and `balance_after` are present when `outcome` is `EXECUTED` or `INSUFFICIENT_FUNDS`
(the balance at decision time); `message` is human-readable and never parsed.

| Outcome | HTTP | Class | Notes |
|---|---|---|---|
| `EXECUTED` | 200 | in effect | `duplicate` true when replayed |
| `INSUFFICIENT_FUNDS` | 422 | final | key consumed; replayed with `duplicate=true` |
| `CANCELLED` | 409 | final | key was burnt by a cancel; always `duplicate=true` |
| `STALE_KEY` | 409 | final | key < last and never decided; will never execute |
| `KEY_CONFLICT` | 409 | final | key decided with a different amount (FR-011) |
| `INVALID_REQUEST` | 400 | final | key not consumed |
| `UNKNOWN_ACCOUNT` | 404 | final | key not consumed |
| `UNAVAILABLE` | 503 | retry | e.g. database busy beyond `busy_timeout`, server stopping |
| (unexpected error) | 500 | retry | body `{"outcome":"UNAVAILABLE","message":...}` |

## 2. Cancel — `POST /accounts/{account_id}/cancellations` (FR-015…FR-018)

**Request**

```json
{ "sequence_key": 8, "is_retry": false }
```

**Answer**: same shape as the debit answer.

| Outcome | HTTP | Class | Notes |
|---|---|---|---|
| `CANCELLED` | 200 | in effect | new key burnt, or `duplicate=true` if already cancelled |
| `ALREADY_REJECTED` | 200 | in effect | the debit was refused; nothing will ever be debited; `duplicate=true` |
| `ALREADY_EXECUTED` | 409 | final | cancel too late; includes original `amount` and `balance_after`; `duplicate=true` |
| `STALE_KEY` | 409 | final | never decided and never will be; client treats as "not executed" |
| `INVALID_REQUEST` | 400 | final | |
| `UNKNOWN_ACCOUNT` | 404 | final | |
| `UNAVAILABLE` | 503 / 500 | retry | |

## 3. Inspection (FR-031)

`GET /accounts/{account_id}` → `200`

```json
{ "account_id": "A", "initial_balance": 1000, "balance": 750, "last_sequence_key": 7 }
```

`GET /accounts/{account_id}/transactions` → `200`

```json
[
  { "sequence_key": 7, "operation": "DEBIT", "amount": 250, "outcome": "EXECUTED",
    "balance_after": 750, "decided_at": "2026-11-03T18:00:00Z" }
]
```

Unknown account → `404` with `{"outcome":"UNKNOWN_ACCOUNT"}`. Inspection is never subject to faults.

## 4. Administration (testing and demonstration only)

### Seed an account — `POST /admin/accounts`

```json
{ "account_id": "A", "balance": 1000 }
```

`201` created; `200` if it already exists with the same initial balance; `409` if it exists with a
different one; `400` if invalid.

### Fault injector — `/admin/faults` (FR-028…FR-030)

- `GET /admin/faults` → current rules.
- `PUT /admin/faults` → replace all rules atomically. `200` with the new rules, or `400` with the reason
  (previous rules stay active, EC-012).
- `DELETE /admin/faults` → remove all rules (injector off).

```json
{
  "rules": [
    { "scenario": "DROP_RESPONSE", "trigger": "NEXT_N", "count": 1, "account_id": "A" },
    { "scenario": "DELAY_BEFORE_EXECUTE", "trigger": "PROBABILITY", "probability": 0.1,
      "delay_ms": 600, "operation": "DEBIT" }
  ],
  "seed": 42
}
```

Field rules: [data-model.md — Fault configuration](../data-model.md#fault-configuration-server-memory-only-never-persisted).
Faults apply only to the debit and cancel endpoints; a request triggers at most one scenario, and the
rules survive an FS-2 crash-restart.

## 5. What the client sees per fault scenario

| Scenario | Client observes | Client action |
|---|---|---|
| FS-1 `DROP_REQUEST` | connection closed, no response (`IOException`); server logs `decision=DROPPED` | retry, `is_retry=true` |
| FS-2 `CRASH_BEFORE_EXECUTE` | connection closed, then connection refused during downtime | retry with backoff until the server is back |
| FS-3 `DROP_RESPONSE` | connection closed, no response | retry → `EXECUTED`, `duplicate=true` |
| FS-4a `DELAY_BEFORE_EXECUTE` | timeout | retry; the two attempts race, the database lock serializes them |
| FS-4b `DELAY_AFTER_EXECUTE` | timeout | retry → `EXECUTED`, `duplicate=true` |
