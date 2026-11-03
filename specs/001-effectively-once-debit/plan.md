# Implementation Plan: Effectively-Once Distributed Debit

**Branch**: `001-effectively-once-debit` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-effectively-once-debit/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

An Order Service client debits accounts held by a Banking Service over HTTP so that every debit takes
effect exactly once despite lost requests, server crashes, lost responses and slow responses.
The client gives each transaction a durable, strictly increasing sequence key, journals it before
sending, and retries with the same key (exponential backoff with jitter) until it gets a final answer.
The server decides every request inside one SQLite `BEGIN IMMEDIATE` transaction that reads the
account's `last_sequence_key` and the key's transaction record, applies the decision table, and
commits before answering, so a key is decided once and every copy gets the same answer. A fault
injector with two hook points around the decision reproduces FS-1…FS-4b on demand.
Technical decisions and their alternatives: [research.md](research.md).

## Technical Context

**Language/Version**: Java 21 (Temurin 21 LTS)

**Primary Dependencies**: JDK `com.sun.net.httpserver.HttpServer` and `java.net.http.HttpClient`;
`org.xerial:sqlite-jdbc 3.47.1.0`; `com.fasterxml.jackson.core:jackson-databind 2.18.2`; test:
`org.junit.jupiter:junit-jupiter 5.11.3`. Build: Maven 3.9.9 via Maven Wrapper 3.3.2, Surefire 3.5.2,
Shade 3.6.0.

