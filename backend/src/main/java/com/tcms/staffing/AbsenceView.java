package com.tcms.staffing;

import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code Absence} on the wire.
 *
 * @param dayFraction the share of each day in the range that is away — {@code 1} a whole day,
 *                    {@code 0.5} the "half day" {@code member.html} shows (017 D-d). A double
 *                    rather than the column's {@code NUMERIC(3,2)}, which is the split this repo
 *                    already makes between a stored estimate and the one on the wire
 */
public record AbsenceView(
        UUID id,
        UUID personId,
        LocalDate startsOn,
        LocalDate endsOn,
        AbsenceKind kind,
        double dayFraction,
        String note) {

    /** The stored row as the wire declares it — one mapping, so two surfaces cannot disagree. */
    static AbsenceView of(Absence row) {
        return new AbsenceView(
                row.getId(),
                row.getPersonId(),
                row.getStartsOn(),
                row.getEndsOn(),
                row.getKind(),
                row.getDayFraction().doubleValue(),
                row.getNote());
    }
}
