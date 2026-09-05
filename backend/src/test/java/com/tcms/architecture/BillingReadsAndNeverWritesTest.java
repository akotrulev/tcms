package com.tcms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 019 D-m, asserted rather than assumed: <strong>{@code billing} reads {@code time}'s tables and the
 * tracker's issue projection, and writes neither.</strong>
 *
 * <p>{@code StaffingReadsNoTimeEntryTest} and {@code TimeWritesNoIssueFactTest} are the two
 * precedents and are copied rather than re-argued. This is the <em>sixth</em> instance of the
 * pattern in this codebase — {@code work.BoardFacts} reads {@code tracker},
 * {@code execution.CaseFacts} reads {@code repository}, {@code tracker.release.ReleaseFacts} reads
 * {@code execution}, {@code time.IssueFacts} reads {@code tracker}, {@code staffing.TimeFacts} reads
 * {@code time} — and the first where one module makes the crossing twice.
 *
 * <p><strong>ArchUnit cannot see this and neither can Modulith.</strong> The crossing is SQL, and
 * SQL is not a Java dependency: {@code billing}'s {@code allowedDependencies} names four foundation
 * modules and {@code time} is not one, so the Java half is already enforced and the table half is
 * invisible to every structural rule in this package. That is why this reads the module's own
 * <strong>source text</strong> — {@code ConfigConventionTest} is the precedent, and it is the same
 * lesson: every other check observes what the compiler produced, and the defect is in what somebody
 * wrote.
 *
 * <h2>Four tables, and one of them is expected to be named by nothing at all</h2>
 *
 * <p>{@code issue_time_rollup} carries approved and pending hours <em>per issue</em>, and an invoice
 * needs the entries — who, when, how many, on which period — not their sum. 016 D-c named M8 as that
 * projection's first outside consumer; this module declines the inheritance, and {@link #OWNERS}
 * records the decision where a later reader will trip over it rather than in prose nothing checks.
 *
 * <p>{@code issue_fact} has a declared owner and <strong>no file yet</strong>, because 019 T3 built
 * no surface that needs an issue's key or title: the class arrives with the invoice entries table
 * that snapshots them (019 T4). The rule below is written as <em>at most</em> the owner, so it holds
 * at full strength on both sides of that: a second file naming the table fails today, and the file
 * that eventually does must be the one named here.
 *
 * <h2>Comments are stripped first, and that is load-bearing rather than tidy</h2>
 *
 * <p>{@code billing/package-info.java} states this rule in prose, so it names all four tables
 * repeatedly. A corpus that counted prose would report the file arguing for the rule as the file
 * breaking it — §152's trap, found once inside the check written to end it.
 *
 * <p>No Spring context and no database: this is a question about text, and a structural rule that
 * only runs when Docker is up is a rule that gets skipped exactly when somebody is in a hurry.
 */
@DisplayName("billing never writes time's or the tracker's tables, and names each in one file (019 D-m)")
class BillingReadsAndNeverWritesTest {

    private static final Path BILLING_SOURCE =
            Path.of("src", "main", "java", "com", "tcms", "billing");

    /**
     * Each foreign table this module is forbidden to write, and the one file allowed to name it.
     *
     * <p>An empty owner means <em>no file may name this at all</em>. {@code issue_fact}'s owner does
     * not exist yet and the header says why; naming it here is what makes the eventual file the one
     * this rule already expects rather than whichever one somebody puts the query in.
     */
    private static final Map<String, String> OWNERS = new LinkedHashMap<>(Map.of(
            "time_entry", "ApprovedTimeFacts.java",
            "timesheet_period", "ApprovedTimeFacts.java",
            "issue_fact", "IssueSubjectFacts.java",
            "issue_time_rollup", ""));

    /** The two tables the crossing demonstrably exists for today. */
    private static final List<String> ALREADY_CROSSED = List.of("time_entry", "timesheet_period");

    private static final Pattern COMMENT =
            Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    private static final List<String> WRITE_VERBS =
            List.of("INSERT INTO ", "UPDATE ", "DELETE FROM ", "MERGE INTO ", "TRUNCATE ");

