package com.tcms.reporting;

import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.StatusKind;
import com.tcms.project.UserRefView;
import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>Run summary</strong> — the first report the product can compute, and the first entry in
 * §6e's catalogue: <em>"single run: status breakdown, per-tester progress, duration, failed cases
 * with evidence thumbnails, defects raised"</em>.
 *
 * <h2>Three of those five, and the missing two are a fact about the fact log</h2>
 *
 * <p>Status breakdown, per-tester progress and duration are all columns of {@code execution_fact}.
 * The other two are not, and cannot be reached from here at any width of {@code allowedDependencies}
 * plan §3 permits:
 *
 * <ul>
 *   <li><strong>Failed cases</strong> would need each case's key and title. The fact log carries
 *       {@code case_id} and {@code suite_path} and deliberately nothing else — a star schema
 *       duplicates the <em>dimensions</em> it aggregates by, not the labels of the rows it
 *       aggregates. A list of forty UUIDs is not the section §6e describes, so this report groups
 *       failures <em>by suite</em>, which the fact log can name, and the per-case list waits for the
 *       slice that decides where case labels come from.</li>
 *   <li><strong>Evidence thumbnails and defects raised</strong> are {@code execution}'s and M4's
 *       respectively. Named here as not built rather than quietly dropped.</li>
 * </ul>
 *
 * <h2>THE denominator, and the sentence the report has to print</h2>
 *
 * <p>{@code execution_fact} holds <strong>recorded</strong> executions only — the projection joins
 * {@code case_status}, so a run's untouched cases have no fact row at all ({@link ExecutionSource}
 * states the constraint and its consequence). A pass rate computed here is therefore
 * <em>passed over recorded</em> and never <em>passed over the run's scope</em>, and the two are very
 * different numbers on a run somebody has just started: three cases executed, all green, would print
 * "100%" for a release nobody has tested.
 *
 * <p>So the model says which one it means, in {@link ReportModel#metadata()} and again in the
 * results section's narrative. That is not documentation-in-a-string: §6e's whole argument for the
 * printed header is that <em>"two teams reading 94% pass can mean entirely different things"</em>,
 * and a percentage whose denominator is only in a javadoc is exactly that. The run's scope comes
 * from somewhere else — §46 found the same for plan statistics — and this report does not pretend
 * to have it.
 *
 * <h2>No aggregation policy, and that is derived rather than skipped</h2>
 *
 * <p>Slice-plan decision D refuses to defer the policy, because a report that quietly means
 * {@code LATEST} has a default nobody chose. It does not apply here and the reason is structural:
 * {@code uq_execution_run_case} makes a case appear <strong>at most once per run</strong>, so within
 * one run there is nothing to choose between. The metadata says so in a line rather than leaving a
 * reader to wonder which policy produced the number. R6's aggregate report spans many runs, which is
 * where the policy is a user-chosen parameter and where decision D's cross-check test binds.
 *
 * <h2>An unknown run is refused, not summarised as zero</h2>
 *
 * <p>A mistyped run id and a run nobody has recorded a result for both yield zero fact rows. Left
 * alone, the first would render a clean report of nothing — the failure mode this project has paid
 * for four times. {@link ExecutionSource#findRun} exists for that one check; a run that exists and
 * has no results is a report that says so.
 */
@Component
class RunSummaryReport implements ReportComputation {

    /** The wire identifier. Durable — a saved definition names this string, not this class. */
    static final String TYPE = "run_summary";

    /**
     * What a NULL {@code suite_path} prints as — a case that was filed nowhere when it ran (§96).
     *
     * <p>A label and not a blank cell: an empty first column reads as a rendering fault, and the
     * one thing a reader must be able to tell is that these executions are counted.
     */
    static final String NOT_FILED = "(not filed)";

    private final EntityManager entities;
    private final ExecutionSource source;
    private final ProjectRepository projects;
    private final PeopleDirectory people;

    RunSummaryReport(
            EntityManager entities,
            ExecutionSource source,
            ProjectRepository projects,
            PeopleDirectory people) {
        this.entities = entities;
        this.source = source;
        this.projects = projects;
        this.people = people;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String description() {
        return "A single run: status breakdown, progress per tester, and results by suite.";
    }

    /**
     * {@code readOnly = true} and {@code REQUIRED}: the worker has already opened a read-write
     * transaction and this joins it. It carries the annotation anyway because isolation is
     * established in {@code doBegin} — a computation called outside a transaction would run with
     * {@code app.current_company} unset, and {@code V4}'s {@code nullif} makes that <em>zero
     * rows</em> rather than an error. The report would be empty and nothing would say why.
     */
    @Override
    @Transactional(readOnly = true)
    public ReportModel compute(UUID projectId, ReportParameters parameters) {
        UUID companyId = CompanyContext.require();
        UUID runId = parameters.requireUuid("runId");

        ExecutionSource.RunHeader run = source.findRun(projectId, runId).orElseThrow(
                () -> new IllegalArgumentException("This project has no run with that id"));

        Totals totals = totals(projectId, runId);
        List<StatusRow> statuses = statuses(projectId, runId);

        List<ReportModel.Section> sections = new ArrayList<>();
        sections.add(resultsSection(totals, statuses));
        sections.add(testerSection(companyId, projectId, runId, totals));
        sections.add(suiteSection(projectId, runId));

        return new ReportModel(
                "Run summary — " + run.key(),
                metadata(projectId, run, totals),
                sections);
    }

    // =====================================================================
    // The model
    // =====================================================================

    private List<ReportModel.Entry> metadata(
            UUID projectId, ExecutionSource.RunHeader run, Totals totals) {

        List<ReportModel.Entry> metadata = new ArrayList<>();
        metadata.add(new ReportModel.Entry("Project", projectLabel(projectId)));
        metadata.add(new ReportModel.Entry("Run", run.key() + " — " + run.name()));
        metadata.add(new ReportModel.Entry("Recorded executions", count(totals.executions())));
        // §6e: "both execution count and distinct-case count — they differ, and conflating them
        // inflates numbers". In one run they are equal by uq_execution_run_case, and printing both
        // is what would make it visible on the day ingestion makes that untrue.
        metadata.add(new ReportModel.Entry("Distinct cases", count(totals.cases())));
        metadata.add(new ReportModel.Entry("Passed", count(totals.passed())));
        metadata.add(new ReportModel.Entry(
                "Pass rate (of recorded)", share(totals.passed(), totals.executions())));
        metadata.add(new ReportModel.Entry("Total recorded duration", duration(totals.totalMs())));
        metadata.add(new ReportModel.Entry("Aggregation policy",
                "Not applicable — one run holds at most one result per case"));
        return metadata;
    }

    private ReportModel.Section resultsSection(Totals totals, List<StatusRow> statuses) {
        String narrative = totals.executions() == 0
                ? "Nothing has been recorded against this run yet."
                : "Shares are of the " + count(totals.executions()) + " execution"
                        + (totals.executions() == 1 ? "" : "s")
                        + " recorded in this run. Cases in the run that nobody has recorded a "
                        + "result for do not appear here at all — the run's scope is not part of "
                        + "the execution facts this report is computed from.";

        if (statuses.isEmpty()) {
            return ReportModel.Section.text("Results", narrative);
        }

        List<List<String>> rows = new ArrayList<>();
        List<ReportModel.Point> points = new ArrayList<>();
        for (StatusRow status : statuses) {
            rows.add(List.of(
                    status.kind(),
                    count(status.executions()),
                    share(status.executions(), totals.executions())));
            points.add(new ReportModel.Point(status.kind(), status.executions()));
        }

        return new ReportModel.Section(
                "Results",
                narrative,
                new ReportModel.Table(List.of("Status", "Executions", "Share"), rows),
                List.of(new ReportModel.Series("Executions by status", points)));
    }

    /**
     * §6e's "per-tester progress".
     *
     * <p>Names come from {@link PeopleDirectory}, which is the reason this module's
     * {@code allowedDependencies} was widened to include {@code project} in R3 phase 1 — a report
     * listing UUIDs is a report nobody reads. An id it cannot place yields no entry by that port's
     * contract, and an execution with no {@code executed_by} at all is an ordinary state (M3's
     * ingestion path records results nobody typed), so both land in one honest row rather than being
     * dropped: dropping them would make the tester column's total disagree with the headline.
     */
    private ReportModel.Section testerSection(
            UUID companyId, UUID projectId, UUID runId, Totals totals) {

        List<TesterRow> testers = testers(projectId, runId);
        if (testers.isEmpty()) {
            return ReportModel.Section.text(
                    "Progress by tester", "No results have been recorded by anybody yet.");
        }

        Set<UUID> ids = new LinkedHashSet<>();
        testers.stream().map(TesterRow::userId).filter(Objects::nonNull).forEach(ids::add);
        Map<UUID, UserRefView> names = people.resolve(companyId, ids);

        List<List<String>> rows = new ArrayList<>();
        for (TesterRow tester : testers) {
            rows.add(List.of(
                    testerLabel(tester.userId(), names),
                    count(tester.executions()),
                    count(tester.passed()),
                    share(tester.passed(), tester.executions())));
        }

        return ReportModel.Section.of(
                "Progress by tester",
                "Every recorded execution is attributed to exactly one row, so these executions "
                        + "sum to the " + count(totals.executions()) + " in the headline.",
                new ReportModel.Table(
                        List.of("Tester", "Executions", "Passed", "Pass rate"), rows));
    }

    /**
     * Failure analysis grouped by suite — §6e's section, in the one grouping the fact log can label.
     *
     * <p>{@code suite_path} is an {@code ltree} materialised onto the fact at insert time and
     * <strong>frozen</strong> there ({@link ExecutionSource}), so this section reports where a case
     * was filed <em>when it ran</em>. That is the property that makes last quarter's report still
     * say what it said after somebody reorganises the tree, and it is worth knowing before reading a
     * path that no longer exists as a surprise.
     *
     * <p><strong>A NULL path is a row and not an omission</strong> (§96). {@code suite_path} is
     * nullable since {@code V31}, because a case may be filed nowhere and the alternative — an inner
     * join in {@link ExecutionSource} — silently drops the execution from the log entirely. Its
     * group is printed as {@value #NOT_FILED}: dropping it here would make this section's
     * executions stop summing to the headline, in the one direction nobody checks, and a reader
     * comparing the two would find the difference and not the reason.
     */
    private ReportModel.Section suiteSection(UUID projectId, UUID runId) {
        List<SuiteRow> suites = suites(projectId, runId);
        if (suites.isEmpty()) {
            return ReportModel.Section.text(
                    "Results by suite", "No results have been recorded against any suite yet.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (SuiteRow suite : suites) {
            rows.add(List.of(
                    suite.path() == null ? NOT_FILED : suite.path(),
                    count(suite.executions()),
                    count(suite.passed()),
                    count(suite.failed())));
        }

        return ReportModel.Section.of(
                "Results by suite",
                "Paths are as they were when each result was recorded, not as the tree stands now.",
                new ReportModel.Table(
                        List.of("Suite", "Executions", "Passed", "Failed"), rows));
    }

    // =====================================================================
    // The queries — execution_fact and nothing else
    // =====================================================================

    /**
     * Headline totals in one statement.
     *
     * <p>Separate from {@link #statuses} rather than summed out of it, because
     * {@code count(DISTINCT case_id)} per status does not add up to the distinct case count over the
     * whole run: a case counted under two statuses would be counted twice. It cannot happen inside
     * one run today, and deriving a number in a way that is only correct by a constraint elsewhere
     * is how it becomes wrong when that constraint moves.
     */
    private Totals totals(UUID projectId, UUID runId) {
        Object[] row = (Object[]) entities.createNativeQuery("""
                SELECT count(*)                                AS executions,
                       count(DISTINCT f.case_id)               AS cases,
                       count(*) FILTER (WHERE f.counts_as_pass) AS passed,
                       COALESCE(sum(f.duration_ms), 0)         AS total_ms
                  FROM execution_fact f
                 WHERE f.project_id = :projectId
                   AND f.run_id = :runId
                """)
                .setParameter("projectId", projectId)
                .setParameter("runId", runId)
                .getSingleResult();

        return new Totals(number(row[0]), number(row[1]), number(row[2]), number(row[3]));
    }

    /**
     * The breakdown, ordered by {@link StatusKind}'s own declaration order.
     *
     * <p>Not by count, and not alphabetically. Two run summaries from the same project are read side
     * by side, and a table whose rows move when the numbers move cannot be compared row by row. The
     * order is the enum's because {@code project.StatusKind} is where the product's fixed roll-up
     * classes are declared — reporting reads it rather than restating one, which is the whole reason
     * {@code status_kind} is on the fact log in the first place ({@code V1}: "lets reporting
     * aggregate project-custom statuses without knowing their names").
     */
    private List<StatusRow> statuses(UUID projectId, UUID runId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object result : entities.createNativeQuery("""
                SELECT f.status_kind, count(*)
                  FROM execution_fact f
                 WHERE f.project_id = :projectId
                   AND f.run_id = :runId
                 GROUP BY f.status_kind
                """)
                .setParameter("projectId", projectId)
                .setParameter("runId", runId)
                .getResultList()) {

            Object[] row = (Object[]) result;
            counts.put((String) row[0], number(row[1]));
        }

        List<StatusRow> ordered = new ArrayList<>();
        for (StatusKind kind : StatusKind.values()) {
            Long executions = counts.remove(kind.name());
            if (executions != null) {
                ordered.add(new StatusRow(kind.name(), executions));
            }
        }
        // Anything left is a kind this build does not know. The CHECK on case_status.kind makes it
        // unreachable today; it is carried rather than dropped because a report that silently omits
        // results is worse than one with a row a reader has to ask about, and the totals above would
        // no longer add up if it were dropped.
        counts.forEach((kind, executions) -> ordered.add(new StatusRow(kind, executions)));
        return ordered;
    }

    private List<TesterRow> testers(UUID projectId, UUID runId) {
        List<TesterRow> testers = new ArrayList<>();
        for (Object result : entities.createNativeQuery("""
                SELECT f.executed_by,
                       count(*)                                 AS executions,
                       count(*) FILTER (WHERE f.counts_as_pass) AS passed
                  FROM execution_fact f
                 WHERE f.project_id = :projectId
                   AND f.run_id = :runId
                 GROUP BY f.executed_by
                 ORDER BY count(*) DESC, f.executed_by NULLS LAST
                """)
                .setParameter("projectId", projectId)
                .setParameter("runId", runId)
                .getResultList()) {

            Object[] row = (Object[]) result;
            testers.add(new TesterRow(
                    row[0] == null ? null : UUID.fromString(row[0].toString()),
                    number(row[1]),
                    number(row[2])));
        }
        return testers;
    }

    private List<SuiteRow> suites(UUID projectId, UUID runId) {
        List<SuiteRow> suites = new ArrayList<>();
        for (Object result : entities.createNativeQuery("""
                SELECT f.suite_path::text,
                       count(*)                                          AS executions,
                       count(*) FILTER (WHERE f.counts_as_pass)          AS passed,
                       count(*) FILTER (WHERE f.status_kind = 'FAILED')  AS failed
                  FROM execution_fact f
                 WHERE f.project_id = :projectId
                   AND f.run_id = :runId
                 GROUP BY f.suite_path
                 ORDER BY f.suite_path
                """)
                .setParameter("projectId", projectId)
                .setParameter("runId", runId)
                .getResultList()) {

            Object[] row = (Object[]) result;
            suites.add(new SuiteRow(
                    (String) row[0], number(row[1]), number(row[2]), number(row[3])));
        }
        return suites;
    }

    // =====================================================================
    // Formatting — once, here, because the model carries text and not numbers
    // =====================================================================

    private String projectLabel(UUID projectId) {
        // The project is reachable (this module may depend on `project`) and the run's own key is
        // not (`test_run` is `execution`'s), which is why one arrives through a seam and the other
        // does not. A project that cannot be read is not a reason to fail a report that is otherwise
        // correct — R4's header is where branding and the full selection get their own treatment.
        Optional<Project> project = projects.findScopedById(projectId);
        return project.map(p -> p.getKey() + " — " + p.getName()).orElse("—");
    }

    private String testerLabel(UUID userId, Map<UUID, UserRefView> names) {
        if (userId == null) {
            return "Unattributed";
        }
        UserRefView person = names.get(userId);
        // PeopleDirectory answers nothing for an account that has since been removed. "Former
        // member" says that, where the raw id would say nothing and inventing a name would attribute
        // somebody's work to a person.
        return person == null ? "Former member" : person.displayName();
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

    /**
     * {@code 2h 14m 3s}, {@code 45s}, or an em dash when nothing was timed.
     *
     * <p>{@code duration_ms} is nullable — a result recorded by hand carries no duration — so the
     * sum is over the executions that had one and this number is not "how long the run took". The
     * metadata label says "recorded duration" for that reason.
     */
    private String duration(long totalMs) {
        if (totalMs <= 0) {
            return "—";
        }
        Duration elapsed = Duration.ofMillis(totalMs);
        long hours = elapsed.toHours();
        int minutes = elapsed.toMinutesPart();
        int seconds = elapsed.toSecondsPart();

        StringBuilder out = new StringBuilder();
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        if (hours > 0 || minutes > 0) {
            out.append(minutes).append("m ");
        }
        return out.append(seconds).append('s').toString();
    }

    /** {@code count(*)} is a {@code Long} and {@code sum(bigint)} is a {@code BigDecimal}. */
    private long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private record Totals(long executions, long cases, long passed, long totalMs) {}

    private record StatusRow(String kind, long executions) {}

    private record TesterRow(UUID userId, long executions, long passed) {}

    private record SuiteRow(String path, long executions, long passed, long failed) {}
}
