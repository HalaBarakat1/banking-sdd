package ds.banking.client;

import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.Answer;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * The Order Service side: sends a debit at least once and makes it effectively once together with the
 * server's key check.
 *
 * <p>Implements: FR-019 and FR-020 (the key is reserved and the transaction journaled before anything
 * is sent), FR-021 (every attempt reuses the key and amount; the attempt is counted durably before it
 * is sent, and {@code is_retry} is true exactly when an earlier attempt was counted), FR-022 (backoff
 * between attempts; the run stops at its attempt limit or total deadline), FR-023 (a final answer ends
 * the run and is recorded), FR-024 (an exhausted run is {@code UNKNOWN}, never "failed", and keeps its
 * key), FR-025 (a second unresolved transaction on an account is refused locally), NFR-004 (one log
 * line per attempt).
 *
 * <p>Thread safety: the journal serializes state changes in its database; the other fields are
 * immutable or thread-safe. Concurrent debits on different accounts are allowed.
 */
public final class PaymentClient {

    private final ClientJournal journal;
    private final BankingHttpClient http;
    private final RetryPolicy policy;
    private final Backoff backoff;
    private final Sleeper sleeper;
    private final Clock clock;

    public PaymentClient(ClientJournal journal, BankingHttpClient http, RetryPolicy policy, Backoff backoff,
            Sleeper sleeper, Clock clock) {
        this.journal = journal;
        this.http = http;
        this.policy = policy;
        this.backoff = backoff;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** A client with real time, real sleeping and random jitter. */
    public static PaymentClient create(URI serverUri, Path journalFile, RetryPolicy policy) {
        return new PaymentClient(new ClientJournal(journalFile), new BankingHttpClient(serverUri, policy), policy,
                Backoff.withRandomJitter(policy), Sleeper.system(), Clock.system());
    }

    public ClientJournal journal() {
        return journal;
    }

    /** Debits {@code amount} from {@code accountId}: reserve, journal, send with retries. */
    public TransactionResult debit(String accountId, long amount) {
        Optional<JournalEntry> reserved = journal.reserve(accountId, Operation.DEBIT, amount);
        if (reserved.isEmpty()) {
            ClientLog.note("op=DEBIT account=" + accountId + " result=refused reason=unresolved transaction");
            return TransactionResult.refused(accountId,
                    "account " + accountId + " has an unresolved transaction; resolve it first");
        }
        return send(reserved.get());
    }

    /** One send run for a journal entry, with fresh attempt and time limits (FR-022). */
    TransactionResult send(JournalEntry entry) {
        long key = entry.sequenceKey();
        RetryRun run = new RetryRun(policy, clock);
        while (run.mayAttempt()) {
            boolean isRetry = journal.countAttempt(key) > 0;
            AttemptResult result = http.debit(entry.accountId(), key, entry.amount(), isRetry, run.attemptTimeout());
            run.attempted();
            ClientLog.attempt(entry.operation(), entry.accountId(), key, run.attempts(), isRetry, result);
            if (result instanceof AttemptResult.Final done) {
                return finish(entry, done, run.attempts());
            }
            Duration wait = backoff.delayAfter(run.attempts());
            if (!run.mayAttempt() || !run.waitFits(wait)) {
                break;
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        journal.markUnknown(key);
        ClientLog.note("op=" + entry.operation() + " account=" + entry.accountId() + " key=" + key
                + " result=unknown attempts=" + run.attempts());
        return new TransactionResult(Result.UNKNOWN, entry.accountId(), key, null, null, null, run.attempts(),
                "no final answer after " + run.attempts() + " attempts; the key is kept for a later resend");
    }

    private TransactionResult finish(JournalEntry entry, AttemptResult.Final done, int attempts) {
        Answer answer = done.answer();
        Outcome outcome = answer == null ? null : answer.outcome();
        Long balanceAfter = answer == null ? null : answer.balanceAfter();
        journal.resolve(entry.sequenceKey(), outcome, balanceAfter);
        String message = answer == null || answer.message() == null ? "HTTP " + done.status() : answer.message();
        return new TransactionResult(debitResult(outcome), entry.accountId(), entry.sequenceKey(), outcome,
                answer == null ? null : answer.duplicate(), balanceAfter, attempts, message);
    }

    /** Client result of a final debit answer (data-model.md, client-side result table). */
    private static Result debitResult(Outcome outcome) {
        if (outcome == Outcome.EXECUTED) {
            return Result.SUCCEEDED;
        }
        if (outcome == Outcome.CANCELLED || outcome == Outcome.STALE_KEY) {
            return Result.NOT_EXECUTED;
        }
        return Result.REJECTED;
    }
}
