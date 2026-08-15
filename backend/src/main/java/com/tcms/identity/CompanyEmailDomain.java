package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One email domain a company claims, so that typing an address at it offers that company's sign-in
 * buttons (§6r).
 *
 * <p><strong>A claim, not an assertion of ownership.</strong> Nothing here verifies that the client
 * controls the domain, and nothing needs to: the row only decides which buttons a login form
 * renders, and pressing one still ends at an issuer that has to authenticate the person. Two
 * companies claiming {@code contractor.example} both appear, which §6r requires — a consultant who
 * genuinely works for both authenticates first and lands in whichever company their identity
 * resolves in.
 *
 * <p>Which is also why there is no instance-wide uniqueness and why the discovery answer carries no
 * company id: a rejection on save, or a company name in the response, would each publish that some
 * other client already claimed the domain.
 */
@Entity
@Table(name = "company_email_domain")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class CompanyEmailDomain {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /**
     * {@code CITEXT}, so {@code ACME.test} and {@code acme.test} are one claim rather than two.
     *
     * <p>The {@code columnDefinition} is load-bearing rather than decoration, for
     * {@link AppUser#getEmail()}'s reason: {@code citext} reports as {@code Types#OTHER} while a
     * bare {@code String} maps to {@code varchar}, so {@code ddl-auto: validate} refuses to start
     * the whole application with "found [citext (Types#OTHER)], but expecting [varchar(255)]".
     */
    @Column(name = "domain", nullable = false, updatable = false, columnDefinition = "citext")
    private String domain;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA only. */
    protected CompanyEmailDomain() {
    }

    public static CompanyEmailDomain claim(UUID id, UUID companyId, String domain, Instant now) {
        CompanyEmailDomain claim = new CompanyEmailDomain();
        claim.id = id;
        claim.companyId = companyId;
        claim.domain = domain;
        claim.createdAt = now;
        return claim;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getDomain() {
        return domain;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
