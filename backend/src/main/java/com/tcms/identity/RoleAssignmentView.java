package com.tcms.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The contract's {@code RoleAssignment} — one grant, as an admin screen renders and revokes it.
 *
 * <p>One view per <em>row</em>, not per person a row reaches. A team grant is one assignment with
 * one id, and {@code DELETE /role-assignments/{id}} revokes that row for everybody it covers; a
 * response that expanded a team grant into one entry per member would offer several ids for one
 * revocable thing, and revoking any of them would silently affect the others.
 *
 * @param user <strong>null for a team grant</strong>, and that is a divergence from the contract,
 *             which declares {@code user} required and non-nullable. It cannot be honoured:
 *             {@code ck_role_assignment_subject} enforces user <em>xor</em> team, and the write
 *             endpoint's own description says "exactly one of {@code userId} and {@code teamId}",
 *             so a team grant has no user to name. Same shape as the {@code EntityVersion.createdBy}
 *             correction handoff §37 records — reported for the contract holder rather than worked
 *             around here. The SPA already reads it with {@code ?.}.
 * @param projectId null for a COMPANY-scope grant: it covers every project in the company, present
 *                  and future
 * @param projectName null both when the grant is company-wide <em>and</em> when the caller may not
 *                    see the project it names — the id alone is not a leak, the name is
 */
public record RoleAssignmentView(
        UUID id,
        RoleView role,
        UserRefView user,
        TeamRef team,
        UUID projectId,
        String projectName,
        Instant grantedAt) {

    /** A team, reduced to what the "granted via" column shows. */
    public record TeamRef(UUID id, String name) {
    }
}
