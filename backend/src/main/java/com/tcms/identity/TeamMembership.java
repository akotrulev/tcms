package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * Membership of a team — the edge {@link PermissionEvaluator} traverses to resolve
 * team-granted roles.
 *
 * <p>Exactly one hop: a user's teams are read directly, and a team's <em>parent</em>
 * confers nothing (see {@link Team}). {@code company_id} is carried and filtered so a
 * team membership can never be read from another client's tenancy — without it the
 * evaluator's subquery would be the one unfiltered step in the union.
 */
@Entity
@Table(name = "team_membership")
@IdClass(TeamMembershipId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class TeamMembership {

    @Id
    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * Team Lead — manages membership and is the default reviewer. Deliberately distinct
     * from the <em>Test Lead</em> role (plan §6b): this flag confers no permission and is
     * not read by the evaluator.
     */
    @Column(name = "is_lead", nullable = false)
    private boolean lead;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /** JPA only. */
    protected TeamMembership() {
    }

    public static TeamMembership join(UUID companyId, UUID teamId, UUID userId, boolean lead, Instant now) {
        TeamMembership membership = new TeamMembership();
        membership.companyId = companyId;
        membership.teamId = teamId;
        membership.userId = userId;
        membership.lead = lead;
        membership.joinedAt = now;
        return membership;
    }

    /**
     * Promotes or demotes an existing member.
     *
     * <p>Separate from {@link #join} because the two are different writes on the same URL:
     * {@code joinedAt} records when this person joined the team and must survive a promotion, so
     * re-creating the row would quietly reset it and lose the one fact the row carries beyond its
     * own key.
     *
     * <p>Confers nothing either way — see {@link #lead}'s comment.
     */
    public void setLead(boolean nowLeads) {
        this.lead = nowLeads;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public boolean isLead() {
        return lead;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
