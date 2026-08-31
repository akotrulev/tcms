package com.tcms.tracker.board;

import java.util.UUID;

/**
 * The contract's {@code BoardSummary} — a row in the board picker.
 *
 * <p>{@code columnCount} rather than the columns themselves: the picker renders "4 columns" and
 * nothing else, and shipping the layout here would make switching boards a second read of something
 * already fetched.
 */
public record BoardSummaryView(UUID id, String name, int columnCount, SwimlaneBy swimlaneBy) {
}
