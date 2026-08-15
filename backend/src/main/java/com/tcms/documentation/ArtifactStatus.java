package com.tcms.documentation;

/**
 * An artifact's lifecycle — plan §6n.
 *
 * <p>Absent from {@code ArtifactInput} on purpose (plan §6g, applied for the third time in this
 * module): a state an ordinary create could set is an approval its author granted themselves. Every
 * registration begins {@link #ACTIVE}; ~~the other two are the outcomes of acts that do not exist
 * yet~~ <strong>→ superseded 2026-08-15 (§209), when both acts were built.</strong> The sentence had
 * been true since {@code V1} and read as a design note rather than as a gap, which is what let it
 * survive: the delete's own refusal already advised superseding, and the upload form already told
 * people to supersede rather than re-upload, so three shipped surfaces named an act the product
 * could not perform.
 *
 * <h2>The two are reached differently, and that asymmetry is the model</h2>
 *
 * <p>{@link #ARCHIVED} is a <em>judgement</em> somebody makes about one row — this is no longer
 * relevant — so it is chosen, on {@code PUT /projects/{projectId}/artifacts/{artifactId}/status},
 * and it is reversible. {@link #SUPERSEDED} is a <em>fact about another row</em>: it holds exactly
 * when {@code artifact.superseded_by_id} names a replacement, so it is never chosen and never left.
 * Offering it on the status endpoint would let a caller assert a replacement that does not exist,
 * which is a claim the register cannot back — the same reason {@code status} is absent from the
 * create.
 */
public enum ArtifactStatus {

    /** Current. The state every registration starts in, and the only one a create can write. */
    ACTIVE,

    /**
     * Replaced by a newer artifact, which {@code artifact.superseded_by_id} names.
     *
     * <p>Superseding <strong>keeps the old row reachable</strong> rather than deleting it (plan
     * §6n), because sign-off records are write-once: a correction to one is a new record pointing at
     * the old, never an edit of it. The audit trail is the whole reason the register exists.
     *
     * <p>Written by registering the replacement and by nothing else, so the status and the pointer
     * cannot disagree. It does <strong>not</strong> make the row undeletable — that is
     * {@code artifact.write_once}'s job, and conflating the two would make a mis-registered link
     * permanent the moment somebody corrected it.
     */
    SUPERSEDED,

    /**
     * No longer relevant, kept for the record. Not deleted, for {@link #SUPERSEDED}'s reason.
     *
     * <p>Reversible, unlike its neighbour: nothing outside the row itself asserts it, so restoring
     * is the same act back. A caller may not archive a {@link #SUPERSEDED} row — there is one way
     * out of circulation per reason, and two overlapping ones would make "why is this not current"
     * unanswerable from the column.
     */
    ARCHIVED
}
