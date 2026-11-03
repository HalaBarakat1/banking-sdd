package ds.banking.client;

import ds.banking.client.TransactionResult.Result;
import ds.banking.protocol.Answer;
import ds.banking.protocol.Operation;
import ds.banking.protocol.Outcome;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Order Service side: sends a debit at least once and makes it effectively once together with the
 * server's key check.
 *
 * <p>Implements: FR-019 and FR-020 (the key is reserved and the transaction journaled before anything
 * is sent), FR-021 (every attempt reuses the key and amount; the attempt is counted durably before it
 * is sent, and {@code is_retry} is true exactly when an earlier attempt was counted), FR-022 (backoff
 * between attempts; the run stops at its attempt limit or total deadline), FR-023 (a final answer ends
 * the run and is recorded), FR-024 (an exhausted run is {@code UNKNOWN}, never "failed", and keeps its
 * key), FR-025 (a second unresolved transaction on an account is refused locally), FR-026 (unresolved
 * entries are resent by {@link #recoverPending()}), FR-027 ({@link #cancel} saves the switch to cancel
 * before sending it, and a send always uses the current journal operation), NFR-004 (one log
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
    private final ClientHooks hooks;
    /** Keys this client is sending right now; a cancel of one of them is refused (research R12). */
    private final Set<Long> active = ConcurrentHashMap.newKeySet();

    public PaymentClient(ClientJournal journal, BankingHttpClient http, RetryPolicy policy, Backoff backoff,
            Sleeper sleeper, Clock clock) {
        this(journal, http, policy, backoff, sleeper, clock, ClientHooks.NONE);
    }

    /** As above, with crash hooks for tests (research R9). */
    public PaymentClient(ClientJournal journal, BankingHttpClient http, RetryPolicy policy, Backoff backoff,
            Sleeper sleeper, Clock clock, ClientHooks hooks) {
        this.journal = journal;
        this.http = http;
        this.policy = policy;
        this.backoff = backoff;
        this.sleeper = sleeper;
        this.clock = clock;
        this.hooks = hooks;
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
        hooks.at(ClientHooks.Point.AFTER_RESERVE, reserved.get());
        return sendOwned(reserved.get().sequenceKey());
    }

    /**
     * Sends the key a {@code debit} call has just reserved. That call owns the transaction, so it must
     * report the key's real outcome: if a recovery in another thread is already sending the key, wait for
     * it instead of answering REFUSED (a caller retrying after REFUSED would pay twice), then report the
     * journal's outcome, or send again if that run ended UNKNOWN.
     */
    private TransactionResult sendOwned(long key) {
        while (!active.add(key)) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                JournalEntry entry = journal.find(key).orElseThrow();
                return new TransactionResult(Result.UNKNOWN, entry.accountId(), key, null, null, null, 0,
                        "interrupted while another thread was sending the key; it is kept for a later resend");
            }
        }
        try {
            return send(key);
        } finally {
            active.remove(key);
        }
    }

    /**
     * Cancels the unresolved debit with {@code key} (FR-027): first the journal entry is switched from
     * debit to cancel and committed, then the cancel is sent like any other operation. Refused locally,
     * with nothing sent, if the key is not an unresolved debit of {@code accountId} in the journal, or if
     * this client is sending that key right now (research R12).
     */
    public TransactionResult cancel(String accountId, long key) {
        if (!active.add(key)) {
            return refusedCancel(accountId, key, "key " + key + " is being sent right now");
        }
        try {
            Optional<JournalEntry> current = journal.find(key);
            if (current.isPresent() && current.get().operation() == Operation.CANCEL
                    && current.get().state() != JournalState.RESOLVED && current.get().accountId().equals(accountId)) {
                return send(key); // already switched by an earlier run: just send the cancel
            }
            Optional<JournalEntry> switched = journal.switchToCancel(accountId, key);
            if (switched.isEmpty()) {
                return refusedCancel(accountId, key, "key " + key + " is not an unresolved debit of " + accountId);
            }
            hooks.at(ClientHooks.Point.AFTER_CANCEL_SWITCH, switched.get());
            return send(key);
        } finally {
            active.remove(key);
        }
    }

    private static TransactionResult refusedCancel(String accountId, long key, String reason) {
        ClientLog.note("op=CANCEL account=" + accountId + " key=" + key + " result=refused reason=" + reason);
        return TransactionResult.refused(accountId, reason);
    }

    /**
     * Resends every unresolved ({@code PENDING} or {@code UNKNOWN}) journal entry with its current
     * operation, in key order, each in a fresh send run (FR-022, FR-026). {@code is_retry} follows the
     * durable attempt counter (FR-021): an entry interrupted before its first attempt was counted is sent
     * with {@code is_retry = false}, any other with {@code true}.
     */
    public List<TransactionResult> recoverPending() {
        List<TransactionResult> results = new ArrayList<>();
        for (JournalEntry entry : journal.unresolved()) {
            ClientLog.note("op=" + entry.operation() + " account=" + entry.accountId() + " key="
                    + entry.sequenceKey() + " recovering state=" + entry.state() + " attempts=" + entry.attempts());
            results.add(sendExclusive(entry.sequenceKey()));
        }
        return results;
    }

    /** Sends {@code key} unless another thread of this client is sending it right now (research R12). */
    private TransactionResult sendExclusive(long key) {
        if (!active.add(key)) {
            JournalEntry entry = journal.find(key).orElseThrow();
            return TransactionResult.refused(entry.accountId(), "key " + key + " is being sent right now");
        }
        try {
            return send(key);
        } finally {
            active.remove(key);
        }
    }

    /**
     * One send run for a journal entry, with fresh attempt and time limits (FR-022). The entry is read
     * again from the journal, so the run always sends its current operation (FR-027); an entry that is
     * already resolved is reported from the journal without sending.
     */
    private TransactionResult send(long key) {
        JournalEntry entry = journal.find(key).orElseThrow();
        if (entry.state() == JournalState.RESOLVED) {
            return new TransactionResult(resultFor(entry.operation(), entry.outcome()), entry.accountId(), key,
                    entry.outcome(), true, entry.balanceAfter(), 0, "already resolved");
        }
        RetryRun run = new RetryRun(policy, clock);
        while (run.mayAttempt()) {
            boolean isRetry = journal.countAttempt(key) > 0;
            AttemptResult result = entry.operation() == Operation.DEBIT
                    ? http.debit(entry.accountId(), key, entry.amount(), isRetry, run.attemptTimeout())
                    : http.cancel(entry.accountId(), key, isRetry, run.attemptTimeout());
            run.attempted();
            ClientLog.attempt(entry.operation(), entry.accountId(), key, run.attempts(), isRetry, result);
            if (result instanceof AttemptResult.Final done) {
                hooks.at(ClientHooks.Point.AFTER_RESPONSE_BEFORE_RECORD, entry);
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
        return new TransactionResult(resultFor(entry.operation(), outcome), entry.accountId(), entry.sequenceKey(),
                outcome, answer == null ? null : answer.duplicate(), balanceAfter, attempts, message);
    }

    /** Client result of a final answer (data-model.md, client-side result table). */
    private static Result resultFor(Operation operation, Outcome outcome) {
        if (operation == Operation.DEBIT) {
            if (outcome == Outcome.EXECUTED) {
                return Result.SUCCEEDED;
            }
            if (outcome == Outcome.CANCELLED || outcome == Outcome.STALE_KEY) {
                return Result.NOT_EXECUTED;
            }
            return Result.REJECTED;
        }
        if (outcome == Outcome.CANCELLED || outcome == Outcome.ALREADY_REJECTED || outcome == Outcome.STALE_KEY) {
            return Result.CANCELLED;
        }
        if (outcome == Outcome.ALREADY_EXECUTED) {
            return Result.SUCCEEDED; // the cancel came too late: the money was debited
        }
        return Result.REJECTED;
    }
}
