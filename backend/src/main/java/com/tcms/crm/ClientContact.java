package com.tcms.crm;

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
 * A person to talk to at a client — <strong>and not a person who can log in.</strong>
 *
 * <p>There is no {@code userId} field on this entity, no credential column on the table behind it,
 * and no credential field on {@code ClientContactInput}, so there is nothing to send even if one
 * were wanted (scope D1). {@code CrmCreatesNoLoginTest} asserts it from the other end, by counting
 * rows in {@code app_user}, {@code company_membership} and {@code invitation} across a contact
 * write: "creates no login" is a claim about what is <em>absent</em>, and a service test cannot see
 * an insert it does not make.
 *
 * <p>{@link #email} is not unique and is not {@code citext}. Two people at one client may share an
 * inbox — {@code client.html}'s third contact is {@code invoices@acme.test} — and a uniqueness rule
 * here would be the first step toward treating this row as an identity.
 */
@Entity
@Table(name = "client_contact")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ClientContact {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "client_id", nullable = false, updatable = false)
    private UUID clientId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "role")
    private String role;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    /** JPA only. */
    protected ClientContact() {
    }

    static ClientContact create(UUID id, UUID companyId, UUID clientId, ContactEdit edit, Instant now) {
        ClientContact created = new ClientContact();
        created.id = id;
        created.companyId = companyId;
        created.clientId = clientId;
        created.createdAt = now;
        created.apply(edit, now);
        return created;
    }

    void apply(ContactEdit edit, Instant now) {
        this.name = edit.name();
        this.role = edit.role();
        this.email = edit.email();
        this.phone = edit.phone();
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCompanyId() {
        return companyId;
    }

    UUID getClientId() {
        return clientId;
    }

    String getName() {
        return name;
    }

    String getRole() {
        return role;
    }

    String getEmail() {
        return email;
    }

    String getPhone() {
        return phone;
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
