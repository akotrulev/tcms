package com.tcms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 017 D-j, asserted rather than assumed: <strong>{@code staffing} reads {@code time}'s tables and
 * never writes them.</strong>
 *
 * <p>{@code TimeWritesNoIssueFactTest} one file over is the same rule one module out, and this is
 * the <em>fifth</em> instance of the pattern in this codebase — {@code work.BoardFacts} reads
 * {@code tracker}, {@code execution.CaseFacts} reads {@code repository},
 * {@code tracker.release.ReleaseFacts} reads {@code execution}, {@code time.IssueFacts} reads
 * {@code tracker}.
 *
 * <p><strong>ArchUnit cannot see this and neither can Modulith.</strong> The crossing is SQL, and
 * SQL is not a Java dependency: {@code staffing}'s {@code allowedDependencies} names the five
 * foundation modules and {@code time} is not one, so the Java half is already enforced and the
 * table half is invisible to every structural rule in this package. That is why this reads the
 * module's own <strong>source text</strong> — {@code ConfigConventionTest} is the precedent, and it
 * is the same lesson: every other check observes what the compiler produced, and the defect is in
 * what somebody wrote.
 *
 * <h2>Comments are stripped first, and that is load-bearing rather than tidy</h2>
 *
 * <p>{@code staffing/package-info.java} states this rule in prose, so it names the tables
 * repeatedly. A corpus that counted prose would report the file arguing for the rule as the file
 * breaking it — §152's trap, found once inside the check written to end it.
 *
 * <p>No Spring context and no database: this is a question about text, and a structural rule that
 * only runs when Docker is up is a rule that gets skipped exactly when somebody is in a hurry.
 */
@DisplayName("staffing never writes time's tables, and names them in exactly one file (017 D-j)")
class StaffingReadsNoTimeEntryTest {

    private static final Path STAFFING_SOURCE =
            Path.of("src", "main", "java", "com", "tcms", "staffing");

    /** The one file the crossing is allowed to live in. */
    private static final String THE_CROSSING = "TimeFacts.java";

    /** Both of {@code time}'s tables, because utilisation joins them. */
    private static final List<String> TIME_TABLES = List.of("time_entry", "timesheet_period");

    private static final Pattern COMMENT =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final List<String> WRITE_VERBS =
            List.of("INSERT INTO ", "UPDATE ", "DELETE FROM ", "MERGE INTO ", "TRUNCATE ");

    @Test
    @DisplayName("the corpus is the whole module and it is not empty")
    void theCorpusIsTheWholeModule() {
        // The vacuity half. An empty walk and a clean module look identical from the two rules
        // below, which is the shape a source-text check fails silently in when the layout moves.
        assertThat(sources())
                .as("no source was found under " + STAFFING_SOURCE + " — the working directory or "
                        + "the module layout has moved, and both rules below would pass over "
                        + "nothing")
                .hasSizeGreaterThan(20);
        assertThat(sources().stream().map(path -> path.getFileName().toString()))
                .contains(THE_CROSSING, "package-info.java");
    }

    @Test
    @DisplayName("no class in staffing writes a time-owned table")
    void noClassInStaffingWritesTimesTables() {
        List<String> offenders = sources().stream()
                .filter(path -> writesATimeTable(withoutComments(read(path))))
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(offenders)
                .as("time owns its own tables and staffing reads them (017 D-j). A downstream "
                        + "module writing a timesheet or an entry would make two modules the "
                        + "authority on the same hours, and the disagreement has no symptom — no "
                        + "error, no empty screen, just a utilisation figure that stops matching "
                        + "the timesheet it was computed from.")
                .isEmpty();
    }

    @Test
    @DisplayName("and the read crossing lives in exactly one file")
    void onlyTimeFactsNamesTimesTables() {
        List<String> namers = sources().stream()
                .filter(path -> {
                    String text = withoutComments(read(path));
                    return TIME_TABLES.stream().anyMatch(text::contains);
                })
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertThat(namers)
                .as("""
                        The whole crossing lives in one class so a reader can see it by opening one \
                        file — work.BoardFacts' rule, applied a fifth time. A second file naming \
                        one of those tables is not a bug on its own; it is how the boundary stops \
                        being findable, which is the property the pattern exists for. Move the \
                        query into TimeFacts.""")
                .containsExactly(THE_CROSSING);
    }

    /**
     * A write verb followed, somewhere in the same statement, by one of {@code time}'s tables.
     *
     * <p>Deliberately looser than a full parse and deliberately tighter than "the file contains
     * INSERT": {@code TimeWritesNoIssueFactTest} can pair its verb with its one table name because
     * there is one, and here there are two, so the pairing is done over the text between the verb
     * and the next semicolon or the end of the statement block. Over-matching in this direction
     * costs a false finding somebody has to read, which is the cheap way for a rule of this shape to
     * be wrong.
     */
    private static boolean writesATimeTable(String source) {
        String upper = source.toUpperCase(Locale.ROOT);
        for (String verb : WRITE_VERBS) {
            int at = upper.indexOf(verb);
            while (at >= 0) {
                String statement = upper.substring(at, Math.min(upper.length(), at + 200));
                if (TIME_TABLES.stream()
                        .anyMatch(table -> statement.contains(table.toUpperCase(Locale.ROOT)))) {
                    return true;
                }
                at = upper.indexOf(verb, at + 1);
            }
        }
        return false;
    }

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(STAFFING_SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(STAFFING_SOURCE + " is this rule's corpus", unreadable);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("unreadable source " + path, unreadable);
        }
    }

    private static String withoutComments(String source) {
        return COMMENT.matcher(source).replaceAll(" ");
    }
}
