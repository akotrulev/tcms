package com.tcms.reporting;

import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Refreshes this company's fact log and rollups as soon as its results move, instead of waiting up
 * to fifteen minutes for {@link ExecutionFactScheduler} (round 3 #52, phase 2).
 *
 * <h2>This is the handler {@code ExecutionSource} said would arrive, and it changes nothing there</h2>
 *
 * <p>That class's header argued at length that the fact log had to be an <strong>idempotent
 * recompute rather than an event handler</strong>, because an event-only log has no rebuild path: a
 * handler that throws, a restart mid-dispatch, or a result recorded before the handler was deployed
 * each leave it permanently and silently short. It then named exactly this: <em>"Landing
 * {@code ResultRecorded} later adds a handler that calls the same service and changes nothing
 * here."</em>
 *
 * <p>So this class has no logic. It calls the two services the scheduler calls, in the order §101
 * requires, and the schedule remains the floor. What the event buys is latency, and only latency —
 * a plan page's headline and its sparkline stop being a quarter of an hour behind the run somebody
 * just finished.
 *
 * <h2>The order is §101's and it is not stylistic</h2>
 *
 * <p>Rollups after the projection, never before: the plan statistics screen takes its headline from
 * {@code plan_snapshot} and its trend from {@code execution_fact}, so the two must move on one
 * clock or the page disagrees with itself by up to a refresh interval with nothing on screen to say
 * so. Both effects live in <em>one</em> handler for that reason — two handlers claiming this event
 * would run in an undefined order, which is the arrangement §101 exists to prevent.
 *
 * <h2>It shares the scheduler's switch, deliberately</h2>
 *
 * <p>{@code tcms.reporting.enabled=false} must mean the fact log does not move, not "does not move
 * on a timer". A deployment with the module off and this handler on would keep projecting facts
 * through the event path and nothing would say why. With both off, the events go
 * {@code DEAD} naming the type — which is the visible, intended reading of "nobody is consuming
 * this here".
 */
@Component
@ConditionalOnProperty(prefix = "tcms.reporting", name = "enabled", matchIfMissing = true)
class FactRefreshOnResultsChanged implements OutboxHandler {

    private final ExecutionFactRefreshService refresh;
    private final RollupRefreshService rollups;

    FactRefreshOnResultsChanged(ExecutionFactRefreshService refresh, RollupRefreshService rollups) {
        this.refresh = refresh;
        this.rollups = rollups;
    }

    @Override
    public String eventType() {
        return OutboxEvents.RESULTS_CHANGED;
    }

    /**
     * Company context is already open — the dispatcher opened it from the envelope — and each
     * service carries its own {@code @Transactional}, so this method holds none.
     *
     * <p>Idempotent by construction, which is what makes at-least-once delivery legal here: the
     * projection is keyed on the primary key and the rollups are recomputed wholesale. Running it
     * twice is a wasted statement, never a wrong number.
     */
    @Override
    public void handle(OutboxMessage message) {
        refresh.refresh();
        rollups.refresh();
    }
}
