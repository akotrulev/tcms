package com.tcms.reporting;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names a table another module owns</strong> — the fourth
 * instance of a shape the codebase now has a name for: {@code execution.CaseFacts} reads
 * {@code repository}, {@code repository.ExecutionFacts} reads {@code execution},
 * {@code work.BoardFacts} reads both, {@code documentation.PlanFacts} reads {@code execution}.
 *
 * <p>ArchUnit permits a cross-module dependency only toward {@code tenancy}, {@code identity} and
 * {@code project} (plan §3), and this module declares {@code tenancy} alone. The projection below
 * nonetheless names {@code test_execution} and {@code test_run} ({@code execution}),
 * {@code test_case}, {@code test_case_version} and {@code suite} ({@code repository}),
 * {@code case_status} and {@code case_classification} ({@code project} / {@code repository}) —
 * seven tables across three modules. Concentrating every one of them in a single file is what makes the coupling reviewable,
 * and what makes it one file to rewrite on the day these facts arrive as events instead.
 *
 * <h2>Native SQL, and why there is no entity for {@code execution_fact} either</h2>
 *
 * <p>Same argument both sibling seams make: a JPA entity mapped over a table another module owns is
 * one table with two sets of column expectations for {@code ddl-auto: validate} to check, and two
 * places a mapping can drift. And {@code execution_fact} itself is deliberately unmapped — nothing
 * reads a single fact row as an object, every consumer aggregates, and an entity would invite the
 * row-at-a-time projection this class exists to avoid.
 *
 * <h2>RLS covers every statement here, including the writes</h2>
 *
 * <p>Each method runs inside the caller's transaction, so {@code app.current_company} has been set
 * by {@code CompanyAwareTransactionManager} in {@code doBegin}. The {@code INSERT … SELECT} carries
 * <strong>no company predicate of its own and does not need one</strong>: {@code FORCE ROW LEVEL
 * SECURITY} applies to both sides, so the {@code SELECT} can only see the open company's executions
 * and the {@code INSERT} can only write that company's rows. That is
 * {@code repository.ExecutionFacts}' property reused rather than re-derived — and, as there,
 * asserted with two companies in {@code ExecutionFactRefreshTest} rather than trusted to this
 * paragraph, because an unasserted design of this kind is indistinguishable from a leak.
 *
 * @see ExecutionFactRefreshService for which company this runs for, and how it is established
 */
@Component
class ExecutionSource {

    /**
     * Constructor-injected rather than {@code @PersistenceContext} on a field: the project's rule is
     * constructor injection everywhere, and Spring resolves this to the same shared,
     * transaction-bound proxy the annotation would have produced.
     */
    private final EntityManager entities;

