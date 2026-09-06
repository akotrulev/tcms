package com.tcms.work;

import java.util.UUID;

/**
 * One status a card may move to — the contract's {@code IssueStatusRef}, served here as well as by
 * {@code tracker.IssueStatusView}.
 *
 * <p>Two records for one schema, for the reason {@code Priority} has had three: {@code work} may not
 * import {@code tracker}, so a shape both modules put on the wire has to be declared in both.
 * {@code ContractConventionTest} names both owners of this schema and compares each to it, which is
 * what keeps them identical — a rename on one side is a failure rather than a divergence somebody
 * finds from the client end.
 *
 * <p>{@code kind} is a String here and an enum there, for {@link CardStatus}' reason.
 *
 * @param blocking 021 D-b, and it travels here for the same reason the rest of the shape does: the
 *                 two records serve ONE schema, so a component added to one and not the other is a
 *                 property every TypeScript consumer reads as absent on half the endpoints that
 *                 send it. This board offers moves; a client that could not tell which target is
 *                 the blocking one could not draw the chip that says what a drop there means
 */
public record BoardStatusRef(UUID id, String name, String kind, int position, boolean blocking) {
}
