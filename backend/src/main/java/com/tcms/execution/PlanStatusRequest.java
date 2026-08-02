package com.tcms.execution;

/**
 * The body of {@code PUT /projects/{projectId}/plans/{planId}/status} — {@code PlanStatusInput}.
 *
 * <p>A {@code String} rather than a {@link PlanStatus}, for {@link RunStatusRequest}'s reason:
 * Jackson's failure on an unknown enum constant is a 400 raised in the message converter, before any
 * handler runs, with a body naming the Java type and its constants. Parsed in the service instead,
 * the same mistake is a field error the form can land on.
 *
 * @param status one of {@code DRAFT}, {@code IN_REVIEW}, {@code APPROVED}, {@code ACTIVE},
 *               {@code CLOSED} — an absolute target, not a verb. Which of them a given plan may
 *               move to, and what each move costs in permissions, is {@link PlanLifecycle}'s
 *               question and not the client's.
 */
public record PlanStatusRequest(String status) {
}
