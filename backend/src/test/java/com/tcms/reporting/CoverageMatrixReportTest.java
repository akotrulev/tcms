package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The coverage matrix — §6e's fourth catalogue entry, and the first report whose spine is a
 * <em>document</em> rather than a run.
 *
 * <h2>What this class is guarding, and it is not the row count</h2>
 *
 * <p>Counting linked cases is the easy half and would pass against every defect that matters here.
 * What the fixture is built to catch, in the order the class asserts it:
 *
 * <ol>
 *   <li><strong>A coverage number that counts the wrong things.</strong> The denominator is
 *       <em>statements</em> — list items and already-linked blocks — and not blocks, not cases and
 *       not executions. {@link Statements} pins which blocks are candidates in both directions: a
 *       paragraph nobody linked is not an uncovered requirement, and a paragraph somebody
 *       <em>did</em> link is a row.</li>
 *   <li><strong>A relationship read as coverage that is not one.</strong> {@code DERIVED_FROM} and
 *       {@code REFERENCES} links exist in the fixture on a statement with no {@code VERIFIES} link
 *       at all, and it must read as <em>Not covered</em>. {@code LinkRelationship}'s header calls
 *       this out as the thing that <em>"turns a coverage number into a false claim"</em>, and this
 *       is where the sentence either holds or does not.</li>
 *   <li><strong>An optimistic roll-up.</strong> A statement with one green case and one red case is
 *       <em>Failing</em>. The fixture builds exactly that pair, because the natural implementation —
 *       "any pass means verified" — passes every other test in this class.</li>
 *   <li><strong>A stale result presented as current.</strong> A case that failed and was then fixed
 *       reads as its <em>latest</em> result. The fixture's two results are in the non-obvious order,
 *       so a report resolving to the oldest row rather than the newest reddens rather than
 *       coincidentally agreeing — the vacuous-fixture trap R6 paid for.</li>
 *   <li><strong>A clean report of nothing.</strong> An unknown page id and a page nobody has linked
 *       anything to both yield no coverage; {@link Absences} separates them.</li>
 *   <li><strong>A report of somebody else's document.</strong> A sibling company's page id must be
 *       refused, and the mechanism is RLS rather than a predicate anybody wrote.</li>
 * </ol>
 *
 * <p>Ids are prefixed {@code c5} — hex, which is not optional; {@code ExecutionFactRefreshTest}'s
 * header records the run a non-hex prefix cost — and slugs are {@code cvm-}. The prefix continues
 * the reporting lane's {@code c1}–{@code c4} and was checked free against every UUID literal in
 * {@code src/}.
 */
