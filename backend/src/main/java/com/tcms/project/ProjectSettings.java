package com.tcms.project;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One project's own policy — <strong>the row §6g has needed since the review workflow was
 * designed</strong>, and the thing four separate behaviours were each blocked on.
 *
 * <p>§6g's first sentence is that review is <em>"enabled per project and per artifact type, off by
 * default"</em>, and until {@code V23} there was nowhere to record that. So review was effectively
 * on everywhere the moment §76 shipped the apparatus, {@link Review#getRequiredPeerReviews()} was
 * stamped from a column default nothing could change, §6g's <em>"single most valuable setting"</em>
 * — author-cannot-approve-own-work — did not exist, and {@code ReviewPanel} rendered the stale
 * marker of an edit-after-approval rule whose other half was never built.
 *
 * <h2>Absence is the defaults, not an error</h2>
 *
 * <p>{@code V23} backfills a row for every project and {@code ProjectCreateService} writes one from
 * now on, so a missing row should be unreachable. {@link #defaults} exists anyway, and every read
 * goes through {@link ProjectSettingsService#effective}, because the failure this guards is not
 * hypothetical: a project created by a path nobody remembered, or a row deleted by hand, must fail
 * to the <em>safe</em> state — review off, nothing extra rendered, approval behaving as it did
 * before §76 — rather than 500 on a screen that has nothing to do with settings.
 *
 * <p>That is also why the defaults are duplicated between this class and {@code V23}'s column
 * defaults rather than read out of the database. They are the same four values for the same reason,
 * and the database's copy is what a hand-written {@code INSERT} gets; this one is what a caller with
 * no row gets. A test pins them equal.
 */
@Entity
@Table(name = "project_settings")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ProjectSettings {

    /**
     * The project is the identity. A surrogate {@code id} would permit two settings rows for one
     * project — an ambiguity with no correct resolution, since neither row is more current than the
     * other — where the primary key refuses it outright.
     */
    @Id
    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "review_enabled_case", nullable = false)
    private boolean reviewEnabledCase;

    @Column(name = "review_enabled_suite", nullable = false)
    private boolean reviewEnabledSuite;

    @Column(name = "review_enabled_plan", nullable = false)
    private boolean reviewEnabledPlan;

    @Column(name = "review_enabled_doc", nullable = false)
    private boolean reviewEnabledDoc;

    @Column(name = "required_peer_reviews", nullable = false)
    private int requiredPeerReviews;

    /**
     * Stored in the positive, so that no consumer reads {@code if (!authorCannotApprove)}. §6g's
     * rule is ON when this is {@code false}, which is the default.
     */
    @Column(name = "author_may_approve_own_work", nullable = false)
    private boolean authorMayApproveOwnWork;

    @Enumerated(EnumType.STRING)
    @Column(name = "edit_after_approval", nullable = false)
    private EditAfterApproval editAfterApproval;

    /**
     * §6g's gating rule ({@code V26}), and the reason it sits beside the review columns without
     * being governed by them is on {@link ReviewSettings} and in {@code V26}'s own comment: it
     * reads the status ladder, which switching review off does not take away.
     *
     * <p>~~There were two: {@code require_approved_plan_to_start}.~~ → dropped in {@code V32}
     * (§97, M7) with the {@code ACTIVE} plan state its rule bound on. A toggle whose rule no
     * longer exists is worse than no toggle: an administrator turns it on and nothing happens,
     * with a success message.
     */
    @Column(name = "require_approved_case_for_run", nullable = false)
    private boolean requireApprovedCaseForRun;

    /**
     * D33's "Require review to close issues" ({@code V8}). Off by default, like §6g's four opt-ins
     * and for a sharper reason: the opposite would dead-end a one-person company the moment its own
     * assistant worked a task (A6).
     */
    @Column(name = "review_enabled_issue", nullable = false)
    private boolean reviewEnabledIssue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /**
     * Annotated, not merely mapped — §74's finding, which found {@code lock_version} mapped without
     * {@code @Version} on three entities and the column therefore reading {@code 0} for ever while
     * looking like a guard. Nothing sends an {@code If-Match} for this row today; two admins saving
     * the settings form at once is nonetheless a real race, and a maintained column is what lets a
     * later slice add the header without first discovering it has been lying.
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected ProjectSettings() {
    }

    /**
     * A project's settings as they are before anybody configures them — §6g's own defaults, and the
     * value {@link ProjectSettingsService#effective} answers when a project has no row.
     *
     * <p>Not persisted and not attached: it carries no {@code companyId} and no timestamps, because
     * a caller that saves it is going through {@link #initial} instead.
     */
    static ReviewSettings defaults() {
        return new ReviewSettings(
                false, false, false, false, 0, false, EditAfterApproval.RETAIN, false, false);
    }

    /** The row a newly created project gets — {@link #defaults}, persisted. */
    static ProjectSettings initial(UUID companyId, UUID projectId, Instant now) {
        ProjectSettings created = new ProjectSettings();
        created.projectId = projectId;
        created.companyId = companyId;
        created.createdAt = now;
        created.updatedAt = now;
        created.apply(defaults(), null, now);
        return created;
    }

    /**
     * Replaces every setting at once.
     *
     * <p>A {@code PUT} rather than a {@code PATCH}, so this takes the whole value: the form shows
     * all seven controls and submits all seven, and a partial update would need §74's
     * absent/null/empty rules for a shape where "leave this one alone" has no user-facing meaning —
     * nothing here is a list and nothing here is clearable.
     */
    void apply(ReviewSettings settings, UUID actorId, Instant now) {
        this.reviewEnabledCase = settings.reviewEnabledCase();
        this.reviewEnabledSuite = settings.reviewEnabledSuite();
        this.reviewEnabledPlan = settings.reviewEnabledPlan();
        this.reviewEnabledDoc = settings.reviewEnabledDoc();
        this.requiredPeerReviews = settings.requiredPeerReviews();
        this.authorMayApproveOwnWork = settings.authorMayApproveOwnWork();
        this.editAfterApproval = settings.editAfterApproval();
        this.requireApprovedCaseForRun = settings.requireApprovedCaseForRun();
        this.reviewEnabledIssue = settings.reviewEnabledIssue();
        this.updatedBy = actorId;
        this.updatedAt = now;
    }

    /** The stored row as the value every consumer reads — see {@link ReviewSettings}. */
    ReviewSettings toSettings() {
        return new ReviewSettings(
                reviewEnabledCase,
                reviewEnabledSuite,
                reviewEnabledPlan,
                reviewEnabledDoc,
                requiredPeerReviews,
                authorMayApproveOwnWork,
                editAfterApproval,
                requireApprovedCaseForRun,
                reviewEnabledIssue);
    }

    public UUID getProjectId() {
        return projectId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }
}
