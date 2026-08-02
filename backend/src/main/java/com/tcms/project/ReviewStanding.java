package com.tcms.project;

/**
 * Where an artifact sits <em>relative to review</em> — the only thing this module needs to know
 * about three different status enums it cannot see.
 *
 * <p>{@code LifecycleStatus} (a case's and a suite's, four values) and {@code PlanStatus} (five,
 * including an execution phase the other two do not have) live in {@code repository} and
 * {@code execution}, modules this one must not depend on. Each maps its own enum onto these three
 * values — one mapping per enum, in the ladder that already owns that enum's rules — and
 * {@link ReviewService} takes it from there.
 *
 * <h2>Three values, and the third is the one that would be tempting to drop</h2>
 *
 * <p>Only the edges <em>out of</em> {@link #IN_REVIEW} change a review, and a caller that reported
 * nothing but "in review or not" would still drive the open and the close correctly — but not the
 * <em>kind</em> of close. A review that ended because someone signed the artifact off is not the
 * same record as one that ended because it was sent back, and collapsing them puts an approval and
 * a rejection in the same row shape (see {@link Review}).
 *
 * <p>Everything else is {@link #OUTSIDE_REVIEW}: a draft, a deprecated case, a plan being executed,
 * a closed release. This enum deliberately does not model them, because a review has no opinion on
 * any of it — {@code ReviewService} writes nothing at all for a move between two of them.
 */
public enum ReviewStanding {

    /** Not in review and not signed off — drafted, retired, active, closed. */
    OUTSIDE_REVIEW,

    /** Under review. At most one review row may be open for a subject in this standing. */
    IN_REVIEW,

    /** Signed off by someone holding the approve key. */
    APPROVED
}
