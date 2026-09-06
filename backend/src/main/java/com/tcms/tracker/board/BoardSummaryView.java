package com.tcms.tracker.board;

import java.util.UUID;

/**
 * The contract's {@code BoardSummary} — a row in the board picker, and a segment of the Work strip.
 *
 * <p>{@code columnCount} rather than the columns themselves: the picker renders "4 columns" and
 * nothing else, and shipping the layout here would make switching boards a second read of something
 * already fetched.
 *
 * @param statusCount how many of the project's statuses this board claims. It exists because a
 *                    board claims a <strong>subset</strong> since 021 R7 — the picker reads "5 of
 *                    11", and the whole matrix is {@link BoardCoverageService}'s
 */
public record BoardSummaryView(
        UUID id, String name, int columnCount, SwimlaneBy swimlaneBy, int statusCount) {
}
