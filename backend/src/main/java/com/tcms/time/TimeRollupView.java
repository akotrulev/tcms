package com.tcms.time;

/**
 * Estimate against approved and pending hours for one issue.
 *
 * @param estimateHours read from the tracker's projection. Null when nobody estimated, which is not
 *                      zero: an unestimated story is not a story estimated at nothing
 * @param pendingHours  logged and not yet approved. Rendered separately and <strong>never summed
 *                      into approved</strong> (016 D-d)
 */
public record TimeRollupView(
        String issueKey, Double estimateHours, double approvedHours, double pendingHours) {
}
