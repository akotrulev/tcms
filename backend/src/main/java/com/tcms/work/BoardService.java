package com.tcms.work;

import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.TeamDirectory;
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
import java.util.stream.Collectors;
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
 *       it is prefixed. {@link #PREFIX_EXECUTION}, {@link #PREFIX_CASE}, {@link #PREFIX_REVIEW}
 *       and {@link #PREFIX_QUARANTINE} are also what {@link #move} uses to <em>recognise</em> a derived id and refuse it — the
 *       refusal is driven by the same constant that produced the id, so the two cannot drift
 *       apart.</li>
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

    /**
     * The review prefix, and the one derived id that is <strong>not</strong> prefix-plus-one-UUID.
     *
     * <p>A review card is one review on one person's list, so its identity is the pair — the same
     * review is a card for each reviewer (§6l), and a board showing three of them needs three ids.
     * That makes {@code review:{reviewId}:{userId}}, and it costs the accidental second guard the
     * other two prefixes get: {@code UUID.fromString} would have thrown on this either way, so
     * nothing is lost, but the prefix check in {@link #isDerived} is now the <em>only</em> thing
     * standing between a review card and a {@code PATCH}. Worth knowing before a fourth generator
     * decides ids are free-form — §121's did not: {@link #PREFIX_QUARANTINE} is prefix-plus-one-UUID
     * and keeps the accidental second guard.
     */
    private static final String PREFIX_REVIEW = "review:";

    /**
     * The quarantine prefix, and the reason it is not {@link #PREFIX_CASE}.
     *
     * <p>Both cards are about a case and both carry its id, so {@code case:{caseId}} would have been
     * the obvious spelling — and a case that is <em>both</em> flagged by the health computation and
     * quarantined would then produce two cards with <strong>the same id</strong>. Nothing would
     * fail: the API returns both, the board renders both, and the SPA's list key collides silently
     * on the one screen whose whole job is to be a list. Two sources, two ids.
     */
    private static final String PREFIX_QUARANTINE = "quarantine:";

    /** {@code CaseCreateService}'s cap, for its reason: the header is attacker-controlled. */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final WorkItemRepository tasks;
    private final BoardFacts facts;
    private final BoardPeople people;
    private final TeamDirectory teams;
    private final Clock clock;

    BoardService(
            ProjectService projects,
            WorkItemRepository tasks,
            BoardFacts facts,
            BoardPeople people,
            TeamDirectory teams,
            Clock clock) {

        this.projects = projects;
        this.tasks = tasks;
        this.facts = facts;
        this.people = people;
        this.teams = teams;
        this.clock = clock;
    }

    /**
     * Everything on one project's board, or empty when the caller may not see the project.
     *
     * <p>An empty <em>list</em> and an empty {@link Optional} mean different things and the
     * controller renders them differently: the first is a reachable project with nothing on its
     * board, and the second is a 404.
     *
     * <p>{@code mine} filters through {@link #present}, after assembly — see there. The
     * cross-project form of this board is {@link #personalBoard}, which is that filter with no
     * project named.
     */
    @Transactional(readOnly = true)
    public Optional<List<WorkItemView>> board(UUID userId, UUID projectId, boolean mine) {
        return projects.readVisible(
                userId, projectId, project -> present(assemble(List.of(project)), userId, mine));
    }

    /**
     * §6l's <strong>personal "My Work" board</strong> — everything assigned to this caller across
     * every project they can see (§83).
     *
     * <h2>Personal by construction, and that is what makes it safe to leave uncapped</h2>
     *
     * <p>There is no {@code mine} parameter because there is no other cross-project board: the
     * aggregate answers <em>"what is on my plate"</em>, and the same read without the personal filter
     * would be "everything happening in the company", which §6l does not describe and nobody could
     * work from. That is also why §6l's <em>"cap derived generation with sensible limits"</em> does
     * not land here as a truncation: the bound on this <em>answer</em> is one person's workload
     * rather than the company's, so a cap would be a silent lie about somebody's queue long before
     * it was a protection. The project board is the unbounded one, and it is bounded by a project.
     *
     * <p><strong>The ANSWER is person-sized; the READ behind it is not, and that is the known cost of
     * this shape.</strong> {@link #present} filters after {@link #assemble}, so a caller who can see
     * every project in the company loads every live task, every outstanding execution, every flagged
     * case and every open review request in it — and resolves all their assignees — before keeping
     * their own. §6l's cap therefore has somewhere to land after all, just not on the response:
     * it belongs on the derived generators, whose row counts nothing bounds. Left alone here
     * deliberately, because pushing the filter down is the very thing {@link #present} refuses and it
     * would change the project board too; measure before building either, since the four statements
     * are indexed and the first company to feel this will be one with a large {@code case_health}.
     *
     * <p>Never a 404 and never an {@link Optional}, unlike {@link #board}: the caller named nothing,
     * so there is nothing that could fail to resolve. A member holding no grants at all gets an empty
     * list — reachable, and empty — which is the distinction the project board has to make and this
     * one does not.
     */
    @Transactional(readOnly = true)
    public List<WorkItemView> personalBoard(UUID userId) {
        return projects.readAllVisible(
                userId, visible -> present(assemble(visible), userId, true));
    }

    /**
     * §6l's <strong>team board</strong> — one team's work, across every project the caller can see,
     * or within one named project (§84). Empty when the team does not exist in this company, or when
     * a named project is not reachable.
     *
     * <h2>The bound is the CALLER's visibility, and never the team's</h2>
     *
     * <p>This is the whole risk of the shape and the only thing the implementation could get
     * catastrophically wrong. A team board built from <em>"cards assigned to members of team T"</em>
     * is one query and reads correctly; what it renders is the case key, the title and the project
     * name of every project any member was granted — including the ones the person looking at the
     * board was not. §83's bound, one step out: there the assignee could be somebody with no grant on
     * their own work's project, and here the <em>reader</em> is. So the projects come from
     * {@link ProjectService#readAllVisible} exactly as the personal board's do, and the team
     * contributes nothing but a set of assignees.
     *
     * <p>Consequence, stated rather than discovered: <strong>two people looking at the same team's
     * board see different boards.</strong> That is correct and is not a bug to report — a team board
     * is "this team's work, as much of it as you are entitled to see", and the alternative is a
     * cross-project leak with a team id for a key.
     *
     * <h2>Three refusals and one empty answer, and they are four different things</h2>
     *
     * <ul>
     *   <li><strong>An unknown or another company's team → empty, rendered 404.</strong> Resolved
     *       through {@link TeamDirectory#find}, which is company-scoped, so the two are one answer —
     *       or this endpoint becomes a company-wide team-existence oracle (§6t), answerable by
     *       anybody with a session and a guessed UUID.</li>
     *   <li><strong>A named project the caller cannot reach → empty, rendered 404</strong>, by
     *       {@link ProjectService#readVisible} unchanged. Note the ordering: the project gate runs
     *       <em>before</em> the empty-team shortcut below, so an empty team plus an unreachable
     *       project is still a 404 rather than a 200 that confirms the project exists.</li>
     *   <li><strong>A team with no members → {@code 200 []}.</strong> Reachable, and empty. Answered
     *       before any board query is issued: assembling a company's whole live work set in order to
     *       keep none of it is the one call whose answer is knowable in advance. Not §83's
     *       {@code IN ()} hazard — this filter runs in Java over the assembled cards, not in SQL —
     *       but the same instinct, and a newly created team is the ordinary first caller here just as
     *       a grantless member is there.</li>
     *   <li><strong>Members with no work the caller can see → {@code 200 []}</strong>, by the filter
     *       finding nothing. Indistinguishable from the row above on the wire, deliberately: both are
     *       "this team has nothing for you", and a client that could tell them apart would be reading
     *       team membership out of a board.</li>
     * </ul>
     *
     * <h2>No new permission key, and no membership requirement</h2>
     *
     * <p>Nothing here asks for anything the caller did not already hold. A key invented for this
     * would be seeded to nobody and therefore a 403 no administrator could grant their way out of
     * (§67's {@code evidence:read}, §79's {@code suite:comment}, §80's assignment gate) — and there
     * is nothing for it to protect: every card on this board is one the caller could already see on
     * a project board. Nor must the caller <em>belong</em> to the team, for §6k's reason: the org
     * chart is readable by any member of the company, and a team board somebody outside the team
     * cannot open is a lead unable to look at their own reports' work.
     */
    @Transactional(readOnly = true)
    public Optional<List<WorkItemView>> teamBoard(UUID userId, UUID teamId, UUID projectId) {
        if (teams.find(CompanyContext.require(), teamId).isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> members = facts.teamMembers(teamId);

        if (projectId == null) {
            return Optional.of(projects.readAllVisible(
                    userId, visible -> forAssignees(visible, members)));
        }
        return projects.readVisible(
                userId, projectId, project -> forAssignees(List.of(project), members));
    }

    /**
     * Assemble the gated projects and keep the cards belonging to these people.
     *
     * <p>The empty check is inside here rather than at the top of {@link #teamBoard} on purpose: by
     * the time this runs the project gate has already been applied, so an empty team can shortcut
     * without the shortcut being able to swallow a 404.
     */
    private List<WorkItemView> forAssignees(List<Project> visible, Set<UUID> assignees) {
        if (assignees.isEmpty()) {
            return List.of();
        }
        return present(assemble(visible), assignees);
    }

    /**
     * The personal filter, applied <strong>after</strong> assembly rather than inside each generator.
     *
     * <p>Pushing it down would mean four predicates to keep in step, and the one that gets forgotten
     * silently shows somebody else's work on a personal board — a leak of attention rather than of
     * data, which is why nothing would ever fail. It is also what lets the cross-project board reuse
     * the project board's assembly unchanged.
     *
     * <p>Since §84 the filter is over a <strong>set</strong> of assignees and the personal board is
     * the one-element case, which is {@code BoardFacts}' project-set decision said about people: a
     * second predicate for "is this one of the team's" would be the copy that drifts, and it would
     * drift in the direction nobody tests, since only one board would exercise each. The argument
     * above is unchanged by the generalisation — the filter is still applied after assembly, and the
     * team board inherits both the reuse and the cost {@link #personalBoard} records.
     */
    private static List<WorkItemView> present(List<WorkItemView> cards, UUID userId, boolean mine) {
        if (!mine) {
            return sorted(cards);
        }
        return present(cards, Set.of(userId));
    }

    private static List<WorkItemView> present(List<WorkItemView> cards, Set<UUID> assignees) {
        return sorted(cards.stream()
                .filter(card -> card.assignee() != null && assignees.contains(card.assignee().id()))
                .toList());
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

    /**
     * Every card in a set of <strong>already-gated</strong> projects.
     *
     * <p>One query per source over all of them, never one per project: the personal board of somebody
     * on six projects is 4 statements rather than 24, which is the difference between a view people
     * keep open all day and one they stop opening. Each card takes its project from the row's own
     * {@code projectId} rather than from a request parameter, which is what the four sources have
     * carried since §53 and what only now has a second reader.
     */
    private List<WorkItemView> assemble(List<Project> visible) {
        if (visible.isEmpty()) {
            // Before any query, because an empty collection bound into `IN ()` is a syntax error
            // rather than a predicate matching nothing (BoardFacts' header). A company member with
            // no project grants is the ordinary caller here, not an edge case.
            return List.of();
        }
        Map<UUID, Project> byId = visible.stream()
                .collect(Collectors.toMap(Project::getId, project -> project));
        Set<UUID> projectIds = byId.keySet();

        List<WorkItem> stored = tasks.findLiveInProjects(projectIds);
        List<BoardFacts.AssignedExecution> executions = facts.assignedExecutions(projectIds);
        List<BoardFacts.FlaggedCase> flagged = facts.casesNeedingAttention(projectIds);
        List<BoardFacts.PendingReview> reviews = facts.pendingReviews(projectIds);
        List<BoardFacts.QuarantinedCase> quarantined = facts.quarantinedCases(projectIds);

        // One resolution for the whole board, across all four sources — see BoardPeople on why
        // this is not done per card. A review contributes TWO ids, the reviewer and whoever asked
        // them, and both go in the same batch: the second is only used for a sentence on the card,
        // and a second round trip to render it would double a two-query resolution.
        Set<UUID> peopleIds = new HashSet<>();
        stored.forEach(task -> addIfPresent(peopleIds, task.getAssigneeId()));
        executions.forEach(execution -> addIfPresent(peopleIds, execution.assigneeId()));
        flagged.forEach(flag -> addIfPresent(peopleIds, flag.ownerId()));
        reviews.forEach(review -> {
            addIfPresent(peopleIds, review.reviewerId());
            addIfPresent(peopleIds, review.requestedBy());
        });
        quarantined.forEach(flake -> {
            addIfPresent(peopleIds, flake.ownerId());
            addIfPresent(peopleIds, flake.quarantinedBy());
        });
        Map<UUID, UserRef> resolved =
                people.resolve(CompanyContext.require(), peopleIds);

        List<WorkItemView> cards = new ArrayList<>();
        for (WorkItem task : stored) {
            cards.add(viewOf(task, byId.get(task.getProjectId()), resolved));
        }
        for (BoardFacts.AssignedExecution execution : executions) {
            cards.add(cardFor(execution, byId.get(execution.projectId()), resolved));
        }
        for (BoardFacts.FlaggedCase flag : flagged) {
            cards.add(cardFor(flag, byId.get(flag.projectId()), resolved));
        }
        for (BoardFacts.PendingReview review : reviews) {
            cards.add(cardFor(review, byId.get(review.projectId()), resolved));
        }
        for (BoardFacts.QuarantinedCase flake : quarantined) {
            cards.add(cardFor(flake, byId.get(flake.projectId()), resolved));
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
            Map<UUID, UserRef> resolved) {

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
            Map<UUID, UserRef> resolved) {

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
     * A card for a review request nobody has answered — §6l's <em>"Review requested → card for each
     * reviewer"</em>, and the inbox §6g's apparatus has not had since §76.
     *
     * <h2>{@code sourceType} and {@code sourceId} name the ARTIFACT, not the review</h2>
     *
     * <p>The other two generators put the thing the card mirrors here, and this one looks like it
     * breaks the pattern. It does not: what those two fields have always been for is <em>what the
     * card opens</em>, and a review has no URL of its own. §6g's panel is rendered on the case's,
     * suite's or plan's own page, so navigating to the artifact <em>is</em> navigating to the
     * review — while a {@code sourceId} naming the review would be an id the SPA could do nothing
     * with, on the one card whose whole purpose is to be clicked.
     *
     * <p>That is also why the contract needed no change for this generator. {@code sourceType} is a
     * free-form string and {@code kind} has carried {@code REVIEW} since §53, when the enum was
     * deliberately completed ahead of the generators — see this package's comment. Three years of
     * that decision paying off is worth one sentence.
     *
     * <h2>{@code TODO}, computed, and it leaves when they answer</h2>
     *
     * <p>Nobody has started until they say something, so a pending request is To do. It leaves the
     * board by a participation row appearing or the review closing, both of which are facts about
     * the review rather than about this card — {@code BoardFacts.pendingReviews} holds the
     * predicates. There is no code path that writes this status.
     *
     * <p>{@code allowedTransitions} is <strong>empty</strong>, for the execution card's reason and
     * one of its own: §6l says dragging a review card to Done <em>requires the approval</em> and is
     * refused with a message when the caller lacks {@code *:approve}. Approving is a real act with a
     * real gate and an {@code If-Match} behind it (§76), so offering it as a drag would be a second,
     * unguarded door to the sign-off — exactly what the participation endpoint refuses to be.
     */
    private WorkItemView cardFor(
            BoardFacts.PendingReview review,
            Project project,
            Map<UUID, UserRef> resolved) {

        UserRef asker = review.requestedBy() == null ? null : resolved.get(review.requestedBy());

        return new WorkItemView(
                PREFIX_REVIEW + review.reviewId() + ":" + review.reviewerId(),
                true,
                review.subjectType(),
                review.subjectId(),
                "REVIEW",
                review.subjectTitle(),
                asker == null
                        // Not "requested by nobody": assigned_by has no FK and no cascade, so an
                        // offboarded account leaves a live request with no named asker, and naming
                        // one would attribute the ask to somebody.
                        ? "Review requested"
                        : "Review requested by " + asker.displayName(),
                WorkItemStatus.TODO,
                Priority.MEDIUM,
                null,
                false,
                resolved.get(review.reviewerId()),
                project.getId(),
                project.getKey(),
                review.subjectKey(),
                List.of());
    }

    /**
     * A card for a test somebody has stopped gating on — §6l's fourth generator (§121).
     *
     * <h2>This is the one derived card that does not clear itself</h2>
     *
     * <p>Every other card here leaves the board because its source moved on: an execution is
     * recorded, a reviewer speaks, a health signal self-heals when the condition is fixed. A
     * quarantine is <em>authored</em>, so this one leaves only when a person releases the test —
     * which is exactly what makes it worth having. {@code CaseFlake}'s header states the
     * alternative: a card derived from the flake score would vanish the moment the test stopped
     * flaking, taking the debt with it while the test was still out of the gate.
     *
     * <p>{@code TODO}, computed, for the maintenance card's reason. There is no code path that
     * writes it, and it cannot be dragged out of the column — {@code allowedTransitions} is empty,
     * because releasing a test is a gated write ({@code case:edit}, §121) with its own endpoint and
     * a reason field that a drag has nowhere to collect.
     *
     * <h2>The assignee is the case OWNER and the description names the DECIDER</h2>
     *
     * <p>Two different people, and putting either in the other's place is a plausible mistake with a
     * visible cost. The owner is who the debt belongs to, so the card lands on their personal board
     * (§83) — assigning it to whoever quarantined the test would make pulling somebody else's flaky
     * test out of the gate an act that moves their work onto your own list. The decider goes in the
     * sentence instead, because <em>who decided this</em> is the first thing a reader asks and the
     * one thing the reason alone cannot say.
     *
     * <p>The unresolved-decider fallback is {@code PendingReview}'s exactly: {@code quarantined_by}
     * references instance-level {@code app_user}, so an offboarded account leaves a live quarantine
     * with nobody to name, and naming somebody would attribute the decision.
     */
    private WorkItemView cardFor(
            BoardFacts.QuarantinedCase flake,
            Project project,
            Map<UUID, UserRef> resolved) {

        UserRef decider =
                flake.quarantinedBy() == null ? null : resolved.get(flake.quarantinedBy());

        return new WorkItemView(
                PREFIX_QUARANTINE + flake.caseId(),
                true,
                // CASE, like the maintenance card: sourceType is what the card OPENS (the review
                // card's rule), and what a quarantine card opens is the case it is about.
                "CASE",
                flake.caseId(),
                "QUARANTINE",
                flake.caseTitle(),
                decider == null
                        ? "Quarantined — " + flake.reason()
                        : "Quarantined by " + decider.displayName() + " — " + flake.reason(),
                WorkItemStatus.TODO,
                Priority.MEDIUM,
                null,
                false,
                flake.ownerId() == null ? null : resolved.get(flake.ownerId()),
                project.getId(),
                project.getKey(),
                flake.caseKey(),
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

    private WorkItemView viewOf(WorkItem task, Project project, Map<UUID, UserRef> resolved) {
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
        return cardId.startsWith(PREFIX_EXECUTION)
                || cardId.startsWith(PREFIX_CASE)
                || cardId.startsWith(PREFIX_REVIEW)
                || cardId.startsWith(PREFIX_QUARANTINE);
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
