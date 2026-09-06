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
 * 020 D-g, asserted rather than assumed: <strong>{@code reporting} reads {@code tracker}'s
 * {@code issue_fact} and {@code release} through exactly one file, and writes neither.</strong>
 *
 * <p>The sixth instance of the pattern in this codebase, and the third test of this shape —
 * {@code work.BoardFacts} reads {@code tracker}, {@code execution.CaseFacts} reads
 * {@code repository}, {@code tracker.release.ReleaseFacts} reads {@code execution},
 * {@code time.IssueFacts} and {@code billing.IssueSubjectFacts} both read {@code tracker}, and
 * {@code StaffingReadsNoTimeEntryTest} and {@code BillingReadsAndNeverWritesTest} sit beside this
 * file asserting the same rule one module out.
 *
 * <h2>ArchUnit cannot see this and neither can Modulith</h2>
 *
 * <p>The crossing is SQL, and SQL is not a Java dependency: {@code reporting}'s
 * {@code allowedDependencies} names the five foundation modules and {@code tracker} is not one, so
 * the Java half is already enforced and the table half is invisible to every structural rule in this
 * package. That is why this reads the module's own <strong>source text</strong> —
 * {@code ConfigConventionTest} is the precedent, and the lesson is the same: every other check
 * observes what the compiler produced, and this defect would be in what somebody wrote.
 *
 * <h2>Comments are stripped first, and it is load-bearing rather than tidy</h2>
 *
 * <p>{@code reporting/analytics/package-info.java} states this rule in prose and therefore names
 * both tables repeatedly. A corpus that counted prose would report the file arguing for the rule as
 * the file breaking it — §152's trap, found once already inside a check written to end it.
 *
 * <h2>{@code release} is matched as a SQL table reference, not as a word</h2>
 *
 * <p>019 D-w's finding, and it bites harder here than it did there. {@code release} is a substring
 * of {@code release_id}, {@code release_version}, {@code release_sort_key} and
 * {@code release_assigned_at} — four columns this module legitimately reads, three of them on tables
 * it owns — and it is also an ordinary English word that appears in a refusal message
 * ({@code "No release …"}) and in a sufficiency sentence. So the match is
 * {@code FROM|JOIN|INTO|UPDATE|TABLE} followed by the bare identifier, which is what a table
 * reference actually looks like and what a second reader of the table would have to write.
 *
 * <p>No Spring context and no database: this is a question about text, and a structural rule that
 * only runs when Docker is up is a rule that gets skipped exactly when somebody is in a hurry.
 */
@DisplayName("reporting reads issue_fact and release in one file, and writes neither (020 D-g)")
class ReportingReadsAndNeverWritesTest {

    private static final Path REPORTING_SOURCE =
            Path.of("src", "main", "java", "com", "tcms", "reporting");

    /** The one file the crossing is allowed to live in. */
    private static final String THE_CROSSING = "DeliveryFacts.java";

    /** An unambiguous identifier — no column or English word contains it. */
    private static final String ISSUE_FACT = "issue_fact";

    /**
     * {@code release} as a table reference.
     *
     * <p>Not {@code \brelease\b}: that matches the word in a message and would report
     * {@code BurnUpService} for saying "release" in a sentence, which is the prose-as-seam trap this
     * class's header describes arriving through the regex instead of through a comment.
     */
    private static final Pattern RELEASE_TABLE = Pattern.compile(
            "\\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\\s+release\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final List<String> WRITE_VERBS =
            List.of("INSERT INTO ", "UPDATE ", "DELETE FROM ", "MERGE INTO ", "TRUNCATE ");

    private static final List<String> TRACKER_TABLES = List.of("ISSUE_FACT", "RELEASE");

    @Test
    @DisplayName("the corpus is the whole module and it is not empty")
    void theCorpusIsTheWholeModule() {
        // The vacuity half. An empty walk and a clean module look identical from the rules below,
        // which is the shape a source-text check fails silently in when the layout moves.
        assertThat(sources())
                .as("no source was found under " + REPORTING_SOURCE + " — the working directory or "
                        + "the module layout has moved, and every rule below would pass over "
                        + "nothing")
                .hasSizeGreaterThan(20);
        assertThat(sources().stream().map(path -> path.getFileName().toString()))
                .contains(THE_CROSSING, "package-info.java");
    }

    @Test
    @DisplayName("no class in reporting writes a tracker-owned table")
    void noClassInReportingWritesTrackersTables() {
        List<String> offenders = sources().stream()
                .filter(path -> writesATrackerTable(withoutComments(read(path))))
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(offenders)
                .as("""
                        issue_fact has ONE writer and it is tracker.IssueFactProjection — scope §3 \
                        names a second writer as the failure by name. The moment a downstream \
                        module writes it, tracker is no longer the authority on issue state and \
                        the two disagree SILENTLY: no error, no empty screen, just a burn-up whose \
                        scope line stops matching the release page's.""")
                .isEmpty();
    }

    @Test
    @DisplayName("and the read crossing lives in exactly one file")
    void onlyDeliveryFactsNamesTrackersTables() {
        List<String> namers = sources().stream()
                .filter(path -> {
                    String text = withoutComments(read(path));
                    return text.contains(ISSUE_FACT) || RELEASE_TABLE.matcher(text).find();
                })
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();

        assertThat(namers)
                .as("""
                        The whole crossing lives in one class so a reader can see it by opening one \
                        file — work.BoardFacts' rule, applied a sixth time. A second file naming \
                        one of those tables is not a bug on its own; it is how the boundary stops \
                        being findable, which is the property the pattern exists for. Move the \
                        query into DeliveryFacts.""")
                .containsExactly(THE_CROSSING);
    }

    /**
     * A write verb followed, within the same statement, by one of {@code tracker}'s two tables.
     *
     * <p>Deliberately looser than a full parse and deliberately tighter than "the file contains
     * INSERT", following {@code StaffingReadsNoTimeEntryTest}: the pairing is done over the text
     * between the verb and 200 characters on. Over-matching in this direction costs a false finding
     * somebody has to read, which is the cheap way for a rule of this shape to be wrong.
     *
     * <p>{@code UPDATE } is itself one of the verbs and {@code RELEASE} one of the tables, so
     * {@code UPDATE release} is caught by the verb scan without the table pattern above — which is
     * why this method matches the bare identifier and the read rule does not.
     */
    private static boolean writesATrackerTable(String source) {
        String upper = source.toUpperCase(Locale.ROOT);
        for (String verb : WRITE_VERBS) {
            int at = upper.indexOf(verb);
            while (at >= 0) {
                String statement = upper.substring(at + verb.length(),
                        Math.min(upper.length(), at + verb.length() + 200));
                if (TRACKER_TABLES.stream().anyMatch(statement::startsWith)) {
                    return true;
                }
                at = upper.indexOf(verb, at + 1);
            }
        }
        return false;
    }

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(REPORTING_SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(REPORTING_SOURCE + " is this rule's corpus", unreadable);
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
