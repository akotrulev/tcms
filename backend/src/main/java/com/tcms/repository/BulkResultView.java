package com.tcms.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/**
 * {@code BulkResult} in {@code docs/specs/openapi.yaml} — what changed, what already matched, and
 * what was skipped and why.
 *
 * <h2>Keys, not counts</h2>
 *
 * <p>Every outcome is a list of display keys ({@code TC-42}) rather than a number. A bare
 * {@code affected: 38} cannot be rendered as a preview at all, and the preview is the entire point
 * of the endpoint — the user is being asked to approve a specific set of changes before they happen,
 * which requires seeing which ones. The counts the dialog shows are derived from the list lengths,
 * so the number and the names cannot disagree.
 *
 * <p>Keys rather than ids for the same reason: the preview is read by a person, and {@code TC-42} is
 * what they see everywhere else in the product. The id is what the request carried and the response
 * has no use for it.
 *
 * @param dryRun       true when nothing was written. Echoed rather than assumed from the request so
 *                     the client can assert what it got back was a preview, which is what
 *                     {@code onCommitted} branches on.
 * @param auditEventId the one audit row this operation wrote, naming every changed case (§6g:
 *                     "preview, transactional, single audit event"). Null on a preview, and null on
 *                     a commit that changed nothing — writing an audit row for an operation that
 *                     touched no case would record something that did not happen.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
record BulkResultView(
        boolean dryRun,
        int total,
        List<String> changed,
        List<String> unchanged,
        List<SkippedCase> skipped,
        UUID auditEventId) {

    /** One case that could not be changed, with the reason rendered beside its key. */
    record SkippedCase(String key, String reason) {
    }
}
