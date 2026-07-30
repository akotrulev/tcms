package com.tcms.project;

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
 * A company-owned aggregate, and the M0 vertical slice that proves the isolation
 * plumbing end to end.
 *
 * <p>Note the two things every company-owned entity must carry:
 * <ul>
 *   <li>{@code company_id} as a real, NOT NULL column — never derived, never nullable</li>
 *   <li>{@code @Filter} naming the tenancy filter, so a Session-based query is scoped
 *       even before RLS is consulted</li>
 * </ul>
 * {@code SchemaConventionTest} enforces the first mechanically over every table, and
 * {@code CompanyFilterTest} the second over every entity — because "remember to add
 * it" is not a mechanism that survives a year of feature work (ERD §0.1).
 */
@Entity
@Table(name = "project")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Project {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * Unique per COMPANY, not globally — two clients may both run a project keyed
     * "OLB". The uniqueness constraint is {@code (company_id, key)} with company_id
     * leading (ERD §0.1 point 3).
     */
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Project() {
    }

    /**
     * @param companyId taken from {@link com.tcms.tenancy.CompanyContext}, never from
     *                  the request body — a client-supplied company id is the whole
     *                  attack (ERD §0.1 point 2)
     */
    public static Project create(UUID id, UUID companyId, String key, String name, String description, Instant now) {
        Project project = new Project();
        project.id = id;
        project.companyId = companyId;
        project.key = key;
        project.name = name;
        project.description = description;
        project.createdAt = now;
        return project;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void describe(String newDescription) {
        this.description = newDescription;
    }

    public void archive(Instant now) {
        this.archivedAt = now;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getKey() {
        return key;
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

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
