package com.tcms.repository;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link CaseClassification}: {@code (case_id, value_id)}, the table's own
 * primary key.
 *
 * <p>A class rather than a record for JPA's {@code @IdClass} contract — see {@link CaseTagId},
 * which carries the same note about why {@code company_id} does not lead it.
 */
public class CaseClassificationId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID caseId;
    private UUID valueId;

    /** JPA only. */
    public CaseClassificationId() {
    }

    public CaseClassificationId(UUID caseId, UUID valueId) {
        this.caseId = caseId;
        this.valueId = valueId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getValueId() {
        return valueId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CaseClassificationId that)) {
            return false;
        }
        return Objects.equals(caseId, that.caseId) && Objects.equals(valueId, that.valueId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caseId, valueId);
    }
}
