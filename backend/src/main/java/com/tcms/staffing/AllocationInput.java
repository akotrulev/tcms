package com.tcms.staffing;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code POST /projects/{projectId}/allocations}.
 *
 * <p>The project is the path's, never this body's — the same rule the company follows one layer
 * down, and for the same reason: a target named by the request is a target the gate did not choose.
 */
public record AllocationInput(
        UUID personId,
        Integer percent,
        LocalDate startsOn,
        LocalDate endsOn,
        String note) {
}
