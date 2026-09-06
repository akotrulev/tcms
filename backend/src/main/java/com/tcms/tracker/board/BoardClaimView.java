package com.tcms.tracker.board;

import java.util.UUID;

/**
 * The contract's {@code BoardClaim} — one board showing one status, and the column it shows it in.
 *
 * <p>Both names travel with both ids because the coverage table is read, not navigated: a cell says
 * "Dev board · In progress" and a client that had to resolve two ids to render it would fetch every
 * board of the project to draw one screen.
 */
public record BoardClaimView(UUID boardId, String boardName, UUID columnId, String columnName) {
}