@DisplayName("Coverage matrix — a specification with its verification evidence inline")
class CoverageMatrixReportTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("c5000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("c5000000-0000-4000-8000-000000000002");
    private static final UUID ACME_PROJECT = UUID.fromString("c5001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_PROJECT = UUID.fromString("c5001000-0000-4000-8000-000000000002");

    private static final UUID SPACE = UUID.fromString("c5002000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SPACE = UUID.fromString("c5002000-0000-4000-8000-000000000009");

    private static final UUID SPEC = UUID.fromString("c5003000-0000-4000-8000-000000000001");
    /** A page with blocks and not one link — the "nobody has linked anything" arm. */
    private static final UUID UNLINKED_PAGE = UUID.fromString("c5003000-0000-4000-8000-000000000002");
    /** Prose only: no list item, no link. Coverage over it is not 0%, it is not applicable. */
    private static final UUID PROSE_PAGE = UUID.fromString("c5003000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_PAGE = UUID.fromString("c5003000-0000-4000-8000-000000000009");

    // The spine. Positions are deliberately not in id order, so a query ordering by anything but
    // `position` produces a document in the wrong order and reddens theSpineIsTheDocumentsOwnOrder.
    private static final UUID INTRO = UUID.fromString("c5004000-0000-4000-8000-000000000001");
    private static final UUID H_REFUNDS = UUID.fromString("c5004000-0000-4000-8000-000000000002");
    private static final UUID S_FULL_REFUND = UUID.fromString("c5004000-0000-4000-8000-000000000003");
    private static final UUID S_PARTIAL = UUID.fromString("c5004000-0000-4000-8000-000000000004");
    private static final UUID S_EXPIRED = UUID.fromString("c5004000-0000-4000-8000-000000000005");
    private static final UUID H_PAYOUTS = UUID.fromString("c5004000-0000-4000-8000-000000000006");
    private static final UUID S_SCHEDULE = UUID.fromString("c5004000-0000-4000-8000-000000000007");
    /** A heading with nothing under it — kept as a section that says so, never dropped. */
    private static final UUID H_EMPTY = UUID.fromString("c5004000-0000-4000-8000-000000000008");
    /** A paragraph somebody linked: context that became a statement by being claimed. */
    private static final UUID P_LINKED = UUID.fromString("c5004000-0000-4000-8000-00000000000a");
    /** A statement before the first heading — the preamble chapter's only occupant. */
    private static final UUID S_PREAMBLE = UUID.fromString("c5004000-0000-4000-8000-00000000000b");
    /** The unlinked page's one statement, and the prose page's one non-statement. */
    private static final UUID S_UNLINKED = UUID.fromString("c5004000-0000-4000-8000-00000000000c");
    private static final UUID P_PROSE = UUID.fromString("c5004000-0000-4000-8000-00000000000d");
    private static final UUID UMBRA_BLOCK = UUID.fromString("c5004000-0000-4000-8000-00000000000f");

    private static final UUID GREEN = UUID.fromString("c5005000-0000-4000-8000-000000000001");
    private static final UUID RED = UUID.fromString("c5005000-0000-4000-8000-000000000002");
    /** Linked, never executed. "Not run" and "not covered" are different problems. */
    private static final UUID NEVER_RUN = UUID.fromString("c5005000-0000-4000-8000-000000000003");
    /** Failed first, passed later. The only proof the resolution is LATEST and not "any". */
    private static final UUID FIXED = UUID.fromString("c5005000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_CASE = UUID.fromString("c5005000-0000-4000-8000-000000000009");

    private static final UUID SUITE = UUID.fromString("c5006000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("c5006000-0000-4000-8000-000000000009");
    private static final UUID RUN = UUID.fromString("c5007000-0000-4000-8000-000000000001");
    private static final UUID LATER_RUN = UUID.fromString("c5007000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_RUN = UUID.fromString("c5007000-0000-4000-8000-000000000009");

    private static final Instant BASE = Instant.parse("2026-04-06T09:00:00Z");

    @Autowired
    private CoverageMatrixReport report;

    @Autowired
    private ExecutionFactRefreshService refresh;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        company(ACME, "cvm-acme", "Acme", now);
        company(UMBRA, "cvm-umbra", "Umbra", now);

        inCompany(ACME, () -> seedProject(ACME_PROJECT, ACME, "CVAC", "Acme Product", now));
        inCompany(UMBRA, () -> seedProject(UMBRA_PROJECT, UMBRA, "CVUM", "Umbra Product", now));

        clearEverything();

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, ACME_PROJECT);
            suite(acme, SUITE, ACME, ACME_PROJECT, "TS-1", 1, "Refunds");

            testCase(acme, GREEN, ACME, ACME_PROJECT, "TC-1", 1, "Full refund is credited");
            testCase(acme, RED, ACME, ACME_PROJECT, "TC-2", 2, "Partial refund rounds down");
            testCase(acme, NEVER_RUN, ACME, ACME_PROJECT, "TC-3", 3, "Expired card is rejected");
            testCase(acme, FIXED, ACME, ACME_PROJECT, "TC-4", 4, "Payout schedule is honoured");

            space(acme, SPACE, ACME, ACME_PROJECT, "Specifications");

            page(acme, SPEC, ACME, SPACE, ACME_PROJECT, "DOC-1", 1, "payments", "Payments",
                    "PUBLISHED", 4);
            page(acme, UNLINKED_PAGE, ACME, SPACE, ACME_PROJECT, "DOC-2", 2, "shipping", "Shipping",
                    "DRAFT", 1);
            page(acme, PROSE_PAGE, ACME, SPACE, ACME_PROJECT, "DOC-3", 3, "charter", "Charter",
                    "DRAFT", 1);

            // The spine, written in the order a reader sees it. Positions ascend; the constants
            // above deliberately do not, so nothing can pass by ordering on the id.
            block(acme, S_PREAMBLE, ACME, SPEC, 0, "list", "Every amount is in minor units.", 1);
            block(acme, INTRO, ACME, SPEC, 1, "paragraph",
                    "This document specifies the payments domain.", null);
            block(acme, H_REFUNDS, ACME, SPEC, 2, "heading", "Refunds", 1);
            block(acme, S_FULL_REFUND, ACME, SPEC, 3, "list",
                    "A full refund credits the original payment method.", 1);
            block(acme, S_PARTIAL, ACME, SPEC, 4, "list",
                    "A partial refund rounds down to the nearest minor unit.", 1);
            block(acme, S_EXPIRED, ACME, SPEC, 5, "list",
                    "A refund to an expired card is rejected.", 1);
            block(acme, P_LINKED, ACME, SPEC, 6, "paragraph",
                    "Refunds settle within three working days.", null);
            block(acme, H_PAYOUTS, ACME, SPEC, 7, "heading", "Payouts", 2);
            block(acme, S_SCHEDULE, ACME, SPEC, 8, "list",
                    "Payouts run on the schedule the merchant chose.", 1);
            block(acme, H_EMPTY, ACME, SPEC, 9, "heading", "Disputes", 2);

            block(acme, S_UNLINKED, ACME, UNLINKED_PAGE, 0, "list",
                    "Parcels are tracked end to end.", 1);
            block(acme, P_PROSE, ACME, PROSE_PAGE, 0, "paragraph",
                    "This charter has no verifiable statements.", null);

            // Coverage. S_EXPIRED gets only the two relationships that are NOT coverage.
            link(acme, ACME, S_FULL_REFUND, GREEN, "VERIFIES");
            link(acme, ACME, S_PARTIAL, GREEN, "VERIFIES");
            link(acme, ACME, S_PARTIAL, RED, "VERIFIES");
            link(acme, ACME, S_EXPIRED, NEVER_RUN, "REFERENCES");
            link(acme, ACME, S_EXPIRED, RED, "DERIVED_FROM");
            link(acme, ACME, S_SCHEDULE, FIXED, "VERIFIES");
            link(acme, ACME, P_LINKED, NEVER_RUN, "VERIFIES");
            link(acme, ACME, S_PREAMBLE, GREEN, "VERIFIES");
            // The same claim recorded twice — BlockLinkService answers the second 200 and writes
            // nothing, but the table has no unique index behind that promise, so the report must
            // not count one case twice.
            link(acme, ACME, S_FULL_REFUND, GREEN, "VERIFIES");

            run(acme, RUN, ACME, ACME_PROJECT, "TR-1", 1, "First pass");
            run(acme, LATER_RUN, ACME, ACME_PROJECT, "TR-2", 2, "Re-test after the fix");

            execution(acme, ACME, ACME_PROJECT, RUN, GREEN, "Passed", BASE);
            execution(acme, ACME, ACME_PROJECT, RUN, RED, "Failed", BASE.plusSeconds(60));
            // Failed, then fixed. The later run holds the PASS, so a report resolving to the
            // earliest row reads this as failing and reddens theResultIsTheLatestOne.
            execution(acme, ACME, ACME_PROJECT, RUN, FIXED, "Failed", BASE.plusSeconds(120));
            execution(acme, ACME, ACME_PROJECT, LATER_RUN, FIXED, "Passed", BASE.plusSeconds(3600));
        }

        try (Connection umbra = appConnection(UMBRA)) {
            seedProjectDefaults(umbra, UMBRA, UMBRA_PROJECT);
            suite(umbra, UMBRA_SUITE, UMBRA, UMBRA_PROJECT, "TS-1", 1, "Root");
            testCase(umbra, UMBRA_CASE, UMBRA, UMBRA_PROJECT, "TC-1", 1, "Umbra's own case");
            space(umbra, UMBRA_SPACE, UMBRA, UMBRA_PROJECT, "Specifications");
            page(umbra, UMBRA_PAGE, UMBRA, UMBRA_SPACE, UMBRA_PROJECT, "DOC-1", 1, "spec",
                    "Umbra's specification", "PUBLISHED", 1);
            block(umbra, UMBRA_BLOCK, UMBRA, UMBRA_PAGE, 0, "list", "Umbra's own statement.", 1);
            link(umbra, UMBRA, UMBRA_BLOCK, UMBRA_CASE, "VERIFIES");
            run(umbra, UMBRA_RUN, UMBRA, UMBRA_PROJECT, "TR-1", 1, "Umbra's run");
            execution(umbra, UMBRA, UMBRA_PROJECT, UMBRA_RUN, UMBRA_CASE, "Passed", BASE);
        }

        refreshFor(ACME);
        refreshFor(UMBRA);
    }

    // =====================================================================

    @Nested
    @DisplayName("Which blocks are statements — the denominator")
    class Statements {

        @Test
        @DisplayName("a list item is a statement and an unlinked paragraph is not")
        void listItemsAreStatementsAndProseIsNot() {
            // THE decision of this slice. Five list items plus one linked paragraph is six; the
            // intro paragraph and the three headings are not statements. Counting every block
            // would print eleven and mark the document's own prose as an untested requirement —
            // DocBlockView's objection to a zeroed inline marker, in a document sent to an auditor.
            assertThat(entry(model(), "Statements")).isEqualTo("6");
        }

        @Test
        @DisplayName("a paragraph somebody linked IS a statement, because they said so")
        void aLinkedParagraphBecomesAStatement() {
            // The second arm of the rule. Hiding a row a reader created by linking it would make
            // the report contradict the page it is about.
            assertThat(rows(model(), "· Payouts"))
                    .as("the linked paragraph is under Refunds, not here")
                    .noneSatisfy(row -> assertThat(row.getFirst()).contains("three working days"));

            assertThat(rows(model(), "Refunds")).anySatisfy(row -> {
                assertThat(row.getFirst()).contains("three working days");
                assertThat(row.get(1)).contains("TC-3");
            });
        }

        @Test
        @DisplayName("a page of prose has nothing to cover, which is not the same as 0%")
        void aProsePageSaysItHasNoStatements() {
            ReportModel model = compute(PROSE_PAGE);

            assertThat(entry(model, "Statements")).isEqualTo("0");
            assertThat(entry(model, "Coverage"))
                    .as("0 of 0 is an em dash — 0.0% would be a claim about a document making none")
                    .isEqualTo("—");
            assertThat(section(model, "Coverage").narrative()).contains("nothing to cover");
            assertThat(section(model, "Coverage").table()).isNull();
        }
    }

    @Nested
    @DisplayName("What counts as coverage")
    class Coverage {

        @Test
        @DisplayName("only VERIFIES is coverage — REFERENCES and DERIVED_FROM are not")
        void onlyVerifiesCounts() {
            // S_EXPIRED carries two links and neither is a claim of proof. Reading the column
            // instead of the relationship is a one-word change that inflates every coverage number
            // in the product, and it looks right in the fixture that has only VERIFIES rows.
            assertThat(rows(model(), "Refunds")).anySatisfy(row -> {
                assertThat(row.getFirst()).contains("expired card");
                assertThat(row.get(1)).isEqualTo("—");
                assertThat(row.get(2)).isEqualTo("Not covered");
            });
        }

        @Test
        @DisplayName("the ladder is pessimistic: one red case makes the statement failing")
        void oneFailingCaseMakesTheStatementFailing() {
            // S_PARTIAL is verified by a green case AND a red one. "Any pass means verified" is the
            // natural implementation and passes every other test here — §46's rule is that a
            // roll-up must not take the direction that makes the report look better than the truth.
            assertThat(coverage(model())).containsExactly(
                    List.of("Verified", "3", "50.0%"),
                    List.of("Failing", "1", "16.7%"),
                    List.of("Not run", "1", "16.7%"),
                    List.of("Not covered", "1", "16.7%"));
        }

        @Test
        @DisplayName("covered-but-unexecuted is its own state, not a failure and not a gap")
        void notRunIsItsOwnState() {
            // P_LINKED's only case has never been executed. Folding it into "not covered" would
            // tell a reader to go and write a test that already exists; folding it into "failing"
            // would report a defect nobody has found. Different problems, different owners.
            assertThat(rows(model(), "Refunds")).anySatisfy(row -> {
                assertThat(row.getFirst()).contains("three working days");
                assertThat(row.get(2)).isEqualTo("Not run");
                assertThat(row.get(3)).isEqualTo("—");
            });
        }

        @Test
        @DisplayName("the same claim recorded twice counts once")
        void duplicateLinksCountOnce() {
            // Two identical doc_link rows for (S_FULL_REFUND, GREEN). doc_link has no unique index,
            // and a second row would both duplicate the evidence row and — worse — leave the
            // statement counts unchanged while the table grew, so the two would silently disagree.
            assertThat(rows(model(), "Refunds"))
                    .filteredOn(row -> row.getFirst().contains("original payment method"))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("Which result — the LATEST arm, spelled out")
    class Results {

        @Test
        @DisplayName("a case that failed and was fixed reads as passing")
        void theResultIsTheLatestOne() {
            // FIXED failed at +120s and passed at +3600s. Resolving to the earliest row instead —
            // one word in the ORDER BY — makes this statement Failing and the summary read
            // 2 verified / 2 failing. That is the mutation R6 found its fixture vacuous under, and
            // it is the reason the two results are in this order rather than mirrored.
            assertThat(rows(model(), "· Payouts")).containsExactly(
                    List.of("Payouts run on the schedule the merchant chose.",
                            "TC-4 — Payout schedule is honoured", "PASSED",
                            "2026-04-06 10:00"));
        }

        @Test
        @DisplayName("the policy is named in the metadata rather than assumed")
        void thePolicyIsStated() {
            // Slice-plan decision D: a report that quietly means LATEST has a default nobody chose.
            assertThat(entry(model(), "Aggregation policy")).contains("LATEST");
        }

        @Test
        @DisplayName("the document's version and status are recorded, per §6i's determinism note")
        void theDocumentVersionIsRecorded() {
            assertThat(entry(model(), "Document version")).isEqualTo("v4");
            assertThat(entry(model(), "Document status")).isEqualTo("PUBLISHED");
            assertThat(entry(model(), "Document")).isEqualTo("DOC-1 — Payments");
        }
    }

    @Nested
    @DisplayName("The spine is the document")
    class Spine {

        @Test
        @DisplayName("sections come out in the document's own order, headings included")
        void theSpineIsTheDocumentsOwnOrder() {
            // Coverage first, then the page top to bottom. Ordering by anything but `position`
            // reorders the specification, which is the one property this report has over a table.
            assertThat(model().sections().stream().map(ReportModel.Section::heading).toList())
                    .containsExactly(
                            "Coverage",
                            CoverageMatrixReport.PREAMBLE,
                            "Refunds",
                            "· Payouts",
                            "· Disputes");
        }

        @Test
        @DisplayName("a statement before the first heading gets its own chapter, not the next one")
        void statementsBeforeAnyHeadingArePreamble() {
            // Attaching them to the heading BELOW would file a requirement under a section it is
            // not in — which a reader checking the report against the page would read as the
            // report being wrong about the document rather than about the tests.
            assertThat(rows(model(), CoverageMatrixReport.PREAMBLE)).containsExactly(
                    List.of("Every amount is in minor units.", "TC-1 — Full refund is credited",
                            "PASSED", "2026-04-06 09:00"));
        }

        @Test
        @DisplayName("a heading with no statements is kept and says so")
        void anEmptyHeadingIsKept() {
            // Dropping it would make the report stop being the document.
            assertThat(section(model(), "· Disputes").table()).isNull();
            assertThat(section(model(), "· Disputes").narrative()).contains("No statements");
        }
    }

    @Nested
    @DisplayName("The three ways of having nothing to report, and none is the same")
    class Absences {

        @Test
        @DisplayName("a page nobody has linked to renders, and every statement reads as a gap")
        void anUnlinkedPageIsAllGaps() {
            ReportModel model = compute(UNLINKED_PAGE);

            assertThat(entry(model, "Statements")).isEqualTo("1");
            assertThat(entry(model, "Coverage")).isEqualTo("0.0%");
            assertThat(coverage(model))
                    .anySatisfy(row -> assertThat(row).containsExactly("Not covered", "1", "100.0%"));
        }

        @Test
        @DisplayName("an unknown page is REFUSED, not reported as a wholly untested specification")
        void anUnknownPageIsRefused() {
            // Without DocumentationSource.findPage this is a clean report of nothing — and here it
            // would be a clean report claiming a specification is entirely unverified, which is the
            // most alarming shape this failure mode has taken in the product.
            UUID nothing = UUID.fromString("c5009000-0000-4000-8000-00000000000f");

            assertThatThrownBy(() -> compute(nothing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no documentation page");
        }

        @Test
        @DisplayName("a soft-deleted page is refused, because a report about it is about nothing")
        void aDeletedPageIsRefused() throws SQLException {
            try (Connection acme = appConnection(ACME);
                    PreparedStatement statement = acme.prepareStatement(
                            "UPDATE doc_page SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
                statement.setString(1, SPEC.toString());
                statement.executeUpdate();
            }

            assertThatThrownBy(() -> compute(SPEC))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("another company's page is refused, and RLS is the mechanism")
        void aSiblingCompanysPageIsRefused() {
            // Asked in Acme's context for Umbra's page id. Nothing in the query says "company".
            assertThatThrownBy(() -> compute(UMBRA_PAGE))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(entry(compute(UMBRA, UMBRA_PAGE), "Statements"))
                    .as("and it is a real document to its own company")
                    .isEqualTo("1");
        }

        @Test
        @DisplayName("a request with no docPageId is refused before anything is queried")
        void aMissingParameterIsRefused() {
            assertThatThrownBy(() -> readIn(ACME,
                    () -> report.compute(ACME_PROJECT, ReportParameters.none())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("docPageId");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private ReportModel model() {
        return compute(SPEC);
    }

    private ReportModel compute(UUID pageId) {
        return compute(ACME, pageId);
    }

    private ReportModel compute(UUID companyId, UUID pageId) {
        UUID projectId = companyId.equals(ACME) ? ACME_PROJECT : UMBRA_PROJECT;
        return readIn(companyId, () -> report.compute(projectId,
                ReportParameters.parse("{\"docPageId\":\"" + pageId + "\"}")));
    }

    private String entry(ReportModel model, String label) {
        return model.metadata().stream()
                .filter(candidate -> candidate.label().equals(label))
                .map(ReportModel.Entry::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No metadata entry '" + label + "'"));
    }

    private ReportModel.Section section(ReportModel model, String heading) {
        return model.sections().stream()
                .filter(candidate -> candidate.heading().equals(heading))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No section '" + heading + "'"));
    }

    private List<List<String>> rows(ReportModel model, String heading) {
        ReportModel.Table table = section(model, heading).table();
        return table == null ? List.of() : table.rows();
    }

    private List<List<String>> coverage(ReportModel model) {
        return rows(model, "Coverage");
    }

    private void refreshFor(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            refresh.refresh();
        }
    }

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    /** {@code RunSummaryReportTest}'s note: two overloads would be ambiguous at every call site. */
    private <T> T readIn(UUID companyId, Supplier<T> work) {
        try (var ignored = CompanyContext.open(companyId)) {
            return work.get();
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void space(Connection connection, UUID id, UUID companyId, UUID projectId, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_space (id, company_id, project_id, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, name);
            statement.executeUpdate();
        }
    }

    private void page(Connection connection, UUID id, UUID companyId, UUID spaceId, UUID projectId,
            String key, long keySeq, String slug, String title, String status, int versionNo)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_page
                    (id, company_id, space_id, project_id, key, key_seq, path, slug, title,
                     status, current_version_no)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, spaceId.toString());
            statement.setString(4, projectId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "p" + keySeq);
            statement.setString(8, slug);
            statement.setString(9, title);
            statement.setString(10, status);
            statement.setInt(11, versionNo);
            statement.executeUpdate();
        }
    }

    /**
     * One block. {@code content} is the JSONB {@code BlockContent} shape — {@code text} always,
     * {@code level} on headings and list items.
     */
    private void block(Connection connection, UUID id, UUID companyId, UUID pageId, int position,
            String blockType, String text, Integer level) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_block (id, company_id, page_id, position, block_type, content)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS jsonb))
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, pageId.toString());
            statement.setInt(4, position);
            statement.setString(5, blockType);
            statement.setString(6, level == null
                    ? "{\"text\": " + json(text) + "}"
                    : "{\"text\": " + json(text) + ", \"level\": " + level + "}");
            statement.executeUpdate();
        }
    }

    private void link(Connection connection, UUID companyId, UUID blockId, UUID caseId,
            String relationship) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO doc_link
                    (id, company_id, source_type, source_id, target_kind, target_block_id,
                     relationship)
                VALUES (gen_random_uuid(), CAST(? AS uuid), 'TEST_CASE', CAST(? AS uuid),
                        'DOC_BLOCK', CAST(? AS uuid), ?)
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, blockId.toString());
            statement.setString(4, relationship);
            statement.executeUpdate();
        }
    }

    private String json(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name, position,
                                   lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED', NULL)
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String title) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        (SELECT s.id FROM suite s WHERE s.project_id = CAST(? AS uuid) LIMIT 1),
                        ?, ?, ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, projectId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case_version
                    (id, company_id, case_id, version_no, snapshot, content_hash, created_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 1,
                        CAST('{}' AS jsonb), ?, now())
                    ON CONFLICT (case_id, version_no) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, id.toString());
            statement.setString(3, "hash-" + id);
            statement.executeUpdate();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?,
                        'IN_PROGRESS', 'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, projectId.toString());
            statement.executeUpdate();
        }
    }

    private void execution(Connection connection, UUID companyId, UUID projectId, UUID runId,
            UUID caseId, String statusName, Instant executedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_execution
                    (id, company_id, project_id, run_id, case_id, case_version_id, status_id,
                     executed_at, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        CAST(? AS uuid),
                        (SELECT v.id FROM test_case_version v
                          WHERE v.case_id = CAST(? AS uuid) AND v.version_no = 1),
                        (SELECT s.id FROM case_status s
                          WHERE s.project_id = CAST(? AS uuid) AND s.name = ?),
                        CAST(? AS timestamptz), now(), now())
                    ON CONFLICT (run_id, case_id) DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.setString(3, runId.toString());
            statement.setString(4, caseId.toString());
            statement.setString(5, caseId.toString());
            statement.setString(6, projectId.toString());
            statement.setString(7, statusName);
            statement.setString(8, executedAt.toString());
            statement.executeUpdate();
        }
    }

    private void clearEverything() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of("execution_fact", "case_health", "step_result",
                        "test_execution", "test_run", "doc_link", "doc_block", "doc_page",
                        "doc_space", "case_classification", "test_case_version", "test_case",
                        "suite")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + table)) {
                        statement.executeUpdate();
                    }
                }
            }
        }
    }
}
