package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Package dependency rules of plan.md (T035, verification test; NFR-005). Scans the source files'
 * import lines, so it needs no extra library.
 */
@Tag("unit")
class ArchitectureTest {

    private static final Path SRC = Path.of("src", "main", "java", "ds", "banking");

    private static List<String> violations(Path folder, String... forbiddenPrefixes) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    for (String prefix : forbiddenPrefixes) {
                        if (trimmed.startsWith("import " + prefix) || trimmed.startsWith("import static " + prefix)) {
                            found.add(SRC.relativize(file) + ": " + trimmed);
                        }
                    }
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("NFR-005: decision rules and storage never depend on HTTP or fault-injection code")
    void decisionAndStoreIndependentOfHttpAndFaults() throws IOException {
        List<String> found = new ArrayList<>();
        found.addAll(violations(SRC.resolve("server").resolve("decision"),
                "ds.banking.server.http", "ds.banking.server.fault"));
        found.addAll(violations(SRC.resolve("server").resolve("store"),
                "ds.banking.server.http", "ds.banking.server.fault", "ds.banking.server.decision"));

        assertEquals(List.of(), found);
    }

    @Test
    @DisplayName("NFR-005: the client shares only the protocol with the server, never server code")
    void clientIndependentOfServer() throws IOException {
        assertEquals(List.of(), violations(SRC.resolve("client"), "ds.banking.server"));
    }
}
