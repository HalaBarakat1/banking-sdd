# CLI Contract: `banking-sdd.jar`

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-11-03

One runnable jar with two sub-commands. Output is one human-readable line per event plus a final
JSON line, so demos are readable and scripts can parse the result.

| Exit code | Meaning |
|---|---|
| `0` | command completed and every transaction it touched (including recovered ones) has a final result (`SUCCEEDED`, `REJECTED`, `NOT_EXECUTED`, `CANCELLED`) |
| `1` | unexpected error (bug, unreadable `client.db`) |
| `2` | invalid arguments |
| `3` | at least one transaction touched by the command (the new one or a recovered one) ended `UNKNOWN` |
| `4` | refused locally, nothing sent (`REFUSED`: unresolved entry on the account — EC-009, or cancel of a resolved key, a key not in the journal, or a key being sent right now) |

When several codes apply (for example recovery leaves an entry `UNKNOWN` and the new `debit` is then
`REFUSED`), the highest-priority code wins: `1` > `2` > `3` > `4` > `0`.

## Server

```text
java -jar target/banking-sdd.jar server [--port 8080] [--db data/bank.db] [--threads 32]
                                        [--seed A=1000,B=500]
```

- Creates the database file and tables if missing; `--seed` behaves like `POST /admin/accounts`.
- Logs one line per request (NFR-004) to stdout.

## Client (Order Service)

```text
java -jar target/banking-sdd.jar client [--url http://localhost:8080] [--db data/client.db]
        [--connect-timeout-ms 1000] [--timeout-ms 2000] [--attempts 6] [--max-total-ms 30000]
        [--backoff-ms 100] [--max-backoff-ms 2000] <command>
```

| Command | Effect | Spec |
|---|---|---|
| `debit <account> <amount>` | reserve key, journal `PENDING`, send with retries, print result | US1, FR-019…FR-025 |
| `cancel <account> <key>` | cancel an unresolved (`UNKNOWN` / recovered `PENDING`) entry | US5, FR-027 |
| `recover` | resend every unresolved journal entry | US4, FR-026 |
| `journal [account]` | print journal entries | inspection |

`debit`, `cancel` and `recover` send requests, so they first resend every unresolved entry, as
FR-026 requires; `journal` is read-only and sends nothing.
`cancel` first saves the switch of that entry from `DEBIT` to `CANCEL` in the journal (FR-027), and
only then runs recovery, which therefore sends the cancel, never the old debit. If the client crashes
in between, the next start of any sending command resends the cancel.

Example final line:

```json
{"result":"SUCCEEDED","account_id":"A","sequence_key":7,"outcome":"EXECUTED","duplicate":true,"balance_after":750,"attempts":2}
```
