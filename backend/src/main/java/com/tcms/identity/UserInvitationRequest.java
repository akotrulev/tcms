package com.tcms.identity;

/**
 * The contract's {@code UserInvitationInput} on the wire — an invitation, as asked for.
 *
 * <p>Both fields are nullable here and validated in {@link MembershipInviteService}, for the
 * reason {@link RoleGrantRequest} states at length: a bean-validation annotation would answer
 * with Spring's own envelope rather than the {@code Problem.errors} map the form lands its
 * messages in, and two shapes on one endpoint is how a form ends up with a message it cannot
 * place.
 *
 * <p><strong>There is no {@code companyId} and no {@code roleId}.</strong> The company comes from
 * the session's context (ERD §0.1 point 2). The role is absent on purpose rather than for want of
 * a field: inviting somebody and deciding what they may do are two acts with two different gates
 * — {@code company:members} here, {@code project:members} at {@code POST /role-assignments} — and
 * folding them together would let one permission do the work of both.
 *
 * @param displayName this company's name for the person (§6u), stored on the membership. Never
 *                    {@link AppUser#getCanonicalName()}, which belongs to whichever company
 *                    created the account and may be another client's.
 */
public record UserInvitationRequest(String email, String displayName) {
}
