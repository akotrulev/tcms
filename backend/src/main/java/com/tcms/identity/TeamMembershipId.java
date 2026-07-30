package com.tcms.identity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link TeamMembership}: {@code (team_id, user_id)}.
 *
 * <p>Note {@code company_id} is <em>not</em> part of the key here, unlike
 * {@link CompanyMembershipId} — a team already belongs to exactly one company, so the
 * column is present for RLS and index leading, not for identity.
 */
public class TeamMembershipId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID teamId;
    private UUID userId;

    /** JPA only. */
    public TeamMembershipId() {
    }

    public TeamMembershipId(UUID teamId, UUID userId) {
        this.teamId = teamId;
        this.userId = userId;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TeamMembershipId that)) {
            return false;
        }
        return Objects.equals(teamId, that.teamId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(teamId, userId);
    }
}
