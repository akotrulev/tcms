package com.tcms.reporting;

import com.tcms.project.ClassificationValueRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.StatusKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>Aggregate execution report</strong> — §6e's second catalogue entry and the first report in
 * the product that spans more than one run: <em>"aggregate across a selection: totals, pass rate,
 * failures by suite and by feature, under a stated aggregation policy"</em>.
 *
 * <h2>The policy is resolved in SQL, and that is decision D's load-bearing half</h2>
 *
 * <p>{@code execution.PlanStatisticsService.resolve} answers the same question in Java, over
 * {@code test_execution}. It is ~80 lines carrying three fixes §46 paid for in defects, and ERD
 * §0.2 puts it out of reach of this module anyway. Copying it here would be E1 — one rule, two
 * implementations — at its most expensive.
 *
 * <p>So this resolves the policy <strong>in one window function over {@code execution_fact}</strong>:
 * a different implementation, over different data, answering the same question. That is only
 * tolerable with the guard decision D makes non-optional, and
 * {@code AggregateExecutionCrossCheckTest} is it — for a single-plan selection the per-status counts
 * here must equal {@code GET /plans/{planId}/statistics}'s under the same policy, asserted
 * <em>against each other</em> rather than against literals. §46's reason, verbatim: a literal
 * agreeing with a wrong implementation is how both screens end up wrong together. The defect it
 * prevents is a plan page reading 94% and the release report reading 91%, for the same plan, on the
 * same day, with nothing to say which is lying.
 *
 * <h3>The window's ORDER BY is {@code resolve}'s comparator, term for term</h3>
 *
 * <p>{@code resolve} keeps the <em>maximum</em> row under a comparator; {@code row_number() = 1}
 * keeps the first under the reverse ordering. They are written to line up clause by clause, and the
 * mapping is the thing to check when either side changes:
 *
 * <table>
 *   <caption>Policy → ordering</caption>
 *   <tr><th>Policy</th><th>{@code resolve} comparator (max wins)</th><th>SQL (first row wins)</th></tr>
 *   <tr><td>{@code ALL}</td><td>no collapse</td><td>no window at all</td></tr>
 *   <tr><td>{@code LATEST}</td><td>{@code executedAt} nulls first, then {@code executionId}</td>
 *       <td>{@code executed_at DESC NULLS LAST, execution_id DESC}, partitioned by case</td></tr>
 *   <tr><td>{@code LATEST_PER_CONFIG}</td><td>same, keyed by (case, configuration)</td>
 *       <td>same, partitioned by (case, configuration)</td></tr>
 *   <tr><td>{@code WORST}</td><td>verdicts first, then {@code -severityIndex}</td>
 *       <td>{@code is_verdict DESC, severity ASC, execution_id DESC}</td></tr>
 *   <tr><td>{@code BEST}</td><td>verdicts first, then {@code +severityIndex}</td>
 *       <td>{@code is_verdict DESC, severity DESC, execution_id DESC}</td></tr>
 * </table>
 *
 * <h3>Two asymmetries that are NOT defects, and had to be found before the cross-check could be believed</h3>
 *
 * <ol>
 *   <li><strong>The fact log has no unrecorded rows and {@code PlanStatisticsService} does.</strong>
 *       {@link ExecutionSource}'s projection joins {@code case_status} <em>inner</em>, so an
 *       execution with no {@code status_id} — a fresh run's untouched checklist — has no fact row at
 *       all, while {@code resolve} sees it as an {@code UNTESTED} row. The two therefore agree on
 *       <em>recorded</em> results and only those. Slice-plan decision D says this in advance, and it
 *       is repeated here because discovering it inside the cross-check test would read as a defect
 *       in the new code.
 *   <li><strong>{@code notRun} is not this report's to compute.</strong> It is
 *       {@code scope − touched}, and the scope is the plan's — re-resolved per read from suites,
 *       tags and named cases. A star schema has no scope; it has facts. So this report's
 *       denominator is <em>recorded executions</em>, said in the metadata and again in the results
 *       narrative, exactly as {@link RunSummaryReport} does for one run and for the same reason.
 * </ol>
 *
 * <p>A third would be a real divergence if it could change a count, and it cannot: {@code UUID}
 * orders differently in Java (signed 64-bit halves) and in Postgres (unsigned bytes), so the
 * {@code execution_id} tiebreak can pick different rows. It only reaches a <em>count</em> under
 * {@code LATEST}/{@code LATEST_PER_CONFIG}, where two executions of one case share an
 * {@code executed_at} to the microsecond and carry different statuses; under {@code WORST} and
 * {@code BEST} a tie is by construction between rows of the same kind, so the winner's identity
 * moves and no number does. The tiebreak exists to be <em>stable</em>, not meaningful
 * ({@code resolve} says so), and the cross-check fixture gives every execution a distinct timestamp
 * so the test asserts the rule rather than the coin toss.
 *
 * <h2>Both counts, always — §6e names conflating them as the defect</h2>
 *
 * <p><em>"Both execution count and distinct-case count (they differ, and conflating them inflates
 * numbers)."</em> Under {@code ALL} they differ by every retry; under {@code LATEST} they are equal
 * by construction; under {@code LATEST_PER_CONFIG} they differ by every extra configuration. All
 * three are printed, so the reader can see which policy produced the spread instead of inferring it.
 */
