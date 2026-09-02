package com.tcms.tracker;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An issue's conversation — {@code /issues/{key}/comments}.
 *
 * <h2>Commenting makes you a watcher, and that is the point of the endpoint rather than a courtesy</h2>
 *
 * <p>Story H8: watchers are notified of status and assignee changes. Without the follow, people
 * comment, the thread continues, and they never hear about it — which is how a comment thread on an
 * issue tracker dies. The star is then only for the case somebody wants to follow work they have not
 * spoken about.
 *
 * <p>The follow is written in the <strong>same transaction</strong> as the comment, so there is no
 * state where somebody has said something and is not following it. It is also written
 * <em>conditionally</em>: a second comment must not fail on {@code issue_watcher}'s primary key, and
 * must not restamp the moment the author started following.
 *
 * <h2>{@code issue:comment} is a key of its own, and Viewer holds it</h2>
 *
 * <p>Roles spec §A3 gives Viewer {@code issue:read} and {@code issue:comment} and nothing else. A
 * reader who can see the work may say something about it and still not be able to change it, which
 * is the whole reason the key is separate from {@code issue:edit} — and the negative half is the one
 * worth asserting, because gating this on {@code issue:edit} would compile, pass every happy-path
 * test, and silently remove a role's only way to contribute.
 */
@Service
public class CommentService {

    /** §A3 "Tracker". Viewer holds this one and holds no other write key. */
    private static final String COMMENT_ISSUE = "issue:comment";

    private final ProjectService projects;
    private final IssueRepository issues;
    private final IssueCommentRepository comments;
    private final IssueWatcherRepository watchers;
    private final TrackerPeople people;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public CommentService(
            ProjectService projects,
            IssueRepository issues,
            IssueCommentRepository comments,
            IssueWatcherRepository watchers,
            TrackerPeople people,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.issues = issues;
        this.comments = comments;
        this.watchers = watchers;
        this.people = people;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Created(CommentView view) implements Outcome {
        }
    }

    /**
     * One issue's comments, oldest first.
     *
     * <p>Gated on visibility alone rather than on {@code issue:read}: all six roles hold that key
     * (§A3), so a 403 arm would be unreachable and the contract declares none.
     */
    @Transactional(readOnly = true)
    public Optional<List<CommentView>> list(UUID userId, String key) {
        return issues.findByIssueKeyAndDeletedAtIsNull(key)
                .flatMap(issue -> projects.readVisible(
                        userId, issue.getProjectId(), project -> viewsOf(issue.getId())));
    }

    /** Appends a comment and makes its author a watcher. */
    public GateResult<Outcome> add(UUID userId, String key, IssueCommentInput request) {
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, issue.get().getProjectId(), COMMENT_ISSUE,
                project -> write(userId, project, issue.get(), request));
    }

    private Outcome write(UUID userId, Project project, Issue issue, IssueCommentInput request) {
        String body = request == null || request.body() == null ? null : request.body().strip();
        if (body == null || body.isEmpty()) {
            return new Outcome.Invalid(Map.of("body", List.of("Write something first.")));
        }

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        IssueComment written =
                comments.save(IssueComment.write(companyId, issue.getId(), body, userId, now));
        follow(companyId, issue.getId(), userId, now);

        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                "issue.commented",
                "issue",
                List.of(issue.getId()),
                // Escaped through the shared helper for IssueService's reason: a key holding a quote
                // or a backslash concatenated raw produces malformed JSONB, the insert fails, and the
                // whole write rolls back naming the audit table rather than the comment.
                "{\"key\":" + AuditEvent.jsonString(issue.getIssueKey())
                        + ",\"commentId\":" + AuditEvent.jsonString(written.getId().toString())
                        + "}",
                now));

        return new Outcome.Created(viewOf(written));
    }

    /**
     * Adds the author as a watcher unless they already are one.
     *
     * <p>Read-then-write rather than an upsert because the timestamp is the thing being protected:
     * a plain {@code save} of a fresh row would overwrite {@code created_at} on every comment, and
     * the watcher list is ordered by it.
     */
    private void follow(UUID companyId, UUID issueId, UUID userId, Instant now) {
        if (watchers.findById(new IssueWatcherId(issueId, userId)).isPresent()) {
            return;
        }
        watchers.save(IssueWatcher.follow(companyId, issueId, userId, now));
    }

    private List<CommentView> viewsOf(UUID issueId) {
        List<IssueComment> written = comments.findByIssueIdOrderByCreatedAtAscIdAsc(issueId);
        Set<UUID> authors = new HashSet<>();
        for (IssueComment comment : written) {
            if (comment.getCreatedBy() != null) {
                authors.add(comment.getCreatedBy());
            }
        }
        Map<UUID, UserRef> refs = people.resolve(CompanyContext.require(), authors);
        return written.stream().map(comment -> view(comment, refs)).toList();
    }

    private CommentView viewOf(IssueComment comment) {
        Set<UUID> author = comment.getCreatedBy() == null
                ? Set.of() : Set.of(comment.getCreatedBy());
        return view(comment, people.resolve(CompanyContext.require(), author));
    }

    /**
     * <p>A null author is a real state rather than a defect: {@code created_by} has no foreign key
     * and no cascade, so an account offboarded since is a comment nobody is named for — and naming
     * somebody would attribute what they did not say. {@code externalAuthor} is the <em>other</em>
     * way that happens — an imported comment whose source author matched nobody here (plan 014
     * D-j) — and the two are never both set, so a reader has one string or the other and never a
     * choice between them.
     */
    private static CommentView view(IssueComment comment, Map<UUID, UserRef> refs) {
        return new CommentView(
                comment.getId(),
                comment.getBody(),
                comment.getCreatedBy() == null ? null : refs.get(comment.getCreatedBy()),
                comment.getExternalAuthor(),
                comment.getCreatedAt(),
                comment.getEditedAt());
    }
}
