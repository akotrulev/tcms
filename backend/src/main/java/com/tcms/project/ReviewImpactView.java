package com.tcms.project;

import java.util.List;

/**
 * {@code ReviewImpact} in the contract — what a change to the review settings would land on.
 *
 * <p>Counts only, nothing nullable: the review-owned five from this module's own tables, the last
 * two through {@link ReviewImpactSource}. A count the SPI cannot answer is a missing implementation,
 * which is a red build rather than a null on the wire.
 */
public record ReviewImpactView(
        int casesInReview,
        int suitesInReview,
        int plansInReview,
        int docsInReview,
        int awaitingFinalApproval,
        int draftDocPages,
        List<ReviewImpactSource.BlockedPlan> plansBlockedFromRunning) {
}
