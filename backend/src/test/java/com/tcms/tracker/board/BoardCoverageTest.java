package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * {@code GET /projects/{projectId}/boards/coverage} — the read that replaced a rule (021 R7, D-e).
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>Story B2 refused a board that left any status unclaimed. That refusal is what made every board
 * show the whole lifecycle, and so what made one Team board the only board anybody ever configured.
 * 021 R7 removed it — a board claims a subset — and the protection it gave is <strong>real</strong>
 * and did not go with it: a card in a status <em>no</em> board claims is a card on no board at all,
 * and nothing about the screen says so.
 *
 * <p>So the protection moved rather than evaporated: it <strong>warns</strong> here where it
 * <strong>refused</strong> there. {@code BoardConfigServiceTest} holds the reversal itself — a
 * partial board now stores with a 201; this class holds what took its place.
 */
@AutoConfigureMockMvc
@DisplayName("Board coverage — which boards show each status, and which statuses none of them do")
class BoardCoverageTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("b7000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("b7001000-0000-4000-8000-000000000001");

    /** Project Manager, which is the tier holding {@code board:configure} (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("b7002000-0000-4000-8000-000000000001");
    private static final UUID MANAGER_GRANT = UUID.fromString("b7003000-0000-4000-8000-000000000001");

    @Autowired
    private BoardCoverageService coverage;

    @Autowired
    private BoardConfigService boardConfig;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "board-coverage-acme", "Acme", now);
            activeUser(MANAGER, "pm@board-coverage.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "BCOV", "Board Coverage Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        restoreBoards();
    }

    @AfterEach
    void putTheBoardsBack() throws SQLException {
        clearIssues(List.of(ACME));
        restoreBoards();
    }

    @Test
    @DisplayName("a freshly seeded project covers every status across its three boards")
    void seededProjectIsFullyCovered() {
        BoardCoverageView matrix = matrix();

        assertThat(matrix.rows()).hasSize(11);
        assertThat(matrix.uncovered())
                .as("the seeded PM · Dev · QA boards claim all eleven between them, so the warning "
                        + "row is empty on day one — it exists for the day somebody deletes a column")
                .isEmpty();
        assertThat(matrix.rows()).extracting(row -> row.status().name())
                .as("one row per status, in workflow order — the matrix reads down the line")
                .containsExactly("New", "Refining", "Ready to develop", "In progress", "In review",
                        "Ready for test", "In test", "Ready to deploy", "Done", "Rework", "Blocked");
    }

    @Test
    @DisplayName("a hand-off status names both boards that share it, in strip order")
    void aHandOffStatusNamesBothBoards() {
        // The shared column IS the hand-off: the same status is the last working column of one
        // board and the first of the next, so a drag on one board is the arrival on the other. That
        // is only readable if a coverage row can name two boards, which is the case the removed
        // rule made impossible to distinguish from "every board claims everything".
        BoardCoverageView matrix = matrix();

        assertThat(claimNamesOf(matrix, "Ready for test"))
                .as("in board position order, so the row reads the way the Work strip draws")
                .containsExactly("Dev board", "QA board");
        assertThat(rowFor(matrix, "Ready for test").claims().get(0).columnName())
                .isEqualTo("Ready for test");

        assertThat(claimNamesOf(matrix, "Blocked"))
                .as("everyone's problem, visible wherever anyone looks")
                .containsExactly("PM board", "Dev board", "QA board");
        assertThat(claimNamesOf(matrix, "Done"))
                .as("shipping is the PO's call, so Done is the PM board's alone (021 D-l)")
                .containsExactly("PM board");
    }

    @Test
    @DisplayName("a status claimed by one board names it once, with the column it is in")
    void aColumnNameTravelsWithEachClaim() {
        // Both names travel with both ids because the table is READ rather than navigated: a cell
        // says "Dev board · In progress", and a client that had to resolve two ids to draw it would
        // fetch every board of the project to render one screen.
        BoardCoverageRowView rework = rowFor(matrix(), "Rework");

        assertThat(rework.claims()).hasSize(2);
        assertThat(rework.claims().get(0).boardName()).isEqualTo("Dev board");
        assertThat(rework.claims().get(0).columnName())
                .as("Dev folds Rework into In progress — a card that came back is work in flight, "
                        + "and a column of its own on that board would be a queue nobody owns")
                .isEqualTo("In progress");
        assertThat(rework.claims().get(1).boardName()).isEqualTo("QA board");
        assertThat(rework.claims().get(1).columnName()).isEqualTo("Rework");
    }

    @Test
    @DisplayName("deleting the QA board's In test column leaves In test uncovered, and coverage says so")
    void uncoveredIsReported() {
        // The whole point. In test is on the QA board and nowhere else, so dropping that column
        // puts every card in it on no board at all — accepted by the service, because 021 R7 traded
        // the refusal for this report, and it is this report's job to make the loss visible.
        removeColumnFrom("QA board", "In test");

        BoardCoverageView matrix = matrix();

        assertThat(matrix.uncovered()).extracting(IssueStatusView::name)
                .containsExactly("In test");
        assertThat(rowFor(matrix, "In test").claims())
                .as("the row is still there and it is empty — a missing row would read as a "
                        + "missing status rather than as an unshown one")
                .isEmpty();
        assertThat(matrix.rows())
                .as("and nothing else lost its claims")
                .hasSize(11);
        assertThat(claimNamesOf(matrix, "Ready for test")).containsExactly("Dev board", "QA board");
    }

    @Test
    @DisplayName("the matrix is a fact about the project, so it survives losing two of three boards")
    void coverageIsAProjectFactRatherThanABoardOne() {
        deleteBoard("QA board");
        deleteBoard("Dev board");

        BoardCoverageView matrix = matrix();

        assertThat(matrix.rows()).hasSize(11);
        assertThat(matrix.uncovered()).extracting(IssueStatusView::name)
                .as("what the PM board does not show, nothing shows any more")
                .containsExactly("In progress", "In review", "Ready for test", "In test", "Rework");
    }

    // =====================================================================

    private BoardCoverageView matrix() {
        return scoped(() -> coverage.coverageOf(MANAGER, PROJECT).orElseThrow());
    }

    private static BoardCoverageRowView rowFor(BoardCoverageView matrix, String statusName) {
        return matrix.rows().stream()
                .filter(row -> row.status().name().equals(statusName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no coverage row for " + statusName));
    }

    private static List<String> claimNamesOf(BoardCoverageView matrix, String statusName) {
        return rowFor(matrix, statusName).claims().stream()
                .map(BoardClaimView::boardName)
                .toList();
    }

    /** Rewrites a board without one of its columns — a {@code PATCH}, exactly as the screen sends. */
    private void removeColumnFrom(String boardName, String columnName) {
        UUID boardId = idOfBoard(boardName);
        BoardView board = scoped(() -> boardConfig.board(MANAGER, boardId).orElseThrow());

        List<BoardColumnInput> kept = new ArrayList<>();
        board.columns().stream()
                .filter(column -> !column.name().equals(columnName))
                .forEach(column -> kept.add(new BoardColumnInput(
                        column.id(), column.name(), column.wipLimit(),
                        column.statuses().stream().map(IssueStatusView::id).toList(),
                        column.defaultStatusId())));

        GateResult<BoardConfigService.Outcome> gate = scoped(() -> boardConfig.update(
                MANAGER, boardId, new BoardInput(board.name(), board.swimlaneBy(), kept)));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        assertThat(((GateResult.Allowed<BoardConfigService.Outcome>) gate).value())
                .as("a board that leaves a status unclaimed is STORED now — the refusal is what "
                        + "021 R7 removed, and this read is what replaced it")
                .isInstanceOf(BoardConfigService.Outcome.Written.class);
    }

    private void deleteBoard(String boardName) {
        UUID boardId = idOfBoard(boardName);
        GateResult<BoardConfigService.Outcome> gate =
                scoped(() -> boardConfig.delete(MANAGER, boardId));
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        assertThat(((GateResult.Allowed<BoardConfigService.Outcome>) gate).value())
                .isInstanceOf(BoardConfigService.Outcome.Deleted.class);
    }

    private UUID idOfBoard(String boardName) {
        return scoped(() -> boardConfig.list(MANAGER, PROJECT).orElseThrow()).stream()
                .filter(board -> board.name().equals(boardName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no board called " + boardName))
                .id();
    }

    /**
     * Every call goes through the company scope guard.
     *
     * <p>A request arrives with one because the session establishes it; a test calling the service
     * in-process does not, and without it {@code app.current_company} is unset, the RLS policy
     * filters every row, and the gate answers "no such project" — fail-closed and silent.
     */
    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return call.get();
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
