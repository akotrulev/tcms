package com.tcms.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A separate client organisation — the top-level isolation boundary (plan §6s).
 *
 * <p>Deliberately <strong>not</strong> under RLS and carrying no {@code company_id}:
 * it <em>is</em> the company, and the Instance Admin reads it to resolve a slug before
 * any company context exists.
 *
 * <p>Provisioned by the {@code provision-company} operator script, never through an
 * admin UI. That is what keeps M6 small — no CRUD screens, no ops console, no archive
 * state (plan §6s).
 */
@Entity
@Table(name = "company")
public class Company {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Immutable after provisioning: it appears in every URL and shared link.
     */
    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CompanyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Company() {
    }

    /**
     * Creates a company. Called by the provisioning script, which is also responsible
     * for seeding the company's vocabularies, statuses and first Company Admin
     * invitation in the same transaction.
     *
     * @param id  assigned by the caller, not the database — distributed-safe, and it
     *            avoids the "save twice to learn the id" trap
     * @param now injected rather than {@code Instant.now()} so provisioning is testable
     */
    public static Company provision(UUID id, String slug, String displayName, Instant now) {
        Company company = new Company();
        company.id = id;
        company.slug = slug;
        company.displayName = displayName;
        company.status = CompanyStatus.ACTIVE;
        company.createdAt = now;
        return company;
    }

    /**
     * The default lifecycle policy is suspend, never delete — deleting would need a
     * purge script across ~60 tables of cascades (plan §6s).
     *
     * <p>{@code SUSPENDED} must be <strong>honoured in code</strong>: refuse all
     * non-Instance-Admin access, return 503 from ingestion with a reason, leave the
     * data untouched. The status column alone enforces nothing.
     */
    public void suspend() {
        this.status = CompanyStatus.SUSPENDED;
    }

    public void reinstate() {
        this.status = CompanyStatus.ACTIVE;
    }

    public void rename(String newDisplayName) {
        this.displayName = newDisplayName;
    }

    public UUID getId() {
        return id;
    }

    public String getSlug() {
        return slug;
    }

    public String getDisplayName() {
        return displayName;
    }

    public CompanyStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getLockVersion() {
        return lockVersion;
    }
}
