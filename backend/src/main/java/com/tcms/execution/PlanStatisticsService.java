package com.tcms.execution;

import com.tcms.execution.CaseFacts.ScopedCase;
import com.tcms.execution.PlanStatisticsView.ConfigurationCountsView;
import com.tcms.execution.PlanStatisticsView.SuiteCountsView;
import com.tcms.execution.PlanStatisticsView.TrendPointView;
import com.tcms.project.CaseStatusView;
import com.tcms.project.ProjectService;
import com.tcms.project.StatusKind;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a plan stands right now (plan §6p) — the third level of the case/suite/plan idea, and the
 * one that answers <em>"are we ready?"</em>.
 *
 * <h2>Computed on read, and §6p specifies otherwise</h2>
 *
 * <p>§6p calls for a {@code PlanSnapshot} read model maintained off the same events as
 * {@code SuiteRunSummary}, updated incrementally as results land. Both tables exist in {@code V1} and
 * neither has a writer, because the {@code reporting} module that would own them does not exist.
 *
 * <p>So this recomputes from {@code test_execution} on every request, which is a deliberate deviation
 * with a stated cost and a stated benefit. The benefit: <strong>a figure recomputed per request
 * cannot go stale relative to a result recorded a second ago</strong>, which is precisely what a
 * tester checks immediately after saving one — the same argument {@link RunService} makes for the run
 * list's counts. The cost: the work is proportional to the plan's own execution count, which is
 * bounded by (cases in scope × runs of the plan) and is a few thousand rows for a plan anyone would
 * recognise. It becomes wrong at the scale §6p is written for — a nightly suite of 500 cases over a
 * year — and the migration path is to feed the rollup and read it here, with this class as the
 * definition of what the rollup must produce.
 *
 * <p>Six queries per view regardless of plan size: the gate, the plan, the scope, the executions, the
 * statuses, the configurations, plus the two {@link ExecutionViews} bulk lookups behind the blockers.
 * None is per case and none is per run.
 *
 * <h2>The scope is the denominator, and it is the plan's own</h2>
 *
 * <p>Every total here is over {@link CaseFacts#casesInPlanScope} — the <em>same</em> expansion
 * {@link CaseFacts#caseCountsByPlan} counts for the plan detail's {@code caseCount}. A statistics
 * page reporting 312 cases beside a plan header reporting 307 would be two numbers on two screens
 * with nothing to identify the wrong one.
 *
 * <p>The consequence §6p asks for follows from this rather than being special-cased: <strong>cases
 * added to the plan after the last run appear as not-run</strong> rather than silently deflating the
 * pass rate, because they are in the scope and have no execution. Coverage absorbs them, which is
 * exactly what coverage is for.
 *
 * <p>The mirror case is handled too and is easier to miss: an execution whose case has since been
 * <em>removed</em> from the scope, or soft-deleted, is dropped. Its run still holds the row — history
 * is not rewritten — but a current-state figure must not be argued from a case the plan no longer
 * covers.
 */
@Service
public class PlanStatisticsService {

    /**
     * How long since the last execution before this plan's numbers are marked stale.
     *
     * <p>Fourteen days: long enough that a plan executed weekly is never flagged, short enough that
     * §6p's dangerous case — "94% from three weeks ago, presented as current" — always is. A constant
     * rather than a project setting because nobody has asked to tune it, and a setting nobody changes
     * is a column that has to be migrated, defaulted, seeded and explained.
     */
    static final Duration STALE_AFTER = Duration.ofDays(14);

    /**
     * Severity, worst first — the order {@link AggregationPolicy#WORST} and {@link
     * AggregationPolicy#BEST} pick along, and deliberately not the declaration order of
     * {@link StatusKind}.
     *
     * <p>The two ends are uncontroversial. The middle is a judgement and is written down here so it
     * can be argued with rather than rediscovered: a <em>blocked</em> case is worse than one owed a
     * verdict, because something is known to be in the way; a case marked for <em>retest</em> is
     * worse than one never touched, because a previous result was withdrawn on purpose; and a
     * <em>skipped</em> case ranks better than either because skipping is a decision someone made,
     * not an absence. Only {@code PASSED} is better than skipped.
     */
    private static final List<StatusKind> WORST_FIRST = List.of(
            StatusKind.FAILED,
            StatusKind.BLOCKED,
            StatusKind.RETEST,
            // WIP (V32, M9) ranks with the other non-verdicts and BELOW untested: a case somebody
            // picked up and did not finish is at least as unresolved as one nobody touched, and
            // ranking it above untested would let a run look better because a tester opened a case.
            StatusKind.WIP,
            StatusKind.UNTESTED,
            StatusKind.SKIPPED,
            StatusKind.PASSED);

    private final ProjectService projects;
    private final TestPlanRepository plans;
    private final TestExecutionRepository executions;
    private final ConfigurationRepository configurations;
    private final ResultStatuses statuses;
    private final ExecutionViews views;
    private final CaseFacts cases;
    private final RollupFacts rollups;
    private final PlanCriteria criteria;
    private final Clock clock;

    public PlanStatisticsService(
            ProjectService projects,
            TestPlanRepository plans,
            TestExecutionRepository executions,
            ConfigurationRepository configurations,
            ResultStatuses statuses,
            ExecutionViews views,
            CaseFacts cases,
            RollupFacts rollups,
            PlanCriteria criteria,
            Clock clock) {

        this.projects = projects;
        this.plans = plans;
        this.executions = executions;
        this.configurations = configurations;
        this.statuses = statuses;
        this.views = views;
        this.cases = cases;
        this.rollups = rollups;
        this.criteria = criteria;
        this.clock = clock;
    }

    /**
     * One plan's statistics, or empty for any of the ways the plan can be unreachable.
     *
     * <p>The two gates are {@link PlanService}'s, borrowed unchanged and for the same reasons: the
     * project gate answers "may this caller see this project", and
     * {@link TestPlanRepository#findLiveInProject} matches the project as well as the plan, so a plan
     * id from a sibling project does not resolve through a path naming a project the caller does
     * hold.
     */
    @Transactional(readOnly = true)
    public Optional<PlanStatisticsView> statistics(
            UUID userId, UUID projectId, UUID planId, AggregationPolicy policy) {

        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> plans.findLiveInProject(project.getId(), planId))
                .map(plan -> compute(companyId, plan, policy));
    }

    private PlanStatisticsView compute(UUID companyId, TestPlan plan, AggregationPolicy policy) {
        List<ScopedCase> scope = cases.casesInPlanScope(plan.getProjectId(), plan.getId());
        Set<UUID> inScope = new HashSet<>();
        for (ScopedCase scoped : scope) {
            inScope.add(scoped.id());
        }

        Map<UUID, CaseStatusView> statusesById = statuses.byId(plan.getProjectId());

        // Where the numbers come from, and it is not the same answer for all five policies —
        // see servedFromRollup. Everything below this line is identical either way, which is the
        // property that keeps one implementation of the arithmetic rather than two.
        List<RollupFacts.PlanSnapshotRow> snapshot = servedFromRollup(policy)
                ? rollups.planSnapshot(plan.getId())
                : List.of();
        boolean fromRollup = !snapshot.isEmpty() && snapshotIsComplete(plan, inScope, snapshot);
        if (!fromRollup) {
            snapshot = List.of();
        }
        List<Row> rows = fromRollup
                ? rollupRows(plan, inScope, snapshot, statusesById)
                : rowsInScope(plan, inScope, statusesById);

        // Resolved once and reused by every breakdown below. A per-panel resolution would let the
        // headline and the by-suite table disagree about the same case under the same policy, which
        // is the class of defect §6p exists to remove.
        List<Contribution> contributions = resolve(rows, policy, statusesById);
        Set<UUID> covered = coveredCases(rows);
        Set<UUID> touched = touchedCases(rows);

        ResultCountsView counts = tally(contributions, scope.size() - touched.size());
        double coveragePercent =
                scope.isEmpty() ? 0.0 : covered.size() * 100.0 / scope.size();

        return new PlanStatisticsView(
                plan.getId(),
                plan.getKey(),
                plan.getName(),
                plan.getCurrentVersionNo(),
                policy,
                counts,
                coveragePercent,
                latestExecutionAt(rows),
                isStale(rows),
                computedAt(snapshot),
                byConfiguration(plan.getProjectId(), scope, rows, policy, statusesById),
                bySuite(scope, contributions, touched),
                blockers(companyId, contributions, statusesById),
                notRun(scope, covered),
                criteria.evaluate(
                        criteria.parse(plan.getExitCriteria()), actuals(counts, coveragePercent)),
                fromRollup ? rollupTrend(plan.getId()) : trend(rows));
    }

    /**
     * Whether {@code plan_snapshot} can answer this policy at all.
     *
     * <p><strong>Two of the five, and it is a property of the table rather than a shortfall to work
     * around.</strong> The rollup holds the latest result per case per configuration, so
     * {@link AggregationPolicy#LATEST_PER_CONFIG} is literally its grain and
     * {@link AggregationPolicy#LATEST} is one further collapse of it — the row with the greatest
     * {@code executed_at} among a case's configuration rows, which {@link #resolve} already computes
     * and which is unchanged by having pre-collapsed the input (latest-per-case over
     * latest-per-case-per-config <em>is</em> latest-per-case, and both keep the larger execution id
     * on a tie).
     *
     * <p>{@link AggregationPolicy#WORST}, {@link AggregationPolicy#BEST} and
     * {@link AggregationPolicy#ALL} each need <em>every</em> execution: the worst result a case ever
     * had is not derivable from its most recent one. They keep the live path, and the response says
     * so through a null {@code computedAt} rather than leaving a reader to discover that the same
     * screen has two staleness behaviours depending on a dropdown.
     */
    private static boolean servedFromRollup(AggregationPolicy policy) {
        return policy == AggregationPolicy.LATEST || policy == AggregationPolicy.LATEST_PER_CONFIG;
    }

    /**
     * Whether the rollup has actually been written for every case this plan has a result for.
     *
     * <h2>The policy check alone was a defect, and {@code RollupAgreementTest} caught it</h2>
     *
     * <p>"This policy <em>can</em> be served from the rollup" is not the same claim as "the rollup
     * <em>has</em> been written". A plan whose first results landed since the last scheduler tick —
     * or any plan at all on a deployment where the job has never run — has an empty
     * {@code plan_snapshot}, and reading it reports <strong>every case as not-run</strong>.
     *
     * <p>That is not the staleness this design accepts and declares through {@code computedAt}. It
     * is a plan that has been executed reporting that nothing has been executed, rendering exactly
     * like an un-started plan — the failure shape §47, §53, §72 and §81 each paid for, arriving
     * through a cache that is merely cold.
     *
     * <p><strong>Falling back is the safe direction and the fallback is not a degradation.</strong>
     * The live path is the specification (this class's header) and is what shipped for the whole of
     * M2; the rollup is an optimisation over it. So a plan the rollup cannot fully answer is served
     * exactly as it was before §101, with {@code computedAt} null — which is true, and is the
     * client's signal that these numbers are current rather than pre-aggregated.
     *
     * <p><strong>Completeness, not freshness.</strong> A snapshot holding a verdict the tester has
     * since amended is <em>stale</em>, and staleness is reported rather than avoided — chasing it
     * here would mean re-reading every execution, which is the scan the rollup exists to remove. A
     * snapshot missing a case entirely is a different thing, and it is the one that produces a wrong
     * number rather than an old one.
     */
    private boolean snapshotIsComplete(
            TestPlan plan, Set<UUID> inScope, List<RollupFacts.PlanSnapshotRow> snapshot) {

        Set<UUID> snapshotted = new HashSet<>();
        for (RollupFacts.PlanSnapshotRow row : snapshot) {
            snapshotted.add(row.caseId());
        }

        for (UUID caseId : executions.recordedCaseIdsForPlan(plan.getProjectId(), plan.getId())) {
            // Out-of-scope cases are absent from the snapshot by design — the refresh's own scope
            // predicate drops them — so counting them here would make the snapshot permanently
            // "incomplete" for any plan somebody has ever narrowed, and the rollup would silently
            // never be used again. That failure is invisible: the page stays correct and slow.
            if (inScope.contains(caseId) && !snapshotted.contains(caseId)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The age of the <strong>stalest</strong> figure on the page, or null when it was computed live.
     *
     * <p><em>Minimum</em> and not maximum, for {@code SuiteHistoryService}'s reason: the response
     * mixes rolled-up rows with a live unrecorded tail, so the newest {@code updated_at} would be a
     * claim true of one row and false of the others — false in the reassuring direction, which is the
     * one that matters on the screen §6p calls the most dangerous in the product. The oldest is the
     * only instant for which "nothing here is older than this" holds of the whole response.
     *
     * <p>Null for an empty snapshot as well as for the live policies, and both are the same
     * statement: no figure here came from a rollup, so nothing here is behind.
     */
    private static Instant computedAt(List<RollupFacts.PlanSnapshotRow> snapshot) {
        Instant oldest = null;
        for (RollupFacts.PlanSnapshotRow row : snapshot) {
            if (oldest == null || row.updatedAt().isBefore(oldest)) {
                oldest = row.updatedAt();
            }
        }
        return oldest;
    }

    /**
     * The trend line from {@code execution_fact}, aggregated in the database.
     *
     * <p>A second implementation of {@link #trend}, and it is forced rather than chosen:
     * {@code plan_snapshot} keeps one row per case and a trend is one point per <em>run</em>, so the
     * rollup path has nothing to compute it from. Computing it live beside a rolled-up headline
     * would keep the scan the rollup exists to remove <em>and</em> put the sparkline on a different
     * clock from the number beside it.
     *
     * <p>Held to the other implementation by {@code RollupAgreementTest} rather than by this
     * comment — the same device {@code SuiteScopeAgreementTest} uses for the membership union, for
     * the same reason: a duplicated computation the module boundary makes unavoidable is kept honest
     * by a test or not at all.
     */
    private List<TrendPointView> rollupTrend(UUID planId) {
        List<TrendPointView> points = new ArrayList<>();
        for (RollupFacts.PlanTrendRow row : rollups.planTrend(planId)) {
            if (row.executed() == 0) {
                continue;
            }
            points.add(new TrendPointView(
                    row.runKey(),
                    row.executedAt(),
                    Math.round(row.passed() * 1000.0 / row.executed()) / 10.0));
        }
        points.sort(Comparator.comparing(
                TrendPointView::executedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(points);
    }

    // ------------------------------------------------------------------ rows ---

    /**
     * One execution's contribution to this plan, joined to the run that holds it.
     *
     * <p><strong>Flattened out of {@link TestExecution} in §101</strong>, and that is what lets the
     * same arithmetic run over two sources. It used to wrap the entity and read {@code getStatusId},
     * {@code getCaseId} and {@code getExecutedAt} off it — which binds every computation below to a
     * row having been loaded as an object. {@code plan_snapshot} stores the resolved
     * {@link StatusKind} rather than a status id, so an entity-shaped row simply cannot be built
     * from it, and flattening is what stops there being two implementations of the five policies.
     *
     * @param kind       resolved <em>at construction</em>, by whichever source built the row: the
     *                   live path through {@link ResultStatuses#countable} against the project's own
     *                   statuses, the rollup path from the {@code status_kind} the refresh already
     *                   resolved. Both end at the same enum, which is the whole point of {@code V1}
     *                   storing a kind beside a project-configurable status.
     * @param executedAt taken from the execution rather than the run: a run started on Monday can
     *                   hold a result recorded on Thursday, and the trend line plots when the work
     *                   happened.
     * @param runId      null on a rollup row — {@code plan_snapshot} is keyed by case, not by run,
     *                   and nothing that reads a rollup row asks which run it came from. The trend,
     *                   which is the only per-run computation here, comes from
     *                   {@link RollupFacts#planTrend} on that path.
     */
    private record Row(
            UUID executionId,
            UUID caseId,
            StatusKind kind,
            Instant executedAt,
            UUID runId,
            String runKey,
            UUID configurationId,
            Instant runStartedAt) {
    }

    private List<Row> rowsInScope(
            TestPlan plan, Set<UUID> inScope, Map<UUID, CaseStatusView> statusesById) {

        List<Row> rows = new ArrayList<>();
        for (Object[] row : executions.findForPlan(plan.getProjectId(), plan.getId())) {
            TestExecution execution = (TestExecution) row[0];
            // Dropped when the case has left the plan's scope or has been soft-deleted. See the
            // class header: the row stays in its run, but a current-state figure is not argued from
            // a case the plan no longer covers.
            if (inScope.contains(execution.getCaseId())) {
                rows.add(new Row(
                        execution.getId(),
                        execution.getCaseId(),
                        kindOf(execution.getStatusId(), statusesById),
                        execution.getExecutedAt(),
                        (UUID) row[1],
                        (String) row[2],
                        (UUID) row[3],
                        (Instant) row[4]));
            }
        }
        return rows;
    }

    /**
     * The same rows, assembled from {@code plan_snapshot} plus the run checklist.
     *
     * <h2>Two sources, and the split is between what grows and what does not</h2>
     *
     * <p>{@code plan_snapshot} holds only <strong>recorded</strong> results — its refresh joins
     * {@code case_status} inner — and that is the half whose size grows without limit: every
     * execution of every case across every run of the plan, for as long as the plan is used. It is
     * the half §6p wrote the rollup for.
     *
     * <p>The <strong>unrecorded</strong> half is the checklist §42 writes at run creation, one empty
     * execution per case in scope. It is bounded by (cases in scope × configurations) and does not
     * grow with time, so it stays live — and it has to be here rather than dropped, for one reason
     * that is easy to miss and easy to ship: {@link #byConfiguration} lists <em>only configurations
     * with at least one execution</em>, so a configuration whose run has been opened and not yet
     * recorded would <strong>disappear from the matrix</strong> if this method returned recorded
     * rows alone. That is §6p's Chrome-versus-Safari failure arrived at from the third direction,
     * after the two the panel's own header already records.
     *
     * <p>Everything else survives the omission by arithmetic rather than by luck, and it is worth
     * writing down because it looks like luck: an unrecorded row contributes {@code UNTESTED}, which
     * {@link #tally} counts in {@code notRun}; without the row the case contributes nothing, is not
     * in {@code touched}, and is phantomed into {@code notRun} by the same method. One each way.
     */
    private List<Row> rollupRows(
            TestPlan plan,
            Set<UUID> inScope,
            List<RollupFacts.PlanSnapshotRow> snapshot,
            Map<UUID, CaseStatusView> statusesById) {

        List<Row> rows = new ArrayList<>(snapshot.size());

        for (RollupFacts.PlanSnapshotRow row : snapshot) {
            // The scope filter is applied by the rollup's own delete pass, and again here. Not
            // redundant: the prune runs on the scheduler's clock, so between a case leaving the
            // scope and the next tick the table still holds its row — and this screen must not
            // report a case the plan no longer covers even for fifteen minutes.
            if (!inScope.contains(row.caseId())) {
                continue;
            }
            rows.add(new Row(
                    row.executionId(),
                    row.caseId(),
                    StatusKind.valueOf(row.statusKind()),
                    row.executedAt(),
                    null,
                    null,
                    row.configurationId(),
                    null));
        }

        for (Object[] row : executions.findUnrecordedForPlan(plan.getProjectId(), plan.getId())) {
            UUID caseId = (UUID) row[1];
            if (!inScope.contains(caseId)) {
                continue;
            }
            rows.add(new Row(
                    (UUID) row[0],
                    caseId,
                    kindOf(null, statusesById),
                    null,
                    (UUID) row[2],
                    (String) row[3],
                    (UUID) row[4],
                    (Instant) row[5]));
        }

        return rows;
    }

    // ----------------------------------------------------------- aggregation ---

    /**
     * One resolved result — what a case contributes to the counts under the chosen policy.
     *
     * @param configurationId carried so {@link #byConfiguration} can slice the same resolved set
     *                        rather than resolving a second time per configuration.
     */
    private record Contribution(UUID caseId, UUID configurationId, StatusKind kind, UUID executionId) {
    }

    /**
     * The executions reduced to one contribution per case (or per case and configuration, or none of
     * the above) — the whole of what {@link AggregationPolicy} means.
     *
     * <p>Every policy but {@link AggregationPolicy#ALL} groups; the difference between them is only
     * the key they group by and the row they keep. Written as one grouping and one comparator rather
     * than five branches so that a sixth policy is a comparator, and so that no two of the five can
     * drift apart in how they handle a null status.
     */
    private List<Contribution> resolve(
            List<Row> rows, AggregationPolicy policy, Map<UUID, CaseStatusView> statusesById) {

        if (policy == AggregationPolicy.ALL) {
            return rows.stream().map(PlanStatisticsService::contribution).toList();
        }

        Comparator<Row> preference = switch (policy) {
            // Most recently executed wins. A row nobody has recorded has no executedAt and loses to
            // any row that has one — it is not a later result, it is the absence of one.
            case LATEST, LATEST_PER_CONFIG -> Comparator.comparing(
                    Row::executedAt, Comparator.nullsFirst(Comparator.naturalOrder()));
            // A recorded verdict beats an unrecorded row before severity is consulted at all —
            // the same precedence LATEST gets from nullsFirst on executedAt, and it is load-bearing
            // rather than tidiness. Without it, OPENING a run degrades the plan: the fresh
            // checklist gives every case an UNTESTED row, which outranks PASSED under WORST, so a
            // case that has passed in every run it was recorded in reads as owed a verdict. The
            // un-started work is already reported — by `coverage` and by `notRun` — and reporting
            // it a second time by hiding a case's actual worst result is how "worst" stops meaning
            // what its label says.
            case WORST -> verdictsFirst().thenComparingInt(
                    row -> -WORST_FIRST.indexOf(row.kind()));
            case BEST -> verdictsFirst().thenComparingInt(
                    row -> WORST_FIRST.indexOf(row.kind()));
            case ALL -> throw new IllegalStateException("handled above");
        };

        // Ties are real and common: three runs of a plan nobody has recorded give a case three rows
        // with no executedAt and the same kind, and under WORST or BEST every row of one kind ties
        // by construction. Without a tiebreak the winner is whichever row the database happened to
        // return first, so the SAME request could answer a different configuration in
        // `byConfiguration` between two calls. Execution id is arbitrary but stable, which is the
        // whole requirement.
        Comparator<Row> keeps = preference.thenComparing(Row::executionId);

        Map<Object, Row> winners = new LinkedHashMap<>();
        for (Row row : rows) {
            Object key = policy == AggregationPolicy.LATEST_PER_CONFIG
                    ? List.of(row.caseId(), row.configurationId())
                    : row.caseId();
            winners.merge(key, row, (held, candidate) ->
                    keeps.compare(candidate, held) > 0 ? candidate : held);
        }
        return winners.values().stream().map(PlanStatisticsService::contribution).toList();
    }

    /** Rows carrying a verdict sort above rows that do not. See the two call sites for why. */
    private static Comparator<Row> verdictsFirst() {
        return Comparator.comparing(row -> isVerdict(row.kind()));
    }

    private static Contribution contribution(Row row) {
        return new Contribution(
                row.caseId(), row.configurationId(), row.kind(), row.executionId());
    }

    /**
     * The {@link StatusKind} behind an execution — never its status <em>name</em>.
     *
     * <p>{@link ResultStatuses} makes the argument: statuses are project-configurable, so a client
     * who renamed "Blocked" to "Environment down" or added a second passing status must not fall out
     * of the counts.
     */
    private static StatusKind kindOf(UUID statusId, Map<UUID, CaseStatusView> statusesById) {
        return ResultStatuses.countable(statusId == null ? null : statusesById.get(statusId));
    }

    /** A verdict was reached — the predicate behind both coverage and the not-run list. */
    private static boolean isVerdict(StatusKind kind) {
        return kind != StatusKind.UNTESTED
                && kind != StatusKind.RETEST
                && kind != StatusKind.WIP;
    }

    /**
     * The in-scope cases carrying at least one recorded verdict, under any run and any configuration.
     *
     * <p><strong>Deliberately independent of the aggregation policy.</strong> A case is covered or it
     * is not; which of its several results is displayed is a different question. Were this derived
     * from the contributions instead, coverage would move when the reader changed the policy dropdown
     * — and coverage is the figure §6p puts beside the pass rate precisely so that the pass rate
     * cannot be read alone.
     */
    private static Set<UUID> coveredCases(List<Row> rows) {
        Set<UUID> covered = new HashSet<>();
        for (Row row : rows) {
            if (isVerdict(row.kind())) {
                covered.add(row.caseId());
            }
        }
        return covered;
    }

    /**
     * The in-scope cases some run has materialised an execution row for, recorded or not.
     *
     * <p><strong>Not the same set as {@link #coveredCases}, and confusing the two double-counts.</strong>
     * The phantom not-run rows {@link #tally} adds exist for cases that contribute <em>nothing</em>;
     * a case with an execution row nobody has recorded already contributes one, of kind
     * {@code UNTESTED}. Counting it again as a phantom made {@code total} exceed the plan's scope by
     * the number of cases sitting in an unfinished run — which is most of them, on the screen where
     * it matters most.
     */
    private static Set<UUID> touchedCases(List<Row> rows) {
        Set<UUID> touched = new HashSet<>();
        for (Row row : rows) {
            touched.add(row.caseId());
        }
        return touched;
    }

    // --------------------------------------------------------------- counting ---

    /**
     * The counts, with one not-run row added for every in-scope case that contributed nothing.
     *
     * <p>Those phantoms are what keeps {@code passed + failed + blocked + skipped + notRun == total}
     * true under all five policies, which is the arithmetic the SPA's {@code lib/results.ts} depends
     * on and the denominator toggle computes from. Under the three one-per-case policies the total
     * lands exactly on the scope size; under {@code LATEST_PER_CONFIG} and {@code ALL} it exceeds it,
     * which is the documented meaning of those two rather than an error.
     */
    private static ResultCountsView tally(List<Contribution> contributions, long unexecuted) {
        long passed = 0;
        long failed = 0;
        long blocked = 0;
        long skipped = 0;
        long notRun = unexecuted;

        for (Contribution contribution : contributions) {
            switch (contribution.kind()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case BLOCKED -> blocked++;
                case SKIPPED -> skipped++;
                case UNTESTED, RETEST, WIP -> notRun++;
            }
        }
        return new ResultCountsView(
                passed, failed, blocked, skipped, notRun,
                passed + failed + blocked + skipped + notRun);
    }

    // ------------------------------------------------------------ breakdowns ---

    /**
     * Pass rate per environment — the matrix §6p requires so that a plan passing on Chrome and
     * failing on Safari cannot average into one reassuring number.
     *
     * <p>Every row's denominator is <strong>the whole plan scope</strong>, not the cases that
     * configuration happened to cover. Two rows are otherwise not comparable: a configuration that
     * ran four cases and passed them all would show 100% beside one that ran three hundred, and the
     * "of total" half of the denominator toggle would be meaningless. The cases a configuration did
     * not touch land in its not-run bucket, which is what they are.
     *
     * <p>Only configurations with at least one execution appear. A project's configuration list is
     * its own vocabulary and can hold a dozen entries the plan was never run against; a table of
     * mostly-empty rows would bury the ones carrying results.
     *
     * <p><strong>Each row resolves the policy within its own configuration, rather than slicing the
     * globally-resolved set.</strong> That distinction is the whole panel. Slicing is the obvious
     * implementation and it silently destroys the matrix under {@code LATEST}: resolving one winner
     * per case <em>across</em> configurations puts every winner in whichever run is newest, so the
     * table collapses to a single column and the other environment <strong>disappears from the
     * screen</strong> — the precise failure §6p introduces this panel to prevent, arrived at from
     * the other direction. Caught by the first run of {@code PlanStatisticsEndpointTest}.
     */
    private List<ConfigurationCountsView> byConfiguration(
            UUID projectId,
            List<ScopedCase> scope,
            List<Row> rows,
            AggregationPolicy policy,
            Map<UUID, CaseStatusView> statusesById) {

        Map<UUID, String> names = new HashMap<>();
        for (Configuration configuration
                : configurations.findByProjectIdOrderByIsDefaultDescNameAsc(projectId)) {
            names.put(configuration.getId(), configuration.getName());
        }

        Map<UUID, List<Row>> byConfigurationId = new LinkedHashMap<>();
        for (Row row : rows) {
            byConfigurationId.computeIfAbsent(row.configurationId(), id -> new ArrayList<>())
                    .add(row);
        }

        List<ConfigurationCountsView> counted = new ArrayList<>(byConfigurationId.size());
        for (Map.Entry<UUID, List<Row>> entry : byConfigurationId.entrySet()) {
            List<Contribution> contributions = resolve(
                    entry.getValue(),
                    // Within one configuration the two are the same question, and asking the
                    // per-config policy here would group by a key that is already constant.
                    policy == AggregationPolicy.LATEST_PER_CONFIG ? AggregationPolicy.LATEST : policy,
                    statusesById);

            Set<UUID> seen = new HashSet<>();
            for (Row row : entry.getValue()) {
                seen.add(row.caseId());
            }

            counted.add(new ConfigurationCountsView(
                    // A configuration outside the project's list is unreachable — configuration_id
                    // is a NOT NULL foreign key and configurations are project-scoped — but a null
                    // label would break a required field on the wire, so it degrades to the id.
                    names.getOrDefault(entry.getKey(), String.valueOf(entry.getKey())),
                    tally(contributions, scope.size() - seen.size())));
        }
        counted.sort(Comparator.comparing(ConfigurationCountsView::configuration));
        return List.copyOf(counted);
    }

    /**
     * Per-suite breakdown over each suite's own in-scope cases.
     *
     * <p><strong>Driven by the scope, not by the executions</strong>, so a suite in the plan where
     * nothing has been run appears with all its cases not-run. That row is the one a release meeting
     * is looking for, and a breakdown assembled from the results alone would omit exactly it.
     *
     * <p>Flat, one row per suite holding an in-scope case, rather than §6p's rolled-up tree. A parent
     * whose own cases are all green while a child is red would need the tree to say so; the SPA
     * renders a flat table, and computing a rollup nothing displays would be a second definition of a
     * suite's numbers waiting to disagree with the first. Recorded as a deliberate limit, not an
     * oversight.
     */
    private static List<SuiteCountsView> bySuite(
            List<ScopedCase> scope, List<Contribution> contributions, Set<UUID> touched) {

        Map<UUID, List<Contribution>> byCase = new HashMap<>();
        for (Contribution contribution : contributions) {
            byCase.computeIfAbsent(contribution.caseId(), id -> new ArrayList<>()).add(contribution);
        }

        // LinkedHashMap over a scope already ordered by key sequence, so suites come out in the order
        // their first case does rather than in hash order.
        Map<UUID, ScopedCase> suites = new LinkedHashMap<>();
        Map<UUID, List<Contribution>> suiteContributions = new HashMap<>();
        Map<UUID, Long> suiteUnexecuted = new HashMap<>();

        // A null suiteId is a group like any other since §96 — the in-scope cases filed in no
        // suite — and it is deliberately not filtered out. Dropping it would make this breakdown
        // stop summing to the headline counts beside it, in the direction nobody checks, and the
        // reader comparing the two would find the difference and never the reason. The maps below
        // are HashMap/LinkedHashMap precisely because both accept the null key; the contract marks
        // the row's three suite fields nullable together for it.
        for (ScopedCase scoped : scope) {
            suites.putIfAbsent(scoped.suiteId(), scoped);
            suiteContributions
                    .computeIfAbsent(scoped.suiteId(), id -> new ArrayList<>())
                    .addAll(byCase.getOrDefault(scoped.id(), List.of()));
            suiteUnexecuted.merge(scoped.suiteId(), touched.contains(scoped.id()) ? 0L : 1L, Long::sum);
        }

        List<SuiteCountsView> rows = new ArrayList<>(suites.size());
        for (ScopedCase suite : suites.values()) {
            rows.add(new SuiteCountsView(
                    suite.suiteId(),
                    suite.suiteKey(),
                    suite.suiteName(),
                    tally(suiteContributions.get(suite.suiteId()),
                            suiteUnexecuted.get(suite.suiteId()))));
        }
        return List.copyOf(rows);
    }

    // --------------------------------------------------------------- the lists ---

    /**
     * The failed and blocked cases, named rather than counted — the list a release meeting reads.
     *
     * <p>Driven by the <em>resolved</em> contributions, so it agrees with the counts above it: under
     * {@code BEST} a case that failed once and later passed is green in the headline and must not
     * appear here, and under {@code WORST} the reverse.
     *
     * <p>Ordered by the pinned case's key sequence — {@link ExecutionViews#render}'s own order —
     * rather than by case priority as §6p asks. Priority is a {@code repository} column that would
     * widen the {@code CaseFacts} seam, and {@code TestExecutionSummary} has no field to display it
     * in, so sorting by it would produce an order the reader cannot account for. A deliberate
     * deviation; revisit when the summary carries priority.
     */
    private List<TestExecutionSummaryView> blockers(
            UUID companyId,
            List<Contribution> contributions,
            Map<UUID, CaseStatusView> statusesById) {

        Set<UUID> blocking = new HashSet<>();
        for (Contribution contribution : contributions) {
            if (contribution.kind() == StatusKind.FAILED
                    || contribution.kind() == StatusKind.BLOCKED) {
                blocking.add(contribution.executionId());
            }
        }
        if (blocking.isEmpty()) {
            return List.of();
        }

        // Loaded by id rather than filtered out of the rows in hand, which is what §101 changed and
        // why: a rollup row is not an entity — plan_snapshot stores an execution's ID and its
        // resolved verdict, not the row — so the live path's "filter the objects we already have"
        // has nothing to filter on the other path. One bounded query over the failed and blocked
        // ids serves both, and having one is what stops the two paths from rendering this list
        // differently.
        List<TestExecution> failing = new ArrayList<>();
        for (TestExecution execution : executions.findAllById(blocking)) {
            failing.add(execution);
        }
        return views.render(companyId, failing, statusesById);
    }

    /**
     * The in-scope cases still owed a verdict, in key order.
     *
     * <p>"Still to execute" includes a case whose execution row exists but carries no result — which
     * is every case of a run nobody has started — as well as one no run covers at all. Both are work
     * outstanding, and a panel that listed only the second would tell a tester a run they had opened
     * and not finished was complete.
     */
    private static List<PlanCaseRefView> notRun(List<ScopedCase> scope, Set<UUID> covered) {
        List<PlanCaseRefView> pending = new ArrayList<>();
        for (ScopedCase scoped : scope) {
            if (!covered.contains(scoped.id())) {
                pending.add(new PlanCaseRefView(
                        scoped.id(),
                        scoped.key(),
                        scoped.keySeq(),
                        scoped.title(),
                        scoped.suiteId(),
                        scoped.suiteName()));
            }
        }
        return List.copyOf(pending);
    }

    /**
     * Pass rate per run of this plan, oldest first — whether the plan is converging or degrading.
     *
     * <p><strong>Per run, and per <em>executed</em> case within that run</strong>, which is the one
     * percentage on this response computed against a denominator other than the scope. A run covering
     * a third of the plan would otherwise plot as a collapse rather than as a partial run, and the
     * line's whole job is the shape rather than the level.
     *
     * <p>A run with nothing recorded is omitted rather than plotted at zero: it has no pass rate, and
     * a point at the bottom of the chart is the strongest possible statement of failure.
     *
     * <p>Ignores the aggregation policy, and must: the policy resolves a case's several results
     * across runs into one, which is the opposite of what a per-run series is.
     */
    private static List<TrendPointView> trend(List<Row> rows) {

        record Accumulator(long passed, long executed, Instant at) {
        }

        Map<UUID, Accumulator> byRun = new HashMap<>();
        Map<UUID, String> keys = new HashMap<>();
        for (Row row : rows) {
            StatusKind kind = row.kind();
            if (!isVerdict(kind)) {
                continue;
            }
            keys.put(row.runId(), row.runKey());
            byRun.merge(
                    row.runId(),
                    new Accumulator(
                            kind == StatusKind.PASSED ? 1 : 0,
                            1,
                            row.executedAt() != null ? row.executedAt() : row.runStartedAt()),
                    (held, one) -> new Accumulator(
                            held.passed() + one.passed(),
                            held.executed() + one.executed(),
                            // The run's own point in time is its LAST result, not its first: a run
                            // left open for a week belongs on the chart where the work finished.
                            later(held.at(), one.at())));
        }

        List<TrendPointView> points = new ArrayList<>(byRun.size());
        for (Map.Entry<UUID, Accumulator> entry : byRun.entrySet()) {
            Accumulator run = entry.getValue();
            points.add(new TrendPointView(
                    keys.get(entry.getKey()),
                    run.at(),
                    Math.round(run.passed() * 1000.0 / run.executed()) / 10.0));
        }
        // A run with no timestamp at all sorts last: it has results but no recorded moment, so it is
        // the least placeable point rather than the earliest.
        points.sort(Comparator.comparing(
                TrendPointView::executedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(points);
    }

    // ------------------------------------------------------------- freshness ---

    private static Instant latestExecutionAt(List<Row> rows) {
        Instant latest = null;
        for (Row row : rows) {
            latest = later(latest, row.executedAt());
        }
        return latest;
    }

    /**
     * <strong>False for a plan with no results at all.</strong> That plan is un-started, not stale,
     * and the SPA already says so in plainer words through its {@code total == notRun} branch.
     * Marking it stale would put a freshness warning on the one page where freshness is not the
     * problem.
     */
    private boolean isStale(List<Row> rows) {
        Instant latest = latestExecutionAt(rows);
        return latest != null && latest.isBefore(clock.instant().minus(STALE_AFTER));
    }

    private static Instant later(Instant held, Instant candidate) {
        if (candidate == null) {
            return held;
        }
        return held == null || candidate.isAfter(held) ? candidate : held;
    }

    // -------------------------------------------------------------- criteria ---

    /**
     * The metrics this endpoint's own numbers can answer, for {@link PlanCriteria#evaluate}.
     *
     * <p>{@code pass_rate} is evaluated <strong>against the response's own counts</strong> — every
     * case in scope, not only those executed — which is what the SPA's denominator notice tells the
     * reader and why the criteria panel legitimately shows a lower figure than the "of executed"
     * headline above it. Deriving it any other way would put two percentages on one screen with
     * nothing explaining the difference, which is the §6p defect the Phase 2 browser pass already
     * found once on this page.
     *
     * <p>It therefore moves with the aggregation policy, as the counts do. That is the honest
     * behaviour and not a loophole: the policy is stated at the top of the same page and carried in
     * the URL, so a gate evaluated under {@code BEST} announces itself. A criterion pinned to one
     * policy while the counts above it followed another would be the contradiction instead.
     */
    private static Map<String, Double> actuals(ResultCountsView counts, double coveragePercent) {
        Map<String, Double> actuals = new HashMap<>();
        actuals.put("coverage", coveragePercent);
        if (counts.total() > 0) {
            actuals.put("pass_rate", counts.passed() * 100.0 / counts.total());
        }
        return actuals;
    }
}
