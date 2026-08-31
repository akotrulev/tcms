package com.tcms.tracker;

/**
 * One story-level issue, flattened to the three facts an epic's two derived numbers need.
 *
 * <p>Shared by {@link Completion} and {@link EpicStatus} rather than one record each, and that is
 * the point rather than thrift: the percentage and the status are computed from the <em>same</em>
 * list, and D31's whole argument is that they must not be able to disagree. Two records would be two
 * places to decide what "counted" means.
 *
 * @param countsTowardCompletion {@code issue_type.counts_toward_completion} — false for a Spike, and
 *     for every {@code EPIC}-level type, which is what keeps an epic out of its own count
 * @param kind                   the issue's status's {@code kind}, never its name (D2)
 * @param estimateHours          null means "not estimated", which is what flips a whole epic's
 *     basis from {@code ESTIMATE} to {@code COUNT}
 */
public record CountedIssue(boolean countsTowardCompletion, IssueStatusKind kind, Double estimateHours) {

    public boolean isDone() {
        return kind == IssueStatusKind.DONE;
    }
}
