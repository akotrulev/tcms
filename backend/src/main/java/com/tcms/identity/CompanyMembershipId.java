package com.tcms.identity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link CompanyMembership}: {@code (company_id, user_id)}, with
 * {@code company_id} leading per ERD §0.1 point 3.
 *
 * <p>A class rather than a record because JPA's {@code @IdClass} contract wants a
 * no-arg constructor and mutable fields; records cannot serve it.
 */
public class CompanyMembershipId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID companyId;
    private UUID userId;

    /** JPA only. */
    public CompanyMembershipId() {
    }

    public CompanyMembershipId(UUID companyId, UUID userId) {
        this.companyId = companyId;
        this.userId = userId;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CompanyMembershipId that)) {
            return false;
        }
        return Objects.equals(companyId, that.companyId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(companyId, userId);
    }
}
