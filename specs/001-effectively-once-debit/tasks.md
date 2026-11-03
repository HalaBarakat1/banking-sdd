---

description: "Task list for Effectively-Once Distributed Debit"
---

# Tasks: Effectively-Once Distributed Debit

**Input**: Design documents from `/specs/001-effectively-once-debit/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md),
[data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Required. Constitution Principle V (NON-NEGOTIABLE): each test task is written first and
committed failing; the implementation task that makes it pass follows. Tasks marked
**(verification)** check behaviour an earlier task already delivered; by the constitution's exception
they may pass on first run, and the task names the deliberate break used to show the test can fail.

**Organization**: Tasks are grouped by user story. US1 is the MVP core (server decision + client
retry); US2–US5 build on it (see Dependencies).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1…US5)
- The bracket at the end of each task lists the requirement IDs it satisfies; its tests cite them in
  `@DisplayName` and its production classes list them in their class comment (Principle I).

## Path Conventions

- Production code: `src/main/java/ds/banking/…`; tests: `tests/java/ds/banking/…` (constitution
  v1.1.0).
- Every test class carries its group with `@Tag("unit" | "contract" | "integration" | "process" |
  "soak")`; the default `mvnw verify` runs all groups (research R9).
- Contract tests are split per endpoint group (`InspectionContractTest`, `DebitContractTest`,
  `CancelContractTest`, `FaultAdminContractTest`) so tasks of different stories never edit the same
  file; together they cover every row of [contracts/http-api.md](contracts/http-api.md).
- "Invariants" below means `Invariants.check` (server money invariant) and, in tests that go through
  `PaymentClient`, also `Invariants.crossCheck` (client journal ↔ server records, research R9).
  Tests that send raw HTTP without a client journal (T043, T044, T045) use `Invariants.check` only.
- "Same answer" is the FR-008 definition: equal outcome, amount and balance after, exactly one
  `duplicate = false` per key.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: A project that builds and runs an empty test suite with one command.

- [x] T001 Add Maven Wrapper 3.3.2 pinned to Maven 3.9.9: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` [SC-002, research R10]
- [x] T002 Create `pom.xml`: Java 21, `sqlite-jdbc 3.47.1.0`, `jackson-databind 2.18.2`, test `junit-jupiter 5.11.3`, `<testSourceDirectory>tests/java</testSourceDirectory>`, Surefire 3.5.2 running `*Test` and `*IT` with tag filtering (`-Dgroups=`), Shade 3.6.0 producing `target/banking-sdd.jar` with main class `ds.banking.Main` [SC-002, research R10]
- [x] T003 [P] Add `.gitignore` (`target/`, `data/`, `*.db`, `*.db-wal`, `*.db-shm`) and `.gitattributes` (`* text=auto eol=lf`, `mvnw.cmd eol=crlf`) [NFR-001: runtime databases stay out of the repository]
- [x] T004 Create `src/main/java/ds/banking/Main.java` printing usage for `server` / `client` and exiting `2` on unknown sub-commands, with smoke test `tests/java/ds/banking/unit/BuildSmokeTest.java` written first; `mvnw.cmd verify` passes [SC-002, contracts/client-cli.md]

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Wire contract, storage, logging, invariant checks and a test server for every story.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Tests (write first, must fail)

