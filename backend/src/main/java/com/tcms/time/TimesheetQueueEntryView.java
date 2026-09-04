package com.tcms.time;

import java.util.List;

/** One person's fortnight as the approvals queue shows it. */
public record TimesheetQueueEntryView(
        TimesheetPeriodView period, List<ProjectTotalView> projectTotals) {

    /**
     * @param projectName the literal "—" is not used; a project-less total omits this row, because a
     *                    dash in a column of project names is a project called dash
     */
    public record ProjectTotalView(String projectName, double hours) {
    }
}
