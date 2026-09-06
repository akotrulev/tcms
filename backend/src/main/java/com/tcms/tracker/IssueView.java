package com.tcms.tracker;

import com.tcms.api.UserRef;
import com.tcms.tracker.release.ReleaseRefView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One issue, with its children, watchers and legal moves — the contract's {@code Issue}.
 *
 * <p><strong>{@code allowedTransitions} is empty for an Epic</strong>, and that is the shape D31
 * asks for rather than an omission: an epic's status is derived from its stories and is not
 * editable, so there is no legal move out of it for anybody. The screen renders the moves disabled
 * rather than hidden ({@code task.html}), which is why the empty list has to be on the wire at all.
 *
 * <p>{@code allowedTransitions} is also <strong>caller-scoped</strong> under D33's review-to-close
 * gate: when a project requires review to close issues and the caller is this issue's assignee,
 * {@code DONE}-kind targets are absent from it. The move exists in the workflow and not for this
 * caller — {@code TransitionService} refuses it with the same reason, so the narrowed list and the
 * 409 are one decision expressed twice rather than two rules that could drift.
 *
 * <p>{@code completion} is <strong>null on every level but Epic</strong> (D8) — completion counts
 * story-level issues, and an epic is the only thing that has them. It is computed here and read
 * everywhere; nothing recomputes it in a component, which is how this repository once shipped two
 * contradictory percentages on one screen.
 */
public record IssueView(
        String key,
        long seq,
        String title,
        String description,
        List<AcceptanceCriterion> acceptanceCriteria,
        IssueTypeView type,
        IssueStatusView status,
        IssueStatusView blockedFromStatus,
        Instant statusEnteredAt,
        UUID projectId,
        String projectKey,
        String parentKey,
        String parentTitle,
        String epicKey,
        String epicTitle,
        UserRef assignee,
        UserRef reporter,
        Priority priority,
        Double estimateHours,
        LocalDate startDate,
        LocalDate targetDate,
        LocalDate dueDate,
        boolean overdue,
        CompletionView completion,
        ReleaseRefView release,
        boolean agentEligible,
        String prUrl,
        List<IssueSummaryView> children,
        List<UserRef> watchers,
        List<IssueStatusView> allowedTransitions,
        Instant createdAt,
        Instant updatedAt,
        Instant doneAt) {
}
