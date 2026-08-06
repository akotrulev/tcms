package com.tcms.reporting;

import com.tcms.tenancy.CompanyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two M2 rollups (§6p) — {@code suite_run_summary} and {@code plan_snapshot} — refreshed for the
 * open company.
 *
 * <p>Both tables have been in {@code V1} since M0 and written by nothing. They were invisible to
 * round 2 of the remaining-scope assessment because both are <em>discussed</em> in javadoc, which is
 * how M2 came to be scored at 90% with one of its named deliverables at zero. Round 3's filter found
 * them; §101 is the writer.
 *
 * <h2>Idempotent recompute, not an event handler</h2>
 *
 * <p>§6p describes both as "updated incrementally as results land". That is the right steady state
 * and the wrong thing to build first, for verbatim the reason {@link ExecutionFactRefreshService}
 * gives: the product has zero {@code CompanyScopedEvent} implementations, no outbox consumer, and
 * {@code @Async} handler failures have, in {@code CompanyScopedEvent}'s own words, "nowhere to
 * report". An event-only rollup therefore has <strong>no rebuild path</strong> — a handler that
 * throws, a restart mid-dispatch, or a result recorded before the handler was deployed each leave a
 * rollup that is permanently and silently short, and a short rollup renders identically to a correct
 * one. A statement keyed on the primary key <em>is</em> the rebuild path. Landing
 * {@code ResultRecorded} later adds a handler that calls this service and changes nothing here.
 *
 * <h2>A sibling of {@link ExecutionFactRefreshService} rather than a fifth statement inside it</h2>
 *
 * <p>That service's header argues its two statements share a transaction because the second
 * <em>reads what the first writes</em> — {@code failing_repeatedly} is derived from
 * {@code execution_fact}, so recomputing it first would read the previous tick's facts. That is not
 * true of these: neither rollup reads the fact log. What the two services do share is the design —
 * idempotent recompute keyed on the primary key, one transaction per company, a missed refresh
 * repaired by the next tick rather than lost.
 *
 * <h2>The two tables are NOT symmetric, and the asymmetry is the slice's finding</h2>
 *
 * <p>{@code suite_run_summary} is <strong>history</strong>: a row says how one suite did in one run,
 * so nothing deletes and membership freezes ({@link ExecutionSource#insertNewSuiteRunSummaries()}).
 * {@code plan_snapshot} is <strong>current state</strong> — {@code V1}'s own comment: "the CURRENT
 * latest result per case per configuration" — so it needs the delete pass its sibling must never
 * grow ({@link ExecutionSource#prunePlanSnapshot()}). Two tables that look alike, created in the
 * same migration, discussed in the same paragraph of §6p, and a recompute written the same way for
 * both is wrong for one of them in a way no row looks wrong for.
 */
@Service
public class RollupRefreshService {

    private static final Logger log = LoggerFactory.getLogger(RollupRefreshService.class);

    private final ExecutionSource source;

    RollupRefreshService(ExecutionSource source) {
        this.source = source;
    }

    /**
     * Refresh both rollups for the open company, in one transaction.
     *
     * <p>One transaction rather than four, for {@link ExecutionFactRefreshService}'s stated reason:
     * a crash between the insert and the correction would leave a company whose new summaries are
     * present and whose amended verdicts are not, and while the next tick repairs it either way, a
     * screen read in between would show two numbers from two moments.
     *
     * @return how many rollup rows were written, corrected or deleted across both tables. Returned
     *     for the log line, and it is the count that matters: a refresh that touches zero rows in a
     *     company with recorded executions is the failure this job would otherwise have no symptom
     *     for. Zero is nonetheless <em>ordinary</em> in the steady state, which is why the four
     *     figures are logged separately — the insert count going to zero is expected, all four going
     *     to zero on a company that just recorded a result is not.
     * @throws IllegalStateException via {@link CompanyContext#require()} if no company is open. Loud
     *     on purpose: with the GUC unset, {@code V4}'s {@code nullif} makes every statement here
     *     match zero rows, so an unguarded job would log a successful tick having done nothing and
     *     every rollup would stay empty for ever.
     */
    @Transactional
    public int refresh() {
        CompanyContext.require();

        int suitesInserted = source.insertNewSuiteRunSummaries();
        int suitesCorrected = source.correctSuiteRunCounts();
        int planRows = source.refreshPlanSnapshot();
        int planPruned = source.prunePlanSnapshot();

        log.debug("Rollups refreshed: suite +{} ~{}, plan ~{} -{}",
                suitesInserted, suitesCorrected, planRows, planPruned);

        return suitesInserted + suitesCorrected + planRows + planPruned;
    }
}
