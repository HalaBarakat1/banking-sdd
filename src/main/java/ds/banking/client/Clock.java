package ds.banking.client;

/** Monotonic time source, injected so tests can control deadlines (FR-022, research R7). */
@FunctionalInterface
public interface Clock {

    long nanoTime();

    static Clock system() {
        return System::nanoTime;
    }
}
