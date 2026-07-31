package com.tcms.execution;

/**
 * A test plan's lifecycle — {@code TestPlanSummary.status} in {@code docs/specs/openapi.yaml},
 * and the {@code CHECK} constraint on {@code test_plan.status} in {@code V1}.
 *
 * <p>Distinct from {@code repository.LifecycleStatus} despite sharing four of its names: a plan
 * that is {@code ACTIVE} is being executed against right now, which is a state no test case has.
 * Merging them would put a value on the case editor's dropdown that means nothing there.
 */
public enum PlanStatus {

    DRAFT,
    IN_REVIEW,
    APPROVED,
    /** Being executed against. Runs are created from a plan in this state. */
    ACTIVE,
    CLOSED
}
