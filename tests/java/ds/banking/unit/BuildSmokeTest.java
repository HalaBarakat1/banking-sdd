package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.Main;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Smoke test for the build and the jar entry point (T004; SC-002, contracts/client-cli.md). */
@Tag("unit")
class BuildSmokeTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return Main.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("SC-002: no sub-command prints usage naming server and client, exit code 2")
    void noArgumentsPrintsUsage() {
        int code = run();

        assertEquals(2, code);
        String usage = err.toString(StandardCharsets.UTF_8);
        assertTrue(usage.contains("server"), usage);
        assertTrue(usage.contains("client"), usage);
    }

    @Test
    @DisplayName("SC-002: unknown sub-command is an invalid argument, exit code 2")
    void unknownSubCommandIsRejected() {
        int code = run("deploy");

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("deploy"));
    }
}