    @Test
    @DisplayName("the corpus is the whole module and it is not empty")
    void theCorpusIsTheWholeModule() {
        // The vacuity half. An empty walk and a clean module look identical from the rules below,
        // which is the shape a source-text check fails silently in when the layout moves.
        assertThat(sources())
                .as("no source was found under " + BILLING_SOURCE + " — the working directory or "
                        + "the module layout has moved, and every rule below would pass over "
                        + "nothing")
                .hasSizeGreaterThan(20);
        assertThat(sources().stream().map(path -> path.getFileName().toString()))
                .contains("ApprovedTimeFacts.java", "package-info.java");
    }

    @Test
    @DisplayName("no class in billing writes a time-owned or tracker-owned table")
    void noClassInBillingWritesAForeignTable() {
        List<String> offenders = sources().stream()
                .filter(path -> writesAForeignTable(withoutComments(read(path))))
                .map(path -> path.getFileName().toString())
                .toList();

        assertThat(offenders)
                .as("""
                        time owns its entries and its periods, and tracker.IssueFactProjection is \
                        the issue projection's ONE writer, fed by an outbox event (019 D-m). A \
                        downstream module writing either would make two modules the authority on \
                        the same rows, and the disagreement has no symptom — no error, no empty \
                        screen, just an invoice that stops matching the timesheet it was computed \
                        from.""")
                .isEmpty();
    }

    @Test
    @DisplayName("and each read crossing lives in at most the one file that owns it")
    void eachForeignTableIsNamedByAtMostItsOwner() {
        Map<String, TreeSet<String>> namers = new LinkedHashMap<>();
        for (String table : OWNERS.keySet()) {
            namers.put(table, new TreeSet<>());
        }
        for (Path path : sources()) {
            String text = withoutComments(read(path));
            for (String table : OWNERS.keySet()) {
                if (text.contains(table)) {
                    namers.get(table).add(path.getFileName().toString());
                }
            }
        }

        for (Map.Entry<String, TreeSet<String>> found : namers.entrySet()) {
            String owner = OWNERS.get(found.getKey());
            assertThat(found.getValue())
                    .as("""
                            %s may be named by %s and by nothing else in com.tcms.billing.

                            The whole crossing lives in one class so a reader can see it by \
                            opening one file — work.BoardFacts' rule, applied a sixth time. A \
                            second file naming one of these tables is not a bug on its own; it is \
                            how the boundary stops being findable, which is the property the \
                            pattern exists for. Move the query into the owner.""",
                            found.getKey(),
                            owner.isEmpty()
                                    ? "NOTHING — an invoice needs the entries, not their sum "
                                            + "(019 D-m)"
                                    : owner)
                    .isSubsetOf(owner.isEmpty() ? List.of() : List.of(owner));
        }

        for (String table : ALREADY_CROSSED) {
            assertThat(namers.get(table))
                    .as("the crossing for %s has to exist, or the rule above is vacuous for it",
                            table)
                    .containsExactly(OWNERS.get(table));
        }
    }

    /**
     * A write verb followed, somewhere in the same statement, by one of the four tables.
     *
     * <p>Deliberately looser than a full parse and deliberately tighter than "the file contains
     * INSERT": the pairing is done over the text between the verb and the next two hundred
     * characters, which is {@code StaffingReadsNoTimeEntryTest}'s bound. Over-matching in this
     * direction costs a false finding somebody has to read, which is the cheap way for a rule of
     * this shape to be wrong.
     */
    private static boolean writesAForeignTable(String source) {
        String upper = source.toUpperCase(Locale.ROOT);
        for (String verb : WRITE_VERBS) {
            int at = upper.indexOf(verb);
            while (at >= 0) {
                String statement = upper.substring(at, Math.min(upper.length(), at + 200));
                if (OWNERS.keySet().stream()
                        .anyMatch(table -> statement.contains(table.toUpperCase(Locale.ROOT)))) {
                    return true;
                }
                at = upper.indexOf(verb, at + 1);
            }
        }
        return false;
    }

    private static List<Path> sources() {
        try (Stream<Path> walk = Files.walk(BILLING_SOURCE)) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(BILLING_SOURCE + " is this rule's corpus", unreadable);
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
