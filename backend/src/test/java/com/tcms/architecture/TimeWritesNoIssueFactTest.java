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
 * 009 review R10, asserted rather than assumed: <strong>{@code time} reads {@code issue_fact} and
 * never writes it.</strong>
 *
 * <p>That table has exactly one writer, {@code tracker.IssueFactProjection}, fed by an outbox event.
 * A second writer would end the tracker's authority over issue state, and the two would then
 * disagree <em>silently</em> — no error, no empty screen, just two numbers that stop matching.
 *
 * <p>Reads the module's own <strong>source text</strong>, because the defect this guards against
 * compiles, passes every functional test and produces two quietly disagreeing figures. It is the
 * same class of failure as the {@code ${VAR:-x}} defect this repository shipped for a year, where
 * every check inspected a resolved value and the fault was in what somebody had written —
 * {@code ConfigConventionTest} is the precedent, and {@code everyMappedColumnHasAReader} the second.
 *
 * <h2>Comments are stripped first, and that is load-bearing rather than tidy</h2>
 *
 * <p>{@code time/package-info.java} states this rule in prose, so it names the table repeatedly. A
 * corpus that counted prose would report the file arguing for the rule as the file breaking it —
 * §152's trap, found once inside the check written to end it. What a comment says is not what the
 * program does, in either direction.
 *
 * <p>No Spring context and no database: this is a question about text, and a structural rule that
 * only runs when Docker is up is a rule that gets skipped exactly when somebody is in a hurry.
 */
@DisplayName("time never writes issue_fact, and names it in exactly one file (009 R10)")
class TimeWritesNoIssueFactTest {

    private static final Path TIME_SOURCE = Path.of("src", "main", "java", "com", "tcms", "time");

    /** The one file the crossing is allowed to live in. */
    private static final String THE_CROSSING = "IssueFacts.java";

    private static final String PROJECTION = "issue_fact";

    private static final Pattern COMMENT =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final List<String> WRITES = List.of(
            "INSERT INTO ISSUE_FACT", "UPDATE ISSUE_FACT", "DELETE FROM ISSUE_FACT",
            "MERGE INTO ISSUE_FACT", "TRUNCATE ISSUE_FACT");

    @Test
    @DisplayName("the corpus is the whole module and it is not empty")
    void theCorpusIsTheWholeModule() {
        // The vacuity half. An empty walk and a clean module look identical from the two rules
        // below, which is the shape a source-text check fails silently in when the layout moves.
        assertThat(sources())
                .as("no source was found under " + TIME_SOURCE + " — the working directory or the "
                        + "module layout has moved, and both rules below would pass over nothing")
                .hasSizeGreaterThan(15);
        assertThat(sources().stream().map(path -> path.getFileName().toString()))
                .contains(THE_CROSSING, "package-info.java");
    }

    @Test
    @DisplayName("no class in time writes the projection")
    void noClassInTimeWritesIssueFact() {
        List<String> offenders = sources().stream()
                .filter(path -> {
                    String text = withoutComments(read(path)).toUpperCase(Locale.ROOT);
                    return WRITES.stream().anyMatch(text::contains);
                })
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(offenders)
                .as("issue_fact has exactly one writer, tracker.IssueFactProjection (scope §3). A "
                        + "downstream module writing it is the failure 009 review R10 names — and "
                        + "it has no symptom, which is why this is a rule and not a review note.")
                .isEmpty();
    }

    @Test
    @DisplayName("and the read crossing lives in exactly one file")
    void onlyIssueFactsNamesTheProjection() {
        List<String> namers = sources().stream()
                .filter(path -> withoutComments(read(path)).contains(PROJECTION))
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertThat(namers)
                .as("""
                        The whole crossing lives in one class so a reader can see it by opening one \
                        file — work.BoardFacts' rule, applied here. A second file naming the table \
                        is not a bug on its own; it is how the boundary stops being findable, which \
                        is the property the pattern exists for. Move the query into IssueFacts.""")
                .containsExactly(THE_CROSSING);
    }

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(TIME_SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(TIME_SOURCE + " is this rule's corpus", unreadable);
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
