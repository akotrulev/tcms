package com.tcms.reporting;

import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings {@code execution_fact} up to date for the company currently in context.
 *
 * <h2>What this makes true that was not true before</h2>
 *
 * <p>{@code execution_fact} has been in {@code V1} since M0 with five purpose-built indexes — one
 * per selection dimension of §6e's aggregate execution report — maintained on an <strong>empty
 * table</strong>. Nothing read it and nothing wrote it. Every report in §6e's catalogue is defined
 * over it, and {@code V1}'s own comment on the table says why it could not be deferred:
 * "reporting built on live joins cannot be converted to a read model incrementally".
 *
 * <p>This is the first writer it has ever had.
 *
 * <h2>{@code @Transactional} without {@code readOnly}, and that is what makes isolation work</h2>
 *
 * <p>Not merely what makes the write legal. {@code CompanyAwareTransactionManager} sets
 * {@code app.current_company} in {@code doBegin}, so the statement inside is the only thing that
 * ever sees this company's rows. A refresh run outside a transaction would write with the GUC unset,
 * and {@code V4}'s {@code nullif} makes that match <strong>zero</strong> rows rather than all of
 * them — the job would report success having done nothing, which is precisely the fail-closed shape
 * §15 documents and the one this class would have no symptom for.
 *
 * <h2>Deliberately the shape an event handler would call</h2>
 *
 * <p>One public method, company taken from context, no arguments. {@code CaseHealthRecomputeService}
 * states the same property for the same reason: landing {@code ResultRecorded} later adds a handler
 * that calls this and changes nothing here. The schedule is the floor, not the mechanism —
 * and per the slice plan's decision A it is also the mechanism a report job invokes synchronously
 * before computing, so a generated report's watermark is exact rather than up to an hour old.
 *
 * @see ExecutionFactScheduler for which companies this runs for, and how each one is established
 * @see ExecutionSource for the statements themselves and the frozen-dimension rule
 */
@Service
public class ExecutionFactRefreshService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionFactRefreshService.class);

    private final ExecutionSource source;
    private final ReportingProperties thresholds;
    private final Clock clock;

    ExecutionFactRefreshService(
            ExecutionSource source, ReportingProperties thresholds, Clock clock) {
        this.source = source;
        this.thresholds = thresholds;
        this.clock = clock;
    }

    /**
     * Project every recorded execution in the open company into the fact log, then re-derive the one
     * health signal that reads it.
     *
     * <h2>Both in ONE transaction, and the order is the reason</h2>
     *
     * <p>{@code failing_repeatedly} is derived from {@code execution_fact}, so a signal recomputed
     * before the projection reads the previous tick's facts and is a quarter-hour stale by
     * construction — visible to nobody, because a stale boolean renders exactly like a fresh one.
     * Sharing the transaction is also what makes the pair atomic: a crash between them would leave a
     * company whose facts moved and whose signal did not, and the next tick repairs it either way,
     * which is the property the whole idempotent-recompute design is for.
     *
     * <h2>The clock is the bean, not {@code Instant.now()}</h2>
     *
     * <p>{@code ArchitectureRulesTest.nothingCallsNowDirectly} fails the build on a direct call, and
     * the bean is truncated to microseconds because that is what a Postgres {@code timestamptz}
     * stores — an untruncated instant is a value returned in memory and read back two different
     * values (§60).
     *
     * @return how many fact rows were inserted or corrected. The signal count is logged rather than
     *     returned: it is every live case in the company on every tick, so it says nothing a caller
     *     could act on, while the fact count moving to zero in a company with recorded executions is
     *     the failure this job would otherwise have no symptom for.
     * @throws IllegalStateException via {@link CompanyContext#require()} if no company is open. Loud
     *     on purpose: the alternative is a job that quietly projects nothing, and "the reports
     *     stopped moving" is far harder to trace back here than a stack trace is.
     */
    @Transactional
    public int refresh() {
        CompanyContext.require();

        Instant now = clock.instant();

        int facts = source.projectRecordedExecutions();
        int signals = source.recomputeFailingRepeatedly(thresholds.failingRepeatedlyWindow(), now);
        // Third statement, one transaction, same order and the same reason: case_flake is derived
        // from execution_fact, so a scoring pass that ran before the projection would score the
        // previous tick's facts. It is deliberately NOT a fourth scheduled job — it reads exactly
        // the rows the projection just wrote, and a separate schedule would mean the score and the
        // signal above it could disagree about which executions exist, with no way to tell which
        // one was stale.
        int scored = source.recomputeFlakeScores(
                now.minus(thresholds.flakeWindow()), thresholds.flakeMinimumTransitions(), now);

        log.debug("Projected {} execution facts, re-derived failing_repeatedly for {} cases "
                + "and flake evidence for {} cases", facts, signals, scored);
        return facts;
    }
}
