package com.tcms.repository;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link TestCaseDraft}: {@code (case_id, user_id)} — the table's own primary key
 * since {@code V64} (audit D1).
 *
 * <p>A class rather than a record because JPA's {@code @IdClass} contract wants a no-arg constructor
 * and mutable fields; {@link SuiteCaseId} says the same.
 *
 * <p>{@code company_id} does <strong>not</strong> lead it, and that is {@link CaseTagId}'s position
 * rather than an exception to ERD §0.1 point 3: a key rooted in {@code case_id} already determines
 * the company, and the convention check pins the ordering of indexes that <em>contain</em> the
 * column rather than requiring every key to contain it.
 */
public class TestCaseDraftId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID caseId;
    private UUID userId;

    /** JPA only. */
    public TestCaseDraftId() {
    }

    public TestCaseDraftId(UUID caseId, UUID userId) {
        this.caseId = caseId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TestCaseDraftId that)) {
            return false;
        }
        return Objects.equals(caseId, that.caseId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caseId, userId);
    }
}
