package com.tcms.repository;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link SuiteTagRule}: {@code (company_id, suite_id, tag)} — the table's own
 * primary key. See {@link SuiteCaseId} for why {@code company_id} leads this one and not
 * {@link CaseTagId}.
 */
public class SuiteTagRuleId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID companyId;
    private UUID suiteId;
    private String tag;

    /** JPA only. */
    public SuiteTagRuleId() {
    }

    public SuiteTagRuleId(UUID companyId, UUID suiteId, String tag) {
        this.companyId = companyId;
        this.suiteId = suiteId;
        this.tag = tag;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SuiteTagRuleId that)) {
            return false;
        }
        return Objects.equals(companyId, that.companyId)
                && Objects.equals(suiteId, that.suiteId)
                && Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(companyId, suiteId, tag);
    }
}
