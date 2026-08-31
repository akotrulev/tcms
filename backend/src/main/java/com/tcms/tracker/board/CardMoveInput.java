package com.tcms.tracker.board;

import java.util.UUID;

/**
 * {@code POST /boards/{boardId}/cards/{key}/move} — the column, never a status.
 *
 * <p>Which status a drop into that column <em>means</em> is the column's decision and not the
 * client's (D2): a stakeholder board's "In flight" claims three, and a client picking one would
 * have to know the mapping, which is the coupling this endpoint exists to remove.
 */
public record CardMoveInput(UUID toColumnId) {
}
