package com.tcms.repository;

import com.tcms.api.UserRef;
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
 * @param quarantinedBy <strong>who decided</strong>, resolved here rather than handed out as an id.
 *        This shipped as a bare {@code UUID} in phase 1 under a javadoc claiming it was "resolved to
 *        a name by the caller like every other actor field on this API" — it was the <em>only</em>
 *        actor field on this API that was not a {@code UserRef}, against roughly twenty-five that
 *        are, so the comment asserted the convention it broke. §100's rule: a comment stating a fact
 *        about another file is not a check. Null exactly when {@link #quarantined} is false — the
 *        database refuses any other combination — and also null when the id resolves to no account,
 *        which {@link CaseOwners} treats as unattributed rather than inventing a name.
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
        UserRef quarantinedBy,
        String quarantineReason) {

    /**
     * @param decider the resolved {@link CaseFlake#getQuarantinedBy()}, or null when the row names
     *                nobody or names an id that resolves to no account. Passed in rather than looked
     *                up here for {@link CaseOwners}' reason: a page of cases resolves its people once,
     *                and a view that resolved its own would be one query per row.
     */
    static CaseFlakeView of(CaseFlake flake, UserRef decider) {
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
                decider,
                flake.getQuarantineReason());
    }
}
