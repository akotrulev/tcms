package com.tcms.tracker.board;

import com.tcms.project.ProjectService;
import com.tcms.tracker.IssueStatus;
import com.tcms.tracker.IssueStatusRepository;
import com.tcms.tracker.IssueStatusView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /projects/{projectId}/boards/coverage} — which boards show each status.
 *
 * <h2>A read that warns, where there used to be a rule that refused (021 D-e)</h2>
 *
 * <p>Story B2 said <em>every status is claimed by exactly one column</em>, and that was two
 * statements. <strong>At most one</strong> is {@code uq_board_column_status} and stays — it is what
 * makes a card's column unambiguous and a drop resolvable. <strong>At least one</strong> was
 * {@code BoardConfigService}'s, and it is gone: it is the rule that forced every board to show the
 * whole lifecycle, which is why one Team board was the only board anybody ever made.
 *
 * <p>What that rule protected against is real — a card in a status no board claims is a card
 * nobody sees — so the protection moved here rather than evaporating. The settings screen renders
 * {@link BoardCoverageView#rows} as the matrix and {@link BoardCoverageView#uncovered} as a warning
 * row. The seeded boards cover every status, so on day one that row is empty; it exists for the day
 * somebody deletes a column.
 *
 * <h2>Gated on visibility alone</h2>
 *
 * <p>{@code board:read} is held by every one of the six project roles — this is a fact about a
 * project's own configuration, not about anybody's work — so a project grant already implies it and
 * a 403 arm would be unreachable. The two configuration reads next door make the same choice.
 */
@Service
public class BoardCoverageService {

    private final ProjectService projects;
    private final IssueStatusRepository statuses;
    private final BoardRepository boards;
    private final BoardColumnRepository columns;
    private final BoardColumnStatusRepository claims;

    public BoardCoverageService(
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

    @Transactional(readOnly = true)
    public Optional<BoardCoverageView> coverageOf(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project -> matrixOf(project.getId()));
    }

    // =====================================================================

    /**
     * Statuses in workflow order × boards in Work-strip order.
     *
     * <p>Built board-major and then read status-major, which is why the claims of one status come
     * out in board order without a sort: the outer loop walks {@link
     * BoardRepository#findByProjectIdOrderByPositionAscNameAsc} and appends, so each row's list is
     * already in the order the strip draws.
     */
    private BoardCoverageView matrixOf(UUID projectId) {
        Map<UUID, List<BoardClaimView>> byStatus = new LinkedHashMap<>();
        for (IssueStatus status : statuses.findByProjectIdOrderByPositionAsc(projectId)) {
            byStatus.put(status.getId(), new ArrayList<>());
        }

        for (Board board : boards.findByProjectIdOrderByPositionAscNameAsc(projectId)) {
            Map<UUID, BoardColumn> columnsById = new LinkedHashMap<>();
            columns.findByBoardIdOrderByPositionAsc(board.getId())
                    .forEach(column -> columnsById.put(column.getId(), column));

            for (BoardColumnStatus claim : claims.findByBoardId(board.getId())) {
                List<BoardClaimView> row = byStatus.get(claim.getStatusId());
                BoardColumn column = columnsById.get(claim.getColumnId());
                // A claim whose status belongs to another project, or whose column has gone, is not
                // a row this matrix can draw. Neither is reachable through the configuration
                // endpoint — validateClaims refuses a foreign status and the claim rows cascade
                // with the column — so this skips rather than throwing.
                if (row != null && column != null) {
                    row.add(new BoardClaimView(
                            board.getId(), board.getName(), column.getId(), column.getName()));
                }
            }
        }

        List<BoardCoverageRowView> rows = new ArrayList<>();
        List<IssueStatusView> uncovered = new ArrayList<>();
        for (IssueStatus status : statuses.findByProjectIdOrderByPositionAsc(projectId)) {
            IssueStatusView view = IssueStatusView.of(status);
            List<BoardClaimView> claimed = byStatus.getOrDefault(status.getId(), List.of());
            rows.add(new BoardCoverageRowView(view, List.copyOf(claimed)));
            if (claimed.isEmpty()) {
                uncovered.add(view);
            }
        }
        return new BoardCoverageView(projectId, List.copyOf(rows), List.copyOf(uncovered));
    }
}
