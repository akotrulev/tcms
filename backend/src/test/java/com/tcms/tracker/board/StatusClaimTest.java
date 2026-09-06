package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.IssueStatusKind;
import com.tcms.tracker.IssueStatusView;
import com.tcms.tracker.TrackerEndpointTestBase;
import com.tcms.tracker.WorkflowService;
import com.tcms.tracker.WorkflowView;
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
 * A status created after the boards exist (009 review R13, rewritten by 021 §2.2).
 *
 * <h2>The invariant this class used to hold is gone, deliberately</h2>
 *
 * <p>~~<strong>An unclaimed status is not a state the data can be in</strong>, and
 * {@code claimNewStatus} is what made that true rather than merely likely: the new status was
 * claimed on <em>every</em> board of the project.~~ → <strong>superseded by 021 R7 (D-e).</strong>
 * That was the other half of the rule that forced every board to show the whole lifecycle, which is
 * exactly why one Team board was the only board anybody ever made. A board claims a SUBSET now, so
 * "on every board" is not a property a new status can have without contradicting what a subset
 * board is for.
 *
 * <h2>What replaced it — two rules, and both are here</h2>
 *
 * <ol>
 *   <li><strong>A status is claimed at most once per board.</strong> That half is
 *       {@code uq_board_column_status}, a real unique index, and it is what makes a card's column
 *       unambiguous. It survives untouched.</li>
 *   <li><strong>A status inserted after X is claimed by every board that claims X, in X's
 *       column.</strong> A board that does not show the neighbour does not show the newcomer —
 *       which is the subset its owner configured, not a gap. A status with no neighbour at all,
 *       the workflow editor's "side status", lands on every board as a new last column, because
 *       it has no column anywhere to join.</li>
 * </ol>
 *
 * <p>The failure the removed rule prevented — a card in a status <em>no</em> board claims, and so
 * on no board at all — is reported rather than refused now: {@code BoardCoverageTest} is where that
 * lives.
 *
 * <p>The mirror-image case — a status <em>leaving</em> — is the database's, in {@code V5}'s
 * {@code board_follows_status_removal} trigger, and {@code BoardSchemaTest} covers it. One rule with
 * two homes is how the two come to disagree.
 */
