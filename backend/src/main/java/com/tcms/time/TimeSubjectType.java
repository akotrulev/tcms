package com.tcms.time;

/**
 * What an entry is logged against (016 D-b).
 *
 * <p>Five members, and {@code TEST_RUN} is deliberately not one. {@code timesheet-approvals.html}
 * displays a row logged against a test run, but {@code timesheets.html} — the only screen that
 * <em>creates</em> an entry — cannot produce one, and a kind no person can make is a kind that does
 * not exist. The two mockups disagree and the log form wins. Adding it later is one member here and
 * one member of {@code ck_time_entry_subject_type}.
 *
 * <p>{@link #ISSUE} is the only member that carries a subject id and a project;
 * {@code ck_time_entry_subject_shape} enforces the pairing in both directions, so a meeting with a
 * project on it is refused by the database as well as by {@code TimeEntryService}.
 */
public enum TimeSubjectType {
    ISSUE,
    MEETING,
    LEAVE,
    TRAINING,
    INTERNAL
}
