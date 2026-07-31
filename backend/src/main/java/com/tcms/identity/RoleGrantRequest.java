package com.tcms.identity;

import java.util.UUID;

/**
 * The contract's {@code RoleAssignmentInput} on the wire — a grant, as asked for.
 *
 * <p>Every field is nullable here and validated in {@link RoleGrantService}, deliberately: a
 * {@code @NotNull} on {@link #roleId} would answer with Spring's own validation envelope rather
 * than the {@code Problem.errors} map the form lands its messages in, and the two shapes on one
 * endpoint is how a form ends up with a message it cannot place.
 *
 * <p><strong>There is no {@code companyId}.</strong> The company comes from the session's context
 * (ERD §0.1 point 2), and a field for it here would be the one input that could select another
 * client's tenancy. {@code ProjectWriteRequest} omits it for the same reason and
 * {@code ProjectCreateEndpointTest.theBodyCannotChooseTheCompany} pins the property.
 *
 * @param projectId <strong>null is meaningful, not missing.</strong> It selects COMPANY scope — a
 *                  grant across every project in the company, present and future — and therefore
 *                  selects which permission gates the request. The contract makes it
 *                  {@code required} and nullable so that widening is something the caller states
 *                  rather than something they fall into by omitting a field; this record cannot
 *                  tell the two apart, so {@link RoleGrantService} treats an omitted
 *                  {@code projectId} as the company-wide grant it asks for and the SPA always
 *                  sends it explicitly.
 */
public record RoleGrantRequest(UUID roleId, UUID userId, UUID teamId, UUID projectId) {
}
