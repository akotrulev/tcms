package com.tcms.tracker;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * A project's workflow — its statuses, its types and the legal moves between the statuses (story B1).
 *
 * <h2>The PUT replaces the whole document, and that is the decision</h2>
 *
 * <p>Reordering is a statement about the <em>set</em>. A sequence of single-status writes has an
 * intermediate state in which two statuses claim one position — which is precisely what the settings
 * screen's ↑ / ↓ buttons generate, so the shape that reads as "more RESTful" is the shape that
 * cannot express what the screen does.
 *
 * <h2>Two things the PUT will not do</h2>
 *
 * <ul>
 *   <li><strong>Remove a status that issues hold.</strong> Refused with a 409 naming the count. The
 *       removal that says where they go is {@code DELETE .../workflow/statuses/{statusId}}, which
 *       moves them in the same transaction — so there is no window in which an issue points at a
 *       status that is gone (B1's third criterion).</li>
 *   <li><strong>Leave the project without a {@code DONE}-kind status.</strong> Every percentage in
 *       the product is computed from that kind (D8); a project with none has no expressible
 *       completion, and the screens would render a percentage of nothing rather than an error.</li>
 * </ul>
 *
 * <p>The GET is gated on visibility alone: every screen that renders a status picker needs it,
 * including the read-only ones, so it is not gated on {@code issue:workflow}.
 */
@Service
public class WorkflowService {

    /** Test Engineer does <em>not</em> hold it; Project Manager, Test Lead and above do (§A3). */
    private static final String EDIT_WORKFLOW = "issue:workflow";

    private final ProjectService projects;
    private final IssueStatusRepository statuses;
    private final IssueTypeRepository types;
    private final IssueTransitionRepository transitions;
    private final IssueRepository issues;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public WorkflowService(
            ProjectService projects,
            IssueStatusRepository statuses,
            IssueTypeRepository types,
            IssueTransitionRepository transitions,
            IssueRepository issues,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.statuses = statuses;
        this.types = types;
        this.transitions = transitions;
        this.issues = issues;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A status being removed still holds live issues, and this PUT did not say where they go. */
        record StatusInUse(String name, long count) implements Outcome {
        }

        /** Removing this would leave the project with no {@code DONE}-kind status at all. */
        record LastDoneStatus() implements Outcome {
        }

        record Replaced(WorkflowView view) implements Outcome {
        }

        record Removed() implements Outcome {
        }
    }

    public Optional<WorkflowView> workflow(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, this::viewOf);
    }

    public GateResult<Outcome> replace(UUID userId, UUID projectId, WorkflowView submitted) {
        return projects.writeGranted(userId, projectId, EDIT_WORKFLOW,
                project -> applyReplace(userId, project, submitted));
    }

    /**
     * Removes one status, moving the issues that hold it in the same transaction.
     *
     * <p>The caller names the destination, which is what makes this a different operation from the
     * PUT rather than a special case of it: B1's third criterion is "I am asked which status those
     * issues move to", and a question nobody was asked is a silent reassignment.
     */
    public GateResult<Outcome> removeStatus(
            UUID userId, UUID projectId, UUID statusId, StatusRemovalInput request) {

        return projects.writeGranted(userId, projectId, EDIT_WORKFLOW,
                project -> applyRemoval(userId, project, statusId, request));
    }

    // =====================================================================

    private Outcome applyReplace(UUID userId, Project project, WorkflowView submitted) {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        if (submitted == null || submitted.statuses() == null || submitted.statuses().isEmpty()) {
            errors.computeIfAbsent("statuses", key -> new ArrayList<>())
                    .add("A project needs at least one status.");
            return new Outcome.Invalid(errors);
        }
        if (submitted.statuses().stream().noneMatch(status -> status.kind() == IssueStatusKind.DONE)) {
            return new Outcome.LastDoneStatus();
        }
        Set<String> names = new LinkedHashSet<>();
        for (IssueStatusView status : submitted.statuses()) {
            if (status.name() == null || status.name().isBlank()) {
                errors.computeIfAbsent("statuses", key -> new ArrayList<>())
                        .add("Every status needs a name.");
            } else if (!names.add(status.name().strip().toLowerCase(java.util.Locale.ROOT))) {
                errors.computeIfAbsent("statuses", key -> new ArrayList<>())
                        .add("Two statuses are both called " + status.name().strip() + ".");
            }
        }
        if (!errors.isEmpty()) {
            return new Outcome.Invalid(errors);
        }

        Map<UUID, IssueStatus> stored = new LinkedHashMap<>();
        statuses.findByProjectIdOrderByPositionAsc(project.getId())
                .forEach(status -> stored.put(status.getId(), status));

        Set<UUID> kept = new HashSet<>();
        submitted.statuses().forEach(status -> {
            if (status.id() != null) {
                kept.add(status.id());
            }
        });
        for (IssueStatus removed : stored.values()) {
            if (kept.contains(removed.getId())) {
                continue;
            }
            long held = issues.countByStatusIdAndDeletedAtIsNull(removed.getId());
            if (held > 0) {
                // Refused rather than silently reassigned: the PUT carries no destination, and
                // choosing one for the caller is B1's third criterion answered by guessing.
                return new Outcome.StatusInUse(removed.getName(), held);
            }
        }

        UUID companyId = CompanyContext.require();
        List<IssueStatus> live = new ArrayList<>();
        int position = 0;
        for (IssueStatusView status : submitted.statuses()) {
            IssueStatus existing = status.id() == null ? null : stored.get(status.id());
            if (existing == null) {
                live.add(statuses.save(IssueStatus.create(
                        UUID.randomUUID(), companyId, project.getId(),
                        status.name().strip(), status.kind(), position)));
            } else {
                existing.redefine(status.name().strip(), status.kind(), position);
                live.add(statuses.save(existing));
            }
            position++;
        }

        // Transitions before statuses: issue_transition's foreign keys point AT issue_status, so a
        // status cannot be deleted while a move still names it. Deleting them all and re-inserting
        // the submitted set is the same whole-document argument the endpoint itself makes — a pair
        // is not an entity anybody edits, it is a cell in a matrix the screen submits entire.
        transitions.deleteAll(transitions.findByProjectId(project.getId()));
        transitions.flush();
        for (IssueStatus removed : stored.values()) {
            if (!kept.contains(removed.getId())) {
                statuses.delete(removed);
            }
        }

        Set<UUID> liveIds = new HashSet<>();
        live.forEach(status -> liveIds.add(status.getId()));
        if (submitted.transitions() != null) {
            Set<String> written = new HashSet<>();
            for (WorkflowView.TransitionView move : submitted.transitions()) {
                if (move.fromStatusId() == null || move.toStatusId() == null
                        || move.fromStatusId().equals(move.toStatusId())
                        || !liveIds.contains(move.fromStatusId())
                        || !liveIds.contains(move.toStatusId())
                        || !written.add(move.fromStatusId() + ">" + move.toStatusId())) {
                    // A self-pair, a duplicate, or a pair naming a status this document does not
                    // contain. Dropped rather than refused: the screen submits a matrix, and a cell
                    // for a row it just deleted is the shape a correct client produces.
                    continue;
                }
                transitions.save(IssueTransition.allow(
                        companyId, project.getId(), move.fromStatusId(), move.toStatusId()));
            }
        }

        applyTypes(project, companyId, submitted.types());

        Instant now = clock.instant();
        auditEvents.save(AuditEvent.record(
                project.getId(), userId, "issue.workflow_replaced", "project",
                List.of(project.getId()),
                "{\"statuses\":" + live.size() + "}",
                now));

        return new Outcome.Replaced(viewOf(project));
    }

    /**
     * The types, replaced the same way as the statuses — but a type is never deleted here.
     *
     * <p>{@code issue.type_id} has no {@code ON DELETE} clause and every issue points at one, so
     * removing a type would either fail at the database or orphan the rows that used it. Renaming
     * and reordering are what the settings screen offers, which is exactly what this does; removing
     * a type is a decision with a destination, like removing a status, and it does not exist yet.
     */
    private void applyTypes(Project project, UUID companyId, List<IssueTypeView> submitted) {
        if (submitted == null) {
            return;
        }
        Map<UUID, IssueType> stored = new LinkedHashMap<>();
        types.findByProjectIdOrderByPositionAsc(project.getId())
                .forEach(type -> stored.put(type.getId(), type));

        int position = 0;
        for (IssueTypeView type : submitted) {
            if (type.name() == null || type.name().isBlank()) {
                continue;
            }
            IssueType existing = type.id() == null ? null : stored.get(type.id());
            if (existing == null) {
                // "?" is Spike's icon and the honest default for a type nobody chose one for: the
                // contract has no iconCode, so a new type cannot name one, and inventing a letter
                // from the name would give two types the same chip the first time somebody added
                // "Tech Debt" beside "Task".
                types.save(IssueType.create(
                        UUID.randomUUID(), companyId, project.getId(), type.name().strip(),
                        type.level(), type.countsTowardCompletion(), "?", position));
            } else {
                // A renamed type keeps its icon, which is why the column is stored rather than
                // derived from the name.
                existing.rename(type.name().strip(), existing.getIconCode(), position);
                types.save(existing);
            }
            position++;
        }
    }

    private Outcome applyRemoval(
            UUID userId, Project project, UUID statusId, StatusRemovalInput request) {

        List<IssueStatus> live = statuses.findByProjectIdOrderByPositionAsc(project.getId());
        Optional<IssueStatus> removed =
                live.stream().filter(status -> status.getId().equals(statusId)).findFirst();
        if (removed.isEmpty()) {
            return new Outcome.Invalid(Map.of(
                    "statusId", List.of("This project has no such status.")));
        }
        UUID destinationId = request == null ? null : request.reassignToStatusId();
        Optional<IssueStatus> destination = live.stream()
                .filter(status -> status.getId().equals(destinationId))
                .filter(status -> !status.getId().equals(statusId))
                .findFirst();
        if (destination.isEmpty()) {
            return new Outcome.Invalid(Map.of("reassignToStatusId", List.of(
                    "Name another of this project's statuses for these issues to move to.")));
        }
        if (removed.get().getKind() == IssueStatusKind.DONE
                && live.stream().filter(status -> status.getKind() == IssueStatusKind.DONE).count() == 1) {
            return new Outcome.LastDoneStatus();
        }

        // The move and the removal in one transaction, so there is no window in which an issue
        // points at a status that is gone.
        int moved = issues.reassignStatus(statusId, destination.get().getId());
        transitions.deleteAll(transitions.findByProjectId(project.getId()).stream()
                .filter(move -> move.getFromStatusId().equals(statusId)
                        || move.getToStatusId().equals(statusId))
                .toList());
        transitions.flush();
        statuses.delete(removed.get());

        Instant now = clock.instant();
        auditEvents.save(AuditEvent.record(
                project.getId(), userId, "issue.status_removed", "project",
                List.of(project.getId()),
                "{\"status\":" + AuditEvent.jsonString(removed.get().getName())
                        + ",\"movedIssues\":" + moved + "}",
                now));

        return new Outcome.Removed();
    }

    private WorkflowView viewOf(Project project) {
        List<IssueStatusView> statusViews =
                statuses.findByProjectIdOrderByPositionAsc(project.getId()).stream()
                        .map(IssueStatusView::of)
                        .toList();
        List<IssueTypeView> typeViews =
                types.findByProjectIdOrderByPositionAsc(project.getId()).stream()
                        .map(IssueTypeView::of)
                        .toList();
        List<WorkflowView.TransitionView> moves =
                transitions.findByProjectId(project.getId()).stream()
                        .map(move -> new WorkflowView.TransitionView(
                                move.getFromStatusId(), move.getToStatusId()))
                        .sorted(Comparator
                                .comparing((WorkflowView.TransitionView move) ->
                                        move.fromStatusId().toString())
                                .thenComparing(move -> move.toStatusId().toString()))
                        .toList();
        return new WorkflowView(project.getId(), statusViews, typeViews, moves);
    }
}
