package com.tcms.reporting;

import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>Coverage matrix</strong> — §6e's fourth catalogue entry (<em>"Requirement → cases → last
 * result → open defects"</em>), computed the way §6i says it should be: with a {@code DocPage} as
 * the report's <em>spine</em>.
 *
 * <p>§6i describes the output exactly — <em>"walk the document's own headings in order, and under
 * each, render the linked cases with their latest results … here is what we said the system does,
 * and here is the proof it does it"</em> — and names the half that makes it worth generating:
 * it <em>"surfaces documented behaviour with no linked test case as an explicit coverage gap, in the
 * reader's own document structure"</em>. A flat list of uncovered requirement ids is a report
 * somebody has to reconcile against the specification by hand; this one <em>is</em> the
 * specification.
 *
 * <h2>This report exists now because {@code doc_link} acquired a writer</h2>
 *
 * <p>M2.5's reporting plan §8 lists the coverage matrix under "not covered", with one blocker: it is
 * <em>"defined as a query over {@code doc_link}"</em>, and nothing could create one. §93's
 * {@code BlockLinkService} closed that, and {@code DocBlockView}'s header already records the
 * supersession. Nothing else about the slice changed — no migration, no table, no column, no
 * permission key.
 *
 * <h2>What counts as a statement, and why it is not every block</h2>
 *
 * <p>A coverage report has to decide which blocks are <em>candidates for coverage</em>, and the
 * tempting answer — all of them — is the one this module has already argued against. {@link
 * com.tcms.documentation.DocBlockView} refuses to serve a zeroed inline {@code coverage} marker
 * because a zero <em>"does not read as 'unknown', it reads as 'no test case verifies this
 * statement'"</em>, and putting that against every paragraph of a specification is a specific,
 * alarming and usually false claim. Making every paragraph a row of this report is the same claim in
 * a document somebody sends an auditor.
 *
 * <p>So a <strong>statement</strong> is either:
 *
 * <ul>
 *   <li>a {@code list} block — §93 made {@code level} a third arm precisely so a nested
 *       test-scenario outline is linkable <em>per bullet</em>: <em>"a {@code doc_link} targets a
 *       block, so a scenario a case can verify must BE a block, so one bullet is one block"</em>.
 *       The bullets are what the document intends to be verifiable, which is the question this
 *       report is asking; or</li>
 *   <li><strong>any block that already carries a {@code VERIFIES} link</strong> — somebody has
 *       asserted it is coverable by asserting it is covered, and a report that hid the very rows a
 *       reader created would be unusable.</li>
 * </ul>
 *
 * <p>Headings are the spine and never a statement: a heading is where a requirement lives, not the
 * requirement. Prose, code, tables and images are context unless linked.
 *
 * <h2>The result is fixed to LATEST, and that is a decision rather than a default</h2>
 *
 * <p>Slice-plan decision D's rule is that a report which quietly means {@code LATEST} has a default
 * nobody chose, so it has to be said. {@link AggregateExecutionReport} makes the policy a parameter
 * because it is answering <em>"how did this release go"</em>, where every execution is evidence. A
 * coverage matrix asks a different question — <em>"is this statement verified now"</em> — and there
 * is exactly one answer to it: the most recent recorded result. {@code WORST} over a case fixed
 * three weeks ago would print a failure that no longer exists, against a requirement, in the
 * document a release board reads. The ordering below is {@code resolvedCte}'s {@code LATEST} arm
 * clause for clause, and the metadata says which policy produced the numbers rather than leaving a
 * reader to assume.
 *
 * <h2>A statement's own state is the pessimistic roll-up</h2>
 *
 * <p>A statement may be verified by several cases, so its state is not any one of theirs:
 *
 * <ol>
 *   <li><strong>Not covered</strong> — no {@code VERIFIES} link at all.</li>
 *   <li><strong>Failing</strong> — at least one linked case whose latest recorded result does not
 *       count as a pass.</li>
 *   <li><strong>Not run</strong> — covered, and no linked case has any recorded result.</li>
 *   <li><strong>Verified</strong> — covered, at least one result recorded, and nothing failing.</li>
 * </ol>
 *
 * <p><strong>Failing outranks verified deliberately.</strong> Four cases green and one red is not a
 * verified requirement, and §46's rule is that where a roll-up could go either way it must not be
 * the direction that makes the report look better than the truth. The same argument is why
 * {@code notRun} is its own state rather than folded into "not verified": <em>nobody has tested
 * this</em> and <em>this fails</em> are different problems with different owners.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>§6e's catalogue line ends <em>"→ open defects"</em>. There is no defect anywhere in this
 * product: {@code IssueTracker} adapters are M4, and the {@code work} module's items are not
 * defects. The column is named here as not built rather than rendered empty, which would read as
 * <em>"no defects are open against this requirement"</em> — a claim, where an absence is not one.
 *
 * <p>The spine is <strong>one page</strong>, not a subtree. §6i offers <em>"a {@code DocPage} (or a
 * subtree)"</em>; a subtree is one {@code path <@} predicate away and is left out because a page's
 * descendants are a different <em>document</em> in every space this product seeds, and a report
 * whose scope silently included three sibling specifications would be wrong in the direction nobody
 * checks. When a caller wants one, it is a parameter, and the header's selection block already
 * prints what was asked for.
 */