- [x] T005 [P] `tests/java/ds/banking/unit/JsonTest.java`: `snake_case` round-trip of `DebitRequest {sequence_key, amount, is_retry}`, `CancelRequest {sequence_key, is_retry}`, `Answer {account_id, sequence_key, outcome, duplicate, amount, balance_after, message}`; rejected: unknown field, missing `is_retry`, `null` values, `"amount": 2.5`, `"amount": "250"`, `sequence_key` or `amount` ≤ 0 [FR-001, FR-002, FR-012, FR-015, EC-004]
- [x] T006 [P] `tests/java/ds/banking/unit/OutcomeTest.java`: `UNAVAILABLE` is the only retryable outcome; all others final [FR-003, FR-004]
- [x] T007 [P] `tests/java/ds/banking/unit/StatusMapperTest.java`: `statusFor(operation, outcome)` returns every status of the debit and cancel tables in contracts/http-api.md, including `CANCELLED` → 409 for `DEBIT` and 200 for `CANCEL` [FR-003, FR-004, FR-017]
- [x] T008 [P] `tests/java/ds/banking/unit/LedgerStoreTest.java`: schema has `CHECK (balance >= 0)`, `CHECK (last_sequence_key >= 0)`, primary key `(account_id, sequence_key)`, `CHECK ((operation = 'DEBIT' AND amount IS NOT NULL AND amount > 0) OR (operation = 'CANCEL' AND amount IS NULL))`, `CHECK ((operation = 'CANCEL') = (outcome = 'CANCELLED'))`; pragmas `journal_mode=wal`, `synchronous=2` (FULL), `foreign_keys=1`; `account_id` "1–64 chars, `[A-Za-z0-9_-]`"; seeding is idempotent for the same balance and refused for a different one; an exception inside `inImmediateTransaction` leaves no change [FR-005, FR-007, FR-014, NFR-001, EC-011]
- [x] T009 [P] `tests/java/ds/banking/unit/RequestLogTest.java`: one line with `account`, `key`, `is_retry`, `decision`, `outcome`, `fault`, `latency_ms`; `decision` follows the table "Log decision per answer" of data-model.md, including `DROPPED` [NFR-004]
- [x] T010 [P] `tests/java/ds/banking/unit/InvariantsTest.java`: `check` detects a balance that does not match `initial_balance − SUM(amount WHERE outcome = 'EXECUTED')`, two records for one key, and `last_sequence_key ≠ MAX(sequence_key)`; `crossCheck(List<ExpectedDebit(key, amount, result)>, records)` (a neutral type defined in `support`, so it needs no client code) detects a `SUCCEEDED` entry without an `EXECUTED` record of the same key and amount, and an `EXECUTED` record without its entry; both accept a correct state [Money Invariant, SC-001]
- [x] T011 [P] `tests/java/ds/banking/unit/BankingServerLifecycleTest.java`: server starts on a free port with a pool of 32 threads, an unknown path returns 404 JSON, `stop` releases the port, a restart on the same port succeeds within 2 s [NFR-002, research R1, R11]

### Implementation

- [x] T012 [P] Protocol: `src/main/java/ds/banking/protocol/DebitRequest.java`, `CancelRequest.java`, `Answer.java` (records with boxed fields), `Outcome.java` (retry class only), `Json.java` (`SNAKE_CASE`, `FAIL_ON_UNKNOWN_PROPERTIES`, `ACCEPT_FLOAT_AS_INT` off, `FAIL_ON_NULL_FOR_PRIMITIVES`, `FAIL_ON_MISSING_CREATOR_PROPERTIES`, `FAIL_ON_NULL_CREATOR_PROPERTIES`, no scalar coercion) — passes T005, T006 [FR-001…FR-004, FR-012, research R8]
- [x] T013 [P] `src/main/java/ds/banking/server/http/StatusMapper.java` — passes T007 [FR-003, FR-004, FR-017, research R4]
- [x] T014 `src/main/java/ds/banking/server/store/LedgerStore.java`: one connection per call, `SQLiteConfig` transaction mode `IMMEDIATE`, configurable `busy_timeout` (default 5000), WAL, `synchronous FULL`, foreign keys; schema of data-model.md; `seedAccount`, `inImmediateTransaction(fn)`, `readAccount`, `readRecords` — passes T008 [FR-005, FR-007, FR-013, FR-014, NFR-001, research R2, R3]
- [x] T015 [P] `src/main/java/ds/banking/server/log/RequestLog.java` with a one-line `java.util.logging` formatter — passes T009 [NFR-004]
- [x] T016 [P] `tests/java/ds/banking/support/Invariants.java` and `support/ExpectedDebit.java` — passes T010 [Money Invariant, SC-001]
- [x] T017 `src/main/java/ds/banking/server/BankingServer.java` (start, stop, fixed pool, port rebind for up to 2 s every 50 ms) and `tests/java/ds/banking/support/TestServer.java` (temp `bank.db`, random free port) — passes T011 [NFR-002, research R1, R11]
- [x] T018 `tests/java/ds/banking/contract/InspectionContractTest.java` (test, write first): `POST /admin/accounts` returns 201 / 200 / 409 / 400 as specified; `GET /accounts/{id}` and `GET /accounts/{id}/transactions` bodies; unknown account → 404 `UNKNOWN_ACCOUNT` [FR-031]
- [x] T019 `src/main/java/ds/banking/server/http/AccountQueryHandler.java` and `server/http/AdminHandler.java` (seeding only) — passes T018 [FR-031]

