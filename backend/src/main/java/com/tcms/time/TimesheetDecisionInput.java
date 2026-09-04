package com.tcms.time;

/**
 * Approve a period, send it back, or take a rejection back.
 *
 * @param decision {@code APPROVE}, {@code REJECT} or {@code WITHDRAW_REJECTION}. A String rather
 *                 than an enum because the contract declares it inline on this schema rather than as
 *                 a named one, and an unknown value has to read as a field error the caller can fix
 *                 rather than as a deserialization failure with no field named
 * @param note     required when {@link #decision} is {@code REJECT}. A rejection is never silent —
 *                 {@code ck_timesheet_period_note} refuses the row without one
 */
public record TimesheetDecisionInput(String decision, String note) {
}
