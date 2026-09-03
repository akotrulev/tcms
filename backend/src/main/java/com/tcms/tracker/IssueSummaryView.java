package com.tcms.tracker;

import com.tcms.api.UserRef;
import com.tcms.tracker.release.ReleaseRefView;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A row in a list — the work table, an epic's children, a story's sub-tasks.
 *
 * <p>{@code epicKey} and {@code epicTitle} are carried so a table need not walk the tree, and
 * {@code agentEligible} rides on the row so the work table can mark it without a second read
 * (D30) — the same reason {@code epicKey} does.
 *
 * <p>{@code overdue} is computed server-side for {@code Issue}'s reason: a client deriving it from
 * {@code dueDate} and its own clock disagrees with the server the moment the two are in different
 * time zones, on the one field a person sorts by.
 */
public record IssueSummaryView(
        String key,
        String title,
        IssueTypeView type,
        IssueStatusView status,
        UUID projectId,
        String projectKey,
        String parentKey,
        String epicKey,
        String epicTitle,
        UserRef assignee,
        LocalDate dueDate,
        boolean overdue,
        Double estimateHours,
        ReleaseRefView release,
        boolean agentEligible) {
}
