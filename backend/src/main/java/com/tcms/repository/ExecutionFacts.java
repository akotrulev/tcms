package com.tcms.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The one place in this module that names an {@code execution} table</strong> — the exact
 * mirror of {@code execution.CaseFacts}, which is the one place that module names a
 * {@code repository} table.
 *
 * <p>The seam is symmetric on purpose. ArchUnit permits a cross-module dependency only toward
 * {@code tenancy}, {@code identity} and {@code project} (plan §3), so neither of these two modules
 * may call the other; each instead reads rows it holds ids into, through exactly one file, so a
 * reviewer can find every crossing by opening two. A second class here that named
 * {@code test_execution} would end that property, and it would end it for the most tempting reason
 * there is — that the new query is short.
 *
 * <p>Two facts live on the other side of this seam, and <strong>neither is ever needed as a
 * value</strong> — both are folded directly into the statement that consumes them, because moving a
 * row per case through the application to decide a boolean is the shape that turns a read-model
 * refresh into an N-query job:
 *
 * <ol>
 *   <li><strong>When each case was last actually executed</strong> — {@link
 *       #recomputeExecutionSignals}.</li>
 *   <li><strong>When CI last posted a result for each case</strong> — {@link
 *       #recomputeAutomationDrift}. The same {@code test_execution} rows, narrowed to those whose
 *       run came through the ingestion door.</li>
 * </ol>
 *
 * <p>Two statements rather than one, though they scan the same table for the same cases. They are
 * separable because they own <strong>disjoint columns</strong> (see either method's {@code ON
 * CONFLICT} note), and separate because merging them would put four booleans behind one lateral and
 * make the cheap half — which needs no {@code test_run} join at all — pay for the expensive one on
 * every tick. The cost of the split is one extra scan per company per tick against a bounded set of
 * live cases; the sizing assumption this is measured against is plan §8 #5, under 10k cases.
 *
 * <h2>Native SQL, and why there is no entity for {@code test_execution} here</h2>
 *
 * <p>Same argument {@code CaseFacts} makes from the other side: a second JPA entity mapped over a
 * table another module owns is one table with two sets of column expectations for
 * {@code ddl-auto: validate} to check, and two places a mapping can drift. SQL through the
 * {@link EntityManager} states plainly what this is — a read of rows this module holds ids into,
 * not a claim on their mapping.
 *
 * <p>RLS still covers every statement here. It runs inside the caller's transaction, so
 * {@code app.current_company} is set by {@code CompanyAwareTransactionManager} and one company's
 * executions are unreachable from another's session regardless of what this asks for. That matters
 * more than usual for a statement that <em>writes</em>: the {@code INSERT … SELECT} below carries no
 * company predicate of its own, and does not need one, because {@code FORCE ROW LEVEL SECURITY}
 * applies to both sides — the {@code SELECT} can only see the open company's cases and the
 * {@code INSERT} can only write that company's rows. {@code CaseHealthRecomputeTest} asserts exactly
 * that with two companies rather than trusting this paragraph.
 */
@Component
class ExecutionFacts {

    /**
     * Constructor-injected rather than {@code @PersistenceContext} on a field: the project's rule is
     * constructor injection everywhere, and Spring resolves this to the same shared,
     * transaction-bound proxy the annotation would have produced.
     */
    private final EntityManager entities;

    ExecutionFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Re-derive {@link HealthSignal#NEVER_EXECUTED} and {@link HealthSignal#LONG_UNEXECUTED} for
     * every live case in the currently-open company, in one statement.
     *
     * <h2>The {@code ON CONFLICT} clause names three columns, and that is the important part</h2>
     *
     * <p>It updates {@code never_executed}, {@code long_unexecuted} and {@code computed_at} — and
     * <strong>deliberately not the other seven signals</strong>. This job computes two things; the
     * remaining seven are derived from documentation links, approvals and ingestion, none of which
     * have a writer. Setting them here, even to their existing value, would make this statement the
     * author of claims it has no evidence for, and the day the approval computation lands the two
     * jobs would race to blank each other's columns. A signal's owner writes its own column and no
     * others; the row is shared, the columns are not.
     *
     * <p>The seven do get written on <em>insert</em>, because a row has to have values — but there
     * they are the column defaults (false), which is the same thing "no computation has asserted
     * this" already meant everywhere else in the read path.
     *
     * <h2>Recorded means {@code status_id IS NOT NULL} — and that is guarded twice on purpose</h2>
     *
     * <p>Not "an execution row exists". Creating a run writes one execution per case in scope
     * (§42), all of them empty, so a case in a freshly created run has an execution and has never
     * been executed — and the case grid, the plan statistics and §52's suite timeline all already
     * agree that a row nobody recorded is work outstanding.
     *
     * <p><strong>The redundancy is measured, not assumed.</strong> {@code TestExecution.record()}
     * sets {@code status_id} and {@code executed_at} together, so {@code max(executed_at)} already
     * ignores an unrecorded row all by itself — {@code MAX} skips nulls. Mutation testing showed
     * this directly: deleting the {@code status_id} predicate kills <em>no</em> test, and swapping
     * {@code executed_at} for an always-set column kills no test either. Only removing both at once
     * makes a planned run look executed.
     *
     * <p>It is kept anyway, and the reason is worth being explicit about since a future reader will
     * find it unreachable and be tempted to tidy it. The two columns move together <em>because one
     * writer sets them</em>, and M3's ingestion path is a second writer being built against this
     * table. A predicate that states the intended meaning of the query — "results, not schedule" —
     * is the thing that keeps being true when that assumption stops holding, and its cost is one
     * line in an already-indexed scan.
     *
     * @param cutoff  the moment a newest-result older than which raises {@code LONG_UNEXECUTED}.
     *                Passed in rather than computed here so the caller owns the clock and the test
     *                can put the boundary wherever it needs it.
     * @param now     stamped into {@code computed_at} — what tells the dashboard "nobody has looked"
     *                apart from "nothing is wrong" (see {@code CaseHealth}'s header).
     * @return how many cases were re-derived. Returned for the log line: a recompute that silently
     *         touches zero rows in a company with cases is the failure this job would otherwise have
     *         no symptom for.
     */
    int recomputeExecutionSignals(Instant cutoff, Instant now) {
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id,
                        never_executed, long_unexecuted, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       last_run.at IS NULL,
                       last_run.at IS NOT NULL AND last_run.at < :cutoff,
                       :now
                  FROM test_case c
                  LEFT JOIN LATERAL (
                           SELECT max(e.executed_at) AS at
                             FROM test_execution e
                            WHERE e.case_id = c.id
                              AND e.status_id IS NOT NULL
                       ) last_run ON true
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET never_executed  = EXCLUDED.never_executed,
                       long_unexecuted = EXCLUDED.long_unexecuted,
                       computed_at     = EXCLUDED.computed_at
                """)
                .setParameter("cutoff", cutoff)
                .setParameter("now", now)
                .executeUpdate();
    }

    /**
     * Re-derive {@link HealthSignal#STALE_AUTOMATION} and {@link HealthSignal#UNDECLARED_AUTOMATION}
     * for every live case in the currently-open company, in one statement (plan §6c).
     *
     * <h2>These are one fact read on two sides of one cutoff</h2>
     *
     * <p>The fact is {@code max(executed_at)} over this case's <em>recorded, ingested</em>
     * executions. Above the cutoff and the case does not claim automation, the automation is
     * undeclared; below it and the case does claim automation, the automation has stopped
     * reporting. <strong>A case can therefore never raise both</strong>, and that is a property of
     * this statement rather than of the schema — which is exactly why the two are written here
     * together instead of by two jobs that would have to agree.
     *
     * <h2>Ingested means the run's {@code source}, which is the door and not the caller</h2>
     *
     * <p>§117 pinned {@code TestRun.create}'s {@code source} to <em>the door the request came
     * through, never the caller's role</em>: an Automation Service token opening a run through
     * {@code POST /runs} writes {@code MANUAL}, and only {@code IngestionService} writes
     * {@code INGESTED}. That is the definition this signal wants — drift is about whether the CI
     * pipeline is posting reports, not about whether a machine was involved somewhere — and the
     * alternative spelling ("the executor was a token") would be a second implementation of a
     * question {@code test_run.source} already answers, differing for the person who kicks the
     * pipeline off by hand.
     *
     * <p>Until §117 nothing wrote {@code INGESTED} at all, which is why this column waited from
     * {@code V1} to M3 for a writer: the predicate would have selected the empty set in every
     * deployment and reported a confident zero.
     *
     * <h2>Staleness requires a PREVIOUS ingested result, and that is not an optimisation</h2>
     *
     * <p>{@code last_ci.at IS NOT NULL} is load-bearing. "Stopped reporting" presupposes started,
     * and §6c makes {@code automationKey} optional precisely so <em>"a case can be marked
     * {@code AUTOMATED} before the key is known"</em>. Without the clause, an ERROR-severity signal
     * fires on every case in a project the day somebody begins declaring automation intent — before
     * anything is wrong, and loudest on the teams doing the work. A case with no results at all is
     * already {@code NEVER_EXECUTED}; a case marked {@code AUTOMATED} whose automation has
     * <em>never</em> reported is a hole this pair leaves open on purpose, because closing it needs a
     * date-automation-was-expected that no write path fills.
     *
     * <p><strong>Mutation testing found that clause is load-bearing twice over.</strong> Deleting it
     * outright does not produce a wrong answer — it produces a <em>crash</em>: with no ingested
     * result, {@code last_ci.at < :cutoff} is NULL, and NULL into a {@code NOT NULL} boolean column
     * aborts the whole recompute. Every test in the class went red, which is the safe direction and
     * says nothing about the semantics. The mutation that <em>does</em> measure them is the naive
     * reading of §6c — {@code (last_ci.at IS NULL OR last_ci.at < :cutoff)}, "no ingested result in
     * N days" taken literally — and that one reddens exactly one test,
     * {@code automationThatNeverStartedHasNotStopped}. Anybody re-measuring this should mutate to
     * the competing implementation rather than to nothing.
     *
     * <h2>The mirror is {@code <> 'AUTOMATED'} and not {@code = 'MANUAL'}</h2>
     *
     * <p>§6c words it as {@code MANUAL}. Two of the other three states are louder:
     * {@code TO_BE_AUTOMATED} means the automation backlog is counting work already done, and
     * {@code NOT_AUTOMATABLE} means a case declared impossible to automate is being automated —
     * which moves the <em>denominator</em> of §6c's own coverage formula,
     * {@code AUTOMATED / (total − NOT_AUTOMATABLE)}.
     *
     * <h2>No {@code deleted_at} predicate on the run, for agreement rather than for correctness</h2>
     *
     * <p>A deleted run's results still count as evidence CI reported. That is arguable; what is not
     * arguable is that the two statements in this file must mean the same thing by "a result", and
     * {@link #recomputeExecutionSignals} has counted every recorded execution since §53. Two
     * neighbouring statements disagreeing would surface as a case that is simultaneously
     * {@code NEVER_EXECUTED} and {@code STALE_AUTOMATION} — which reads as a bug in whichever the
     * reader trusts less. If it changes, it changes for both.
     *
     * <h2>{@code ON CONFLICT} names three columns, and there are now three writers of this row</h2>
     *
     * <p>{@code stale_automation}, {@code undeclared_automation}, {@code computed_at} — the rule
     * {@link #recomputeExecutionSignals} states (the row is shared, the columns are not), and this
     * is the first slice in which it holds in both directions: {@code reporting.ExecutionSource}
     * writes {@code failing_repeatedly} into the same rows on its own schedule, so a statement here
     * that set every column would blank a sibling's live work rather than a hypothetical future
     * one's.
     *
     * @param cutoff the boundary. Newest ingested result older than this ⇒ stale; newer ⇒ the case
     *               had better claim automation. Passed in for the same reason its sibling's is: the
     *               caller owns the clock.
     * @param now    stamped into {@code computed_at}.
     * @return how many cases were re-derived.
     */
    int recomputeAutomationDrift(Instant cutoff, Instant now) {
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id,
                        stale_automation, undeclared_automation, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       c.automation_status =  'AUTOMATED'
                           AND last_ci.at IS NOT NULL AND last_ci.at <  :cutoff,
                       c.automation_status <> 'AUTOMATED'
                           AND last_ci.at IS NOT NULL AND last_ci.at >= :cutoff,
                       :now
                  FROM test_case c
                  LEFT JOIN LATERAL (
                           SELECT max(e.executed_at) AS at
                             FROM test_execution e
                             JOIN test_run r ON r.id = e.run_id
                            WHERE e.case_id = c.id
                              AND e.status_id IS NOT NULL
                              AND r.source = 'INGESTED'
                       ) last_ci ON true
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET stale_automation      = EXCLUDED.stale_automation,
                       undeclared_automation = EXCLUDED.undeclared_automation,
                       computed_at           = EXCLUDED.computed_at
                """)
                .setParameter("cutoff", cutoff)
                .setParameter("now", now)
                .executeUpdate();
    }
}