@Component
class CoverageMatrixReport implements ReportComputation {

    /** The wire identifier. Durable — a saved definition names this string, not this class. */
    static final String TYPE = "coverage_matrix";

    /** What the spine's first section is called when a page opens with statements, not a heading. */
    static final String PREAMBLE = "(before the first heading)";

    /**
     * Where a statement's text is cut.
     *
     * <p>Cut here, in the computation, and never in a renderer: {@link ReportModel}'s load-bearing
     * decision is that a cell is text somebody has already formatted, so that the screen and the
     * export cannot disagree. A paragraph is not bounded, a table cell effectively is, and a column
     * whose first row is nine hundred words is a table nobody can read in any of the three formats.
     */
    static final int STATEMENT_LIMIT = 160;

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final EntityManager entities;
    private final DocumentationSource documents;
    private final ExecutionSource executions;
    private final ProjectRepository projects;

    CoverageMatrixReport(
            EntityManager entities,
            DocumentationSource documents,
            ExecutionSource executions,
            ProjectRepository projects) {

        this.entities = entities;
        this.documents = documents;
        this.executions = executions;
        this.projects = projects;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String description() {
        return "A specification with its verification evidence inline: every documented statement, "
                + "the cases that claim to verify it, and their latest results.";
    }

    /**
     * {@code readOnly = true} and {@code REQUIRED} — {@code RunSummaryReport}'s reasoning verbatim:
     * the worker has already opened a transaction and this joins it, but the annotation stays,
     * because a computation running outside one has {@code app.current_company} unset and {@code V4}
     * makes that <em>zero rows</em> rather than an error.
     */
    @Override
    @Transactional(readOnly = true)
    public ReportModel compute(UUID projectId, ReportParameters parameters) {
        UUID pageId = parameters.requireUuid("docPageId");

        DocumentationSource.PageHeader page = documents.findPage(projectId, pageId).orElseThrow(
                () -> new IllegalArgumentException("This project has no documentation page with "
                        + "that id"));

        List<DocumentationSource.BlockRow> blocks = documents.blocks(pageId);
        Map<UUID, List<UUID>> verifying = documents.verifyingCasesByBlock(pageId);

        Set<UUID> caseIds = new LinkedHashSet<>();
        verifying.values().forEach(caseIds::addAll);
        Map<UUID, ExecutionSource.CaseLabel> labels = executions.caseLabels(projectId, caseIds);
        Map<UUID, LatestResult> latest = latestResults(projectId, caseIds);

        List<Chapter> chapters = spine(blocks, verifying);
        Tally tally = tally(chapters, verifying, latest);

        List<ReportModel.Section> sections = new ArrayList<>();
        sections.add(coverageSection(tally));
        for (Chapter chapter : chapters) {
            sections.add(chapterSection(chapter, verifying, labels, latest));
        }

        return new ReportModel(
                "Coverage matrix — " + page.title(),
                metadata(projectId, page, tally),
                sections);
    }

    // =====================================================================
    // The spine — the document's own structure, in its own order
    // =====================================================================

    /**
     * Splits the page's blocks into chapters at each heading, keeping every statement under the
     * heading it is written beneath.
     *
     * <p>A page that opens with statements before any heading gets a {@value #PREAMBLE} chapter
     * rather than having them attached to the first heading below them, which would file a
     * requirement under a section it is not in. A chapter with no statements is <strong>kept</strong>
     * and prints a sentence saying so: a heading that vanishes from the output makes the report stop
     * being the document, which is the one property it has over a flat table.
     *
     * <p>Heading depth is not nested into a tree. {@link ReportModel.Section} is a flat list by
     * design, and the depth is carried into the heading text as a prefix so a reader can still see
     * the structure. A nested model would be a change to every renderer for a document shape none of
     * them has ever had.
     */
    private List<Chapter> spine(
            List<DocumentationSource.BlockRow> blocks, Map<UUID, List<UUID>> verifying) {

        List<Chapter> chapters = new ArrayList<>();
        Chapter current = null;

        for (DocumentationSource.BlockRow block : blocks) {
            if ("heading".equals(block.blockType())) {
                current = new Chapter(headingLabel(block), new ArrayList<>());
                chapters.add(current);
                continue;
            }
            if (!isStatement(block, verifying)) {
                continue;
            }
            if (current == null) {
                current = new Chapter(PREAMBLE, new ArrayList<>());
                chapters.add(current);
            }
            current.statements().add(block);
        }
        return chapters;
    }

    /** See the class header: a bullet, or anything somebody has already linked. */
    private boolean isStatement(
            DocumentationSource.BlockRow block, Map<UUID, List<UUID>> verifying) {

        return "list".equals(block.blockType()) || verifying.containsKey(block.id());
    }

    /** {@code 1.2 Refunds} without the numbering — depth as leading dots, then the text. */
    private String headingLabel(DocumentationSource.BlockRow block) {
        int depth = block.level() == null ? 1 : Math.clamp(block.level(), 1, 6);
        String text = block.text().isBlank() ? "(untitled heading)" : block.text().trim();
        return depth <= 1 ? text : "· ".repeat(depth - 1) + text;
    }

    // =====================================================================
    // The model
    // =====================================================================

    private List<ReportModel.Entry> metadata(
            UUID projectId, DocumentationSource.PageHeader page, Tally tally) {

        List<ReportModel.Entry> metadata = new ArrayList<>();
        metadata.add(new ReportModel.Entry("Project", projectLabel(projectId)));
        metadata.add(new ReportModel.Entry("Document", page.key() + " — " + page.title()));
        // §6i's determinism note: a report generated against a doc records which version it used.
        // Both, because a v4 DRAFT and a v4 PUBLISHED are different claims about the same numbers.
        metadata.add(new ReportModel.Entry("Document version", "v" + page.versionNo()));
        metadata.add(new ReportModel.Entry("Document status", page.status()));
        metadata.add(new ReportModel.Entry("Statements", count(tally.statements())));
        metadata.add(new ReportModel.Entry("Covered", count(tally.covered())));
        metadata.add(new ReportModel.Entry(
                "Coverage", share(tally.covered(), tally.statements())));
        metadata.add(new ReportModel.Entry("Aggregation policy",
                "LATEST — each case's most recent recorded result across every run"));
        return metadata;
    }

    /**
     * The headline: the coverage ladder over the whole document.
     *
     * <p>The narrative names the denominator, which is <strong>statements and not cases</strong>. It
     * is the field this report differs from every other one in the product on, and getting it
     * backwards is how §65's "two contradictory percentages on one screen" happened: 12 of 14 cases
     * passing and 12 of 40 statements covered are both "86%" and "30%" to somebody skimming.
     */
    private ReportModel.Section coverageSection(Tally tally) {
        if (tally.statements() == 0) {
            return ReportModel.Section.text("Coverage",
                    "This document contains no statements a test case can be linked to. Coverage is "
                            + "reported over list items and any block somebody has already linked — "
                            + "a page of prose alone has nothing to cover, which is not the same as "
                            + "being uncovered.");
        }

        List<List<String>> rows = new ArrayList<>();
        List<ReportModel.Point> points = new ArrayList<>();
        for (State state : State.values()) {
            long value = tally.of(state);
            rows.add(List.of(state.label(), count(value), share(value, tally.statements())));
            points.add(new ReportModel.Point(state.label(), value));
        }

        return new ReportModel.Section(
                "Coverage",
                "Shares are of the " + count(tally.statements()) + " statement"
                        + (tally.statements() == 1 ? "" : "s")
                        + " in this document — not of the cases that verify them, and not of the "
                        + "project's cases. A statement is failing if any one of its cases last "
                        + "failed, so a requirement four cases prove and one disproves is counted "
                        + "as failing.",
                new ReportModel.Table(List.of("State", "Statements", "Share"), rows),
                List.of(new ReportModel.Series("Statements by coverage state", points)));
    }

    /**
     * One heading of the document, with its statements and their evidence.
     *
     * <p>A statement with several cases is <strong>several rows</strong> with the statement repeated,
     * and an uncovered one is a single row whose case column says so. One row shape rather than two
     * tables is what lets a reader scan a section for the word "Not covered" — §6i's coverage gap
     * "in the reader's own document structure" — and repeating the text is the cost of that, paid
     * because a blank cell continuing the row above reads as a rendering fault in Markdown and is
     * simply wrong in a table a screen reader announces cell by cell.
     */
    private ReportModel.Section chapterSection(
            Chapter chapter,
            Map<UUID, List<UUID>> verifying,
            Map<UUID, ExecutionSource.CaseLabel> labels,
            Map<UUID, LatestResult> latest) {

        if (chapter.statements().isEmpty()) {
            return ReportModel.Section.text(chapter.heading(),
                    "No statements under this heading. Coverage is reported over list items and any "
                            + "block already linked to a case.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (DocumentationSource.BlockRow statement : chapter.statements()) {
            List<UUID> cases = verifying.getOrDefault(statement.id(), List.of());
            String text = statementText(statement);

            if (cases.isEmpty()) {
                rows.add(List.of(text, "—", State.NOT_COVERED.label(), "—"));
                continue;
            }
            for (UUID caseId : cases) {
                LatestResult result = latest.get(caseId);
                rows.add(List.of(
                        text,
                        caseLabel(caseId, labels),
                        result == null ? State.NOT_RUN.label() : result.statusKind(),
                        result == null || result.executedAt() == null
                                ? "—"
                                : WHEN.format(result.executedAt())));
            }
        }

        return ReportModel.Section.of(
                chapter.heading(),
                null,
                new ReportModel.Table(
                        List.of("Statement", "Case", "Last result", "Last run"), rows));
    }

    // =====================================================================
    // The tally
    // =====================================================================

    private Tally tally(
            List<Chapter> chapters,
            Map<UUID, List<UUID>> verifying,
            Map<UUID, LatestResult> latest) {

        Map<State, Long> counts = new LinkedHashMap<>();
        long statements = 0;
        for (Chapter chapter : chapters) {
            for (DocumentationSource.BlockRow statement : chapter.statements()) {
                statements++;
                State state = stateOf(verifying.getOrDefault(statement.id(), List.of()), latest);
                counts.merge(state, 1L, Long::sum);
            }
        }
        return new Tally(statements, counts);
    }

    /** The ladder in the class header, in its stated order — failing before verified. */
    private State stateOf(List<UUID> cases, Map<UUID, LatestResult> latest) {
        if (cases.isEmpty()) {
            return State.NOT_COVERED;
        }
        boolean anyRecorded = false;
        for (UUID caseId : cases) {
            LatestResult result = latest.get(caseId);
            if (result == null) {
                continue;
            }
            anyRecorded = true;
            if (!result.countsAsPass()) {
                return State.FAILING;
            }
        }
        return anyRecorded ? State.VERIFIED : State.NOT_RUN;
    }

    // =====================================================================
    // The query — execution_fact, and the LATEST arm of resolvedCte
    // =====================================================================

    /**
     * Each case's most recent recorded result across every run in the project.
     *
     * <p>{@code DISTINCT ON} rather than {@code AggregateExecutionReport}'s window function, because
     * there is no policy to switch on and no selection to narrow by — but the {@code ORDER BY} is
     * that report's {@code LATEST} arm clause for clause, including {@code NULLS LAST} (<em>"a row
     * nobody timed is not a later result, it is the absence of one"</em>) and the
     * {@code execution_id} tiebreak. <strong>Check both when either moves</strong>: two answers to
     * "the latest result" that disagree only on ties is the worst kind of disagreement, because it is
     * reproducible and rare.
     *
     * <p>The {@code execution_id} tiebreak carries the asymmetry {@link AggregateExecutionReport}
     * records — a {@code UUID} orders by signed halves in Java and unsigned bytes in Postgres — and
     * it is reachable here only for a case with two results at the identical timestamp and differing
     * verdicts. Nothing in this report compares its answer to a Java-side one, so there is no second
     * ordering to disagree with; the note is here so the next person does not add one.
     *
     * <p><strong>The fact log holds recorded executions only</strong>, which is what makes an absent
     * entry mean {@code Not run} and not {@code missing}. It is also why the fact log can never
     * answer this report's <em>denominator</em> — the statement count comes from the document, and
     * §46's finding is that a coverage number sourced from the executions is a coverage number of
     * the wrong thing.
     */
    private Map<UUID, LatestResult> latestResults(UUID projectId, Set<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LatestResult> latest = new LinkedHashMap<>();
        for (Object result : entities.createNativeQuery("""
                SELECT DISTINCT ON (f.case_id)
                       f.case_id, f.status_kind, f.counts_as_pass, f.executed_at
                  FROM execution_fact f
                 WHERE f.project_id = :projectId
                   AND f.case_id IN (:caseIds)
                 ORDER BY f.case_id, f.executed_at DESC NULLS LAST, f.execution_id DESC
                """)
                .setParameter("projectId", projectId)
                .setParameter("caseIds", caseIds)
                .getResultList()) {

            Object[] row = (Object[]) result;
            latest.put(
                    UUID.fromString(row[0].toString()),
                    new LatestResult(
                            (String) row[1],
                            (Boolean) row[2],
                            toInstant(row[3])));
        }
        return Map.copyOf(latest);
    }

    // =====================================================================
    // Formatting — once, here
    // =====================================================================

    /**
     * {@code TIMESTAMPTZ} arrives as {@link java.sql.Timestamp} through a native query, unlike the
     * {@link Instant} an entity mapping hands back — {@code execution.CaseFacts} pays for the same
     * thing at the same seam, and this is its helper rather than a bare cast, because which of the
     * three types the driver returns is a property of the driver and not of this query.
     */
    private static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case java.sql.Timestamp timestamp -> timestamp.toInstant();
            case java.time.OffsetDateTime offset -> offset.toInstant();
            default -> throw new IllegalStateException(
                    "Unexpected timestamp type " + value.getClass());
        };
    }

