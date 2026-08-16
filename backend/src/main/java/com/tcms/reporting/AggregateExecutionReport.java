package com.tcms.reporting;

import com.tcms.project.ClassificationValueRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.StatusKind;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@code executed_at} to the microsecond and carry different statuses; under {@code WORST}
 * a tie is by construction between rows of the same kind, so the winner's identity
 * moves and no number does. The tiebreak exists to be <em>stable</em>, not meaningful
 * ({@code resolve} says so), and the cross-check fixture gives every execution a distinct timestamp
 * so the test asserts the rule rather than the coin toss.
 *
 * <h2>Two pass rates, and the cross-check is why the headline one could not be the excluded one</h2>
 *
 * <p>Quarantined cases are excluded from a <em>second</em> figure printed beside the first. Making
 * the single printed rate the excluded one was the other reading of the request and it is refused
 * twice over. §6e refuses it because a lone 95.2% cannot be told from a 95.2% with nothing
 * quarantined. The cross-check above refuses it structurally: {@code PlanStatisticsService} lives in
 * {@code execution}, which may not read {@code case_flake} at all, so an exclusion applied to these
 * counts and not to those would break the one guard §46 paid for in defects — and break it by
 * <em>design</em>, leaving nothing to distinguish it from the drift it exists to catch.
 *
 * <p>So neither {@link #totals}' headline nor {@link #statuses}' breakdown moves, and the
 * cross-check is untouched by this slice. What is added is a metadata pair, and a section naming the
 * excluded cases and the sentence a person wrote when they excluded them.
 * {@link ExecutionSource#quarantinedCases} carries the live-versus-frozen argument.
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
     * {@code AggregateExecutionCrossCheckTest} against {@code StatusKind}'s own value set so a kind
     * added to the product cannot silently fall through to the {@code ELSE} and rank as the best
     * possible result. ~~AggregateExecutionPolicyTest~~ is what this said until §206, and no class
     * of that name has ever existed: the pin was a sentence. It is a test as of §206 — which is why
     * this field is package-private rather than {@code private}, the one change that costs anything.
     * The ranking itself is argued in {@code PlanStatisticsService}: blocked is
     * worse than owed a verdict, retest worse than never touched, skipped better than either
     * because skipping is a decision.
     */
    static final List<StatusKind> WORST_FIRST = List.of(
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

    /** What the callout prints for a case whose pass and failure are the same cell — a real flake. */
    static final String WITHIN_ONE_CONFIGURATION_AND_VERSION =
            "Within one configuration and version";

    private final EntityManager entities;
    private final ProjectRepository projects;
    private final ClassificationValueRepository features;
    private final ExecutionSource source;

    AggregateExecutionReport(
            EntityManager entities,
            ProjectRepository projects,
            ClassificationValueRepository features,
            ExecutionSource source) {
        this.entities = entities;
        this.projects = projects;
        this.features = features;
        this.source = source;
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

        // Read once and used three times — the totals filter by it, the metadata counts it and the
        // section names the cases. Three reads of a live flag inside one report is how a report
        // disagrees with itself about which tests it left out.
        List<ExecutionSource.QuarantinedCase> quarantined = source.quarantinedCases(projectId);

        Totals totals = totals(
                projectId, selection,
                quarantined.stream().map(ExecutionSource.QuarantinedCase::caseId).toList());
        List<StatusRow> statuses = statuses(projectId, selection);
        // Computed once and read twice — the section prints it and the metadata flags it, and two
        // queries for one number is how a headline and its own section come to disagree.
        List<VersionRow> divergence = versionDivergence(projectId, selection);

        List<ReportModel.Section> sections = new ArrayList<>();
        sections.add(resultsSection(selection, totals, statuses));
        // Second, immediately under the headline, because §6e calls it "the single most valuable
        // section" and because a reader who stops after the totals is the reader it is written for.
        sections.add(inconsistencySection(projectId, selection, totals));
        // Third, against the section it qualifies: the callout's "Across versions" rows are a
        // subset of these, and a reader who has just been told a case disagrees across versions is
        // the one who needs to know which versions, and which of them the totals used.
        sections.add(divergenceSection(totals, divergence));
        sections.add(suiteSection(projectId, selection));
        sections.add(featureSection(projectId, selection));
        sections.add(automationSection(projectId, selection, totals));
        sections.add(retrySection(projectId, selection, totals));
        // Last, and after every section whose numbers include the rows it is about: this section
        // does not change any of them, it says which of them the second pass rate left out.
        sections.add(quarantineSection(totals, quarantined));

        return new ReportModel(
                "Aggregate execution report",
                metadata(projectId, selection, totals, divergence),
                sections);
    }

    // =====================================================================
    // The model
    // =====================================================================

    private List<ReportModel.Entry> metadata(
            UUID projectId, AggregateSelection selection, Totals totals,
            List<VersionRow> divergence) {

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
        // Both rates, always, and the original one is untouched. Replacing it with the excluded
        // figure was the other option and it is the one §6e refuses: a single 95.2% cannot be told
        // from a 95.2% with nothing quarantined, and it would put this report's per-status counts
        // out of agreement with GET /plans/{planId}/statistics — the disagreement
        // AggregateExecutionCrossCheckTest exists to make impossible, and which PlanStatisticsService
        // could not follow us into anyway, since `execution` may not read `case_flake`.
        metadata.add(new ReportModel.Entry(
                "Pass rate (excluding quarantined cases)",
                share(totals.trustedPassed(), totals.trustedExecutions())));
        metadata.add(new ReportModel.Entry(
                "Quarantined cases in this selection",
                count(totals.quarantinedCases()) + " (" + count(totals.quarantinedExecutions())
                        + " execution" + (totals.quarantinedExecutions() == 1 ? "" : "s")
                        + ", not counted in the rate above)"));
        // R8's flag, and it is printed even when it is zero. §6e asks the report to *flag*
        // divergence, and a line that appears only when there is something to say is one a reader
        // cannot tell from a build that does not compute it — the same argument that keeps every
        // status row in the breakdown. Two reports from one project also have to line up.
        metadata.add(new ReportModel.Entry(
                "Cases executed at more than one version", count(divergentCases(divergence))));
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

    /**
     * <strong>The inconsistency callout</strong> — §6e's <em>"single most valuable section"</em>:
     * cases that both passed <em>and</em> failed inside the selection, which is
     * <em>"the flaky-test and environment-instability signal, and it's invisible in any single-run
     * report."</em>
     *
     * <h2>It reads the un-collapsed rows, and that is the whole slice</h2>
     *
     * <p>Every other section in this report reads {@link #resolvedCte}, which applies the aggregation
     * policy. This one reads {@link #selectedCte}, which does not — and the difference is not a
     * refinement, it is the difference between a section that works and one that is permanently
     * empty. Under {@code LATEST}, the <strong>default</strong> and the policy nearly every report
     * will be generated under, each case has exactly one resolved row. A case cannot disagree with
     * itself. So a callout computed over the resolved set prints <em>"no case both passed and
     * failed"</em> for every project, for ever, and the failure renders as a reassuring sentence in
     * a section that looks like it is working (§47, §53, §72, §81 — the same shape, fifth time).
     *
     * <p>Read the other way round: <strong>the policy exists to hide exactly what this section is
     * about.</strong> Collapsing many results into one is how the rest of the report answers "where
     * do we stand"; this section is the one that says what was collapsed. It therefore answers
     * identically under all five policies, which
     * {@code AggregateVariabilityTest.theCalloutSurvivesTheDefaultPolicy} asserts in the direction
     * that fails against the tempting implementation.
     *
     * <h2>A pass and a BLOCK is not an inconsistency, and the slice plan's own query shape says it is</h2>
     *
     * <p>The plan proposed <em>"a {@code HAVING} over distinct {@code counts_as_pass} values"</em>.
     * That is one predicate too wide: {@code counts_as_pass} is false for {@code BLOCKED},
     * {@code SKIPPED}, {@code RETEST} and {@code UNTESTED}, so a case that passed once and was
     * blocked once would be listed as flaky. It is not — nothing failed; somebody could not run it.
     * §105 settled the general form (<em>blocked stays out of the failure count</em>) and this is that
     * rule one report over. The predicate here is <strong>at least one pass and at least one
     * {@code FAILED}</strong>, and inflating the most-quoted section of the release report is exactly
     * the direction §46 says never to fail in.
     *
     * <h2>Where the disagreement is, is a finding of its own</h2>
     *
     * <p>§6p's rule for the case timeline: <em>"pass/fail alternation within a single version and
     * configuration, which is the only scoping under which flakiness means anything. Alternation
     * across versions is just change, and reporting it as flake is a false signal."</em> A case that
     * fails on Chrome and passes on Firefox is a real finding and a completely different one from a
     * case that fails and passes on the same browser at the same version.
     *
     * <p>So the row is <em>listed</em> either way — a reader must not have to guess which cases were
     * filtered out — and the last column names which it is, resolved from a per-cell aggregate where
     * a cell is (case, configuration, case version). Collapsing the two into one number would be a
     * flake count that is wrong in whichever direction the reader assumes.
     *
     * <p>Ordered instability first, then by failures — this table is read to decide what to look at.
     */
    private ReportModel.Section inconsistencySection(
            UUID projectId, AggregateSelection selection, Totals totals) {

        List<InconsistentCase> cases = inconsistentCases(projectId, selection);
        if (cases.isEmpty()) {
            return ReportModel.Section.text("Inconsistent results", totals.executions() == 0
                    ? "No results contribute to this selection."
                    // A positive statement, not a blank table: this section saying nothing is a
                    // finding, and it must be distinguishable from the section having failed.
                    : "No case in this selection both passed and failed. Every recorded result for "
                            + "a case agrees with that case's other results, so there is no "
                            + "instability here to chase.");
        }

        Map<UUID, ExecutionSource.CaseLabel> labels = source.caseLabels(
                projectId, cases.stream().map(InconsistentCase::caseId).toList());

        List<List<String>> rows = new ArrayList<>();
        for (InconsistentCase row : cases) {
            ExecutionSource.CaseLabel label = labels.get(row.caseId());
            rows.add(List.of(
                    // A hard-deleted case prints its id rather than vanishing — featureSection's
                    // reason, and dropping the row would take the finding with it.
                    label == null ? row.caseId().toString() : label.display(),
                    count(row.executions()),
                    count(row.passed()),
                    count(row.failed()),
                    row.disagreement()));
        }

        return ReportModel.Section.of(
                "Inconsistent results",
                "Cases with at least one pass and at least one failure. This section reads every "
                        + "contributing execution and deliberately ignores the aggregation policy — "
                        + "the policy's job is to collapse exactly the disagreement listed here, so "
                        + "under it every case has one result and none of them can differ. A case "
                        + "that was blocked and later passed is not listed: nothing failed. "
                        + "Instability within one configuration and version first.",
                new ReportModel.Table(
                        List.of("Case", "Executions", "Passed", "Failed", "Disagreement"), rows));
    }

    /**
     * <strong>Version divergence</strong> — R8, and the last section §6e's aggregate report was
     * waiting for: <em>"otherwise you are averaging results for two different tests presented as
     * one."</em>
     *
     * <p>{@code execution_fact.case_version_id} is what makes it answerable at all, and it was
     * specified for exactly this — {@code V1}'s comment on {@code test_execution.case_version_id}
     * calls it <strong>THE PIN</strong>: <em>"every result knows exactly which version of the case
     * it executed — without this, editing a case silently rewrites history in every past run
     * report."</em> Two other shipped surfaces already carry the rule in prose (the case timeline's
     * contract text: <em>"blending results across versions reports a pass rate for a test whose text
     * changed"</em>), so a report that blended silently would contradict them both.
     *
     * <h2>It reads the un-collapsed rows, for {@link #inconsistencySection}'s reason exactly</h2>
     *
     * <p>{@link #selectedCte} again, and the trap is the same one R7 turned on: under {@code LATEST}
     * — the default — each case is one resolved row, one row pins one version, and a divergence
     * section computed over the resolved set reports <strong>zero divergence in every project, for
     * ever</strong>. The policy is what makes the divergence invisible; it is not what removes it.
     *
     * <h2>Divergence is wider than the callout's "Across versions" row, and deliberately</h2>
     *
     * <p>{@link #inconsistencySection} lists a case only when it both passed <em>and</em> failed, so
     * its <em>Across versions</em> rows are a strict subset of these. A case that failed at v1 and
     * failed at v2 disagrees with nothing and is still two tests averaged as one — §6e's harm is in
     * the <em>blending</em>, not in the disagreement. So the predicate here is
     * {@code count(DISTINCT case_version_id) > 1} and nothing about outcomes.
     *
     * <h2>The last column is computed, not asserted, and it is what the section is for</h2>
     *
     * <p>What divergence <em>costs</em> is a property of the policy, not of the selection, and it
     * differs in kind rather than in degree:
     *
     * <ul>
     *   <li>Under {@code ALL} every version's results reach the totals, so the headline pass rate is
     *       §6e's sentence literally — one number over two different tests.
     *   <li>Under {@code LATEST} one version survives per case, so the totals do not blend and the
     *       older version's results are in <strong>no</strong> total in this report. That is not
     *       harmless, it is a different fact, and a section that said "blended" would be lying.
     *   <li>Under {@code WORST} the surviving row is chosen by severity <em>across</em>
     *       versions, so a failure recorded against text that has since been rewritten can outrank a
     *       pass against the current text.
     *   <li>Under {@code LATEST_PER_CONFIG} both happen at once: one version per configuration, and
     *       two configurations that pinned different versions blend after all.
     * </ul>
     *
     * <p>Rather than branch on the policy and print a sentence about it, each row says whether it
     * reached the totals — an {@code EXISTS} against the resolved set — and the narrative's closing
     * sentence is chosen by whether any case has <em>more than one</em> counted version, which is
     * the fact "the totals blend versions" actually consists of. A prose claim keyed on the policy
     * name would be a second implementation of the policy, in English, in the one place a reader has
     * no way to check it.
     */
    private ReportModel.Section divergenceSection(Totals totals, List<VersionRow> divergence) {
        if (divergence.isEmpty()) {
            return ReportModel.Section.text("Version divergence", totals.executions() == 0
                    ? "No results contribute to this selection."
                    // A positive statement for inconsistencySection's reason: this section saying
                    // nothing is a finding, and must not read as the section having failed.
                    : "Every case in this selection was executed at one version of its own text, so "
                            + "no figure in this report averages results for two different tests.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (VersionRow row : divergence) {
            rows.add(List.of(
                    row.caseLabel(),
                    row.versionLabel(),
                    count(row.executions()),
                    count(row.passed()),
                    count(row.failed()),
                    row.counted() ? "Counted" : "Not counted"));
        }

        return ReportModel.Section.of(
                "Version divergence",
                "Cases the selection executed at more than one version of their own text. Results "
                        + "recorded against two versions are results for two different tests, so a "
                        + "single figure over both describes neither. Like the inconsistency "
                        + "callout, this reads every contributing execution and ignores the "
                        + "aggregation policy, which is what makes divergence invisible rather than "
                        + "what removes it. A case is listed whether or not its versions disagree — "
                        + "two failures at two versions are still two tests. "
                        + (blendsVersions(divergence)
                                ? "Under this policy more than one version of the same case reaches "
                                        + "the totals above, so those totals average results across "
                                        + "versions."
                                : "Under this policy one version of each case reaches the totals "
                                        + "above and the rest do not, so the totals average nothing "
                                        + "— but the results marked Not counted are in no figure in "
                                        + "this report except this table."),
                new ReportModel.Table(
                        List.of("Case", "Version", "Executions", "Passed", "Failed",
                                "Counted in the totals"),
                        rows));
    }

    /** How many distinct cases the divergence rows describe — the metadata flag's number. */
    private static long divergentCases(List<VersionRow> divergence) {
        return divergence.stream().map(VersionRow::caseId).distinct().count();
    }

    /**
     * Whether the resolved totals actually span more than one version of some case.
     *
     * <p>Not "is the policy ALL": {@code LATEST_PER_CONFIG} blends whenever two configurations
     * pinned different versions and does not otherwise, and {@code ALL} blends nothing when the
     * divergent rows fall outside the selection's own narrowing. The question is about the rows.
     */
    private static boolean blendsVersions(List<VersionRow> divergence) {
        Map<UUID, Long> counted = new LinkedHashMap<>();
        for (VersionRow row : divergence) {
            if (row.counted()) {
                counted.merge(row.caseId(), 1L, Long::sum);
            }
        }
        return counted.values().stream().anyMatch(versions -> versions > 1);
    }

    /**
     * <strong>The manual and automated split</strong> — §6e's <em>"manual vs automated split"</em>,
     * and it is a cross-tab because the fact log holds <em>two</em> columns that both read like the
     * answer and mean different things.
     *
     * <ul>
     *   <li>{@code automation_status} is <strong>the case's</strong> status as it stood when the
     *       result was recorded (a frozen dimension, §86) — where that test sits on the road to being
     *       automated (§6c).
     *   <li>{@code source} is <strong>the result's</strong> provenance — a {@code MANUAL} run somebody
     *       executed, or an {@code INGESTED} one a CI job posted (§6e's <em>"both manual and
     *       CI-ingested results"</em>).
     * </ul>
     *
     * <p>Picking either one and labelling the table "manual vs automated" is a report that answers a
     * question nobody asked with a number that looks like the answer to the one they did. They
     * disagree in both directions and each disagreement is real: an automated case triaged by hand
     * during a release, or a case still marked {@code MANUAL} whose results have been arriving from
     * CI for a month. The second is a data-quality signal about the repository, not a category of
     * testing — said in the narrative, because a reader who does not know that reads it as a third
     * kind of test.
     *
     * <h2>Alphabetical, not the enum's own order, and that is a constraint rather than a preference</h2>
     *
     * <p>{@link #statuses} orders by {@code StatusKind}'s declaration order for a stated reason: two
     * reports from one project are read side by side, so a table's row order must not move when its
     * numbers do. The same argument applies here and the same mechanism is unavailable —
     * {@code AutomationStatus} lives in {@code repository} and {@code RunSource} in
     * {@code execution}, neither of which this module may import at any width plan §3 permits (ERD
     * §0.2). Alphabetical on both columns delivers what that argument actually needs — an order that
     * is total, stable and independent of the data — and gives up only the enums' semantic ordering,
     * which no reader of this table is relying on. A third copy of two enums to recover it would be
     * the trade §95 and decision E both refused.
     */
    private ReportModel.Section automationSection(
            UUID projectId, AggregateSelection selection, Totals totals) {

        List<SplitRow> split = automationSplit(projectId, selection);
        if (split.isEmpty()) {
            return ReportModel.Section.text("Manual and automated", totals.executions() == 0
                    ? "No results contribute to this selection."
                    : "No contributing execution carries an automation status or a source.");
        }

        List<List<String>> rows = new ArrayList<>();
        for (SplitRow row : split) {
            rows.add(List.of(
                    row.automationStatus(),
                    row.source(),
                    count(row.executions()),
                    count(row.passed()),
                    share(row.passed(), row.executions())));
        }

        return ReportModel.Section.of(
                "Manual and automated",
                "Two dimensions, because the fact log holds two: the automation status is the "
                        + "case's, as it stood when the result was recorded, and the source is how "
                        + "that result arrived. They disagree in both directions — an automated case "
                        + "executed by hand, or a case still marked manual whose results come from "
                        + "CI — and such a row is a signal about the repository, not a category of "
                        + "testing. Shares are of each row's own executions.",
                new ReportModel.Table(
                        List.of("Automation status", "Source", "Executions", "Passed", "Pass rate"),
                        rows));
    }

    /**
     * <strong>First attempt and retries</strong> — §6e's <em>"first-attempt vs after-retry pass
     * rate"</em>, and the second section that must not read the resolved rows.
     *
     * <p>The reason is {@link #inconsistencySection}'s, in a second shape: a retry is only visible
     * where the retries still exist. Under {@code LATEST} every group holds one attempt, which is by
     * definition the first, so <em>"passed only after a retry"</em> would be zero everywhere and the
     * report would describe a project whose tests all pass first time.
     *
     * <h2>The unit is a cell, not a case, and the column heading has to say so</h2>
     *
     * <p>Attempts are ordered inside (case, configuration, case version) — §6p's scoping again: a
     * failure on Chrome followed by a pass on Firefox is not a retry, and a failure before an edit
     * followed by a pass after it is not a retry either, it is a fix. So one case can contribute
     * several groups, these counts do not sum to the distinct-case count, and the column is named
     * <em>Case, configuration and version</em> rather than "Cases" so that nobody adds it to the
     * headline. §96's rule: say it in the table, rather than let a reader find it by subtraction.
     *
     * <p>The three outcomes are exhaustive and disjoint by construction, so they sum to the number of
     * groups — which is what makes this table checkable rather than three numbers to take on trust.
     * <em>Never passed</em> includes a group that was only ever blocked; that is what the words say.
     * An attempt with no timestamp sorts <strong>last</strong>, for {@link #resolvedCte}'s reason: a
     * row nobody timed is not the earliest attempt, it is the absence of a time.
     */
    private ReportModel.Section retrySection(
            UUID projectId, AggregateSelection selection, Totals totals) {

        RetryOutcomes outcomes = retryOutcomes(projectId, selection);
        long groups = outcomes.total();
        if (groups == 0) {
            return ReportModel.Section.text("First attempt and retries", totals.executions() == 0
                    ? "No results contribute to this selection."
                    : "No contributing execution can be ordered into attempts.");
        }

        List<List<String>> rows = new ArrayList<>();
        List<ReportModel.Point> points = new ArrayList<>();
        for (Map.Entry<String, Long> outcome : outcomes.byLabel().entrySet()) {
            rows.add(List.of(
                    outcome.getKey(),
                    count(outcome.getValue()),
                    share(outcome.getValue(), groups)));
            points.add(new ReportModel.Point(outcome.getKey(), outcome.getValue()));
        }

        return new ReportModel.Section(
                "First attempt and retries",
                "Each group is one case, on one configuration, at one version — the only unit under "
                        + "which a retry means anything: a failure on one configuration followed by "
                        + "a pass on another is not a retry, and neither is a failure before an edit "
                        + "followed by a pass after it. One case can therefore appear in several "
                        + "groups, so these do not add up to the distinct-case count above. Like the "
                        + "inconsistency callout, this section ignores the aggregation policy, which "
                        + "would leave one attempt per group and no retries to count.",
                new ReportModel.Table(
                        List.of("Outcome", "Case, configuration and version", "Share"), rows),
                List.of(new ReportModel.Series("Retry outcomes", points)));
    }

    /**
     * What the second pass rate left out, and why — <strong>the face of the report
     * {@code CaseQuarantineService} said this exclusion had to be stated on.</strong>
     *
     * <p>Its header is the specification: <em>"excluding a case from a pass rate silently is how a
     * green number stops meaning anything … when gating reads this column it must say so on the face
     * of the report."</em> The reason column is the whole point of the section rather than a
     * courtesy: <em>these results were left out</em> is only actionable next to <em>why, and since
     * when</em>, and without them the only way to evaluate an exclusion is to read the test — which
     * is the work the quarantine was supposed to defer. {@code case_flake_quarantine_complete}
     * guarantees the reason is there, so this column is never blank.
     *
     * <p><strong>The section renders even when nothing is quarantined</strong>, as a sentence. An
     * absent section cannot be told from a build that does not compute one, and this report already
     * prints its divergence count and every status row at zero for that reason. It is also what
     * makes two reports of one project comparable line by line.
     *
     * <p><strong>The table is the project's live quarantines and the metadata line is the subset
     * that contributed, so the two are different numbers on purpose</strong> — the narrative says
     * which is which rather than leaving a reader to reconcile a table of seven rows against a count
     * of three. Filtering the table down to contributors was the alternative and it hides the more
     * useful of the two facts: a quarantined case with no result in this selection is a test nobody
     * is running at all, which is exactly the debt the board card exists to surface and which a
     * release document is the right place to notice.
     *
     * <p>The decider is deliberately not a column. {@code work.BoardService} already names who
     * pulled the test — that is a card about a person's decision — where this table is about the
     * numbers, and resolving people here would widen this class's dependencies to
     * {@code PeopleDirectory} for one cell. The reason is the sentence that person wrote, which is
     * the part a reader of a release document needs.
     */
    private ReportModel.Section quarantineSection(
            Totals totals, List<ExecutionSource.QuarantinedCase> quarantined) {

        if (quarantined.isEmpty()) {
            return ReportModel.Section.text("Quarantined cases",
                    "No case in this project is quarantined, so the two pass rates above are the "
                            + "same number and every recorded result counted towards both.");
        }

        String narrative = "These cases are excluded from the "
                + "\"Pass rate (excluding quarantined cases)\" figure above and from nothing else — "
                + "every other number in this report, including the headline pass rate, counts "
                + "their results in full. Quarantine is read as it stands now rather than as it "
                + "stood when each result was recorded, so a test quarantined today is excluded "
                + "from this figure for the whole selection, however old the results are. "
                + "This table lists every case quarantined in the project — all " + count(quarantined.size())
                + " of them — while " + count(totals.quarantinedCases())
                + " of those contributed " + count(totals.quarantinedExecutions())
                + " of this selection's " + count(totals.executions()) + " execution"
                + (totals.executions() == 1 ? "" : "s") + ". A case listed here with nothing in "
                + "the selection is one nobody is running at all.";

        List<List<String>> rows = new ArrayList<>();
        for (ExecutionSource.QuarantinedCase entry : quarantined) {
            rows.add(List.of(
                    entry.label().display(),
                    entry.reason(),
                    entry.quarantinedAt() == null ? "" : entry.quarantinedAt().toString()));
        }

        return new ReportModel.Section(
                "Quarantined cases",
                narrative,
                new ReportModel.Table(
                        List.of("Case", "Reason", "Quarantined at"), rows),
                List.of());
    }

    // =====================================================================
    // The queries — execution_fact and nothing else
    // =====================================================================

    /**
     * The <strong>narrowed</strong> row set as a CTE, named {@code selected} — every execution the
     * selection covers, with no policy applied.
     *
     * <p>Split out of {@link #resolvedCte} in R7, and the split is load-bearing rather than tidy.
     * Which of the two a section reads decides whether it can answer its question at all: an
     * inconsistency, a first attempt, a retry and a version divergence are all properties of the
     * executions the policy <em>discards</em>. A caller reaching for {@code resolvedCte} where it
     * wanted this one gets a section that is empty under the default policy and looks like a project
     * with nothing to report.
     *
     * <p>Three sections read this one — the inconsistency callout, the version divergence and the
     * retries — while the totals, the status breakdown, the two failure groupings and the automation
     * split read the resolved set. R8's {@link #versionDivergence} is the one query that reads
     * <em>both</em>, on purpose: what diverged comes from here, and whether the totals used it comes
     * from there.
     *
     * <p>{@code case_version_id}, {@code automation_status} and {@code source} are projected for
     * R7's three sections and R8's fourth. They cost nothing to carry: the policy window in
     * {@link #resolvedCte} passes {@code s.*} through, so the resolved set gains them too.
     */
    private String selectedCte(AggregateSelection selection) {
        return """
                WITH selected AS (
                    SELECT f.execution_id, f.case_id, f.case_version_id, f.configuration_id,
                           f.status_kind, f.counts_as_pass, f.executed_at, f.suite_path,
                           f.feature_ids, f.automation_status, f.source
                      FROM execution_fact f
                     WHERE f.project_id = :projectId
                """ + selection.where() + "\n)";
    }

    /**
     * The resolved row set as a CTE, named {@code r}, ready to be aggregated by any statement.
     *
     * <p>One string, built once, so every section narrows and collapses identically. See the class
     * header for the clause-by-clause mapping onto {@code PlanStatisticsService.resolve}.
     */
    private String resolvedCte(AggregateSelection selection) {
        String selected = selectedCte(selection);

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

    /**
     * The headline totals, and the three quarantine counts that sit beside them.
     *
     * <p>One statement rather than two, for the reason {@link #compute} gives about
     * {@code divergence}: a headline and the line that qualifies it must not come from two queries,
     * which is how a report comes to disagree with itself. The three extra aggregates are
     * {@code FILTER}s over the same resolved set, so they are counted from the rows the policy
     * actually chose and not from the selection before it collapsed.
     *
     * <p>The predicate is composed as text rather than bound as an array. An empty quarantine set is
     * the ordinary state and {@code IN ()} is not valid SQL, so the empty case is the literal
     * {@code false} — every {@code FILTER} counts nothing, the excluded figures equal the headline
     * ones, and the metadata still prints a zero. That is deliberate: a line that appears only when
     * there is something to say is one a reader cannot tell from a build that does not compute it,
     * which is this report's standing rule for the divergence count and for every status row.
     */
    private Totals totals(UUID projectId, AggregateSelection selection, List<UUID> quarantined) {
        String isQuarantined = quarantined.isEmpty()
                ? "false"
                : "r.case_id IN (:quarantinedCaseIds)";

        Query query = entities.createNativeQuery(resolvedCte(selection) + """
                SELECT count(*)                                 AS executions,
                       count(DISTINCT r.case_id)                AS cases,
                       count(*) FILTER (WHERE r.counts_as_pass) AS passed,
                       count(*) FILTER (WHERE %1$s)             AS quarantined_executions,
                       count(DISTINCT r.case_id) FILTER (
                           WHERE %1$s)                          AS quarantined_cases,
                       count(*) FILTER (
                           WHERE %1$s AND r.counts_as_pass)     AS quarantined_passed
                  FROM resolved r
                """.formatted(isQuarantined))
                .setParameter("projectId", projectId);
        selection.bind(query);
        if (!quarantined.isEmpty()) {
            query.setParameter("quarantinedCaseIds", quarantined);
        }

        Object[] row = (Object[]) query.getSingleResult();
        return new Totals(
                number(row[0]), number(row[1]), number(row[2]),
                number(row[3]), number(row[4]), number(row[5]));
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

    /**
     * Cases with at least one pass and at least one {@code FAILED}, over the <em>un-collapsed</em>
     * rows — {@link #inconsistencySection} is the argument for every clause here.
     *
     * <p>Two aggregates over the same {@code selected} set, joined: one per case (the printed
     * numbers) and one per <em>cell</em> — (case, configuration, case version) — whose only purpose is
     * {@code bool_or}, the answer to "is any single cell internally inconsistent". The per-cell pass
     * uses the same two predicates as the per-case one, so a cell cannot be judged by a rule the row
     * above it was not.
     */
    private List<InconsistentCase> inconsistentCases(UUID projectId, AggregateSelection selection) {
        Query query = entities.createNativeQuery(selectedCte(selection) + """
                , cells AS (
                    SELECT s.case_id, s.configuration_id, s.case_version_id,
                           count(*) FILTER (WHERE s.counts_as_pass)         AS passed,
                           count(*) FILTER (WHERE s.status_kind = 'FAILED') AS failed
                      FROM selected s
                     GROUP BY s.case_id, s.configuration_id, s.case_version_id
                )
                , per_case AS (
                    SELECT s.case_id,
                           count(*)                                        AS executions,
                           count(*) FILTER (WHERE s.counts_as_pass)         AS passed,
                           count(*) FILTER (WHERE s.status_kind = 'FAILED') AS failed,
                           count(DISTINCT s.configuration_id)              AS configurations,
                           count(DISTINCT s.case_version_id)               AS versions
                      FROM selected s
                     GROUP BY s.case_id
                )
                SELECT p.case_id, p.executions, p.passed, p.failed, p.configurations, p.versions,
                       bool_or(c.passed > 0 AND c.failed > 0) AS within_one_cell
                  FROM per_case p
                  JOIN cells c ON c.case_id = p.case_id
                 WHERE p.passed > 0 AND p.failed > 0
                 GROUP BY p.case_id, p.executions, p.passed, p.failed, p.configurations, p.versions
                 ORDER BY within_one_cell DESC, p.failed DESC, p.executions DESC, p.case_id
                """)
                .setParameter("projectId", projectId);
        selection.bind(query);

        List<InconsistentCase> cases = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            cases.add(new InconsistentCase(
                    UUID.fromString(row[0].toString()),
                    number(row[1]),
                    number(row[2]),
                    number(row[3]),
                    number(row[4]),
                    number(row[5]),
                    Boolean.TRUE.equals(row[6])));
        }
        return List.copyOf(cases);
    }

    /**
     * One row per (case, version) for every case the selection ran at more than one version, over
     * the <em>un-collapsed</em> rows — {@link #divergenceSection} is the argument for every clause.
     *
     * <p>{@code diverged} is the {@code HAVING} that decides who is listed and says nothing about
     * outcomes; {@code per_version} is what is printed; and the {@code EXISTS} against
     * {@code resolved} is the only place in this class where the two CTEs meet. That meeting is the
     * point: it answers "did this version reach the totals" from the resolved set itself rather than
     * from a rule about the policy written twice.
     *
     * <p>Labels are resolved in Java through {@link ExecutionSource} — {@link #featureGroups}' rule,
     * and the ordering therefore has to be Java's too, since {@code version_no} does not exist in
     * this query. Cases with the most versions first, then the most failures, because this table is
     * read to decide what to look at; versions ascending inside a case, because that is the order
     * they were written in and a divergence is read as a sequence.
     */
    private List<VersionRow> versionDivergence(UUID projectId, AggregateSelection selection) {
        Query query = entities.createNativeQuery(resolvedCte(selection) + """
                , diverged AS (
                    SELECT s.case_id
                      FROM selected s
                     GROUP BY s.case_id
                    HAVING count(DISTINCT s.case_version_id) > 1
                )
                , per_version AS (
                    SELECT s.case_id, s.case_version_id,
                           count(*)                                         AS executions,
                           count(*) FILTER (WHERE s.counts_as_pass)         AS passed,
                           count(*) FILTER (WHERE s.status_kind = 'FAILED') AS failed
                      FROM selected s
                      JOIN diverged d ON d.case_id = s.case_id
                     GROUP BY s.case_id, s.case_version_id
                )
                SELECT v.case_id, v.case_version_id, v.executions, v.passed, v.failed,
                       EXISTS (SELECT 1 FROM resolved r
                                WHERE r.case_id = v.case_id
                                  AND r.case_version_id = v.case_version_id) AS counted
                  FROM per_version v
                """)
                .setParameter("projectId", projectId);
        selection.bind(query);

        List<Object[]> raw = new ArrayList<>();
        Set<UUID> caseIds = new LinkedHashSet<>();
        Set<UUID> versionIds = new LinkedHashSet<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            raw.add(row);
            caseIds.add(UUID.fromString(row[0].toString()));
            versionIds.add(UUID.fromString(row[1].toString()));
        }
        if (raw.isEmpty()) {
            return List.of();
        }

        Map<UUID, ExecutionSource.CaseLabel> labels = source.caseLabels(projectId, caseIds);
        Map<UUID, Integer> numbers = source.caseVersionNumbers(projectId, versionIds);

        List<VersionRow> rows = new ArrayList<>();
        Map<UUID, Long> versionsPerCase = new LinkedHashMap<>();
        Map<UUID, Long> failuresPerCase = new LinkedHashMap<>();
        for (Object[] row : raw) {
            UUID caseId = UUID.fromString(row[0].toString());
            UUID versionId = UUID.fromString(row[1].toString());
            ExecutionSource.CaseLabel label = labels.get(caseId);
            rows.add(new VersionRow(
                    caseId,
                    // A case that no longer resolves prints its id rather than vanishing —
                    // inconsistencySection's rule, and dropping the row would take the finding.
                    label == null ? caseId.toString() : label.display(),
                    versionId,
                    numbers.get(versionId),
                    number(row[2]),
                    number(row[3]),
                    number(row[4]),
                    Boolean.TRUE.equals(row[5])));
            versionsPerCase.merge(caseId, 1L, Long::sum);
            failuresPerCase.merge(caseId, number(row[4]), Long::sum);
        }

        rows.sort(Comparator
                .comparingLong((VersionRow row) -> -versionsPerCase.get(row.caseId()))
                .thenComparingLong(row -> -failuresPerCase.get(row.caseId()))
                .thenComparing(VersionRow::caseLabel)
                // Total, and never consulted for two rows of one case — the id is what the label
                // was derived from, so this is the tiebreak that cannot itself tie.
                .thenComparing(row -> row.caseId().toString())
                // An unresolvable version sorts last: it is the absence of a number, not v0.
                .thenComparingInt(row -> row.versionNo() == null ? Integer.MAX_VALUE : row.versionNo())
                .thenComparing(row -> row.versionId().toString()));
        return List.copyOf(rows);
    }

    /** The cross-tab of {@link #automationSection}. Alphabetical, for the reason stated there. */
    private List<SplitRow> automationSplit(UUID projectId, AggregateSelection selection) {
        Query query = entities.createNativeQuery(resolvedCte(selection) + """
                SELECT r.automation_status, r.source,
                       count(*)                                 AS executions,
                       count(*) FILTER (WHERE r.counts_as_pass) AS passed
                  FROM resolved r
                 GROUP BY r.automation_status, r.source
                 ORDER BY r.automation_status, r.source
                """)
                .setParameter("projectId", projectId);
        selection.bind(query);

        List<SplitRow> split = new ArrayList<>();
        for (Object result : query.getResultList()) {
            Object[] row = (Object[]) result;
            split.add(new SplitRow(
                    row[0] == null ? "" : row[0].toString(),
                    row[1] == null ? "" : row[1].toString(),
                    number(row[2]),
                    number(row[3])));
        }
        return List.copyOf(split);
    }

    /**
     * Three counts over the attempt-ordered rows — {@link #retrySection} is the argument.
     *
     * <p>{@code row_number() = 1} is the first attempt <em>within a cell</em>; the outer aggregate
     * reduces each cell to two booleans and the three {@code FILTER}s over them are disjoint and
     * exhaustive, which is why the section can print shares of a total it did not have to be told.
     */
    private RetryOutcomes retryOutcomes(UUID projectId, AggregateSelection selection) {
        Query query = entities.createNativeQuery(selectedCte(selection) + """
                , attempts AS (
                    SELECT s.case_id, s.configuration_id, s.case_version_id, s.counts_as_pass,
                           row_number() OVER (
                               PARTITION BY s.case_id, s.configuration_id, s.case_version_id
                               ORDER BY s.executed_at ASC NULLS LAST, s.execution_id ASC) AS attempt
                      FROM selected s
                )
                , groups AS (
                    SELECT bool_or(a.counts_as_pass AND a.attempt = 1) AS first_passed,
                           bool_or(a.counts_as_pass)                   AS ever_passed
                      FROM attempts a
                     GROUP BY a.case_id, a.configuration_id, a.case_version_id
                )
                SELECT count(*) FILTER (WHERE g.first_passed)                       AS first_attempt,
                       count(*) FILTER (WHERE NOT g.first_passed AND g.ever_passed) AS after_retry,
                       count(*) FILTER (WHERE NOT g.ever_passed)                    AS never_passed
                  FROM groups g
                """)
                .setParameter("projectId", projectId);
        selection.bind(query);

        Object[] row = (Object[]) query.getSingleResult();
        return new RetryOutcomes(number(row[0]), number(row[1]), number(row[2]));
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

    /**
     * @param quarantinedExecutions how many of {@link #executions} belong to a case that is
     *     quarantined <em>right now</em>. Not a property of the fact — see
     *     {@link ExecutionSource#quarantinedCases} for why the flag is read live and what that costs.
     */
    private record Totals(
            long executions, long cases, long passed,
            long quarantinedExecutions, long quarantinedCases, long quarantinedPassed) {

        /** The denominator with the quarantined rows taken out. */
        long trustedExecutions() {
            return executions - quarantinedExecutions;
        }

        /** The numerator with them taken out, so the two move together or not at all. */
        long trustedPassed() {
            return passed - quarantinedPassed;
        }
    }

    private record StatusRow(String kind, long executions, long cases) {}

    private record GroupRow(String label, long executions, long failed) {}

    /**
     * One row of the inconsistency callout.
     *
     * @param withinOneCell whether some single (configuration, version) holds both the pass and the
     *     failure — §6p's <em>"the only scoping under which flakiness means anything"</em>
     */
    private record InconsistentCase(
            UUID caseId,
            long executions,
            long passed,
            long failed,
            long configurations,
            long versions,
            boolean withinOneCell) {

        /**
         * What the last column prints, and it is a classification rather than a count.
         *
         * <p>Instability wins where it is present: a case that alternates inside one cell
         * <em>and</em> also differs across configurations is a flaky test first, because that is the
         * finding that changes what somebody does next. Where it is absent, the axes that actually
         * vary are named — and at least one of them must, since a case with a single cell can only
         * disagree inside it.
         */
        String disagreement() {
            if (withinOneCell) {
                return WITHIN_ONE_CONFIGURATION_AND_VERSION;
            }
            if (configurations > 1 && versions > 1) {
                return "Across configurations and versions";
            }
            return configurations > 1 ? "Across configurations" : "Across versions";
        }
    }

    /**
     * One row of the version-divergence table — one case, at one of the versions it ran at.
     *
     * @param versionNo null when the version no longer resolves; the id is printed instead, for
     *     {@code caseLabel}'s reason one column over
     * @param counted whether this version reached the report's totals under the policy in force —
     *     computed from the resolved set, never inferred from the policy's name
     */
    private record VersionRow(
            UUID caseId,
            String caseLabel,
            UUID versionId,
            Integer versionNo,
            long executions,
            long passed,
            long failed,
            boolean counted) {

        /** {@code v2}. The number a person is shown on the case's own history screen (§35). */
        String versionLabel() {
            return versionNo == null ? versionId.toString() : "v" + versionNo;
        }
    }

    /** One row of the manual/automated cross-tab: the case's status, the result's provenance. */
    private record SplitRow(String automationStatus, String source, long executions, long passed) {}

    /** The three disjoint, exhaustive outcomes of {@link #retrySection}, in reading order. */
    private record RetryOutcomes(long firstAttempt, long afterRetry, long neverPassed) {

        long total() {
            return firstAttempt + afterRetry + neverPassed;
        }

        Map<String, Long> byLabel() {
            Map<String, Long> outcomes = new LinkedHashMap<>();
            outcomes.put("Passed on the first attempt", firstAttempt);
            outcomes.put("Passed only after a retry", afterRetry);
            outcomes.put("Never passed", neverPassed);
            return outcomes;
        }
    }
}
