package com.tcms.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * The digest of one API token, and the company it belongs to. Nothing else.
 *
 * <p><strong>Instance-level: no company filter, no RLS</strong> (ERD §0.1 exception list, decided
 * in {@code V36}). This is {@link MembershipInvitation}'s decision a second time and for the same
 * reason: the credential is presented <em>before</em> authentication, so there is no session to
 * resolve a company from, and {@link com.tcms.tenancy.CompanyContext} is never populated from
 * request input. Under RLS the row would be unreadable in precisely the one place it must be read —
 * {@code V7}'s defect, which returned nothing in production while 167 tests stayed green.
 *
 * <p>{@code CompanyFilterTest} derives the entities that must carry the filter from
 * {@code pg_class.relrowsecurity}, so this one is required <em>not</em> to have it.
 *
 * <h2>Why this is a separate row rather than a column on {@link ApiToken}</h2>
 *
 * <p>Because the split is what makes the exception cheap. Everything an authorization decision
 * needs — name, owner, role, project, expiry, revocation — stays on the policed row and is read
 * <em>after</em> {@link #companyId} has opened a context. This table answers one question, and a
 * reader who obtains it learns that some digest belongs to some company: no person, no project, no
 * capability.
 *
 * <p>The alternative — keeping {@code V1}'s {@code api_token.token_hash} and adding a copy here —
 * is two homes for one credential digest, which diverge the first time a revocation writes one and
 * a filter reads the other. {@code V36} moves it rather than duplicating it.
 */
@Entity
@Table(name = "api_token_lookup")
public class ApiTokenLookup {

    /** Hex SHA-256 of the presented secret, and the primary key: the lookup is one indexed probe. */
    @Id
    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "token_id", nullable = false, updatable = false)
    private UUID tokenId;

    /** The company this digest belongs to. A target, not a tenancy discriminator. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** JPA only. */
    protected ApiTokenLookup() {
    }

    public static ApiTokenLookup of(String tokenHash, UUID tokenId, UUID companyId) {
        ApiTokenLookup lookup = new ApiTokenLookup();
        lookup.tokenHash = tokenHash;
        lookup.tokenId = tokenId;
        lookup.companyId = companyId;
        return lookup;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getTokenId() {
        return tokenId;
    }

    public UUID getCompanyId() {
        return companyId;
    }
}
