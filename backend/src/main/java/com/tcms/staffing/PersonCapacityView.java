package com.tcms.staffing;

import com.tcms.api.UserRef;
import java.time.LocalDate;
import java.util.List;

/**
 * One person over one window — the shape the directory, the company staffing view and the person
 * page all render.
 *
 * <p><strong>{@link #capacityHours} is already net of absence.</strong> There is no screen that
 * should subtract it a second time, and one that did would contradict this one — which is the whole
 * reason {@link Capacity} exists rather than four screens each doing the arithmetic.
 *
 * @param profile                null when nobody has recorded this person's contracted hours. Every
 *                               hours figure below is then null too — absent, not zero, and not a
 *                               default of 40 (016 D-j's refusal, kept). The percentages are still
 *                               real: an unprofiled person can be allocated and over-allocated, and
 *                               saying so is how somebody notices the profile is missing
 * @param allocatedPercent       the mean daily allocated percentage across the window, which is what
 *                               the row label shows
 * @param peakPercent            the highest single day's total (017 D-e). This is what the flag is
 *                               read off — a month's sum would flag two consecutive half-month
 *                               allocations at 200%
 * @param utilisationPercent     {@code approvedHours ÷ allocatedCapacityHours} (017 D-i).
 *                               <strong>Null, not zero, when allocated capacity is zero</strong> — a
 *                               person nobody has allocated has no utilisation, and 0% would say
 *                               they are idle when the true statement is that nobody planned their
 *                               time
 * @param freeFrom               the first day in the window under 100%, which is what
 *                               {@code directory.html}'s "free from 1 Oct" reads. Null when they are
 *                               full for all of it
 */
public record PersonCapacityView(
        UserRef person,
        PersonProfileView profile,
        List<SkillView> skills,
        Double capacityHours,
        Double absenceHours,
        int allocatedPercent,
        Double allocatedCapacityHours,
        int freePercent,
        int peakPercent,
        LocalDate peakOn,
        boolean overAllocated,
        Double approvedHours,
        Integer utilisationPercent,
        LocalDate freeFrom,
        List<AllocationSpanView> allocations,
        List<AbsenceView> absences) {
}
