# Research: Effectively-Once Distributed Debit

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-11-03

Each entry records a technical decision, why it was chosen, and what else was considered. The stack
itself is fixed by the constitution (Java 21, Maven Wrapper, JDK HTTP, SQLite, Jackson, JUnit 5); this
document decides how to use it.

---

## R1. HTTP server: JDK `HttpServer` with a fixed thread pool

- **Decision**: `com.sun.net.httpserver.HttpServer` bound to a configurable port, with
  `Executors.newFixedThreadPool(32)` as executor (size configurable).
- **Rationale**: No framework magic, so every line can be explained in the discussion (Principle VI).
  A fixed pool gives real parallelism for NFR-002 (50 simultaneous requests) and survives the blocking
  sleeps of FS-4. The JDK server lets a handler close the connection without writing a response, which
  is exactly what FS-1 and FS-3 need.
- **Alternatives**: Spring Boot (too much hidden behaviour, and an extra dependency); Jetty/Undertow
  (extra dependency, not needed for this load); the default single-threaded executor (would serialize
  all requests and hide race conditions instead of surviving them).

## R2. Atomic check-and-update: one SQLite transaction started with `BEGIN IMMEDIATE`

- **Decision**: Each debit or cancel runs in one JDBC transaction on its own connection, opened in
  SQLite `IMMEDIATE` transaction mode (`SQLiteConfig.setTransactionMode(IMMEDIATE)`). Inside it: read
  the account, read the transaction record for the key, decide, update the account, insert the record,
  commit. `busy_timeout = 5000 ms` makes concurrent writers wait in line instead of failing.
- **Rationale**: `BEGIN IMMEDIATE` takes the database write lock at the *start* of the transaction, so
  between "read last key" and "write last key" no other request can run its own check. This is what
  removes the check-then-act race (FR-007, Principle II/III). The lock is a file lock, so it also holds
  if two server processes share the file (Principle III).
  The primary key `(account_id, sequence_key)` on transaction records is a second line of defence: even
  a logic bug cannot insert two decisions for one key; the insert would fail and the whole transaction
  would roll back.
- **Alternatives**:
  - Default `BEGIN DEFERRED`: two transactions can both read, then both try to upgrade to write; one
    gets `SQLITE_BUSY` in the middle. Correct only with extra retry logic; harder to reason about.
  - A JVM lock (`synchronized` per account) around the logic: forbidden as the only protection by
    Principle III, and it does not survive a second process.
  - A single conditional `UPDATE ... WHERE last_sequence_key < ?`: protects the balance but cannot
    replay the original answer or detect conflicts by itself; the record lookup is still needed.

## R3. Durability settings

- **Decision**: `journal_mode = WAL`, `synchronous = FULL`, `foreign_keys = ON`, set on every
  connection. The answer is written to the socket only after `commit()` returns (FR-013).
- **Rationale**: With `FULL`, a committed transaction survives both a process crash and a power loss
  (NFR-001). WAL lets read-only inspection queries (FR-031) run while a debit is writing. A transaction
  interrupted by a crash is rolled back automatically by SQLite on the next open (EC-011).
- **Alternatives**: `synchronous = NORMAL` (faster; still safe for process crashes in WAL mode, but may
  lose the last commits on power loss — not acceptable for money); an in-memory map with periodic
  snapshots (loses data on crash, violates Principle II).

## R4. HTTP mapping and retry classification

- **Decision**: The `outcome` field in the JSON body is the source of truth. The HTTP status is chosen
  so that the *class* of status alone tells the client whether to retry:
  `2xx` final (operation in effect), `4xx` final (rejected), `5xx` retryable. The status depends on
  the **operation and the outcome together** (`CANCELLED` is 409 for a debit but 200 for a cancel), so
  it is computed by `server.http.StatusMapper.statusFor(operation, outcome)`; the `Outcome` enum only
  knows its retry class. Full table in [contracts/http-api.md](contracts/http-api.md).
  The client retries on: no response, timeout, connection error, any `5xx`, and a `2xx` whose body
  cannot be parsed (treated as "no answer"). A `4xx` is always final, even if its body cannot be parsed
  (client result `REJECTED`, logged with the raw status).