    ExecutionSource(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Project every <em>recorded</em> execution in the open company into {@code execution_fact}, in
     * one statement.
     *
     * <h2>Idempotent recompute, not an event handler — and that is the slice's load-bearing choice</h2>
     *
     * <p>§6e and the ERD both describe {@code execution_fact} as "event-updated", and that is the
     * right steady state and the wrong thing to build <em>first</em>. When this was written the
     * product had <strong>zero</strong> {@code CompanyScopedEvent} implementations, no outbox
     * consumer ({@code outbox} was a {@code V1} table with no code) and {@code @Async} handlers
     * whose failures had, in {@code CompanyScopedEvent}'s own words, "nowhere to report".
     * <strong>All three are now false — round 3 #52 built the outbox and
     * {@code reporting.FactRefreshOnResultsChanged} is a consumer of it</strong> — and the paragraph
     * stands as written because the argument was never about the absence: an event-only fact log
     * therefore has <strong>no rebuild path</strong>: a handler that throws, a restart mid-dispatch,
     * or a result recorded before the handler was deployed each produce a log that is permanently
     * and silently short — and every report computed from it is then wrong in a way that renders
     * identically to a correct one. This repository has paid for that shape four times (§47's empty
     * "Needs attention" panel, §53's silent card, §72's every-run-not-started, §81's mock that had
     * shipped a feature wrong for 28 slices), and the common thread each time is that a claim with
     * no evidence behind it looks exactly like a true one.
     *
     * <p>A statement keyed on the primary key <em>is</em> the rebuild path. Landing
     * {@code ResultRecorded} later adds a handler that calls the same service and changes nothing
     * here — which is verbatim the argument {@code CaseHealthRecomputeService} already makes for
     * itself, and which is what happened: {@code execution.results_changed} arrived in round 3 #52,
     * the handler is four lines, this class is untouched, and {@code ExecutionFactScheduler} still
     * runs. The event buys latency; the schedule remains the floor.
     *
     * <h2>The {@code ON CONFLICT} set-list names seven columns, and the fifteen it omits are the point</h2>
     *
     * <p>It updates only what a re-recorded result can legitimately change — {@code status_kind},
     * {@code counts_as_pass}, {@code executed_at}, {@code executed_by}, {@code duration_ms} and the
     * two {@code release_*} columns — so an amended verdict is corrected on the next tick.
     *
     * <p><strong>Everything else is a frozen dimension, written once on insert and never again.</strong>
     * {@code automation_status} moves when a case is automated; {@code test_type_id},
     * {@code test_level_id} and {@code feature_ids} move when a case is reclassified;
     * {@code suite_id} and {@code suite_path} move when a case is filed somewhere else. A
     * {@code DO UPDATE} covering those would retroactively re-file last quarter's results under this
     * quarter's taxonomy — the aggregate report's "failures by feature" and "failures by suite"
     * sections would silently change their own history every hour, and nobody could name when. This
     * is a star schema: a fact's dimensions are as-of the fact, which is what §6e means by
     * "deliberately duplicating dimensions to avoid joins".
     *
     * <p>{@code suite_id} / {@code suite_path} are frozen for that reason and not because the plan
     * said so — it listed only the classification and automation columns. The argument is identical
     * and the safe direction is to freeze, since a frozen dimension that should have moved is one
     * statement to change and a moving dimension that should have been frozen has already destroyed
     * the history it rewrote.
     *
     * <h2>{@code suite_path} after §98, and why the column outlived the column it copied</h2>
     *
     * <p>{@code suite.path} is <strong>gone</strong> ({@code V33}) and {@code execution_fact.suite_path}
     * is not. That is deliberate on both sides: the fact log's copy is history — it says where the
     * case was filed <em>when it ran</em> — and rows written before the flattening carry dotted
     * paths describing a hierarchy that no longer exists. They are correct and must not be
     * rewritten; the migration touches no {@code execution_fact} row.
     *
     * <p>New rows still need a value, and the one written here is {@code 's' || key_seq} — which is
     * <strong>exactly the path a ROOT suite always had</strong>. {@link com.tcms.repository.Suite}
     * derived its {@code ltree} label that way (unique by construction, stable across renames, never
     * empty, and prefixed with a letter so a segment is recognisable as a suite when it turns up
     * here beside other trees), and a flat suite is a root. So the column's contents stay
     * type-correct, unique and comparable, and a report spanning the flattening prints deep paths
     * for old runs and single labels for new ones — which is the truth about how the project was
     * organised at each of those moments.
     *
     * <h2>"Recorded" is the join to {@code case_status}, and that is not an accident of the query</h2>
     *
     * <p>{@code execution_fact.status_kind} is {@code NOT NULL} while {@code test_execution.status_id}
     * is nullable, so <strong>the fact log can structurally hold only recorded executions</strong>.
     * Creating a run writes one empty execution per case in scope (§42), so "an execution row exists"
     * would file every planned case as history. The inner join to {@code case_status} <em>is</em> the
     * {@code status_id IS NOT NULL} predicate {@code ExecutionFacts} spells out separately, and it is
     * written as a join rather than as a predicate plus a left join because a fact with no
     * {@code status_kind} could not be inserted anyway.
     *
     * <p>The consequence is a constraint on every report downstream and is stated here so it is not
     * discovered in R6: <strong>coverage and not-run denominators can never come from this table.</strong>
     * They come from the plan's scope, exactly as §46 found for plan statistics.
     *
     * <h2>The suite is LEFT joined, and the inner spelling was a silently short log (§96)</h2>
     *
     * <p>The suite join is the exact opposite case and reads identically. {@code test_case.suite_id}
     * is nullable since {@code V31} — a suite is a resolvable collection and a case may be filed
     * nowhere — so {@code JOIN suite s ON s.id = c.suite_id} <strong>drops the whole fact row</strong>
     * for every unfiled case that ran. Not the {@code suite_id} column: the row. Nothing fails,
     * nothing warns, the tick reports a successful run, and every report over this table is smaller
     * than the truth by however many unfiled cases the project has.
     *
     * <p>So {@code execution_fact.suite_id} and {@code suite_path} are nullable too ({@code V31}) and
     * this is a {@code LEFT JOIN}. NULL there is a <em>fact about the execution</em> — it was run
     * against a case nobody had filed — and suite-grouped report sections carry an explicit
     * "not filed" bucket ({@code RunSummaryReport}) rather than omitting those results, which would
     * be the same short-log defect one layer up.
     *
     * <h2>The classification lateral takes {@code array_agg(...)[1]} and NOT {@code max(...)}</h2>
     *
     * <p>Not a style choice: <strong>Postgres has no {@code max(uuid)} aggregate</strong>, in 17 or
     * anywhere else, and the first draft of this statement used one. It compiles, it is the obvious
     * way to collapse a group to one value, and it fails at runtime with
     * {@code function max(uuid) does not exist} — which is the whole class of defect a native-SQL
     * seam trades away in exchange for not moving a row per execution through the application.
     *
     * <p>{@code test_type} and {@code test_level} are {@code SINGLE}-cardinality dimensions —
     * {@code V2}'s {@code case_classification_integrity} trigger refuses a second value — so the
     * group holds at most one element and the subscript is total. {@code ORDER BY} is on it anyway,
     * so that if that trigger is ever relaxed this statement keeps choosing the same value on every
     * tick instead of quietly becoming non-idempotent. {@code feature} is the one {@code MULTI}
     * dimension (§6d) and keeps the whole array; {@code COALESCE} to {@code '{}'} rather than
     * leaving NULL, because "no features" and "unknown" behave alike under a GIN containment query
     * today and only one of them is true.
     *
     * <h2>Soft-deleted cases and runs ARE projected, deliberately</h2>
     *
     * <p>{@code V1}'s own comment calls this an "append-only fact log", and this writer never
     * deletes. Filtering {@code deleted_at IS NULL} on insert while never removing what was already
     * inserted would make the log's contents a function of <em>when the job last ran relative to the
     * delete</em> — the same class of silently-short log the whole recompute design exists to
     * prevent, and undetectable from the outside. Deleting a run does not un-run it; the results
     * were recorded by somebody who typed a verdict.
     *
     * <p>The alternative reading — a soft delete should hide results from reports — is defensible and
     * it is <em>not</em> a predicate: it needs a delete pass over facts whose source has gone, and it
     * changes what "append-only" means. It belongs to R6 with the selection, not here.
     *
     * <p>Takes no clock, deliberately. The fact log carries the <em>execution's</em> timestamps and
     * never the job's — {@code execution_fact} has no {@code computed_at} column, and a report's
     * freshness is the report header's claim to make (R4), not a column here.
     *
     * @return how many fact rows were inserted or corrected. Returned for the log line: a refresh
     *         that silently touches zero rows in a company with recorded executions is the failure
     *         this job would otherwise have no symptom for.
     */
    int projectRecordedExecutions() {
        return entities.createNativeQuery("""
                INSERT INTO execution_fact (
                        execution_id, company_id, project_id, case_id, case_version_id,
                        suite_id, suite_path, plan_id, run_id, configuration_id,
                        release_version, release_sort_key, status_kind, counts_as_pass,
                        automation_status, source, executed_by, executed_at, duration_ms,
                        test_type_id, test_level_id, feature_ids)
                SELECT e.id, e.company_id, e.project_id, e.case_id, e.case_version_id,
                       c.suite_id, ('s' || s.key_seq)::ltree, r.plan_id, e.run_id, r.configuration_id,
                       e.release_version, e.release_sort_key, st.kind, st.counts_as_pass,
                       c.automation_status, r.source, e.executed_by, e.executed_at, e.duration_ms,
                       cls.test_type_id, cls.test_level_id, cls.feature_ids
                  FROM test_execution e
                  JOIN test_run     r  ON r.id  = e.run_id
                  JOIN test_case    c  ON c.id  = e.case_id
                  LEFT JOIN suite   s  ON s.id  = c.suite_id
                  JOIN case_status  st ON st.id = e.status_id
                  LEFT JOIN LATERAL (
                           SELECT (array_agg(cc.value_id ORDER BY cc.value_id)
                                       FILTER (WHERE d.key = 'test_type'))[1]  AS test_type_id,
                                  (array_agg(cc.value_id ORDER BY cc.value_id)
                                       FILTER (WHERE d.key = 'test_level'))[1] AS test_level_id,
                                  COALESCE(
                                      array_agg(cc.value_id ORDER BY cc.value_id)
                                          FILTER (WHERE d.key = 'feature'),
                                      '{}'::uuid[]) AS feature_ids
                             FROM case_classification cc
                             JOIN classification_dimension d ON d.id = cc.dimension_id
                            WHERE cc.case_id = c.id
                       ) cls ON true
                    ON CONFLICT (execution_id) DO UPDATE
                   SET status_kind      = EXCLUDED.status_kind,
                       counts_as_pass   = EXCLUDED.counts_as_pass,
                       executed_by      = EXCLUDED.executed_by,
                       executed_at      = EXCLUDED.executed_at,
                       duration_ms      = EXCLUDED.duration_ms,
                       release_version  = EXCLUDED.release_version,
                       release_sort_key = EXCLUDED.release_sort_key
                """)
                .executeUpdate();
    }

    /**
     * Re-derive {@link com.tcms.repository.HealthSignal#FAILING_REPEATEDLY} for every live case in
     * the open company, from the fact log this class just filled.
     *
     * <h2>This is the first time a SECOND module writes {@code case_health}, and that is the slice</h2>
     *
     * <p>{@code repository.ExecutionFacts}' header already prescribes the rule — <em>"a signal's
     * owner writes its own column and no others; the row is shared, the columns are not"</em> — and
     * until now nothing exercised it, because {@code repository} was the only writer. So the
     * {@code ON CONFLICT} names exactly two columns: {@code failing_repeatedly}, which this owns,
     * and {@code computed_at}, which means "somebody looked" and is deliberately shared.
     *
     * <p>An {@code EXCLUDED.*} set-list here would blank {@code never_executed} and
     * {@code long_unexecuted} on every tick — {@code repository}'s two signals, computed correctly
     * an hour ago, silently reset to their column defaults by a job in another module. It would
     * present as a defect in the case-health recompute, which would be innocent.
     *
     * <h2>FAILED, not "did not pass" — the difference is a whole category of case</h2>
     *
     * <p>The tempting predicate is {@code NOT counts_as_pass}, because the column is right there and
     * it is what an aggregate report's pass rate uses. It is wrong for this signal: {@code BLOCKED},
     * {@code SKIPPED}, {@code RETEST} and {@code UNTESTED} all count as not-a-pass, so a case that
     * has been <em>blocked</em> three times running — nobody could run it, nothing failed — would be
     * reported as failing repeatedly. §6o's wording is "failed the last N executions", and the
     * dashboard tile it lights says <em>Failing repeatedly</em>. A blocked case is a different
     * problem with a different fix, and telling a lead their test is broken when their environment
     * is broken sends them to the wrong place.
     *
     * <p>{@code status_kind} rather than the status's name, because {@code kind} is precisely what
     * {@code V1}'s comment says it is for: <em>"lets reporting aggregate project-custom statuses
     * without knowing their names"</em>. A project that renames Failed to "Broken" still raises the
     * signal.
     *
     * <h2>The window must be FULL, or "failing repeatedly" means "failed once"</h2>
     *
     * <p>The count is compared to the window rather than to the number of rows found, so a case with
     * only two recorded results never raises a three-deep signal. That matters most for a young
     * project, where every case has one result: with a "all of what we have" reading, the first
     * failure anybody records lights the tile, and the panel reads as a repository in crisis on the
     * day it is created.
     *
     * <p>Nothing is needed to express "at least N" separately — the failure count cannot exceed the
     * number of rows the inner {@code LIMIT} returned, so equality with the window implies both.
     *
     * <h2>Ordering, and why it is not just {@code executed_at}</h2>
     *
     * <p>{@code executed_at} is nullable in the fact log (M3's ingestion path is a second writer of
     * {@code test_execution} and is not obliged to stamp it), so {@code NULLS LAST} keeps an
     * undated fact from displacing real history at the head of the window. {@code execution_id} is
     * the tie-break, so two results recorded in the same microsecond resolve the same way on every
     * tick — without it this job is not idempotent in exactly the case where a CI run posts a batch.
     * The read is served by {@code execution_fact_case_idx} {@code (company_id, case_id,
     * executed_at DESC)}, which {@code V1} has maintained on an empty table since M0.
     *
     * <h2>Live cases only — and here the soft delete DOES apply</h2>
     *
     * <p>The opposite call from {@link #projectRecordedExecutions()}, and the two are consistent
     * rather than in tension. The fact log is history and history does not un-happen; {@code
     * case_health} is a worklist about cases that exist, and {@code CaseHealthRecomputeTest} already
     * pins that a deleted case gets no row at all — "a signal with nothing to navigate to".
     *
     * @param window how many of the newest recorded results must all be failures
     * @param now    stamped into {@code computed_at} — what tells the dashboard "nobody has looked"
     *               apart from "nothing is wrong" (see {@code CaseHealth}'s header)
     * @return how many cases were re-derived
     */
    /**
     * The key and name of one run, or empty when this project has no such run.
     *
     * <h2>Why a seam method exists for two strings</h2>
     *
     * <p>{@code execution_fact} carries {@code run_id} and nothing else about the run, so a run
     * summary computed from the fact log alone can only title itself with a UUID — and, far worse,
     * <strong>cannot tell a mistyped run id from a run nobody has recorded a result for</strong>.
     * Both produce zero fact rows, and a report reading "0 executions" for a run that does not exist
     * is this repository's most-repeated defect shape: a claim with no evidence behind it renders
     * identically to a true one (§47, §53, §72, §81). One existence check turns it into a refusal.
     *
     * <p>It is here rather than in {@link RunSummaryReport} because this class's header states the
     * rule: this is the one file in the module that names a table another module owns. Reporting may
     * not depend on {@code execution} at any width of {@code allowedDependencies} that plan §3
     * permits, so the alternative was not a nicer import — it was a second file with the same
     * problem.
     *
     * <h2>A soft-deleted run is still reported on</h2>
     *
     * <p>No {@code deleted_at} predicate, which is {@link #projectRecordedExecutions()}'s call
     * rather than a second opinion: deleting a run does not un-run it, its results are already in
     * the fact log by that argument, and a report that named the run yesterday and refuses it today
     * would make a signed-off release document unreproducible — the one property §6e's determinism
     * bullet asks for by name.
     *
     * @param projectId bounds the lookup, so a run in a sibling project of the same company reads as
     *     absent rather than as somebody else's title on this project's report
     */
    Optional<RunHeader> findRun(UUID projectId, UUID runId) {
        List<?> rows = entities.createNativeQuery("""
                SELECT r.key, r.name
                  FROM test_run r
                 WHERE r.id = :runId
                   AND r.project_id = :projectId
                """)
                .setParameter("runId", runId)
                .setParameter("projectId", projectId)
                .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(new RunHeader((String) row[0], (String) row[1]));
    }

    /** What a report may say about a run without leaving the fact log for anything else. */
    record RunHeader(String key, String name) {}

    /**
     * Display keys and titles for the cases a report is about to name — <strong>the slice
     * {@link RunSummaryReport}'s header said it was waiting for.</strong>
     *
     * <p>That header states the problem exactly: <em>"a list of forty UUIDs is not the section §6e
     * describes, so this report groups failures by suite, which the fact log can name, and the
     * per-case list waits for the slice that decides where case labels come from."</em> R7's
     * inconsistency callout <em>is</em> per case — §6e's <em>"cases that both passed and failed
     * within the selection"</em> cannot be grouped into anything else — so the decision could not be
     * deferred again, and this is it: <strong>here, in the seam, exactly where {@link #findRun} is
     * and for the same stated reason.</strong>
     *
     * <p>A star schema duplicates the dimensions it aggregates <em>by</em>, not the labels of the
     * rows it aggregates, so {@code execution_fact} carries {@code case_id} and nothing else about
     * the case. Adding {@code case_key} to it was the alternative and is worse in two ways: a key is
     * mutable (§6t reallocates one on a clone) and a title is edited constantly, so freezing either
     * into an append-only log means old rows print names that no longer exist, and the log grows a
     * column no aggregate ever groups by.
     *
     * <p><strong>No {@code deleted_at} predicate</strong>, which is {@link #findRun}'s call rather
     * than a second opinion: deleting a case does not un-run it, its results are already in the fact
     * log, and a report that named the case yesterday and prints a bare UUID today would make a
     * signed-off release document unreproducible — §6e's determinism bullet, by name.
     *
     * @param projectId bounds the lookup, so a case in a sibling project of the same company cannot
     *     put its title on this project's report. RLS scopes to the company and no further, and this
     *     module has no {@code project_id}-aware repository to borrow the predicate from.
     * @param caseIds the ids to label; an empty collection is not queried at all
     * @return labels by id, missing entries for any id that no longer resolves — the caller prints
     *     the id rather than dropping the row, for {@link AggregateExecutionReport}'s stated reason
     */
    Map<UUID, CaseLabel> caseLabels(UUID projectId, Collection<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows = entities.createNativeQuery("""
                SELECT c.id, c.key, c.title
                  FROM test_case c
                 WHERE c.project_id = :projectId
                   AND c.id IN (:caseIds)
                """)
                .setParameter("projectId", projectId)
                .setParameter("caseIds", caseIds)
                .getResultList();

        Map<UUID, CaseLabel> labels = new LinkedHashMap<>();
        for (Object result : rows) {
            Object[] row = (Object[]) result;
            labels.put(
                    UUID.fromString(row[0].toString()),
                    new CaseLabel((String) row[1], (String) row[2]));
        }
        return Map.copyOf(labels);
    }

    /** What a report may say about a case. Its display key (§6t) and its current title. */
    record CaseLabel(String key, String title) {

        /** {@code TC-14 — Checkout with an expired card}. One cell of one table. */
        String display() {
            return key + " — " + title;
        }
    }

    /**
     * Version <em>numbers</em> for the case versions a report is about to distinguish between —
     * R8's half of {@link #caseLabels}, and the third method to sit here for the same stated reason.
     *
     * <p>{@code execution_fact.case_version_id} is <strong>the pin</strong> — {@code V1}'s own comment
     * on {@code test_execution.case_version_id} says so in those words: <em>"every result knows
     * exactly which version of the case it executed — without this, editing a case silently rewrites
     * history in every past run report"</em>. It is a UUID, and a UUID is unusable as the thing a
     * divergence section is <em>about</em>: <em>"executed at 6f2c… and at a1b9…"</em> tells a reader
     * that two versions exist and nothing whatever about which came first, which is the only fact
     * that makes the divergence readable. {@code version_no} is the number the product already shows
     * a person on the case's own history screen (§35), so this is the label they can act on.
     *
     * <p>{@code execution_fact} grows no {@code case_version_no} column, for
     * {@link #caseLabels}' first reason and one stronger: unlike a key or a title, the number is
     * <strong>immutable</strong> and could therefore be frozen honestly — but a star schema
     * duplicates the dimensions it aggregates <em>by</em>, and nothing groups by version number.
     * The id is the dimension; the number is a label on it.
     *
     * <p><strong>No {@code deleted_at} predicate</strong> and <strong>bounded by {@code project_id}</strong>
     * — {@link #findRun}'s and {@link #caseLabels}' calls rather than second opinions, and the join
     * to {@code test_case} is the only route to a project bound, since {@code test_case_version}
     * carries {@code company_id} and no {@code project_id}. RLS scopes to the company and no
     * further.
     *
     * @param versionIds the ids to number; an empty collection is not queried at all
     * @return numbers by id, missing entries for any id that no longer resolves — the caller prints
     *     the id rather than dropping the row, for {@link AggregateExecutionReport}'s stated reason
     */
    Map<UUID, Integer> caseVersionNumbers(UUID projectId, Collection<UUID> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows = entities.createNativeQuery("""
                SELECT v.id, v.version_no
                  FROM test_case_version v
                  JOIN test_case c ON c.id = v.case_id
                 WHERE c.project_id = :projectId
                   AND v.id IN (:versionIds)
                """)
                .setParameter("projectId", projectId)
                .setParameter("versionIds", versionIds)
                .getResultList();

        Map<UUID, Integer> numbers = new LinkedHashMap<>();
        for (Object result : rows) {
            Object[] row = (Object[]) result;
            numbers.put(UUID.fromString(row[0].toString()), ((Number) row[1]).intValue());
        }
        return Map.copyOf(numbers);
    }

    int recomputeFailingRepeatedly(int window, Instant now) {
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id, failing_repeatedly, computed_at)
                SELECT c.id, c.company_id, c.project_id, recent.failing, :now
                  FROM test_case c
                  LEFT JOIN LATERAL (
                           SELECT count(*) FILTER (WHERE f.status_kind = 'FAILED')
                                      = CAST(:window AS bigint) AS failing
                             FROM (SELECT ef.status_kind
                                     FROM execution_fact ef
                                    WHERE ef.case_id = c.id
                                    ORDER BY ef.executed_at DESC NULLS LAST, ef.execution_id DESC
                                    LIMIT CAST(:window AS bigint)) f
                       ) recent ON true
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET failing_repeatedly = EXCLUDED.failing_repeatedly,
                       computed_at        = EXCLUDED.computed_at
                """)
                .setParameter("window", window)
                .setParameter("now", now)
                .executeUpdate();
    }

    /**
     * Re-derive every case's flake evidence in the open company into {@code case_flake} —
     * <strong>M3 slice 6, and §6l's flakiness rule computed rather than described.</strong>
     *
     * <h2>Why this is here and not in {@code repository.ExecutionFacts}, where §120's pair went</h2>
     *
     * <p>§120 put the automation-drift pair in {@code repository} and gave the deciding reason: drift
     * needs {@code test_case.automation_status}, which is not on the star schema and should not be
     * (R7's argument about mutable case columns), so it had to read {@code test_execution}.
     *
     * <p><strong>Flake needs nothing but executions</strong> — {@code case_id},
     * {@code configuration_id}, {@code case_version_id}, {@code executed_at} and the outcome — and
     * the fact log carries all five. That makes this exactly the shape of
     * {@link #recomputeFailingRepeatedly}, which already writes a case-level read model from here,
     * and it buys the property that matters most: <strong>R7's inconsistency callout reads the same
     * rows.</strong> {@code AggregateExecutionReport.inconsistentCases} and this statement answer
     * nearly the same question, and two implementations over two different tables would disagree
     * the day somebody changes what a recorded execution is — with both looking correct.
     *
     * <h2>A cell is {@code (case, configuration, case version)}, and it is R7's cell</h2>
     *
     * <p>Straight out of §6l: <em>"flakiness — pass/fail alternation within a single version and
     * configuration, which is the only scoping under which flakiness means anything. Alternation
     * across versions is just change, and reporting it as flake is a false signal."</em> The
     * {@code PARTITION BY} is that sentence. Without the {@code case_version_id} term, a case that
     * passed at v2 and fails at v3 — the ordinary shape of a test catching a real regression after
     * an edit — is scored as a flake, and the one case the signal most needs to leave alone is the
     * one it reports loudest.
     *
     * <p>{@code AggregateExecutionReport.inconsistentCases} groups by the same triple for
     * {@code within_one_cell}. That is not a coincidence to be tidied away into a shared helper: the
     * two live in different modules and read through different seams, and the agreement is asserted
     * by test rather than by construction, exactly as R6's cross-check is.
     *
     * <h2>Non-outcomes are removed BEFORE the sequence is formed, which is not the same as ignoring
     * them</h2>
     *
     * <p>The {@code WHERE} keeps only rows that are a pass or a {@code FAILED}. {@code BLOCKED},
     * {@code SKIPPED}, {@code RETEST} and {@code UNTESTED} never enter the window function's input,
     * so two passes with a blocked run between them are <em>adjacent</em>.
     *
     * <p>The alternative — leaving them in and treating "not a pass" as the other value — is the
     * implementation anybody would write first, and it scores a perfectly stable test at
     * {@code 1.0}: pass, blocked, pass reads as two alternations out of two transitions. This is
     * R7's <em>"a pass and a BLOCK is not an inconsistency"</em> rule applied to a sequence instead
     * of to a set, and {@link #recomputeFailingRepeatedly}'s {@code FAILED}-not-{@code NOT
     * counts_as_pass} argument in its third shape. It is the mutation this statement most needs.
     *
     * <h2>{@code status_kind = 'PASSED'}, NOT {@code counts_as_pass} — this is the THIRD
     * implementation of §6l's sentence, and the two that share its name must agree</h2>
     *
     * <p>{@code execution.CaseHistoryService.flakiness} has computed this since §49, for §6p's
     * per-case timeline, from the same §6l sentence and under the same name. It groups by
     * {@code (version, configuration)}, keeps only {@code PASSED} and {@code FAILED}, counts
     * transitions over adjacent pairs, and answers null when there is no pair — the same
     * computation this statement performs in SQL over the fact log.
     *
     * <p><strong>{@code kind} and {@code counts_as_pass} are independent columns on
     * {@code case_status}</strong>, so a project that defines "Passed with known issues" as
     * {@code kind = 'RETEST', counts_as_pass = true} makes the two spellings disagree: that row is a
     * pass to one and not a verdict at all to the other. On the six seeded defaults they agree
     * exactly, which is what makes this the kind of divergence that <em>ships</em> — correct in
     * every test and in every project that has not customised its status vocabulary, wrong in the
     * first one that has, and presenting as a case page and a worklist quoting different numbers
     * with no way to tell which is right.
     *
     * <p>So this takes {@code CaseHistoryService}'s spelling rather than R7's, and the choice is
     * about which question is being answered rather than which column is nearer. Two computations
     * that both answer <em>"how flaky is this case"</em> must not disagree; R7's callout answers a
     * different one — <em>did this case both pass and fail inside this selection</em> — where
     * {@code counts_as_pass} is the spelling consistent with the pass rate printed beside it.
     *
     * <p>The two remaining differences from {@code CaseHistoryService} are deliberate and are
     * differences of <em>scope</em> rather than of rule. That one is unbounded and scores from a
     * single pair, because it renders the number directly above the sequence it came from, where a
     * reader can see it was drawn from two results. This one is windowed and refuses to score below
     * the evidence floor, because a worklist row carries no such context.
     *
     * <h2>No {@code source} predicate — flakiness is a property of results, not of the door</h2>
     *
     * <p>Deliberately unlike §120's pair, which filters {@code source = 'INGESTED'} because that
     * signal is literally about whether the CI pipeline is posting. A manual test that alternates
     * pass and fail on one configuration at one version is genuinely flaky, and R7's callout — the
     * statement this one must agree with — has no source predicate either.
     *
     * <h2>{@code transitions}, not {@code executions}, is the denominator</h2>
     *
     * <p>A case with six results spread across six cells has six executions and <em>zero</em>
     * transitions: nothing in it ever ran twice under the same conditions, so there is no evidence
     * about stability at all. Dividing by the execution count would score that case {@code 0.0} —
     * "evidently stable" — on evidence that says nothing, which is the confident zero in its most
     * misleading form, because the case looks like the ones that really are stable.
     *
     * <h2>The score is NULL below the floor, and the row is written anyway</h2>
     *
     * <p>Null is <em>not enough evidence to say</em>; {@code 0.0} is <em>evidently stable</em>. The
     * counts are stored either way, so the screen can show why there is no score rather than an
     * unexplained blank — and a case with no qualifying executions at all still gets a row with
     * zeroes and a fresh {@code computed_at}, which is what tells "nobody has looked" from "we
     * looked and there is nothing".
     *
     * <h2>The {@code ON CONFLICT} names seven columns and never the four quarantine ones</h2>
     *
     * <p>{@link #recomputeFailingRepeatedly}'s rule, and the first time it binds two writers of one
     * <em>table</em> rather than of one row across modules. A set-list of {@code EXCLUDED.*} here
     * would release every quarantined test on the next tick — silently, in the safe-looking
     * direction, and presenting as a board whose quarantine column empties itself overnight.
     *
     * <h2>Ordering inside a cell, and why {@code execution_id} is in it</h2>
     *
     * <p>{@code executed_at} ascending, because a sequence is read forwards; {@code execution_id} as
     * the tie-break for {@link #recomputeFailingRepeatedly}'s reason exactly — two results recorded
     * in the same microsecond must resolve the same way on every tick, or this job is not idempotent
     * in precisely the case where a CI run posts a batch, and the score would flicker between two
     * values with no input changing. Undated facts are excluded outright rather than sorted last:
     * a row with no {@code executed_at} has no position in a sequence, and giving it one invents the
     * adjacency the whole computation is about.
     *
     * @param cutoff the oldest execution the window admits. Passed in rather than computed here so
     *     the caller owns the clock and a test can put the boundary where it needs it — §120's
     *     signature, for §120's reason.
     * @param minimumTransitions the evidence floor; below it {@code flake_score} is null
     * @param now stamped into {@code computed_at}
     * @return how many cases were re-derived — every live case in the company, logged rather than
     *     acted on, for the reason {@code ExecutionFactRefreshService} states
     */
    int recomputeFlakeScores(Instant cutoff, int minimumTransitions, Instant now) {
        return entities.createNativeQuery("""
                WITH outcomes AS (
                    SELECT ef.case_id,
                           ef.configuration_id,
                           ef.case_version_id,
                           ef.executed_at,
                           ef.status_kind = 'PASSED' AS passed,
                           lag(ef.status_kind = 'PASSED') OVER (
                               PARTITION BY ef.case_id, ef.configuration_id, ef.case_version_id
                               ORDER BY ef.executed_at, ef.execution_id) AS previous_passed
                      FROM execution_fact ef
                     WHERE ef.executed_at IS NOT NULL
                       AND ef.executed_at >= :cutoff
                       AND ef.status_kind IN ('PASSED', 'FAILED')
                )
                , per_case AS (
                    SELECT o.case_id,
                           count(*)                                                AS executions,
                           count(*) FILTER (
                               WHERE o.previous_passed IS NOT NULL)                AS transitions,
                           count(*) FILTER (
                               WHERE o.previous_passed IS NOT NULL
                                 AND o.previous_passed <> o.passed)                AS alternations,
                           count(DISTINCT (o.configuration_id, o.case_version_id)) AS cells,
                           max(o.executed_at) FILTER (
                               WHERE o.previous_passed IS NOT NULL
                                 AND o.previous_passed <> o.passed)                AS last_alternation_at
                      FROM outcomes o
                     GROUP BY o.case_id
                )
                INSERT INTO case_flake (
                        case_id, company_id, project_id,
                        executions, transitions, alternations, cells,
                        flake_score, last_alternation_at, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       coalesce(p.executions, 0),
                       coalesce(p.transitions, 0),
                       coalesce(p.alternations, 0),
                       coalesce(p.cells, 0),
                       CASE WHEN coalesce(p.transitions, 0) >= CAST(:minimumTransitions AS bigint)
                            THEN round(p.alternations::numeric / p.transitions, 4)
                            END,
                       p.last_alternation_at,
                       :now
                  FROM test_case c
                  LEFT JOIN per_case p ON p.case_id = c.id
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET executions          = EXCLUDED.executions,
                       transitions         = EXCLUDED.transitions,
                       alternations        = EXCLUDED.alternations,
                       cells               = EXCLUDED.cells,
                       flake_score         = EXCLUDED.flake_score,
                       last_alternation_at = EXCLUDED.last_alternation_at,
                       computed_at         = EXCLUDED.computed_at
                """)
                .setParameter("cutoff", cutoff)
                .setParameter("minimumTransitions", minimumTransitions)
                .setParameter("now", now)
                .executeUpdate();
    }

    // =========================================================================
    // The M2 rollups (§6p) — §101
    // =========================================================================

    /**
     * Insert one {@code suite_run_summary} row per {@code (run, suite, configuration)} the open
     * company has an execution for, capturing the suite's membership <em>as it stands now</em>.
     *
     * <h2>{@code ON CONFLICT DO NOTHING}, and the freeze is structural rather than a set-list</h2>
     *
     * <p>An existing row's membership is <strong>never</strong> revisited. Counts are corrected by
     * {@link #correctSuiteRunCounts()}, which tallies over the snapshot this statement stored, so
     * the two frozen columns — {@code membership_snapshot} and {@code suite_version_no} — are
     * unreachable from the correcting statement rather than merely omitted from it.
     *
     * <p>That is deliberate, and it is the one place this class departs from
     * {@link #projectRecordedExecutions()}'s shape. There, fifteen frozen columns are held by an
     * {@code ON CONFLICT} set-list naming the other seven — correct, and held only by a reviewer
     * noticing which columns are absent. Here the freeze is a property of the statement's shape.
     *
     * <h2>Why membership must freeze at all</h2>
     *
     * <p>A run's executions are fixed when the run is created (§42), so <em>which cases ran</em>
     * cannot move. <strong>Which suites claim those cases moves every time somebody adds a tag.</strong>
     * A recompute that re-derived membership on every tick would therefore rewrite last March's pass
     * rate: a case tagged {@code checkout} today would join the March run's Checkout summary, whose
     * total goes 43 → 44 and whose percentage moves — with both numbers looking equally correct and
     * nobody able to name when it changed.
     *
     * <p>§6p settled this before the table had a writer, as open question <strong>#32</strong>:
     * as-executed membership is the default, <em>"the run shows the cases the suite held when it
     * ran; a later suite edit cannot retroactively change a past run's coverage"</em>.
     * {@code membership_snapshot} is the {@code V1} column that exists to hold it, and this is the
     * statement that fills it. It is also §86's frozen-dimension argument reaching a second table: a
     * frozen dimension that should have moved is one statement to change, while a moving dimension
     * that should have been frozen has already destroyed the history it rewrote.
     *
     * <h2>Three arms, and {@code c.suite_id = s.id} is the defect that reads right</h2>
     *
     * <p>The fourth copy of {@code execution.CaseFacts#memberOf}'s union, and the module boundary is
     * what makes it a copy rather than a call (this class's header, ERD §0.2). One column comparison
     * is what the schema looks like it wants, and against a suite defined by a <strong>tag
     * rule</strong> — which files nothing — it resolves to the empty set: a summary of
     * {@code 0 of 0, 100%} over a run that covered forty of that suite's cases. §98 found that
     * between a count and a grid; §99 found it as an empty checklist; this is the third door onto it.
     * {@code SuiteScopeAgreementTest} holds this copy to the others.
     *
     * <h2>{@code not_run} counts the non-verdicts, and this is where the rollup differs from the fact log</h2>
     *
     * <p>{@link #projectRecordedExecutions()} joins {@code case_status} inner, so the fact log
     * structurally holds only recorded executions and its header states the consequence: coverage
     * and not-run denominators can never come from that table. This one <em>is</em> a coverage
     * denominator. §6p: <em>"where a run covers only part of a suite, show the coverage alongside
     * the percentage, because a 100% pass rate over 3 of 47 cases is otherwise actively
     * misleading."</em> So the join is a {@code LEFT JOIN} and an execution with no status at all —
     * the empty row §42 writes per case at run creation — lands in {@code not_run} rather than
     * vanishing from {@code total}.
     *
     * <h2>{@code suite_version_no} is the version in force AT THE RUN</h2>
     *
     * <p>Never {@code suite.current_version_no}, which is the version now: today's number beside an
     * old run puts this year's composition under last year's pass rate, and the column exists
     * precisely so a reader can tell those apart. Falls back to 1, the column default on every suite
     * row and the honest answer for suites created before {@code SuiteVersions} existed.
     *
     * @return rows inserted. Zero on a steady-state tick is ordinary — every run already has its
     *     summary — which is why {@link #correctSuiteRunCounts()} reports its own count separately.
     */
    int insertNewSuiteRunSummaries() {
        return entities.createNativeQuery("""
                INSERT INTO suite_run_summary (
                        company_id, run_id, suite_id, configuration_id, suite_version_no,
                        passed, failed, blocked, skipped, not_run, total,
                        membership_snapshot, last_executed_at, updated_at)
                SELECT m.company_id, m.run_id, m.suite_id, m.configuration_id,
                       COALESCE((SELECT max(v.version_no) FROM suite_version v
                                  WHERE v.suite_id = m.suite_id
                                    AND m.last_executed_at IS NOT NULL
                                    AND v.created_at <= m.last_executed_at), 1),
                       m.passed, m.failed, m.blocked, m.skipped, m.not_run, m.total,
                       m.case_ids, m.last_executed_at, now()
                  FROM (
                        SELECT e.company_id,
                               e.run_id,
                               s.id AS suite_id,
                               r.configuration_id,
                               count(*) FILTER (WHERE st.kind = 'PASSED')  AS passed,
                               count(*) FILTER (WHERE st.kind = 'FAILED')  AS failed,
                               count(*) FILTER (WHERE st.kind = 'BLOCKED') AS blocked,
                               count(*) FILTER (WHERE st.kind = 'SKIPPED') AS skipped,
                               count(*) FILTER (WHERE st.kind IS NULL
                                                   OR st.kind IN ('UNTESTED','RETEST','WIP'))
                                                                           AS not_run,
                               count(*)                                    AS total,
                               max(e.executed_at)                          AS last_executed_at,
                               to_jsonb(array_agg(e.case_id ORDER BY e.case_id)) AS case_ids
                          FROM test_execution e
                          JOIN test_run  r ON r.id = e.run_id
                          JOIN test_case c ON c.id = e.case_id
                          JOIN suite     s ON s.project_id = e.project_id
                          LEFT JOIN case_status st ON st.id = e.status_id
                         WHERE ( c.suite_id = s.id
                                 OR EXISTS (SELECT 1 FROM suite_case sc
                                             WHERE sc.suite_id = s.id AND sc.case_id = c.id)
                                 OR EXISTS (SELECT 1 FROM suite_tag_rule tr
                                              JOIN case_tag ct ON ct.tag = tr.tag
                                                              AND ct.case_id = c.id
                                             WHERE tr.suite_id = s.id) )
                         GROUP BY e.company_id, e.run_id, s.id, r.configuration_id
                       ) m
                    ON CONFLICT (run_id, suite_id, configuration_id) DO NOTHING
                """)
                .executeUpdate();
    }

    /**
     * Re-tally the counts of {@code suite_run_summary} rows that already exist, over
     * <strong>the membership each row froze</strong> and never over today's.
     *
     * <p>This is the half that follows an amended verdict: a result re-recorded from {@code FAILED}
     * to {@code PASSED} must move the row, and nothing else may. The join to the row's own
     * {@code membership_snapshot} is what makes that true by construction — there is no set-list to
     * get wrong, and a case that joined the suite after the run is not in the snapshot and therefore
     * cannot be counted.
     *
     * <h2>The trailing {@code IS DISTINCT FROM} is not micro-optimisation</h2>
     *
     * <p>Without it every tick rewrites every row and bumps {@code updated_at} — which is what the
     * contract's {@code computedAt} serves to the client (§101 decision D7). A freshness field that
     * always says "just now" says nothing, and it would say it most loudly on the screen §6p calls
     * the most dangerous in the product. So a row whose counts have not changed keeps the timestamp
     * of the tick that last changed them, which is the honest reading of "as of".
     *
     * <p>{@code IS DISTINCT FROM} rather than {@code <>} because a row comparison containing a NULL
     * is NULL rather than true — and every count column is {@code NOT NULL} today, so this is
     * defence against a column that later is not, in the one direction that fails silently: {@code <>}
     * evaluating to NULL would skip the update and freeze a count for ever.
     *
     * @return rows corrected. Zero on a steady-state tick is the ordinary case.
     */
    int correctSuiteRunCounts() {
        return entities.createNativeQuery("""
                UPDATE suite_run_summary srs
                   SET passed = t.passed, failed = t.failed, blocked = t.blocked,
                       skipped = t.skipped, not_run = t.not_run, total = t.total,
                       -- Moves with the counts and is NOT frozen with the membership: it describes
                       -- the results, not the composition. An amended verdict changes when the
                       -- run's last result landed, and the page sorts by that.
                       last_executed_at = t.last_executed_at,
                       updated_at = now()
                  FROM (
                        SELECT s.run_id, s.suite_id, s.configuration_id,
                               max(e.executed_at)                          AS last_executed_at,
                               count(*) FILTER (WHERE st.kind = 'PASSED')  AS passed,
                               count(*) FILTER (WHERE st.kind = 'FAILED')  AS failed,
                               count(*) FILTER (WHERE st.kind = 'BLOCKED') AS blocked,
                               count(*) FILTER (WHERE st.kind = 'SKIPPED') AS skipped,
                               count(*) FILTER (WHERE st.kind IS NULL
                                                   OR st.kind IN ('UNTESTED','RETEST','WIP'))
                                                                           AS not_run,
                               count(*)                                    AS total
                          FROM suite_run_summary s
                          JOIN LATERAL jsonb_array_elements_text(s.membership_snapshot) AS mc(id)
                                    ON true
                          JOIN test_execution e ON e.run_id = s.run_id
                                               AND e.case_id = CAST(mc.id AS uuid)
                          LEFT JOIN case_status st ON st.id = e.status_id
                         GROUP BY s.run_id, s.suite_id, s.configuration_id
                       ) t
                 WHERE srs.run_id = t.run_id
                   AND srs.suite_id = t.suite_id
                   AND srs.configuration_id = t.configuration_id
                   AND (srs.passed, srs.failed, srs.blocked, srs.skipped, srs.not_run, srs.total,
                        srs.last_executed_at)
                       IS DISTINCT FROM
                       (CAST(t.passed AS int), CAST(t.failed AS int), CAST(t.blocked AS int),
                        CAST(t.skipped AS int), CAST(t.not_run AS int), CAST(t.total AS int),
                        t.last_executed_at)
                """)
                .executeUpdate();
    }

    /**
     * Upsert the current latest recorded result per {@code (plan, case, configuration)} for the open
     * company.
     *
     * <h2>Every column moves on conflict, which is the opposite of {@code execution_fact} and correct</h2>
     *
     * <p>{@link #projectRecordedExecutions()}'s set-list names seven columns and freezes fifteen,
     * because a fact's dimensions are as-of the fact. <strong>This table has no dimensions.</strong>
     * {@code V1}: "The CURRENT latest result per case per configuration." Every column on it is part
     * of the answer to <em>which result is current</em>, so a frozen column here would be a row that
     * had stopped following the executions it claims to summarise.
     *
     * <p>Read the two set-lists together and the rule is visible: freeze what describes the past,
     * move what describes the present. The two tables are one migration apart and the same recompute
     * written twice is wrong for one of them.
     *
     * <h2>The scope is the plan's own, never "cases with an execution naming this plan"</h2>
     *
     * <p>{@code plan_scope_item}'s three arms, the same expansion {@code CaseFacts#scopeMatch}
     * makes — case, suite (through the membership union), tag. A case that ran <em>inside</em> one of
     * the plan's runs without being in its scope is not evidence about that plan, and a scope-blind
     * statement would file it as readiness. §46 established the direction for the live path and it
     * holds here: a plan's numbers are argued from what it covers.
     *
     * <p>The mirror follows without a special case: <strong>a case in scope that has never run is
     * ABSENT from this table</strong> rather than present with a null status, because the join to
     * {@code case_status} is inner. Coverage and not-run are the reader's difference against the
     * scope (§6p), and a null-status row here would be a second, disagreeing source for a number the
     * plan already knows.
     *
     * <h2>Deleted runs are EXCLUDED, and that is the opposite call from the fact log</h2>
     *
     * <p>{@code TestExecutionRepository#findForPlan} excludes them and its javadoc says why: §6p's
     * "deleted runs must not orphan history" is about the case timeline, where the entry is marked
     * and kept, while a <em>current-state</em> figure is the other case and counting a withdrawn run
     * there would restore a result the person deleting it meant to retract. This statement is the
     * current-state one, so it matches — and {@code RollupAgreementTest} is what holds the two to
     * each other rather than this paragraph.
     *
     * <h2>{@code DISTINCT ON} and the tiebreak</h2>
     *
     * <p>The distinct key is exactly the conflict target. That is not tidiness: with two source rows
     * mapping to one conflict target Postgres raises <em>"ON CONFLICT DO UPDATE command cannot
     * affect row a second time"</em>, and a case reachable through two scope items — named directly
     * and matched by a tag — is the ordinary way to produce one.
     *
     * <p>{@code e.id DESC} after {@code executed_at DESC} is not decoration. Two results recorded in
     * the same microsecond must resolve the same way on every tick, or this statement is not
     * idempotent in exactly the case where a CI batch lands. {@code NULLS LAST} keeps an undated
     * execution from displacing real history at the head, the same defence
     * {@link #recomputeFailingRepeatedly(int, java.time.Instant)} makes.
     *
     * @return rows inserted or corrected
     */
    int refreshPlanSnapshot() {
        return entities.createNativeQuery("""
                INSERT INTO plan_snapshot (
                        company_id, plan_id, case_id, configuration_id, execution_id,
                        status_kind, counts_as_pass, executed_at, release_version, updated_at)
                SELECT DISTINCT ON (i.plan_id, e.case_id, r.configuration_id)
                       e.company_id, i.plan_id, e.case_id, r.configuration_id, e.id,
                       st.kind, st.counts_as_pass, e.executed_at, e.release_version, now()
                  FROM plan_scope_item i
                  JOIN test_plan p ON p.id = i.plan_id
                  LEFT JOIN suite s ON s.id = i.suite_id
                  JOIN test_case c ON c.project_id = p.project_id
                                  AND c.deleted_at IS NULL
                                  AND ( (i.case_id IS NOT NULL AND c.id = i.case_id)
                                     OR (i.suite_id IS NOT NULL
                                         AND ( c.suite_id = s.id
                                               OR EXISTS (SELECT 1 FROM suite_case sc
                                                           WHERE sc.suite_id = s.id
                                                             AND sc.case_id = c.id)
                                               OR EXISTS (SELECT 1 FROM suite_tag_rule tr
                                                            JOIN case_tag ct ON ct.tag = tr.tag
                                                                            AND ct.case_id = c.id
                                                           WHERE tr.suite_id = s.id) ))
                                     OR (i.tag IS NOT NULL
                                         AND EXISTS (SELECT 1 FROM case_tag pt
                                                      WHERE pt.case_id = c.id
                                                        AND pt.tag = i.tag)) )
                  JOIN test_execution e ON e.case_id = c.id
                  JOIN test_run       r ON r.id = e.run_id
                                       AND r.plan_id = i.plan_id
                                       AND r.deleted_at IS NULL
                  JOIN case_status   st ON st.id = e.status_id
                 ORDER BY i.plan_id, e.case_id, r.configuration_id,
                          e.executed_at DESC NULLS LAST, e.id DESC
                    ON CONFLICT (plan_id, case_id, configuration_id) DO UPDATE
                   SET execution_id    = EXCLUDED.execution_id,
                       status_kind     = EXCLUDED.status_kind,
                       counts_as_pass  = EXCLUDED.counts_as_pass,
                       executed_at     = EXCLUDED.executed_at,
                       release_version = EXCLUDED.release_version,
                       updated_at      = now()
                 -- Only when something actually moved, for correctSuiteRunCounts' reason: this
                 -- column is served as `computedAt`, and a tick that rewrote every row would make
                 -- the freshness field always say "just now", which is a freshness field that says
                 -- nothing on the screen §6p calls the most dangerous in the product.
                 WHERE (plan_snapshot.execution_id, plan_snapshot.status_kind,
                        plan_snapshot.counts_as_pass, plan_snapshot.executed_at,
                        plan_snapshot.release_version)
                       IS DISTINCT FROM
                       (EXCLUDED.execution_id, EXCLUDED.status_kind, EXCLUDED.counts_as_pass,
                        EXCLUDED.executed_at, EXCLUDED.release_version)
                """)
                .executeUpdate();
    }

    /**
     * Delete {@code plan_snapshot} rows whose case is no longer in the plan's scope, has been
     * soft-deleted, or whose execution has gone.
     *
     * <h2>The asymmetry with {@code suite_run_summary}, stated where somebody would copy the wrong one</h2>
     *
     * <p>Its sibling has <strong>no delete pass and must never grow one</strong>: a
     * {@code suite_run_summary} row is history — how one suite did in one run — and a suite that
     * stops claiming a case does not un-run it. This table is current state, so the same
     * recompute-with-no-prune leaves a plan arguing readiness from a case it no longer covers, with
     * its totals permanently too high and <em>nothing on screen that looks wrong</em>.
     *
     * <p>{@code PlanStatisticsService.rowsInScope} has done this filter in Java since §46 and that
     * class's header calls the mirror case "easier to miss". Moving the computation into a table
     * moves the filter into a {@code DELETE}, and losing it in the move is this slice's second
     * finding.
     *
     * <h2>Written as the negation of the insert's own predicate, not as a shorter one</h2>
     *
     * <p>The tempting spelling is "delete rows the insert did not touch this tick", which needs a
     * timestamp column this table does not have, and would delete everything the first time an
     * insert failed. The predicate below is the insert's scope expansion negated — so the pair is
     * total by construction, and a scope arm added to one and not the other fails
     * {@code PlanSnapshotRefreshTest} rather than quietly accumulating rows.
     *
     * @return rows deleted
     */
    int prunePlanSnapshot() {
        return entities.createNativeQuery("""
                DELETE FROM plan_snapshot ps
                 WHERE NOT EXISTS (
                           SELECT 1
                             FROM plan_scope_item i
                             JOIN test_plan p ON p.id = i.plan_id
                             LEFT JOIN suite s ON s.id = i.suite_id
                             JOIN test_case c ON c.id = ps.case_id
                                             AND c.project_id = p.project_id
                                             AND c.deleted_at IS NULL
                                             AND ( (i.case_id IS NOT NULL AND c.id = i.case_id)
                                                OR (i.suite_id IS NOT NULL
                                                    AND ( c.suite_id = s.id
                                                          OR EXISTS (SELECT 1 FROM suite_case sc
                                                                      WHERE sc.suite_id = s.id
                                                                        AND sc.case_id = c.id)
                                                          OR EXISTS (SELECT 1 FROM suite_tag_rule tr
                                                                       JOIN case_tag ct
                                                                            ON ct.tag = tr.tag
                                                                           AND ct.case_id = c.id
                                                                      WHERE tr.suite_id = s.id) ))
                                                OR (i.tag IS NOT NULL
                                                    AND EXISTS (SELECT 1 FROM case_tag pt
                                                                 WHERE pt.case_id = c.id
                                                                   AND pt.tag = i.tag)) )
                            WHERE i.plan_id = ps.plan_id)
                    OR NOT EXISTS (SELECT 1 FROM test_execution e
                                    WHERE e.id = ps.execution_id)
                """)
                .executeUpdate();
    }
}
