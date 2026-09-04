package com.tcms.time;

import java.time.LocalDate;

/**
 * What a person submits to log or amend an hour.
 *
 * <p>The period is <strong>not</strong> among these fields: it is derived from {@link #spentOn}
 * (016 D-a) and created if it does not exist yet, so nothing has to open one first and two clients
 * cannot disagree about where a fortnight starts.
 *
 * @param issueKey required when {@link #subjectType} is {@link TimeSubjectType#ISSUE}, and refused
 *                 otherwise — {@code ck_time_entry_subject_shape} says the same thing at the table,
 *                 so a request that got past this would get a constraint name instead of a sentence
 */
public record TimeEntryInput(
        TimeSubjectType subjectType,
        String issueKey,
        LocalDate spentOn,
        Double hours,
        String note) {
}
