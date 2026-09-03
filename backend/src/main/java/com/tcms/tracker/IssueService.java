package com.tcms.tracker;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectKeys;
import com.tcms.project.ProjectService;
import com.tcms.project.ProjectSettingsService;
import com.tcms.project.ReviewSettings;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tracker.release.Release;
import com.tcms.tracker.release.ReleaseRefView;
import com.tcms.tracker.release.ReleaseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Issues — the read, the create, the field edit and the delete.
 *
 * <p>The move is not here: it is {@link TransitionService}, because the workflow's legal-move set
 * has to be checked on exactly one path and a second writer of {@code status_id} is how a rule
 * acquires an unchecked spelling.
 *
 * <h2>The gate runs before the payload is looked at</h2>
 *
 * <p>{@code ProjectCreateService}'s ordering, and for a sharper version of its reason: the parent
 * lookup below reports whether a key exists, so running it for a caller who may not create issues
 * would turn a 403 endpoint into a probe for which keys a project has taken.
 *
 * <h2>Two things a new issue does not choose</h2>
 *
 * <ul>
 *   <li><strong>Its key.</strong> {@link ProjectKeys#allocate} joins this transaction rather than
 *       starting its own — required rather than incidental: the row lock must be held until the
 *       issue that uses the number commits, or a caller's rollback would leave the number spent.</li>
 *   <li><strong>Its status.</strong> The project's first status <em>by position</em>, never by the
 *       word "Backlog". A project that renamed its first column still has a first column, and a
 *       lookup on the word would break exactly the projects that used the configurability story B1
 *       promises them.</li>
 * </ul>
 */
@Service
public class IssueService {

    /** Roles spec §A3 "Tracker". Test Engineer holds it (D28) — a developer is a Test Engineer here. */
    private static final String CREATE_ISSUE = "issue:create";

    private static final String EDIT_ISSUE = "issue:edit";

    /** Test Engineer does <em>not</em> hold it; Test Lead and above do (§A3). */
    private static final String DELETE_ISSUE = "issue:delete";

    private static final String AUDIT_TARGET = "issue";

    /**
     * Its own action rather than a field inside {@code issue.updated}'s document, because
     * {@code task.html} renders it as an event in its own right — "attached PR #482". A reader
     * scanning a history for where the work went wants a line, not a diff to open.
     */
    private static final String AUDIT_PR_URL_SET = "issue.pr_url_set";

    private static final String AUDIT_AGENT_ELIGIBILITY_SET = "issue.agent_eligibility_set";

    /**
     * A cap this endpoint's contract does not state, applied for {@link #MAX_IDEMPOTENCY_KEY}'s
     * reason: {@code issue.pr_url} is {@code TEXT}, the value is caller-controlled, and a field
     * error naming the limit is a better answer than a row nobody meant to store.
     */
    private static final int MAX_PR_URL = 2000;

    /**
     * {@code CaseCreateService}'s cap, for its reason: the header is attacker-controlled and it is
     * indexed.
     *
     * <p>One of eleven identical declarations, pinned by {@code IdempotencyKeyCapTest} rather than
     * shared — that class carries the argument for why a header's length cap does not belong in a
     * foundation module. <strong>This one was missing until T4</strong>: {@code POST /issues}
     * declared the parameter from its first day and stored whatever arrived, and the count only
     * became wrong when {@code work.BoardService}'s declaration retired with {@code work_item} —
     * the check catching the defect by the population moving rather than by anybody reading the
     * service.
     */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final IssueRepository issues;
    private final IssueTypeRepository types;
    private final IssueStatusRepository statuses;
    private final IssueTransitionRepository transitions;
    private final TrackerPeople people;
    private final WatcherService watchers;
    private final ProjectKeys keys;
    private final AuditEventRepository auditEvents;
    private final Outbox outbox;
    private final ReleaseRepository releases;
    private final ProjectSettingsService settings;
    private final Clock clock;

    public IssueService(
            ProjectService projects,
            IssueRepository issues,
            IssueTypeRepository types,
            IssueStatusRepository statuses,
            IssueTransitionRepository transitions,
            TrackerPeople people,
            WatcherService watchers,
            ProjectKeys keys,
            AuditEventRepository auditEvents,
            Outbox outbox,
            ReleaseRepository releases,
            ProjectSettingsService settings,
            Clock clock) {

        this.projects = projects;
        this.issues = issues;
        this.types = types;
        this.statuses = statuses;
        this.transitions = transitions;
        this.people = people;
        this.watchers = watchers;
        this.keys = keys;
        this.auditEvents = auditEvents;
        this.outbox = outbox;
        this.releases = releases;
        this.settings = settings;
        this.clock = clock;
    }

    /** What a write did. Sealed, so a controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Created(IssueView view) implements Outcome {
        }

        /** The {@code Idempotency-Key} had already created this issue — 200, not a second one. */
        record Replayed(IssueView view) implements Outcome {
        }

        record Updated(IssueView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }

        /** The issue holds children, so deleting it is either a cascade nobody asked for or orphans. */
        record HasChildren(long count) implements Outcome {
        }
    }

    /** The filters {@code GET /issues} offers, bundled so the signature does not grow past reading. */
    public record Filter(
            IssueLevel level,
            String epicKey,
            String parentKey,
            List<UUID> statusIds,
            List<UUID> assigneeIds,
            boolean unparented,
            Boolean agentEligible,
            String sort) {
    }

    /**
     * One project's issues — the work table.
     *
     * <p>Gated on visibility alone rather than on {@code issue:read}: all six roles hold that key
     * (§A3), so a 403 arm would be unreachable, and the contract declares no 403 on this operation.
     * An empty list is a valid answer and means the project is reachable with nothing in it.
     */
    public Optional<List<IssueSummaryView>> list(UUID userId, UUID projectId, Filter filter) {
        return projects.readVisible(userId, projectId, project -> summaries(project, filter));
    }

    /**
     * Whether {@code DONE}-kind targets must be withheld from this caller on this issue — D33's
     * guard, asked once and answered the same way by the view, the two board card faces and
     * {@link TransitionService}.
     *
     * <p>Package-private so those callers share this reading rather than each spelling it: the
     * project half is {@link ReviewSettings#issueCloseNeedsSecondPerson()} and the request half is
     * "the caller is the assignee". An unassigned issue is not guarded — nobody is the author of
     * work nobody has taken, and a rule that bit there would make an unassigned task unclosable by
     * everyone.
     */
    public static boolean closeGuardBinds(ReviewSettings policy, Issue issue, UUID userId) {
        return policy.issueCloseNeedsSecondPerson()
                && issue.getAssigneeId() != null
                && issue.getAssigneeId().equals(userId);
    }

    /** One issue, with its children and its legal moves. Addressed by key, gated on its own project. */
    public Optional<IssueView> find(UUID userId, String key) {
        return issues.findByIssueKeyAndDeletedAtIsNull(key)
                .flatMap(issue -> projects.readVisible(
                        userId, issue.getProjectId(), project -> viewOf(userId, issue, project)));
    }

    public GateResult<Outcome> create(UUID userId, IssueInput request, String idempotencyKey) {
        if (request == null || request.projectId() == null) {
            // No project means no gate to run, and the caller is entitled to be told which field is
            // missing rather than getting the 404 an unresolvable project would earn.
            return new GateResult.Allowed<>(new Outcome.Invalid(Map.of(
                    "projectId", List.of("Choose a project."))));
        }
        return projects.writeGranted(userId, request.projectId(), CREATE_ISSUE,
                project -> validateAndCreate(userId, project, request, idempotencyKey));
    }

    /**
     * Edits an issue's fields. <strong>Not its status</strong> — that is {@link TransitionService}.
     *
     * <p>The {@link com.tcms.api.PatchBody} travels with the request record because Jackson gives an
     * absent field and an explicit {@code null} the same Java value. A service that cannot ask what
     * it was sent has to read both as <em>clear it</em>, which is the defect B1 records: a delta edit
     * emptied four columns and answered 200. The merge happens here, behind the gate, so
     * {@link IssueEdit} still sees one set of values and stays pure.
     */
    public GateResult<Outcome> update(
            UUID userId, String key, IssueUpdate request, com.tcms.api.PatchBody patch) {

        return onIssue(userId, key, EDIT_ISSUE,
                (project, issue) -> applyUpdate(userId, project, issue, request, patch));
    }

    /**
     * Soft-deletes an issue.
     *
     * <p>Refused with a 409 if anything hangs off it. Deleting an epic that holds seventeen stories
     * is either a cascade nobody asked for or seventeen orphans, and {@code issue.parent_id} is
     * {@code ON DELETE RESTRICT} precisely so the choice cannot be made by accident.
     */
    public GateResult<Outcome> delete(UUID userId, String key) {
        return onIssue(userId, key, DELETE_ISSUE, (project, issue) -> {
            long children = issues.countByParentIdAndDeletedAtIsNull(issue.getId());
            if (children > 0) {
                return new Outcome.HasChildren(children);
            }
            Instant now = clock.instant();
            issue.softDelete(userId, now);
            issues.save(issue);
            audit(project, userId, issue, "issue.deleted", now);
            return new Outcome.Deleted();
        });
    }

    // =====================================================================
    // The writes
    // =====================================================================

    /**
     * The gate for a write addressed by issue key.
     *
     * <p>The key has no project segment, so the issue is read first and <em>its</em> project is what
     * the permission is checked against — {@code work.BoardService.move}'s shape. RLS has already
     * bounded the read to the caller's company before that; the grant check bounds it to their
     * projects, and an issue in a project they cannot see is {@code Unreachable} exactly as a key
     * that names nothing is.
     */
    private GateResult<Outcome> onIssue(
            UUID userId, String key, String permission,
            java.util.function.BiFunction<Project, Issue, Outcome> write) {

        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, issue.get().getProjectId(), permission,
                project -> write.apply(project, issue.get()));
    }

    private Outcome validateAndCreate(
            UUID userId, Project project, IssueInput request, String idempotencyKey) {

        Instant now = clock.instant();

        /*
          A replay returns THIS issue rather than creating a second one, and — note — BEFORE a key is
          allocated. Allocating first would burn a number on every retry of one intent, so a project
          with a flaky client would show gaps in its keys that look like deleted work.
        */
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<Issue> replay = issues.findReplay(project.getId(), idempotencyKey);
            if (replay.isPresent()) {
                return new Outcome.Replayed(viewOf(userId, replay.get(), project));
            }
        }

        Map<UUID, IssueType> typesById = typesOf(project.getId());
        IssueType type = request.typeId() == null ? null : typesById.get(request.typeId());
        Issue parent = parentOf(request.parentKey(), project.getId());
        IssueTypeRow parentType = parent == null ? null : rowOf(typesById.get(parent.getTypeId()));

        IssueEdit.Outcome validated =
                IssueEdit.validateCreate(request, rowOf(type), parentType);
        if (validated instanceof IssueEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        IssueEdit edit = ((IssueEdit.Outcome.Valid) validated).edit();

        Map<String, List<String>> assigneeErrors = checkAssignee(edit.assigneeId(), project.getId());
        if (!assigneeErrors.isEmpty()) {
            return new Outcome.Invalid(assigneeErrors);
        }

        Map<String, List<String>> releaseErrors = checkRelease(edit.releaseId(), project.getId());
        if (!releaseErrors.isEmpty()) {
            return new Outcome.Invalid(releaseErrors);
        }

        IssueStatus opening = statuses.findByProjectIdOrderByPositionAsc(project.getId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Project " + project.getId() + " has no issue statuses. "
                                + "tcms_seed_project_defaults creates them and is idempotent; a "
                                + "project without them predates V3 and the backfill missed it."));

        ProjectKeys.Allocation allocation = keys.allocate(project.getId(), 1);
        Issue created = issues.save(Issue.create(
                allocation.keyAt(0),
                allocation.seqAt(0),
                project.getId(),
                type.getId(),
                opening.getId(),
                parent == null ? null : parent.getId(),
                edit,
                userId,
                idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey,
                now));

        created.setAgentEligible(Boolean.TRUE.equals(request.agentEligible()));
        audit(project, userId, created, "issue.created", now);
        announceAssignment(created, null);
        return new Outcome.Created(viewOf(userId, created, project));
    }

    private Outcome applyUpdate(
            UUID userId, Project project, Issue issue, IssueUpdate request,
            com.tcms.api.PatchBody patch) {

        Map<UUID, IssueType> typesById = typesOf(project.getId());
        IssueTypeRow type = rowOf(typesById.get(issue.getTypeId()));

        String parentKey = patch.has("parentKey")
                ? request.parentKey()
                : keyOfParent(issue);
        Issue parent = parentOf(parentKey, project.getId());
        IssueTypeRow parentType = parent == null ? null : rowOf(typesById.get(parent.getTypeId()));

        if (parent != null && parent.getId().equals(issue.getId())) {
            // ck_issue_not_own_parent would refuse this at the database, as a 500. The rule is the
            // depth rule's, so the answer is the depth rule's: a field error the form can land on.
            return new Outcome.Invalid(Map.of(
                    "parentKey", List.of("An issue cannot be its own parent.")));
        }

        IssueEdit merged = merge(issue, request, patch);
        IssueEdit.Outcome validated =
                IssueEdit.validateUpdate(merged, type, parentKey, parentType);
        if (validated instanceof IssueEdit.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        IssueEdit edit = ((IssueEdit.Outcome.Valid) validated).edit();

        Map<String, List<String>> assigneeErrors = checkAssignee(edit.assigneeId(), project.getId());
        if (!assigneeErrors.isEmpty()) {
            return new Outcome.Invalid(assigneeErrors);
        }

        Map<String, List<String>> releaseErrors = checkRelease(edit.releaseId(), project.getId());
        if (!releaseErrors.isEmpty()) {
            return new Outcome.Invalid(releaseErrors);
        }

        String prUrl = patch.has("prUrl") ? blankToNull(request.prUrl()) : issue.getPrUrl();
        if (prUrl != null && prUrl.length() > MAX_PR_URL) {
            return new Outcome.Invalid(Map.of("prUrl", List.of(
                    "Keep the pull request link to " + MAX_PR_URL + " characters or fewer.")));
        }
        boolean agentEligible = patch.has("agentEligible")
                ? Boolean.TRUE.equals(request.agentEligible())
                : issue.isAgentEligible();

        Instant now = clock.instant();
        UUID wasAssignedTo = issue.getAssigneeId();
        boolean prUrlChanged = !java.util.Objects.equals(prUrl, issue.getPrUrl());
        boolean eligibilityChanged = agentEligible != issue.isAgentEligible();

        issue.apply(edit, parent == null ? null : parent.getId(), userId, now);
        issue.setPrUrl(prUrl);
        issue.setAgentEligible(agentEligible);
        Issue saved = issues.save(issue);

        audit(project, userId, saved, "issue.updated", now);
        if (prUrlChanged) {
            // Beside issue.updated rather than instead of it: the edit happened and the trail
            // records edits, and a reader who filters to this action is asking a narrower question
            // than "what changed on this issue".
            auditEvents.save(AuditEvent.record(
                    project.getId(), userId, AUDIT_PR_URL_SET, AUDIT_TARGET,
                    List.of(saved.getId()),
                    // A cleared link is the JSON literal null, not jsonString(null) — that
                    // helper takes a String's length and throws on one, and the failure surfaces
                    // as a 500 out of the audit writer on a PATCH about something else.
                    "{\"key\":" + AuditEvent.jsonString(saved.getIssueKey())
                            + ",\"prUrl\":"
                            + (prUrl == null ? "null" : AuditEvent.jsonString(prUrl)) + "}",
                    now));
        }
        if (eligibilityChanged) {
            auditEvents.save(AuditEvent.record(
                    project.getId(), userId, AUDIT_AGENT_ELIGIBILITY_SET, AUDIT_TARGET,
                    List.of(saved.getId()),
                    "{\"key\":" + AuditEvent.jsonString(saved.getIssueKey())
                            + ",\"agentEligible\":" + agentEligible + "}",
                    now));
        }
        announceAssignment(saved, wasAssignedTo);
        return new Outcome.Updated(viewOf(userId, saved, project));
    }

    /**
     * An empty string clears the link rather than storing one nobody can follow — the shape
     * {@code IssueEdit} applies to every other free-text field on this form.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * The stored values with the sent fields overlaid — one line per field, and none of them clever.
     *
     * <p>{@code patch.has(field)} is the whole point: it is true for a field sent as {@code null},
     * which is how a client clears a due date, and false for one that was never mentioned, which is
     * how it keeps one.
     */
    private IssueEdit merge(Issue issue, IssueUpdate request, com.tcms.api.PatchBody patch) {
        List<AcceptanceCriterion> criteria = patch.has("acceptanceCriteria")
                ? renumber(request.acceptanceCriteria())
                : AcceptanceCriteria.parse(issue.getAcceptanceCriteria());

        return new IssueEdit(
                patch.has("title") ? request.title() : issue.getTitle(),
                patch.has("description") ? request.description() : issue.getDescription(),
                criteria,
                patch.has("priority") ? request.priority() : issue.getPriority(),
                patch.has("estimateHours") ? request.estimateHours() : issue.getEstimateHours(),
                patch.has("startDate") ? request.startDate() : issue.getStartDate(),
                patch.has("targetDate") ? request.targetDate() : issue.getTargetDate(),
                patch.has("dueDate") ? request.dueDate() : issue.getDueDate(),
                patch.has("assigneeId") ? request.assigneeId() : issue.getAssigneeId(),
                patch.has("releaseId") ? request.releaseId() : issue.getReleaseId());
    }

    /**
     * The checklist as the editor sends it back — whole, in order, with fresh ids.
     *
     * <p>Ids are minted rather than carried because the update shape has none: the list is written
     * whole by one form in one request, so a stable id would be a promise the wire cannot keep.
     * Nothing outside the issue references a criterion, which is why it is a JSONB column rather
     * than a table in the first place.
     */
    private static List<AcceptanceCriterion> renumber(List<IssueUpdate.CriterionUpdate> sent) {
        if (sent == null) {
            return List.of();
        }
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        for (IssueUpdate.CriterionUpdate row : sent) {
            if (row != null && row.text() != null && !row.text().isBlank()) {
                criteria.add(new AcceptanceCriterion(
                        UUID.randomUUID(), row.text().strip(), row.done(), criteria.size()));
            }
        }
        return List.copyOf(criteria);
    }

    private String keyOfParent(Issue issue) {
        if (issue.getParentId() == null) {
            return null;
        }
        return issues.findById(issue.getParentId()).map(Issue::getIssueKey).orElse(null);
    }

    /**
     * The assignee must be able to open the project the work is in.
     *
     * <p>{@code BoardEndpointTest}'s defect 4, in this module: the id is trivially available from
     * any other tab, the card renders their name perfectly, and the only person who ever sees the
     * problem is the assignee — who gets a 404 when they click it.
     */
    /**
     * A release from another project is a field error and never a silent null.
     *
     * <p>D13 keys a release on {@code (company_id, project_id, version)}, so an id from a sibling
     * project resolves to a real row this issue may not point at — and storing it would put a story
     * into a roadmap its project cannot see, which is the §A1 boundary crossed by an id nobody
     * checked. The message names the constraint rather than the id, because the id came from a
     * picker the client populated.
     */
    private Map<String, List<String>> checkRelease(UUID releaseId, UUID projectId) {
        if (releaseId == null) {
            return Map.of();
        }
        boolean ours = releases.findScopedById(releaseId)
                .filter(release -> release.getProjectId().equals(projectId))
                .isPresent();
        return ours ? Map.of() : Map.of("releaseId", List.of(
                "That release belongs to another project. A version shipped from two projects is "
                        + "two releases, and an issue can only target its own project's."));
    }

    private Map<String, List<String>> checkAssignee(UUID assigneeId, UUID projectId) {
        if (assigneeId == null || people.isAssignableTo(assigneeId, projectId)) {
            return Map.of();
        }
        return Map.of("assigneeId", List.of(
                "That person is not a member of this project, so they would not be able to open "
                        + "the issue you assigned them."));
    }

    private Issue parentOf(String parentKey, UUID projectId) {
        if (parentKey == null || parentKey.isBlank()) {
            return null;
        }
        return issues.findByIssueKeyAndDeletedAtIsNull(parentKey.strip())
                // A parent in another project is not a parent: the key is unique per COMPANY, so
                // one really can name an issue outside this project, and a cross-project hierarchy
                // is a completion figure that counts work the epic's own project cannot see.
                .filter(candidate -> candidate.getProjectId().equals(projectId))
                .orElse(null);
    }

    /**
     * The audit row and the projection trigger, together, inside the write's own transaction.
     *
     * <p>One call site for all four writes rather than four, because the two facts are the same
     * fact: this issue moved. {@code Outbox.recordCoalesced} refuses to run outside a transaction,
     * which is what keeps the event and the row it describes committing together or not at all —
     * and being coalesced, a bulk edit records one notice rather than one per issue.
     */
    private void audit(Project project, UUID userId, Issue issue, String action, Instant now) {
        outbox.recordCoalesced(OutboxEvents.ISSUES_CHANGED);
        auditEvents.save(AuditEvent.record(
                project.getId(),
                userId,
                action,
                AUDIT_TARGET,
                List.of(issue.getId()),
                // Escaped through the shared helper: a title holding a quote or a backslash
                // concatenated raw produces a malformed JSONB document, the insert fails, and the
                // whole write rolls back with an error naming the audit table rather than the title.
                "{\"key\":" + AuditEvent.jsonString(issue.getIssueKey())
                        + ",\"title\":" + AuditEvent.jsonString(issue.getTitle()) + "}",
                now));
    }

    /**
     * Records that this issue went to somebody, for whoever is subscribed to hear about it.
     *
     * <p><strong>Inside the write's transaction, which is the whole pattern.</strong> The issue and
     * the notice of it commit together or neither does; {@code Outbox#record} throws when it is
     * called outside one rather than trusting this comment.
     *
     * <p><strong>Nothing at all for unassigned work</strong>, which is most of a backlog. The event
     * is "assigned to somebody" and not "an issue changed", so work that goes onto the board for
     * whoever picks it up records no occurrence — an event whose payload named no assignee would be
     * one every consumer had to filter, and one of them would forget.
     *
     * <p><strong>And nothing for an edit that left the assignee where it was.</strong> Renaming an
     * issue is not handing it to anybody, and a notice on every save is how a subscriber learns to
     * ignore the channel.
     *
     * <p>Not coalesced. Fifty issues assigned in a planning session are fifty notices, because each
     * names a different person and a coalesced event could name only one of them truthfully.
     */
    private void announceAssignment(Issue issue, UUID previousAssignee) {
        UUID assignee = issue.getAssigneeId();
        if (assignee == null || assignee.equals(previousAssignee)) {
            return;
        }
        outbox.record(
                IssueAssignedEvent.AGGREGATE,
                issue.getId(),
                OutboxEvents.ITEM_ASSIGNED,
                new IssueAssignedEvent(
                        UUID.randomUUID(),
                        issue.getId(),
                        issue.getTitle(),
                        issue.getProjectId(),
                        assignee));
    }

    // =====================================================================
    // Views
    // =====================================================================

    /**
     * The full issue, as {@code GET /issues/{key}} serves it.
     *
     * <p>Package-private so {@link TransitionService} can answer a move with the issue's new state
     * without a second round trip, and without a second assembly of this shape that would drift from
     * this one the first time a field was added.
     */
    IssueView viewOf(UUID userId, Issue issue, Project project) {
        Map<UUID, IssueType> typesById = typesOf(project.getId());
        Map<UUID, IssueStatus> statusesById = statusesOf(project.getId());
        IssueType type = typesById.get(issue.getTypeId());
        IssueStatus status = statusesById.get(issue.getStatusId());

        List<Issue> children = issues.findByParentIdAndDeletedAtIsNullOrderByKeySeqAsc(issue.getId());
        boolean isEpic = type.getLevel() == IssueLevel.EPIC;

        /*
          D8 and D31, from ONE list, which is the whole reason CountedIssue is shared. An epic
          reading "In Progress" beside a completion of 100% is the disagreement D31 exists to make
          unrepresentable, and two lists is how it would come back.
        */
        List<CountedIssue> counted = children.stream()
                .map(child -> new CountedIssue(
                        typesById.get(child.getTypeId()).isCountsTowardCompletion(),
                        statusesById.get(child.getStatusId()).getKind(),
                        child.getEstimateHours()))
                .toList();
        IssueStatusView statusView = isEpic
                ? derivedStatus(EpicStatus.of(counted), statusesById, status)
                : IssueStatusView.of(status);
        CompletionView completion = isEpic ? Completion.of(counted).view() : null;
        Issue parent = issue.getParentId() == null
                ? null : issues.findById(issue.getParentId()).orElse(null);
        Issue epic = epicOf(issue, parent, typesById);

        Set<UUID> peopleIds = new HashSet<>();
        addIfPresent(peopleIds, issue.getAssigneeId());
        addIfPresent(peopleIds, issue.getReporterId());
        children.forEach(child -> addIfPresent(peopleIds, child.getAssigneeId()));
        Map<UUID, UserRef> refs = people.resolve(CompanyContext.require(), peopleIds);

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        return new IssueView(
                issue.getIssueKey(),
                issue.getKeySeq(),
                issue.getTitle(),
                issue.getDescription(),
                AcceptanceCriteria.parse(issue.getAcceptanceCriteria()),
                IssueTypeView.of(type),
                statusView,
                issue.getStatusEnteredAt(),
                project.getId(),
                project.getKey(),
                parent == null ? null : parent.getIssueKey(),
                parent == null ? null : parent.getTitle(),
                epic == null ? null : epic.getIssueKey(),
                epic == null ? null : epic.getTitle(),
                refs.get(issue.getAssigneeId()),
                refs.get(issue.getReporterId()),
                issue.getPriority(),
                issue.getEstimateHours(),
                issue.getStartDate(),
                issue.getTargetDate(),
                issue.getDueDate(),
                overdue(issue, status, today),
                completion,
                releaseRefOf(issue.getReleaseId()),
                issue.isAgentEligible(),
                issue.getPrUrl(),
                children.stream()
                        .map(child -> summaryOf(child, project, typesById, statusesById, refs,
                                releaseRefsOf(children), issue, epic, today))
                        .toList(),
                watchers.watchersOf(issue.getId()),
                allowedTransitions(userId, issue, type, statusesById),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                issue.getDoneAt());
    }

    /**
     * The project status that spells a derived kind — the first one at that kind, by position.
     *
     * <p>An epic's status is a {@link IssueStatusKind}, not a row: D31 derives it and nobody sets
     * it, so there is no {@code status_id} that means it. The screen still has to render a NAME, and
     * it must be one of this project's own — a hardcoded "Done" would be the word-lookup story B1
     * exists to remove. The stored status is the fallback for a project whose workflow has no status
     * at that kind at all, which the DONE-status rule makes impossible to reach for {@code DONE} and
     * which is otherwise an honest "we cannot spell this".
     */
    private static IssueStatusView derivedStatus(
            IssueStatusKind derived, Map<UUID, IssueStatus> statusesById, IssueStatus stored) {

        return statusesById.values().stream()
                .filter(status -> status.getKind() == derived)
                .min(Comparator.comparingInt(IssueStatus::getPosition))
                .map(IssueStatusView::of)
                .orElseGet(() -> IssueStatusView.of(stored));
    }

    /**
     * The statuses this issue may legally move to, from where it stands.
     *
     * <p><strong>Empty for an Epic</strong>, and that is D31 rather than an omission: an epic's
     * status is derived from its stories and is not editable, so there is no legal move out of it
     * for anybody. {@link TransitionService} refuses the move for the same reason, so the empty list
     * and the 409 are one decision expressed twice rather than two rules that could drift.
     */
    private List<IssueStatusView> allowedTransitions(
            UUID userId, Issue issue, IssueType type, Map<UUID, IssueStatus> statusesById) {

        if (type.getLevel() == IssueLevel.EPIC) {
            return List.of();
        }
        boolean withholdDone =
                closeGuardBinds(settings.effective(issue.getProjectId()), issue, userId);
        return transitions.findByFromStatusId(issue.getStatusId()).stream()
                .map(move -> statusesById.get(move.getToStatusId()))
                .filter(java.util.Objects::nonNull)
                // The workflow set is the base and DONE is SUBTRACTED from it — never a
                // replacement set built from the guard. A caller under the guard must still be
                // offered In Review and Blocked, which is what makes the refusal actionable.
                .filter(status -> !withholdDone || status.getKind() != IssueStatusKind.DONE)
                .sorted(Comparator.comparingInt(IssueStatus::getPosition))
                .map(IssueStatusView::of)
                .toList();
    }

    private List<IssueSummaryView> summaries(Project project, Filter filter) {
        Map<UUID, IssueType> typesById = typesOf(project.getId());
        Map<UUID, IssueStatus> statusesById = statusesOf(project.getId());

        List<UUID> levelTypeIds = filter.level() == null ? List.of()
                : typesById.values().stream()
                        .filter(type -> type.getLevel() == filter.level())
                        .map(IssueType::getId)
                        .toList();
        if (filter.level() != null && levelTypeIds.isEmpty()) {
            // The project has no type at that level, so nothing can match. Answered here rather
            // than bound as an empty IN list, which is a syntax error.
            return List.of();
        }

        Issue narrowedParent = parentOf(
                filter.parentKey() != null ? filter.parentKey() : filter.epicKey(), project.getId());
        boolean anyParent = filter.parentKey() == null && filter.epicKey() == null;
        if (!anyParent && narrowedParent == null) {
            // The key names nothing in this project. An empty table is the honest answer; a 404
            // would say the PROJECT was unreachable, which it is not.
            return List.of();
        }

        List<Issue> matched = issues.findFiltered(
                project.getId(),
                filter.agentEligible() == null,
                Boolean.TRUE.equals(filter.agentEligible()),
                filter.level() == null,
                levelTypeIds.isEmpty() ? List.of(project.getId()) : levelTypeIds,
                anyParent,
                narrowedParent == null ? null : narrowedParent.getId(),
                filter.unparented(),
                isEmpty(filter.statusIds()),
                isEmpty(filter.statusIds()) ? List.of(project.getId()) : filter.statusIds(),
                isEmpty(filter.assigneeIds()),
                isEmpty(filter.assigneeIds()) ? List.of(project.getId()) : filter.assigneeIds());

        Map<UUID, Issue> parentsById = parentsOf(matched);
        Set<UUID> peopleIds = new HashSet<>();
        matched.forEach(issue -> addIfPresent(peopleIds, issue.getAssigneeId()));
        Map<UUID, UserRef> refs = people.resolve(CompanyContext.require(), peopleIds);
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);

        List<Issue> ordered = new ArrayList<>(matched);
        ordered.sort(sortOf(filter.sort(), statusesById));

        Map<UUID, ReleaseRefView> releaseRefs = releaseRefsOf(ordered);

        List<IssueSummaryView> views = new ArrayList<>();
        for (Issue issue : ordered) {
            Issue parent = issue.getParentId() == null ? null : parentsById.get(issue.getParentId());
            views.add(summaryOf(issue, project, typesById, statusesById, refs, releaseRefs, parent,
                    epicOf(issue, parent, typesById), today));
        }
        return views;
    }

    /**
     * One release, for the detail view — which has exactly one issue and therefore one lookup.
     *
     * <p>Not folded into {@link #releaseRefsOf}: that batches over a list, and calling it with a
     * singleton to reuse it would read as though the detail path had a list.
     */
    private ReleaseRefView releaseRefOf(UUID releaseId) {
        return releaseId == null ? null
                : releases.findScopedById(releaseId).map(ReleaseRefView::of).orElse(null);
    }

    /**
     * Every distinct release a list of issues targets, in one query.
     *
     * <p>The alternative is a lookup per row, which on the work table is the N+1 a reader notices
     * first. A release the caller cannot see resolves to nothing and the row renders unscheduled,
     * which cannot happen through the API — the write path refuses a cross-project release — and is
     * the honest answer if a row is ever reached another way.
     */
    private Map<UUID, ReleaseRefView> releaseRefsOf(List<Issue> rows) {
        Map<UUID, ReleaseRefView> refs = new HashMap<>();
        Set<UUID> ids = new HashSet<>();
        rows.forEach(issue -> addIfPresent(ids, issue.getReleaseId()));
        if (ids.isEmpty()) {
            // A HashMap and NOT Map.of(). Every caller looks up a nullable release id, and
            // Map.of().get(null) throws by contract — the trap PeopleDirectory's own javadoc
            // records as a 500 in §38, and the one this rule caught again here: four
            // IssueEndpointTest tests and one IssueFactProjectionTest failed with a bare NPE from
            // inside ImmutableCollections, naming nothing about releases at all.
            return refs;
        }
        for (Release release : releases.findAllById(ids)) {
            refs.put(release.getId(), ReleaseRefView.of(release));
        }
        return refs;
    }

    private IssueSummaryView summaryOf(
            Issue issue,
            Project project,
            Map<UUID, IssueType> typesById,
            Map<UUID, IssueStatus> statusesById,
            Map<UUID, UserRef> refs,
            Map<UUID, ReleaseRefView> releaseRefs,
            Issue parent,
            Issue epic,
            LocalDate today) {

        IssueStatus status = statusesById.get(issue.getStatusId());
        return new IssueSummaryView(
                issue.getIssueKey(),
                issue.getTitle(),
                IssueTypeView.of(typesById.get(issue.getTypeId())),
                IssueStatusView.of(status),
                project.getId(),
                project.getKey(),
                parent == null ? null : parent.getIssueKey(),
                epic == null ? null : epic.getIssueKey(),
                epic == null ? null : epic.getTitle(),
                refs.get(issue.getAssigneeId()),
                issue.getDueDate(),
                overdue(issue, status, today),
                issue.getEstimateHours(),
                releaseRefs.get(issue.getReleaseId()),
                issue.isAgentEligible());
    }

    /**
     * The epic a row belongs to, whatever level the row is.
     *
     * <p>A story's epic is its parent; a task's is its parent's parent. Walked here rather than
     * stored on the row, and bounded at two steps because the depth rule bounds the tree at three
     * levels — a loop would be a loop over a tree that cannot be deeper than this.
     */
    private Issue epicOf(Issue issue, Issue parent, Map<UUID, IssueType> typesById) {
        if (parent == null) {
            return null;
        }
        if (isEpic(parent, typesById)) {
            return parent;
        }
        if (parent.getParentId() == null) {
            return null;
        }
        return issues.findById(parent.getParentId())
                .filter(grandparent -> isEpic(grandparent, typesById))
                .orElse(null);
    }

    private boolean isEpic(Issue issue, Map<UUID, IssueType> typesById) {
        IssueType type = typesById.get(issue.getTypeId());
        return type != null && type.getLevel() == IssueLevel.EPIC;
    }

    /**
     * Overdue is computed here and never by the client.
     *
     * <p>A client deriving it from {@code dueDate} and its own clock disagrees with the server the
     * moment the two are in different time zones — on the one field people sort a work table by. A
     * done issue is never overdue: work that shipped late is late in a report, not a warning on a
     * screen somebody can no longer act on.
     */
    private boolean overdue(Issue issue, IssueStatus status, LocalDate today) {
        return issue.getDueDate() != null
                && status.getKind() != IssueStatusKind.DONE
                && issue.getDueDate().isBefore(today);
    }

    /**
     * The work table's sort.
     *
     * <p>Applied in Java rather than in SQL, because two of the five keys are a <em>name</em> on a
     * row this query does not join. The default is newest key first, which is {@code key_seq}
     * descending — never the key string, which puts {@code ACME-100} before {@code ACME-2}.
     */
    private static Comparator<Issue> sortOf(String sort, Map<UUID, IssueStatus> statusesById) {
        return switch (sort == null ? "" : sort) {
            case "title" -> Comparator.comparing(Issue::getTitle, String.CASE_INSENSITIVE_ORDER);
            case "status" -> Comparator.comparingInt(
                    issue -> statusesById.get(issue.getStatusId()).getPosition());
            case "dueDate" -> Comparator.comparing(
                    Issue::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()));
            case "estimateHours" -> Comparator.comparing(
                    Issue::getEstimateHours, Comparator.nullsLast(Comparator.naturalOrder()));
            case "key" -> Comparator.comparingLong(Issue::getKeySeq);
            default -> Comparator.comparingLong(Issue::getKeySeq).reversed();
        };
    }

    private Map<UUID, Issue> parentsOf(List<Issue> matched) {
        Set<UUID> parentIds = new HashSet<>();
        matched.forEach(issue -> addIfPresent(parentIds, issue.getParentId()));
        Map<UUID, Issue> parents = new LinkedHashMap<>();
        if (!parentIds.isEmpty()) {
            issues.findAllById(parentIds).forEach(parent -> parents.put(parent.getId(), parent));
        }
        return parents;
    }

    private Map<UUID, IssueType> typesOf(UUID projectId) {
        return byId(types.findByProjectIdOrderByPositionAsc(projectId), IssueType::getId);
    }

    private Map<UUID, IssueStatus> statusesOf(UUID projectId) {
        return byId(statuses.findByProjectIdOrderByPositionAsc(projectId), IssueStatus::getId);
    }

    private static <T> Map<UUID, T> byId(List<T> rows, Function<T, UUID> id) {
        Map<UUID, T> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(id.apply(row), row));
        return byId;
    }

    private static IssueTypeRow rowOf(IssueType type) {
        return type == null ? null : type.row();
    }

    private static void addIfPresent(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static boolean isEmpty(List<UUID> ids) {
        return ids == null || ids.isEmpty();
    }
}
