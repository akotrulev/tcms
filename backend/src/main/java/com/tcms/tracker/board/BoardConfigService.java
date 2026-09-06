package com.tcms.tracker.board;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.IssueStatus;
import com.tcms.tracker.IssueStatusKind;
import com.tcms.tracker.IssueStatusRepository;
import com.tcms.tracker.IssueStatusView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's boards — their columns, their WIP limits and their swimlane axis (story B2).
 *
 * <h2>A board is a lens, and that is what makes deleting one safe</h2>
 *
 * <p>Nothing points at a board from {@code issue}: an issue appears on every board of its project,
 * in the column claiming its status. So a board owns no work, a board {@code PATCH} that drops a
 * column moves nothing, and {@code DELETE} loses nothing. The only refusal is the project's
 * <em>last</em> board, because a project with none cannot render {@code ?view=board} at all and an
 * empty board picker is a dead end rather than a state the SPA can explain.
 *
 * <h2>A board claims a SUBSET, and half of B2's rule went with that (021 R7, D-e)</h2>
 *
 * <p>{@code uq_board_column_status (company_id, board_id, status_id)} rejects a status claimed
 * twice <em>on one board</em>, and that half stays: it is what makes a card's column unambiguous
 * and a drop resolvable. ~~<strong>"Claimed at least once" is a statement about the absence of a
 * row</strong> — a cross-table assertion Postgres has no CHECK for — so it lives in
 * {@link #validateClaims} instead.~~ → <strong>superseded</strong>: that half is the rule that
 * forced every board to show the whole lifecycle, which is why one Team board was the only board
 * anybody made. A status a board does not claim is now simply a status <em>that board does not
 * show</em>.
 *
 * <p>What the removed half protected — no card invisible on every board — is a read now rather
 * than a refusal: {@link BoardCoverageService} reports the status × board matrix and names the
 * statuses no board claims. The seeded PM · Dev · QA boards cover all eleven between them, so the
 * warning is empty on day one; it exists for the day somebody deletes a column.
 *
 * <h2>The repair when a status LEAVES is not here, deliberately</h2>
 *
 * <p>{@code V5}'s {@code board_follows_status_removal} trigger repoints a column at the earliest
 * status it still claims and deletes the column only when the departing status was the last thing
 * it claimed. A second repair in Java is how the two would come to disagree. What is here is
 * {@link #claimNewStatus}, for a status <em>arriving</em>, which has no database event to hang off.
 */
@Service
public class BoardConfigService {

    /**
     * Configuration is {@code board:configure} and there is no {@code board:move} (§A3 line 260) —
     * a second key for an act an existing key already covers is a permission somebody can hold
     * without being able to use it. Labels are this key too, for the same reason (D-e).
     */
    private static final String CONFIGURE = "board:configure";

    private final ProjectService projects;
    private final IssueStatusRepository statuses;
    private final BoardRepository boards;
    private final BoardColumnRepository columns;
    private final BoardColumnStatusRepository claims;

    public BoardConfigService(
            ProjectService projects,
            IssueStatusRepository statuses,
            BoardRepository boards,
            BoardColumnRepository columns,
            BoardColumnStatusRepository claims) {

        this.projects = projects;
        this.statuses = statuses;
        this.boards = boards;
        this.columns = columns;
        this.claims = claims;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /**
         * The submitted board cannot be stored, and {@code detail} says which status is the problem.
         *
         * <p>422 rather than 400 for all of it, including a blank name: the contract declares no
         * 400 on these operations, and two spellings of "the body is wrong" on one endpoint is a
         * client that handles one of them.
         */
        record Unprocessable(String title, String detail) implements Outcome {
        }

        record Written(BoardView view) implements Outcome {
        }

        record Deleted() implements Outcome {
        }
    }

    public Optional<List<BoardSummaryView>> list(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, this::summariesOf);
    }

    public Optional<BoardView> board(UUID userId, UUID boardId) {
        return boards.findScopedById(boardId).flatMap(board ->
                projects.readVisible(userId, board.getProjectId(), project -> viewOf(board)));
    }

    public GateResult<Outcome> create(UUID userId, UUID projectId, BoardInput input) {
        return projects.writeGranted(userId, projectId, CONFIGURE,
                project -> applyCreate(project, input));
    }

    public GateResult<Outcome> update(UUID userId, UUID boardId, BoardInput input) {
        return onBoard(userId, boardId,
                (project, board) -> applyUpdate(project.getId(), board, input));
    }

    public GateResult<Outcome> delete(UUID userId, UUID boardId) {
        return onBoard(userId, boardId, (project, board) -> applyDelete(project, board));
    }

    /**
     * A status created after a board exists (009 review R13, rewritten by 021 §2.2).
     *
     * <p><strong>The new status goes where its neighbour already is.</strong> ~~It is claimed on
     * every board of the project, by the column holding the default status of the same
     * {@link IssueStatusKind}.~~ → superseded, because a board claims a subset now and "beside the
     * first column of the same kind" would put a new {@code IN_PROGRESS} status on the PM board,
     * which shows no working column at all. The rule instead: a status inserted after
     * {@code insertedAfter} is claimed by <strong>every board that claims {@code insertedAfter}</strong>,
     * in that board's column for it, until somebody moves it. A board that does not show the
     * neighbour does not show the newcomer either, which is what a subset board means.
     *
     * <p><strong>{@code insertedAfter} null is the workflow editor's "side status"</strong> — the
     * way {@code Rework} and {@code Blocked} are made, a status off the line naming no predecessor.
     * It has no neighbour's column to join, so it lands on <em>every</em> board as a new last
     * column of its own. Last and not first for §12.2's reason: left to right is direction of
     * travel, and a status filed at the start of a board it does not belong to is worse than one
     * filed late.
     *
     * <p><strong>Called directly, not through an event</strong> (D-g). {@code WorkflowService} and
     * this class are in the same Spring Modulith module — {@code board} is a sub-package of
     * {@code tracker}, not a module of its own — so this runs inside the caller's transaction. An
     * {@code OutboxEvents} hop has a one-minute delivery floor, and for that minute a status added
     * to a workflow would be on no board of a project whose boards all show its neighbour.
     *
     * @param insertedAfter the status the new one was placed after, or {@code null} when it was
     *                      placed first or off the line
     */
    @Transactional
    public void claimNewStatus(UUID projectId, IssueStatus created, IssueStatus insertedAfter) {
        UUID companyId = CompanyContext.require();

        for (Board board : boards.findByProjectIdOrderByPositionAscNameAsc(projectId)) {
            List<BoardColumn> ordered = columns.findByBoardIdOrderByPositionAsc(board.getId());
            BoardColumn target = insertedAfter == null
                    ? null
                    : columnClaiming(board, ordered, insertedAfter.getId());

            if (target == null && insertedAfter != null) {
                // This board does not show the neighbour, so it does not show the newcomer. Not an
                // omission and not a gap to fill: it is the subset the board's owner configured,
                // and getBoardCoverage is where a status no board at all claims becomes visible.
                continue;
            }
            if (target == null) {
                target = columns.save(BoardColumn.create(
                        UUID.randomUUID(), companyId, board.getId(), created.getName(),
                        ordered.isEmpty() ? 0 : ordered.get(ordered.size() - 1).getPosition() + 1,
                        null, created.getId()));
                // board_column_status.column_id is a foreign key and Hibernate orders its inserts
                // by entity type rather than by the order they were made in, so the column is on
                // disk before the claim below names it.
                columns.flush();
            }
            claims.save(BoardColumnStatus.claim(
                    companyId, board.getId(), target.getId(), created.getId()));
        }
    }

    // =====================================================================

    /**
     * Resolves the board, then gates on the project that owns it.
     *
     * <p>The resolve is first and an unresolvable id answers {@link GateResult.Unreachable}, for
     * {@code ProjectService.writeGrantedOnTarget}'s reason: the project to gate against is a
     * property of the stored row, so a caller whose board does not exist cannot be told 403 without
     * the server inventing which project they failed on.
     */
    private GateResult<Outcome> onBoard(
            UUID userId, UUID boardId, java.util.function.BiFunction<Project, Board, Outcome> write) {

        Optional<Board> resolved = boards.findScopedById(boardId);
        if (resolved.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, resolved.get().getProjectId(), CONFIGURE,
                project -> boards.findScopedById(boardId)
                        .map(board -> write.apply(project, board))
                        .orElseThrow());
    }

    /**
     * A created board goes to the <em>right</em> of every board the project has.
     *
     * <p>{@code max + 1} rather than a count, because {@code board."position"} is not unique and a
     * count would put a fourth board on top of a third that had been renumbered. Nothing reorders
     * boards yet (021 D-f), so the strip's order is creation order after the three seeded ones.
     */
    private Outcome applyCreate(Project project, BoardInput input) {
        Optional<Outcome> refused = validate(project.getId(), input);
        if (refused.isPresent()) {
            return refused.get();
        }
        Integer last = boards.maxPositionOf(project.getId());
        return store(Board.create(
                UUID.randomUUID(), CompanyContext.require(), project.getId(),
                nameOf(input), swimlaneOf(input), last == null ? 0 : last + 1), input);
    }

    private Outcome applyUpdate(UUID projectId, Board board, BoardInput input) {
        Optional<Outcome> refused = validate(projectId, input);
        if (refused.isPresent()) {
            return refused.get();
        }
        board.redefine(nameOf(input), swimlaneOf(input));
        return store(board, input);
    }

    private Outcome store(Board board, BoardInput input) {
        Board stored = boards.save(board);
        rebuildColumns(stored, input);
        return new Outcome.Written(viewOf(stored));
    }

    private Outcome applyDelete(Project project, Board board) {
        if (boards.countByProjectId(project.getId()) <= 1) {
            return new Outcome.Unprocessable(
                    "A project keeps at least one board",
                    "This is the only board over " + project.getName() + ". A project with no "
                            + "board has no board view at all, and no issue is on this one — a "
                            + "board is a view of the project's work and owns none of it.");
        }
        boards.delete(board);
        return new Outcome.Deleted();
    }

    /**
     * Writes the submitted columns over the board's existing ones.
     *
     * <p><strong>Every kept column is parked at a negative position first, and that is not
     * decoration.</strong> {@code uq_board_column_position (company_id, board_id, position)} is not
     * deferrable and Hibernate flushes updates in an order of its own choosing, so reordering three
     * columns in place collides with the index on whichever update goes first — a legal reorder
     * refused by a constraint about the state it passes through. The parking pass moves every kept
     * column out of the range the final pass writes into.
     */
    private void rebuildColumns(Board board, BoardInput input) {
        claims.deleteAll(claims.findByBoardId(board.getId()));
        claims.flush();

        Map<UUID, BoardColumn> stored = new LinkedHashMap<>();
        columns.findByBoardIdOrderByPositionAsc(board.getId())
                .forEach(column -> stored.put(column.getId(), column));

        Set<UUID> kept = new LinkedHashSet<>();
        input.columns().forEach(column -> {
            if (column.id() != null && stored.containsKey(column.id())) {
                kept.add(column.id());
            }
        });
        stored.forEach((id, column) -> {
            if (!kept.contains(id)) {
                columns.delete(column);
            }
        });
        columns.flush();

        int parked = -1;
        for (UUID id : kept) {
            stored.get(id).park(parked--);
        }
        columns.flush();

        UUID companyId = CompanyContext.require();
        List<BoardColumn> written = new ArrayList<>();
        int position = 0;
        for (BoardColumnInput submitted : input.columns()) {
            BoardColumn column = submitted.id() == null ? null : stored.get(submitted.id());
            if (column == null || !kept.contains(submitted.id())) {
                written.add(columns.save(BoardColumn.create(
                        UUID.randomUUID(), companyId, board.getId(), submitted.name().strip(),
                        position, submitted.wipLimit(), submitted.defaultStatusId())));
            } else {
                column.redefine(submitted.name().strip(), position,
                        submitted.wipLimit(), submitted.defaultStatusId());
                written.add(columns.save(column));
            }
            position++;
        }
        // The columns are on disk before any claim names one: board_column_status.column_id is a
        // foreign key, and Hibernate orders its inserts by entity type rather than by the order
        // they were made in.
        columns.flush();

        for (int index = 0; index < input.columns().size(); index++) {
            UUID columnId = written.get(index).getId();
            for (UUID statusId : input.columns().get(index).statusIds()) {
                claims.save(BoardColumnStatus.claim(
                        companyId, board.getId(), columnId, statusId));
            }
        }
        claims.flush();
    }

    private Optional<Outcome> validate(UUID projectId, BoardInput input) {
        if (input == null || input.name() == null || input.name().isBlank()) {
            return Optional.of(new Outcome.Unprocessable(
                    "A board needs a name", "The board picker lists boards by name."));
        }
        if (input.columns() == null || input.columns().isEmpty()) {
            return Optional.of(new Outcome.Unprocessable(
                    "A board needs at least one column",
                    "A board with no columns claims none of the project's statuses, and every "
                            + "status has to be claimed by exactly one."));
        }
        for (BoardColumnInput column : input.columns()) {
            if (column.name() == null || column.name().isBlank()) {
                return Optional.of(new Outcome.Unprocessable(
                        "Every column needs a name",
                        "A column with no name is a column nobody can be asked to drop onto."));
            }
            if (column.statusIds() == null || column.statusIds().isEmpty()) {
                return Optional.of(new Outcome.Unprocessable(
                        "Every column claims at least one status",
                        "Column '" + column.name().strip() + "' claims none, so no card could "
                                + "ever be in it."));
            }
        }
        return validateClaims(projectId, input);
    }

    /**
     * The half of "exactly one column per status" that Postgres cannot express, plus the two rules
     * that go with it.
     *
     * <p>A status claimed twice is caught here as well as by the unique index, so the caller is told
     * which status rather than being handed a constraint name. ~~A status claimed by nothing is
     * caught <em>only</em> here.~~ → superseded (021 R7): it is not caught at all any more, it is
     * <em>reported</em>, by {@link BoardCoverageService}. And a column whose default is outside its
     * own claim set is refused because a drop into it would move the card to a status the column
     * does not show — the card would vanish from the board on being dropped onto it (D2).
     */
    private Optional<Outcome> validateClaims(UUID projectId, BoardInput input) {
        Map<UUID, String> known = new LinkedHashMap<>();
        statuses.findByProjectIdOrderByPositionAsc(projectId)
                .forEach(status -> known.put(status.getId(), status.getName()));

        List<UUID> claimed = new ArrayList<>();
        input.columns().forEach(column -> claimed.addAll(column.statusIds()));

        Set<UUID> foreign = new LinkedHashSet<>(claimed);
        foreign.removeAll(known.keySet());
        if (!foreign.isEmpty()) {
            return Optional.of(new Outcome.Unprocessable(
                    "That is not a status of this project",
                    foreign.size() + " of the claimed statuses belong to another project's "
                            + "workflow, or have been removed from this one."));
        }

        Set<UUID> seen = new HashSet<>();
        Set<UUID> twice = new LinkedHashSet<>();
        claimed.forEach(statusId -> {
            if (!seen.add(statusId)) {
                twice.add(statusId);
            }
        });
        if (!twice.isEmpty()) {
            return Optional.of(new Outcome.Unprocessable(
                    "One status, one column",
                    "Claimed by more than one column: " + names(known, twice) + ". Every drop "
                            + "into either column would mean the same status, so the board would "
                            + "move an issue and leave the card where it was."));
        }

        // ~~"Every status needs a column" was refused here.~~ → REMOVED by 021 R7. A board claims
        // a SUBSET: a status this board does not claim is a status this board does not show, which
        // is what makes a PM board and a Dev board different boards rather than two orderings of
        // the same one. The failure the refusal guarded — a card in a status NO board claims, and
        // so on no board at all — is reported by BoardCoverageService instead of prevented here,
        // because preventing it is what forced every board to show the whole lifecycle.

        for (BoardColumnInput column : input.columns()) {
            if (!column.statusIds().contains(column.defaultStatusId())) {
                return Optional.of(new Outcome.Unprocessable(
                        "A column defaults to a status it claims",
                        "Column '" + column.name().strip() + "' defaults to a status it does not "
                                + "claim, so a card dropped into it would move to a status the "
                                + "column does not show and disappear from the board."));
            }
        }
        return Optional.empty();
    }

    /**
     * The column of one board claiming one status, or null when that board does not show it.
     *
     * <p>Resolved against the columns already in hand rather than by {@code findById}: a
     * query-based lookup is what applies the company filter in the application layer too, and the
     * caller has just made one.
     */
    private BoardColumn columnClaiming(Board board, List<BoardColumn> ordered, UUID statusId) {
        UUID columnId = claims.findByBoardId(board.getId()).stream()
                .filter(claim -> claim.getStatusId().equals(statusId))
                .map(BoardColumnStatus::getColumnId)
                .findFirst()
                .orElse(null);
        if (columnId == null) {
            return null;
        }
        return ordered.stream()
                .filter(column -> column.getId().equals(columnId))
                .findFirst()
                .orElse(null);
    }

    /**
     * The picker's rows, in Work-strip order.
     *
     * <p>{@code statusCount} is counted per board rather than derived from the project's status
     * total, because a board claims a subset now (021 R7) and the picker reads "5 of 11". Counting
     * the claim rows is the honest number: a column claiming three statuses contributes three.
     */
    private List<BoardSummaryView> summariesOf(Project project) {
        return boards.findByProjectIdOrderByPositionAscNameAsc(project.getId()).stream()
                .map(board -> new BoardSummaryView(
                        board.getId(),
                        board.getName(),
                        columns.findByBoardIdOrderByPositionAsc(board.getId()).size(),
                        board.getSwimlaneBy(),
                        claims.findByBoardId(board.getId()).size()))
                .toList();
    }

    private BoardView viewOf(Board board) {
        Map<UUID, IssueStatusView> known = new LinkedHashMap<>();
        statuses.findByProjectIdOrderByPositionAsc(board.getProjectId())
                .forEach(status -> known.put(status.getId(), IssueStatusView.of(status)));

        Map<UUID, List<IssueStatusView>> byColumn = new HashMap<>();
        for (BoardColumnStatus claim : claims.findByBoardId(board.getId())) {
            IssueStatusView status = known.get(claim.getStatusId());
            if (status != null) {
                byColumn.computeIfAbsent(claim.getColumnId(), id -> new ArrayList<>()).add(status);
            }
        }

        List<BoardColumnView> columnViews =
                columns.findByBoardIdOrderByPositionAsc(board.getId()).stream()
                        .map(column -> new BoardColumnView(
                                column.getId(),
                                column.getName(),
                                column.getPosition(),
                                column.getWipLimit(),
                                inWorkflowOrder(byColumn.get(column.getId())),
                                column.getDefaultStatusId()))
                        .toList();

        return new BoardView(board.getId(), board.getProjectId(), board.getName(),
                board.getSwimlaneBy(), columnViews);
    }

    private static List<IssueStatusView> inWorkflowOrder(List<IssueStatusView> claimed) {
        return claimed == null
                ? List.of()
                : claimed.stream().sorted(Comparator.comparingInt(IssueStatusView::position)).toList();
    }

    private static String names(Map<UUID, String> known, Set<UUID> wanted) {
        return wanted.stream().map(known::get).filter(java.util.Objects::nonNull).sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static String nameOf(BoardInput input) {
        return input.name().strip();
    }

    private static SwimlaneBy swimlaneOf(BoardInput input) {
        return input.swimlaneBy() == null ? SwimlaneBy.NONE : input.swimlaneBy();
    }
}
