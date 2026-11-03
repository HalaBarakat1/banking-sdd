# banking-sdd: Fault-Tolerant Distributed Banking Transaction

An Order Service (client) debits accounts held by a Banking Service (server) over HTTP so that every
debit takes effect **exactly once**: never lost, never applied twice, even when requests are lost, the
server crashes, responses are lost or arrive too late, or the client itself crashes. The project was
built with **Spec-Driven Development** (GitHub Spec Kit): every line of code traces back to the
specification.

| | |
|---|---|
| **Track** | Track 1: Fault-Tolerant Distributed Banking Transaction (effectively-once execution) |
| **Language** | Java 21 (Temurin 21 LTS), Maven 3.9.9 through the Maven Wrapper |
| **Libraries** | JDK `HttpServer` / `HttpClient`, `sqlite-jdbc` 3.47.1.0, `jackson-databind` 2.18.2, JUnit 5.11.3. Nothing else. |
| **Coding agent** | Claude Code (Claude Opus 5.5, Claude desktop app) driving GitHub Spec Kit 1.2.7 (`/speckit-constitution`, `/speckit-specify`, `/speckit-plan`, `/speckit-tasks`, `/speckit-analyze`, `/speckit-implement`) |
| **Author** | HalaBarakat1 |
| **Tests** | 331 automated tests (unit 253, contract 50, integration 26, process 1, soak 1), all green |

## How it works

```text
Order Service (client)                                Banking Service (server)
──────────────────────                                ────────────────────────
1. reserve next key k and journal          POST /accounts/A/debits
   "k: debit 100, PENDING" (client.db)     {"sequence_key":k,"amount":100,"is_retry":…}
2. count the attempt, then send  ───────────────────►  BEGIN IMMEDIATE      (takes the write lock first)
                                                         record for (A, k)?  → replay it (duplicate=true)
                                                         k <= last key?      → STALE_KEY, never executes
                                                         else decide: debit or INSUFFICIENT_FUNDS,
                                                              last key = k, insert record for (A, k)
                                                       COMMIT                (bank.db, synchronous=FULL)
3. 2xx/4xx → final, record the outcome  ◄─────────────  answer, sent only after COMMIT
   timeout / no answer / 5xx → wait (exponential backoff with jitter) and resend the same key
   attempts or 30 s exhausted → UNKNOWN: key kept, account blocked until resent or cancelled
```

The key ideas:
- **Idempotency key.** The client generates a strictly increasing `sequence_key` per transaction and
  saves it **before** sending. Every retry reuses it. `is_retry` is logged but never decides anything.
- **One atomic decision per key.** The server checks the key and moves the money inside one SQLite
  `BEGIN IMMEDIATE` transaction. Each decided key gets its own transaction record (primary key
  `(account_id, sequence_key)`), so every copy of a request, however late, gets the original answer.
- **At-least-once delivery.** Bounded retries with exponential backoff and jitter. A run that ends without
  an answer reports `UNKNOWN`, never "failed".
- **Durable client.** A journal in `client.db` survives a client crash. `recover` and every sending command
  resend unresolved entries.
- **Cancel.** `cancel` burns a key on the server, so a debit still stuck in the network can never execute.
- **Fault injector.** Built into the server, configurable at runtime through `/admin/faults`: FS-1 request
  lost, FS-2 crash before execution (real stop and restart), FS-3 response lost, FS-4a/FS-4b response
  delayed past the client timeout.

## Repository layout

```text
.specify/memory/constitution.md          project principles (v1.1.0)
specs/001-effectively-once-debit/
  spec.md                                what and why: user stories, FR/NFR/EC/SC/FS requirements, amendments
  plan.md, research.md                   how: architecture, decisions R1–R12, traceability table
  data-model.md, contracts/, quickstart.md   tables and decision table, HTTP and CLI contracts, run guide
  tasks.md                               58 test-first tasks, all checked
src/main/java/ds/banking/                production code
  protocol/  server/{decision,store,http,fault,log}/  client/  cli/
tests/java/ds/banking/                   tests: unit/ contract/ integration/ process/ soak/ support/
docs/quickstart-run.txt                  transcript of the manual demo run (T058)
```

## Build and test

Only Java 21 is needed; the wrapper downloads Maven on first use.

Windows:

```bash
mvnw.cmd verify
```

macOS / Linux:

