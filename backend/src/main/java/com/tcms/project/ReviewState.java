package com.tcms.project;

/** How a review stands. Open, or one of three ways to have ended — see {@link Review}. */
public enum ReviewState {

    /** Open. At most one per subject, backed by {@code V22}'s partial unique index. */
    IN_REVIEW,

    /** Signed off, by the person in {@code approved_by} at {@code approved_at}. */
    APPROVED,

    /** Sent back by somebody other than the submitter. A verdict, and it has a participation row. */
    REJECTED,

    /**
     * Taken back by the person who submitted it. <strong>Not a verdict</strong>, so it writes no
     * participation row: nobody judged the artifact, its author simply stopped asking. {@code V22}
     * added this value for exactly that reason.
     */
    WITHDRAWN
}