@Component
class AggregateExecutionReport implements ReportComputation {

    /** The wire identifier. Durable — a saved definition names this string, not this class. */
    static final String TYPE = "aggregate_execution";

    /** What a NULL {@code suite_path} prints as (§96) — a case filed nowhere when it ran. */
    static final String NOT_FILED = RunSummaryReport.NOT_FILED;

    /** What a NULL/empty {@code feature_ids} prints as — a case nobody classified by feature. */
    static final String UNCLASSIFIED = "(no feature)";

    /**
     * Severity, worst first — a mirror of {@code PlanStatisticsService.WORST_FIRST}, as a SQL
     * {@code CASE}.
     *
     * <p>Duplicated for the same reason the enum above it is, and pinned by
     * {@code AggregateExecutionPolicyTest} against {@code StatusKind}'s own value set so a kind
     * added to the product cannot silently fall through to the {@code ELSE} and rank as the best
     * possible result. The ranking itself is argued in {@code PlanStatisticsService}: blocked is
     * worse than owed a verdict, retest worse than never touched, skipped better than either
     * because skipping is a decision.
     */
    private static final List<StatusKind> WORST_FIRST = List.of(
            StatusKind.FAILED,
            StatusKind.BLOCKED,
            StatusKind.RETEST,
            StatusKind.WIP,
            StatusKind.UNTESTED,
            StatusKind.SKIPPED,
            StatusKind.PASSED);

    /** The kinds that are not a verdict — {@code PlanStatisticsService.isVerdict}, negated. */
    private static final List<StatusKind> NOT_A_VERDICT =
            List.of(StatusKind.UNTESTED, StatusKind.RETEST, StatusKind.WIP);

    private final EntityManager entities;
    private final ProjectRepository projects;
    private final ClassificationValueRepository features;

