package com.tcms.integration.tracker;

/**
 * {@code TrackerConnectionState} in {@code docs/specs/openapi.yaml} — <strong>the whole of what
 * {@code GET …/tracker/connected-members} may say about a person</strong>, per host (004 D19).
 *
 * <p>D19 was taken over a stated counter-argument: a lead learns which of their team hold a Jira or
 * ADO account, which is personal information those people gave TCMS for their own use. It was
 * overridden knowingly, because <em>"why can nobody on this team file defects?"</em> is unanswerable
 * without it — and the override is bounded by this enum being the entire answer.
 *
 * <p>{@link #NEEDS_ATTENTION} means "the last call failed, or the credential has expired" and
 * nothing finer. The sentence explaining it belongs on the affected person's own account page,
 * where the credential is theirs to read about; it is not a field here and there is no member that
 * would carry it.
 */
public enum TrackerConnectionState {
    CONNECTED,
    NEEDS_ATTENTION,
    NOT_CONNECTED
}
