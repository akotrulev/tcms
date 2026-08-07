package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One case's flake evidence and its quarantine decision — {@code V38}'s {@code case_flake}.
 *
 * <p><strong>This is the one table in the product whose row is half derived and half authored</strong>,
 * and the split is the design rather than an accident of convenience. §6c names the two in one
 * breath — <em>"result history per automation key, flake score, quarantine flag"</em> — and they
 * cannot share {@code case_health}, whose own table comment forbids it: <em>"signals are DERIVED,
 * never authored … no 'mark as not-stale' button exists to lie with"</em>.
 *
 * <h2>The boundary is the {@code ON CONFLICT} set-list, and it now bites inside one table</h2>
 *
 * <p>{@code repository.ExecutionFacts} states the rule the whole read-model design runs on — <em>"a
 * signal's owner writes its own column and no others; the row is shared, the columns are not"</em>
 * — and §120 was the first slice where it bit between two <em>live</em> writers. Here it binds two
 * writers of one row:
 *
 * <ul>
 *   <li>{@code reporting.ExecutionSource.recomputeFlakeScores} owns the seven derived columns and
 *       names exactly those. A case quarantined by a human in March is still quarantined after the
 *       next tick — not because the job is careful, but because the columns are unreachable from
 *       its statement.</li>
 *   <li>{@code CaseQuarantineService} owns the four authored ones. Recording a
 *       decision cannot rewrite the evidence the decision was made on, which is the direction that
 *       would be much harder to notice: the score would simply be a tick stale, and a stale score
 *       renders exactly like a fresh one.</li>
 * </ul>
 *
 * <h2>Why quarantine is authored, when deriving it would have been free</h2>
 *
 * <p>A score crossing a threshold is a fact, and §6o's no-button-to-lie-with rule would forbid a
 * human overriding it. But quarantining is not a claim about the world — it is a <em>decision</em>,
 * "stop gating on this test", and §6o forbids authoring a derived claim rather than recording a
 * choice. It is also the only version in which §6l's board card means anything: a card derived from
 * the score would vanish the moment the test stopped flaking, taking the debt with it while the
 * test was still excluded from gating.
 *
 * <h2>No factory for the derived half, and no {@code lock_version} at all</h2>
 *
 * <p>The derived columns have no mutator here for {@link CaseHealth}'s reason: the recompute writes
 * them in one {@code INSERT … ON CONFLICT}, and a Java-side setter would be a second way to author
 * a number that is supposed to be evidence. The authored half does have one — {@link #quarantine}
 * and {@link #release} — because that is what an authored column is.
 *
 * <p>No optimistic lock. The toggle is a single-writer transition from one action, not two screens
 * racing over a form; {@code IngestionJob}'s argument, {@code report_job}'s shape rather than
 * {@code test_case}'s.
 */
@Entity
@Table(name = "case_flake")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CaseFlake {

    /** Also the primary key: one row per case, or none. {@link CaseHealth}'s shape exactly. */
    @Id
    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    /** Recorded outcomes inside the window that were a pass or a {@code FAILED}. */
    @Column(name = "executions", nullable = false)
    private int executions;

    /**
     * Adjacent pairs of outcomes <em>inside one cell</em> — the denominator of {@link #flakeScore},
     * and deliberately not {@link #executions}. A case with six results spread across six cells has
     * six executions and zero transitions, because nothing in it ever ran twice under the same
     * conditions, and a score over the execution count would report that case as perfectly stable
     * on evidence that says nothing at all.
     */
    @Column(name = "transitions", nullable = false)
    private int transitions;

    /** How many of those pairs changed between a pass and a {@code FAILED}. */
    @Column(name = "alternations", nullable = false)
    private int alternations;

    /**
     * How many {@code (configuration, case version)} cells contributed.
     *
     * <p>Stored rather than derivable, because it is what tells a test that flaps on one browser
     * from one that flaps on five — the same distinction §6l's <em>stability by configuration</em>
     * matrix exists for, at one number instead of a grid.
     */
    @Column(name = "cells", nullable = false)
    private int cells;

    /**
     * {@link #alternations} / {@link #transitions}, in {@code [0,1]} — <strong>or null below the
     * evidence floor, which is a different claim from zero.</strong>
     *
     * <p>Two results that went pass then fail score {@code 1.0} on any ratio definition, and that is
     * noise rather than a flake. {@code 0.0} means <em>evidently stable</em>; null means <em>not
     * enough evidence to say</em>. Collapsing them is the confident zero §95's {@code usage_count}
     * and §100's exit criteria both record, and it is worse here than in either, because the number
     * this one would invent is the one a human uses to decide whether to quarantine.
     *
     * <p>{@link BigDecimal} rather than {@code double}: the column is {@code NUMERIC(5,4)} and a
     * binary float would round-trip to a different value than the one the {@code CHECK} admitted —
     * §60's defect in its arithmetic form.
     */
    @Column(name = "flake_score")
    private BigDecimal flakeScore;

    /** The newest moment an outcome changed inside a cell. Null when nothing ever alternated. */
    @Column(name = "last_alternation_at")
    private Instant lastAlternationAt;

    /**
     * When the derived half was last computed.
     *
     * <p>Mapped for {@link CaseHealth#getCaseId()}'s neighbour's reason: an absent score and an
     * uncomputed score render identically, and only this column tells them apart.
     */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Column(name = "quarantined", nullable = false)
    private boolean quarantined;

    @Column(name = "quarantined_at")
    private Instant quarantinedAt;

    @Column(name = "quarantined_by")
    private UUID quarantinedBy;

    /**
     * Required whenever {@link #quarantined} — {@code case_flake_quarantine_complete} in {@code V38}
     * enforces it in the database as well, because a quarantine with no reason is debt nobody can
     * clear: the board card says a test is excluded and cannot say why or by whom, so the only way
     * to evaluate it is to read the test.
     */
    @Column(name = "quarantine_reason")
    private String quarantineReason;

    /** JPA only. */
    protected CaseFlake() {
    }

    /**
     * A row for a case that has not been scored yet and is not quarantined.
     *
     * <p>Used only by the quarantine write, for the case where a human quarantines a test the
     * recompute has never produced a row for. That is a real sequence rather than a corner: a run
     * that has just gone red is quarantined within the hour, and the fact-log refresh is on a
     * schedule. The derived columns take their {@code V38} defaults, which is what "no evidence"
     * already means everywhere else in this table — and the next tick fills them in without
     * touching the decision recorded here.
     */
    static CaseFlake unscored(UUID caseId, UUID companyId, UUID projectId, Instant now) {
        CaseFlake flake = new CaseFlake();
        flake.caseId = caseId;
        flake.companyId = companyId;
        flake.projectId = projectId;
        flake.executions = 0;
        flake.transitions = 0;
        flake.alternations = 0;
        flake.cells = 0;
        flake.flakeScore = null;
        flake.computedAt = now;
        flake.quarantined = false;
        return flake;
    }

    /**
     * Record the decision to stop gating on this test.
     *
     * <p>Sets all four authored columns together, which is what makes the {@code CHECK} unreachable
     * rather than merely satisfied — there is no path here that sets the flag without the reason,
     * so a future caller cannot add a one-argument toggle without deleting this signature.
     */
    void quarantine(String reason, UUID by, Instant at) {
        this.quarantined = true;
        this.quarantineReason = reason;
        this.quarantinedBy = by;
        this.quarantinedAt = at;
    }

    /**
     * Put the test back in the gate.
     *
     * <p>Clears all four, for the same reason {@link #quarantine} sets all four: a released row that
     * kept its reason would render as "quarantined by Ana — flaky on Firefox" everywhere the reason
     * is shown, with a flag saying otherwise, and the two would be read in whichever order the
     * screen happened to put them.
     */
    void release() {
        this.quarantined = false;
        this.quarantineReason = null;
        this.quarantinedBy = null;
        this.quarantinedAt = null;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getExecutions() {
        return executions;
    }

    public int getTransitions() {
        return transitions;
    }

    public int getAlternations() {
        return alternations;
    }

    public int getCells() {
        return cells;
    }

    public BigDecimal getFlakeScore() {
        return flakeScore;
    }

    public Instant getLastAlternationAt() {
        return lastAlternationAt;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public boolean isQuarantined() {
        return quarantined;
    }

    public Instant getQuarantinedAt() {
        return quarantinedAt;
    }

    public UUID getQuarantinedBy() {
        return quarantinedBy;
    }

    public String getQuarantineReason() {
        return quarantineReason;
    }
}