```bash
./mvnw verify
```

This runs all 331 tests (about 70 s, most of it the soak test) and builds `target/banking-sdd.jar`.
To run one group (`unit`, `contract`, `integration`, `process`, `soak`):

```bash
mvnw.cmd verify -Dgroups=integration
```

Every test's display name starts with the requirement IDs it proves, e.g.
`FS-3 / FR-008: the response of a committed debit is lost; the retry replays it as a duplicate`.
`TraceabilityTest` fails the build if any ID in `spec.md` is not cited by a test, or if a production class
does not name the IDs it implements.

## Run the system

Build the jar once:

```bash
mvnw.cmd -q package -DskipTests
```

Start the server with one account (it listens on 127.0.0.1 only):

```bash
java -jar target/banking-sdd.jar server --port 8080 --db data/bank.db --seed A=1000
```

In a second terminal, lose the next response (FS-3) and send a debit:

```bash
curl -X PUT http://127.0.0.1:8080/admin/faults -H "Content-Type: application/json" -d "{\"rules\":[{\"scenario\":\"DROP_RESPONSE\",\"trigger\":\"NEXT_N\",\"count\":1}]}"
```

```bash
java -jar target/banking-sdd.jar client --db data/client.db debit A 100
```

Other client commands: `cancel <account> <key>`, `recover`, `journal [account]`. Every retry limit is a
flag (`--timeout-ms`, `--attempts`, `--max-total-ms`, ...); see
[contracts/client-cli.md](specs/001-effectively-once-debit/contracts/client-cli.md). Exit codes: `0` final
result, `2` invalid arguments, `3` some transaction is `UNKNOWN`, `4` refused locally.

Inspect and reset:

```bash
curl http://127.0.0.1:8080/accounts/A
```

```bash
curl -X DELETE http://127.0.0.1:8080/admin/faults
```

## Evidence

| Requirement | Proven by |
|---|---|
| Debit once, duplicates replay, insufficient funds, gaps, stale keys (US1, FR-005…FR-014) | `DecisionServiceDebitTest`, `DebitContractTest`, `DebitFlowIT` |
| Bounded retries, `UNKNOWN`, one transaction per account (FR-021…FR-025, NFR-003) | `BackoffTest`, `PaymentClientTest`, `ClientJournalTest`, `CliTest` |
| FS-1, FS-2, FS-3, FS-4a, FS-4b, each injected for one request (US2, SC-002) | `FaultScenariosIT` |
| 50 simultaneous copies execute once; 10 accounts in parallel (US3, SC-003, NFR-002) | `ConcurrencyIT` |
| Late and stale copies (US3, FR-009) | `LateCopiesIT` |
| Real `kill -9` of the server JVM under load, then restart (SC-004, NFR-001, FR-013) | `ServerProcessCrashIT` |
| Client crash before sending / after the server executed (US4, SC-005) | `ClientRecoveryIT` |
| Cancel beats a delayed debit; crash after the cancel switch (US5, SC-007, FR-027) | `CancelIT`, `DecisionServiceCancelTest`, `CancelContractTest` |
| 1,000 debits, all faults at random, ~220 crash-restarts: every balance exact (SC-001, SC-006) | `RandomFaultSoakIT` |

Tests that check behaviour an earlier task delivered (US3, the soak test, the architecture and
traceability checks) are **verification tests**. Before each was committed it was shown to fail
against a deliberate break, named in its commit message. Two examples:
- Storing state in a per-process file made `ServerProcessCrashIT` fail with a **real double debit**
  after the restart.
- Switching `BEGIN IMMEDIATE` to `BEGIN DEFERRED` made `ConcurrencyIT` fail with 503s (see below).

## Engineering Reflection

### How Spec-Driven Development found design gaps before any code existed

1. **"Only the last key" was not enough.** The first design stored only the account's
   `last_sequence_key` and its last outcome. Writing the spec showed that a late copy of an *older*
   rejected key would then get a different answer than the original. Fix: one transaction record per
   decided key, so any copy of any key replays its original answer.
2. **A stuck request could execute after the client gave up.** While discussing the spec, I asked: what
   if a request is stuck, the client gives up, and the request arrives later? It would execute, because
   its key is still the newest. Fix: a **cancel** operation that burns the key (User Story 5), instead of
   a deadline, which would depend on clocks agreeing.
