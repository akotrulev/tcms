package com.tcms.execution;

/**
 * A test plan's lifecycle — {@code TestPlanSummary.status} in {@code docs/specs/openapi.yaml},
 * and the {@code CHECK} constraint on {@code test_plan.status} in {@code V32}.
 *
 * <p>Three values, and the ladder is {@code DRAFT → IN_REVIEW → APPROVED}. A plan is an
 * <em>outline of what must be tested</em>; whether that testing is under way is answered by its
 * runs, and {@code test_run} has carried {@code NOT_STARTED → IN_PROGRESS → COMPLETED → ABORTED}
 * since {@code V1}.
 *
 * <h2>Two values were removed in §97 (M7), and the reasoning that put them here is kept</h2>
 *
 * <p>~~{@code ACTIVE} — being executed against right now, which is a state no test case has.~~<br>
 * ~~{@code CLOSED} — the release was executed and is over; {@code closed_at} records when.~~
 *
 * <p>→ <strong>superseded 2026-08-05.</strong> {@link PlanStatusService} wrote
 * {@code plan.started}, {@code plan.reopened} and {@code plan.closed} and filled
 * {@code closed_at} — every one of those describes a plan <em>being run</em>, which is the
 * execution's business. Plan-{@code ACTIVE} duplicated run-{@code IN_PROGRESS} one level up, and
 * two places to ask "is this release under test" is how they come to disagree.
 *
 * <p><strong>{@code IN_REVIEW} stays, and it is not a third state the author manages</strong> — it
 * is the transit between the two the user named. Cutting it would remove plan review from the
 * product: {@code review.subject_type} admits {@code PLAN}, and §77's {@code reviewEnabledPlan},
 * §79's comments, §80's assignment and §81's reviewer inbox all operate on it. It would also
 * reopen what {@link PlanLifecycle} closed on §6g's authority — <em>"the state machine has exactly
 * one gate"</em> — by making approval reachable with no gate before it.
 *
 * <p>Still distinct from {@code repository.LifecycleStatus} despite sharing three of its names:
 * that enum has {@code DEPRECATED}, which a plan does not, and merging them would put a value on
 * one editor's dropdown that means nothing there.
 */
public enum PlanStatus {

    DRAFT,
    IN_REVIEW,
    APPROVED
}
