package com.tcms.integration.tracker;

/**
 * {@code TrackerIssueAvailability} in {@code docs/specs/openapi.yaml} — why a preview is or is not
 * there.
 *
 * <p><strong>Three states, and the middle one is the only distinction this API draws</strong>
 * (004 D16). {@link #NOT_CONNECTED} is a fact about the <em>viewer</em> — they have no identity for
 * this host — and it earns a call to action on their own account page, which is the one place the
 * remedy lives.
 *
 * <p>{@link #UNAVAILABLE} folds "the tracker is down", "the token expired", "the token was refused"
 * and "this person cannot see that issue" into one word. That is deliberate and it is the security
 * half: telling them apart would make the preview an oracle for what exists in a tracker the viewer
 * cannot read — ask for an id, read which refusal comes back, learn whether the issue is there.
 */
public enum TrackerIssueAvailability {
    AVAILABLE,
    NOT_CONNECTED,
    UNAVAILABLE
}
