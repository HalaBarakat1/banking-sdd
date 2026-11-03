package ds.banking.server.store;

/** Result of seeding an account for tests and demonstrations (FR-031, contracts/http-api.md §4). */
public enum SeedResult {
    /** The account did not exist and was created. */
    CREATED,
    /** The account already exists with the same initial balance; nothing changed. */
    ALREADY_EXISTS,
    /** The account already exists with a different initial balance; nothing changed. */
    CONFLICT
}
