package com.tcms.repository;

/**
 * Where a generated case is in its review — {@code ai_proposal.status}.
 *
 * <h2>Three values, where §6w.6 sketched four</h2>
 *
 * <p>{@code SUPERSEDED} is not here, and its absence is {@code V39}'s rule (#50) applied at the
 * point the enum was written rather than a migration later: it describes a proposal displaced by a
 * newer one, and nothing in this product displaces anything — proposals are filed one at a time and
 * decided one at a time. A value no code can set is a filter option that always returns nothing,
 * and a reviewer who sees one reasonably concludes the feature is broken rather than absent.
 *
 * <p>It becomes real the day something generates a second proposal <em>for the same subject</em>,
 * which needs the generation job §6w.6 sketches and this milestone defers.
 */
public enum ProposalStatus {

    /** Filed, waiting for a person. The only status the review queue browses. */
    PENDING,

    /**
     * A person accepted it and the case exists — {@code accepted_case_id} names it, and the
     * database refuses this status without one.
     */
    ACCEPTED,

    /**
     * A person rejected it. <strong>Kept, not deleted.</strong> §6w.6: a generation profile whose
     * proposals are rejected 80% of the time is a measurable fact, and proposals that vanish when
     * dismissed make it unknowable.
     */
    REJECTED
}
