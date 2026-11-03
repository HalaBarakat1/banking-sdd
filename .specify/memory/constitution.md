# Fault-Tolerant Distributed Banking Transaction Constitution

## Core Principles

### I. Specification Is the Single Source of Truth

- Work MUST follow the Spec-Driven Development order: constitution → spec → plan → tasks →
  implement & verify. No phase starts before the previous phase's document is committed.
- Every requirement in `spec.md` MUST carry a stable identifier (`FR-xxx`, `NFR-xxx`, `FS-x` for
  failure scenarios, `EC-xxx` for edge cases).
- Every task in `tasks.md`, every production class, and every test MUST reference the
  requirement IDs it satisfies (task text, Javadoc `@implNote`/class comment, and test
  `@DisplayName`).
- Code that cannot be traced to a clause in `spec.md` and `plan.md` MUST NOT be merged. If the
  code needs behaviour the spec does not describe, the spec is amended first, then the code.

Rationale: the assignment grades the specification as the engineering contract; traceability
makes that contract checkable.

### II. Effectively-Once Money Movement (NON-NEGOTIABLE)

- A balance change MUST happen only inside the same atomic database transaction that checks and
  advances the account's `last_sequence_key`. Check-then-act across two transactions is
  forbidden.
- The server MUST decide "execute" vs "duplicate" from persisted state only. The `is_retry` flag
  is informational (logging, validation, metrics) and MUST NOT by itself cause a request to be
  skipped or executed.
- The outcome of the last executed request per account MUST be persisted with its key, so a
  retried request receives the same response as the original attempt.
- Once a sequence key is decided (executed, rejected, or cancelled), that decision is final: no
  later copy of a request with that key may change money or produce a different outcome.
- The server MUST commit the transaction before sending any success response. No balance or
  sequence state may live only in memory.
- The client MUST persist its sequence counter before sending a new transaction, and MUST reuse
  the same key for every retry of that transaction.
- The client MUST have at most one unresolved transaction per account: a new key for an account
  is issued only after the previous transaction on that account reached a final outcome.

Rationale: these rules are the minimum that guarantees no lost and no doubled debit under the
failure model of the assignment.

### III. Thread-Safety & Concurrency Discipline

- The server MUST be safe under concurrent requests, including concurrent requests carrying the
  same sequence key for the same account.
- Shared mutable state MUST be either (a) owned by the database and touched only inside a
  transaction, or (b) held in a `java.util.concurrent` type or immutable object, with the
  thread-safety argument documented in the class comment.
- JVM-level locks (`synchronized`, `ReentrantLock`) MUST NOT be the only protection for money
  state; correctness MUST hold even if two server processes share the database.
- Records and DTOs MUST be immutable (`record` types).

Rationale: race conditions are the failure the assignment names explicitly; the database is the
single serialization point.

### IV. Explicit, Bounded Failure Handling

- Every network call MUST have an explicit connect timeout and request timeout.
- Client retries MUST be bounded (maximum attempts and maximum total time) and use exponential
  backoff with jitter. All values MUST be configurable.
- Errors MUST be classified as retryable (timeout, connection failure, any HTTP 5xx) or final
  (HTTP 4xx business/validation errors such as insufficient funds, unknown account, stale key).
  Only retryable errors are retried; idempotency is what makes retrying a 5xx safe.
- Exceptions MUST NOT be swallowed. Each one is either handled with a documented decision or
  propagated. Server errors are returned as structured JSON with a machine-readable `code`.
- When retries are exhausted, the client MUST report the transaction as `UNKNOWN` (not
  `FAILED`) and keep its key, so it can be safely resubmitted later with the same key. The
  account stays blocked for new transactions until that transaction is resolved.

Rationale: an at-least-once client is only safe if its retry behaviour is predictable and its
"I don't know" state is explicit.

### V. Test-First & Fault-Proven (NON-NEGOTIABLE)

- For every task, tests are written first and MUST fail before the implementation is written.
- Every `FR-xxx` and every failure scenario `FS-1`…`FS-4` MUST have at least one automated test.
  The four fault scenarios are: request lost, server crash before execution, execution persisted
  but response lost, and response delayed past the client timeout.
- Fault and integration tests MUST check the money invariant:
  `final balance = initial balance − sum of distinct successfully executed debits`, and the
  number of executed debits per key MUST be exactly one.
- A concurrency test MUST send the same key in parallel and prove a single execution.
- A task or feature is done only when `mvnw verify` passes with all tests green. Happy-path-only
  coverage of a feature is a failed review.

Rationale: reliability claims are accepted only when a reproducible test demonstrates them.

### VI. Simplicity & Observability

- Prefer the JDK over frameworks; every third-party dependency MUST be listed in this
  constitution (see Technology Stack).
- Each server request MUST emit one structured log line containing: account id, sequence key,
  `is_retry`, decision (`EXECUTED` / `DUPLICATE` / `REJECTED`), injected fault (if any), and
  latency.
- The fault injector MUST be disabled by default, configurable at runtime (per scenario and per
  request count or probability), and clearly separated from business logic.

Rationale: a student must be able to explain every line of the generated code; fewer moving
parts and explicit logs make that possible.

## Technology Stack & Constraints

- Language: Java 21 (LTS). Build: Maven via the Maven Wrapper (`mvnw` / `mvnw.cmd`) committed to
  the repository, so no global Maven install is required.
- HTTP server: JDK `com.sun.net.httpserver.HttpServer`. HTTP client: JDK `java.net.http.HttpClient`.
- Persistence: SQLite through JDBC (`org.xerial:sqlite-jdbc`), file-based so state survives a
  process crash and restart.
- JSON: `com.fasterxml.jackson.core:jackson-databind`.
- Testing: JUnit 5 (`org.junit.jupiter`).
- Logging: `java.util.logging` (no extra dependency).
- No other runtime or test dependency may be added without amending this constitution.
- Source layout: production code under `src/main/java`, tests under `tests/java` (the assignment's
  required `src/` and `tests/` folders); client, server, persistence, and fault-injection code live in
  separate packages.

## Development Workflow & Quality Gates

- Git history MUST show the SDD phases in order, one or more commits per phase, using these
  prefixes:
  `docs(constitution)`, `docs(spec)`, `docs(plan)`, `docs(tasks)`, `test(Txxx)`, `feat(Txxx)`,
  `fix(Txxx)`, `docs(readme)`.
- Committing the whole project in a single commit is forbidden.
- Before `/speckit-implement`, `/speckit-analyze` MUST be run and any CRITICAL finding resolved.
- Each implementation task is committed only after its tests pass and the full suite stays green.
- The coding agent's output is reviewed by the student (the system architect) before each commit;
  any correction to the agent's direction is recorded for the README Engineering Reflection.
- `README.md` MUST state the chosen track, language and coding agent, exact run/test commands,
  and an Engineering Reflection section.

## Governance

- This constitution supersedes any other practice or agent suggestion in this repository.
- Amendments are made by editing this file in a dedicated `docs(constitution)` commit that states
  the reason and the version bump.
- Versioning follows semantic versioning: MAJOR for removed or redefined principles, MINOR for new
  principles or materially expanded rules, PATCH for clarifications.
- Compliance is checked at every phase: plan's Constitution Check, `/speckit-analyze` before
  implementation, and review of every task commit against Principles I–VI.

**Version**: 1.0.1 | **Ratified**: 2026-11-03 | **Last Amended**: 2026-11-03
