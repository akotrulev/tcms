package com.tcms.tracker;

import java.util.List;

/**
 * D31 — an epic's status, derived from its stories.
 *
 * <p><strong>Not editable, and that is the decision this class exists to enforce.</strong>
 * {@code TransitionService} refuses a move on an epic and {@code IssueService} offers it no legal
 * transitions; this is where the value those two refuse to let anybody set actually comes from.
 * An epic that reads "In Progress" beside a completion of 100% is the disagreement D31 makes
 * unrepresentable, which is why the same {@link CountedIssue} list feeds both.
 *
 * <p>Public since 012 T4, for {@link Completion}'s reason: a board card shows an epic's status and
 * it has to be the same derived value {@code GET /issues/&#123;key&#125;} shows, or one screen says
 * "In Progress" about an epic another says is done.
 *
 * <p>Pure, and five lines. The value is in the two cases the sentence does not spell out: an epic
 * with no counted stories is {@code NOT_STARTED} rather than vacuously {@code DONE}, and a spike
 * never decides the answer — for exactly the reason it is left out of the percentage.
 */
public final class EpicStatus {

    private EpicStatus() {
    }

    public static IssueStatusKind of(List<CountedIssue> children) {
        List<CountedIssue> counted = children.stream()
                .filter(CountedIssue::countsTowardCompletion)
                .toList();

        if (counted.isEmpty()) {
            // "Every counted story is terminal" is TRUE of an empty list, so an allMatch
            // alone reads a brand-new epic as Done.
            return IssueStatusKind.NOT_STARTED;
        }
        if (counted.stream().allMatch(CountedIssue::isDone)) {
            return IssueStatusKind.DONE;
        }
        if (counted.stream().allMatch(child -> child.kind() == IssueStatusKind.NOT_STARTED)) {
            return IssueStatusKind.NOT_STARTED;
        }
        return IssueStatusKind.IN_PROGRESS;
    }
}
