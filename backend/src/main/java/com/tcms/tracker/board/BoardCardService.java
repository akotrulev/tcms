package com.tcms.tracker.board;

import com.tcms.api.UserRef;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.Completion;
import com.tcms.tracker.CountedIssue;
import com.tcms.tracker.EpicStatus;
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
 * <p>On the board an epic is a card whose {@code status} is the <strong>derived</strong> one (D31)
 * and whose {@code allowedTransitions} is empty — the contract says so, and the alternative is a
 * board that quietly does not show a kind of work. In the <em>backlog</em> it is neither: D31
 * derives an epic's status from its stories, so it has nothing to be ranked against, and a list
 * carrying both an epic and the stories under it double-counts the same work in the one view whose
 * whole job is "what is next".
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
            boolean unparented) {

        public static Filter none() {
            return new Filter(List.of(), List.of(), null, null, List.of(), false);
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
                        project -> assemble(board, project, filter)));
    }

    /** {@code GET /projects/{projectId}/backlog}. */
    @Transactional(readOnly = true)
    public Optional<List<BacklogRowView>> backlog(UUID userId, UUID projectId, Filter filter) {
        return projects.readVisible(userId, projectId,
                project -> backlogRows(factsOf(project), filter));
    }

    // =====================================================================

    private BoardContentsView assemble(Board board, Project project, Filter filter) {
        Facts facts = factsOf(project);
        Map<UUID, UUID> columnOfStatus = new HashMap<>();
        claims.findByBoardId(board.getId())
                .forEach(claim -> columnOfStatus.put(claim.getStatusId(), claim.getColumnId()));

        List<BoardIssueCardView> cards = new ArrayList<>();
        for (Issue issue : facts.ordered()) {
            UUID statusId = facts.shownStatus(issue).id();
            UUID columnId = columnOfStatus.get(statusId);
            if (columnId == null) {
                // Every status is claimed by exactly one column, so this is unreachable through the
                // configuration endpoint. Skipped rather than assumed into column zero: a card in a
                // column that does not claim its status vanishes the moment somebody drops it.
                continue;
            }
            if (facts.matches(issue, filter)) {
                cards.add(facts.cardOf(issue, columnId));
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
        };
    }

    private static String catchAllLabel(SwimlaneBy axis) {
        return switch (axis) {
            case EPIC -> "No epic";
            case ASSIGNEE -> "Unassigned";
            case LABEL -> "No label";
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

    private Facts factsOf(Project project) {
        return factsFor(project.getId());
    }

    /**
     * Everything a card face reads, for one project, in a handful of queries.
     *
     * <p>Package-private and keyed on the id rather than the gated {@code Project} because
     * {@link BoardMoveService} calls it after {@code TransitionService} has already gated the same
     * project — and answering a move with a card assembled somewhere else would be a second
     * assembly of this shape, drifting from this one the first time a field was added.
     */
    Facts factsFor(UUID projectId) {
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

        return new Facts(live, typesById, statusesById, labelsByIssue, refs,
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
        private final Map<UUID, List<Issue>> childrenByParent = new HashMap<>();
        private final LocalDate today;

        private Facts(
                List<Issue> live,
                Map<UUID, IssueType> typesById,
                Map<UUID, IssueStatus> statusesById,
                Map<UUID, List<LabelView>> labelsByIssue,
                Map<UUID, UserRef> refs,
                LocalDate today) {

            this.live = live;
            this.typesById = typesById;
            this.statusesById = statusesById;
            this.labelsByIssue = labelsByIssue;
            this.refs = refs;
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
         * The status a screen shows — <strong>derived for an Epic</strong> (D31), stored for
         * everything else.
         *
         * <p>An epic's stored {@code status_id} is not editable and means nothing; the derived kind
         * is spelled with the project's own first status at that kind, because a hardcoded "Done"
         * would be the word-lookup story B1 exists to remove.
         */
        IssueStatusView shownStatus(Issue issue) {
            IssueStatus stored = statusesById.get(issue.getStatusId());
            if (stored == null) {
                throw new IllegalStateException("issue " + issue.getIssueKey()
                        + " points at a status that is not in its project");
            }
            if (typeOf(issue).getLevel() != IssueLevel.EPIC) {
                return viewOf(stored);
            }
            IssueStatusKind derived = EpicStatus.of(countedChildrenOf(issue));
            return statusesById.values().stream()
                    .filter(status -> status.getKind() == derived)
                    .min(Comparator.comparingInt(IssueStatus::getPosition))
                    .map(Facts::viewOf)
                    .orElseGet(() -> viewOf(stored));
        }

        BoardIssueCardView cardOf(Issue issue, UUID columnId) {
            IssueType type = typeOf(issue);
            IssueStatusView status = shownStatus(issue);
            List<Issue> children = childrenOf(issue);
            Issue epic = epicOf(issue);
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
                    allowedTransitions(issue, type),
                    children.size(),
                    (int) children.stream().filter(this::isDone).count());
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
         * The statuses this issue may legally move to — <strong>empty for an Epic</strong>, which is
         * D31 rather than an omission, and the same answer {@code GET /issues/&#123;key&#125;} gives.
         *
         * <p>§12.4: the legal set must be known client-side before the drag starts, so a forbidden
         * drop is refused at the drag with the reason rather than accepted and reverted.
         */
        private List<IssueStatusView> allowedTransitions(Issue issue, IssueType type) {
            if (type.getLevel() == IssueLevel.EPIC) {
                return List.of();
            }
            return transitions.findByFromStatusId(issue.getStatusId()).stream()
                    .map(move -> statusesById.get(move.getToStatusId()))
                    .filter(Objects::nonNull)
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
            return new IssueStatusView(
                    status.getId(), status.getName(), status.getKind(), status.getPosition());
        }

        private static boolean isEmpty(List<UUID> ids) {
            return ids == null || ids.isEmpty();
        }
    }
}
