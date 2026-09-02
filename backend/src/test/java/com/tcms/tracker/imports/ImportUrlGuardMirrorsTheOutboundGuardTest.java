package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The instrument that keeps plan 014 D-c's second copy of a security control from being the drift
 * it looks like.
 *
 * <p>Same shape as {@code contractPairing.test.ts} over {@code contractBodies.ts}'s marked region,
 * and for the same reason: two files must hold the same text, no compiler can say so, and a
 * convention is what gets forgotten. No Spring context and no container — this reads two source
 * files.
 *
 * <p><strong>Both files are declared in {@code backend/build.gradle.kts}'s {@code inputs.files}.</strong>
 * Gradle tracks a {@code Test} task's inputs as its classpath, and a comment-only edit to either
 * guard changes no bytecode — so without those two lines {@code :test} stays {@code UP-TO-DATE}
 * and this rule is green by cache, which is the exact failure the block comment around that call
 * was written about.
 */
@DisplayName("The import guard is a copy of integration's, and the copy may not drift (014 D-c)")
class ImportUrlGuardMirrorsTheOutboundGuardTest {

    private static final Path OUTBOUND =
            Path.of("src/main/java/com/tcms/integration/OutboundUrlGuard.java");
    private static final Path IMPORTS =
            Path.of("src/main/java/com/tcms/tracker/imports/ImportUrlGuard.java");

    @Test
    @DisplayName("the two marked regions are byte-identical, or the copy has drifted")
    void theMirroredRegionsAreIdentical() throws IOException {
        String outbound = region(OUTBOUND, "BEGIN MIRRORED SOURCE", "END MIRRORED SOURCE");
        String imports = region(IMPORTS, "BEGIN MIRRORED FROM", "END MIRRORED");

        assertThat(imports)
                .as("""
                    tracker.imports may not import integration.OutboundUrlGuard (ArchUnit), so the \
                    rules are copied. A copy of a security control that is allowed to drift is \
                    worse than no copy: the private-range list gets a fix in one file and the \
                    other keeps dialling 169.254.169.254. Apply the edit to both.""")
                .isEqualTo(outbound);
    }

    @Test
    @DisplayName("fixture precondition: the region is the rules and not an empty pair of markers")
    void theRegionIsNotEmpty() throws IOException {
        // Without this, deleting the whole body between the markers in BOTH files leaves the rule
        // above comparing "" to "" and reporting success — a check that cannot fail is the thing
        // this file exists to stop being.
        assertThat(region(IMPORTS, "BEGIN MIRRORED FROM", "END MIRRORED"))
                .contains("isPrivate")
                .contains("isLinkLocalAddress")
                .hasLineCount(75);
    }

    /**
     * The lines strictly between the first line containing {@code begin} and the next containing
     * {@code end}.
     *
     * <p>Strictly between, so each file may carry its own explanation <em>above</em> its opening
     * marker without the two regions differing by it — which is what lets the copy say it is a
     * copy and the original say it is mirrored.
     */
    private static String region(Path file, String begin, String end) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int from = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (from < 0 && lines.get(index).contains(begin)) {
                from = index + 1;
            } else if (from >= 0 && lines.get(index).contains(end)) {
                return String.join("\n", lines.subList(from, index));
            }
        }
        throw new AssertionError(
                file + " has no region between '" + begin + "' and '" + end + "'. The markers are "
                        + "what pins the copy; removing one is removing the check.");
    }
}
