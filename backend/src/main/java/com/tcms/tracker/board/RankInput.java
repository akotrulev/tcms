package com.tcms.tracker.board;

/**
 * {@code PUT /issues/{key}/rank} — <strong>neighbour-relative, never an absolute rank</strong>.
 *
 * <p>Two people dragging at once each compute a midpoint from a list they read seconds ago, so
 * sending the number races and sending the neighbours does not: the server reads both ranks inside
 * the transaction that writes. <strong>Exactly one of the two</strong> — both, or neither, is a 400,
 * which is a cross-field rule no {@code required} array can spell, and is why the contract's is
 * empty.
 */
public record RankInput(String afterKey, String beforeKey) {
}
