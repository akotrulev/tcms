package com.tcms.repository;

/**
 * The authoring lifecycle of a suite or a case — {@code LifecycleStatus} in
 * {@code docs/specs/openapi.yaml}, and the {@code lifecycle_status} check constraint in
 * {@code V1}.
 *
 * <p>Distinct from an <em>execution</em> status ({@link com.tcms.project.StatusKind}): this says
 * whether the asset is written and agreed, not how it fared when it was run. The two are easy to
 * conflate because both are "status" on a case, and conflating them is how a deprecated case
 * disappears from a pass rate it should still be counted in.
 *
 * <p>Closed and not project-configurable, unlike the execution statuses — the review workflow
 * (plan §6g) branches on these four by name, so a client adding a fifth would be adding a state no
 * transition knows about.
 */
public enum LifecycleStatus {

    DRAFT,
    IN_REVIEW,
    APPROVED,
    DEPRECATED
}
