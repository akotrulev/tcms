package com.tcms.tracker;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /issues}.
 *
 * <p><strong>No {@code status} field</strong>, for {@code TestPlan.create}'s reason: a create that
 * could choose its column is a move no move was recorded for, and "create it directly in Done" is
 * the shape that lets a board show completed work nobody did. A new issue enters the project's
 * first status <em>by position</em>.
 *
 * <p>No {@code key} either — the key is allocated server-side from the project's own counter
 * ({@link com.tcms.project.ProjectKeys}) and is never a field on the request.
 */
public record IssueInput(
        UUID projectId,
        UUID typeId,
        String title,
        String description,
        String parentKey,
        UUID assigneeId,
        Priority priority,
        Double estimateHours,
        LocalDate startDate,
        LocalDate targetDate,
        LocalDate dueDate,
        List<String> acceptanceCriteria) {
}
