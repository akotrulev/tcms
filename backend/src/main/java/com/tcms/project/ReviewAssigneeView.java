package com.tcms.project;

import java.time.Instant;
import java.util.UUID;

/**
 * One request to review, as the contract's {@code ReviewAssignee}.
 *
 * <p>Exactly one of {@link #user} and {@link #team} is non-null on a well-formed row — {@code V25}'s
 * {@code num_nonnulls(...) = 1} check — and the client branches on which. The pair is served rather
 * than a flattened {@code (kind, id, name)} triple because the two carry different affordances: a
 * person's row can say "that's you", a team's cannot, and collapsing them would make the client
 * re-derive from a string which of the two it is holding.
 *
 * @param id         this request's own id, which is what {@code DELETE} addresses. Not the
 *                   assignee's — one person may be asked to review several artifacts, and a client
 *                   that withdrew by assignee id would have to send the review id too and could
 *                   still name a pairing that never existed
 * @param user       the person asked, or null when {@code team} names a team instead. Also null —
 *                   with a non-null {@code team} — never: an unresolvable <em>person</em> yields a
 *                   row with both null, which is what an offboarded account looks like, and the
 *                   client renders it as a request nobody can action rather than dropping it
 * @param team       the team asked, or null when {@code user} names a person. §6g: any member of it
 *                   may action the request, and the members are deliberately not expanded — see
 *                   {@link TeamRefView}
 * @param assignedBy who asked. Null for an unattributable request, which {@code assigned_by}
 *                   permits for the reason every actor column in this schema does (§66)
 */
public record ReviewAssigneeView(
        UUID id,
        UserRefView user,
        TeamRefView team,
        UserRefView assignedBy,
        Instant assignedAt) {
}
