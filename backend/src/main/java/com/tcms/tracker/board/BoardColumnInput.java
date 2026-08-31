package com.tcms.tracker.board;

import java.util.List;
import java.util.UUID;

/**
 * One column of a submitted board.
 *
 * <p>{@link #id} present edits an existing column, absent creates one. There is no {@code position}
 * field — the array index is the position, so the client cannot submit two columns claiming one.
 */
public record BoardColumnInput(
        UUID id,
        String name,
        Integer wipLimit,
        List<UUID> statusIds,
        UUID defaultStatusId) {
}
