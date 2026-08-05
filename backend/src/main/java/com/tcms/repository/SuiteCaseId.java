package com.tcms.repository;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link SuiteCase}: {@code (company_id, suite_id, case_id)} — the table's own
 * primary key, {@code company_id} leading, per ERD §0.1 point 3.
 *
 * <p>A class rather than a record because JPA's {@code @IdClass} contract wants a no-arg
 * constructor and mutable fields.
 *
 * <p>It leads here and does <strong>not</strong> lead {@link CaseTagId}, and the difference is the
 * table's rather than this class's: {@code case_tag} is keyed on a case, which already determines
 * its company, while {@code suite_case} is a V31 table written to the current rule.
 */
public class SuiteCaseId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID companyId;
    private UUID suiteId;
    private UUID caseId;

    /** JPA only. */
    public SuiteCaseId() {
    }

    public SuiteCaseId(UUID companyId, UUID suiteId, UUID caseId) {
        this.companyId = companyId;
        this.suiteId = suiteId;
        this.caseId = caseId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SuiteCaseId that)) {
            return false;
        }
        return Objects.equals(companyId, that.companyId)
                && Objects.equals(suiteId, that.suiteId)
                && Objects.equals(caseId, that.caseId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(companyId, suiteId, caseId);
    }
}