    private String projectLabel(UUID projectId) {
        Optional<Project> project = projects.findScopedById(projectId);
        return project.map(p -> p.getKey() + " — " + p.getName()).orElse("—");
    }

    /**
     * A statement's text, on one line and bounded.
     *
     * <p>Newlines collapse to spaces: a cell containing one breaks a Markdown table outright and
     * silently reflows an HTML one, and a block's text is free-form.
     */
    private String statementText(DocumentationSource.BlockRow block) {
        String text = block.text().replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            // A linked block with no words is a link somebody made to a spacer. Printing the
            // position rather than an empty cell is what makes it findable in the document.
            return "(empty block at position " + block.position() + ")";
        }
        return text.length() <= STATEMENT_LIMIT
                ? text
                : text.substring(0, STATEMENT_LIMIT - 1).trim() + "…";
    }

    /**
     * {@code TC_0007 — Refund a captured payment}, or the raw id.
     *
     * <p>{@code doc_link.source_id} is opaque with no foreign key behind it — {@code documentation}
     * may not hold a key into {@code repository} — so a link can outlive the case it names. Printing
     * the id is {@link AggregateExecutionReport}'s call in the same situation and for its reason: the
     * row is evidence that a claim was recorded, and dropping it would quietly raise the coverage
     * number by removing a case that no longer exists.
     */
    private String caseLabel(UUID caseId, Map<UUID, ExecutionSource.CaseLabel> labels) {
        ExecutionSource.CaseLabel label = labels.get(caseId);
        return label == null ? caseId.toString() : label.key() + " — " + label.title();
    }

    /** No grouping separators and no locale: an artifact must not read differently per JVM. */
    private String count(long value) {
        return Long.toString(value);
    }

    /** One decimal, {@link Locale#ROOT}, and an em dash where the denominator is zero. */
    private String share(long part, long whole) {
        if (whole == 0) {
            return "—";
        }
        return String.format(Locale.ROOT, "%.1f%%", (part * 100.0d) / whole);
    }

    /** A heading of the document and the statements written beneath it. */
    private record Chapter(String heading, List<DocumentationSource.BlockRow> statements) {}

    /** One case's most recent recorded result. */
    private record LatestResult(String statusKind, boolean countsAsPass, Instant executedAt) {}

    /**
     * The coverage ladder, in the order the summary table prints it.
     *
     * <p>Declaration order is the table's order for {@code RunSummaryReport}'s reason: two coverage
     * reports are read side by side, and a table whose rows move when the numbers move cannot be
     * compared row by row.
     */
    private enum State {
        VERIFIED("Verified"),
        FAILING("Failing"),
        NOT_RUN("Not run"),
        NOT_COVERED("Not covered");

        private final String label;

        State(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** Statement counts by state, and the total they are shares of. */
    private record Tally(long statements, Map<State, Long> byState) {

        long of(State state) {
            return byState.getOrDefault(state, 0L);
        }

        long covered() {
            return statements - of(State.NOT_COVERED);
        }
    }
}
