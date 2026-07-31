package com.tcms.repository;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <p>Mapped thin on purpose. {@code type}, {@code parameters}, {@code usage_count} and
 * {@code search_vector} all belong to surfaces that do not exist yet — the typeahead (§6h) and the
 * pre-edit impact warning (§6f) — and a field mapped before its reader is a field nobody notices
 * has gone stale.
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

    /** Never null in practice — {@code body} is {@code NOT NULL} — but may legitimately be empty. */
    public List<String> getBody() {
        return body == null ? List.of() : List.copyOf(body);
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
