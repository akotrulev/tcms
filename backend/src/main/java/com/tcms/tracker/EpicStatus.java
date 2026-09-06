package com.tcms.tracker;

import java.util.List;

/**
 * ~~D31 — an epic's status, derived from its stories.~~ → superseded (021 R10 / D-d).
 *
 * <h2>Why the status left this class</h2>
 *
 * <p>D31 conflated two facts about an epic. The <em>percentage</em> is honestly derived from the
 * counted stories, and it stayed where it was — {@link Completion} computes it and nothing here
 * ever did. The <em>status</em> was derived only because an epic had no lifecycle of its own; it
 * has one now (New → Refining → Ready to develop → … → Done), it is stored in
 * {@code issue.status_id} like any other issue's, the PO moves it, and {@code TransitionService}
 * accepts that move. So {@code of(List)} — the four-armed kind derivation this class was — is gone
 * rather than kept unused, and with it the third spelling of it in {@code IssueFactProjection}'s
 * SQL.
 *
 * <p>What survives is the one thing the old derivation was genuinely useful for: telling the PO
 * that the work underneath is finished. {@link #allStoriesDone} is that sentence, and it is
 * <strong>a nudge and never a transition</strong> — the server does not move an epic on its own,
 * because "every story is done" and "this body of work is finished" are different claims and only
 * a person can make the second (§11: <em>does not automate any transition</em>).
 *
 * <p>Pure, and four lines. The value is in the two cases the sentence does not spell out, which are
 * the same two the derivation had: an epic with no counted stories is <strong>not</strong> done
 * (the vacuous truth that would put the nudge on every brand-new epic in the project), and a spike
 * never decides the answer — for exactly the reason it is left out of the percentage.
 */
public final class EpicStatus {

    private EpicStatus() {
    }

    /**
     * Whether every story under this epic that counts toward completion is in a {@code DONE}-kind
     * status — the PM card's nudge (021 §2.6, D-d).
     *
     * <p>False for an epic with no counted children at all: {@code allMatch} is true of an empty
     * list, so the guard is what stops a new epic reading as finished.
     */
    public static boolean allStoriesDone(List<CountedIssue> children) {
        List<CountedIssue> counted = children.stream()
                .filter(CountedIssue::countsTowardCompletion)
                .toList();

        return !counted.isEmpty() && counted.stream().allMatch(CountedIssue::isDone);
    }
}
