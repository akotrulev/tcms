package com.tcms.execution;

/**
 * {@code TestPlanCloneInput} on the wire — one field, and the shortness is the design.
 *
 * <p>Everything else about the new plan comes from the one being cloned: its description, its
 * milestone, its whole scope including §97's sections and the tag arm, both prose fields and both
 * criteria lists. A clone request that accepted any of them would be {@link PlanUpdateRequest} with
 * an extra id, and the two would then be two answers to "what may a client set on a new plan".
 *
 * <p>{@code name} is required and the server invents no {@code "Copy of "} — see
 * {@link RunCloneRequest}, which carries the argument for all three clone endpoints. It is validated
 * by {@link PlanEdit}, so "what is an acceptable plan name" keeps having one answer.
 *
 * <p><strong>There is no target-project field</strong>, and there is no destination of any kind: a
 * plan's scope names suites and cases in <em>its own</em> project, so a cross-project clone would
 * have to re-resolve every id against the target's repository and revalidate its classification
 * against the target's vocabularies (§6d). §6v names that as its own problem and §129 does not solve
 * it — the path is {@code /projects/{projectId}/plans/{planId}/clone}, so the target is structurally
 * the source's project rather than a refusal somebody has to remember to write.
 */
public record PlanCloneRequest(String name) {
}
