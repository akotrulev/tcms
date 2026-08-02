package com.tcms.identity;

/**
 * The contract's {@code TeamMemberInput} — the membership state being asserted for one person in
 * one team by {@code PUT /teams/{teamId}/members/{userId}}.
 *
 * <p>The person is in the path, not the body: the URL names the membership, which is what makes the
 * write idempotent by construction rather than by a check.
 *
 * @param lead whether they lead the team. <strong>Boxed, and validated non-null</strong> — a
 *             primitive {@code boolean} would read a body that omits the key as {@code false}, so
 *             a client that forgot the field would silently <em>demote</em> a lead and get a 200
 *             saying it worked. The contract declares it required; this is where that is enforced.
 *             <p>A lead is a member with a flag and grants nothing. Capability in this product comes
 *             from a role assignment, never from leading a team — the same separation
 *             {@link Team}'s header makes about the parent edge.
 */
public record TeamMemberInput(Boolean lead) {
}
