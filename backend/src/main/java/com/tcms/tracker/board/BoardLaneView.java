package com.tcms.tracker.board;

import com.tcms.tracker.CompletionView;
import java.util.List;

/**
 * The contract's {@code BoardLane}.
 *
 * <p>One lane when the board's {@code swimlaneBy} is {@code NONE}, whose {@link #key} is null. The
 * catch-all lane — "No epic", "Unassigned", "No label" — is a real lane with a null key and is
 * <strong>last</strong>, because a story without an epic is a normal story rather than an error.
 *
 * @param completion the lane's roll-up, so collapsing it to one card keeps its percentage (B3).
 *                   Reuses M1's {@code IssueCompletion} — done over the counted total, with its
 *                   basis on the wire (D8), so a lane header can say "by estimate" or "by count"
 */
public record BoardLaneView(
        String key,
        String label,
        List<BoardIssueCardView> cards,
        CompletionView completion) {
}
