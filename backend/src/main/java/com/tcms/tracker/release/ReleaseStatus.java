package com.tcms.tracker.release;

/**
 * Whether a version has shipped.
 *
 * <p>Two members and no third. {@code CANCELLED} was considered and refused: a release nobody
 * shipped is deleted, and the issues that pointed at it fall back to unscheduled — which is the
 * state they were in before anybody planned them, and is what {@code ON DELETE SET NULL} on
 * {@code issue.release_id} already produces. A third member would be a row that renders in every
 * list and means "ignore this", which is the shape a person then has to filter out everywhere.
 *
 * <p>The transition is one-way in M3: {@code ReleaseService#publish} moves {@code PLANNED} to
 * {@code RELEASED} and nothing moves it back. Un-publishing needs a decision about what happens to
 * {@code released_at} — cleared, and the shipment is unrecorded; kept, and the status contradicts
 * it — and no screen has asked for one.
 */
public enum ReleaseStatus {
    PLANNED,
    RELEASED
}
