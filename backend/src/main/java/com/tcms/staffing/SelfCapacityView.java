package com.tcms.staffing;

import java.time.LocalDate;

/**
 * {@code GET /staffing/capacity/me} — <strong>the one staffing read with no staffing permission on
 * it</strong>, because it is about the caller (017 D-m).
 *
 * <p>Serves the timesheet's {@code Period capacity} tile and My Work's this-period meter, both of
 * which pass the <em>period's</em> bounds and not a week's (016 D-a). It is a staffing read rather
 * than a field on {@code GET /timesheets/periods/current} because capacity is computed from
 * {@code person_profile}, {@code allocation} and {@code absence}, none of which {@code time} owns —
 * putting it on the timesheet payload would make that module serve a number it cannot compute.
 *
 * @param hasProfile explicit rather than inferred from a null, so a screen can say <em>no profile
 *                   recorded</em> instead of <em>failed to load</em>. The two look identical without
 *                   it, and one of them is the user's to fix
 */
public record SelfCapacityView(
        LocalDate from,
        LocalDate to,
        Double capacityHours,
        Double absenceHours,
        int allocatedPercent,
        boolean hasProfile) {
}
