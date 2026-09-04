package com.tcms.staffing;

import com.tcms.api.UserRef;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code Allocation} on the wire — one bar on the project's staffing timeline.
 *
 * <p>Carries the person as a {@link UserRef} as well as their id, because every row that renders one
 * needs a name and the alternative is a second read per row.
 *
 * <p><strong>No {@code lockVersion}</strong>, which is why {@code PATCH} declares no
 * {@code If-Match}: there is nothing to precondition on. What that costs is bounded by the delta —
 * two callers editing one allocation lose only the fields they both named — and it is recorded on
 * both agent surfaces rather than left for a reader to discover.
 */
public record AllocationView(
        UUID id,
        UUID projectId,
        UUID personId,
        UserRef person,
        int percent,
        LocalDate startsOn,
        LocalDate endsOn,
        String note) {
}
