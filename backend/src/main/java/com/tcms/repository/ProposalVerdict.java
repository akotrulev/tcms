package com.tcms.repository;

/**
 * What the proposer claims about the proposal's grounding — {@code ai_proposal.verdict}.
 *
 * <p>A claim, not a finding. What it is <em>worth</em> is {@link VerdictSource}'s question, and the
 * two are separate columns for the reason §6w.1a gives at length: a verdict with no provenance is a
 * number any caller can inflate.
 */
public enum ProposalVerdict {

    /** Every step traces to something in the source material. */
    GROUNDED,

    /** Some of it traces; the rest is asserted. The grounding payload says which is which. */
    PARTIAL,

    /** The proposer's own critic found invented content. Filed anyway — see the class note below. */
    HALLUCINATED,

    /**
     * <strong>Nobody checked, and this is the honest default rather than an error state</strong>
     * (§6w.6). It is what every proposal carries on rungs 0 and 1, and what a rung-2 proposal falls
     * back to when the critic times out or is unset. The review surface renders it as <em>"not
     * independently checked — review against the spec"</em>, which is exactly the instruction a
     * reviewer needs and exactly what a human-only workflow has always required.
     */
    UNVERIFIED
}
