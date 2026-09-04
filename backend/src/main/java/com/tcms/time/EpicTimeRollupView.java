package com.tcms.time;

import java.util.List;

/**
 * The epic time panel — totals, and a row per counted child.
 *
 * <p>Aggregated at read time by joining the tracker's projection. There is no epic-grain rollup
 * table (016 D-c), because three stored grains are three tables that can disagree about the same
 * hours.
 */
public record EpicTimeRollupView(
        String epicKey,
        double estimateHours,
        double approvedHours,
        double pendingHours,
        List<ChildView> children) {

    /**
     * @param overEstimate approved hours past the estimate — the highlighted row on {@code
     *                     epic.html}. False when nothing was estimated, because an unestimated story
     *                     cannot be over its estimate
     */
    public record ChildView(
            String issueKey,
            String title,
            Double estimateHours,
            double approvedHours,
            boolean overEstimate) {
    }
}
