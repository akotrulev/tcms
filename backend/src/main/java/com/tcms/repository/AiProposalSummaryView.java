package com.tcms.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code AiProposalSummary} in {@code docs/specs/openapi.yaml} — one row of the review queue.
 *
 * <p>Deliberately without the payload and without the grounding. A queue of fifty proposals is
 * fifty full test cases and fifty critic reports if the summary carries them, and the reviewer is
 * choosing which one to open, not reading them all — {@code TestCaseSummaryView}'s split, for its
 * reason.
 *
 * <p>{@link #verdict()} and {@link #verdictSource()} <em>are</em> here, both of them, because they
 * are what the choosing is done on: an unverified proposal needs a slower read than a checked one,
 * and a client that could show the verdict without its provenance would show a claim as a finding.
 */
public record AiProposalSummaryView(
        UUID id,
        String title,
        UUID suiteId,
        ProposalVerdict verdict,
        VerdictSource verdictSource,
        ProposalStatus status,
        Instant createdAt,
        UserRefView createdBy,
        boolean filedByAgent,
        int stepCount) {
}
