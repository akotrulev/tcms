package com.tcms.tracker.board;

import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code Board} — configuration only.
 *
 * <p>The cards are {@code GET /boards/{boardId}/cards} and not this: columns change when somebody
 * edits them and cards change on every transition anywhere in the project, so one combined read
 * would relayout the board on every card move (D-h).
 */
public record BoardView(
        UUID id,
        UUID projectId,
        String name,
        SwimlaneBy swimlaneBy,
        List<BoardColumnView> columns) {
}
