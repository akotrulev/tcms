package com.tcms.tracker.board;

import com.tcms.api.UserRef;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.Priority;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code BoardIssueCard} — <strong>not {@code BoardCard}</strong>.
 *
 * <p>That schema is {@code work}'s composite card and carries four kinds, a {@code derived} flag and
 * a synthetic id. Every card here is an issue with a real key, so the fields that exist only to
 * describe a derived card would be constant and misleading.
 *
 * @param status            for an Epic this is the <strong>derived</strong> status (D31), the same
 *                          value {@code GET /issues/&#123;key&#125;} shows — an epic reading "In
 *                          Progress" on the board while its detail page says it is done is the
 *                          disagreement D31 exists to make unrepresentable
 * @param rank              a decimal as a string, for {@link RankResultView#rank()}'s reason
 * @param allowedTransitions §12.4, verbatim and load-bearing: the legal set must be known
 *                          client-side <em>before the drag starts</em>, so a forbidden drop is
 *                          refused at the drag with the reason rather than accepted and reverted.
 *                          Empty for an Epic (D31)
 */
public record BoardIssueCardView(
        String key,
        String title,
        String typeName,
        IssueLevel typeLevel,
        String iconCode,
        boolean countsTowardCompletion,
        UUID columnId,
        IssueStatusView status,
        String rank,
        Priority priority,
        UserRef assignee,
        LocalDate dueDate,
        boolean overdue,
        Double estimateHours,
        String epicKey,
        List<LabelView> labels,
        List<IssueStatusView> allowedTransitions,
        int subtaskTotal,
        int subtaskDone) {
}