    AggregateExecutionReport(
            EntityManager entities,
            ProjectRepository projects,
            ClassificationValueRepository features) {
        this.entities = entities;
        this.projects = projects;
        this.features = features;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String description() {
        return "Executions across a selection of plans, suites, features and dates, "
                + "resolved under a stated aggregation policy.";
    }

    /** {@code readOnly = true}, {@code REQUIRED} — {@link RunSummaryReport} states why at length. */
    @Override
    @Transactional(readOnly = true)
    public ReportModel compute(UUID projectId, ReportParameters parameters) {
        AggregateSelection selection = AggregateSelection.parse(parameters);

        Totals totals = totals(projectId, selection);
        List<StatusRow> statuses = statuses(projectId, selection);

        List<ReportModel.Section> sections = new ArrayList<>();
        sections.add(resultsSection(selection, totals, statuses));
        sections.add(suiteSection(projectId, selection));
        sections.add(featureSection(projectId, selection));

        return new ReportModel(
                "Aggregate execution report", metadata(projectId, selection, totals), sections);
    }

    // =====================================================================
    // The model
    // =====================================================================

    private List<ReportModel.Entry> metadata(
            UUID projectId, AggregateSelection selection, Totals totals) {

        List<ReportModel.Entry> metadata = new ArrayList<>();
        metadata.add(new ReportModel.Entry("Project", projectLabel(projectId)));
        metadata.addAll(selection.applied());
        // §6e: both, always. See the class header for how the two move apart per policy.
        metadata.add(new ReportModel.Entry("Contributing executions", count(totals.executions())));
        metadata.add(new ReportModel.Entry("Distinct cases", count(totals.cases())));
        metadata.add(new ReportModel.Entry("Passed", count(totals.passed())));
        metadata.add(new ReportModel.Entry(
                "Pass rate (of contributing executions)",
                share(totals.passed(), totals.executions())));
        return List.copyOf(metadata);
    }

    private ReportModel.Section resultsSection(
            AggregateSelection selection, Totals totals, List<StatusRow> statuses) {

        if (totals.executions() == 0) {
            // An empty selection is a sentence and never a clean report of nothing — the failure
            // shape §47, §53, §72 and §81 each paid for. It says which of the two it is: nothing
            // recorded at all, or a selection that matched none of what is recorded.
            return ReportModel.Section.text("Results", selection.isWholeProject()
                    ? "No executions have been recorded in this project yet."
                    : "No recorded execution matches this selection. The project may still hold "
                            + "results outside the plans, suites, features or dates selected here.");
        }

        String narrative = "Shares are of the " + count(totals.executions())
                + " execution" + (totals.executions() == 1 ? "" : "s")
                + " that contribute under " + selection.policy().wireValue()
                + ". Cases with no recorded result do not appear here at all — a selection's scope "
                + "is not part of the execution facts this report is computed from.";

        List<List<String>> rows = new ArrayList<>();
        List<ReportModel.Point> points = new ArrayList<>();
        for (StatusRow status : statuses) {
            rows.add(List.of(
                    status.kind(),
                    count(status.executions()),
                    count(status.cases()),
                    share(status.executions(), totals.executions())));
            points.add(new ReportModel.Point(status.kind(), status.executions()));
        }

        return new ReportModel.Section(
                "Results",
                narrative,
                new ReportModel.Table(
                        List.of("Status", "Executions", "Distinct cases", "Share"), rows),
                List.of(new ReportModel.Series("Executions by status", points)));
    }

    /**
     * Failures by suite — §6e's section, over the frozen {@code suite_path} dimension.
     *
     * <p>Ordered by failures descending, because this table is read to decide where to look first,
     * and it is the one table in the report whose row order is a finding rather than a convention.
     * A NULL path is a row printed as {@value #NOT_FILED} and never an omission (§96) — dropping it
     * would stop this section summing to the headline in the direction nobody checks.
     */
    private ReportModel.Section suiteSection(UUID projectId, AggregateSelection selection) {
        List<GroupRow> suites = groups(projectId, selection, "COALESCE(r.suite_path::text, '')");
        if (suites.isEmpty()) {
            return ReportModel.Section.text(
                    "Failures by suite", "No results contribute to this selection.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (GroupRow suite : suites) {
            rows.add(List.of(
                    suite.label().isEmpty() ? NOT_FILED : suite.label(),
                    count(suite.executions()),
                    count(suite.failed()),
                    share(suite.failed(), suite.executions())));
        }

        return ReportModel.Section.of(
                "Failures by suite",
                "Paths are as they were when each result was recorded, not as the project is "
                        + "organised now. Worst failure rate first.",
                new ReportModel.Table(
                        List.of("Suite", "Executions", "Failed", "Failure rate"), rows));
    }

    /**
     * Failures by feature — the section that needs the fact log's {@code feature_ids} array.
     *
     * <h2>A case with two features is counted under both, and the column total therefore exceeds
     * the headline</h2>
     *
     * <p>{@code feature_ids} is a {@code UUID[]} and {@code unnest} is what groups by it, so an
     * execution classified under Payments <em>and</em> Refunds contributes a row to each. That is
     * the only useful reading — "how much of Payments is failing" must count every Payments
     * execution — and it makes this the one table in the report that does not sum to the headline.
     * §96 settled the same for suite counts and the rule from it holds: <strong>say so in the
     * narrative rather than let a reader find it by subtraction.</strong>
     *
     * <p>Executions carrying no feature are one {@value #UNCLASSIFIED} row rather than dropped, for
     * {@link #suiteSection}'s reason: an omission here reads as a project that classified everything.
     */
    private ReportModel.Section featureSection(UUID projectId, AggregateSelection selection) {
        List<GroupRow> features = featureGroups(projectId, selection);
        if (features.isEmpty()) {
            return ReportModel.Section.text(
                    "Failures by feature", "No results contribute to this selection.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (GroupRow feature : features) {
            rows.add(List.of(
                    feature.label().isEmpty() ? UNCLASSIFIED : feature.label(),
                    count(feature.executions()),
                    count(feature.failed()),
                    share(feature.failed(), feature.executions())));
        }

        return ReportModel.Section.of(
                "Failures by feature",
                "A case classified under several features is counted under each, so these "
                        + "executions add up to more than the headline. Worst failure rate first.",
                new ReportModel.Table(
                        List.of("Feature", "Executions", "Failed", "Failure rate"), rows));
    }

    // =====================================================================
    // The queries — execution_fact and nothing else
    // =====================================================================

    /**
     * The resolved row set as a CTE, named {@code r}, ready to be aggregated by any statement.
     *
     * <p>One string, built once, so every section narrows and collapses identically. See the class
     * header for the clause-by-clause mapping onto {@code PlanStatisticsService.resolve}.
     */
    private String resolvedCte(AggregateSelection selection) {
        String selected = """
                WITH selected AS (
                    SELECT f.execution_id, f.case_id, f.configuration_id, f.status_kind,
                           f.counts_as_pass, f.executed_at, f.suite_path, f.feature_ids
                      FROM execution_fact f
                     WHERE f.project_id = :projectId
                """ + selection.where() + "\n)";

        if (selection.policy() == AggregationPolicy.ALL) {
            // No window at all. ALL is "every execution counted separately", so collapsing and then
            // undoing it would be the same answer computed twice.
            return selected + ", resolved AS (SELECT * FROM selected)";
        }

        String partition = selection.policy() == AggregationPolicy.LATEST_PER_CONFIG
                ? "s.case_id, s.configuration_id"
                : "s.case_id";

        String order = switch (selection.policy()) {
            // NULLS LAST: a row nobody timed is not a later result, it is the absence of one —
            // resolve()'s `nullsFirst` on a max-wins comparator, which is the same statement.
            case LATEST, LATEST_PER_CONFIG ->
                    "s.executed_at DESC NULLS LAST, s.execution_id DESC";
            // A recorded verdict beats an unrecorded row before severity is consulted at all.
            // Without it, an UNTESTED row would outrank PASSED under WORST and a case that has
            // passed in every run it was recorded in would read as owed a verdict.
            case WORST -> verdictFirst() + ", " + severityRank("s") + " ASC, s.execution_id DESC";
            case BEST -> verdictFirst() + ", " + severityRank("s") + " DESC, s.execution_id DESC";
            case ALL -> throw new IllegalStateException("handled above");
        };

        return selected + """
                , ranked AS (
                    SELECT s.*, row_number() OVER (
                               PARTITION BY %s ORDER BY %s) AS rn
                      FROM selected s
                )
                , resolved AS (SELECT * FROM ranked WHERE rn = 1)
                """.formatted(partition, order);
    }

    /** {@code resolve}'s {@code verdictsFirst()} — true sorts above false, hence DESC. */
    private static String verdictFirst() {
        StringBuilder out = new StringBuilder("(s.status_kind NOT IN (");
        for (int i = 0; i < NOT_A_VERDICT.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('\'').append(NOT_A_VERDICT.get(i).name()).append('\'');
        }
        return out.append(")) DESC").toString();
    }

    /**
     * {@code WORST_FIRST.indexOf(kind)} as a {@code CASE} — 0 is the worst result.
     *
     * <p>The {@code ELSE} is deliberately the <em>worst</em> rank and not the best. A status kind
     * this build does not know is a defect, and a defect that makes a report look better than the
     * truth is the one direction §46 says must never be taken by default.
     */
    private static String severityRank(String alias) {
        StringBuilder out = new StringBuilder("CASE " + alias + ".status_kind");
        for (int i = 0; i < WORST_FIRST.size(); i++) {
            out.append(" WHEN '").append(WORST_FIRST.get(i).name()).append("' THEN ").append(i);
        }
        return out.append(" ELSE -1 END").toString();
    }

    private Totals totals(UUID projectId, AggregateSelection selection) {
        Query query = entities.createNativeQuery(resolvedCte(selection) + """
                SELECT count(*)                                 AS executions,
                       count(DISTINCT r.case_id)                AS cases,
                       count(*) FILTER (WHERE r.counts_as_pass) AS passed
                  FROM resolved r
                """)
                .setParameter("projectId", projectId);
        selection.bind(query);

        Object[] row = (Object[]) query.getSingleResult();
        return new Totals(number(row[0]), number(row[1]), number(row[2]));
    }

    /**
     * The breakdown, ordered by {@link StatusKind}'s own declaration order — never by count.
     *
     * <p>{@link RunSummaryReport} makes the argument: two reports from one project are read side by
     * side, and a table whose rows move when the numbers move cannot be compared row by row.
     *
     * <p>Distinct cases per status is reported <em>as well as</em> executions, and it is a genuinely
     * different number under {@code ALL} — a case retried until it passed contributes to both the
     * FAILED and the PASSED row. It is also why the per-status case counts do not sum to the
     * headline's distinct-case count, which the header states rather than leaving to arithmetic.
     */
    private List<StatusRow> statuses(UUID projectId, AggregateSelection selection) {
        Query query = entities.createNativeQuery(resolvedCte(selection) + """
                SELECT r.status_kind, count(*), count(DISTINCT r.case_id)
                  FROM resolved r
                 GROUP BY r.status_kind
                """)
                .setParameter("projectId", projectId);
        selection.bind(query);

        Map<String, StatusRow> byKind = new LinkedHashMap<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            String kind = (String) row[0];
            byKind.put(kind, new StatusRow(kind, number(row[1]), number(row[2])));
        }

        List<StatusRow> ordered = new ArrayList<>();
        for (StatusKind kind : StatusKind.values()) {
            StatusRow row = byKind.remove(kind.name());
            if (row != null) {
                ordered.add(row);
            }
        }
        // Anything left is a kind this build does not know. Carried rather than dropped: a report
        // that silently omits results is worse than one with a row a reader has to ask about, and
        // the totals above would no longer add up if it were dropped.
        ordered.addAll(byKind.values());
        return List.copyOf(ordered);
    }

    /** One grouped table over the resolved rows, keyed by whatever expression the caller names. */
    private List<GroupRow> groups(UUID projectId, AggregateSelection selection, String keyExpr) {
        Query query = entities.createNativeQuery(resolvedCte(selection) + """
                SELECT %s                                                AS label,
                       count(*)                                          AS executions,
                       count(*) FILTER (WHERE r.status_kind = 'FAILED')  AS failed
                  FROM resolved r
                 GROUP BY %s
                 ORDER BY count(*) FILTER (WHERE r.status_kind = 'FAILED') DESC, 1
                """.formatted(keyExpr, keyExpr))
                .setParameter("projectId", projectId);
        selection.bind(query);

        return toGroups(query);
    }

    /**
     * Failures by feature — {@code unnest} over the array, grouped by <em>id</em>.
     *
     * <h2>Two things here are the shape of a defect this project has already paid for</h2>
     *
     * <p><strong>{@code LEFT JOIN LATERAL unnest(...)} and not a plain {@code unnest}</strong>. A
     * plain one drops the row entirely when the array is empty or NULL, so every unclassified
     * execution would vanish and the section would read as a project that classified everything —
     * §96's {@code JOIN suite} defect in a second shape, and the reason the orientation cache's
     * standing rule is to check what a join does to the empty side <em>before</em> adding one.
     *
     * <p>~~The {@code CASE} substituting a one-element {@code ARRAY[NULL]} is what keeps that
     * row.~~ → <strong>corrected in R6 phase 4, by the mutant that proved it.</strong> Removing the
     * {@code CASE} reddens <em>nothing</em>: {@code LEFT JOIN LATERAL … ON true} already preserves
     * the left row when {@code unnest} yields no rows. It is the <strong>join kind</strong> that
     * carries the guarantee — swapping this for a {@code CROSS JOIN LATERAL} drops every
     * unclassified execution and reddens three tests. The {@code CASE} is kept because it states
     * the intent at the point of the array, but a reader must not believe it is the check: the
     * thing to protect in review is the word {@code LEFT}.
     *
     * <p><strong>The label is resolved in Java, not joined in SQL.</strong> {@code execution_fact}
     * is the only table this class may name — {@link ExecutionSource} is the module's declared seam
     * for anything else — and {@code classification_value} belongs to {@code project}, which this
     * module may depend on as a <em>bean</em>. So the query groups by id and
     * {@link ClassificationValueRepository#findAllById} supplies the names, which is exactly how
     * {@link RunSummaryReport} gets tester names from {@code PeopleDirectory}.
     *
     * <p>{@code findAllById} is deliberate over any "live values" lookup: a feature retired last
     * quarter still named last quarter's failures, and a historical report whose rows lose their
     * labels when somebody tidies the vocabulary is a report that shrinks. A value that has been
     * hard-deleted resolves to nothing and prints its id rather than disappearing, for the same
     * reason the unclassified row exists.
     */
    private List<GroupRow> featureGroups(UUID projectId, AggregateSelection selection) {
        Query query = entities.createNativeQuery(resolvedCte(selection) + """
                SELECT fid                                                AS feature_id,
                       count(*)                                          AS executions,
                       count(*) FILTER (WHERE r.status_kind = 'FAILED')  AS failed
                  FROM resolved r
                  LEFT JOIN LATERAL unnest(
                           CASE WHEN r.feature_ids IS NULL OR cardinality(r.feature_ids) = 0
                                THEN ARRAY[NULL]::uuid[]
                                ELSE r.feature_ids END) AS fid ON true
                 GROUP BY fid
                 ORDER BY count(*) FILTER (WHERE r.status_kind = 'FAILED') DESC, 1
                """)
                .setParameter("projectId", projectId);
        selection.bind(query);

        List<Object[]> rows = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            rows.add(row);
            if (row[0] != null) {
                ids.add(UUID.fromString(row[0].toString()));
            }
        }

        Map<UUID, String> names = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            features.findAllById(ids).forEach(value -> names.put(value.getId(), value.getName()));
        }

        List<GroupRow> groups = new ArrayList<>();
        for (Object[] row : rows) {
            String label = "";
            if (row[0] != null) {
                UUID id = UUID.fromString(row[0].toString());
                label = names.getOrDefault(id, id.toString());
            }
            groups.add(new GroupRow(label, number(row[1]), number(row[2])));
        }
        return List.copyOf(groups);
    }

    private List<GroupRow> toGroups(Query query) {
        List<GroupRow> groups = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            groups.add(new GroupRow(
                    row[0] == null ? "" : row[0].toString(), number(row[1]), number(row[2])));
        }
        return List.copyOf(groups);
    }

    // =====================================================================
    // Formatting — once, here, because the model carries text and not numbers
    // =====================================================================

    private String projectLabel(UUID projectId) {
        Optional<Project> project = projects.findScopedById(projectId);
        return project.map(p -> p.getKey() + " — " + p.getName()).orElse("—");
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

    /** {@code count(*)} is a {@code Long}; a {@code sum} would be a {@code BigDecimal}. */
    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private record Totals(long executions, long cases, long passed) {}

    private record StatusRow(String kind, long executions, long cases) {}

    private record GroupRow(String label, long executions, long failed) {}
}
