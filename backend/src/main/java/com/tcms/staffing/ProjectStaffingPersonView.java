package com.tcms.staffing;

import com.tcms.api.UserRef;

/**
 * A person on <em>this</em> project.
 *
 * <p>Carries their company-wide {@link #totalPercent} and the over-allocation flag and
 * <strong>names no other project</strong> (017 D-f). A Project Manager holds
 * {@code staffing:read} on their own projects and no company-wide key, so a breakdown here would
 * disclose, through a project-scope grant, the existence and staffing of projects they cannot see.
 * The total is what makes the flag actionable — <em>this person is already at 130%, do not add to
 * them</em> — and the breakdown is the company view's, under {@code staffing:directory}.
 */
public record ProjectStaffingPersonView(
        UserRef person,
        int percentOnThisProject,
        int totalPercent,
        boolean overAllocated,
        Double capacityHours) {
}
