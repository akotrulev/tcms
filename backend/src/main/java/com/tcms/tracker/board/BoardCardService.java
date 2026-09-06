package com.tcms.tracker.board;

import com.tcms.api.UserRef;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.project.ProjectSettingsService;
import com.tcms.project.ReviewSettings;
import com.tcms.project.StatusKind;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.Completion;
import com.tcms.tracker.CountedIssue;
import com.tcms.tracker.EpicStatus;
import com.tcms.tracker.IssueService;
import com.tcms.tracker.Issue;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueRepository;
import com.tcms.tracker.IssueStatus;
import com.tcms.tracker.IssueStatusKind;
import com.tcms.tracker.IssueStatusRepository;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.IssueTransitionRepository;
import com.tcms.tracker.IssueType;
import com.tcms.tracker.IssueTypeRepository;
import com.tcms.tracker.Priority;
import com.tcms.tracker.release.LinkedCaseFacts;
import com.tcms.tracker.release.ReleaseRefView;
import com.tcms.tracker.release.ReleaseRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cards on a board, and the backlog — the two reads that assemble an issue into a card face.
 *
 * <h2>The WIP breach is REPORTED, never enforced</h2>
 *
 * <p>Story B3: <em>"the drop is accepted and the column header shows the breach"</em>. This returns
 * every column currently over its limit, and {@link BoardMoveService} answers 200 with the breach
 * rather than refusing the move. A limit that refused drops is a limit people route around by not
 * using the board, and then the board stops describing the work — which costs more than the limit
 * was ever worth. A column at <strong>exactly</strong> its limit is not in breach.
 *
 * <p>Counted across every lane and over the <em>whole</em> project rather than the filtered set: a
 * WIP limit is about how much is in flight, three cards in three epics is three cards in flight, and
 * narrowing the view does not take work out of a column. Counted once per issue, so a card that
 * appears in three label lanes is one card.
 *
 * <h2>Filter semantics are Trello's, exactly</h2>
 *
 * <p><strong>OR within a category, AND across categories</strong> (§12.2) — worth copying precisely
 * because it is learnable. {@code labelId=a&labelId=b&assigneeId=c} is <em>(a or b) and c</em>.
 *
 * <h2>An Epic is a card here and a row in nothing</h2>
 *
 * <p>~~On the board an epic is a card whose {@code status} is the <strong>derived</strong> one
 * (D31) and whose {@code allowedTransitions} is empty~~ → superseded (021 R10): an epic's status
 * is stored and it moves like a story, so its card carries the stored status and the workflow's
 * moves. What the card adds is {@code allStoriesDone} — the nudge that the work underneath is
 * finished, which the PO acts on with a drag and the server never acts on by itself.
 *
 * <p>In the <em>backlog</em> an epic is still neither a row nor a card: it has nothing to be
 * ranked against, and a list carrying both an epic and the stories under it double-counts the same
 * work in the one view whose whole job is "what is next".
 */
@Service
public class BoardCardService {

    private final ProjectService projects;
    private final PeopleDirectory people;
    private final BoardRepository boards;
    private final BoardColumnRepository columns;
    private final BoardColumnStatusRepository claims;
    private final IssueRepository issues;
    private final IssueTypeRepository types;
    private final IssueStatusRepository statuses;
    private final IssueTransitionRepository transitions;
    private final IssueLabelRepository labels;
    private final IssueLabelAssignmentRepository assignments;
    private final ReleaseRepository releases;
    private final LinkedCaseFacts linkedCases;
    private final ProjectSettingsService settings;
    private final Clock clock;

