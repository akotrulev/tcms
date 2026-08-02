package com.tcms.documentation;

/**
 * An artifact's lifecycle — plan §6n.
 *
 * <p>Absent from {@code ArtifactInput} on purpose (plan §6g, applied for the third time in this
 * module): a state an ordinary create could set is an approval its author granted themselves. Every
 * registration begins {@link #ACTIVE}; the other two are the outcomes of acts that do not exist yet.
 */
public enum ArtifactStatus {

    /** Current. The state every registration starts in, and the only one this slice can write. */
    ACTIVE,

    /**
     * Replaced by a newer artifact, which {@code artifact.superseded_by_id} names.
     *
     * <p>Superseding <strong>keeps the old row reachable</strong> rather than deleting it (plan
     * §6n), because sign-off records are write-once: a correction to one is a new record pointing at
     * the old, never an edit of it. The audit trail is the whole reason the register exists.
     */
    SUPERSEDED,

    /** No longer relevant, kept for the record. Not deleted, for {@link #SUPERSEDED}'s reason. */
    ARCHIVED
}