**Checkpoint**: Server starts, accounts can be seeded and inspected; tests can start servers on temp
databases and check invariants.

---

## Phase 3: User Story 1 — Debit executes exactly once despite retries (Priority: P1) 🎯 MVP

**Goal**: A client debit with a durable key, retried with the same key until a final answer or the
limits, executes once; every copy gets the same answer.

**Independent Test**: Seed an account, debit once with no faults, resend the same key; balance drops
once and both answers are the same answer (spec, User Story 1).

### Tests for User Story 1 (write first, must fail)

- [x] T020 [P] [US1] `tests/java/ds/banking/unit/DecisionServiceDebitTest.java`: decision-table rows 1–6 of data-model.md — new key executes and sets last key (US1-AS1); same key replays with `duplicate=true`, same answer, no write (US1-AS2); insufficient funds consumes key and replays (US1-AS3); gap key is new (US1-AS4); stale undecided key → `STALE_KEY` (FR-009); different amount → `KEY_CONFLICT` (EC-003); unknown account writes nothing (EC-005); debit of the whole balance → 0 (EC-006); `is_retry` true/false gives the same decision (EC-001, EC-002) [FR-005…FR-011, FR-014]
- [x] T021 [P] [US1] `tests/java/ds/banking/contract/DebitContractTest.java`: `POST /accounts/{id}/debits` status and body for every debit row of contracts/http-api.md; invalid bodies (`2.5`, `"250"`, missing `is_retry`, unknown field) → 400 with `last_sequence_key` unchanged; `503 UNAVAILABLE` when a second connection holds `BEGIN IMMEDIATE` and the server's `busy_timeout` is 100 ms; `500` with `{"outcome":"UNAVAILABLE"}` when the `Decider` throws; exactly one request-log line per request [FR-001…FR-004, FR-012, FR-013, NFR-004, EC-004]
- [x] T022 [P] [US1] `tests/java/ds/banking/unit/BackoffTest.java`: wait before attempt `n+1` is within `[d/2, d]` with `d = min(maxBackoff, base × 2^(n−1))`; never zero; capped at 2 s; a wait that does not fit before the total deadline is not taken; worst case with defaults ≤ 30 s [FR-022, NFR-003]
- [x] T023 [P] [US1] `tests/java/ds/banking/unit/ClientJournalTest.java`: `reserve` increments `last_key` and inserts a `PENDING` row with `attempts = 0` in one transaction; keys strictly increase across close/reopen; `countAttempt` is committed and returns the previous count; a second unresolved entry for the same account is refused by `UNIQUE (account_id) WHERE state <> 'RESOLVED'` and consumes no key; `resolve` / `markUnknown` transitions; `client.db` pragmas WAL, `synchronous=2`, `busy_timeout=5000` [FR-019, FR-020, FR-021, FR-025, NFR-001, EC-009]
- [x] T024 [P] [US1] `tests/java/ds/banking/unit/BankingHttpClientTest.java` (stub JDK HttpServer): 2xx and 4xx parsed as final with their outcome; a 4xx with an unparseable body is final `REJECTED`; 5xx, response timeout, connect timeout, connection refused and an unparseable 2xx are retryable; HTTP/1.1 with connect and response timeouts [FR-004, FR-022, research R4]
- [x] T025 [P] [US1] `tests/java/ds/banking/unit/PaymentClientTest.java` (stub server, fake clock and sleeper): first attempt `is_retry=false`, later ones `true` with the same key and amount; stub always `503` → stops after `maxAttempts`, result `UNKNOWN`, journal entry `UNKNOWN` with its key (FR-024); stub never answering → stops at the total deadline (FR-022); a new debit on that account is `REFUSED` locally and nothing is sent (EC-008, EC-009); every attempt writes a client log line [FR-021…FR-025, NFR-003, NFR-004, EC-008, EC-009]
- [x] T026 [P] [US1] `tests/java/ds/banking/unit/CliTest.java`: `server` arguments (`--port`, `--db`, `--threads`, `--seed A=1000,B=500`) and `client` arguments with their defaults (`--connect-timeout-ms 1000`, `--timeout-ms 2000`, `--attempts 6`, `--max-total-ms 30000`, `--backoff-ms 100`, `--max-backoff-ms 2000`); exit codes `2` invalid arguments, `3` `UNKNOWN`, `4` `REFUSED`, `0` final result; `journal` sends nothing [FR-022, FR-025, contracts/client-cli.md]
- [x] T027 [US1] `tests/java/ds/banking/integration/DebitFlowIT.java`: real client + `TestServer`, no faults: US1 acceptance scenarios 1–4 end to end, client result `SUCCEEDED`, Invariants hold [US1, FR-021, FR-023]

