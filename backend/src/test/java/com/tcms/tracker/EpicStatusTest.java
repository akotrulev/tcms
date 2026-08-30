package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D31 — an epic's status, derived from its stories and not editable.
 *
 * <p>The implementation is five lines; the value is in the last two cases.
 */
@DisplayName("EpicStatus — derived, vacuously-true-proof, and consistent with the percentage")
class EpicStatusTest {

    @Test
    void noStoryStartedReadsNotStarted() {
        assertThat(EpicStatus.of(List.of(notStarted(), notStarted())))
                .isEqualTo(IssueStatusKind.NOT_STARTED);
    }

    @Test
    void everyCountedStoryTerminalReadsDone() {
        // Story H5's last criterion: "given every story in an epic is done, its status
        // reads Done WITHOUT ANYONE EDITING IT". project-overview.html says the same
        // from the other end — "closed itself when the last story closed".
        assertThat(EpicStatus.of(List.of(done(), done()))).isEqualTo(IssueStatusKind.DONE);
    }

    @Test
    void anythingElseReadsInProgress() {
        assertThat(EpicStatus.of(List.of(done(), notStarted())))
                .isEqualTo(IssueStatusKind.IN_PROGRESS);
        assertThat(EpicStatus.of(List.of(inProgress()))).isEqualTo(IssueStatusKind.IN_PROGRESS);
    }

    @Test
    void anEmptyEpicIsNotStartedRatherThanDone() {
        // The vacuous-truth trap: "every counted story is terminal" is TRUE of an empty
        // epic, so a naive allMatch reads a brand-new epic as Done. It is the one case
        // D31 does not spell out, and the one a reader would notice immediately on the
        // screen.
        assertThat(EpicStatus.of(List.of())).isEqualTo(IssueStatusKind.NOT_STARTED);
    }

    @Test
    void spikesDoNotDecideAnEpicsStatus() {
        // Consistent with the percentage beside it. An epic whose only unfinished work
        // is a spike shows 100% — so it must not also show "In Progress", which is
        // precisely the disagreement D31 exists to make unrepresentable.
        assertThat(EpicStatus.of(List.of(done(), spikeNotStarted())))
                .isEqualTo(IssueStatusKind.DONE);
    }

    @Test
    void anEpicOfNothingButSpikesIsNotStarted() {
        // The two rules above meet here: excluding spikes leaves an empty list, and an
        // empty epic is NOT_STARTED rather than Done. Without this case, "spikes are
        // excluded" and "empty is not started" can each be right while their
        // conjunction reads a brand-new epic full of research as finished.
        assertThat(EpicStatus.of(List.of(spikeNotStarted(), spikeNotStarted())))
                .isEqualTo(IssueStatusKind.NOT_STARTED);
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
