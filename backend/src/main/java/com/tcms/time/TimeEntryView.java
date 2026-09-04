package com.tcms.time;

import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a timesheet.
 *
 * @param subject  present only when {@link #subjectType} is {@link TimeSubjectType#ISSUE},
 *                 denormalised from the tracker's projection through {@link IssueFacts}
 * @param editable false whenever the owning period is {@code SUBMITTED} or {@code APPROVED}. Served
 *                 rather than derived on the client so that one rule has one home — the client
 *                 computing it from {@code state} is a second implementation of the lock this whole
 *                 feature turns on
 */
public record TimeEntryView(
        UUID id,
        UUID periodId,
        TimeSubjectType subjectType,
        SubjectView subject,
        LocalDate spentOn,
        double hours,
        String note,
        boolean editable) {

    /** The issue an entry names, as much of it as a timesheet row renders. */
    public record SubjectView(
            UUID issueId,
            String issueKey,
            String title,
            String typeLevel,
            UUID projectId,
            String projectName) {
    }
}
