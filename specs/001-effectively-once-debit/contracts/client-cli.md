# CLI Contract: `banking-sdd.jar`

**Feature**: [spec.md](../spec.md) | **Plan**: [plan.md](../plan.md) | **Date**: 2026-11-03

One runnable jar with two sub-commands. Output is one human-readable line per event plus a final
JSON line, so demos are readable and scripts can parse the result. Exit code `0` = command completed
(whatever the business outcome), `2` = invalid arguments, `3` = result `UNKNOWN`, `1` = unexpected error.

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
        [--timeout-ms 2000] [--attempts 6] [--backoff-ms 100] [--max-backoff-ms 2000] <command>
```

| Command | Effect | Spec |
|---|---|---|
| `debit <account> <amount>` | reserve key, journal `PENDING`, send with retries, print result | US1, FR-019…FR-025 |
| `cancel <account> <key>` | cancel an unresolved (`UNKNOWN` / recovered `PENDING`) entry | US5, FR-027 |
| `recover` | resend every unresolved journal entry | US4, FR-026 |
| `journal [account]` | print journal entries | inspection |

`debit`, `cancel` and `recover` resend every unresolved entry, as FR-026 requires on start.
`cancel` first saves the switch of that entry from `DEBIT` to `CANCEL` in the journal (FR-027), and
only then runs recovery, which therefore sends the cancel, never the old debit. If the client crashes
in between, the next start of any command resends the cancel. `journal` only reads.

Example final line:

```json
{"result":"SUCCEEDED","account_id":"A","sequence_key":7,"outcome":"EXECUTED","duplicate":true,"balance_after":750,"attempts":2}
```
