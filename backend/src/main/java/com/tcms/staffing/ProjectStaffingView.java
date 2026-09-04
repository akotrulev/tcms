package com.tcms.staffing;

import java.time.LocalDate;
import java.util.List;

/** {@code GET /projects/{projectId}/staffing} — this project's allocations and the people behind them. */
public record ProjectStaffingView(
        LocalDate from,
        LocalDate to,
        List<AllocationView> allocations,
        List<ProjectStaffingPersonView> people) {
}
