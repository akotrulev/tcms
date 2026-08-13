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
 * <h2><del>The label is here because {@code DashboardTile} carries one on the wire</del> The label
 * is here because a refusal is a sentence</h2>
 *
 * <p><del>Everywhere else the SPA labels these from its own catalogue ({@code lib/labels.ts}, where
 * a new member is a compile error until it is labelled) and the server sends the constant — see
 * {@code CaseService.facet}. The dashboard is the one exception, and deliberately: a tile is a
 * generic {@code (key, label, count, filter)} shape precisely so a <em>new</em> signal computed
 * server-side renders as a working, drillable tile with no frontend change at all. That property is
 * worth one duplicated set of four English words, and {@code DashboardEndpointTest} pins them.</del>
 *
 * <p><strong>→ superseded in §180: the dashboard no longer carries a label, and the property that
 * paid for the duplication was not the one being bought.</strong> What made a tile extensible is
 * that its {@code filter} is opaque to the client, and that is untouched. The words were never
 * extensible: {@code HealthSignal} — which rides the same field — is a contract enum, so a new
 * member is a compile error in {@code lib/labels.ts} before it can render anywhere, and the grid
 * chip and the filter bar need the label regardless. Meanwhile the duplication did exactly what
 * {@code CaseService.facet} predicts: on the health half, six of ten drifted apart, and a tile
 * opened a case list that called the same thing something else.
 *
 * <p><strong>{@link #label()} survives that deletion, with one caller and a different justification
 * — {@code CaseBulkService.leadOnly}, which composes a refusal sentence:</strong> <em>"Only a test
 * lead can change a case that is already approved."</em> That is not the wire labelling an enum for
 * a client to render; it is the server writing prose, the way §149's {@code errors} value is the
 * server's sentence printed as it arrives. The client cannot compose it — the sentence is the
 * server's judgement about a row the caller could not see — so the word has to exist here. The test
 * of the distinction is what happens when the two disagree: two spellings of a <em>label</em> put
 * one status under two names on two screens, where a sentence is read once and quoted nowhere.
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

    /**
     * How this status is written inside a server-composed sentence — {@code CaseBulkService}'s
     * lead-only refusal, lower-cased into it, and nothing else since §180. Never on the wire as a
     * field, never parsed, never a key.
     */
    String label() {
        return label;
    }
}
