package com.tcms.tracker.board;

/**
 * The contract's {@code LinkedCasesSummary} — the roll-up of a card's linked test cases (021 D-k).
 *
 * <p><strong>Not served yet, and the contract says so in words</strong>: {@code linkedCases} is
 * declared nullable with "null until served (021 T4)", and the key is always present so a consumer
 * tells <em>not served</em> from <em>none linked</em> by reading the null rather than by a property
 * that is missing. The shape is here because a component the schema declares and no record carries
 * is a divergence nothing else can see; the read behind it — the same {@code case_health} rows
 * {@code listIssueTestCases} already makes, batched over a board — is T4's.
 */
public record LinkedCasesSummaryView(
        int total, int passing, int failing, int blocked, int notRun) {
}