**Storage**: SQLite files — server `bank.db` (accounts, transaction records), client `client.db` (key
counter, journal). WAL, `synchronous=FULL` ([research R3](research.md#r3-durability-settings)).

**Testing**: JUnit 5 with tags `unit`, `contract`, `integration`, `process`, `soak`
([research R9](research.md#r9-test-strategy-and-timing)).

**Target Platform**: Any OS with JDK 21 (developed on Windows 11).

**Project Type**: Single Maven project producing a client/server CLI jar.

**Performance Goals**: ≥ 50 simultaneous requests handled correctly (NFR-002); client answer within
30 s with defaults (NFR-003); full `mvnw verify` in under 5 minutes.

**Constraints**: No dependency outside the constitution list; decisions only from persisted state;
answer only after commit; fault injector off by default.

**Scale/Scope**: Demonstration scale — tens of accounts, thousands of transactions, one server and one
client process at a time.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | How this plan complies | Status |
|---|---|---|
| I. Spec is the single source of truth | Every component lists the FR/NFR/FS/EC IDs it serves (Traceability); tests carry IDs in `@DisplayName`; spec gaps found while planning are fixed in the spec first (Amendments) | ✅ |
| II. Effectively-once money movement | One `BEGIN IMMEDIATE` transaction per decision (R2); decision from stored records only, `is_retry` only logged; per-key records replay the original answer; commit before answer; client reserves key + journals in one transaction and reuses the key on retries (R6) | ✅ |
| III. Thread-safety | Database write lock is the single serialization point (works across processes); one JDBC connection per request; fault config in an `AtomicReference` of immutable records; all DTOs are `record`s | ✅ |
| IV. Bounded failure handling | Connect + request timeout on every call; 6 attempts, equal-jitter backoff, all configurable (R7); retry on no answer / any 5xx only (R4); `UNKNOWN` on exhaustion; JSON errors with `outcome` | ✅ |
| V. Test-first, fault-proven | Each task in `tasks.md` starts with failing tests, except verification tests (v1.1.0 exception), which name the deliberate break that makes them fail; FS-1…FS-4b, same-key concurrency, money invariant, process crash and soak tests planned (R9) | ✅ |
| VI. Simplicity & observability | JDK HTTP, no framework; one log line per request with account, key, `is_retry`, decision, fault, latency; injector isolated in `server.fault` and empty by default | ✅ |
| Technology stack | Uses exactly the listed libraries; tests move to `tests/java`, which needs constitution **v1.0.1** (see Amendments) | ✅ after amendment |

**Post-design re-check (after Phase 1)**: data model, contracts and quickstart introduce no new
dependency, no in-memory money state and no decision based on `is_retry` or time. Gate passes.

## Project Structure

### Documentation (this feature)

```text
specs/001-effectively-once-debit/
├── spec.md              # What and why
├── plan.md              # This file
├── research.md          # Technical decisions R1–R12
├── data-model.md        # Tables, decision table, state machines
├── quickstart.md        # How to validate end to end
├── contracts/
│   ├── http-api.md      # Endpoints, bodies, status codes, fault behaviour
│   └── client-cli.md    # Jar sub-commands
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Next phase (/speckit-tasks)
```

### Source Code (repository root)

```text
pom.xml
mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties
src/main/java/ds/banking/
├── Main.java                         # "server" / "client" sub-command dispatch
├── protocol/                         # shared wire contract (both sides)
│   ├── DebitRequest.java, CancelRequest.java, Answer.java      # records
│   ├── Outcome.java                  # enum + retry class only (R4)
│   └── Json.java                     # configured ObjectMapper
├── server/
│   ├── BankingServer.java            # HttpServer lifecycle, crashAndRestart (R5, R11)
│   ├── ServerMain.java               # CLI args, seeding
│   ├── http/                         # thin HTTP adapters, no rules
│   │   ├── OperationHandler.java     # parse → before hook → decide → after hook → write
│   │   ├── StatusMapper.java         # HTTP status from (operation, outcome) (R4)
│   │   ├── AccountQueryHandler.java  # FR-031
│   │   └── AdminHandler.java         # seed accounts, fault config
│   ├── decision/
│   │   ├── Decider.java              # interface used by OperationHandler (test seam for 500)
│   │   └── DecisionService.java      # the decision table (FR-005…FR-018)
│   ├── store/
│   │   └── LedgerStore.java          # SQLite schema, IMMEDIATE transactions (R2, R3)
│   ├── fault/
│   │   ├── FaultInjector.java        # two hooks, AtomicReference<FaultConfig>
│   │   ├── FaultConfig.java, FaultRule.java, FaultScenario.java, FaultTrigger.java
│   │   └── FaultAction.java          # PROCEED / DROP / CRASH (+ delay)
│   └── log/
│       └── RequestLog.java           # one structured line per request (NFR-004)
└── client/
    ├── PaymentClient.java            # facade: debit, cancel, recoverPending (FR-019…FR-027)
    ├── BankingHttpClient.java        # one attempt: send, classify (R4)
    ├── RetryPolicy.java, Backoff.java, Sleeper.java   # R7
    ├── ClientJournal.java            # client.db: reserve, record, unresolved (R6)
    ├── TransactionResult.java        # SUCCEEDED / REJECTED / NOT_EXECUTED / CANCELLED / UNKNOWN / REFUSED
    ├── ClientHooks.java              # no-op by default; test seam for client crashes (R9)
    └── ClientMain.java               # CLI (contracts/client-cli.md)
tests/java/ds/banking/
├── unit/          # JsonTest, OutcomeTest, StatusMapperTest, LedgerStoreTest, RequestLogTest,
│                  # InvariantsTest, DecisionServiceDebitTest, DecisionServiceCancelTest, BackoffTest,
│                  # ClientJournalTest, BankingHttpClientTest, PaymentClientTest, CliTest,
│                  # FaultConfigTest, ArchitectureTest, TraceabilityTest
├── contract/      # InspectionContractTest, DebitContractTest, CancelContractTest,
│                  # FaultAdminContractTest (together: every row of contracts/http-api.md)
├── integration/   # DebitFlowIT, FaultScenariosIT, ConcurrencyIT, LateCopiesIT, CancelIT,
│                  # ClientRecoveryIT
├── process/       # ServerProcessCrashIT
├── soak/          # RandomFaultSoakIT
└── support/       # TestServer (random port, temp db), Invariants (money invariant + client cross-check)
```

**Structure Decision**: One Maven module. Production code in `src/main/java`, tests in `tests/java`
(the assignment's `src/` and `tests/` folders). `protocol` is the only package shared by client and
server; `server.decision` never imports `server.http` or `server.fault`, so the rules cannot depend on
transport or fault code (NFR-005). Package dependencies:
`server.http → server.decision → server.store`, `server.http → server.fault`, `client → protocol`.

## Architecture

### Request flow on the server

```text
client ──HTTP──► OperationHandler
                   1. strict parse + validate body ──invalid──► 400 INVALID_REQUEST (no DB access)
                   2. FaultInjector.beforeDecision()  ── FS-1 drop / FS-2 crash / FS-4a delay
                   3. DecisionService.debit|cancel()
                        └─ LedgerStore.inImmediateTransaction(decision table) ── COMMIT
                   4. FaultInjector.afterDecision()   ── FS-3 drop / FS-4b delay
                   5. write Answer (status = StatusMapper.statusFor(operation, outcome))
                      + RequestLog line (decision per data-model.md "Log decision per answer")
```

`OperationHandler` depends on the `Decider` interface, implemented by `DecisionService`; database
lock timeouts (`SQLITE_BUSY`) become `503 UNAVAILABLE`, any other exception `500` (research R9 seams).

### Client flow for one debit

```text
PaymentClient.debit(account, amount)
  1. ClientJournal.reserve(account, DEBIT, amount)   ── one tx: key+1, insert PENDING, attempts=0
       └─ unresolved entry exists ──► REFUSED locally (FR-025), no key consumed
  2. this run: runAttempts = 0, deadline = now + maxTotal   (fresh for every send run, FR-022)
     loop while runAttempts < maxAttempts and the deadline is not reached
       previous = ClientJournal.countAttempt(key)     ── committed before sending (FR-021)
       BankingHttpClient.send(key, amount, is_retry = previous > 0); runAttempts += 1
         ├─ final outcome  ──► ClientJournal.resolve(key, outcome) ──► return result
         └─ retryable      ──► Backoff.delay(attempt) via Sleeper, if it fits before the deadline
  3. exhausted ──► ClientJournal.markUnknown(key) ──► return UNKNOWN
```

### Concurrency and state management

- **Server**: 32 worker threads (configurable). Each request opens its own SQLite connection; the
  `IMMEDIATE` write lock serializes decisions across all threads and processes; `busy_timeout 5000 ms`
  makes waiters queue. Waiting longer than that returns `503 UNAVAILABLE`, which the client retries.
  No money or key state is held in Java memory. The only shared in-memory state is the fault config
  (`AtomicReference`, immutable value), `NEXT_N` counters (`AtomicInteger`), the draw counter
  (`AtomicLong`, research R5) and the restart flag (`AtomicBoolean`).
- **Crash-restart (FS-2)**: runs on a dedicated thread, single-flight; the fault injector is kept.
  Requests already inside a transaction either commit (and then behave like FS-3 for their client,
  whose connection is gone) or are rolled back by SQLite; both are safe.
- **Client**: `ClientJournal` uses one connection per operation; the partial unique index enforces one
  unresolved entry per account even across threads; an in-memory `ConcurrentHashMap` of active keys
  blocks a cancel of a key that is being sent right now (R12).

## Traceability

| Requirement | Component | Test group |
|---|---|---|
| FR-001…FR-004 | `protocol.*`, `StatusMapper`, `OperationHandler` | unit (`JsonTest`, `OutcomeTest`, `StatusMapperTest`), contract |
| FR-005…FR-014 | `DecisionService`, `LedgerStore` | unit (`DecisionServiceDebitTest`), integration |
| FR-015…FR-018 | `DecisionService` (cancel rows of the decision table) | unit (`DecisionServiceCancelTest`), integration (`CancelIT`) |
| FR-019…FR-021 | `ClientJournal`, `PaymentClient` | unit (`ClientJournalTest`, `PaymentClientTest`), integration |
| FR-022 | `RetryPolicy`, `Backoff` | unit (`BackoffTest`, `PaymentClientTest`) |
| FR-023…FR-027 | `PaymentClient`, `ClientMain` | unit (`PaymentClientTest`, `CliTest`), integration (`ClientRecoveryIT`, `CancelIT`) |
| FR-028…FR-030 | `server.fault.*`, `BankingServer.crashAndRestart` | unit (`FaultConfigTest`), integration (`FaultScenariosIT`) |
| FR-031 | `AccountQueryHandler` | contract |
| NFR-001 | `LedgerStore` (R3), `ClientJournal` | process (`ServerProcessCrashIT`), integration (`ClientRecoveryIT`) |
| NFR-002 | thread pool + `IMMEDIATE` transactions | integration (`ConcurrencyIT`) |
| NFR-003 | `RetryPolicy` defaults | unit (`BackoffTest` worst case), soak |
| NFR-004 | `RequestLog`, client attempt logging | unit (`RequestLogTest`, `PaymentClientTest`), contract |
| NFR-005 | package rule (`decision` imports nothing from `fault`/`http`) | unit (`ArchitectureTest`) |
| EC-001…EC-012 | as above | unit + integration, one test per EC |
| SC-001…SC-007 | whole system | integration, process, soak |

## Amendments Required Before Tasks

Found while planning; to be committed **before** `tasks.md` (constitution Principle I):

1. **Spec FR-011 contradicts User Story 5, scenario 2.** FR-011 rejects a copy whose *operation*
   differs from the record as `KEY_CONFLICT`, but US5 says a late debit copy of a cancelled key must
   receive `CANCELLED`. Fix: `KEY_CONFLICT` only when a debit copy carries a different amount than a
   debit record; a debit copy of a cancelled key replays `CANCELLED` (FR-008). Also list
   `ALREADY_EXECUTED` and `ALREADY_REJECTED` in FR-003 so all outcomes are in one place.
2. **Spec FR-026 contradicts FR-027.** "On start, resend every unresolved transaction" would re-send
   the debit a user is starting the client to cancel. First fix: FR-026 excludes the entry being
   cancelled. Review of that fix found it lost the cancel if the client crashed before sending it.
   Final fix: FR-027 saves the switch to cancel in the journal before anything is sent, and FR-026
   keeps resending every unresolved entry with its current operation.
3. **Constitution v1.0.1 (PATCH).** Technology Stack says tests live in `src/test/java`; the
   assignment's deliverables require a `tests/` folder. Change to `tests/java`.

`/speckit-analyze` on the first `tasks.md` found 30 more issues (9 critical). They were fixed in
constitution **v1.1.0** (`outcome` is the error code, transitive dependencies allowed, verification
tests, `DROPPED` log decision), a spec amendment (total deadline and connect timeout, `is_retry` from a
durable attempt counter, "same answer", strict values, operation filter, accounts exist before use),
research R4/R5/R6/R7/R8/R9 and the data model, contracts and tasks.

## Complexity Tracking

No constitution violations to justify.
