package com.tcms.time;

/**
 * Where a person's half-month stands.
 *
 * <p>{@code OPEN -> SUBMITTED -> APPROVED | REJECTED}, and <strong>{@code REJECTED} returns to
 * editable without returning to {@code OPEN}</strong>: the note that sent the period back survives
 * on the row and is shown at the top of the unlocked period. That is why rejection is a state rather
 * than a reversal — a period that went back to {@code OPEN} would lose the only record of why.
 */
public enum TimesheetState {
    OPEN,
    SUBMITTED,
    APPROVED,
    REJECTED;

    /** Whether entries may be written into a period in this state. {@code editable} on the wire. */
    boolean editable() {
        return this == OPEN || this == REJECTED;
    }
}
