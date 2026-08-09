package com.tcms.repository;

import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives the execution-driven health signals for the company currently in context — two since
 * §53, and <strong>four since M3 slice 5</strong>, which added §6c's automation-drift pair now that
 * ingestion exists to make "no ingested result in N days" mean something.
 *
 * <h2>Why this exists at all, and what it stops the dashboard from claiming</h2>
 *
 * <p>{@code case_health} has had an entity, a repository, an enum, four partial indexes and a
 * dashboard panel reading it since §47 — and <strong>no writer</strong>. {@code DashboardService}
 * says so in its own javadoc, and draws the consequence precisely: an empty panel headed "Needs
 * attention" is a page telling the reader everything is fine, and it is not, because nobody has
 * looked. {@code healthComputedAt} exists solely so the SPA can render "not computed yet" instead.
 *
 * <p>This is the first thing that makes that timestamp non-null. ~~Two of the nine signals become
 * real; the other seven keep their honest zero, because approvals, documentation links and
 * ingestion still have no writer of their own~~ → <strong>four of the ten, and ingestion is no
 * longer on the list</strong>: §117 gave {@code test_run.source = 'INGESTED'} a writer, which is the
 * whole predicate {@code STALE_AUTOMATION} was blocked on. Approvals, documentation links and the
 * owner check keep their <del>honest</del> zero (see {@code ExecutionFacts}' note on why this job
 * must not touch their columns), as does {@code FAILING_REPEATEDLY}, which is a sibling job's and
 * not absent.
 *
 * <p><strong>§156: the word "honest" was load-bearing and this service is what took it away.</strong>
 * A zero is honest while the row is absent — the dashboard's whole three-state design rests on
 * that. The first run of this job writes a row and a {@code computed_at} for every live case, so
 * from then on the five signals nobody derives report a zero the read path cannot tell from a check
 * that passed. Nothing here is wrong; the consequence just belongs written down at the thing that
 * causes it. {@code CaseHealth}'s header has the account.
 *
 * <h2>Why a scheduled recompute and not an event handler</h2>
 *
 * <p>{@code HealthSignal}'s header calls {@code case_health} "a read model fed by events from
 * {@code execution} and {@code documentation}", and for seven of the signals that is right. It
 * cannot be the whole story for these two: <strong>{@code LONG_UNEXECUTED} becomes true through the
 * passage of time, not through anything happening.</strong> No event fires on the day a case's last
 * result turns ninety days old. An event-only design would leave the signal correct exactly until
 * the moment it should have been raised, which is the one moment it exists for.
 *
 * <p>So the schedule is the floor. Events remain the right way to make a <em>freshly</em> executed
 * case stop reporting {@code NEVER_EXECUTED} promptly, and this service is deliberately the shape an
 * event handler would call — one public method, company taken from context, clock injected — so
 * that landing {@code ResultRecorded} later adds a handler and changes nothing here.
 *
 * @see CaseHealthScheduler which company this runs for, and how that company is established
 */
@Service
public class CaseHealthRecomputeService {

    private static final Logger log = LoggerFactory.getLogger(CaseHealthRecomputeService.class);

    private final ExecutionFacts executionFacts;
    private final CaseHealthProperties thresholds;
    private final Clock clock;

    CaseHealthRecomputeService(
            ExecutionFacts executionFacts, CaseHealthProperties thresholds, Clock clock) {
        this.executionFacts = executionFacts;
        this.thresholds = thresholds;
        this.clock = clock;
    }

    /**
     * Recompute every live case in the open company.
     *
     * <p>{@code @Transactional} without {@code readOnly}, and that is what makes the isolation work
     * rather than merely what makes the write legal: {@code CompanyAwareTransactionManager} sets
     * {@code app.current_company} in {@code doBegin}, so the statement inside is the only thing that
     * ever sees this company's rows. A recompute run outside a transaction would write with the GUC
     * unset, and V4's {@code nullif} makes that match zero rows rather than all of them — the job
     * would report success having done nothing, which is precisely the fail-closed shape §15
     * documents.
     *
     * @throws IllegalStateException via {@link CompanyContext#require()} if no company is open. Loud
     *     on purpose: the alternative is a job that quietly recomputes nothing, and "the numbers
     *     stopped updating" is far harder to trace back here than a stack trace is.
     */
    @Transactional
    public int recompute() {
        CompanyContext.require();
        Instant now = clock.instant();
        Instant cutoff = now.minus(thresholds.longUnexecuted());

        int cases = executionFacts.recomputeExecutionSignals(cutoff, now);
        // Both statements in one transaction, and therefore under one GUC and one company. Split
        // across two, a company whose second transaction failed would be left with half a tick's
        // signals stamped with this tick's computed_at — a row claiming to have been derived just
        // now, half of which was not.
        executionFacts.recomputeAutomationDrift(now.minus(thresholds.staleAutomation()), now);
        log.debug("Recomputed execution and automation-drift health signals for {} cases", cases);
        return cases;
    }
}
