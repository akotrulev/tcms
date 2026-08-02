package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A company-owned org-chart node (plan §6k), and a grant subject: a
 * {@link RoleAssignment} may name a team instead of a user, so onboarding a person into
 * a team confers that team's grants.
 *
 * <p><strong>Permissions never cascade through {@link #parentTeamId}.</strong> The
 * parent edge is org-chart structure only (plan §6b/§6k) — a parent team's grants are
 * not inherited by its children, and {@link PermissionEvaluator} deliberately joins
 * {@code team_membership} directly rather than walking the tree. Making structure confer
 * capability is how an org-chart tidy-up silently becomes a privilege escalation.
 *
 * <p>Cycles in the parent edge are prevented by a database trigger (V2), which also
 * verifies the parent belongs to the same company — a cross-company parent would be both
 * a leak and an unwalkable tree.
 */
@Entity
@Table(name = "team")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Team {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** Org-chart structure only — never a permission edge. See the class comment. */
    @Column(name = "parent_team_id")
    private UUID parentTeamId;

    /** Unique per company, not globally: two clients may both have a "QA" team. */
    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Team() {
    }

    /**
     * @param companyId taken from {@link com.tcms.tenancy.CompanyContext}, never from a
     *                  request body
     */
    public static Team create(
            UUID id,
            UUID companyId,
            String name,
            String description,
            UUID parentTeamId,
            Instant now) {

        Team team = new Team();
        team.id = id;
        team.companyId = companyId;
        team.name = name;
        team.description = description;
        team.parentTeamId = parentTeamId;
        team.createdAt = now;
        team.updatedAt = now;
        return team;
    }

    /**
     * Applies a whole validated edit — <strong>one mutator, because the write is one PUT.</strong>
     *
     * <p>This replaced a {@code rename} and a {@code reparent} that had stood here with no callers
     * at all until {@link TeamWriteService} existed. Two mutators would each stamp
     * {@link #updatedAt}, so an edit changing both fields would touch the row twice for one request
     * and — if a caller ever forgot the second — leave a team renamed and not moved, with a 200
     * saying otherwise. The contract's body carries every editable field for the same reason.
     *
     * <p>Validation is {@link TeamEdit}'s and is not repeated here. The V2 trigger refuses a cycle
     * and a cross-company parent underneath both, as a backstop rather than as the answer.
     */
    public void applyEdit(String newName, String newDescription, UUID newParentTeamId, Instant now) {
        this.name = newName;
        this.description = newDescription;
        this.parentTeamId = newParentTeamId;
        this.updatedAt = now;
    }


    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getParentTeamId() {
        return parentTeamId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
