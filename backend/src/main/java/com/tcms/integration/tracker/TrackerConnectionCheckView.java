package com.tcms.integration.tracker;

import java.time.Instant;

/**
 * {@code TrackerConnectionCheck} — what {@code POST …/tracker-identities/{id}/test} answers.
 *
 * <p><strong>A tracker refusal is a 200 with {@code ok: false}, not a 4xx.</strong> The request to
 * <em>this</em> API succeeded, and the answer is the fact the caller asked for. Answering 401 would
 * also be actively harmful: it is the status the SPA's session-recovery path watches, and a person
 * testing an expired Jira token would be signed out of TCMS for it.
 *
 * @param error the tracker's own message, redacted. Shown to the person whose credential it is and
 *              to nobody else — the connected-members list carries a state and no sentence (D19).
 */
public record TrackerConnectionCheckView(boolean ok, Instant checkedAt, String error) {
}
