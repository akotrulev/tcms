package com.tcms.tracker.release;

import com.tcms.tracker.IssueLevel;

/**
 * The contract's {@code IssueRef} — an issue as it appears on something that is <em>not</em> an
 * issue, which today is a test case's Verifies column.
 *
 * <p>Distinct from {@code IssueSummaryView}, which is a row in a list <em>of</em> issues and carries
 * a status, an assignee, an epic and an estimate this shape has no room for. Widening one to serve
 * both would put six fields the cases grid never renders on every row of it.
 */
public record IssueRefView(String key, String title, IssueLevel typeLevel) {
}
