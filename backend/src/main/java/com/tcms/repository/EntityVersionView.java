package com.tcms.repository;

import com.tcms.api.UserRef;
import java.time.Instant;

/**
 * {@code EntityVersion} in {@code docs/specs/openapi.yaml} — one row of a case's authored history.
 *
 * <p>Deliberately <em>not</em> carrying the snapshot. The contract's shape is a list entry: version
 * number, who, when, why, and whether anything still references it. A version list that shipped the
 * snapshot with every row would send the entire text of every version of a case in order to render
 * a five-line panel — for a case edited fifty times, fifty full copies of it. The snapshot is what a
 * diff view reads, one version at a time, through an endpoint that does not exist yet.
 *
 * @param createdBy <strong>null when the author cannot be named</strong>, and the contract does not
 *                  say so — see {@link CaseVersionService} for the divergence and why this field is
 *                  the honest shape. {@code test_case_version.created_by} is a nullable column with
 *                  no foreign key to {@code app_user} (handoff §28, finding 1), so both "nobody was
 *                  recorded" and "the recorded id names no account" are reachable states of a real
 *                  row. Inventing a name for either would put an author on the one table whose
 *                  purpose is that past claims stay true.
 * @param pinned    true when a run, approval or report references this version, making it
 *                  permanently exempt from retention. False on every row today because nothing
 *                  writes {@code test_execution} — the honest value, not a placeholder.
 */
public record EntityVersionView(
        int versionNo,
        UserRef createdBy,
        Instant createdAt,
        String changeComment,
        boolean pinned) {

    static EntityVersionView of(TestCaseVersion version, UserRef author) {
        return new EntityVersionView(
                version.getVersionNo(),
                author,
                version.getCreatedAt(),
                version.getChangeComment(),
                version.isPinned());
    }
}
