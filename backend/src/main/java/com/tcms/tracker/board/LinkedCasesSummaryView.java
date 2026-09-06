package com.tcms.tracker.board;

/**
 * The contract's {@code LinkedCasesSummary} — the roll-up of a card's linked test cases (021 D-k).
 *
 * <p>~~Not served yet, and the contract says so in words.~~ → <strong>served since 021 T4</strong>,
 * from the same {@code case_health} rows {@code listIssueTestCases} reads, batched over a whole
 * board by {@code tracker.release.LinkedCaseFacts}. {@code linkedCases} stays nullable and the key
 * stays always-present, but the null now means one thing rather than two: <em>this card has no
 * linked cases</em>. The set behind it is the issue's subtree — a task borrows its parent story's —
 * and the counts are computed in {@code BoardCardService.Facts.linkedCasesOf}, whose header carries
 * the roll-up rules.
 *
 * <p>{@code total} is a count of <strong>distinct</strong> cases, and
 * {@code passing + failing + blocked + notRun == total} always: only {@code PASSED},
 * {@code FAILED} and {@code BLOCKED} are verdicts, and every other kind joins the never-executed in
 * {@code notRun}.
 */
public record LinkedCasesSummaryView(
        int total, int passing, int failing, int blocked, int notRun) {
}
