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
 * @param overrideApprovalGate §6g's <em>"documented lead override for the 2am release"</em> (§82),
 *                             and meaningful on exactly one of the five targets — the
 *                             {@code DRAFT → ACTIVE} edge, which is the only one the gating rule
 *                             refuses. On every other move it is read and ignored, which is honest
 *                             rather than sloppy: the alternative is a second endpoint whose only
 *                             difference from this one is a boolean, and a client that had to know
 *                             which of the two to call would be holding a copy of
 *                             {@link PlanLifecycle#startsWithoutApproval}.
 *
 *                             <p>Like {@code TestRunInput}'s, it nominates no permission — it is
 *                             consulted only where §6g's rule would otherwise refuse, and only for
 *                             a caller already holding {@code plan:approve}, so it can neither
 *                             widen anybody's reach nor turn an acceptable request into a refused
 *                             one. Explicit rather than automatic, because a lead silently exempted
 *                             never discovers the policy their own project turned on.
 */
public record PlanStatusRequest(String status, Boolean overrideApprovalGate) {
}
