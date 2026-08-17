package com.tcms.identity;

import com.tcms.api.TeamRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The contract's {@code OrgChartPerson} — one row of the chart.
 *
 * <p>Flat rather than nested under a {@code UserRef} for {@link UserView}'s reason: the contract
 * composes them with {@code allOf} and that serializes flat, so a nested record would put
 * {@code ref.email} on the wire and no consumer expects it.
 *
 * @param teams      <strong>refs, not names.</strong> The chart links a person to a team
 *                   <em>node</em>. {@code User.teams} is {@code string[]} and stays that way —
 *                   widening a shipped shape for a screen that can have its own is a breaking
 *                   change, and a new schema is not
 * @param leadsTeams the subset of {@link #teams} where {@code team_membership.is_lead}. <strong>A
 *                   Team Lead is not the Test Lead role</strong> — that column's own comment says
 *                   so, and the flag confers no permission; it names the default reviewer
 * @param companyRoles COMPANY-scope role names held, in practice {@code Company Admin} or empty. On
 *                   the person and never smeared across the columns: a COMPANY-scope role is
 *                   authority over the company rather than a default for its projects (roles spec
 *                   §A1.1 property 2). The {@code Company Member} baseline is filtered out — every
 *                   member holds it, so a badge for it would be on every row saying nothing.
 *                   SYSTEM scope is absent entirely: {@code instance_role_assignment} has no
 *                   {@code company_id} and no RLS, and an instance fact does not belong on a
 *                   tenant's wire
 * @param projects   the cells, one per column this person holds an effective role in. Empty means
 *                   "no reach into any project the CALLER can see", which is not the same claim as
 *                   "no reach" — and a screen must not read it as one
 * @param lastLoginAt <strong>null for a caller who does not hold {@code company:members}</strong> —
 *                   the same rule and the same reason as {@link UserView#lastLoginAt}. The contract
 *                   declares it nullable for everyone, so withholding it changes no shape
 */
public record OrgChartPersonView(
        UUID id,
        String displayName,
        String email,
        String avatarUrl,
        UserStatus status,
        List<TeamRef> teams,
        List<TeamRef> leadsTeams,
        List<String> companyRoles,
        List<OrgChartAssignmentView> projects,
        Instant lastLoginAt) {
}
