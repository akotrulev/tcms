package com.tcms.staffing;

import java.time.LocalDate;

/**
 * {@code PATCH /projects/{projectId}/allocations/{allocationId}} — a true delta.
 *
 * <p>Bound beside a {@link com.tcms.api.PatchBody} so absent and explicitly-null are told apart:
 * Jackson gives them the same Java value, and a service that could not ask what it was sent read
 * both as <em>clear it</em>, which is the defect B1 records.
 *
 * <p><strong>Neither the person nor the project is here.</strong> Moving an allocation to a
 * different person is deleting one and creating another; doing it as an edit would slip past the
 * overlap check, which keys on exactly those two columns (017 D-n).
 */
public record AllocationPatch(
        Integer percent,
        LocalDate startsOn,
        LocalDate endsOn,
        String note) {
}
