package com.tcms.tracker;

/**
 * The contract's {@code IssueCompletion} — D8, and <strong>the basis is on the wire</strong>.
 *
 * <p>Never inferred by the client. A client asked to infer it would have to know that every counted
 * issue carries an estimate, which means shipping the whole list to a screen that wanted one number
 * — and the two sides would infer differently the first time a filter narrowed one of them.
 *
 * <p>Part B fills this in from {@code Completion}. Until then an epic carries {@code null}, which is
 * the contract's nullable value and not a zero pretending to be an answer.
 */
public record CompletionView(
        int percent,
        String basis,
        int done,
        int total,
        Double estimatedHoursDone,
        Double estimatedHoursTotal,
        int excludedSpikes) {
}
