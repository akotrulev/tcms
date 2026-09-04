package com.tcms.staffing;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /staffing/capacity} — everybody, plus the over-allocated subset.
 *
 * @param overAllocated the members of {@link #people} whose {@code overAllocated} is true, served
 *                      rather than re-filtered so the Over-100% panel cannot disagree with the row
 *                      badge above it. Two client-side filters over one list is how one screen comes
 *                      to say two things.
 */
public record StaffingCapacityView(
        LocalDate from,
        LocalDate to,
        List<PersonCapacityView> people,
        List<PersonCapacityView> overAllocated) {
}
