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
 *
 * <h2>The label is here because {@code DashboardTile} carries one on the wire</h2>
 *
 * <p>Everywhere else the SPA labels these from its own catalogue ({@code lib/labels.ts}, where a new
 * member is a compile error until it is labelled) and the server sends the constant — see
 * {@code CaseService.facet}. The dashboard is the one exception, and deliberately: a tile is a
 * generic {@code (key, label, count, filter)} shape precisely so a <em>new</em> signal computed
 * server-side renders as a working, drillable tile with no frontend change at all. That property is
 * worth one duplicated set of four English words, and {@code DashboardEndpointTest} pins them.
 */
public enum LifecycleStatus {

    DRAFT("Draft"),
    IN_REVIEW("In review"),
    APPROVED("Approved"),
    DEPRECATED("Deprecated");

    private final String label;

    LifecycleStatus(String label) {
        this.label = label;
    }

    /** How this status is written on a dashboard tile. Never parsed, never a key. */
    String label() {
        return label;
    }
}
