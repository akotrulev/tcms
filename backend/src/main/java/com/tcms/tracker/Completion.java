package com.tcms.tracker;

import java.util.List;

/**
 * D8 — an epic's completion, and <strong>the basis is returned beside the number, always</strong>.
 *
 * <p>Pure and static, so its nine cases cost no container. It is called from {@code IssueService}
 * and from nowhere else: D8's "one number, one place" is not a style rule — this repository has
 * shipped two contradictory percentages on one screen, and a component that computes its own is how
 * that happened.
 *
 * <h2>Why the basis is on the wire and never inferred by the client</h2>
 *
 * <p>A client asked to infer it would have to know that <em>every counted issue</em> carries an
 * estimate, which means shipping the whole list to a screen that wanted one number. Worse, the two
 * sides would infer it from different data the first time a filter narrowed one of them — and a
 * percentage whose meaning depends on which screen you are looking at is the failure D8 spends a
 * paragraph on.
 *
 * <h2>An unestimated epic gets a coarser number, not a wrong one</h2>
 *
 * <p>Nobody is forced to estimate for a number to exist. The override is all-or-nothing on purpose:
 * a mix of estimated and unestimated issues weighted by hours would silently treat the unestimated
 * ones as zero, so an epic would read <em>higher</em> the less it was planned.
 */
record Completion(
        int percent,
        Basis basis,
        int done,
        int total,
        Double estimatedHoursDone,
        Double estimatedHoursTotal,
        int excludedSpikes) {

    /** What the percentage above counts. On the wire, because a client must never infer it. */
    enum Basis {

        /** Issues. The fallback the moment one counted issue lacks an estimate. */
        COUNT,

        /** Hours — only when every counted issue carries an estimate. */
        ESTIMATE
    }

    static Completion of(List<CountedIssue> children) {
        List<CountedIssue> counted = children.stream()
                .filter(CountedIssue::countsTowardCompletion)
                .toList();
        int excluded = children.size() - counted.size();

        if (counted.isEmpty()) {
            return new Completion(0, Basis.COUNT, 0, 0, null, null, excluded);
        }
        boolean everyOneEstimated = counted.stream().allMatch(child -> child.estimateHours() != null);
        int doneCount = (int) counted.stream().filter(CountedIssue::isDone).count();

        if (!everyOneEstimated) {
            // Integer division truncates, which is the rounding this wants: 1 of 3 is
            // 33 and 999 of 999.5 is 99, so 100% means done and nothing else does.
            return new Completion(doneCount * 100 / counted.size(), Basis.COUNT,
                    doneCount, counted.size(), null, null, excluded);
        }
        double total = counted.stream().mapToDouble(CountedIssue::estimateHours).sum();
        double finished = counted.stream()
                .filter(CountedIssue::isDone)
                .mapToDouble(CountedIssue::estimateHours)
                .sum();

        // Every counted issue estimated at zero is a fully-estimated epic with no
        // denominator. 0% and basis ESTIMATE — falling back to COUNT here would make
        // the page say "by count" about an epic that is entirely estimated.
        int percent = total == 0 ? 0 : (int) (finished * 100 / total);
        return new Completion(percent, Basis.ESTIMATE, doneCount, counted.size(),
                finished, total, excluded);
    }

    /** The wire shape, which names the basis as a string because the contract's enum is one. */
    CompletionView view() {
        return new CompletionView(percent, basis.name(), done, total,
                estimatedHoursDone, estimatedHoursTotal, excludedSpikes);
    }
}
