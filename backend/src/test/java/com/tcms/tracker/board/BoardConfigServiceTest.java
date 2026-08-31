package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.TrackerEndpointTestBase;
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
 * The board rules the database cannot hold, checked where they live.
 *
 * <p>{@code uq_board_column_status} is the half of "every status is claimed by exactly one column"
 * Postgres <em>can</em> enforce, and {@code BoardSchemaTest} covers it. This class covers the other
 * half and the two rules that travel with it: <strong>claimed at least once</strong>, which is a
 * statement about the absence of a row and so a cross-table assertion no CHECK can express; a
 * column whose default is outside its own claim set, which would move a card to a status the column
 * does not show it in; and the project's last board, which cannot be deleted because a project with
 * no board has no board view at all.
 *
 * <p>A trigger was refused for the first of those and the reason is worth keeping in sight: it
 * would fire while a {@code PATCH} rebuilds a board column by column, and reject the legal
 * intermediate state.
 */
@AutoConfigureMockMvc
@DisplayName("Board configuration — the claim rules, and the board that owns nothing")
class BoardConfigServiceTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("b6001000-0000-4000-8000-000000000001");

    /** Project Manager, which is the tier holding {@code board:configure} (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("b6002000-0000-4000-8000-000000000001");
    private static final UUID MANAGER_GRANT = UUID.fromString("b6003000-0000-4000-8000-000000000001");

    @Autowired
    private BoardConfigService boardConfig;

    @Autowired
    private WorkflowService workflow;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "board-config-acme", "Acme", now);
            activeUser(MANAGER, "pm@board-config.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "BCFG", "Board Config Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        restoreBoards();
    }

    /**
     * And again on the way out, because half of these methods add a board and one deletes one.
     * Restoring only on the way in is green in this class and red in whichever class asserts over
     * every project in the shared container.
     */
    @AfterEach
    void putTheBoardsBack() throws SQLException {
        clearIssues(List.of(ACME));
        restoreBoards();
    }

    @Test
    @DisplayName("a board that leaves a status unclaimed is refused, and the message names it")
    void refusesABoardThatLeavesAStatusUnclaimed() {
        List<IssueStatusView> statuses = statuses();
        List<BoardColumnInput> columns = new ArrayList<>();
        statuses.stream()
                .filter(status -> !status.name().equals("Blocked"))
                .forEach(status -> columns.add(new BoardColumnInput(
                        null, status.name(), null, List.of(status.id()), status.id())));

        BoardConfigService.Outcome.Unprocessable refused = refusal(create(new BoardInput("Partial", SwimlaneBy.NONE, columns)));

        assertThat(refused.detail())
                .as("""
                        An issue in an unclaimed status would be on no board at all, and "invalid \
                        board" sends somebody to count six columns against six statuses by hand.""")
                .contains("Blocked");
    }

    @Test
    @DisplayName("a column defaulting to a status it does not claim is refused")
    void refusesADefaultStatusTheColumnDoesNotClaim() {
        // D2: a drop into a column takes that column's default status. A default outside the claim
        // set would move the card to a status the column does not show it in, so the card would
        // vanish from the board on being dropped onto it.
        List<IssueStatusView> statuses = statuses();
        UUID done = idOf(statuses, "Done");
        List<BoardColumnInput> columns = new ArrayList<>();
        for (IssueStatusView status : statuses) {
            columns.add(new BoardColumnInput(
                    null, status.name(), null, List.of(status.id()),
                    status.name().equals("Backlog") ? done : status.id()));
        }

        BoardConfigService.Outcome.Unprocessable refused = refusal(create(new BoardInput("Wrong default", SwimlaneBy.NONE, columns)));

        assertThat(refused.detail()).contains("Backlog");
    }

    @Test
    @DisplayName("a status claimed by two columns is refused before the unique index sees it")
    void refusesAStatusClaimedByTwoColumns() {
        // The index would refuse it too, and with a constraint name. Refusing it here is what lets
        // the caller be told WHICH status, which is the whole reason the rule is stated twice.
        List<IssueStatusView> statuses = statuses();
        UUID backlog = idOf(statuses, "Backlog");
        List<BoardColumnInput> columns = new ArrayList<>();
        for (IssueStatusView status : statuses) {
            columns.add(new BoardColumnInput(
                    null, status.name(), null, List.of(status.id()), status.id()));
        }
        columns.add(new BoardColumnInput(
                null, "Backlog again", null, List.of(backlog), backlog));

        BoardConfigService.Outcome.Unprocessable refused = refusal(create(new BoardInput("Twice", SwimlaneBy.NONE, columns)));

        assertThat(refused.detail()).contains("Backlog");
    }

    @Test
    @DisplayName("the project's last board cannot be deleted")
    void refusesDeletingTheProjectsLastBoard() {
        List<BoardSummaryView> boards = boards();
        assertThat(boards).hasSize(1);

        BoardConfigService.Outcome.Unprocessable refused =
                refusal(deleteBoard(boards.get(0).id()));

        assertThat(refused.title()).contains("at least one board");
    }

    @Test
    @DisplayName("deleting a board loses no issue — a board is a lens and owned none of them")
    void deletingABoardLosesNoIssue() throws SQLException {
        // Story B2's fourth criterion, and §12.1's whole design. Nothing points at a board from
        // `issue`, so this is a claim about the schema as much as about the service.
        insertIssue(701, "board lens fixture");
        long before = issueCount();
        UUID extra = written(create(coarseBoard())).view().id();

        BoardConfigService.Outcome deleted = allowed(deleteBoard(extra));

        assertThat(deleted).isInstanceOf(BoardConfigService.Outcome.Deleted.class);
        assertThat(issueCount()).isEqualTo(before);
        assertThat(boards()).hasSize(1);
    }

    @Test
    @DisplayName("a column keeps its id across a reorder, and the array index is the position")
    void reorderingColumnsKeepsTheirIdentity() {
        // The intermediate state uq_board_column_position would otherwise refuse: three columns
        // rotating one place all collide with the index unless the write moves them out of the
        // range it is writing into first.
        UUID board = written(create(coarseBoard())).view().id();
        BoardView before = boardView(board);

        List<BoardColumnInput> rotated = new ArrayList<>();
        for (int index = 1; index <= before.columns().size(); index++) {
            BoardColumnView column = before.columns().get(index % before.columns().size());
            rotated.add(new BoardColumnInput(
                    column.id(), column.name(), column.wipLimit(),
                    column.statuses().stream().map(IssueStatusView::id).toList(),
                    column.defaultStatusId()));
        }

        BoardView after = written(update(board, new BoardInput("Coarse", SwimlaneBy.NONE, rotated))).view();

        assertThat(after.columns().stream().map(BoardColumnView::name).toList())
                .containsExactly("In flight", "Done", "Not started");
        assertThat(after.columns().stream().map(BoardColumnView::position).toList())
                .containsExactly(0, 1, 2);
        assertThat(after.columns().stream().map(BoardColumnView::id).toList())
                .as("a column carrying an id is kept and updated, never recreated")
                .containsExactlyInAnyOrderElementsOf(
                        before.columns().stream().map(BoardColumnView::id).toList());
    }

    // =====================================================================

    /** Six statuses folded into three columns — the stakeholder board V5 deliberately does not seed. */
    private BoardInput coarseBoard() {
        List<IssueStatusView> statuses = statuses();
        return new BoardInput("Coarse", SwimlaneBy.NONE, List.of(
                column("Not started", statuses, "Backlog", "Ready"),
                column("In flight", statuses, "In Progress", "In Review", "Blocked"),
                column("Done", statuses, "Done")));
    }

    private static BoardColumnInput column(
            String name, List<IssueStatusView> statuses, String... claimed) {

        List<UUID> ids = new ArrayList<>();
        for (String status : claimed) {
            ids.add(idOf(statuses, status));
        }
        return new BoardColumnInput(null, name, null, ids, ids.get(0));
    }

    private List<IssueStatusView> statuses() {
        return scoped(() -> workflow.workflow(MANAGER, PROJECT).orElseThrow().statuses());
    }

    private List<BoardSummaryView> boards() {
        return scoped(() -> boardConfig.list(MANAGER, PROJECT).orElseThrow());
    }

    private BoardView boardView(UUID boardId) {
        return scoped(() -> boardConfig.board(MANAGER, boardId).orElseThrow());
    }

    private GateResult<BoardConfigService.Outcome> create(BoardInput input) {
        return scoped(() -> boardConfig.create(MANAGER, PROJECT, input));
    }

    private GateResult<BoardConfigService.Outcome> update(UUID boardId, BoardInput input) {
        return scoped(() -> boardConfig.update(MANAGER, boardId, input));
    }

    private GateResult<BoardConfigService.Outcome> deleteBoard(UUID boardId) {
        return scoped(() -> boardConfig.delete(MANAGER, boardId));
    }

    /**
     * Every call below goes through the company scope guard, and that is not fixture noise.
     *
     * <p>A request arrives with one because the session establishes it; a test calling the service
     * in-process does not, and without it {@code app.current_company} is unset, the RLS policy
     * filters every row, and the gate answers "no such project" — <strong>fail-closed and silent,
     * which is exactly the trap {@code tenancy-invariants.md} names.</strong>
     */
    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return call.get();
        }
    }

    private static UUID idOf(List<IssueStatusView> statuses, String name) {
        return statuses.stream()
                .filter(status -> status.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no status called " + name))
                .id();
    }

    private static BoardConfigService.Outcome allowed(
            GateResult<BoardConfigService.Outcome> gate) {

        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<BoardConfigService.Outcome>) gate).value();
    }

    private static BoardConfigService.Outcome.Written written(
            GateResult<BoardConfigService.Outcome> gate) {

        BoardConfigService.Outcome outcome = allowed(gate);
        assertThat(outcome).isInstanceOf(BoardConfigService.Outcome.Written.class);
        return (BoardConfigService.Outcome.Written) outcome;
    }

    private static BoardConfigService.Outcome.Unprocessable refusal(
            GateResult<BoardConfigService.Outcome> gate) {

        BoardConfigService.Outcome outcome = allowed(gate);
        assertThat(outcome).isInstanceOf(BoardConfigService.Outcome.Unprocessable.class);
        return (BoardConfigService.Outcome.Unprocessable) outcome;
    }

    private void insertIssue(int keySeq, String title) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue (id, company_id, project_id, key_seq, issue_key,
                                           type_id, status_id, title)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                (SELECT id FROM issue_type
                                  WHERE project_id = CAST(? AS uuid) AND name = 'Task'),
                                (SELECT id FROM issue_status
                                  WHERE project_id = CAST(? AS uuid) AND name = 'Backlog'),
                                ?)
                        """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ACME);
            statement.setObject(3, PROJECT);
            statement.setInt(4, keySeq);
            statement.setString(5, "BCFG-" + keySeq);
            statement.setObject(6, PROJECT);
            statement.setObject(7, PROJECT);
            statement.setString(8, title);
            statement.executeUpdate();
        }
    }

    private long issueCount() throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM issue WHERE project_id = CAST(? AS uuid)")) {
            statement.setObject(1, PROJECT);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * Drops every board of the project and re-seeds the one {@code tcms_seed_project_defaults}
     * makes.
     *
     * <p>The board rows go <strong>first</strong>, and that ordering is the whole of why this
     * helper exists: the seed function's board block is guarded by {@code NOT EXISTS (SELECT 1 FROM
     * board …)}, so a project that still has a board row keeps whatever columns this class left on
     * it and the next method starts from another method's fixture.
     */
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