- **Rationale**: Matches the constitution's rule (retry on timeout, connection failure, any 5xx; 4xx is
  final). Retrying an unparseable `2xx` is safe because every retry is idempotent.
- **Alternatives**: Always `200` with the outcome in the body (simpler, but loses the standard meaning
  of status codes and makes `curl` demos less clear).

## R5. Fault injector: two hook points in the request pipeline

- **Decision**: Every debit/cancel handler runs the same pipeline:
  `parse → injector.beforeDecision() → DecisionService → injector.afterDecision() → write answer`.
  The injector holds an immutable `FaultConfig` (a list of rules) in an `AtomicReference`, swapped
  atomically by the admin endpoint. Both hooks run after parsing and validation, so every log line has
  the key and `is_retry`. A request triggers **at most one** scenario, chosen once in
  `beforeDecision`:
  1. `NEXT_N` rules that match (account, operation) are checked in list order; the first with count
     left fires and decrements its `AtomicInteger`.
  2. Otherwise one draw `u ∈ [0, 1)` is taken and the matching `PROBABILITY` rules are laid side by side
     (cumulative); the rule whose slice contains `u` fires. Validation requires the probabilities of
     rules that can match the same request to add up to at most 1, so each rule fires with exactly its
     own probability.
  The chosen scenario is remembered for `afterDecision` of the same request.

  **Random draws without shared mutable `Random`**: draw number `n` comes from an `AtomicLong`
  counter, and `u = new SplittableRandom(seed + n).nextDouble()`. The only shared mutable state is the
  `AtomicLong` (Principle III). Runs are exactly reproducible when requests arrive one at a time; under
  concurrency the sequence of draws is the same but which request gets which draw depends on arrival
  order, so only the fault *rate* is reproducible.

  Mechanics per scenario:

  | Scenario | Hook | Mechanic |
  |---|---|---|
  | FS-1 request lost | before | after parsing, close the exchange without deciding or answering |
  | FS-2 crash before execution | before | close the exchange, then *crash-restart* the server (below) |
  | FS-3 response lost | after | the decision is committed; close the exchange without answering |
  | FS-4a delay before | before | sleep `delay_ms`, then continue normally |
  | FS-4b delay after | after | sleep `delay_ms` after commit, then answer |

  Rules can be limited to one account and/or one operation (`DEBIT` / `CANCEL`).
- **Crash-restart (FS-2)**: `BankingServer.crashAndRestart(downtime)` runs on a dedicated restart
  thread (never on a pool thread of the listener it stops). It stops the HTTP listener immediately
  (`stop(0)`), discards the server's business objects (`DecisionService`, `LedgerStore`, handlers),
  waits `downtime_ms` (default 1000), and starts a new listener and new business objects on the same
  database file. Everything the server "knows" about money after restart comes from the database, which
  is what a real crash would force. The `FaultInjector` is test infrastructure, not server state: it is
  kept across the restart so its rules and counters continue (FR-029). An `AtomicBoolean` makes the
  restart single-flight: a second `CRASH_BEFORE_EXECUTE` that fires while a restart is in progress only
  drops its request. A separate process-level test (R9) kills a real server JVM to cover a true crash.
- **Rationale**: Faults sit outside the decision rules (FR-030, NFR-005); with the default empty config
  the injector is a no-op (FR-030). Both hooks are needed because FS-3 and FS-4b happen *after* the
  commit.
- **Alternatives**: A `com.sun.net.httpserver.Filter` (can only act before the handler, not between
  commit and response); a network proxy between client and server (more realistic for FS-1 but cannot
  simulate FS-2/FS-3 at the right moment and adds a process to every test); `Runtime.halt()` for FS-2
  (a true crash, but then every test needs an external restarter — covered once by the process test).

## R6. Client durable store: a second SQLite file

