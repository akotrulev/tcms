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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * A status created after the board exists (009 review R13).
 *
 * <p><strong>An unclaimed status is not a state the data can be in.</strong> The workflow is
 * upstream of every board (D2), so a status arriving has to be claimed by all of them in the same
 * transaction that creates it — which is why {@code WorkflowService} calls
 * {@link BoardConfigService#claimNewStatus} directly rather than raising an event. An
 * {@code OutboxEvents} hop has a one-minute delivery floor, and for that minute the project would
 * hold a status no column claims; the third method here is the invariant itself, computed over
 * every board rather than read off the one being looked at.
 *
 * <p>The mirror-image case — a status <em>leaving</em> — is the database's, in {@code V5}'s
 * {@code board_follows_status_removal} trigger, and {@code BoardSchemaTest} covers it. One rule with
 * two homes is how the two come to disagree.
 */
@AutoConfigureMockMvc
@DisplayName("The status claim — a status added later lands on every board of the project")
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
     * Every method here adds a seventh status, and one class over asserts that <em>every</em> seeded
     * project in the shared container has the six defaults in order. Restoring only on the way in is
     * green here and red there.
     */
    @AfterEach
    void putTheWorkflowAndTheBoardsBack() throws SQLException {
        clearIssues(List.of(ACME));
        restoreProjectDefaults();
    }

    @Test
    @DisplayName("an IN_PROGRESS status added later lands beside In Progress, on every board")
    void anInProgressStatusAddedLaterLandsBesideInProgressOnEveryBoard() {
        UUID coarse = created(create(coarseBoard())).id();

        replaceWorkflowAdding("Awaiting QA", IssueStatusKind.IN_PROGRESS);

        assertThat(columnClaiming(onlySeededBoard(), "Awaiting QA"))
                .as("the seeded per-status board files it beside the first IN_PROGRESS column")
                .isEqualTo("In Progress");
        assertThat(columnClaiming(coarse, "Awaiting QA"))
                .as("and so does the coarse one — 'every board of the project', not just the one "
                        + "somebody happens to be looking at")
                .isEqualTo("In flight");
    }

    @Test
    @DisplayName("a status of a kind no column defaults to lands in the LAST column")
    void aStatusOfAKindNoColumnDefaultsToLandsInTheLastColumn() {
        // Not the first. Left-to-right is direction of travel (§12.2), and a new DONE status filed
        // at the start of the workflow is worse than one filed late.
        UUID board = created(create(boardWithNoDoneDefault())).id();

        replaceWorkflowAdding("Shipped", IssueStatusKind.DONE);

        BoardView after = boardView(board);
        assertThat(columnClaiming(board, "Shipped"))
                .isEqualTo(after.columns().get(after.columns().size() - 1).name());
    }

    @Test
    @DisplayName("no status is ever unclaimed after a workflow edit, on any board")
    void noStatusIsEverUnclaimedAfterAWorkflowEdit() {
        // The invariant itself, computed rather than read — CLAUDE.md's rule. Reading one board and
        // finding the new status on it is what a per-board assertion proves, and this is the claim
        // the design actually makes.
        create(coarseBoard());

        replaceWorkflowAdding("Awaiting QA", IssueStatusKind.IN_PROGRESS);

        Set<UUID> all = new LinkedHashSet<>();
        statuses().forEach(status -> all.add(status.id()));
        List<BoardSummaryView> boards = boards();
        assertThat(boards).hasSize(2);

        for (BoardSummaryView board : boards) {
            List<UUID> claimed = new ArrayList<>();
            boardView(board.id()).columns()
                    .forEach(column -> column.statuses()
                            .forEach(status -> claimed.add(status.id())));
            assertThat(claimed)
                    .as("board '" + board.name() + "' claims every status of the project, and each "
                            + "of them exactly once")
                    .containsExactlyInAnyOrderElementsOf(all);
        }
    }

    // =====================================================================

    private void replaceWorkflowAdding(String name, IssueStatusKind kind) {
        WorkflowView current = scoped(() -> workflow.workflow(MANAGER, PROJECT).orElseThrow());
        List<IssueStatusView> statuses = new ArrayList<>(current.statuses());
        statuses.add(new IssueStatusView(null, name, kind, statuses.size()));

        GateResult<WorkflowService.Outcome> gate = scoped(() -> workflow.replace(MANAGER, PROJECT,
                new WorkflowView(PROJECT, statuses, current.types(), current.transitions())));

        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        assertThat(((GateResult.Allowed<WorkflowService.Outcome>) gate).value())
                .isInstanceOf(WorkflowService.Outcome.Replaced.class);
    }

    private String columnClaiming(UUID boardId, String statusName) {
        return boardView(boardId).columns().stream()
                .filter(column -> column.statuses().stream()
                        .anyMatch(status -> status.name().equals(statusName)))
                .map(BoardColumnView::name)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no column of board " + boardId + " claims " + statusName));
    }

    private UUID onlySeededBoard() {
        return boards().stream()
                .filter(board -> board.name().equals("Team board"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the seeded board is gone"))
                .id();
    }

    /** Six statuses folded into three columns, one of which defaults to a DONE-kind status. */
    private BoardInput coarseBoard() {
        List<IssueStatusView> statuses = statuses();
        return new BoardInput("Coarse", SwimlaneBy.NONE, List.of(
                column("Not started", statuses, "Backlog", "Ready"),
                column("In flight", statuses, "In Progress", "In Review", "Blocked"),
                column("Shipped", statuses, "Done")));
    }

    /**
     * Two columns, and <strong>no column defaults to a {@code DONE}-kind status</strong> — the
     * arrangement that makes the fallback observable at all. Done is claimed by the second column
     * and is not its default, so a new {@code DONE} status matches no column's default kind.
     */
    private BoardInput boardWithNoDoneDefault() {
        List<IssueStatusView> statuses = statuses();
        return new BoardInput("Two lanes", SwimlaneBy.NONE, List.of(
                column("Waiting", statuses, "Backlog", "Ready"),
                column("Moving", statuses, "In Progress", "In Review", "Blocked", "Done")));
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

    private static BoardView created(GateResult<BoardConfigService.Outcome> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        BoardConfigService.Outcome outcome =
                ((GateResult.Allowed<BoardConfigService.Outcome>) gate).value();
        assertThat(outcome).isInstanceOf(BoardConfigService.Outcome.Written.class);
        return ((BoardConfigService.Outcome.Written) outcome).view();
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
