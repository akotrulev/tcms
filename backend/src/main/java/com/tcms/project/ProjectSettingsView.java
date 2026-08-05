package com.tcms.project;

import java.time.Instant;
import java.util.UUID;

/**
 * A project's §6g policy on the wire.
 *
 * <p><strong>Flat, not a nested {@code settings} object.</strong> {@link ReviewSettings} is the
 * internal value because it crosses two module boundaries and wants to be one argument; the response
 * is flat because the contract's {@code required} array does not descend into an inline nested
 * object (the working agreement's rule, which is why every generated property under such an object
 * comes out optional and no consumer can use it). Eight required scalars is the shape a client can
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
        int requiredPeerReviews,
        boolean authorMayApproveOwnWork,
        EditAfterApproval editAfterApproval,
        boolean requireApprovedCaseForRun,
        UserRefView updatedBy,
        Instant updatedAt) {

    /** Assembled from the value every consumer reads, so the wire cannot disagree with the gate. */
    ProjectSettingsView(
            UUID projectId, ReviewSettings settings, UserRefView updatedBy, Instant updatedAt) {

        this(
                projectId,
                settings.reviewEnabledCase(),
                settings.reviewEnabledSuite(),
                settings.reviewEnabledPlan(),
                settings.requiredPeerReviews(),
                settings.authorMayApproveOwnWork(),
                settings.editAfterApproval(),
                settings.requireApprovedCaseForRun(),
                updatedBy,
                updatedAt);
    }
}
