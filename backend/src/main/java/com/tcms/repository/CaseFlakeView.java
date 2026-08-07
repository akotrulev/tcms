package com.tcms.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A case's flake evidence and quarantine state on the wire — {@code CaseFlake}.
 *
 * <h2>The counts ship, and that is the whole reason a null score is legible</h2>
 *
 * <p>{@link #flakeScore} is null below the evidence floor, and a screen showing a blank where a
 * number should be is indistinguishable from a screen that failed to load one. {@link #executions}
 * and {@link #transitions} are what let the SPA say <em>"3 results, not enough to score"</em>
 * instead — which is §47's empty-panel lesson applied to a single field rather than to a panel.
 *
 * <p>{@link #cells} ships for the same kind of reason at one level up: a score of 0 over eight
 * transitions across two cells is a different claim from the same score over one cell, and it is the
 * number that tells a reader the test was exercised on more than one configuration.
 *
 * @param flakeScore alternations ÷ transitions in {@code [0,1]}, or <strong>null when there is not
 *        enough evidence</strong> — never zero as a stand-in. The two mean opposite things: zero is
 *        <em>evidently stable</em> and null is <em>we cannot say</em>.
 * @param quarantinedBy the user id, resolved to a name by the caller like every other actor field
 *        on this API. Null exactly when {@link #quarantined} is false — the database refuses any
 *        other combination.
 * @param computedAt when the derived half was last recomputed. On the wire for
 *        {@code DashboardView.healthComputedAt}'s reason: it is the only thing that tells "this test
 *        is not flaky" from "nobody has looked at this test yet", and those render identically
 *        without it.
 */
public record CaseFlakeView(
        UUID caseId,
        int executions,
        int transitions,
        int alternations,
        int cells,
        BigDecimal flakeScore,
        Instant lastAlternationAt,
        Instant computedAt,
        boolean quarantined,
        Instant quarantinedAt,
        UUID quarantinedBy,
        String quarantineReason) {

    static CaseFlakeView of(CaseFlake flake) {
        return new CaseFlakeView(
                flake.getCaseId(),
                flake.getExecutions(),
                flake.getTransitions(),
                flake.getAlternations(),
                flake.getCells(),
                flake.getFlakeScore(),
                flake.getLastAlternationAt(),
                flake.getComputedAt(),
                flake.isQuarantined(),
                flake.getQuarantinedAt(),
                flake.getQuarantinedBy(),
                flake.getQuarantineReason());
    }
}
