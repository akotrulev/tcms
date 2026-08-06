package com.tcms.reporting;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
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
 * {@code test_case} and {@code suite} ({@code repository}), {@code case_status} and
 * {@code case_classification} ({@code project} / {@code repository}) — six tables across three
 * modules. Concentrating every one of them in a single file is what makes the coupling reviewable,
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
     * right steady state and the wrong thing to build <em>first</em>. The product has
     * <strong>zero</strong> {@code CompanyScopedEvent} implementations, no outbox consumer
     * ({@code outbox} is a {@code V1} table with no code) and {@code @Async} handlers whose failures
     * have, in {@code CompanyScopedEvent}'s own words, "nowhere to report". An event-only fact log
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
     * itself.
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
}