3. **Insufficient funds must consume the key** ("one key = one decision"). Otherwise a delayed copy of a
   refused debit could execute later if the balance grew.
4. **Gaps between keys must be allowed**: a client that crashes after reserving a key must not block
   its account forever.
5. **Planning found contradictions in the spec** (commit `14e5478`). FR-011 called a late debit on a
   cancelled key a `KEY_CONFLICT`, contradicting User Story 5. FR-026 ("resend every unresolved
   transaction on start") would resend the very debit the user starts the client to cancel.
6. **The first fix was flawed**, and reviewing it showed why (commit `be46a1e`). Skipping the entry in
   recovery kept the cancel only in memory: a crash before sending it would resend the debit. Final fix:
   the switch to cancel is **committed to the journal first**, and recovery always sends the latest
   intention.
7. **`/speckit-analyze` found 30 issues (9 critical) in the first task list**, then 9 more in my fixes.
   The most serious, all fixed in the spec or plan before coding:
   - Jackson's default `ACCEPT_FLOAT_AS_INT` would have turned `"amount": 2.5` into a debit of **2**.
   - There was no total retry deadline and no connect timeout.
   - A 4xx with an unreadable body would have been retried.
   - `UNKNOWN_ACCOUNT` could break the finality of a key.
   - Retry limits counted on the durable attempt counter meant an `UNKNOWN` transaction could never be
     resent.

### What the tests found during implementation

- **A NULL hole in a database rule** (commit `cfdd962`). `CHECK (operation = 'DEBIT' AND amount > 0)`
  accepts a debit with a NULL amount, because `NULL > 0` is "unknown" and SQL only rejects a row when
  the condition is false. It was found while writing the schema test, and fixed in the data model first.
- **A missing log line** (commit `b84b6a0`). In FS-4, when the client had already timed out, sending the
  late answer failed, and the log line written after it never appeared. The handler now logs in a
  `finally` block.
- **Why `BEGIN IMMEDIATE` matters** (commit `6061029`). With `BEGIN DEFERRED`, two transactions read
  first and then collide on the write lock. SQLite refuses one at once, and debits got 503, even on
  different accounts.
- **A client-side double payment** (commits `f66aa25`, `3c6c96f`). Found by the soak test: 1,001
  journal entries for 1,000 debits.
  - What happened: a recovery in one thread sent a key that a debit in another thread had just
    reserved. The debit then reported `REFUSED`, and a caller retrying after `REFUSED` paid twice.
  - Why other tests missed it: the server was correct (each key executed once), so only a realistic
    caller loop could expose it.
  - Fix: a debit owns the key it reserved and reports its real outcome.
- **A flaky test setup** (commit `b9348eb`). Log-capturing tests could lose their handler, because
  `LogManager` holds loggers weakly and an inlined constant did not load the logging class.

### Directing the coding agent

I acted as the architect:
- **Phase by phase:** each phase was reviewed before it was committed.
- **Design decisions were mine:** Option B for insufficient funds, the stale-key lookup, allowing gaps,
  the client journal, and the cancel operation.
- **I questioned the details:** for example, how the client could crash after saving its counter, and why
  the first recovery fix skipped the cancelled entry. Both led to better designs.
- **I corrected the agent's mistakes, all fixed in visible commits, never by rewriting history:**
  - Editing `tasks.md` through Windows PowerShell garbled UTF-8 characters (fixed in `4d07069`).
  - A miscounted number of tests in one commit message (corrected in the next commit).
  - A planned verification break that would not have tested durability (`ServerProcessCrashIT` uses a
    better one, recorded in `tasks.md`).
- **Every implementation task was test-first:** a red `test(Txxx)` commit, then a green `feat(Txxx)`
  commit. The Git history shows each phase in order: constitution → spec → plan → amendments → tasks →
  implementation.

### Limitations and out of scope

- **One server instance at a time.** Correctness relies on SQLite's file lock, which also holds across
  processes on one machine, but there is no replication.
- **Process crashes, not power loss.** Process crashes are tested for real. Power loss relies on
  `synchronous=FULL`, and is not tested.
- **No deposits, refunds, authentication or TLS.** A cancel that arrives after the debit executed reports
  `ALREADY_EXECUTED`; undoing it would need a refund, which is out of scope.
- **One client per account,** and accounts are seeded before clients use them (spec assumptions).
