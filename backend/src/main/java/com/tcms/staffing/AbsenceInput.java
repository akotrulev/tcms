package com.tcms.staffing;

import java.time.LocalDate;

/**
 * {@code POST /staffing/people/{userId}/absences}.
 *
 * <p><strong>No {@code Idempotency-Key}, and the omission is the parameter's own scope rather than
 * an oversight</strong> (017 T1 correction 5): the header deduplicates within
 * {@code (company, project, key)}, and an absence is not addressed to a project. What a lost
 * response costs is a duplicate row over the same days, which double-counts nothing — capacity is
 * computed per DAY from the absences covering it and {@link Capacity} caps the day at one whole one.
 *
 * @param dayFraction absent means a whole day. The contract's minimum is {@code 0.25} and the
 *                    column's check is the wider {@code (0, 1]}: a quarter day is what a form should
 *                    refuse to go below, not what the schema should make unrepresentable
 */
public record AbsenceInput(
        LocalDate startsOn,
        LocalDate endsOn,
        AbsenceKind kind,
        Double dayFraction,
        String note) {
}
