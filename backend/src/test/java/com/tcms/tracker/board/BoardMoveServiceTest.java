package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.TrackerEndpointTestBase;
import com.tcms.tracker.TransitionService;
import com.tcms.tracker.WorkflowService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * Dropping a card into a column — the three answers that are easy to get backwards.
 *
 * <p>The WIP limit is <strong>reported and never enforced</strong>; a card already in a status the
 * target column claims is <strong>not moved</strong>; and a move the workflow forbids is refused by
 * {@code TransitionService} rather than by a second copy of that rule living here.
 */
@AutoConfigureMockMvc
@DisplayName("The card move — the default status, the breach, and the drop that changes nothing")
class BoardMoveServiceTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("bf300000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("bf301000-0000-4000-8000-000000000001");

    /** Project Manager — the tier holding {@code board:configure} and {@code issue:edit} (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("bf302000-0000-4000-8000-000000000001");
    private static final UUID MANAGER_GRANT = UUID.fromString("bf303000-0000-4000-8000-000000000001");

    @Autowired
    private BoardMoveService moves;

    @Autowired
    private BoardCardService cards;

    @Autowired
    private BoardConfigService boardConfig;

    @Autowired
    private WorkflowService workflow;

    @Autowired
    private TransitionService transitions;

    private int nextSeq;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "board-move-acme", "Acme", now);
            activeUser(MANAGER, "pm@board-move.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "MOVE", "Board Move Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        restoreBoards();
        nextSeq = 1;
    }

    /**
     * And again on the way out, because every method here adds a board.
     *
     * <p>The {@code board} rows go <strong>first</strong>: the seed function's board block is
     * guarded by {@code NOT EXISTS (SELECT 1 FROM board …)}, so a project that still has a board row
     * keeps whatever columns the last method left on it (D-p).
     */
    @AfterEach
    void putTheBoardsBack() throws SQLException {
        clearIssues(List.of(ACME));
        restoreBoards();
    }

    @Test
    @DisplayName("a drop into a column claiming three statuses takes that column's default")
    void aDropIntoAColumnClaimingThreeStatusesTakesItsDefault() throws SQLException {
        // Story B2's second criterion, and D2's whole point: a stakeholder board's "In flight"
        // claims three engineering states and a drop into it must mean exactly one of them.
        UUID board = coarseBoard();
        String key = issue("a story to fly");

        CardMoveResultView result = moved(board, key, columnId(board, "In flight"));

        assertThat(result.card().status().name()).isEqualTo("In Progress");
        assertThat(statusOf(key)).isEqualTo("In Progress");
    }

    @Test
    @DisplayName("a card already in a status the column claims is not demoted to the default")
    void aCardAlreadyInAStatusTheColumnClaimsIsNotDemoted() throws SQLException {
        // "In flight" claims In Progress, In Review and Blocked, and defaults to In Progress. A
        // move that wrote the default would silently walk a card in review BACKWARDS, and read as
        // the board undoing the drag.
        UUID board = coarseBoard();
        String key = issue("a story in review");
        transitionTo(key, "Ready");
        transitionTo(key, "In Progress");
        transitionTo(key, "In Review");

        CardMoveResultView result = moved(board, key, columnId(board, "In flight"));

        assertThat(statusOf(key)).isEqualTo("In Review");
        assertThat(result.card().status().name()).isEqualTo("In Review");
        assertThat(result.card().columnId()).isEqualTo(columnId(board, "In flight"));
    }

    @Test
    @DisplayName("a move over the WIP limit succeeds and reports the breach")
    void aMoveOverTheWipLimitSucceedsAndReportsTheBreach() throws SQLException {
        // Reported, NOT enforced (story B3). A limit that refused drops is a limit people route
        // around by not using the board, and then the board stops describing the work.
        UUID board = boardWithWipLimit("In Progress", 1);
        String first = issue("first in flight");
        String second = issue("second in flight");
        UUID inProgress = columnId(board, "In Progress");

        moved(board, first, inProgress);
        CardMoveResultView result = moved(board, second, inProgress);

        assertThat(statusOf(second)).isEqualTo("In Progress");
        assertThat(result.breach()).isNotNull();
        assertThat(result.breach().count()).isEqualTo(2);
        assertThat(result.breach().limit()).isEqualTo(1);
    }

    @Test
    @DisplayName("a column at exactly its limit is not in breach")
    void aColumnAtExactlyItsLimitIsNotInBreach() throws SQLException {
        // Off-by-one here puts a permanent warning on any team that actually works to its limit,
        // and a warning that is always on is one nobody reads.
        UUID board = boardWithWipLimit("In Progress", 2);
        String first = issue("first in flight");
        String second = issue("second in flight");
        UUID inProgress = columnId(board, "In Progress");

        moved(board, first, inProgress);
        CardMoveResultView result = moved(board, second, inProgress);

        assertThat(result.breach()).isNull();
        assertThat(contents(board).breaches()).isEmpty();
    }

    @Test
    @DisplayName("a move the workflow forbids is refused by TransitionService, not by a second rule")
    void aMoveTheWorkflowForbidsIsRefused() throws SQLException {
        // Unreachable in an ordinary session, because the picker only offers legal moves — the card
        // carries allowedTransitions so a forbidden drop is refused at the drag (§12.4).
        UUID board = defaultBoard();
        String key = issue("a backlog story");

        BoardMoveService.Outcome.NotAllowed refused =
                notAllowed(board, key, columnId(board, "Done"));

        assertThat(refused.from()).isEqualTo("Backlog");
        assertThat(refused.to()).isEqualTo("Done");
        assertThat(statusOf(key)).isEqualTo("Backlog");
    }

    @Test
    @DisplayName("a card moved on one board is in the matching column on the other")
    void aCardMovedOnOneBoardIsInTheMatchingColumnOnTheOther() throws SQLException {
        // THE module's done-criterion, as a test rather than as a click-through. It holds because
        // there is one status and two mappings of it (D2) — there is no per-board state that could
        // disagree.
        UUID fine = defaultBoard();
        UUID coarse = coarseBoard();
        String key = issue("a story on two boards");
        transitionTo(key, "Ready");
        transitionTo(key, "In Progress");

        moved(fine, key, columnId(fine, "In Review"));

        BoardContentsView onCoarse = contents(coarse);
        assertThat(columnOf(onCoarse, key)).isEqualTo(columnId(coarse, "In flight"));
        assertThat(columnOf(contents(fine), key)).isEqualTo(columnId(fine, "In Review"));
    }

    // =====================================================================

    /** The board {@code tcms_seed_project_defaults} makes — one column per status. */
    private UUID defaultBoard() {
        return boards().get(0).id();
    }

    /** Six statuses folded into three columns — the stakeholder board V5 deliberately does not seed. */
    private UUID coarseBoard() {
        List<IssueStatusView> statuses = statuses();
        return written(new BoardInput("Coarse", SwimlaneBy.NONE, List.of(
                column("Not started", statuses, null, "Backlog", "Ready"),
                column("In flight", statuses, null, "In Progress", "In Review", "Blocked"),
                column("Done", statuses, null, "Done"))));
    }

    /** The seeded one-column-per-status shape, with a limit on one of them. */
    private UUID boardWithWipLimit(String columnName, int limit) {
        List<IssueStatusView> statuses = statuses();
        List<BoardColumnInput> columns = new ArrayList<>();
        statuses.forEach(status -> columns.add(new BoardColumnInput(
                null, status.name(), status.name().equals(columnName) ? limit : null,
                List.of(status.id()), status.id())));
        return written(new BoardInput("Limited", SwimlaneBy.NONE, columns));
    }

    private static BoardColumnInput column(
            String name, List<IssueStatusView> statuses, Integer wipLimit, String... claimed) {

        List<UUID> ids = new ArrayList<>();
        for (String status : claimed) {
            ids.add(idOf(statuses, status));
        }
        return new BoardColumnInput(null, name, wipLimit, ids, ids.get(0));
    }

    private UUID written(BoardInput input) {
        GateResult<BoardConfigService.Outcome> gate =
                scoped(() -> boardConfig.create(MANAGER, PROJECT, input));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        BoardConfigService.Outcome outcome =
                ((GateResult.Allowed<BoardConfigService.Outcome>) gate).value();
        assertThat(outcome).isInstanceOf(BoardConfigService.Outcome.Written.class);
        return ((BoardConfigService.Outcome.Written) outcome).view().id();
    }

    private CardMoveResultView moved(UUID boardId, String key, UUID columnId) {
        BoardMoveService.Outcome outcome = allowed(boardId, key, columnId);
        assertThat(outcome).isInstanceOf(BoardMoveService.Outcome.Moved.class);
        return ((BoardMoveService.Outcome.Moved) outcome).result();
    }

    private BoardMoveService.Outcome.NotAllowed notAllowed(
            UUID boardId, String key, UUID columnId) {

        BoardMoveService.Outcome outcome = allowed(boardId, key, columnId);
        assertThat(outcome).isInstanceOf(BoardMoveService.Outcome.NotAllowed.class);
        return (BoardMoveService.Outcome.NotAllowed) outcome;
    }

    private BoardMoveService.Outcome allowed(UUID boardId, String key, UUID columnId) {
        GateResult<BoardMoveService.Outcome> gate =
                scoped(() -> moves.move(MANAGER, boardId, key, columnId));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<BoardMoveService.Outcome>) gate).value();
    }

    private BoardContentsView contents(UUID boardId) {
        return scoped(() ->
                cards.contents(MANAGER, boardId, BoardCardService.Filter.none()).orElseThrow());
    }

    private static UUID columnOf(BoardContentsView contents, String key) {
        return contents.lanes().stream()
                .flatMap(lane -> lane.cards().stream())
                .filter(card -> card.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no card for " + key))
                .columnId();
    }

    private UUID columnId(UUID boardId, String name) {
        return scoped(() -> boardConfig.board(MANAGER, boardId).orElseThrow()).columns().stream()
                .filter(column -> column.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column called " + name))
                .id();
    }

    private void transitionTo(String key, String statusName) {
        UUID target = idOf(statuses(), statusName);
        GateResult<TransitionService.Outcome> gate =
                scoped(() -> transitions.move(MANAGER, key, target));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        assertThat(((GateResult.Allowed<TransitionService.Outcome>) gate).value())
                .as("the fixture's own move must be a legal one")
                .isInstanceOf(TransitionService.Outcome.Moved.class);
    }

    private List<IssueStatusView> statuses() {
        return scoped(() -> workflow.workflow(MANAGER, PROJECT).orElseThrow().statuses());
    }

    private List<BoardSummaryView> boards() {
        return scoped(() -> boardConfig.list(MANAGER, PROJECT).orElseThrow());
    }

    private static UUID idOf(List<IssueStatusView> statuses, String name) {
        return statuses.stream()
                .filter(status -> status.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no status called " + name))
                .id();
    }

    /**
     * Every call goes through the company scope guard.
     *
     * <p>A request arrives with one because the session establishes it; a test calling the service
     * in-process does not, and without it {@code app.current_company} is unset, RLS filters every
     * row and the gate answers "no such project".
     */
    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return call.get();
        }
    }

    private String issue(String title) throws SQLException {
        int seq = nextSeq++;
        String key = "MOVE-" + seq;
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue (id, company_id, project_id, key_seq, issue_key,
                                           type_id, status_id, title)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                (SELECT id FROM issue_type
                                  WHERE project_id = CAST(? AS uuid) AND name = 'Story'),
                                (SELECT id FROM issue_status
                                  WHERE project_id = CAST(? AS uuid) AND name = 'Backlog'),
                                ?)
                        """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setInt(4, seq);
            statement.setString(5, key);
            statement.setObject(6, PROJECT);
            statement.setObject(7, PROJECT);
            statement.setString(8, title);
            statement.executeUpdate();
        }
        return key;
    }

    private String statusOf(String key) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT s.name FROM issue i
                          JOIN issue_status s ON s.id = i.status_id
                         WHERE i.issue_key = ?
                        """)) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private void restoreBoards() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM board WHERE project_id = CAST(? AS uuid)")) {
                statement.setObject(1, PROJECT);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setObject(1, ACME);
                statement.setObject(2, PROJECT);
                statement.execute();
            }
        }
    }
}