    public BoardCardService(
            ProjectService projects,
            PeopleDirectory people,
            BoardRepository boards,
            BoardColumnRepository columns,
            BoardColumnStatusRepository claims,
            IssueRepository issues,
            IssueTypeRepository types,
            IssueStatusRepository statuses,
            IssueTransitionRepository transitions,
            IssueLabelRepository labels,
            IssueLabelAssignmentRepository assignments,
            ReleaseRepository releases,
            LinkedCaseFacts linkedCases,
            ProjectSettingsService settings,
            Clock clock) {

        this.projects = projects;
        this.people = people;
        this.boards = boards;
        this.columns = columns;
        this.claims = claims;
        this.issues = issues;
        this.types = types;
        this.statuses = statuses;
        this.transitions = transitions;
        this.labels = labels;
        this.assignments = assignments;
        this.releases = releases;
        this.linkedCases = linkedCases;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * The board's filter, and the backlog's — one shape, because §12.2 says one filter serves five
     * views and a second record is how the two start disagreeing about what {@code unparented}
     * means.
     *
     * <p>Every collection is an OR-set and every populated category is ANDed with the others. An
     * empty or null collection means "do not filter on this", never "match nothing".
     */
    public record Filter(
            List<UUID> labelIds,
            List<UUID> assigneeIds,
            String epicKey,
            IssueLevel level,
            List<Priority> priorities,
            boolean unparented,
            UUID releaseId,
            boolean unscheduled) {

        public static Filter none() {
            return new Filter(List.of(), List.of(), null, null, List.of(), false, null, false);
        }
    }

    /**
     * {@code GET /boards/{boardId}/cards}.
     *
     * <p>Gated on project visibility alone, exactly as the board's configuration read is:
     * {@code board:read} is held by every one of the six project roles, so a project grant already
     * implies it and a 403 arm here would be unreachable.
     */
    @Transactional(readOnly = true)
    public Optional<BoardContentsView> contents(UUID userId, UUID boardId, Filter filter) {
        return boards.findScopedById(boardId).flatMap(board ->
                projects.readVisible(userId, board.getProjectId(),
                        project -> assemble(userId, board, project, filter)));
    }

    /** {@code GET /projects/{projectId}/backlog}. */
    @Transactional(readOnly = true)
    public Optional<List<BacklogRowView>> backlog(UUID userId, UUID projectId, Filter filter) {
        return projects.readVisible(userId, projectId,
                project -> backlogRows(factsOf(userId, project), filter));
    }

    // =====================================================================

    private BoardContentsView assemble(
            UUID userId, Board board, Project project, Filter filter) {

        Facts facts = factsOf(userId, project);
        BoardScope scope = scopeOf(board, project.getId());
        Map<UUID, UUID> columnOfStatus = new HashMap<>();
        claims.findByBoardId(board.getId())
                .forEach(claim -> columnOfStatus.put(claim.getStatusId(), claim.getColumnId()));

        List<BoardIssueCardView> cards = new ArrayList<>();
        for (Issue issue : facts.ordered()) {
            UUID statusId = facts.shownStatus(issue).id();
            UUID columnId = columnOfStatus.get(statusId);
            if (columnId == null) {
                // A BOARD CLAIMS A SUBSET (021 R7), so this is now the ORDINARY case rather than an
                // unreachable one: a status this board does not claim is a status this board does
                // not show, and its cards belong to whichever board does. ~~Every status is claimed
                // by exactly one column, so this is unreachable through the configuration
                // endpoint.~~ → superseded. Still a `continue` and never column zero, for the
                // unchanged reason: a card in a column that does not claim its status vanishes the
                // moment somebody drops it.
                continue;
            }
            if (facts.matches(issue, filter)) {
                cards.add(facts.cardOf(issue, columnId, scope));
            }
        }

        return new BoardContentsView(
                board.getId(),
                board.getSwimlaneBy(),
                lanesOf(board.getSwimlaneBy(), cards, facts),
                breaches(board, facts, columnOfStatus));
    }

    /**
     * The columns over their limit, counted over every live issue of the project.
     *
     * <p>Deliberately not over {@code cards}: those are filtered, and a filter is a view. It is also
     * why this counts issues rather than lane entries — a card grouped by label appears in one lane
     * per label it carries, and three lanes is still one card in flight.
     */
    private List<WipBreachView> breaches(Board board, Facts facts, Map<UUID, UUID> columnOfStatus) {
        Map<UUID, Integer> held = new HashMap<>();
        for (Issue issue : facts.ordered()) {
            UUID columnId = columnOfStatus.get(facts.shownStatus(issue).id());
            if (columnId != null) {
                held.merge(columnId, 1, Integer::sum);
            }
        }

        List<WipBreachView> over = new ArrayList<>();
        for (BoardColumn column : columns.findByBoardIdOrderByPositionAsc(board.getId())) {
            Integer limit = column.getWipLimit();
            int count = held.getOrDefault(column.getId(), 0);
            if (limit != null && count > limit) {
                over.add(new WipBreachView(column.getId(), limit, count));
            }
        }
        return over;
    }

    /**
     * One lane per group, with the catch-all last.
     *
     * <p>"Last" is the decision the screen states in words: a story without an epic is a normal
     * story rather than an error, so its lane is a lane and not a warning at the top. A card with
     * two labels is in two {@code LABEL} lanes, which is what a label axis means.
     */
    private List<BoardLaneView> lanesOf(
            SwimlaneBy axis, List<BoardIssueCardView> cards, Facts facts) {

        if (axis == SwimlaneBy.NONE) {
            return List.of(lane(null, "All work", cards, facts));
        }

        Map<String, Group> known = new LinkedHashMap<>();
        Map<String, List<BoardIssueCardView>> grouped = new LinkedHashMap<>();
        List<BoardIssueCardView> catchAll = new ArrayList<>();

        for (BoardIssueCardView card : cards) {
            List<Group> groups = groupsOf(axis, card, facts);
            if (groups.isEmpty()) {
                catchAll.add(card);
                continue;
            }
            for (Group group : groups) {
                known.putIfAbsent(group.key(), group);
                grouped.computeIfAbsent(group.key(), key -> new ArrayList<>()).add(card);
            }
        }

        List<Group> order = new ArrayList<>(known.values());
        order.sort(Comparator.comparingInt(Group::order)
                .thenComparing(Group::label, String.CASE_INSENSITIVE_ORDER));

        List<BoardLaneView> lanes = new ArrayList<>();
        order.forEach(group ->
                lanes.add(lane(group.key(), group.label(), grouped.get(group.key()), facts)));
        if (!catchAll.isEmpty()) {
            lanes.add(lane(null, catchAllLabel(axis), catchAll, facts));
        }
        return lanes;
    }

    /**
     * One lane's identity plus how it sorts against its siblings.
     *
     * <p>{@link #order} exists for the priority axis alone: alphabetical would read Critical, High,
     * Low, Medium, which puts the least urgent lane in the middle of the board. Every other axis
     * sorts by its label and leaves this at zero.
     */
    private record Group(String key, String label, int order) {
    }

    private List<Group> groupsOf(SwimlaneBy axis, BoardIssueCardView card, Facts facts) {
        return switch (axis) {
            case NONE -> List.of();
            case EPIC -> card.epicKey() == null
                    ? List.of()
                    : List.of(new Group(card.epicKey(), facts.titleOfEpic(card.epicKey()), 0));
            case ASSIGNEE -> card.assignee() == null
                    ? List.of()
                    : List.of(new Group(
                            card.assignee().id().toString(), card.assignee().displayName(), 0));
            case LABEL -> card.labels().stream()
                    .map(label -> new Group(label.id().toString(), label.name(), 0))
                    .toList();
            case PRIORITY -> List.of(new Group(
                    card.priority().name(), card.priority().name(), card.priority().ordinal()));
            // 013 M3. The card face does not carry a release — `work-board.html` does not draw one
            // — so the lane is read off the issue rather than off the card, which is why this arm
            // takes `facts` where EPIC and ASSIGNEE take the card. A card with no release falls to
            // the catch-all lane, exactly as one with no epic does.
            case RELEASE -> facts.releaseOf(card.key())
                    .map(release -> List.of(new Group(
                            release.id().toString(), release.version(), 0)))
                    .orElseGet(List::of);
        };
    }

    private static String catchAllLabel(SwimlaneBy axis) {
        return switch (axis) {
            case EPIC -> "No epic";
            case ASSIGNEE -> "Unassigned";
            case LABEL -> "No label";
            case RELEASE -> "Unscheduled";
            case NONE, PRIORITY -> "Other";
        };
    }

    private BoardLaneView lane(
            String key, String label, List<BoardIssueCardView> cards, Facts facts) {

        List<CountedIssue> counted = cards.stream().map(facts::countedOf).toList();
        return new BoardLaneView(key, label, cards, Completion.of(counted).view());
    }

    private List<BacklogRowView> backlogRows(Facts facts, Filter filter) {
        List<BacklogRowView> rows = new ArrayList<>();
        for (Issue issue : facts.ordered()) {
            IssueType type = facts.typeOf(issue);
            if (type.getLevel() == IssueLevel.EPIC) {
                continue;
            }
            if (facts.shownStatus(issue).kind() == IssueStatusKind.DONE) {
                continue;
            }
            if (!facts.matches(issue, filter)) {
                continue;
            }
            rows.add(facts.backlogRowOf(issue, rows.size() + 1));
        }
        return rows;
    }

    private Facts factsOf(UUID userId, Project project) {
        return factsFor(userId, project.getId());
    }

    /**
     * Which statuses <em>this</em> board shows, and which each of the project's other boards shows.
     *
     * <p>Read once per board read rather than per card: three boards is three
     * {@code board_column_status} queries, and asking per card would be one per card per board.
     *
     * <p>Package-private because {@link BoardMoveService} answers a move with a card too, and a
     * card assembled there without this would carry an empty {@code elsewhere} — a row header that
     * loses its "1 on QA board" link the moment somebody drags something on it.
     */
    BoardScope scopeOf(Board board, UUID projectId) {
        Set<UUID> here = new LinkedHashSet<>();
        claims.findByBoardId(board.getId()).forEach(claim -> here.add(claim.getStatusId()));

        List<BoardReach> others = new ArrayList<>();
        for (Board other : boards.findByProjectIdOrderByPositionAscNameAsc(projectId)) {
            if (other.getId().equals(board.getId())) {
                continue;
            }
            Set<UUID> shown = new LinkedHashSet<>();
            claims.findByBoardId(other.getId()).forEach(claim -> shown.add(claim.getStatusId()));
            others.add(new BoardReach(other.getId(), other.getName(), Set.copyOf(shown)));
        }
        return new BoardScope(Set.copyOf(here), List.copyOf(others));
    }

    /**
     * The statuses one board shows, for the boards that are not the one being read.
     *
     * <p>In Work-strip position order, so a card's {@code elsewhere} entries come out in the order
     * the strip draws its segments and the popover behind "3 on other boards" reads left to right.
     */
    record BoardReach(UUID boardId, String boardName, Set<UUID> statusIds) {
    }

    /**
     * What {@code elsewhere} is computed against: this board's claimed statuses, and every other
     * board's.
     *
     * <p>Both halves are needed and neither is derivable from the other, which is 021 §2.5's whole
     * arithmetic point: a status may be claimed by two boards, so <em>shown here</em> and
     * <em>elsewhere</em> overlap and "total minus shown" is not the count. The not-shown-here set
     * is computed from {@link #claimedHere}, then grouped by {@link #others}.
     */
    record BoardScope(Set<UUID> claimedHere, List<BoardReach> others) {
    }

    /**
     * Everything a card face reads, for one project, in a handful of queries.
     *
     * <p>Package-private and keyed on the id rather than the gated {@code Project} because
     * {@link BoardMoveService} calls it after {@code TransitionService} has already gated the same
     * project — and answering a move with a card assembled somewhere else would be a second
     * assembly of this shape, drifting from this one the first time a field was added.
     */
    Facts factsFor(UUID userId, UUID projectId) {
        List<Issue> live = issues.findByProjectIdAndDeletedAtIsNull(projectId);

        Map<UUID, IssueType> typesById = new LinkedHashMap<>();
        types.findByProjectIdOrderByPositionAsc(projectId)
                .forEach(type -> typesById.put(type.getId(), type));
        Map<UUID, IssueStatus> statusesById = new LinkedHashMap<>();
        statuses.findByProjectIdOrderByPositionAsc(projectId)
                .forEach(status -> statusesById.put(status.getId(), status));

        Map<UUID, LabelView> labelsById = new LinkedHashMap<>();
        labels.findByProjectIdOrderByNameAsc(projectId).forEach(label -> labelsById.put(
                label.getId(), new LabelView(label.getId(), label.getName(), label.getColor())));

        Map<UUID, List<LabelView>> labelsByIssue = new HashMap<>();
        if (!live.isEmpty()) {
            for (IssueLabelAssignment assignment
                    : assignments.findByIssueIdIn(live.stream().map(Issue::getId).toList())) {
                LabelView label = labelsById.get(assignment.getLabelId());
                if (label != null) {
                    labelsByIssue.computeIfAbsent(assignment.getIssueId(), id -> new ArrayList<>())
                            .add(label);
                }
            }
        }

        Set<UUID> peopleIds = new HashSet<>();
        live.forEach(issue -> {
            if (issue.getAssigneeId() != null) {
                peopleIds.add(issue.getAssigneeId());
            }
        });
        Map<UUID, UserRef> refs = people.resolve(CompanyContext.require(), peopleIds);

        // Every release in the project, not only the ones in use: it is one small query either way,
        // and the alternative — collecting the distinct ids off `live` first — is a second pass
        // over the issues to save a handful of rows.
        Map<UUID, ReleaseRefView> releasesById = new LinkedHashMap<>();
        releases.findByProjectIdOrderBySortKeyAsc(projectId).forEach(release ->
                releasesById.put(release.getId(), ReleaseRefView.of(release)));

        // 021 D-k, and ONE call per board read rather than one per card: `LinkedCaseFacts` takes
        // every live issue of the project at once, so the summary on a card costs a map lookup and
        // the read behind it is three statements whether the board holds two cards or two hundred.
        LinkedCaseFacts.LinkedCases linked = linkedCases.forIssues(
                projectId, live.stream().map(Issue::getId).toList());

        return new Facts(live, typesById, statusesById, labelsByIssue, refs, releasesById,
                linked, userId, settings.effective(projectId),
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * One project's issues plus everything a card face reads, assembled once.
     *
     * <p>It exists so the board, the backlog and a single card after a move all read the same
     * derived values — an epic's status, a story's epic, whether a due date is overdue. Three
     * assemblies of a card face is three places for the same field to drift.
     */
    final class Facts {

        private final List<Issue> live;
        private final Map<UUID, Issue> byId = new LinkedHashMap<>();
        private final Map<UUID, IssueType> typesById;
        private final Map<UUID, IssueStatus> statusesById;
        private final Map<UUID, List<LabelView>> labelsByIssue;
        private final Map<UUID, UserRef> refs;
        private final Map<UUID, ReleaseRefView> releasesById;
        private final Map<UUID, List<Issue>> childrenByParent = new HashMap<>();
        private final LinkedCaseFacts.LinkedCases linked;
        /** Memoised {@link #caseIdsUnder}, because an epic's subtree is walked once per card in it. */
        private final Map<UUID, Set<UUID>> caseIdsBySubtree = new HashMap<>();
        private final UUID userId;
        private final ReviewSettings policy;
        private final LocalDate today;

        private Facts(
                List<Issue> live,
                Map<UUID, IssueType> typesById,
                Map<UUID, IssueStatus> statusesById,
                Map<UUID, List<LabelView>> labelsByIssue,
                Map<UUID, UserRef> refs,
                Map<UUID, ReleaseRefView> releasesById,
                LinkedCaseFacts.LinkedCases linked,
                UUID userId,
                ReviewSettings policy,
                LocalDate today) {

            this.live = live;
            this.typesById = typesById;
            this.statusesById = statusesById;
            this.labelsByIssue = labelsByIssue;
            this.refs = refs;
            this.releasesById = releasesById;
            this.linked = linked;
            this.userId = userId;
            this.policy = policy;
            this.today = today;
            live.forEach(issue -> byId.put(issue.getId(), issue));
            live.forEach(issue -> {
                if (issue.getParentId() != null) {
                    childrenByParent
                            .computeIfAbsent(issue.getParentId(), id -> new ArrayList<>())
                            .add(issue);
                }
            });
        }

        /**
         * Every live issue in the project's rank order.
         *
         * <p>{@code rank, created_at, id} and never {@code rank} alone: the column is deliberately
         * not unique, so two issues created in one project in the same instant tie and
         * {@code created_at} is what breaks it.
         */
        List<Issue> ordered() {
            return live.stream()
                    .sorted(Comparator.comparing(Issue::getRank)
                            .thenComparing(Issue::getCreatedAt)
                            .thenComparing(Issue::getId))
                    .toList();
        }

        IssueType typeOf(Issue issue) {
            IssueType type = typesById.get(issue.getTypeId());
            if (type == null) {
                throw new IllegalStateException("issue " + issue.getIssueKey()
                        + " points at a type that is not in its project");
            }
            return type;
        }

        /**
         * The status a screen shows — <strong>the stored one, at every level</strong>.
         *
         * <p>~~Derived for an Epic (D31): its stored {@code status_id} is not editable and means
         * nothing.~~ → superseded (021 R10 / D-d). It means something now: an epic has a lifecycle
         * of its own and the PO moves it along the same workflow, so there is one rule here rather
         * than two and a board and a detail page cannot spell an epic's status differently.
         */
        IssueStatusView shownStatus(Issue issue) {
            IssueStatus stored = statusesById.get(issue.getStatusId());
            if (stored == null) {
                throw new IllegalStateException("issue " + issue.getIssueKey()
                        + " points at a status that is not in its project");
            }
            return viewOf(stored);
        }

        BoardIssueCardView cardOf(Issue issue, UUID columnId, BoardScope scope) {
            IssueType type = typeOf(issue);
            IssueStatusView status = shownStatus(issue);
            List<Issue> children = childrenOf(issue);
            Issue epic = epicOf(issue);
            Issue parent = issue.getParentId() == null ? null : byId.get(issue.getParentId());
            return new BoardIssueCardView(
                    issue.getIssueKey(),
                    issue.getTitle(),
                    type.getName(),
                    type.getLevel(),
                    type.getIconCode(),
                    type.isCountsTowardCompletion(),
                    columnId,
                    status,
                    issue.getRank().toPlainString(),
                    issue.getPriority(),
                    refs.get(issue.getAssigneeId()),
                    issue.getDueDate(),
                    overdue(issue, status),
                    issue.getEstimateHours(),
                    epic == null ? null : epic.getIssueKey(),
                    labelsOf(issue),
                    allowedTransitions(issue),
                    children.size(),
                    (int) children.stream().filter(this::isDone).count(),
                    issue.isAgentEligible(),
                    parent == null ? null : parent.getIssueKey(),
                    elsewhereOf(children, scope),
                    issue.hasAcceptanceCriteria(),
                    blockedFromStatusOf(issue),
                    allStoriesDone(issue, type, status),
                    linkedCasesOf(issue, type));
        }

        /**
         * The linked-case roll-up on a card (021 D-k), or null when nothing under it is linked.
         *
         * <h2>The set is the issue's subtree, and a TASK borrows its parent's</h2>
         *
         * <p>A story's cases are its own plus its tasks'; an <strong>epic's</strong> are the union
         * over everything beneath it, which is what "an epic rolls up its stories" means once the
         * stories have tasks of their own. A <strong>task</strong> has no subtree, so it shows its
         * parent story's — {@code docs/mockups/ia-redesign/13-qa-board.html} draws the summary on
         * task cards and linking is done on the story (H7), so a task computing only its own would
         * show the QA board, the one board made of tasks, nothing at all.
         *
         * <p><strong>A union of case ids, never a sum of counts.</strong> One case may verify two
         * stories of the same epic, and adding the two summaries would report an epic as having four
         * cases when it has three — a total a reader cannot reconcile against the stories it came
         * from.
         *
         * <h2>Only three kinds are a verdict; everything else is notRun</h2>
         *
         * <p>{@code documentation.BlockCoverageView}'s rule, over the same {@code case_health} rows:
         * {@code SKIPPED}, {@code RETEST}, {@code UNTESTED} and {@code WIP} land in {@code notRun}
         * beside a case that has never been executed at all, because §46 says a roll-up that could
         * go either way must not go the way that makes the report look better than the truth. So
         * {@code passing + failing + blocked + notRun == total}, always — a summary whose parts do
         * not sum is one nobody can subtract with.
         *
         * <p>Null rather than a zeroed summary for a card with no linked cases: the contract's key
         * is always present, and a card reading "0 of 0 passing" claims coverage was measured where
         * none was ever asked for.
         */
        private LinkedCasesSummaryView linkedCasesOf(Issue issue, IssueType type) {
            Issue parent = issue.getParentId() == null ? null : byId.get(issue.getParentId());
            Set<UUID> caseIds = type.getLevel() == IssueLevel.TASK && parent != null
                    ? caseIdsUnder(parent)
                    : caseIdsUnder(issue);
            if (caseIds.isEmpty()) {
                return null;
            }
            int passing = 0;
            int failing = 0;
            int blocked = 0;
            int notRun = 0;
            for (UUID caseId : caseIds) {
                StatusKind kind = linked.lastKindByCase().get(caseId);
                if (kind == StatusKind.PASSED) {
                    passing++;
                } else if (kind == StatusKind.FAILED) {
                    failing++;
                } else if (kind == StatusKind.BLOCKED) {
                    blocked++;
                } else {
                    // Absent, null, SKIPPED, RETEST, UNTESTED, WIP — see the header.
                    notRun++;
                }
            }
            return new LinkedCasesSummaryView(caseIds.size(), passing, failing, blocked, notRun);
        }

        /**
         * The distinct cases linked to this issue or to anything beneath it.
         *
         * <p>Recursive rather than a loop with a stack because the depth rule bounds the tree at
         * three levels, so the recursion cannot be deeper than {@code epicOf}'s two steps already
         * assume — and memoised, because a lane draws an epic card and every card under it and the
         * epic's subtree would otherwise be walked once per card in it.
         */
        private Set<UUID> caseIdsUnder(Issue issue) {
            Set<UUID> memo = caseIdsBySubtree.get(issue.getId());
            if (memo != null) {
                return memo;
            }
            Set<UUID> ids = new LinkedHashSet<>(
                    linked.casesByIssue().getOrDefault(issue.getId(), List.of()));
            childrenOf(issue).forEach(child -> ids.addAll(caseIdsUnder(child)));
            Set<UUID> fixed = Set.copyOf(ids);
            caseIdsBySubtree.put(issue.getId(), fixed);
            return fixed;
        }

        /**
         * The status this issue was in when it was blocked, or null (021 D-c).
         *
         * <p>Read only while the issue is IN the blocking status. The column is cleared on the way
         * out so a stale value is not a state the data can be in, but reading it unconditionally
         * would make the card depend on that being true forever — and a card reading
         * "Blocked · from In test" while sitting in In progress is worse than one saying nothing.
         */
        private IssueStatusView blockedFromStatusOf(Issue issue) {
            IssueStatus origin = blockedOriginOf(issue);
            return origin == null ? null : IssueStatusView.of(origin);
        }

        /**
         * The same origin {@link #blockedFromStatusOf} shows, as the row rather than the view.
         *
         * <p>One method feeding both the chip and {@code allowedTransitions}, deliberately: the
         * card reads "Blocked · from In test" and offers "In test", and two spellings of the
         * lookup is how a card comes to name one status and offer another.
         */
        private IssueStatus blockedOriginOf(Issue issue) {
            IssueStatus current = statusesById.get(issue.getStatusId());
            if (current == null || !current.isBlocking() || issue.getBlockedFromStatusId() == null) {
                return null;
            }
            return statusesById.get(issue.getBlockedFromStatusId());
        }

        /**
         * Whether an epic's counted stories are all done while the epic itself is not (021 D-d).
         *
         * <p><strong>A nudge, never a transition.</strong> The card says so and the PO drags it;
         * the server does not move an epic on its own, because "all the stories are done" and "this
         * body of work is finished" are different claims and only a person can make the second.
         *
         * <p>False for anything that is not an epic, and false for an epic with no counted stories
         * at all — a vacuous truth would put the nudge on every empty epic in the project.
         *
         * <p>The {@code status} it reads is the epic's own STORED one since 021 R10, and that is
         * what makes the expression mean what §11 wants rather than what it used to. It used to
         * compare the derived kind against the stories the kind was derived FROM, so "all done and
         * not itself done" was arithmetically unreachable and the flag could only ever be false;
         * now the two sides are independent — the stories say the work is finished, the epic says
         * the PO has not shipped it — which is precisely the gap the nudge exists to point at.
         */
        private boolean allStoriesDone(Issue issue, IssueType type, IssueStatusView status) {
            if (type.getLevel() != IssueLevel.EPIC || status.kind() == IssueStatusKind.DONE) {
                return false;
            }
            return EpicStatus.allStoriesDone(countedChildrenOf(issue));
        }

        /**
         * The children this board does not show, grouped by the boards that do (021 §2.5).
         *
         * <p><strong>Counted as the not-shown-here set, and never as total minus shown.</strong> A
         * status may be claimed by two boards — that is exactly what a shared hand-off column is —
         * so "shown here" and "elsewhere" overlap, and subtracting one total from the other
         * reports a task that is on both boards as being somewhere else. A child in a status no
         * board claims contributes to no entry here at all, which is honest: it is on no board, and
         * {@code getBoardCoverage} is the read that says so.
         *
         * <p>Direct children rather than every descendant, because the row header this feeds is a
         * story's and the tree is three levels deep: a story's children are its tasks, which are
         * the only things that can be cards under it.
         */
        private List<CardElsewhereView> elsewhereOf(List<Issue> children, BoardScope scope) {
            if (children.isEmpty()) {
                return List.of();
            }
            List<Issue> hidden = children.stream()
                    .filter(child -> !scope.claimedHere().contains(shownStatus(child).id()))
                    .toList();
            if (hidden.isEmpty()) {
                return List.of();
            }
            List<CardElsewhereView> reached = new ArrayList<>();
            for (BoardReach other : scope.others()) {
                int count = (int) hidden.stream()
                        .filter(child -> other.statusIds().contains(shownStatus(child).id()))
                        .count();
                if (count > 0) {
                    reached.add(new CardElsewhereView(
                            other.boardId(), other.boardName(), count));
                }
            }
            return List.copyOf(reached);
        }

        BacklogRowView backlogRowOf(Issue issue, int position) {
            IssueType type = typeOf(issue);
            Issue epic = epicOf(issue);
            return new BacklogRowView(
                    issue.getIssueKey(),
                    issue.getTitle(),
                    type.getName(),
                    type.getLevel(),
                    type.getIconCode(),
                    position,
                    issue.getRank().toPlainString(),
                    shownStatus(issue),
                    refs.get(issue.getAssigneeId()),
                    issue.getEstimateHours(),
                    epic == null ? null : epic.getIssueKey(),
                    releaseRefOf(issue),
                    labelsOf(issue));
        }

        CountedIssue countedOf(BoardIssueCardView card) {
            return new CountedIssue(
                    card.countsTowardCompletion(), card.status().kind(), card.estimateHours());
        }

        String titleOfEpic(String epicKey) {
            return live.stream()
                    .filter(issue -> issue.getIssueKey().equals(epicKey))
                    .map(Issue::getTitle)
                    .findFirst()
                    .orElse(epicKey);
        }

        /**
         * Whether one issue survives the filter.
         *
         * <p>OR within a category, AND across categories (§12.2). An empty category is skipped
         * rather than treated as an empty allow-list, which would answer "nothing matches" to a
         * request that asked for no narrowing at all.
         */
        boolean matches(Issue issue, Filter filter) {
            if (filter == null) {
                return true;
            }
            if (filter.level() != null && typeOf(issue).getLevel() != filter.level()) {
                return false;
            }
            if (!isEmpty(filter.assigneeIds())
                    && !filter.assigneeIds().contains(issue.getAssigneeId())) {
                return false;
            }
            if (filter.priorities() != null && !filter.priorities().isEmpty()
                    && !filter.priorities().contains(issue.getPriority())) {
                return false;
            }
            if (!isEmpty(filter.labelIds())) {
                Set<UUID> held = labelsOf(issue).stream()
                        .map(LabelView::id)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
                if (filter.labelIds().stream().noneMatch(held::contains)) {
                    return false;
                }
            }
            // Two members rather than one nullable id, for `unparented`'s reason one field up:
            // an absent parameter already means "do not filter", so a client cannot spell "no
            // release" with the id alone. The controller refuses both together.
            if (filter.releaseId() != null && !filter.releaseId().equals(issue.getReleaseId())) {
                return false;
            }
            if (filter.unscheduled() && issue.getReleaseId() != null) {
                return false;
            }
            Issue epic = epicOf(issue);
            if (filter.unparented() && epic != null) {
                return false;
            }
            return filter.epicKey() == null
                    || (epic != null && epic.getIssueKey().equals(filter.epicKey()));
        }

        private List<LabelView> labelsOf(Issue issue) {
            return labelsByIssue.getOrDefault(issue.getId(), List.of());
        }

        private ReleaseRefView releaseRefOf(Issue issue) {
            return issue.getReleaseId() == null ? null : releasesById.get(issue.getReleaseId());
        }

        /**
         * The release behind a card, by the card's key.
         *
         * <p>By key and not by id because {@code BoardIssueCardView} carries neither a release nor
         * an issue id — the card face draws no release chip ({@code work-board.html} does not have
         * one) and 012 deliberately kept a synthesised id off it. The lane axis needs the value the
         * face does not show, so it reads it back off the issue.
         */
        Optional<ReleaseRefView> releaseOf(String key) {
            return live.stream()
                    .filter(issue -> issue.getIssueKey().equals(key))
                    .findFirst()
                    .map(this::releaseRefOf)
                    .map(Optional::of)
                    .orElseGet(Optional::empty);
        }

        private List<Issue> childrenOf(Issue issue) {
            return childrenByParent.getOrDefault(issue.getId(), List.of());
        }

        private List<CountedIssue> countedChildrenOf(Issue issue) {
            return childrenOf(issue).stream()
                    .map(child -> new CountedIssue(
                            typeOf(child).isCountsTowardCompletion(),
                            statusesById.get(child.getStatusId()).getKind(),
                            child.getEstimateHours()))
                    .toList();
        }

        private boolean isDone(Issue issue) {
            IssueStatus status = statusesById.get(issue.getStatusId());
            return status != null && status.getKind() == IssueStatusKind.DONE;
        }

        /**
         * The epic a row belongs to, whatever level the row is.
         *
         * <p>A story's epic is its parent; a task's is its parent's parent. Bounded at two steps
         * because the depth rule bounds the tree at three levels, so a loop here would be a loop
         * over something that cannot be deeper.
         */
        private Issue epicOf(Issue issue) {
            Issue parent = issue.getParentId() == null ? null : byId.get(issue.getParentId());
            if (parent == null) {
                return null;
            }
            if (typeOf(parent).getLevel() == IssueLevel.EPIC) {
                return parent;
            }
            Issue grandparent =
                    parent.getParentId() == null ? null : byId.get(parent.getParentId());
            return grandparent != null && typeOf(grandparent).getLevel() == IssueLevel.EPIC
                    ? grandparent
                    : null;
        }

        /**
         * The statuses this issue may legally move to — the same answer
         * {@code GET /issues/&#123;key&#125;} gives, and ~~empty for an Epic (D31)~~ → superseded
         * (021 R10): an epic's status is stored and it moves like a story, so {@code type} is no
         * longer read here.
         *
         * <p>§12.4: the legal set must be known client-side before the drag starts, so a forbidden
         * drop is refused at the drag with the reason rather than accepted and reverted. That is
         * also why the remembered origin is added below — {@code TransitionService} accepts it
         * with no {@code issue_transition} row behind it (021 D-c), and a legal target this list
         * withheld would be §12.4 the wrong way round: the client refusing at the drag a move the
         * server allows.
         */
        private List<IssueStatusView> allowedTransitions(Issue issue) {
            boolean withholdDone = IssueService.closeGuardBinds(policy, issue, userId);
            Map<UUID, IssueStatus> offered = new LinkedHashMap<>();
            transitions.findByFromStatusId(issue.getStatusId()).stream()
                    .map(move -> statusesById.get(move.getToStatusId()))
                    .filter(Objects::nonNull)
                    // D33, subtracted from the workflow set rather than replacing it — the same
                    // narrowing GET /issues/{key} applies, so a card and its detail page cannot
                    // offer different moves to one caller.
                    .filter(status -> !withholdDone || status.getKind() != IssueStatusKind.DONE)
                    .forEach(status -> offered.put(status.getId(), status));

            IssueStatus origin = blockedOriginOf(issue);
            if (origin != null && (!withholdDone || origin.getKind() != IssueStatusKind.DONE)) {
                offered.put(origin.getId(), origin);
            }

            return offered.values().stream()
                    .sorted(Comparator.comparingInt(IssueStatus::getPosition))
                    .map(Facts::viewOf)
                    .toList();
        }

        /**
         * Overdue is computed here and never by the client, for {@code IssueService}'s reason: a
         * client deriving it from its own clock disagrees with the server the moment the two are in
         * different time zones. A done issue is never overdue.
         */
        private boolean overdue(Issue issue, IssueStatusView status) {
            return issue.getDueDate() != null
                    && status.kind() != IssueStatusKind.DONE
                    && issue.getDueDate().isBefore(today);
        }

        private static IssueStatusView viewOf(IssueStatus status) {
            return IssueStatusView.of(status);
        }

        private static boolean isEmpty(List<UUID> ids) {
            return ids == null || ids.isEmpty();
        }
    }
}
