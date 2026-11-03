# Quickstart: Validate Effectively-Once Debit

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-11-03

How to prove the feature works end to end. Endpoint and field details are in
[contracts/http-api.md](contracts/http-api.md) and [contracts/client-cli.md](contracts/client-cli.md).

## Prerequisites

- JDK 21 on `PATH` (`java -version`). Maven is **not** needed: the wrapper downloads it.
- Free port 8080 (or pass `--port`).
- Windows: use `mvnw.cmd`; macOS/Linux: `./mvnw`.

## 1. Automated validation (the main proof)

```bash
mvnw.cmd verify
```

Expected: build success; every test group passes. Each test's `@DisplayName` starts with the
requirement IDs it proves (for example `FS-3 / FR-008: response lost → retry replays EXECUTED`).

Run one group at a time:

```bash
mvnw.cmd test -Dgroups=integration
```

Groups (`@Tag` on every test class): `unit`, `contract`, `integration`, `process`, `soak`. The default
`verify` runs all five.

| Scenario | Proven by | Spec |
|---|---|---|
| Debit once, duplicate replay, insufficient funds, gaps, retries exhausted → `UNKNOWN` | `unit`, `integration` | US1, FR-024, EC-008 |
| FS-1, FS-2, FS-3, FS-4a, FS-4b | `integration` | US2, SC-002 |
| 50 simultaneous copies, late and stale copies | `integration` | US3, SC-003 |
| Client crash before send / after execute | `integration` | US4, SC-005 |
| Cancel, then late debit copy | `integration` | US5, SC-007 |
| Server JVM killed under load, restarted | `process` | NFR-001, SC-004 |
| 1,000 debits with random faults | `soak` | SC-001, SC-006 |

## 2. Manual demonstration

Build the jar and start the server with one account:

```bash
mvnw.cmd -q package -DskipTests
```

```bash
java -jar target/banking-sdd.jar server --port 8080 --db data/bank.db --seed A=1000
```

In a second terminal, lose the next response (FS-3) and send one debit:

```bash
curl -X PUT http://localhost:8080/admin/faults -H "Content-Type: application/json" -d "{\"rules\":[{\"scenario\":\"DROP_RESPONSE\",\"trigger\":\"NEXT_N\",\"count\":1}]}"
```

```bash
java -jar target/banking-sdd.jar client --db data/client.db debit A 100
```

Expected client output: attempt 1 fails with no response, attempt 2 returns
`"outcome":"EXECUTED","duplicate":true` and the final line shows `"result":"SUCCEEDED"`.

Check the server state:

```bash
curl http://localhost:8080/accounts/A
```

Expected: `"balance":900,"last_sequence_key":1`. The server log shows `decision=EXECUTED
fault=DROP_RESPONSE` for attempt 1 and `decision=DUPLICATE is_retry=true` for attempt 2.

Repeat with `DROP_REQUEST`, `CRASH_BEFORE_EXECUTE`, `DELAY_BEFORE_EXECUTE` or
`DELAY_AFTER_EXECUTE` (add `"delay_ms":3000` for the delay scenarios, longer than the 2 s client
timeout). In every case the balance drops by exactly 100 per debit.

Turn faults off:

```bash
curl -X DELETE http://localhost:8080/admin/faults
```

## 3. Clean state

Delete `data/bank.db` and `data/client.db` (and their `-wal` / `-shm` files) to start over.