- **Decision**: The client keeps `key_counter` and `journal` in its own SQLite file (`client.db`).
  Reserving the next key and inserting the `PENDING` journal entry happen in **one** transaction. A
  partial unique index `UNIQUE(account_id) WHERE state <> 'RESOLVED'` enforces FR-025 in the database.
  `client.db` uses the same settings as R2/R3: `IMMEDIATE` transactions, `busy_timeout 5000`, WAL,
  `synchronous FULL`. The attempt counter of FR-021 is incremented in its own committed update
  before each send.
- **Rationale**: Doing both in one transaction means a client crash can never leave a reserved key
  without its journal entry, or the reverse (FR-019, FR-020). Reuses an allowed dependency; no hand-made
  file format, no partial writes. The index makes "one unresolved transaction per account" hold even
  if two client threads race.
- **Alternatives**: A counter file plus a JSON journal with write-to-temp-and-rename (two files that can
  disagree after a crash); keeping the journal in memory (violates NFR-001).

## R7. Backoff: exponential with "equal jitter"

- **Decision**: Before attempt `n + 1`: `d = min(maxBackoff, base × 2^(n−1))`, wait
  `d/2 + random(0, d/2)`. Defaults: connect timeout 1 s, per-attempt response timeout 2 s, 6 attempts,
  total deadline 30 s, base 100 ms, max 2 s. The attempt limit and the deadline belong to one send run
  (a command, or one recovery pass over an entry) and start fresh on each run; the durable journal
  counter only decides `is_retry` (FR-021, FR-022). Before each attempt and each wait the client
  checks the deadline; if the remaining time is shorter than the wait, it stops and reports `UNKNOWN`; the last
  attempt's timeout is shortened to the remaining time. The waiting function (`Sleeper`), the clock
  and the random source are injected so tests run fast and repeatable.
- **Rationale**: Grows exponentially (FR-022), never waits zero (unlike "full jitter"), and spreads out
  retries of many clients. Worst case with defaults, if connect and response limits add up:
  6 × (1 s + 2 s) + (0.1 + 0.2 + 0.4 + 0.8 + 1.6) s ≈ 21.1 s, under the 30 s limit; the explicit deadline guarantees NFR-003 even if timeouts are configured larger.
- **Alternatives**: Fixed delay (thundering herd); full jitter (can retry immediately, less readable in
  logs).

## R8. JSON mapping

- **Decision**: Jackson `ObjectMapper` with `PropertyNamingStrategies.SNAKE_CASE`, Java `record` DTOs
  with boxed fields (`Long`, `Boolean`), and strict parsing: `FAIL_ON_UNKNOWN_PROPERTIES` on,
  `ACCEPT_FLOAT_AS_INT` **off** (it is on by default and would turn `2.5` into `2`),
  `FAIL_ON_NULL_FOR_PRIMITIVES` on, `FAIL_ON_MISSING_CREATOR_PROPERTIES` and
  `FAIL_ON_NULL_CREATOR_PROPERTIES` on, and scalar coercion disabled so `"250"` (a string) is rejected.
  After parsing, explicit validation: `sequence_key > 0`, `amount > 0`, `is_retry` present
  → otherwise `INVALID_REQUEST`.
- **Rationale**: Field names on the wire match the spec exactly (`sequence_key`, `is_retry`). Records
  are immutable (Principle III). Jackson's lenient defaults would silently change money amounts or
  default a missing `is_retry` to `false`; FR-012 forbids rounding or defaulting.
- **Alternatives**: Hand-written JSON (error-prone); Gson (not in the allowed list).

## R9. Test strategy and timing

