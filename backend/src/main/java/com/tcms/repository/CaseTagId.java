package com.tcms.repository;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link CaseTag}: {@code (case_id, tag)} — the table's own primary key.
 *
 * <p>A class rather than a record because JPA's {@code @IdClass} contract wants a no-arg
 * constructor and mutable fields; records cannot serve it. Same shape and same reason as
 * {@code CompanyMembershipId}.
 *
 * <p><strong>{@code company_id} does not lead this key</strong>, unlike the pattern ERD §0.1
 * point 3 sets for indexes. That is the schema's choice, not this class's: {@code case_tag}'s
 * primary key is {@code (case_id, tag)} because a case id already determines its company, so
 * prefixing the key would add a column that can never discriminate. The column is still
 * {@code NOT NULL} on the table and still the RLS predicate, which is what the rule is actually
 * protecting.
 */
public class CaseTagId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID caseId;
    private String tag;

    /** JPA only. */
    public CaseTagId() {
    }

    public CaseTagId(UUID caseId, String tag) {
        this.caseId = caseId;
        this.tag = tag;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getTag() {
        return tag;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CaseTagId that)) {
            return false;
        }
        return Objects.equals(caseId, that.caseId) && Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caseId, tag);
    }
}
