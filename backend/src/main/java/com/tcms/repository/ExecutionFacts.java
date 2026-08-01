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
 * <p>One fact lives on the other side of this seam:
 *
 * <ol>
 *   <li><strong>When each case was last actually executed</strong> — folded directly into
 *       {@link #recomputeExecutionSignals}, because the answer is never needed as a value. Its only
 *       consumer writes {@code case_health}, and moving a row per case through the application to
 *       decide two booleans is the shape that turns a read model refresh into an N-query job.</li>
 * </ol>
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
}