@AutoConfigureMockMvc
@DisplayName("The status claim — a status added later lands where its neighbour already is")
class StatusClaimTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("b5000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("b5001000-0000-4000-8000-000000000001");

    /** Project Manager — the tier holding both {@code issue:workflow} and {@code board:configure}. */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("b5002000-0000-4000-8000-000000000001");
    private static final UUID MANAGER_GRANT = UUID.fromString("b5003000-0000-4000-8000-000000000001");

    @Autowired
    private BoardConfigService boardConfig;

    @Autowired
    private WorkflowService workflow;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "status-claim-acme", "Acme", now);
            activeUser(MANAGER, "pm@status-claim.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "SCLM", "Status Claim Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        restoreProjectDefaults();
    }

    /**
     * Every method here adds a twelfth status, and one class over asserts that <em>every</em> seeded
     * project in the shared container has the eleven defaults in order. Restoring only on the way in
     * is green here and red there.
     */
    @AfterEach
    void putTheWorkflowAndTheBoardsBack() throws SQLException {
        clearIssues(List.of(ACME));
        restoreProjectDefaults();
    }

    @Test
    @DisplayName("a status inserted after In review joins In review's column, on the boards that show it")
    void aStatusInsertedAfterInReviewJoinsThatColumnOnEveryBoardClaimingIt() {
        // In review is a DEV-board status and nothing else claims it, which is what makes this
        // assertion about the rule rather than about "all three boards got it anyway".
        replaceWorkflowInsertingAfter("In review", "Awaiting merge", IssueStatusKind.IN_PROGRESS);

        assertThat(columnClaiming("Dev board", "Awaiting merge"))
                .as("the board that claims the neighbour claims the newcomer, in the "
                        + "neighbour's own column")
                .isEqualTo("In review");
        assertThat(columnClaiming("PM board", "Awaiting merge"))
                .as("the PM board does not show In review, so it does not show what was "
                        + "inserted after it — that is the subset, not a gap")
                .isNull();
        assertThat(columnClaiming("QA board", "Awaiting merge")).isNull();
    }

    @Test
    @DisplayName("and it is claimed at most once on that board — the half of B2 that survived")
    void theNewStatusIsClaimedAtMostOncePerBoard() {
        // uq_board_column_status is what enforces it and this is what says the auto-claim does not
        // route around it. "At most one per board" is the half of story B2 that makes a card's
        // column unambiguous; "at least one per board" is the half 021 R7 removed.
        replaceWorkflowInsertingAfter("In review", "Awaiting merge", IssueStatusKind.IN_PROGRESS);

        assertThat(columnsClaiming("Dev board", "Awaiting merge")).hasSize(1);
    }

    @Test
    @DisplayName("a side status with no neighbour lands on every board as a new last column")
    void aSideStatusWithNoNeighbourLandsOnEveryBoardAsANewLastColumn() {
        // The workflow editor's "add a side status" — how Rework and Blocked are made — names no
        // predecessor, so there is no column anywhere for it to join. Every board gets one of its
        // own, LAST and not first: left to right is direction of travel (§12.2), and a status filed
        // at the start of a board it does not belong to is worse than one filed late.
        List<Integer> widthsBefore = List.of(
                columnCountOf("PM board"), columnCountOf("Dev board"), columnCountOf("QA board"));

        replaceWorkflowInsertingFirst("On hold externally", IssueStatusKind.IN_PROGRESS);

        for (String board : List.of("PM board", "Dev board", "QA board")) {
            assertThat(columnClaiming(board, "On hold externally"))
                    .as("board '%s' claims the side status in a column of its own", board)
                    .isEqualTo("On hold externally");
            assertThat(lastColumnNameOf(board))
                    .as("and that column is the LAST one on '%s'", board)
                    .isEqualTo("On hold externally");
        }
        assertThat(List.of(
                columnCountOf("PM board"), columnCountOf("Dev board"), columnCountOf("QA board")))
                .containsExactly(
                        widthsBefore.get(0) + 1, widthsBefore.get(1) + 1, widthsBefore.get(2) + 1);
    }

    @Test
    @DisplayName("a status inserted after a shared column's status lands on both boards that share it")
    void aStatusInsertedAfterASharedStatusLandsOnBothBoardsSharingIt() {
        // Ready for test is the Dev → QA hand-off: the last column of one board and the first of
        // the next. "Every board that claims the neighbour" is therefore two boards here and one in
        // the first method, which is the difference a per-board assertion cannot see.
        replaceWorkflowInsertingAfter("Ready for test", "Awaiting env", IssueStatusKind.IN_PROGRESS);

        assertThat(columnClaiming("Dev board", "Awaiting env")).isEqualTo("Ready for test");
        assertThat(columnClaiming("QA board", "Awaiting env")).isEqualTo("Ready for test");
        assertThat(columnClaiming("PM board", "Awaiting env")).isNull();
    }

    // =====================================================================

    private void replaceWorkflowInsertingAfter(
            String neighbourName, String name, IssueStatusKind kind) {

        replaceWorkflowInserting(name, kind, indexOf(neighbourName) + 1);
    }

    private void replaceWorkflowInsertingFirst(String name, IssueStatusKind kind) {
        // Index zero is how "no neighbour" is expressed: the contract carries no "off the line"
        // flag, so the status the newcomer was placed after is the one before it in the submitted
        // order, and a status placed first has none.
        replaceWorkflowInserting(name, kind, 0);
    }

    private void replaceWorkflowInserting(String name, IssueStatusKind kind, int index) {
        WorkflowView current = scoped(() -> workflow.workflow(MANAGER, PROJECT).orElseThrow());
        List<IssueStatusView> statuses = new ArrayList<>(current.statuses());
        // blocking = false: the workflow editor cannot move the flag yet (021 T3), and a second
        // blocking status would be refused by uq_issue_status_one_blocking anyway.
        statuses.add(index, new IssueStatusView(null, name, kind, index, false));

        GateResult<WorkflowService.Outcome> gate = scoped(() -> workflow.replace(MANAGER, PROJECT,
                new WorkflowView(PROJECT, statuses, current.types(), current.transitions())));

        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        assertThat(((GateResult.Allowed<WorkflowService.Outcome>) gate).value())
                .isInstanceOf(WorkflowService.Outcome.Replaced.class);
    }

    private int indexOf(String statusName) {
        List<IssueStatusView> statuses = statuses();
        for (int index = 0; index < statuses.size(); index++) {
            if (statuses.get(index).name().equals(statusName)) {
                return index;
            }
        }
        throw new AssertionError("no status called " + statusName);
    }

    /** The column of one board claiming one status, or null when that board does not show it. */
    private String columnClaiming(String boardName, String statusName) {
        List<String> columns = columnsClaiming(boardName, statusName);
        return columns.isEmpty() ? null : columns.get(0);
    }

    private List<String> columnsClaiming(String boardName, String statusName) {
        return boardView(boardName).columns().stream()
                .filter(column -> column.statuses().stream()
                        .anyMatch(status -> status.name().equals(statusName)))
                .map(BoardColumnView::name)
                .toList();
    }

    private int columnCountOf(String boardName) {
        return boardView(boardName).columns().size();
    }

    private String lastColumnNameOf(String boardName) {
        List<BoardColumnView> columns = boardView(boardName).columns();
        return columns.get(columns.size() - 1).name();
    }

    private List<IssueStatusView> statuses() {
        return scoped(() -> workflow.workflow(MANAGER, PROJECT).orElseThrow().statuses());
    }

    private BoardView boardView(String boardName) {
        UUID boardId = scoped(() -> boardConfig.list(MANAGER, PROJECT).orElseThrow()).stream()
                .filter(board -> board.name().equals(boardName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the project has no board called " + boardName))
                .id();
        return scoped(() -> boardConfig.board(MANAGER, boardId).orElseThrow());
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

    /**
     * Puts the project back to what {@code tcms_seed_project_defaults} makes.
     *
     * <p>The boards go <strong>before</strong> the statuses and the seed runs after both, because
     * the seed function's board block is guarded by {@code NOT EXISTS (SELECT 1 FROM board …)}: a
     * project that still has a board row would keep the columns this class left on it, and the
     * status delete would have taken most of them via {@code board_follows_status_removal} on the
     * way past.
     */
    private void restoreProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            for (String statement : List.of(
                    "DELETE FROM board WHERE project_id = CAST(? AS uuid)",
                    "DELETE FROM issue_transition WHERE project_id = CAST(? AS uuid)",
                    "DELETE FROM issue_status WHERE project_id = CAST(? AS uuid)",
                    "DELETE FROM issue_type WHERE project_id = CAST(? AS uuid)")) {
                try (PreparedStatement prepared = connection.prepareStatement(statement)) {
                    prepared.setObject(1, PROJECT);
                    prepared.executeUpdate();
                }
            }
            try (PreparedStatement prepared = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                prepared.setObject(1, ACME);
                prepared.setObject(2, PROJECT);
                prepared.execute();
            }
        }
    }
}
