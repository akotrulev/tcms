package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ~~D31 — an epic's status, derived from its stories and not editable.~~ → superseded (021 R10).
 *
 * <p>The status moved onto the epic's own row and the derivation went with it, so what is left
 * here is the <strong>nudge</strong>: every counted story is done and the epic is not. The
 * implementation is four lines and the value is in the last two cases, which are the same two the
 * derivation had — an empty epic and an epic of nothing but spikes must not read as finished.
 *
 * <p>These are the cases that decide whether a card says "ready to close" to a PO, and getting
 * either wrong is silent: a brand-new epic wearing the nudge is not an error anywhere, it is just
 * advice nobody should act on.
 */
@DisplayName("EpicStatus — the nudge, vacuously-true-proof, and consistent with the percentage")
class EpicStatusTest {

    @Test
    void everyCountedStoryDoneIsTheNudge() {
        // Story H5's last criterion, as 021 D-d re-reads it: "given every story in an epic is
        // done" no longer closes the epic — it tells the PO that it could be closed, and the PO
        // drags it. §11: the product automates no transition.
        assertThat(EpicStatus.allStoriesDone(List.of(done(), done()))).isTrue();
    }

    @Test
    void oneUnfinishedStoryIsNotTheNudge() {
        assertThat(EpicStatus.allStoriesDone(List.of(done(), notStarted()))).isFalse();
        assertThat(EpicStatus.allStoriesDone(List.of(done(), inProgress()))).isFalse();
    }

    @Test
    void anEmptyEpicIsNotNudgedRatherThanVacuouslyFinished() {
        // The vacuous-truth trap: "every counted story is done" is TRUE of an empty epic, so a
        // naive allMatch puts "ready to close" on every epic the moment it is created — which is
        // the one case a reader would notice immediately on the screen and no test would.
        assertThat(EpicStatus.allStoriesDone(List.of())).isFalse();
    }

    @Test
    void spikesDoNotDecideTheNudge() {
        // Consistent with the percentage beside it. An epic whose only unfinished work is a spike
        // shows 100%, so a card that showed 100% and withheld the nudge would be two answers to
        // one question — which is the disagreement the shared CountedIssue list exists to prevent.
        assertThat(EpicStatus.allStoriesDone(List.of(done(), spikeNotStarted()))).isTrue();
    }

    @Test
    void anEpicOfNothingButSpikesIsNotNudged() {
        // The two rules above meet here: excluding spikes leaves an empty list, and an empty epic
        // is not nudged. Without this case, "spikes are excluded" and "empty is not nudged" can
        // each be right while their conjunction tells a PO to close an epic full of open research.
        assertThat(EpicStatus.allStoriesDone(List.of(spikeNotStarted(), spikeNotStarted())))
                .isFalse();
    }

    // =====================================================================

    private static CountedIssue notStarted() {
        return new CountedIssue(true, IssueStatusKind.NOT_STARTED, null);
    }

    private static CountedIssue inProgress() {
        return new CountedIssue(true, IssueStatusKind.IN_PROGRESS, null);
    }

    private static CountedIssue done() {
        return new CountedIssue(true, IssueStatusKind.DONE, null);
    }

    private static CountedIssue spikeNotStarted() {
        return new CountedIssue(false, IssueStatusKind.NOT_STARTED, null);
    }
}
