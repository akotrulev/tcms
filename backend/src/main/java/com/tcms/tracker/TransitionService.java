package com.tcms.tracker;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Moving an issue to another status — <strong>the only path {@code issue.status_id} is written
 * on</strong>.
 *
 * <p>That is the design rather than an accident of layout. The workflow's legal-move set is one
 * rule, and a second writer of the column (a {@code PATCH} that accepted a status, say) would be a
 * second, unchecked spelling of it — so {@code IssueUpdate} has no {@code status} field and this
 * class has no field edits.
 *
 * <h2>Two refusals, both 409, and they are different facts</h2>
 *
 * <ul>
 *   <li><strong>The workflow forbids this pair.</strong> {@code issue_transition} is an allow-list,
 *       so a pair with no row is refused — story H5: "a move the project forbids is never offered",
 *       and on the board "the card does not move and the board tells me why" rather than snapping
 *       back in silence.</li>
 *   <li><strong>The issue is an Epic.</strong> D31 derives an epic's status from its stories and it
 *       is not editable. Refused here rather than by omitting rows from the workflow table, and
 *       deliberately: an epic has no status rows of its own to omit, and a manual epic status is
 *       exactly the "In Progress, 100%" disagreement D31 exists to make unrepresentable.</li>
 * </ul>
 *
 * <p>{@code status_entered_at} moves on every accepted transition; {@code done_at} is set the FIRST
 * time a {@code DONE}-kind status is reached and is <strong>never cleared by a reopen</strong> — an
 * issue that has been done once has been done once, and a lead time computed from a cleared column
 * silently lengthens every time somebody reopens something.
 */
@Service
public class TransitionService {

    /**
     * <strong>There is no {@code board:move} permission.</strong> Dragging a card changes the
     * issue's status and costs exactly what editing it costs (§A3, and the contract says so).
     */
    private static final String EDIT_ISSUE = "issue:edit";

    private final ProjectService projects;
    private final IssueService issueViews;
    private final IssueRepository issues;
    private final IssueTypeRepository types;
    private final IssueStatusRepository statuses;
    private final IssueTransitionRepository transitions;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public TransitionService(
            ProjectService projects,
            IssueService issueViews,
            IssueRepository issues,
            IssueTypeRepository types,
            IssueStatusRepository statuses,
            IssueTransitionRepository transitions,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.issueViews = issueViews;
        this.issues = issues;
        this.types = types;
        this.statuses = statuses;
        this.transitions = transitions;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** The body named a status this project does not have — a field error, not a 404. */
        record UnknownStatus() implements Outcome {
        }

        /** The workflow has no row for this pair. */
        record NotAllowed(String from, String to) implements Outcome {
        }

        /** D31 — an epic's status is derived from its stories and is not editable. */
        record DerivedStatus() implements Outcome {
        }

        record Moved(IssueView view) implements Outcome {
        }
    }

    public GateResult<Outcome> move(UUID userId, String key, UUID targetStatusId) {
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, issue.get().getProjectId(), EDIT_ISSUE,
                project -> apply(userId, project, issue.get(), targetStatusId));
    }

    private Outcome apply(UUID userId, Project project, Issue issue, UUID targetStatusId) {
        IssueType type = types.findById(issue.getTypeId()).orElseThrow(() ->
                new IllegalStateException("issue " + issue.getIssueKey()
                        + " points at a type that is not in its project"));
        if (type.getLevel() == IssueLevel.EPIC) {
            return new Outcome.DerivedStatus();
        }

        List<IssueStatus> projectStatuses =
                statuses.findByProjectIdOrderByPositionAsc(project.getId());
        IssueStatus current = projectStatuses.stream()
                .filter(status -> status.getId().equals(issue.getStatusId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("issue " + issue.getIssueKey()
                        + " points at a status that is not in its project"));
        Optional<IssueStatus> target = projectStatuses.stream()
                .filter(status -> status.getId().equals(targetStatusId))
                .findFirst();
        if (target.isEmpty()) {
            return new Outcome.UnknownStatus();
        }

        if (targetStatusId.equals(issue.getStatusId())) {
            // A status is always a legal destination from itself, and there is no row saying so:
            // ck_issue_transition_distinct refuses the self-pair, because storing it would make
            // "no moves allowed" and "only the current one" the same set. Answered as a move that
            // changes nothing rather than as a refusal, so a client retrying a dropped response
            // sees the state it asked for instead of a 409 about a move it already made.
            return new Outcome.Moved(issueViews.viewOf(issue, project));
        }

        boolean allowed = transitions.findByFromStatusId(issue.getStatusId()).stream()
                .anyMatch(move -> move.getToStatusId().equals(targetStatusId));
        if (!allowed) {
            return new Outcome.NotAllowed(current.getName(), target.get().getName());
        }

        Instant now = clock.instant();
        issue.moveTo(targetStatusId, target.get().getKind() == IssueStatusKind.DONE, userId, now);
        Issue moved = issues.save(issue);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                "issue.moved",
                "issue",
                List.of(moved.getId()),
                "{\"key\":" + AuditEvent.jsonString(moved.getIssueKey())
                        + ",\"from\":" + AuditEvent.jsonString(current.getName())
                        + ",\"to\":" + AuditEvent.jsonString(target.get().getName()) + "}",
                now));

        return new Outcome.Moved(issueViews.viewOf(moved, project));
    }
}
