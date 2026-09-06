package com.tcms.tracker.board;

import com.tcms.tracker.IssueStatusView;
import java.util.List;

/**
 * The contract's {@code BoardCoverageRow} — one status, and every board that shows it.
 *
 * @param claims in board position order, so the row reads left to right the way the Work strip does.
 *               <strong>Empty is the interesting value</strong>: a status no board claims is a
 *               status whose cards are on no board at all, which is the failure 021 R7 traded a
 *               refusal for a report of
 */
public record BoardCoverageRowView(IssueStatusView status, List<BoardClaimView> claims) {
}
