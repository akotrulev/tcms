package com.tcms.execution;

/**
 * {@code PlanArchiveRequest} in {@code docs/specs/openapi.yaml} — put a plan away, or bring it
 * back.
 *
 * <h2>A body with one boolean, not two endpoints and not a bare {@code POST}</h2>
 *
 * <p>{@code POST …/archive} + {@code POST …/unarchive} would be two paths, two permissions to keep
 * in step and two audit verbs for one fact. One {@code PUT} naming the desired state is the shape
 * every other "set this to that" write in this product already has ({@code PUT …/status},
 * {@code PUT …/quarantine}, {@code PUT …/assignee}), and it is idempotent by construction: sending
 * {@code true} twice asks for a state the plan is already in, which is not an error.
 *
 * @param archived required. Absent is a 400 rather than a default, because both defaults are a
 *                 guess at what the caller wanted done to a plan, and one of them hides a release's
 *                 plan from its team.
 */
public record PlanArchiveRequest(Boolean archived) {
}
