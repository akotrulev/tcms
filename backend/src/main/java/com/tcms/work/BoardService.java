package com.tcms.work;

import com.tcms.api.UserRef;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ProjectSettingsService;
import com.tcms.project.TeamDirectory;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * The board: issues and derived cards, assembled into one list (plan §6l).
 *
 * <p>~~stored tasks and derived cards~~ → the stored half is an {@code issue} in
 * {@code com.tcms.tracker} since 009 module M1, read through {@link BoardFacts#assignedIssues}. The
 * rule the module is built around is unchanged and is why the replacement was possible at all:
 * exactly one kind of card is a row.
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
 *   <li><strong>A derived card's id is synthetic</strong> and cannot collide with an issue's,
 *       because it is prefixed — and an issue's id on this board is its <em>key</em>, which cannot
 *       hold a colon. ~~{@link #PREFIX_EXECUTION} … are also what {@code move} uses to recognise a
 *       derived id and refuse it.~~ <strong>→ that refusal moved</strong>: {@code PATCH
 *       /work-items/{id}} is gone (D-a) and moving a card is {@code POST /issues/{key}/transitions},
 *       which answers 404 for a synthetic id because it is not an issue key. Recorded as a
 *       regression — the old 409 named the action that does work, and M2's board restores it.</li>
 *   <li><strong>{@code allowedTransitions} is a property of the card, not of the column.</strong>
 *       For an {@code ISSUE} card it is the project's own workflow ({@link BoardFacts#legalMoves});
 *       for a derived one it is empty, per generator, with the reason at each.</li>
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

    /**
     * The card kinds, ordered as the board sorts within one priority.
     *
     * <p>{@code ISSUE} is the row-backed one and is deliberately not special-cased anywhere below:
     * what separates it from the other four is that its status comes from a row, and every other
     * property of a card is assembled the same way for all five.
     */
    private static final String KIND_ISSUE = "ISSUE";

    /**
     * Priority, ranked rather than compared.
     *
     * <p>{@code work.Priority} retired with {@code work_item}, so the value is a String carried from
     * {@code tracker} ({@link BoardCardView}), and a String comparison would sort
     * {@code CRITICAL, HIGH, LOW, MEDIUM} — putting the least urgent card above the middle one on
     * every board in the product, silently, with the sort still looking stable. The order is the
     * contract's {@code Priority} enum, which is the same order the retired enum declared.
     */
    private static final List<String> PRIORITY_ORDER =
            List.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    /**
     * What a derived card's priority is, and it is the same answer it has always been.
     *
     * <p>The four derived generators carry no priority of their own — an execution, a health signal,
     * a review request and a quarantine each have one urgency, which is "this is outstanding" — so
     * they sit in the middle of the sort rather than above or below every issue. Spelled as the
     * literal the retired {@code work.Priority.MEDIUM} produced on the wire, because that is what it
     * has always been and a derived card's position in the sort is not a thing to change quietly.
     */
    private static final String DEFAULT_PRIORITY = "MEDIUM";

    private final ProjectService projects;
    private final ProjectSettingsService settings;
    private final BoardFacts facts;
    private final BoardPeople people;
    private final TeamDirectory teams;
    private final CardAnnotationRepository annotations;
    private final Clock clock;

    BoardService(
            ProjectService projects,
            ProjectSettingsService settings,
            BoardFacts facts,
            BoardPeople people,
            TeamDirectory teams,
            CardAnnotationRepository annotations,
            Clock clock) {

        this.projects = projects;
        this.settings = settings;
        this.facts = facts;
        this.people = people;
        this.teams = teams;
        this.annotations = annotations;
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
    public Optional<List<BoardCardView>> board(UUID userId, UUID projectId, boolean mine) {
        return projects.readVisible(userId, projectId,
                project -> present(assemble(List.of(project), userId), userId, mine));
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
    public List<BoardCardView> personalBoard(UUID userId) {
        return projects.readAllVisible(
                userId, visible -> present(assemble(visible, userId), userId, true));
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
    public Optional<List<BoardCardView>> teamBoard(UUID userId, UUID teamId, UUID projectId) {
        if (teams.find(CompanyContext.require(), teamId).isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> members = facts.teamMembers(teamId);

        if (projectId == null) {
            return Optional.of(projects.readAllVisible(
                    userId, visible -> forAssignees(visible, userId, members)));
        }
        return projects.readVisible(
                userId, projectId, project -> forAssignees(List.of(project), userId, members));
    }

    /**
     * Assemble the gated projects and keep the cards belonging to these people.
     *
     * <p>The empty check is inside here rather than at the top of {@link #teamBoard} on purpose: by
     * the time this runs the project gate has already been applied, so an empty team can shortcut
     * without the shortcut being able to swallow a 404.
     */
    private List<BoardCardView> forAssignees(
            List<Project> visible, UUID userId, Set<UUID> assignees) {

        if (assignees.isEmpty()) {
            return List.of();
        }
        return present(assemble(visible, userId), assignees);
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
    private static List<BoardCardView> present(
            List<BoardCardView> cards, UUID userId, boolean mine) {

        if (!mine) {
            return sorted(cards);
        }
        return present(cards, Set.of(userId));
    }

    private static List<BoardCardView> present(List<BoardCardView> cards, Set<UUID> assignees) {
        return sorted(cards.stream()
                .filter(card -> card.assignee() != null && assignees.contains(card.assignee().id()))
                .toList());
    }

    /**
     * Writes the caller's own note, personal due date and flag on a card (D11, story B5).
     *
     * <p><strong>Keyed on the CALLER and on nobody else.</strong> Two people reading one card get
     * different values here, and that is the feature rather than a caching bug.
     *
     * <p><strong>No permission beyond seeing the card, and no project gate at all.</strong>
     * Annotating something is not editing it — which is the whole reason this is a separate table
     * from the row — and a note nobody else can read is not a claim about the work. RLS bounds the
     * write to the caller's company and the primary key bounds it to the caller, so the two things
     * a gate here would protect are already protected by something that cannot be forgotten.
     *
     * <p>The merge is a PATCH's, on a PUT: an absent key is <em>leave it</em>, which the contract
     * says and which {@link com.tcms.api.PatchBody} is what makes expressible — without it Jackson
     * gives an absent field and an explicit null the same value, and every flag edit would silently
     * empty the note.
     */
    @Transactional
    public CardAnnotationView annotate(
            UUID userId,
            String sourceType,
            UUID sourceId,
            CardAnnotationInput request,
            com.tcms.api.PatchBody patch) {

        CardAnnotationId id = new CardAnnotationId(userId, sourceType, sourceId);
        CardAnnotation annotation = annotations.findById(id).orElseGet(() ->
                CardAnnotation.forCard(CompanyContext.require(), userId, sourceType, sourceId));

        annotation.apply(
                patch.has("note") ? request.note() : annotation.getNote(),
                patch.has("personalDueDate")
                        ? request.personalDueDate() : annotation.getPersonalDueDate(),
                patch.has("flagged") && request.flagged() != null
                        ? request.flagged() : annotation.isFlagged());

        return CardAnnotationView.of(annotations.save(annotation));
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
    private List<BoardCardView> assemble(List<Project> visible, UUID userId) {
        if (visible.isEmpty()) {
            // Before any query, because an empty collection bound into `IN ()` is a syntax error
            // rather than a predicate matching nothing (BoardFacts' header). A company member with
            // no project grants is the ordinary caller here, not an edge case.
            return List.of();
        }
        Map<UUID, Project> byId = visible.stream()
                .collect(Collectors.toMap(Project::getId, project -> project));
        Set<UUID> projectIds = byId.keySet();

        List<BoardFacts.AssignedIssue> issues = facts.assignedIssues(projectIds);
        List<BoardFacts.AssignedExecution> executions = facts.assignedExecutions(projectIds);
        List<BoardFacts.FlaggedCase> flagged = facts.casesNeedingAttention(projectIds);
        List<BoardFacts.PendingReview> reviews = facts.pendingReviews(projectIds);
        List<BoardFacts.QuarantinedCase> quarantined = facts.quarantinedCases(projectIds);

        // One resolution for the whole board, across all five sources — see BoardPeople on why
        // this is not done per card. A review contributes TWO ids, the reviewer and whoever asked
        // them, and both go in the same batch: the second is only used for a sentence on the card,
        // and a second round trip to render it would double a two-query resolution.
        Set<UUID> peopleIds = new HashSet<>();
        issues.forEach(issue -> addIfPresent(peopleIds, issue.assigneeId()));
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

        Map<UUID, List<BoardStatusRef>> moves = movesByStatus(projectIds);
        Map<String, CardAnnotationView> notes = notesOf(userId);

        // D33, per project rather than per card: the guard is a project setting and this board
        // spans several, so one read each beats one read per card on the view somebody keeps open
        // all day.
        Map<UUID, Boolean> closeGuarded = new HashMap<>();
        projectIds.forEach(projectId -> closeGuarded.put(
                projectId, settings.effective(projectId).issueCloseNeedsSecondPerson()));

        List<BoardCardView> cards = new ArrayList<>();
        for (BoardFacts.AssignedIssue issue : issues) {
            cards.add(cardFor(issue, byId.get(issue.projectId()), resolved, moves, notes,
                    Boolean.TRUE.equals(closeGuarded.get(issue.projectId()))
                            && userId.equals(issue.assigneeId())));
        }
        for (BoardFacts.AssignedExecution execution : executions) {
            cards.add(cardFor(execution, byId.get(execution.projectId()), resolved, notes));
        }
        for (BoardFacts.FlaggedCase flag : flagged) {
            cards.add(cardFor(flag, byId.get(flag.projectId()), resolved, notes));
        }
        for (BoardFacts.PendingReview review : reviews) {
            cards.add(cardFor(review, byId.get(review.projectId()), resolved, notes));
        }
        for (BoardFacts.QuarantinedCase flake : quarantined) {
            cards.add(cardFor(flake, byId.get(flake.projectId()), resolved, notes));
        }
        return cards;
    }

    /**
     * The workflow, keyed by the status a card is standing in.
     *
     * <p>One statement for the whole board rather than one per card — {@link BoardFacts#legalMoves}
     * carries the argument. A status with no row here has no legal move out of it, which is a real
     * state: {@code issue_transition} is an allow-list, so a status added to a workflow reaches
     * nothing until somebody says where it goes.
     */
    private Map<UUID, List<BoardStatusRef>> movesByStatus(Set<UUID> projectIds) {
        Map<UUID, List<BoardStatusRef>> byStatus = new HashMap<>();
        for (BoardFacts.LegalMove move : facts.legalMoves(projectIds)) {
            byStatus.computeIfAbsent(move.fromStatusId(), status -> new ArrayList<>())
                    .add(new BoardStatusRef(
                            move.toStatusId(), move.toName(), move.toKind(), move.toPosition(),
                            move.toBlocking()));
        }
        return byStatus;
    }

    /**
     * The caller's own annotations, keyed by the {@code (sourceType, sourceId)} a card carries.
     *
     * <p>Every one of them, not only the ones on this board: the set is bounded by how many notes
     * one person has written, and narrowing it would mean binding a list of every card's id into an
     * {@code IN} — with the empty-collection hazard on the ordinary path, since most people have
     * annotated nothing.
     */
    private Map<String, CardAnnotationView> notesOf(UUID userId) {
        Map<String, CardAnnotationView> notes = new HashMap<>();
        for (CardAnnotation annotation : annotations.findByUserId(userId)) {
            notes.put(annotationKey(annotation.getSourceType(), annotation.getSourceId()),
                    CardAnnotationView.of(annotation));
        }
        return notes;
    }

    /**
     * An annotation's key is the card's <strong>kind</strong> and its source id, not its
     * {@code sourceType}.
     *
     * <p>{@code sourceType} says what the card OPENS, and two cards can open the same thing: a case
     * that is both flagged by the health computation and quarantined produces two cards, both
     * {@code sourceType = CASE} with the same case id. Keyed that way, a note written on one would
     * appear on the other — the same collision the synthetic ids avoid one field over, and the
     * reason {@link #PREFIX_QUARANTINE} is not {@link #PREFIX_CASE}.
     *
     * <p>{@code ISSUE} is the kind and is also what {@code V3} relabelled its migrated {@code 'TASK'}
     * rows to, so a note written on a task before the upgrade lands on the issue it became.
     *
     * <p><strong>The one collision this does not resolve is two reviewers of one artifact</strong> —
     * a review card's identity is the pair {@code (review, reviewer)} and this key has room for one
     * id. Accepted rather than solved: the annotation is private to the caller, so what two
     * reviewers share is a note each wrote for themselves on the same case, and widening the table's
     * primary key is a migration for a case §6l does not describe.
     */
    private static String annotationKey(String kind, UUID sourceId) {
        return kind + ":" + sourceId;
    }

    /**
     * Overdue first, then by priority, then by id.
     *
     * <p>Sorted here rather than left to the SPA — which sorts too — for {@code DashboardService}'s
     * reason: the order is a property of the answer, and a client that forgot would bury a critical
     * overdue card under thirty routine ones. The id tiebreaker is what stops cards reshuffling on
     * every refetch, which reads as the page flickering rather than as an unstable sort.
     */
    private static List<BoardCardView> sorted(List<BoardCardView> cards) {
        return cards.stream()
                .sorted(Comparator
                        .comparing(BoardCardView::overdue).reversed()
                        .thenComparingInt(BoardService::rank)
                        .thenComparing(BoardCardView::id))
                .toList();
    }

    /**
     * Priority as a number, because it is a String on the card — see {@link #PRIORITY_ORDER}.
     *
     * <p>An unknown value sorts last rather than throwing. The column has a CHECK behind it, so the
     * only way to reach that arm is a value added in a migration and not here — and a card in the
     * wrong place is a better failure than a board that will not render.
     */
    private static int rank(BoardCardView card) {
        int at = card.priority() == null ? -1 : PRIORITY_ORDER.indexOf(card.priority());
        return at < 0 ? PRIORITY_ORDER.size() : at;
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
    private BoardCardView cardFor(
            BoardFacts.AssignedExecution execution,
            Project project,
            Map<UUID, UserRef> resolved,
            Map<String, CardAnnotationView> notes) {

        return new BoardCardView(
                PREFIX_EXECUTION + execution.executionId(),
                true,
                "EXECUTION",
                execution.executionId(),
                "EXECUTION",
                execution.caseTitle(),
                "Execute in " + execution.runName(),
                CardStatus.inProgress(),
                DEFAULT_PRIORITY,
                null,
                false,
                execution.assigneeId() == null ? null : resolved.get(execution.assigneeId()),
                project.getId(),
                project.getKey(),
                execution.caseKey(),
                notes.get(annotationKey("EXECUTION", execution.executionId())),
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
    private BoardCardView cardFor(
            BoardFacts.FlaggedCase flagged,
            Project project,
            Map<UUID, UserRef> resolved,
            Map<String, CardAnnotationView> notes) {

        return new BoardCardView(
                PREFIX_CASE + flagged.caseId(),
                true,
                "CASE",
                flagged.caseId(),
                "CASE_MAINTENANCE",
                flagged.caseTitle(),
                describe(flagged.signals()),
                CardStatus.toDo(),
                DEFAULT_PRIORITY,
                null,
                false,
                flagged.ownerId() == null ? null : resolved.get(flagged.ownerId()),
                project.getId(),
                project.getKey(),
                flagged.caseKey(),
                notes.get(annotationKey("CASE_MAINTENANCE", flagged.caseId())),
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
    private BoardCardView cardFor(
            BoardFacts.PendingReview review,
            Project project,
            Map<UUID, UserRef> resolved,
            Map<String, CardAnnotationView> notes) {

        UserRef asker = review.requestedBy() == null ? null : resolved.get(review.requestedBy());

        return new BoardCardView(
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
                CardStatus.toDo(),
                DEFAULT_PRIORITY,
                null,
                false,
                resolved.get(review.reviewerId()),
                project.getId(),
                project.getKey(),
                review.subjectKey(),
                notes.get(annotationKey("REVIEW", review.subjectId())),
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
    private BoardCardView cardFor(
            BoardFacts.QuarantinedCase flake,
            Project project,
            Map<UUID, UserRef> resolved,
            Map<String, CardAnnotationView> notes) {

        UserRef decider =
                flake.quarantinedBy() == null ? null : resolved.get(flake.quarantinedBy());

        return new BoardCardView(
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
                CardStatus.toDo(),
                DEFAULT_PRIORITY,
                null,
                false,
                flake.ownerId() == null ? null : resolved.get(flake.ownerId()),
                project.getId(),
                project.getKey(),
                flake.caseKey(),
                notes.get(annotationKey("QUARANTINE", flake.caseId())),
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

    /**
     * A card for an issue — <strong>the row-backed one, and the only one</strong>.
     *
     * <h2>The id is the KEY, not the UUID</h2>
     *
     * <p>{@code ACME-124} is what a person types into a commit message, what every screen shows and
     * what {@code /issues/{key}} takes, so a board card whose id was the row's UUID would be the one
     * identifier in the product that could not be pasted anywhere. It cannot collide with a derived
     * id either: a key holds no colon.
     *
     * <h2>{@code allowedTransitions} come from the project's workflow</h2>
     *
     * <p>~~Every column except the one it is in.~~ <strong>→ superseded by 009 module M1.</strong>
     * {@code WorkItem.moveTo}'s comment said no transition was refused <em>"and that is the model
     * rather than an omission"</em>; that model is over. {@code issue_transition} is an allow-list
     * per project, which is what makes {@code settings-workflow.html}'s "Allowed moves" panel mean
     * anything — and what makes a board that offers a move the endpoint would then refuse
     * impossible rather than merely unlikely.
     *
     * <p><strong>Empty for an Epic</strong> — and since 021 R10 this is the last surface where
     * that is true, so it is a deliberate leftover rather than the rule it used to be. An epic's
     * status is stored now and {@code POST /issues/&#123;key&#125;/transitions} accepts the move;
     * this board keeps the empty list because it retires in 021 T8 (D-n) and the contract's
     * {@code BoardCard} still declares it. The cost meanwhile is a drag refused client-side on a
     * screen the project boards replaced, never a write that disagrees with the tracker.
     *
     * <p>~~(D31): an epic's status is derived from its stories and is not editable, so there is no
     * legal move out of it for anybody. {@code IssueView} answers the same empty list and
     * {@code TransitionService} refuses the move, so the three are one decision expressed three
     * times rather than three rules that could drift.~~ → two of the three inverted by R10.
     */
    private BoardCardView cardFor(
            BoardFacts.AssignedIssue issue,
            Project project,
            Map<UUID, UserRef> resolved,
            Map<UUID, List<BoardStatusRef>> moves,
            Map<String, CardAnnotationView> notes,
            boolean withholdDone) {

        boolean epic = "EPIC".equals(issue.typeLevel());

        return new BoardCardView(
                issue.issueKey(),
                false,
                KIND_ISSUE,
                issue.issueId(),
                KIND_ISSUE,
                issue.title(),
                issue.description(),
                new CardStatus(issue.statusName(), issue.statusKind()),
                issue.priority(),
                issue.dueDate(),
                isOverdue(issue),
                issue.assigneeId() == null ? null : resolved.get(issue.assigneeId()),
                project.getId(),
                project.getKey(),
                null,
                notes.get(annotationKey(KIND_ISSUE, issue.issueId())),
                epic ? List.of() : legalMoves(issue, moves, withholdDone));
    }

    /**
     * The card's legal moves, with {@code DONE} subtracted when D33's guard binds for this reader.
     *
     * <p>Subtracted from the workflow's set rather than replacing it, exactly as
     * {@code tracker.IssueService} and {@code tracker.board.BoardCardService} do: the three are one
     * decision expressed three times, and a caller under the guard is still offered In Review —
     * which is what makes the refusal something they can act on rather than a dead end.
     */
    private static List<BoardStatusRef> legalMoves(
            BoardFacts.AssignedIssue issue,
            Map<UUID, List<BoardStatusRef>> moves,
            boolean withholdDone) {

        List<BoardStatusRef> legal = moves.getOrDefault(issue.statusId(), List.of());
        return withholdDone
                ? legal.stream().filter(move -> !"DONE".equals(move.kind())).toList()
                : legal;
    }

    /**
     * Overdue means a due date strictly before today.
     *
     * <p>A card in a {@code DONE}-kind status is never overdue, and here that needs no clause of its
     * own: {@link BoardFacts#assignedIssues} does not return one, for §6l's "card leaves" rule. The
     * check the retired {@code work_item} version needed — <em>a task in DONE is never overdue</em> —
     * was guarding against a Done column that filled with red alarms about finished work, and the
     * generator now prevents that one table earlier.
     *
     * <p>UTC, matching the column's {@code DATE} and every other date on the wire. A board read at
     * 23:00 in a westward zone can therefore flag something as due tomorrow that the reader would
     * call today — accepted, because the alternative is a per-user timezone the product does not
     * collect, and §6l's deadlines are days the whole team agreed on rather than personal ones.
     */
    private boolean isOverdue(BoardFacts.AssignedIssue issue) {
        return issue.dueDate() != null
                && issue.dueDate().isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

    private static void addIfPresent(Set<UUID> ids, UUID id) {
        if (id != null) {
            ids.add(id);
        }
    }
}
