package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * The derived health of one case — a <strong>read model</strong>, not authored state (plan §6o).
 *
 * <p>Nine booleans, one per {@link HealthSignal}, keyed on the case. Denormalized into columns
 * rather than held as a set of signal rows because every use is a predicate over a specific signal
 * — "which cases in this project have a stale approval" is a partial index away in this shape and a
 * join plus a filter in the other, and {@code V1} already ships four such indexes.
 *
 * <h2>Nothing writes this yet, and the endpoint is correct anyway</h2>
 *
 * <p>The signals are computed from executions and documentation links, and neither module exists.
 * So the table is empty, every case reports no signals, and every health facet counts zero. That is
 * the truthful answer rather than a stub: no signal <em>has</em> been computed. It begins working
 * when the computation lands, and nothing in the read path changes.
 *
 * <p>Which is also why there is no factory here and no mutator anywhere: a signal that a human can
 * set is a signal that can be set to false while its cause is still true, and the self-healing
 * property is the entire reason these are derived.
 *
 * <h2>Two schema properties worth stating, because both look like mistakes</h2>
 *
 * <p>{@code case_health} carries {@code company_id} and {@code project_id} with <em>no</em> foreign
 * keys — deliberate for a read model, which must be rebuildable and truncatable without fighting
 * referential integrity. It is nonetheless under {@code FORCE ROW LEVEL SECURITY} like every other
 * company-owned table, so the missing FK costs nothing in isolation terms.
 *
 * <p><del>{@code computed_at} is not mapped. It matters to whatever recomputes this and to nothing
 * on the read side; a summary that showed "health as of…" would be reporting on the freshness of a
 * job the user cannot see or trigger.</del> <strong>→ superseded by the dashboard.</strong> The
 * reasoning held while the only reader was a per-case chip list, where absent signals and
 * uncomputed signals render identically and harmlessly. It fails for a panel headed <em>"Needs
 * attention"</em>: empty there is a <em>claim</em> that nothing needs attention, and with no writer
 * for this table that claim is false in every deployment. The column is what tells the two apart,
 * so it is mapped and {@code DashboardView.healthComputedAt} carries it. The original objection is
 * still right about what not to do with it — the SPA reports "not computed yet", never a job's
 * freshness.
 */
@Entity
@Table(name = "case_health")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseHealth {

    /** Also the primary key: one row per case, or none. */
    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "outdated_spec_changed", nullable = false)
    private boolean outdatedSpecChanged;

    @Column(name = "stale_approval", nullable = false)
    private boolean staleApproval;

    @Column(name = "condition_drift", nullable = false)
    private boolean conditionDrift;

    @Column(name = "stale_automation", nullable = false)
    private boolean staleAutomation;

    @Column(name = "never_executed", nullable = false)
    private boolean neverExecuted;

    @Column(name = "long_unexecuted", nullable = false)
    private boolean longUnexecuted;

    @Column(name = "unlinked", nullable = false)
    private boolean unlinked;

    @Column(name = "failing_repeatedly", nullable = false)
    private boolean failingRepeatedly;

    @Column(name = "orphaned", nullable = false)
    private boolean orphaned;

    /**
     * {@code case_health.incomplete} — mapped so {@code ddl-auto: validate} has the whole row in
     * view, and deliberately absent from {@link HealthSignal}: the contract's enum has no such
     * member, and inventing one would put a value on the wire the SPA cannot label.
     */
    @Column(name = "incomplete", nullable = false)
    private boolean incomplete;

    /**
     * When this row was last derived.
     *
     * <p>Mapped for the dashboard, which reports the newest of these across a project so that "no
     * signal is raised" and "no signal has been computed" render as different sentences. Nothing
     * writes it yet, so the aggregate over an empty table is null — which is exactly the state the
     * dashboard needs to be able to distinguish.
     */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    /** JPA only. */
    protected CaseHealth() {
    }

    public UUID getCaseId() {
        return caseId;
    }

    /**
     * The signals this row asserts, in the enum's declaration order.
     *
     * <p>Reflection-free on purpose even though {@link HealthSignal} names an attribute: that name
     * exists for Criteria, which needs a string, and a second string-driven path here would turn a
     * typo into a silently missing chip instead of the startup failure the mapped field already
     * gives. The switch is exhaustive over the enum, so a new signal is a compile error until it is
     * handled — which is the property that keeps the three uses in step.
     */
    List<HealthSignal> signals() {
        List<HealthSignal> raised = new ArrayList<>();
        for (HealthSignal signal : HealthSignal.values()) {
            boolean set = switch (signal) {
                case OUTDATED_SPEC_CHANGED -> outdatedSpecChanged;
                case STALE_APPROVAL -> staleApproval;
                case CONDITION_DRIFT -> conditionDrift;
                case STALE_AUTOMATION -> staleAutomation;
                case NEVER_EXECUTED -> neverExecuted;
                case LONG_UNEXECUTED -> longUnexecuted;
                case UNLINKED -> unlinked;
                case FAILING_REPEATEDLY -> failingRepeatedly;
                case ORPHANED -> orphaned;
            };
            if (set) {
                raised.add(signal);
            }
        }
        return raised;
    }
}
