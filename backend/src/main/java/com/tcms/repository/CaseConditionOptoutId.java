package com.tcms.repository;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link CaseConditionOptout}: {@code (case_id, suite_condition_id)} — the
 * table's own primary key.
 *
 * <p>A class rather than a record because JPA's {@code @IdClass} contract wants a no-arg
 * constructor and mutable fields; records cannot serve it. Same shape and same reason as
 * {@link CaseTagId}.
 *
 * <p>{@code company_id} does not lead this key, for the reason {@link CaseTagId} records: a case id
 * already determines its company, so prefixing the key would add a column that can never
 * discriminate. The column is still {@code NOT NULL} and still the RLS predicate.
 */
public class CaseConditionOptoutId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID caseId;
    private UUID suiteConditionId;

    /** JPA only. */
    public CaseConditionOptoutId() {
    }

    public CaseConditionOptoutId(UUID caseId, UUID suiteConditionId) {
        this.caseId = caseId;
        this.suiteConditionId = suiteConditionId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getSuiteConditionId() {
        return suiteConditionId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CaseConditionOptoutId that)) {
            return false;
        }
        return Objects.equals(caseId, that.caseId)
                && Objects.equals(suiteConditionId, that.suiteConditionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caseId, suiteConditionId);
    }
}
