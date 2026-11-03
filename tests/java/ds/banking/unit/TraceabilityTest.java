package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Traceability between the spec, the tests and the code (T056, verification test; constitution
 * Principles I and V). Reads the files as text, so it needs no extra library.
 */
@Tag("unit")
class TraceabilityTest {

    private static final Path SPEC = Path.of("specs", "001-effectively-once-debit", "spec.md");
    private static final Path TESTS = Path.of("tests", "java");
    private static final Path SOURCES = Path.of("src", "main", "java");

    private static final Pattern ID = Pattern.compile("\\b(?:FR|NFR|EC|SC)-\\d{3}\\b|\\bFS-[1-4][ab]?\\b");
    /** {@code @DisplayName("...")}, also when the text is split into several {@code "..." + "..."} parts. */
    private static final Pattern DISPLAY_NAME =
            Pattern.compile("@DisplayName\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)\\)");
    /** The Javadoc right before the file's top-level type. */
    private static final Pattern CLASS_COMMENT = Pattern.compile(
            "/\\*\\*(.*?)\\*/\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*public\\s+(?:final\\s+|abstract\\s+|sealed\\s+)*"
                    + "(?:class|interface|enum|record)\\s", Pattern.DOTALL);

    private static Set<String> ids(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = ID.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }

    @Test
    @DisplayName("Principle I / Principle V: every FR, NFR, EC, SC and FS ID of spec.md is cited by at least one test @DisplayName")
    void everySpecIdIsTested() throws IOException {
        Set<String> required = ids(Files.readString(SPEC));
        Set<String> cited = new TreeSet<>();
        for (Path file : javaFiles(TESTS)) {
            Matcher m = DISPLAY_NAME.matcher(Files.readString(file));
            while (m.find()) {
                cited.addAll(ids(m.group(1)));
            }
        }
        // FS-4 is covered by its two variants.
        if (cited.contains("FS-4a") && cited.contains("FS-4b")) {
            cited.add("FS-4");
        }

        Set<String> missing = new TreeSet<>(required);
        missing.removeAll(cited);

        assertTrue(required.size() > 50, "spec IDs found: " + required);
        assertEquals(Set.of(), missing, "spec IDs no test cites");
    }

    @Test
    @DisplayName("Principle I: every production class names the requirement IDs it implements in its class comment")
    void everyClassNamesItsIds() throws IOException {
        Set<String> withoutIds = new TreeSet<>();
        for (Path file : javaFiles(SOURCES)) {
            Matcher m = CLASS_COMMENT.matcher(Files.readString(file));
            if (!m.find() || ids(m.group(1)).isEmpty()) {
                withoutIds.add(SOURCES.relativize(file).toString());
            }
        }

        assertEquals(Set.of(), withoutIds, "classes whose comment names no requirement ID");
    }
}