### Implementation for User Story 1

- [x] T028 [US1] `src/main/java/ds/banking/server/decision/Decider.java` and `DecisionService.java` `debit(...)`: rows 1–6 of the decision table inside `LedgerStore.inImmediateTransaction`; returns `Answer` + log decision — passes T020 [FR-005…FR-011, FR-014]
- [x] T029 [US1] `src/main/java/ds/banking/server/http/OperationHandler.java` for `POST /accounts/{id}/debits`: strict parse + validate (no DB access on invalid input), decide through `Decider`, answer after commit with `StatusMapper`, `SQLITE_BUSY` → 503, other exceptions → 500, one `RequestLog` line; extend `BankingServer` and `tests/java/ds/banking/support/TestServer.java` with a `Decider` factory and a `busyTimeoutMs` option for the T021 seams — passes T021 [FR-001…FR-004, FR-012, FR-013, NFR-004]
- [x] T030 [P] [US1] `src/main/java/ds/banking/client/RetryPolicy.java`, `Backoff.java`, `Sleeper.java`, `Clock` seam (defaults connect 1 s, response 2 s, 6 attempts, total 30 s, base 100 ms, max 2 s; injectable random) — passes T022 [FR-022, NFR-003, research R7]
- [x] T031 [P] [US1] `src/main/java/ds/banking/client/ClientJournal.java` (`client.db`: `key_counter`, `journal`, partial unique index, `countAttempt`, R3 pragmas) — passes T023 [FR-019, FR-020, FR-021, FR-025, NFR-001, research R6]
- [x] T032 [US1] `src/main/java/ds/banking/client/BankingHttpClient.java`: one attempt, classification of research R4 — passes T024 [FR-004, FR-022]
- [x] T033 [US1] `src/main/java/ds/banking/client/PaymentClient.java` `debit(account, amount)` and `TransactionResult.java`: reserve → loop with a **per-run** attempt counter and deadline that start fresh on every send run (`countAttempt` committed before send and used only for `is_retry = previous count > 0`; backoff within the run's deadline) → `resolve` or `markUnknown`; local `REFUSED`; attempt logging — passes T025, T027 [FR-021…FR-025, NFR-004]
- [x] T034 [US1] `src/main/java/ds/banking/server/ServerMain.java`, `src/main/java/ds/banking/client/ClientMain.java` (`debit`, `journal`) and `Main` dispatch per contracts/client-cli.md — passes T026 [FR-022, FR-025, FR-031]
- [x] T035 [US1] `tests/java/ds/banking/unit/ArchitectureTest.java` **(verification)**: no file in `server/decision` or `server/store` imports `ds.banking.server.http` or `ds.banking.server.fault`, and no file in `client` imports `ds.banking.server`. Break used: a temporary forbidden import in `DecisionService` [NFR-005]

**Checkpoint**: MVP — `mvnw verify` green; manual `client debit` works against `server`.

---

## Phase 4: User Story 2 — Faults can be injected and the system survives them (Priority: P1)

**Goal**: The five fault scenarios can be switched on at runtime and the system still debits once.

**Independent Test**: For each scenario enable it for the next request only, run one debit through the
client, check exactly one execution, result `SUCCEEDED`, Invariants hold (spec, User Story 2).

### Tests for User Story 2 (write first, must fail)

- [x] T036 [P] [US2] `tests/java/ds/banking/unit/FaultConfigTest.java`: rule validation quoted from data-model.md — `count` "required for `NEXT_N`, 1–10,000", `probability` "required for `PROBABILITY`, 0 < p ≤ 1", `delay_ms` "required for `DELAY_*`, 1–60,000", `downtime_ms` "0–60,000, default 1000"; probabilities of overlapping rules sum to at most 1; an invalid config is refused as a whole and the previous one stays (EC-012); a request triggers at most one scenario (`NEXT_N` first, then one cumulative draw); `NEXT_N` fires exactly `count` times under 50 concurrent threads; draws from the `AtomicLong` counter are reproducible for sequential requests; account and operation filters; empty config never fires [FR-028, FR-029, FR-030, EC-012]
- [ ] T037 [P] [US2] `tests/java/ds/banking/contract/FaultAdminContractTest.java`: `GET`, `PUT` (200 / 400 keeps previous), `DELETE /admin/faults`; inspection and admin endpoints are never faulted [FR-029, FR-030]
- [ ] T038 [US2] `tests/java/ds/banking/integration/FaultScenariosIT.java`: one test each for FS-1 `DROP_REQUEST` (server logs `decision=DROPPED`), FS-2 `CRASH_BEFORE_EXECUTE`, FS-3 `DROP_RESPONSE`, FS-4a `DELAY_BEFORE_EXECUTE`, FS-4b `DELAY_AFTER_EXECUTE` (client timeout 300 ms, delay 600 ms, downtime 300 ms): result `SUCCEEDED`, exactly one `EXECUTED` record for the key, ≥ 2 attempts with later ones `is_retry=true`, all answers the same answer, Invariants hold; the fault config is still active after an FS-2 restart; no rule → no fault [US2-AS1…AS5, FR-028, FR-029, SC-002]

### Implementation for User Story 2

- [x] T039 [P] [US2] `src/main/java/ds/banking/server/fault/FaultScenario.java`, `FaultTrigger.java`, `FaultRule.java` (record + validation, account and operation filters), `FaultConfig.java` (overlap sum check, seed), `FaultAction.java` — passes T036 validation cases [FR-028, FR-029, EC-012]
- [x] T040 [US2] `src/main/java/ds/banking/server/fault/FaultInjector.java`: `AtomicReference<FaultConfig>`, scenario chosen once in `beforeDecision` and reused in `afterDecision`, `NEXT_N` `AtomicInteger`, draws `new SplittableRandom(seed + n)` with `n` from an `AtomicLong` — passes T036 [FR-028…FR-030, research R5]
- [ ] T041 [US2] Hook the injector into `OperationHandler` after parse + validate (drop = close the exchange without answering, log `DROPPED`; delays), and add `/admin/faults` to `AdminHandler` — passes T037 [FR-028…FR-030, NFR-004, NFR-005]
- [ ] T042 [US2] `BankingServer.crashAndRestart(downtime)`: dedicated restart thread, single-flight `AtomicBoolean`, `stop(0)`, rebuild `LedgerStore` / `DecisionService` / handlers on the same file, keep the `FaultInjector`, rebind the same port — passes T038 [FR-028, FR-029, research R5, R11]

**Checkpoint**: All five scenarios demonstrable with `curl` (quickstart.md §2).

---

## Phase 5: User Story 3 — Concurrent and late copies are harmless (Priority: P2)

**Goal**: Prove that simultaneous and very late copies never debit twice or change an answer, and that
state survives a real server crash. US1 already delivers the behaviour, so these are verification
tests; any defect they reveal is fixed in a `fix(Txxx)` commit.

**Independent Test**: 50 identical copies at once → 1 execution; old key after a newer key → not
executed (spec, User Story 3).

- [ ] T043 [P] [US3] `tests/java/ds/banking/integration/ConcurrencyIT.java` **(verification)**: 50 identical copies released together by a `CountDownLatch` → 1 execution, 50 copies of the same answer, exactly one `duplicate=false` (US3-AS1, SC-003, EC-007); concurrent debits on 10 accounts → Invariants hold for every account (US3-AS4). Break used: `DecisionService` skips the record lookup [NFR-002, EC-007, SC-003]
- [ ] T044 [P] [US3] `tests/java/ds/banking/integration/LateCopiesIT.java` **(verification)**: late copy of key 5 after key 6 replays the original (US3-AS2); undecided key 4 below last key → `STALE_KEY` now and after further keys (US3-AS3); late copy of a key rejected for insufficient funds replays `INSUFFICIENT_FUNDS`; Invariants hold. Break used: keys ≤ last treated as new [FR-008, FR-009]
- [ ] T045 [US3] `tests/java/ds/banking/process/ServerProcessCrashIT.java` **(verification)**: start `ServerMain` as a separate JVM via `ProcessBuilder` with the test class path, run debits, `destroyForcibly()` under load, restart on the same file; every answered decision is still present and replays unchanged; Invariants hold. Break used: the `commit()` call removed, so answers are sent for decisions that are never saved and the durability assertions fail after restart [SC-004, NFR-001, EC-011]

**Checkpoint**: Race conditions and true process crashes covered.

---

## Phase 6: User Story 4 — The client recovers from its own crash (Priority: P2)

**Goal**: After a client crash, unresolved transactions finish with exactly one execution and keys are
never reused.

**Independent Test**: Record a pending debit, stop the client before/after sending, start a new client
on the same `client.db`, check exactly one execution (spec, User Story 4).

### Tests for User Story 4 (write first, must fail)

- [ ] T046 [US4] `tests/java/ds/banking/integration/ClientRecoveryIT.java`: crash after reserve, before any attempt is counted → restart resends with `is_retry=false`, executes once (US4-AS1); crash after the server executed, before the answer is recorded → restart resends with `is_retry=true`, gets `duplicate=true`, no second debit (US4-AS2); next new key after restart is greater than the reserved one (US4-AS3); an `UNKNOWN` entry whose journal already counts 6 attempts (the maximum) is resent with `is_retry=true` when a sending command starts and resolves, because the retry limits are per send run (FR-022); Invariants hold [FR-021, FR-022, FR-026, SC-005, EC-008]

### Implementation for User Story 4

- [ ] T047 [US4] `src/main/java/ds/banking/client/ClientHooks.java` (no-op by default; points `AFTER_RESERVE`, `AFTER_RESPONSE_BEFORE_RECORD`, `AFTER_CANCEL_SWITCH`) and the calls to it in `PaymentClient` [FR-026, research R9]
- [ ] T048 [US4] First add `CliTest` cases for `recover` and its exit codes and see them fail; then `PaymentClient.recoverPending()` resending every `PENDING` / `UNKNOWN` entry with its current operation, FR-021 `is_retry`, and fresh per-run limits; `ClientMain` runs it before `debit` and adds `recover` — passes T046 and the new `CliTest` cases [FR-021, FR-022, FR-026]

**Checkpoint**: Effectively-once holds end to end, including client crashes.

---

## Phase 7: User Story 5 — Cancel a transaction that might still arrive (Priority: P3)

**Goal**: A cancel permanently burns a key so a late debit copy can never execute.

**Independent Test**: Delay a debit at the server, cancel its key, let the delayed request finish;
balance unchanged (spec, User Story 5).

### Tests for User Story 5 (write first, must fail)

- [ ] T049 [P] [US5] `tests/java/ds/banking/unit/DecisionServiceCancelTest.java`: cancel rows of the decision table — new key → `CANCELLED`, last key set, no money moved (US5-AS1); repeated cancel → `CANCELLED` `duplicate=true` (US5-AS4); executed key → `ALREADY_EXECUTED` with original `amount` and `balance_after`, `duplicate=true` (US5-AS3); insufficient-funds key → `ALREADY_REJECTED`, `duplicate=true` (EC-010); stale undecided key → `STALE_KEY`; debit copy of a cancelled key → `CANCELLED` `duplicate=true`, not `KEY_CONFLICT` (US5-AS2, FR-011) [FR-011, FR-015…FR-018, EC-010]
- [ ] T050 [P] [US5] `tests/java/ds/banking/contract/CancelContractTest.java`: `POST /accounts/{id}/cancellations` status and body for every cancel row of contracts/http-api.md; invalid bodies → 400 [FR-015, FR-017, FR-018]
- [ ] T051 [US5] `tests/java/ds/banking/integration/CancelIT.java`: client timeout 200 ms, 3 attempts, total 1,000 ms; rule `DELAY_BEFORE_EXECUTE`, `NEXT_N` count 3, `operation` `DEBIT`, `delay_ms` 2,000 → client result `UNKNOWN`; then cancel → `CANCELLED` with first cancel attempt `is_retry=false`; after the three delayed attempts finish (their log lines appear), each replays `CANCELLED` and the balance is unchanged (SC-007); crash at `AFTER_CANCEL_SWITCH` → next start sends the cancel, never the debit (FR-027); cancel of a key being sent right now is `REFUSED` (research R12); Invariants hold [SC-007, FR-021, FR-027]

### Implementation for User Story 5

- [ ] T052 [US5] `DecisionService.cancel(...)` in the same `inImmediateTransaction` pattern — passes T049 [FR-011, FR-016, FR-017, FR-018]
- [ ] T053 [US5] `POST /accounts/{id}/cancellations` in `OperationHandler`, with the same parsing, fault hooks, status mapping and logging as debit — passes T050 [FR-015, FR-017, NFR-004]
- [ ] T054 [US5] First add `CliTest` cases for `cancel` (including `REFUSED` → exit 4) and see them fail; then `ClientJournal.switchToCancel(key)` (one committed update: `operation := CANCEL`, `attempts := 0`, `state := PENDING`, allowed from `UNKNOWN` or a `PENDING` entry not being sent), `PaymentClient.cancel(account, key)` with the active-key guard, `ClientMain cancel` (switch first, then normal recovery) — passes T051 and the new `CliTest` cases [FR-021, FR-026, FR-027, research R12]

**Checkpoint**: All user stories complete and independently tested.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T055 `tests/java/ds/banking/soak/RandomFaultSoakIT.java` **(verification)**: 1,000 debits over 10 accounts in parallel; one config with the five scenarios at probability 0.1 each (sum 0.5), seed 42; client timeout 150 ms, delays 300 ms, downtime 100 ms; afterwards run one recovery pass (fresh per-run limits) for any `UNKNOWN`; Invariants hold for 100% of accounts, zero double debits, each transaction reaches a final outcome or `UNKNOWN` within its total deadline. Break used: `DecisionService` skips the record lookup [SC-001, SC-006, NFR-003]
- [ ] T056 [P] `tests/java/ds/banking/unit/TraceabilityTest.java` **(verification)**: every `FR-`, `NFR-`, `EC-`, `SC-` and `FS-` ID in spec.md appears in at least one test `@DisplayName` under `tests/java`, and every class under `src/main/java` has a class comment naming at least one ID. Break used: a temporary ID missing from all tests [constitution Principle I, V]
- [ ] T057 [P] `README.md`: chosen track (Track 1), language (Java 21) and coding agent (Claude Code), exact build/run/test commands, project layout, and the Engineering Reflection (design gaps SDD found before code: per-key transaction records, cancel burning keys, FR-011 vs US5, FR-026 vs FR-027 and the flawed first fix, `/speckit-analyze` findings such as `2.5` → `2`, retry deadline, `UNKNOWN_ACCOUNT` finality) [assignment §5, SC-002]
- [ ] T058 Run quickstart.md §1 and §2 by hand on a clean `data/` folder and record the observed output in `README.md` [SC-002, quickstart.md]

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup; blocks every story. Inside it: T017 (`TestServer`)
  before T018, which needs it.
- **US1 (Phase 3)**: depends on Foundational. It is the core every other story exercises.
- **US2 (Phase 4)**: depends on US1 (faults are observed through `PaymentClient.debit` and the debit
  endpoint).
- **US3 (Phase 5)**: depends on US1 only.
- **US4 (Phase 6)**: depends on US1 only (`ClientHooks` simulate the crashes; no fault rules needed).
- **US5 (Phase 7)**: depends on US2 (T051 delays the debit with an operation-filtered rule) and US4
  (T051 crash case uses `ClientHooks.AFTER_CANCEL_SWITCH`).
- **Polish (Phase 8)**: depends on all stories (T055 needs every scenario and recovery).

Recommended order (single developer): Setup → Foundational → US1 → US2 → US3 → US4 → US5 → Polish.

### Within Each Story

- Tests are written and committed failing before the implementation tasks of the same story.
- `protocol` before `store` before `decision` before `http`; client: `RetryPolicy` / `ClientJournal`
  before `BankingHttpClient` before `PaymentClient` before `ClientMain`.
- `CliTest` grows across stories (T026, then cases in T048 and T054), so those edits are sequential.

### Parallel Opportunities

- Phase 2: T005–T011 (tests) together; T012, T013, T015, T016 together.
- US1: T020–T026 together; T030 and T031 together.
- US2: T036 and T037 together. US3: T043 and T044 together. US5: T049 and T050 together.
- Polish: T056 and T057 together.

---

## Parallel Example: User Story 1

```bash
# Write the independent US1 tests together (they touch different files):
Task: "DecisionServiceDebitTest in tests/java/ds/banking/unit/DecisionServiceDebitTest.java"
Task: "DebitContractTest in tests/java/ds/banking/contract/DebitContractTest.java"
Task: "BackoffTest in tests/java/ds/banking/unit/BackoffTest.java"
Task: "ClientJournalTest in tests/java/ds/banking/unit/ClientJournalTest.java"
Task: "BankingHttpClientTest in tests/java/ds/banking/unit/BankingHttpClientTest.java"
Task: "PaymentClientTest in tests/java/ds/banking/unit/PaymentClientTest.java"
Task: "CliTest in tests/java/ds/banking/unit/CliTest.java"

# Then the independent client pieces together:
Task: "RetryPolicy/Backoff/Sleeper in src/main/java/ds/banking/client/"
Task: "ClientJournal in src/main/java/ds/banking/client/ClientJournal.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 Setup → Phase 2 Foundational.
2. Phase 3 US1 → **STOP and VALIDATE**: `mvnw verify` green and a manual `client debit` works.

### Incremental Delivery

1. US1 → effectively-once without faults, bounded retries, `UNKNOWN` (MVP).
2. US2 → the four required fault scenarios (the assignment's core proof).
3. US3 → races and real process crash.
4. US4 → client crash recovery.
5. US5 → cancel.
6. Polish → soak test, traceability check, README with Engineering Reflection.

---

## Notes

- **Commit pattern** (constitution, Development Workflow): a test task is committed as
  `test(Txxx): …` while red (a verification test's message names the break it was checked with); the
  implementation task that makes it pass is committed as `feat(Tyyy): …` and ticks both checkboxes in
  this file. Fixes found later: `fix(Txxx): …`. README: `docs(readme): …`.
- Every test `@DisplayName` starts with the IDs it proves, written out one by one (no ranges such as
  "FR-005…FR-011", which T056 would not match), e.g.
  `"FS-3 / FR-008: response lost → retry replays EXECUTED"`. The ranges in the task brackets above are
  shorthand for the individual IDs.
- Every production class's Javadoc lists the IDs it implements (checked by T056).
- `/speckit-analyze` was run on the first version of this file; its 30 findings are resolved here and
  in the amendments listed in plan.md. Run it again before T001.
