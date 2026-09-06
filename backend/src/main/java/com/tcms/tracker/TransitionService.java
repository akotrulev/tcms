package com.tcms.tracker;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ProjectSettingsService;
import com.tcms.project.ReviewSettings;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
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
 * <h2>Three refusals, all 409, and they are different facts</h2>
 *
 * <ul>
 *   <li><strong>The workflow forbids this pair.</strong> {@code issue_transition} is an allow-list,
 *       so a pair with no row is refused — story H5: "a move the project forbids is never offered",
 *       and on the board "the card does not move and the board tells me why" rather than snapping
 *       back in silence.</li>
 *   <li><strong>A {@code blocking} target from a {@code DONE} status.</strong> Blocked is where
 *       work waits, and finished work is not waiting for anything — 021 §2.3 seeds the pair from
 *       every <em>non-done</em> status for exactly that reason. Refused here as well as omitted
 *       from the seed, because {@code blocking} is a flag a workflow editor can move onto any
 *       status and the seed is only the shape a new project starts in: a project that listed the
 *       pair by hand would otherwise get a card that is Done, blocked, and remembers Done as the
 *       place to return to.</li>
 *   <li><strong>The caller is closing their own work and the project requires review to close
 *       it</strong> (D33). Refused only when the project has switched
 *       {@code review_enabled_issue} on, {@code author_may_approve_own_work} is off, and the caller
 *       is the issue's <em>assignee</em> — the party who did the work. A token acts as its owner,
 *       so an assistant working its owner's assigned task is refused exactly as its owner would be,
 *       and there is deliberately <strong>no token-specific branch</strong>: with the gate off the
 *       same move succeeds, which is A6's one-person company. The refusal is recorded on the
 *       issue's history, because "the move did not happen and here is why" is the thing a lead
 *       reading the task later needs.</li>
 * </ul>
 *
 * <p>That guard is <strong>not the review module</strong>. No {@code review} row is opened for an
 * issue, no reviewer is assigned and no verdict is recorded; it is the self-approval rule §6g
 * already states, applied to the terminal transition. Scoped that way deliberately — see plan 015's
 * decision 2 — rather than by omission.
 *
 * <p>{@code status_entered_at} moves on every accepted transition; {@code done_at} is set the FIRST
 * time a {@code DONE}-kind status is reached and is <strong>never cleared by a reopen</strong> — an
 * issue that has been done once has been done once, and a lead time computed from a cleared column
 * silently lengthens every time somebody reopens something.
 *
 * <h2>The one legal move with no row behind it (021 D-c)</h2>
 *
 * <p>Entering the project's {@code blocking} status records the status being left in
 * {@code issue.blocked_from_status_id}, and <strong>that origin is a legal target on the way
 * out</strong> even though {@code issue_transition} has no pair for it. Blocked is reached from
 * every non-done status, so the alternative was ten reverse pairs — and ten reverse pairs make
 * "back to where it was" indistinguishable from "to anywhere", which is the whole distinction the
 * column buys. The widening applies only <em>from</em> a blocking status, so no other pair in the
 * project becomes legal because a column exists.
 */
@Service
public class TransitionService {

    /**
     * <strong>There is no {@code board:move} permission.</strong> Dragging a card changes the
     * issue's status and costs exactly what editing it costs (§A3, and the contract says so).
     */
    private static final String EDIT_ISSUE = "issue:edit";

    private final ProjectService projects;
    private final ProjectSettingsService settings;
    private final IssueService issueViews;
    private final IssueRepository issues;
    private final IssueTypeRepository types;
    private final IssueStatusRepository statuses;
    private final IssueTransitionRepository transitions;
    private final AuditEventRepository auditEvents;
    private final Outbox outbox;
    private final Clock clock;

