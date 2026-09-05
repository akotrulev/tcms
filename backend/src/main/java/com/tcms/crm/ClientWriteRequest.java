package com.tcms.crm;

import java.util.UUID;

/**
 * The body of {@code POST /clients} and {@code PUT /clients/{clientId}}.
 *
 * <p><strong>A name is the only required field</strong> (story C1), and that is the product's rule
 * rather than a convenience: {@code client-new.html} says so on the form, and a client with a name
 * and nothing else is a complete client. Validation lives in {@link ClientService} rather than in
 * annotations here, as it does throughout this codebase, because two of the three rules are not
 * expressible as one — a blank name and an account manager who is not a member of this company are
 * both field errors on a body Bean Validation would accept.
 *
 * <p>{@code status} is nullable and the contract defaults it to {@code ACTIVE}; the service resolves
 * it into {@link ClientEdit}, which carries the enum. That split is where "what a caller may leave
 * out" stops being confusable with "what the row must have".
 *
 * <p><strong>There is no credential field, and there is nothing here that could become one.</strong>
 */
public record ClientWriteRequest(
        String name,
        String location,
        String industry,
        String notes,
        UUID accountManagerId,
        ClientStatus status) {
}
