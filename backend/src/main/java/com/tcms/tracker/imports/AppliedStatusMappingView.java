package com.tcms.tracker.imports;

import com.tcms.tracker.IssueStatusKind;
import java.util.UUID;

/**
 * The mapping as it was <strong>applied</strong>, with the target's name and kind captured at the
 * time (D-g).
 *
 * <p>Stored as JSONB on {@code import_run} and joined to by nothing: a status is removable through
 * the workflow screen, so a foreign key would either cascade the report away or block the removal.
 * The name is therefore the value, not a label looked up from a row that may be gone.
 *
 * @param issueCount how many issues arrived in this status, which is what makes the report's
 *                   mapping panel legible rather than a list of names
 */
public record AppliedStatusMappingView(
        String sourceStatusName,
        UUID targetStatusId,
        String targetStatusName,
        IssueStatusKind targetStatusKind,
        int issueCount) {
}
