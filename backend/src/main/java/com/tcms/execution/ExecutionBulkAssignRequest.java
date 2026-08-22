package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code ExecutionBulkOperation} in {@code docs/specs/openapi.yaml} — one action, one argument, one
 * selection.
 *
 * @param action       {@code ASSIGN} or {@code DISTRIBUTE}. There is no {@code UNASSIGN}: the
 *                     single-row {@code PUT} already spells "nobody" as a null assignee, and a
 *                     second spelling is a second implementation to keep agreeing.
 * @param executionIds the selection. Duplicates are collapsed by the service and each distinct id
 *                     is reported exactly once. Capped at 500 — one transaction, and up to 500
 *                     audit rows.
 * @param dryRun       {@code null} means <strong>true</strong>, which is the contract's default and
 *                     the one defaulting mistake here that would destroy data: a client omitting
 *                     the field must get a preview, never a commit.
 * @param payload      the argument, whose required member depends on {@link #action}.
 */
public record ExecutionBulkAssignRequest(
        String action, List<UUID> executionIds, Boolean dryRun, ExecutionBulkAssignPayload payload) {

    /** Whether this is a preview. Absent means yes — see the {@code dryRun} note above. */
    boolean previewing() {
        return dryRun == null || dryRun;
    }
}
