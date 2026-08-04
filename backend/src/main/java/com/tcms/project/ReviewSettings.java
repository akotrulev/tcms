package com.tcms.project;

/**
 * One project's review policy as a value — <strong>the single shape every consumer of §6g's
 * settings reads</strong>, whether the project has a stored row or not.
 *
 * <p>{@link ProjectSettings} is the row; this is what it resolves to, and what
 * {@link ProjectSettings#defaults()} answers when there is no row. Consumers take this rather than
 * the entity for the ordinary reason — it crosses out of {@code project} into {@code repository} and
 * {@code execution}, and handing a managed entity across a module boundary hands out its mutators
 * too — and for one specific to this table: a caller that held the entity could ask a
 * <em>different</em> question of it than {@link #reviewEnabledFor} answers, which is how the
 * per-artifact-type opt-in would quietly become per-project.
 *
 * <h2>The last two are §6g's gating rules, and they are deliberately not review settings</h2>
 *
 * <p>They live on the same value because they are the same project's §6g policy and every consumer
 * that has one already has the other. They are <strong>not</strong> governed by
 * {@link #reviewEnabledFor}, and that is the one thing about this record that looks like an
 * oversight and is not — the argument is on {@code V26} and it is §77's own: <em>"off" is narrow.
 * It suppresses the reviewer apparatus and not the status ladder.</em> Gating reads the status
 * ladder, which a project with review switched off still has and still uses, so it is its own
 * opt-in with its own default. A consumer that ANDed a gating flag with the opt-in would silently
 * disable the gate on exactly the projects that never turned peer review on — which is most of them,
 * and which includes every project that enabled gating for the reason it exists.
 *
 * @param reviewEnabledCase          §6g's opt-in for cases
 * @param reviewEnabledSuite         §6g's opt-in for suites
 * @param reviewEnabledPlan          §6g's opt-in for plans
 * @param requiredPeerReviews        copied onto each review at submission, never read live — see
 *                                   {@link Review#getRequiredPeerReviews()}
 * @param authorMayApproveOwnWork    {@code false} means §6g's rule is on, which is the default
 * @param editAfterApproval          what an edit to an approved artifact does
 * @param requireApprovedCaseForRun  §6g's gating rule for cases — every case a new run materialises
 *                                   must be {@code APPROVED}, on the resolved set and not merely on
 *                                   the explicitly named one
 * @param requireApprovedPlanToStart §6g's gating rule for plans — a plan is started from
 *                                   {@code APPROVED}, which binds on the {@code DRAFT → ACTIVE}
 *                                   edge alone and never on a reopen
 */
public record ReviewSettings(
        boolean reviewEnabledCase,
        boolean reviewEnabledSuite,
        boolean reviewEnabledPlan,
        int requiredPeerReviews,
        boolean authorMayApproveOwnWork,
        EditAfterApproval editAfterApproval,
        boolean requireApprovedCaseForRun,
        boolean requireApprovedPlanToStart) {

    /**
     * Whether §6g's workflow is switched on for one kind of artifact in this project.
     *
     * <p>A switch over {@link ReviewSubjectType} rather than three public booleans a caller picks
     * from, so that <strong>a fourth subject type is a compile error here</strong> instead of
     * silently defaulting to whichever branch an {@code if} chain ended on. Under {@code -Werror}
     * the switch must be exhaustive, which is the same property {@code LifecycleLadder.decide} and
     * {@code PermissionQualifier}'s consumers rely on — and it is the reason {@code V23} stores
     * three columns rather than an array: a new subject type needs a ladder, an opt-in and a
     * migration, and this is where it is made to say so.
     *
     * <p><strong>What "off" means is deliberately narrow.</strong> It suppresses the reviewer
     * apparatus — no review row is opened, no peer verdict can be recorded, no panel is rendered —
     * and it does <em>not</em> touch the status ladder. A project turning review off must not
     * discover that {@code IN_REVIEW} and {@code APPROVED} have been withdrawn from a four-value
     * status its team has used since M1, and since the default is off, the opposite reading would
     * have made every existing project lose its approvals on the day this shipped.
     */
    public boolean reviewEnabledFor(ReviewSubjectType subjectType) {
        return switch (subjectType) {
            case CASE -> reviewEnabledCase;
            case SUITE -> reviewEnabledSuite;
            case PLAN -> reviewEnabledPlan;
        };
    }
}
