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
 * <h2><del>Nothing writes this yet, and the endpoint is correct anyway</del> → <del>FIVE</del> SIX OF
 * THE TEN SIGNALS ARE WRITTEN NOW, AND THAT IS WHAT MAKES THE OTHER <del>FIVE</del> FOUR
 * MISLEADING</h2>
 *
 * <p><del>The signals are computed from executions and documentation links, and neither module
 * exists. So the table is empty, every case reports no signals, and every health facet counts zero.
 * That is the truthful answer rather than a stub: no signal <em>has</em> been computed.</del>
 * <strong>→ superseded 2026-08-09 (§156), and the reversal is the point.</strong> Both modules
 * exist. {@code ExecutionFacts} writes {@link #neverExecuted}, {@link #longUnexecuted},
 * {@link #staleAutomation} and {@link #undeclaredAutomation} (the last two are §120's),
 * {@code ExecutionSource} writes {@link #failingRepeatedly}, and all three stamp
 * {@link #computedAt} in the same statement. So the table is <em>not</em> empty, and the argument
 * above — which was sound while it was empty — no longer holds for the five signals that stayed
 * behind.
 *
 * <p><strong>What changed is what a {@code false} means.</strong> On an absent row, every signal
 * reads "not computed", which is honest. On a row that exists,
 * {@link #outdatedSpecChanged}, <del>{@link #staleApproval}</del>, {@link #conditionDrift},
 * {@link #unlinked} and {@link #orphaned} are {@code NOT NULL} columns holding their default, and
 * the read path cannot tell that from a computation that ran and found nothing wrong.
 * <strong>§157 closed the first of them</strong> — {@code ApprovalFacts} derives
 * {@link #staleApproval} now, so four are left and this paragraph is four-fifths as true as it was. The chip list omits them,
 * the facet counts them zero, and {@code computedAt} — the column mapped precisely so
 * "nothing is wrong" and "nobody has looked" render as different sentences — now says
 * <em>looked</em> for the whole row. The distinction it was added for survives per row and has been
 * lost per signal. {@code ArchitectureRulesTest.everyMappedColumnHasAWriter} is what names the five;
 * closing it is five derivations, not a schema change, and each needs the module that owns its
 * cause.
 *
 * <p>Which is also why there is no factory here and no mutator anywhere: a signal that a human can
 * set is a signal that can be set to false while its cause is still true, and the self-healing
 * property is the entire reason these are derived. It is also why the five are not "fixed" by
 * making them nullable — a three-valued signal moves the problem onto every reader.
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
 *
 * <p><strong>And the sentence above with "no writer for this table" in it is the one §156
 * falsified</strong> — there are three writers now, so {@code computed_at} is non-null wherever a
 * recompute has run, the dashboard stops saying "not computed yet", and the five signals nothing
 * derives are covered by that answer without being part of it. Which is the section above, and the
 * reason it is above this one.
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

    /**
     * <strong>No writer</strong> (§156, {@code ArchitectureRulesTest.WRITERLESS_BY_DECISION}), like
     * the four below it. Its cause is a document change under a linked specification, which is
     * {@code documentation}'s to see and {@code repository}'s to record — so it is an event and a
     * derivation, not a column to fill in here. See the class header for why {@code false} on an
     * existing row is now a claim rather than a silence.
     */
    @Column(name = "outdated_spec_changed", nullable = false)
    private boolean outdatedSpecChanged;

    /**
     * <del>No writer (§156)</del> → <strong>§157 gave it one</strong>, the first of the five to get
     * a derivation: {@code ApprovalFacts} compares the highest {@code APPROVED}
     * {@code review.subject_version_no} against {@code test_case.current_version_no}, which is the
     * comparison {@code ReviewPanel} has drawn its stale banner from since §76.
     *
     * <p><strong>A zero here is now a check that ran</strong>, unlike the four below and above it —
     * which is the distinction the class header says the read path cannot draw, drawn for one
     * column at a time.
     */
    @Column(name = "stale_approval", nullable = false)
    private boolean staleApproval;

    /** No writer (§156) — outdatedSpecChanged's case; the cause is a condition the case no longer matches. */
    @Column(name = "condition_drift", nullable = false)
    private boolean conditionDrift;

    @Column(name = "stale_automation", nullable = false)
    private boolean staleAutomation;

    @Column(name = "never_executed", nullable = false)
    private boolean neverExecuted;

    @Column(name = "long_unexecuted", nullable = false)
    private boolean longUnexecuted;

    /**
     * No writer (§156) — outdatedSpecChanged's case, and <strong>the one the first draft of that
     * rule reported as live</strong>: a fixture's unterminated {@code UPDATE} swallowed the rest of
     * its file's string literals, and an {@code @DisplayName} containing the word "unlinked" stood
     * in for a writer. The cause is a case with no {@code doc_link}, which {@code BlockLinks} can
     * see and nothing derives.
     */
    @Column(name = "unlinked", nullable = false)
    private boolean unlinked;

    @Column(name = "failing_repeatedly", nullable = false)
    private boolean failingRepeatedly;

    /** No writer (§156) — outdatedSpecChanged's case; the cause is a case in no suite and no plan. */
    @Column(name = "orphaned", nullable = false)
    private boolean orphaned;

    /**
     * {@code case_health.undeclared_automation} — {@code V37}, the mirror of
     * {@link #staleAutomation} (plan §6c).
     *
     * <p>Both are derived from one fact on opposite sides of one cutoff, so a case can never raise
     * both. That is a property of the computation and not of the schema, which is why
     * {@code ExecutionFacts} writes them in a single statement rather than two.
     */
    @Column(name = "undeclared_automation", nullable = false)
    private boolean undeclaredAutomation;

    /**
     * {@code case_health.incomplete} — mapped so {@code ddl-auto: validate} has the whole row in
     * view, and deliberately absent from {@link HealthSignal}: the contract's enum has no such
     * member, and inventing one would put a value on the wire the SPA cannot label.
     *
     * <p>§154 made this the reference entry in
     * {@code ArchitectureRulesTest.READERLESS_BY_DECISION}, because the comment above was already
     * in the shape that rule asks of the other twelve: what the column is, why it is mapped
     * anyway, and why it stops there. §156 put it in {@code WRITERLESS_BY_DECISION} as well, which
     * is the harmless overlap: unlike the five signals above it, nothing reads this one either, so
     * there is no reader to be misled by the default it holds.
     */
    @Column(name = "incomplete", nullable = false)
    private boolean incomplete;

    /**
     * When this row was last derived.
     *
     * <p>Mapped for the dashboard, which reports the newest of these across a project so that "no
     * signal is raised" and "no signal has been computed" render as different sentences.
     * <del>Nothing writes it yet, so the aggregate over an empty table is null — which is exactly
     * the state the dashboard needs to be able to distinguish.</del>
     *
     * <p><strong>That sentence was written in §47 and was false from §53 onward; §156 corrected the
     * class header and left this one standing, which is the same slice-shape it was about.</strong>
     * Every writer of this table stamps this column in the same statement, and the recompute is
     * scheduled by default — so the null branch is the one no running deployment is in, and what
     * the dashboard actually needs to distinguish is per-signal rather than per-row. See the class
     * header.
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
                case UNDECLARED_AUTOMATION -> undeclaredAutomation;
            };
            if (set) {
                raised.add(signal);
            }
        }
        return raised;
    }
}
