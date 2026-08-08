package com.tcms.repository;

import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Recomputes this company's health signals as soon as its results move, instead of waiting up to an
 * hour for {@link CaseHealthScheduler} (round 3 #52, phase 2).
 *
 * <h2>The second claimant of one event, and that is what the fan-out is for</h2>
 *
 * <p>{@code reporting} has a handler for {@code execution.results_changed} too. Neither module may
 * import the other (plan §3), so one row telling both is the only shape available — and the two are
 * independent: the fact log and the health signals derive from the same rows and from each other
 * not at all, so the undefined order between them is genuinely undefined rather than tolerated.
 *
 * <h2>{@code CaseHealthRecomputeService} predicted this handler and said what it cannot fix</h2>
 *
 * <p>Its header: the service is <em>"deliberately the shape an event handler would call — one
 * public method, company taken from context, clock injected — so that landing {@code ResultRecorded}
 * later adds a handler and changes nothing here"</em>. It also names the half an event can never
 * cover: <strong>{@code LONG_UNEXECUTED} becomes true through the passage of time, not through
 * anything happening.</strong> No event fires on the day a case's last result turns ninety days
 * old. So the hourly schedule stays, and remains the floor; what this adds is that a freshly
 * executed case stops reporting {@code NEVER_EXECUTED} in a minute rather than in an hour.
 *
 * <p>Shares the scheduler's switch for {@code FactRefreshOnResultsChanged}'s reason, one module over:
 * {@code tcms.case-health.enabled=false} must mean the signals do not move, not that they move by a
 * different route.
 */
@Component
@ConditionalOnProperty(prefix = "tcms.case-health", name = "enabled", matchIfMissing = true)
class HealthRecomputeOnResultsChanged implements OutboxHandler {

    private final CaseHealthRecomputeService recompute;

    HealthRecomputeOnResultsChanged(CaseHealthRecomputeService recompute) {
        this.recompute = recompute;
    }

    @Override
    public String eventType() {
        return OutboxEvents.RESULTS_CHANGED;
    }

    /**
     * Idempotent — the recompute rewrites every signal for the company from the current facts — so
     * at-least-once delivery costs a repeated statement and never a wrong signal.
     */
    @Override
    public void handle(OutboxMessage message) {
        recompute.recompute();
    }
}