    public TransitionService(
            ProjectService projects,
            ProjectSettingsService settings,
            IssueService issueViews,
            IssueRepository issues,
            IssueTypeRepository types,
            IssueStatusRepository statuses,
            IssueTransitionRepository transitions,
            AuditEventRepository auditEvents,
            Outbox outbox,
            Clock clock) {

        this.projects = projects;
        this.settings = settings;
        this.issueViews = issueViews;
        this.issues = issues;
        this.types = types;
        this.statuses = statuses;
        this.transitions = transitions;
        this.auditEvents = auditEvents;
        this.outbox = outbox;
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

        /**
         * 021 §2.3 — the target is the project's {@code blocking} status and the issue is done.
         *
         * <p>Its own outcome rather than a {@link NotAllowed}, because the two need different
         * sentences: {@code NotAllowed} ends "someone who can edit the workflow can add that
         * step", and here that advice is false — a project that <em>did</em> add the pair would
         * still be refused, and should be, since a card that is Done, blocked, and remembering
         * Done as the way back is a state no screen can read.
         *
         * @param from the done status the issue is sitting in, named so the refusal says which
         */
        record BlockingFromDone(String from) implements Outcome {
        }

        /**
         * D33 — the caller is the assignee and this project requires a second person to close.
         *
         * @param detail the sentence the client shows, naming the move that IS open instead. A
         *               refusal that does not say what to do next is one somebody retries.
         */
        record CloseRefused(String detail) implements Outcome {
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
        // The type is read for the exception, and no longer for a refusal: 021 R10 gives an epic a
        // stored status and a lifecycle of its own, so an epic moves along the workflow exactly as
        // a story does. What stays derived is the COMPLETION, which is Completion's and was never
        // this method's.
        types.findById(issue.getTypeId()).orElseThrow(() ->
                new IllegalStateException("issue " + issue.getIssueKey()
                        + " points at a type that is not in its project"));

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
            return new Outcome.Moved(issueViews.viewOf(userId, issue, project));
        }

        // AFTER the self-move short circuit and before the allow-list check. A caller re-sending
        // the status an issue is already in is a retry of a move that already happened, and
        // answering that with a refusal would tell somebody their finished work is not finished.
        if (target.get().getKind() == IssueStatusKind.DONE) {
            ReviewSettings policy = settings.effective(project.getId());
            if (IssueService.closeGuardBinds(policy, issue, userId)) {
                // Recorded inside the read: nothing else is written, and a refusal with no trace is
                // one the lead who set the policy cannot see working.
                auditEvents.save(AuditEvent.record(
                        project.getId(), userId, "issue.close_refused", "issue",
                        List.of(issue.getId()),
                        "{\"key\":" + AuditEvent.jsonString(issue.getIssueKey())
                                + ",\"to\":" + AuditEvent.jsonString(target.get().getName())
                                + ",\"reason\":\"review to close is on and you are the assignee\"}",
                        clock.instant()));
                return new Outcome.CloseRefused(
                        "Closing an issue you are the assignee of takes a second person while "
                                + "\"Require review to close issues\" is on for this project. Move "
                                + "it to In Review instead, and ask somebody to close it.");
            }
        }

        // Blocked is where work WAITS, and finished work is not waiting for anything. Checked
        // here rather than left to the seed's omission, because `blocking` is a flag the workflow
        // editor moves and the seed is only the shape a NEW project starts in — a project that
        // listed Done → Blocked by hand would otherwise get a card that is done, blocked, and
        // remembering Done as the place Unblock returns it to.
        if (target.get().isBlocking() && current.getKind() == IssueStatusKind.DONE) {
            return new Outcome.BlockingFromDone(current.getName());
        }

        // THE REMEMBERED ORIGIN IS LEGAL WITHOUT A ROW (021 D-c). Blocked is entered from every
        // non-done status, and listing ten reverse pairs would make "back to where it was"
        // indistinguishable from "to anywhere" — so the way out is the one status the issue was in
        // when it was blocked, and nothing else the workflow does not already allow. Checked only
        // FROM a blocking status, so the column cannot widen the legal set anywhere else.
        boolean unblocking = current.isBlocking()
                && targetStatusId.equals(issue.getBlockedFromStatusId());
        boolean allowed = unblocking || transitions.findByFromStatusId(issue.getStatusId()).stream()
                .anyMatch(move -> move.getToStatusId().equals(targetStatusId));
        if (!allowed) {
            return new Outcome.NotAllowed(current.getName(), target.get().getName());
        }

        Instant now = clock.instant();
        // Inside the transaction that moves the issue, so issue_fact cannot be told about a move
        // that then rolls back. Coalesced: a board drag-and-drop session is one recompute.
        outbox.recordCoalesced(OutboxEvents.ISSUES_CHANGED);
        issue.moveTo(targetStatusId, target.get().getKind() == IssueStatusKind.DONE,
                target.get().isBlocking(), userId, now);
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

        return new Outcome.Moved(issueViews.viewOf(userId, moved, project));
    }
}
