package com.tcms.project;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * A project's §6g policy on the wire.
 *
 * <p><strong>Flat, not a nested {@code settings} object.</strong> {@link ReviewSettings} is the
 * internal value because it crosses two module boundaries and wants to be one argument; the response
 * is flat because the contract's {@code required} array does not descend into an inline nested
 * object (the working agreement's rule, which is why every generated property under such an object
 * comes out optional and no consumer can use it). Nine required scalars is the shape a client can
 * actually rely on.
 *
 * <p>{@code updatedBy} and {@code updatedAt} are <strong>nullable</strong> and both are absent for a
 * project nobody has configured — including one whose row {@code V23} backfilled, which is most of
 * them on the day this ships. A client renders "never changed", which is true, rather than
 * attributing the defaults to whoever created the project.
 */
public record ProjectSettingsView(
        UUID projectId,
        boolean reviewEnabledCase,
        boolean reviewEnabledSuite,
        boolean reviewEnabledPlan,
        boolean reviewEnabledDoc,
        int requiredPeerReviews,
        boolean authorMayApproveOwnWork,
        EditAfterApproval editAfterApproval,
        boolean requireApprovedCaseForRun,
        boolean reviewEnabledIssue,
        boolean trackTime,
        UserRef updatedBy,
        Instant updatedAt) {

    /**
     * Assembled from the value every consumer reads, so the wire cannot disagree with the gate.
     *
     * <p>{@code trackTime} is passed beside {@link ReviewSettings} rather than read out of it: it
     * is 016 D-g's switch and not a review setting, and that value crosses two module boundaries
     * as the review policy alone.
     */
    ProjectSettingsView(
            UUID projectId,
            ReviewSettings settings,
            boolean trackTime,
            UserRef updatedBy,
            Instant updatedAt) {

        this(
                projectId,
                settings.reviewEnabledCase(),
                settings.reviewEnabledSuite(),
                settings.reviewEnabledPlan(),
                settings.reviewEnabledDoc(),
                settings.requiredPeerReviews(),
                settings.authorMayApproveOwnWork(),
                settings.editAfterApproval(),
                settings.requireApprovedCaseForRun(),
                settings.reviewEnabledIssue(),
                trackTime,
                updatedBy,
                updatedAt);
    }
}
