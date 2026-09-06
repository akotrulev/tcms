package com.tcms.tracker.board;

import java.util.UUID;

/**
 * The contract's {@code CardElsewhere} — descendants of this card that <em>this</em> board does not
 * show, and the board that does (021 §2.5).
 *
 * <h2>The not-shown-here set, never total minus shown</h2>
 *
 * <p>A status may be claimed by two boards — that is what makes a shared column a hand-off — so
 * "shown here" and "elsewhere" OVERLAP, and the difference of two totals is therefore not the
 * count anybody wants. A story with four tasks, one of them in {@code Ready for test} which both
 * the Dev and QA boards claim, reads {@code 1 on QA board} on Dev only if that task is NOT also on
 * Dev. Counting the descendants this board does not claim, then grouping those by the boards that
 * do, is the arithmetic that survives a shared column; subtracting is the arithmetic that quietly
 * does not.
 */
public record CardElsewhereView(UUID boardId, String boardName, int count) {
}
