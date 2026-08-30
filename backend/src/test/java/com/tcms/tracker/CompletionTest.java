package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.tracker.Completion.Basis;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D8 — an epic's completion, and the basis beside it.
 *
 * <p>No container: {@link Completion} is pure and static, which is what makes nine cases cheap. It
 * is called from {@code IssueService} and from nowhere else — D8's "one number, one place" is not a
 * style rule, this repository has shipped two contradictory percentages on one screen, and a
 * component computing its own is how that happened.
 */
@DisplayName("Completion — one number, its basis beside it, and spikes out of both halves")
class CompletionTest {

    @Test
    void anEpicWithNoCountedIssuesIsZeroPercentByCount() {
        // Zero is a real answer, not an error and not "n/a". An epic somebody just
        // created is 0% of nothing, and the page says "0 of 0 done" — which is
        // honest, where a dash would make the reader wonder if it was broken.
        Completion completion = Completion.of(List.of());

        assertThat(completion.percent()).isZero();
        assertThat(completion.basis()).isEqualTo(Basis.COUNT);
        assertThat(completion.total()).isZero();
    }

    @Test
    void byCountWhenAtLeastOneCountedIssueLacksAnEstimate() {
        // D8's override is ALL-or-nothing: one missing estimate and the whole epic
        // falls back. project-overview.html shows exactly this — "by count · 4 of 10
        // done · 2 lack an estimate".
        Completion completion = Completion.of(List.of(done(8.0), done(4.0), open(null), open(6.0)));

        assertThat(completion.basis()).isEqualTo(Basis.COUNT);
        assertThat(completion.percent()).isEqualTo(50);       // 2 of 4, not 12 of 18 hours
        assertThat(completion.done()).isEqualTo(2);
        assertThat(completion.total()).isEqualTo(4);
    }

    @Test
    void byEstimateWhenEveryCountedIssueCarriesOne() {
        Completion completion = Completion.of(List.of(done(8.0), done(4.0), open(6.0), open(2.0)));

        assertThat(completion.basis()).isEqualTo(Basis.ESTIMATE);
        assertThat(completion.percent()).isEqualTo(60);       // 12h of 20h
        assertThat(completion.estimatedHoursDone()).isEqualTo(12.0);
        assertThat(completion.estimatedHoursTotal()).isEqualTo(20.0);
    }

    @Test
    void aSpikeIsExcludedFromBothNumeratorAndDenominator() {
        // D8. Counting it would let an epic read 40% done having shipped nothing.
        // Note the third assertion: an UNESTIMATED spike must not drag the basis down
        // either, because it is not a counted issue at all — the case a naive
        // "any missing estimate → COUNT" gets wrong.
        Completion completion = Completion.of(List.of(done(8.0), open(2.0), spike(null)));

        assertThat(completion.total()).isEqualTo(2);
        assertThat(completion.excludedSpikes()).isEqualTo(1);
        assertThat(completion.basis()).isEqualTo(Basis.ESTIMATE);
        assertThat(completion.percent()).isEqualTo(80);       // 8h of 10h
    }

    @Test
    void anEpicOfNothingButSpikesIsZeroOfZeroAndSaysHowManyItLeftOut() {
        Completion completion = Completion.of(List.of(spike(3.0), spike(null)));

        assertThat(completion.total()).isZero();
        assertThat(completion.percent()).isZero();
        assertThat(completion.excludedSpikes()).isEqualTo(2);
    }

    @Test
    void thereIsNoPartialCreditForInProgressWork() {
        // D8: "a number that moves before anything ships is the number people learn
        // to distrust, and once distrusted it is decoration." IN_PROGRESS counts
        // exactly as NOT_STARTED does — as not done.
        assertThat(Completion.of(List.of(inProgress(8.0), inProgress(8.0))).percent()).isZero();
    }

    @Test
    void aBugCountsAsUnfinishedWorkUntilItIsDone() {
        // Story H2's third criterion, and it needs no branch: a Bug is a story-level
        // type with counts_toward_completion = true, so it is simply in the list. The
        // test exists so a future "exclude bugs" idea has to delete an assertion that
        // states the decision.
        assertThat(Completion.of(List.of(done(4.0), open(4.0))).percent()).isEqualTo(50);
    }

    @Test
    void percentRoundsTowardTheNumberThatIsNotMisleading() {
        // 1 of 3 is 33, not 34: rounding half-up would let an epic with nothing
        // finished round to 1% and an epic with one story left round to 100%. The
        // second assertion is the one that matters — 100% must mean DONE.
        assertThat(Completion.of(List.of(done(1.0), open(1.0), open(1.0))).percent()).isEqualTo(33);
        assertThat(Completion.of(List.of(done(999.0), open(0.5))).percent()).isEqualTo(99);
    }

    @Test
    void aZeroHourEstimateKeepsTheEstimateBasisAndDoesNotDivideByZero() {
        // Zero is a legitimate estimate (see IssueEditTest). An epic where every
        // counted issue is estimated at zero has an ESTIMATE basis and no
        // denominator — the answer is 0%, not a crash and not a fallback to COUNT,
        // because falling back would make the page say "by count" about an epic that
        // is fully estimated.
        Completion completion = Completion.of(List.of(done(0.0), open(0.0)));

        assertThat(completion.basis()).isEqualTo(Basis.ESTIMATE);
        assertThat(completion.percent()).isZero();
    }

    // =====================================================================

    private static CountedIssue done(Double hours) {
        return new CountedIssue(true, IssueStatusKind.DONE, hours);
    }

    private static CountedIssue open(Double hours) {
        return new CountedIssue(true, IssueStatusKind.NOT_STARTED, hours);
    }

    private static CountedIssue inProgress(Double hours) {
        return new CountedIssue(true, IssueStatusKind.IN_PROGRESS, hours);
    }

    private static CountedIssue spike(Double hours) {
        return new CountedIssue(false, IssueStatusKind.NOT_STARTED, hours);
    }
}
