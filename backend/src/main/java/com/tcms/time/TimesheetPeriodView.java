package com.tcms.time;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A person's half-month, with its entries and its totals.
 *
 * @param loggedHours   everything in the period, whatever anybody has decided about it
 * @param approvedHours zero unless {@link #state} is {@code APPROVED}. Carried separately from
 *                      {@link #loggedHours} per 016 D-d, and never summed with it: one screen says
 *                      "approved hours only" and another counts hours nobody has approved, so a
 *                      single number could not say both
 * @param decisionNote  the rejector's note, shown at the top of the unlocked period and never
 *                      cleared — including after a re-submission, because the engineer needs to see
 *                      what they were asked to fix while they fix it
 */
public record TimesheetPeriodView(
        UUID id,
        UUID personId,
        String personName,
        LocalDate startsOn,
        LocalDate endsOn,
        TimesheetState state,
        double loggedHours,
        double approvedHours,
        Instant submittedAt,
        Instant decidedAt,
        String decidedByName,
        String decisionNote,
        boolean editable,
        List<TimeEntryView> entries) {
}
