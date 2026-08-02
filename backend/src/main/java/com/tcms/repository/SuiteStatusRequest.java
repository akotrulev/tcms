package com.tcms.repository;

/**
 * The body of {@code PUT /projects/{projectId}/suites/{suiteId}/status} — {@code SuiteStatusInput}.
 *
 * <p>A {@code String} rather than a {@link LifecycleStatus}, for {@code PlanStatusRequest}'s and
 * {@code RunStatusRequest}'s reason: Jackson's failure on an unknown enum constant is a 400 raised
 * in the message converter, before any handler runs, with a body naming the Java type and its
 * constants. Parsed in the service instead, the same mistake is a field error the form can land on.
 *
 * @param status one of {@code DRAFT}, {@code IN_REVIEW}, {@code APPROVED}, {@code DEPRECATED} — an
 *               absolute target, not a verb. Which of them a given suite may move to, and what each
 *               move costs in permissions, is {@link LifecycleLadder}'s question and not the
 *               client's.
 */
public record SuiteStatusRequest(String status) {
}
