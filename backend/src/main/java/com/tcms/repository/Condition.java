package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A reusable pre- or post-condition from the project's library (plan §6f, ERD §4).
 *
 * <p>A suite or a case attaches one by <em>reference</em> while authoring, so editing the library
 * entry propagates; the reference is resolved and snapshotted into the case version at version-pin
 * time. That is the whole design: DRY while live, immutable once executed. This module reads the
 * live side only.
 *
 * <p>Still mapped thin, but less so than it was: {@code type} and {@code usage_count} were left
 * off while nothing read them, and the library endpoint ({@link ConditionController}) is the
 * reader they were waiting for — the contract marks both required on {@code Condition}.
 * {@code parameters} and {@code search_vector} stay unmapped on the original terms, because a
 * field mapped before its reader is a field nobody notices has gone stale: parameters belong to a
 * substitution surface nothing implements, and {@code search_vector} is a generated column no
 * caller ever wants the value of.
 */
@Entity
@Table(name = "condition")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Condition {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "title", nullable = false)
    private String title;

    /**
     * PRE, POST or BOTH — see {@link ConditionType} for why BOTH is not a third role.
     *
     * <p>{@code @Enumerated(STRING)} rather than the JPA default: the default is
     * {@code ORDINAL}, which stores 0/1/2 against a {@code TEXT CHECK (type IN ('PRE','POST',
     * 'BOTH'))} column and fails at insert — and, worse, would silently re-map every stored row if
     * a member were ever inserted into the middle of the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ConditionType type;

    /**
     * How many cases and suites reference this entry — the typeahead's ranking signal (§6h) and
     * the input to the pre-edit impact warning (§6f).
     *
     * <p><strong>Zero on every row today, and served as zero rather than hidden.</strong> The
     * column is {@code NOT NULL DEFAULT 0} and the writers that would maintain it — condition
     * attachment, case authoring — do not exist yet, so the honest answer is the stored one. Same
     * shape as {@code case_health}, which §25 left as a real query over a table nothing computes:
     * it starts ranking the moment something counts, with no change here.
     */
    @Column(name = "usage_count", nullable = false)
    private int usageCount;

    /**
     * The ordered steps — "log in" is realistically three actions, so the body is a list and not a
     * paragraph (ERD §4).
     *
     * <p>{@code @JdbcTypeCode(JSON)} rather than {@code columnDefinition = "jsonb"}, for the
     * reason {@code InstanceAuditEvent.changes} records at length: {@code columnDefinition} only
     * satisfies {@code ddl-auto: validate} while the parameter is still bound as {@code varchar},
     * and Postgres does not implicitly cast {@code varchar} to {@code jsonb}. Reading a
     * {@code List<String>} out of it goes through Hibernate's JSON format mapper, which is Jackson
     * here.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "body", nullable = false)
    private List<String> body;

    /** Set ⇒ retired from the library. Attachments to it survive; see {@link SuiteConditionView}. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** JPA only. */
    protected Condition() {
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public ConditionType getType() {
        return type;
    }

    public int getUsageCount() {
        return usageCount;
    }

    /** Never null in practice — {@code body} is {@code NOT NULL} — but may legitimately be empty. */
    public List<String> getBody() {
        return body == null ? List.of() : List.copyOf(body);
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
