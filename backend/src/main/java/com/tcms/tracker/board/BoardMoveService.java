package com.tcms.tracker.board;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tracker.Issue;
import com.tcms.tracker.IssueRepository;
import com.tcms.tracker.TransitionService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving a card to a column — {@code POST /boards/{boardId}/cards/{key}/move}.
 *
 * <p>Costs {@code issue:edit}, and <strong>there is no {@code board:move}</strong> (§A3 line 260): a
 * second key for an act an existing key already covers is a permission somebody could hold without
 * being able to use it.
 *
 * <h2>Two things this does that {@code POST /issues/{key}/transitions} cannot</h2>
 *
 * <ol>
 *   <li><strong>It resolves the column's default status</strong> (D2). A stakeholder board's "In
 *       flight" claims In Progress, In Review and Blocked; a drop into it must mean exactly one of
 *       them, and which one is the column's decision rather than the client's. A client doing this
 *       itself would have to know the column mapping, which is the coupling this endpoint removes.</li>
 *   <li><strong>It reports the WIP breach</strong> the move may have caused — 200 with the breach in
 *       the body, never a refusal (story B3).</li>
 * </ol>
 *
 * <p>The transition itself is <strong>delegated</strong> to {@link TransitionService} — same module,
 * same allow-list, same audit row. Two implementations of "may this issue go there" is how two
 * answers to that question start disagreeing.
 *
 * <h2>Already in a status the column claims: nothing happens, and it is a 200</h2>
 *
 * <p>Dragging a card from In Review onto a coarse column that already claims In Review is not a
 * move. Writing the column's default would silently walk it back to In Progress and read as the
 * board undoing a drag.
 */
@Service
public class BoardMoveService {

    /** The same key {@link TransitionService} charges, because it is the same act. */
    private static final String EDIT_ISSUE = "issue:edit";

    private final ProjectService projects;
    private final TransitionService transitions;
    private final BoardCardService cards;
    private final BoardRepository boards;
    private final BoardColumnRepository columns;
    private final BoardColumnStatusRepository claims;
    private final IssueRepository issues;

    public BoardMoveService(
            ProjectService projects,
            TransitionService transitions,
            BoardCardService cards,
            BoardRepository boards,
            BoardColumnRepository columns,
            BoardColumnStatusRepository claims,
            IssueRepository issues) {

        this.projects = projects;
        this.transitions = transitions;
        this.cards = cards;
        this.boards = boards;
        this.columns = columns;
        this.claims = claims;
        this.issues = issues;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** The workflow has no row for this pair — 409, naming both ends. */
        record NotAllowed(String from, String to) implements Outcome {
        }

        /** 021 §2.3 — a {@code blocking} column, dragged onto from a {@code DONE} status. */
        record BlockingFromDone(String from) implements Outcome {
        }

        /**
         * D33 — the dragger is the assignee and this project requires a second person to close.
         *
         * <p>Carried through rather than mapped to {@code NotAllowed}: the workflow <em>does</em>
         * allow this move and the card's own {@code allowedTransitions} already withheld the
         * column, so a message about the workflow would send somebody to edit a setting that is
         * correct.
         */
        record CloseRefused(String detail) implements Outcome {
        }

        record Moved(CardMoveResultView result) implements Outcome {
        }
    }

