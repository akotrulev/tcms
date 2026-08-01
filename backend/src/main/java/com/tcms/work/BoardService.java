package com.tcms.work;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The board: stored tasks and derived cards, assembled into one list (plan §6l).
 *
 * <h2>Where the derived-item invariant actually lives</h2>
 *
 * <p>The package comment states the rule; this class is where it is kept or broken. Three properties
 * hold it together and each one is a place a plausible implementation would go wrong:
 *
 * <ol>
 *   <li><strong>A derived card's status is computed on every read</strong> ({@link #cardFor}), never
 *       stored and never cached. An unrecorded execution is {@code IN_PROGRESS} because somebody is
 *       expected to run it; a flagged case is {@code TODO} because nobody has started. There is no
 *       code path that writes either.</li>
 *   <li><strong>A derived card's id is synthetic</strong> and cannot collide with a task's, because
 *       it is prefixed. {@link #PREFIX_EXECUTION} and {@link #PREFIX_CASE} are also what
 *       {@link #move} uses to <em>recognise</em> a derived id and refuse it — the refusal is driven
 *       by the same constant that produced the id, so the two cannot drift apart.</li>
 *   <li><strong>{@code allowedTransitions} is a property of the card, not of the column</strong>
 *       ({@link #transitionsFor}).</li>
 * </ol>
 */
@Service
public class BoardService {

    /**
     * The synthetic-id prefixes.
     *
     * <p>A colon, and the part after it is a UUID — so {@code UUID.fromString} on a derived id
     * throws, which is a second, independent reason {@link #move} can never resolve one to a row.
     * The prefix check is the intentional guard; the parse failure is the accident that would also
     * have caught it, and having both is deliberate for an invariant this load-bearing.
     */
    private static final String PREFIX_EXECUTION = "execution:";
    private static final String PREFIX_CASE = "case:";

    /** {@code CaseCreateService}'s cap, for its reason: the header is attacker-controlled. */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final WorkItemRepository tasks;
    private final BoardFacts facts;
    private final BoardPeople people;
    private final Clock clock;

    BoardService(
            ProjectService projects,
            WorkItemRepository tasks,
            BoardFacts facts,
            BoardPeople people,
            Clock clock) {

        this.projects = projects;
        this.tasks = tasks;
        this.facts = facts;
        this.people = people;
        this.clock = clock;
    }

    /**
     * Everything on one project's board, or empty when the caller may not see the project.
     *
     * <p>An empty <em>list</em> and an empty {@link Optional} mean different things and the
     * controller renders them differently: the first is a reachable project with nothing on its
     * board, and the second is a 404.
     *
     * <p>{@code mine} filters <strong>after</strong> assembly rather than inside each generator.
     * Pushing it down would mean three predicates to keep in step, and the one that gets forgotten
     * silently shows somebody else's work on a personal board — a leak of attention rather than of
     * data, which is why nothing would ever fail.
     */
    @Transactional(readOnly = true)
    public Optional<List<WorkItemView>> board(UUID userId, UUID projectId, boolean mine) {
        return projects.readVisible(userId, projectId, project -> {
            List<WorkItemView> cards = assemble(project);
            if (!mine) {
                return sorted(cards);
            }
            return sorted(cards.stream()
                    .filter(card -> card.assignee() != null && card.assignee().id().equals(userId))
                    .toList());
        });
    }

    /**
     * Creates a task.
     *
     * <p>Gated on {@code workitem:manage} at project scope. The permission is checked against the
     * project named <em>in the body</em>, which is the only project this request mentions — there is
     * no path segment to gate on, so {@link ProjectService#writeGranted} is handed the body's id and
     * answers {@code Unreachable} for a project the caller cannot see. That collapses "no such
     * project", "another company's project" and "a project you hold no grant on" into one 404,
     * which is the same answer every project-scoped path gives.
     *
     * @param idempotencyKey the request's {@code Idempotency-Key} header, or null when absent
     *                       (V17). Honoured from the first day the header is <em>declared</em>,
     *                       which is §53's whole reason for leaving it undeclared until the column
     *                       existed — see {@code openapi.yaml} on this operation.
     */
    @Transactional
    public GateResult<CreateOutcome> create(
            UUID userId, WorkItemWriteRequest request, String idempotencyKey) {

        UUID projectId = request.projectId();
        if (projectId == null) {
            // Before the gate, and deliberately not a 404: there is no project to be unable to
            // see. A missing projectId is a malformed request, and answering 404 would tell a
            // caller who forgot a field that something does not exist.
            return new GateResult.Allowed<>(new CreateOutcome.Invalid(
                    Map.of("projectId", List.of("Choose a project for this task."))));
        }

        return projects.writeGranted(userId, projectId, "workitem:manage", project -> {
            // The replay is answered INSIDE the gate and BEFORE validation, and both halves are
            // deliberate. Inside, because a caller who cannot reach the project must get the same
            // 404 they would get for a first attempt — recognising their key would confirm that
            // something exists there. Before, because a retry of a create that succeeded must
            // answer with the task it made even if the payload has since drifted: the client is
            // asking "did my intent land", and a 400 would send it to fix fields on a card that
            // already exists. PlanCreateService and CaseCreateService order it the same way.
            if (idempotencyKey != null) {
                if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                    return new CreateOutcome.Invalid(Map.of("Idempotency-Key",
                            List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY
                                    + " characters.")));
                }
                Optional<WorkItem> already =
                        tasks.findByIdempotencyKey(project.getId(), idempotencyKey);
                if (already.isPresent()) {
                    return new CreateOutcome.Replayed(viewOf(already.get(), project));
                }
            }

            WorkItemEdit.Outcome outcome =
                    WorkItemEdit.validateCreate(request, assignableUsers(project, request));

            return switch (outcome) {
                case WorkItemEdit.Outcome.Invalid invalid ->
                        new CreateOutcome.Invalid(invalid.errors());
                case WorkItemEdit.Outcome.Valid valid -> {
                    WorkItem created = tasks.save(WorkItem.create(
                            project.getId(), valid.edit(), userId, idempotencyKey, clock.instant()));
                    yield new CreateOutcome.Created(viewOf(created, project));
                }
            };
        });
    }

    /**
     * Moves a card to another column.
     *
     * <h2>The three refusals, and why each has the status code it does</h2>
     *
     * <ul>
     *   <li><strong>A derived id → {@link MoveOutcome.NotStored}, rendered 409.</strong> Not 404:
     *       the card exists, the caller is looking at it, and answering "gone" would invite a reload
     *       that brings it straight back. What is impossible is storing a status for it, which is a
     *       statement about the request rather than about the resource's existence.</li>
     *   <li><strong>An unknown or invisible id → {@code Unreachable}, rendered 404.</strong></li>
     *   <li><strong>A column outside {@code allowedTransitions} → {@link MoveOutcome.NotAllowed},
     *       rendered 409.</strong> Today a stored task may move anywhere, so this is unreachable
     *       from the SPA — it is here because {@code allowedTransitions} is the client's copy of a
     *       server rule, and a rule enforced only on the client is not enforced.</li>
     * </ul>
     */
    @Transactional
    public GateResult<MoveOutcome> move(UUID userId, String cardId, WorkItemStatus target) {
        if (isDerived(cardId)) {
            // Answered BEFORE any lookup: a derived id names no row, so a repository call would
            // return empty and the natural rendering of that is the 404 this deliberately is not.
            return new GateResult.Allowed<>(new MoveOutcome.NotStored());
        }

        UUID id;
        try {
            id = UUID.fromString(cardId);
        } catch (IllegalArgumentException malformed) {
            // A card id that is neither a derived id nor a UUID names nothing. 404 rather than 400:
            // it is the same answer an unknown UUID gets, and distinguishing them tells a caller
            // which ids are well-formed, which is the probe §6t's 404-over-403 rule closes.
            return new GateResult.Unreachable<>();
        }

        Optional<WorkItem> found = tasks.findLiveById(id);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        WorkItem task = found.get();

        // The gate is the TASK's project, not one the request supplied — there is no project in
        // this path. A caller who may see the card but holds no workitem:manage on its project gets
        // Denied (403); one who cannot see the project at all gets Unreachable (404).
        return projects.writeGranted(userId, task.getProjectId(), "workitem:manage", project -> {
            if (!transitionsFor(task).contains(target)) {
                return new MoveOutcome.NotAllowed(task.getStatus(), target);
            }
            task.moveTo(target, userId, clock.instant());
            return new MoveOutcome.Moved(viewOf(task, project));
        });
    }

    // =====================================================================
    // Assembly
    // =====================================================================

    private List<WorkItemView> assemble(Project project) {
        List<WorkItem> stored = tasks.findLiveInProject(project.getId());
        List<BoardFacts.AssignedExecution> executions = facts.assignedExecutions(project.getId());
        List<BoardFacts.FlaggedCase> flagged = facts.casesNeedingAttention(project.getId());

        // One resolution for the whole board, across all three sources — see BoardPeople on why
        // this is not done per card.
        Set<UUID> peopleIds = new HashSet<>();
        stored.forEach(task -> addIfPresent(peopleIds, task.getAssigneeId()));
        executions.forEach(execution -> addIfPresent(peopleIds, execution.assigneeId()));
        flagged.forEach(flag -> addIfPresent(peopleIds, flag.ownerId()));
        Map<UUID, UserRefView> resolved =
                people.resolve(CompanyContext.require(), peopleIds);

        List<WorkItemView> cards = new ArrayList<>();
        for (WorkItem task : stored) {
            cards.add(viewOf(task, project, resolved));
        }
        for (BoardFacts.AssignedExecution execution : executions) {
            cards.add(cardFor(execution, project, resolved));
        }
        for (BoardFacts.FlaggedCase flag : flagged) {
            cards.add(cardFor(flag, project, resolved));
        }
        return cards;
    }

    /**
     * Overdue first, then by priority, then by id.
     *
     * <p>Sorted here rather than left to the SPA — which sorts too — for {@code DashboardService}'s
     * reason: the order is a property of the answer, and a client that forgot would bury a critical
     * overdue card under thirty routine ones. The id tiebreaker is what stops cards reshuffling on
     * every refetch, which reads as the page flickering rather than as an unstable sort.
     */
    private static List<WorkItemView> sorted(List<WorkItemView> cards) {
        return cards.stream()
                .sorted(Comparator
                        .comparing(WorkItemView::overdue).reversed()
                        .thenComparing(WorkItemView::priority)
                        .thenComparing(WorkItemView::id))
                .toList();
    }

    /**
     * A card for an outstanding execution.
     *
     * <p><strong>{@code IN_PROGRESS}, computed, and there is no other value it could take.</strong>
     * The generator only returns unrecorded executions in live runs (see {@code BoardFacts}), so
     * "somebody is expected to run this" is the whole population. A recorded execution is not on the
     * board at all rather than being a card in {@code DONE} — §6l says "execution completed → card
     * leaves", and a Done column accumulating every result ever recorded is a board that gets
     * slower and less useful every week.
     *
     * <p>{@code allowedTransitions} is <strong>empty</strong>. Dragging this card cannot do
     * anything: §6l's eventual behaviour is that moving it to Done <em>opens the execution to be
     * completed</em>, and until that navigation exists, offering a move that silently does nothing
     * is worse than offering none. The SPA disables every move button and says why (§31).
     */
    private WorkItemView cardFor(
            BoardFacts.AssignedExecution execution,
            Project project,
            Map<UUID, UserRefView> resolved) {

        return new WorkItemView(
                PREFIX_EXECUTION + execution.executionId(),
                true,
                "EXECUTION",
                execution.executionId(),
                "EXECUTION",
                execution.caseTitle(),
                "Execute in " + execution.runName(),
                WorkItemStatus.IN_PROGRESS,
                Priority.MEDIUM,
                null,
                false,
                execution.assigneeId() == null ? null : resolved.get(execution.assigneeId()),
                project.getId(),
                project.getKey(),
                execution.caseKey(),
                List.of());
    }

    /**
     * A card for a case the health computation has flagged.
     *
     * <p>{@code TODO}, computed: a signal nobody has acted on is work not started. It leaves the
     * board by the signal clearing — which happens because the underlying condition was fixed, since
     * {@code case_health} is derived and has no "mark as not stale" button to lie with
     * ({@code CaseHealth}'s header). That is the derived-item property working end to end: the card
     * cannot be dismissed without fixing what it is about.
     *
     * <p>The title names the signal rather than reading "needs attention", because a card whose
     * reason is hidden behind a click is one that gets ignored in bulk.
     */
    private WorkItemView cardFor(
            BoardFacts.FlaggedCase flagged,
            Project project,
            Map<UUID, UserRefView> resolved) {

        return new WorkItemView(
                PREFIX_CASE + flagged.caseId(),
                true,
                "CASE",
                flagged.caseId(),
                "CASE_MAINTENANCE",
                flagged.caseTitle(),
                describe(flagged.signals()),
                WorkItemStatus.TODO,
                Priority.MEDIUM,
                null,
                false,
                flagged.ownerId() == null ? null : resolved.get(flagged.ownerId()),
                project.getId(),
                project.getKey(),
                flagged.caseKey(),
                List.of());
    }

    /**
     * The signals as a sentence.
     *
     * <p>Deliberately not labelled here. {@code repository.HealthSignal} owns the human-readable
     * label and this module cannot import it — so rather than inventing a second set of words that
     * would drift from the dashboard's, the card carries the raw signal names and the SPA labels
     * them from the contract's enum, which is where every other enum's words already come from
     * (§ the {@code lib/labels.ts} rule). A second labelling here is exactly how one value comes to
     * read three different ways across grid, dashboard and board.
     */
    private static String describe(List<String> signals) {
        return String.join(", ", signals);
    }

    private WorkItemView viewOf(WorkItem task, Project project) {
        return viewOf(task, project,
                people.resolve(CompanyContext.require(),
                        task.getAssigneeId() == null ? Set.of() : Set.of(task.getAssigneeId())));
    }

    private WorkItemView viewOf(WorkItem task, Project project, Map<UUID, UserRefView> resolved) {
        return new WorkItemView(
                task.getId().toString(),
                false,
                null,
                null,
                "TASK",
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getDueDate(),
                isOverdue(task),
                task.getAssigneeId() == null ? null : resolved.get(task.getAssigneeId()),
                project.getId(),
                project.getKey(),
                null,
                transitionsFor(task));
    }

    /**
     * Overdue means a due date strictly before today, and a card in {@code DONE} is never overdue.
     *
     * <p>The second half is the one worth stating: without it, finishing a late task leaves the card
     * flagged red forever, so the Done column fills with alarms about work that is complete and the
     * flag stops meaning anything anywhere on the board.
     *
     * <p>UTC, matching the column's {@code DATE} and every other date on the wire. A board read at
     * 23:00 in a westward zone can therefore flag something as due tomorrow that the reader would
     * call today — accepted, because the alternative is a per-user timezone the product does not
     * collect, and §6l's deadlines are days the whole team agreed on rather than personal ones.
     */
    private boolean isOverdue(WorkItem task) {
        if (task.getDueDate() == null || task.getStatus() == WorkItemStatus.DONE) {
            return false;
        }
        return task.getDueDate().isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

    /**
     * Where a stored task may go: every column except the one it is in.
     *
     * <p>A task's status is its own — that is what makes it a task rather than a projection — so
     * there is no workflow to enforce and inventing one ("you may not go from To do straight to
     * Done") would be a rule nobody asked for on the screen people use to correct mistakes.
     * Excluding the current column keeps the SPA from rendering a move button that does nothing.
     */
    private static List<WorkItemStatus> transitionsFor(WorkItem task) {
        List<WorkItemStatus> allowed = new ArrayList<>();
        for (WorkItemStatus status : WorkItemStatus.values()) {
            if (status != task.getStatus()) {
                allowed.add(status);
            }
        }
        return allowed;
    }

    /**
     * Who may be assigned: active members of this company holding a grant on this project.
     *
     * <p>Both halves are necessary and {@code WorkItemEdit}'s header explains what each one alone
     * lets through. Since §54 neither half is expressed here — the predicate lives in
     * {@code identity.ProjectMembershipService} and is the same one
     * {@code GET /projects/{id}/members} filters the picker with, so this validator cannot refuse
     * somebody the SPA was just shown. {@code BoardPeople.isAssignableTo} carries the account of
     * why that had to stop being two ANDed calls at this line.
     *
     * <p>The set is computed only when the request names an assignee, because the common case is an
     * unassigned task (§6l's unclaimed pool) and this is a membership scan.
     */
    private Set<UUID> assignableUsers(Project project, WorkItemWriteRequest request) {
        UUID assigneeId = request.assigneeId();
        if (assigneeId == null) {
            return Set.of();
        }
        return people.isAssignableTo(assigneeId, project.getId())
                ? Set.of(assigneeId)
                : Set.of();
    }

    private static boolean isDerived(String cardId) {
        return cardId.startsWith(PREFIX_EXECUTION) || cardId.startsWith(PREFIX_CASE);
    }

    private static void addIfPresent(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }

    /** Sealed so the controller's mapping is exhaustive, like {@code GateResult}. */
    public sealed interface CreateOutcome {

        record Invalid(Map<String, List<String>> errors) implements CreateOutcome {
        }

        record Created(WorkItemView view) implements CreateOutcome {
        }

        /**
         * A retry of a create that already succeeded. 200, so "I made this" stays distinguishable
         * from "this already existed" — the distinction {@code PlanCreateService.Outcome.Replayed}
         * draws, and the reason neither carries a {@code Location}.
         */
        record Replayed(WorkItemView view) implements CreateOutcome {
        }
    }

    /** Likewise for a move. */
    public sealed interface MoveOutcome {

        /** The id names a derived card, which has no stored status. Rendered 409. */
        record NotStored() implements MoveOutcome {
        }

        /** The target column is not in this card's allowed transitions. Rendered 409. */
        record NotAllowed(WorkItemStatus from, WorkItemStatus to) implements MoveOutcome {
        }

        record Moved(WorkItemView view) implements MoveOutcome {
        }
    }
}
