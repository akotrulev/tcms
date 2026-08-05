package com.tcms.execution;

/**
 * The body of {@code PUT /projects/{projectId}/plans/{planId}/status} — {@code PlanStatusInput}.
 *
 * <p>A {@code String} rather than a {@link PlanStatus}, for {@link RunStatusRequest}'s reason:
 * Jackson's failure on an unknown enum constant is a 400 raised in the message converter, before any
 * handler runs, with a body naming the Java type and its constants. Parsed in the service instead,
 * the same mistake is a field error the form can land on.
 *
 * <h2>{@code overrideApprovalGate} was here and is gone (M7)</h2>
 *
 * <p>~~§6g's <em>"documented lead override for the 2am release"</em> (§82), meaningful on exactly
 * one of the five targets — the {@code DRAFT → ACTIVE} edge, which is the only one the gating rule
 * refuses.~~ → <strong>superseded 2026-08-05.</strong> §97 removed {@code ACTIVE}, so the field's
 * one meaningful target no longer exists and every remaining move would read and ignore it. A
 * boolean that is always ignored is worse than an absent one: a client keeps sending it and the
 * next reader has to prove it does nothing. §6g's override survives where its rule does, on
 * {@code TestRunInput.overrideApprovalGate}.
 *
 * @param status one of {@code DRAFT}, {@code IN_REVIEW} or {@code APPROVED} — an absolute target,
 *               not a verb. Which of them a given plan may move to, and what each move costs in
 *               permissions, is {@link PlanLifecycle}'s question and not the client's.
 */
public record PlanStatusRequest(String status) {
}