    /**
     * The board, the column and the issue are resolved before the gate, and an unresolvable one
     * answers {@link GateResult.Unreachable} — {@code writeGrantedOnTarget}'s reason: the project to
     * gate against is a property of the stored row, so a caller naming a board that does not exist
     * cannot be told 403 without the server inventing which project they failed on.
     */
    @Transactional
    public GateResult<Outcome> move(UUID userId, UUID boardId, String key, UUID toColumnId) {
        Optional<Board> board = boards.findScopedById(boardId);
        if (board.isEmpty() || toColumnId == null) {
            return new GateResult.Unreachable<>();
        }
        Optional<BoardColumn> column = columns.findByBoardIdOrderByPositionAsc(boardId).stream()
                .filter(candidate -> candidate.getId().equals(toColumnId))
                .findFirst();
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (column.isEmpty() || issue.isEmpty()
                || !issue.get().getProjectId().equals(board.get().getProjectId())) {
            return new GateResult.Unreachable<>();
        }

        if (claimed(boardId, toColumnId).contains(issue.get().getStatusId())) {
            return projects.writeGranted(userId, board.get().getProjectId(), EDIT_ISSUE,
                    project -> answered(userId, board.get(), column.get(), key));
        }

        GateResult<TransitionService.Outcome> moved =
                transitions.move(userId, key, column.get().getDefaultStatusId());
        return switch (moved) {
            case GateResult.Unreachable<TransitionService.Outcome> ignored ->
                    new GateResult.Unreachable<>();
            case GateResult.Denied<TransitionService.Outcome> ignored -> new GateResult.Denied<>();
            case GateResult.Allowed<TransitionService.Outcome> allowed ->
                    new GateResult.Allowed<>(
                            mapped(userId, allowed.value(), board.get(), column.get(), key));
        };
    }

    private Outcome mapped(
            UUID userId, TransitionService.Outcome outcome, Board board, BoardColumn column,
            String key) {

        return switch (outcome) {
            case TransitionService.Outcome.NotAllowed refused ->
                    new Outcome.NotAllowed(refused.from(), refused.to());
            case TransitionService.Outcome.BlockingFromDone refused ->
                    new Outcome.BlockingFromDone(refused.from());
            // The column's default is one of the statuses it claims, and every claimed status is one
            // of the project's — BoardConfigService refuses a board where either is false. Reaching
            // here means a row was written past that gate, which is a defect rather than an answer.
            case TransitionService.Outcome.UnknownStatus ignored ->
                    throw new IllegalStateException("board column " + column.getId()
                            + " defaults to a status that is not in its project's workflow");
            case TransitionService.Outcome.CloseRefused refused ->
                    new Outcome.CloseRefused(refused.detail());
            case TransitionService.Outcome.Moved ignored -> answered(userId, board, column, key);
        };
    }

    /**
     * The card as it now stands, plus the target column's breach.
     *
     * <p>Re-read rather than patched from the request: the transition wrote {@code status_entered_at}
     * and may have written {@code done_at}, and a card assembled from what the caller sent would be
     * a second assembly of the shape {@link BoardCardService} owns.
     */
    private Outcome answered(UUID userId, Board board, BoardColumn column, String key) {
        BoardCardService.Facts facts = cards.factsFor(userId, board.getProjectId());
        Issue moved = issues.findByIssueKeyAndDeletedAtIsNull(key).orElseThrow();

        Map<UUID, UUID> columnOfStatus = new HashMap<>();
        claims.findByBoardId(board.getId())
                .forEach(claim -> columnOfStatus.put(claim.getStatusId(), claim.getColumnId()));

        int held = 0;
        for (Issue issue : facts.ordered()) {
            if (column.getId().equals(columnOfStatus.get(facts.shownStatus(issue).id()))) {
                held++;
            }
        }
        // A column at EXACTLY its limit is not in breach. Off-by-one here puts a permanent warning
        // on any team that actually works to its limit, and a warning that is always on is one
        // nobody reads.
        WipBreachView breach = column.getWipLimit() != null && held > column.getWipLimit()
                ? new WipBreachView(column.getId(), column.getWipLimit(), held)
                : null;

        UUID columnId = columnOfStatus.get(facts.shownStatus(moved).id());
        // The same scope the board read builds, and for the same reason a card is re-read rather
        // than patched: `elsewhere` is a fact about this board against the project's others, so a
        // card answered here without it would lose its row-header link on every drag.
        BoardCardService.BoardScope scope = cards.scopeOf(board, board.getProjectId());
        return new Outcome.Moved(new CardMoveResultView(
                facts.cardOf(moved, columnId == null ? column.getId() : columnId, scope), breach));
    }

    private List<UUID> claimed(UUID boardId, UUID columnId) {
        return claims.findByBoardId(boardId).stream()
                .filter(claim -> claim.getColumnId().equals(columnId))
                .map(BoardColumnStatus::getStatusId)
                .toList();
    }
}
