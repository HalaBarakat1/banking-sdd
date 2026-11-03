package ds.banking.client;

import java.time.Duration;

/** Waits between attempts; injected so tests do not really sleep (FR-022, research R7). */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper system() {
        return duration -> Thread.sleep(duration);
    }
}
