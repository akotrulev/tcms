package com.tcms.identity;

import com.tcms.api.TeamRef;
import java.util.UUID;

/**
 * The contract's {@code OrgChartAssignment} — one matrix cell.
 *
 * <p>A cell exists only for a project the <strong>caller</strong> can open. That is the whole
 * disclosure boundary, and it is a property of the reader rather than of the person in the row: two
 * colleagues open this chart, see the same people, and see different matrices.
 *
 * @param roleName the person's effective PROJECT-scope role here, after roles spec §A1.1's
 *                 replace-don't-union override and after the strongest-grant ranking.
 *                 <strong>Display only</strong> — nothing may branch on this string; code checks
 *                 permissions, never role names (plan §6b)
 * @param via      which of the three ways the role reaches them
 * @param viaTeam  non-null exactly when {@link #via} is {@link AssignmentVia#TEAM}. Read off the
 *                 grant that <em>won</em> the ranking rather than re-derived, so the arm shown and
 *                 the name shown cannot come from two different grants
 */
public record OrgChartAssignmentView(
        UUID projectId, String roleName, AssignmentVia via, TeamRef viaTeam) {
}
