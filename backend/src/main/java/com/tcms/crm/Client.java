package com.tcms.crm;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A company's own customer.
 *
 * <p><strong>An attribute of a project, never a level of the hierarchy</strong> (scope D1). Nothing
 * on this entity is a permission target and nothing is an isolation boundary except
 * {@link #companyId}, which is the same boundary every other table here carries.
 *
 * <p><strong>No rate, no budget, no invoice and no hours column</strong>, and their absence is a
 * decision rather than an omission — see the package comment. What a client's work is worth is M8's.
 *
 * <p>{@link #name} is a plain {@code varchar} and <em>not</em> {@code citext}, unlike
 * {@code staffing.Skill.name} and {@code AppUser.email}. The case-folding argument there is that a
 * filter missing half the matches is worse than no filter; here the constraint guards a different
 * thing — a duplicate organisation record — and two clients genuinely spelled "Acme" and "ACME" are
 * a distinction a person may be making on purpose. {@code uq_client_name} is therefore
 * case-sensitive, and nothing in this module casts.
 */
@Entity
@Table(name = "client")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Client {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "location")
    private String location;

    @Column(name = "industry")
    private String industry;

    @Column(name = "notes")
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClientStatus status;

    /**
     * The company member who owns this relationship, or {@code null}.
     *
     * <p>A raw id and not an association: {@code app_user} is {@code identity}'s, and this module
     * resolves the person through {@code project.PeopleDirectory} — the same arrangement, in the
     * same direction, that {@code staffing} uses for the same table.
     */
    @Column(name = "account_manager_id")
    private UUID accountManagerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected Client() {
    }

    static Client create(UUID id, UUID companyId, ClientEdit edit, Instant now) {
        Client created = new Client();
        created.id = id;
        created.companyId = companyId;
        created.createdAt = now;
        created.apply(edit, now);
        return created;
    }

    /**
     * Replace every field the form carries.
     *
     * <p>A full replacement rather than a delta, because {@code PUT /clients/{clientId}} is one:
     * the edit screen shows every field and submits every field, so "leave this one alone" has no
     * meaning a user of it could ask for. The MCP {@code edit} arm reads the record and merges
     * before calling, which is where a partial caller is reconciled — not here.
     */
    void apply(ClientEdit edit, Instant now) {
        this.name = edit.name();
        this.location = edit.location();
        this.industry = edit.industry();
        this.notes = edit.notes();
        this.status = edit.status();
        this.accountManagerId = edit.accountManagerId();
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    String getName() {
        return name;
    }

    String getLocation() {
        return location;
    }

    String getIndustry() {
        return industry;
    }

    String getNotes() {
        return notes;
    }

    ClientStatus getStatus() {
        return status;
    }

    UUID getAccountManagerId() {
        return accountManagerId;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    long getLockVersion() {
        return lockVersion;
    }
}
