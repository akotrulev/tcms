package com.tcms.tracker.board;

import java.util.UUID;

/**
 * The contract's {@code WipBreach} — a column over its limit, <strong>reported and never
 * enforced</strong>.
 *
 * <p>Story B3: "the drop is accepted and the column header shows the breach". A limit that refused
 * drops is a limit people route around by not using the board, and then the board stops describing
 * the work — which costs more than the limit was ever worth.
 *
 * <p>A column at <em>exactly</em> its limit is not in breach. Off-by-one here puts a permanent
 * warning on any team that actually works to its limit, and a warning that is always on is one
 * nobody reads.
 */
public record WipBreachView(UUID columnId, int limit, int count) {
}
