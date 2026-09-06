package com.tcms.tracker.board;

import com.tcms.tracker.IssueStatusView;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code BoardCoverage} — the status × board matrix behind Settings → Boards.
 *
 * <h2>It replaced a rule, and that is the whole reason it exists</h2>
 *
 * <p>Until 021 R7 a board had to claim <em>every</em> status of its project, which made every board
 * show the whole lifecycle and is exactly why one Team board was the only board anybody made. The
 * half of that rule Postgres holds — {@code uq_board_column_status}, at most one column per status
 * per board — stays, because it is what makes a card's column unambiguous. The half that forced
 * full coverage is gone, and this read is what took its place: it <strong>warns</strong> where the
 * rule <strong>refused</strong>.
 *
 * @param uncovered the statuses no board claims, in workflow order. Empty for a freshly seeded
 *                  project, because the three boards V13 seeds claim all eleven between them; it
 *                  fills the day somebody deletes a column
 */
public record BoardCoverageView(
        UUID projectId, List<BoardCoverageRowView> rows, List<IssueStatusView> uncovered) {
}