- **Decision**: Five test groups under `tests/java`:
  `unit` (decision rules on a temp database, backoff math, fault-config validation, JSON),
  `contract` (status codes and bodies of every endpoint),
  `integration` (real client + in-process server on a random free port: FS-1…FS-4b, concurrency,
  stale and late copies, cancel, client crash recovery),
  `process` (server started as a separate JVM with `ProcessBuilder`, killed with `destroyForcibly()`
  under load, restarted, invariant checked — SC-004),
  `soak` (1,000 debits over 10 accounts in parallel with random faults and a fixed seed — SC-001).
  Every test class carries its group with `@Tag`; the default `mvnw verify` runs all five groups.
  Integration tests use short timings (client timeout 300 ms, FS-4 delay 600 ms, downtime 300 ms);
  the soak test uses client timeout 150 ms, delays 300 ms, downtime 100 ms, so its ~100 crash-restarts
  stay within about a minute.
  Client crashes are simulated with a test-only `ClientHooks` seam that throws at a chosen point; a new
  client instance is then built on the same `client.db`.
  The money invariant is checked on the server **and**, in tests that go through `PaymentClient`,
  cross-checked against what the client believes (`Invariants.crossCheck(expected, records)`, with
  `expected` a list of `ExpectedDebit(key, amount, result)` built from the client journal): every
  `SUCCEEDED` entry has exactly one `EXECUTED` record with the same key and amount, and every
  `EXECUTED` record belongs to exactly one entry. Tests that send raw HTTP (no journal) use the
  server check only. Server-only
  checks cannot see a client that reports success for a debit that never happened.
  `503` / `500` paths are tested with two seams: a second connection holding `BEGIN IMMEDIATE` while
  the server's `busy_timeout` is set to 100 ms (forces `SQLITE_BUSY` → 503), and a `Decider` that throws
  (→ 500).
- **Rationale**: Every FR and FS gets an automated test (Principle V); short timings keep
  `mvnw verify` within a few minutes; the process test proves durability against a real crash.
- **Alternatives**: Mocking the database (would not test the atomicity that matters); Testcontainers or
  external tools (not allowed, not needed).

## R10. Build, layout and packaging

- **Decision**: Maven Wrapper 3.3.2 (Maven 3.9.9). `src/main/java` for production code and
  `tests/java` for tests (`<testSourceDirectory>`), matching the assignment's required `src/` and
  `tests/` folders. `maven-shade-plugin` builds one runnable jar `target/banking-sdd.jar` with
  sub-commands `server` and `client`. Versions: `sqlite-jdbc 3.47.1.0`, `jackson-databind 2.18.2`,
  `junit-jupiter 5.11.3`, Surefire 3.5.2.
- **Rationale**: The deliverables section of the assignment names `src/` and `tests/` explicitly; this
  needs constitution v1.0.1 (it said `src/test/java`). One jar makes the README commands short.
  Build plugins are not runtime dependencies, so the dependency list of the constitution is unchanged.
  `sqlite-jdbc` brings `slf4j-api` transitively; constitution v1.1.0 allows transitive dependencies as
  long as project code does not use them. With no binding it prints a one-line notice and otherwise
  does nothing.
- **Alternatives**: Gradle (not chosen in the constitution); keeping `src/test/java` (does not match
  the deliverables list).

## R11. Rebinding the port after a crash-restart

- **Decision**: On restart, try to bind the same port for up to 2 s (every 50 ms) before failing.
- **Rationale**: On some systems the listening port is not free the instant after `stop(0)`. A bounded
  retry keeps FS-2 tests stable without hiding a real failure.
- **Alternatives**: A new random port on restart (the client would need discovery; not realistic for a
  "the same server came back" scenario).

## R12. Client concurrency and cancel

- **Decision**: The client keeps an in-memory set of keys it is currently sending. `cancel(key)` is
  allowed only for a journal entry that is unresolved **and** not being sent right now (an `UNKNOWN`
  entry, or a `PENDING` entry found after restart). The switch of the entry's operation to `CANCEL` is
  committed to the journal first; the cancel is then sent by the same retry loop (and the same
  recovery path) as a debit.
- **Rationale**: Prevents two threads of one client from sending a debit and a cancel for the same key at
  once, which would make the client's own view ambiguous. The server is safe either way (the first
  decision wins); this rule keeps the client's journal consistent (FR-027).
- **Alternatives**: Interrupting the active send loop (more complex; the in-flight attempt may still
  execute, so it gains nothing).
