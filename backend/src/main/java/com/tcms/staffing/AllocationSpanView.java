package com.tcms.staffing;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One project's claim on one person, as the company timeline draws it.
 *
 * <p><strong>This names the project and {@link ProjectStaffingPersonView} deliberately does
 * not</strong> (017 D-f). It is reached only through {@code staffing:directory}, which is COMPANY
 * scope; the project screen's equivalent is gated on a per-project grant, and listing "Acme Loyalty
 * 50% + Bergstrom Portal 30%" there would disclose, through a project-scope grant, the existence and
 * staffing of projects the caller cannot see.
 */
public record AllocationSpanView(
        UUID projectId,
        String projectKey,
        String projectName,
        int percent,
        LocalDate startsOn,
        LocalDate endsOn) {
}
