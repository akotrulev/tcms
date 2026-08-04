package com.tcms.reporting;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A rendered report that exists on disk — {@code V1}'s {@code report_artifact}, first writer.
 *
 * <p>The table has been in the schema since M0 with no entity and no code. Everything about it was
 * designed before there was a pipeline to fill it, and two of its columns are the reason it is worth
 * reading the file rather than inferring the shape:
 *
 * <ul>
 *   <li>{@link #filterSnapshot} is {@code NOT NULL} and {@code V1}'s comment says why in one line —
 *       "a report you cannot reproduce is a report nobody trusts" (§6e). It is what the report was
 *       computed <em>over</em>, frozen, so a number read six months later can be explained.
 *   <li>{@link #dataWatermark} is how fresh the underlying facts were. §87 sets it from the
 *       {@code execution_fact} refresh the job runs immediately before computing, so it is exact
 *       rather than "some time in the last quarter hour".
 * </ul>
 *
 * <h2>No {@code project_id}, and the download gate must therefore go through the job</h2>
 *
 * <p>{@code V1} scoped this table by {@code company_id} and {@code definition_id} only. The
 * project-scoped download route (§87) proves an artifact belongs to the project in its path by way
 * of the {@code report_job} row that names it, which is total in the designed system — every
 * artifact is produced by a job. Recorded because it looks like an omission and is not, and because
 * the day something produces an artifact without a job, that route needs a second answer.
 *
 * <h2>Immutable once written</h2>
 *
 * <p>Every column is {@code updatable = false}. A report is a statement about a moment; editing one
 * in place changes what an already-circulated link says, which is the property §6e's determinism
 * bullet exists to protect. Regenerating produces a new row.
 */
@Entity
@Table(name = "report_artifact")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class ReportArtifact {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** Null ⇒ produced by an ad-hoc request rather than by a saved definition (R5). */
    @Column(name = "definition_id", updatable = false)
    private UUID definitionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, updatable = false)
    private ReportFormat format;

    /**
     * The opaque key the bytes live under in {@link ReportStore}. Server-generated; nothing a client
     * sends becomes part of it.
     */
    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    /** See the class header. {@code NOT NULL} in {@code V1} on purpose. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_snapshot", nullable = false, updatable = false)
    private String filterSnapshot;

    @Column(name = "data_watermark", updatable = false)
    private Instant dataWatermark;

    @Column(name = "generated_by", updatable = false)
    private UUID generatedBy;

    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;

    /** JPA only. */
    protected ReportArtifact() {
    }

    static ReportArtifact of(
            UUID companyId,
            UUID definitionId,
            ReportFormat format,
            String storageKey,
            String filterSnapshot,
            Instant dataWatermark,
            UUID userId,
            Instant now) {

        ReportArtifact artifact = new ReportArtifact();
        artifact.id = UUID.randomUUID();
        artifact.companyId = companyId;
        artifact.definitionId = definitionId;
        artifact.format = format;
        artifact.storageKey = storageKey;
        artifact.filterSnapshot = filterSnapshot;
        artifact.dataWatermark = dataWatermark;
        artifact.generatedBy = userId;
        artifact.generatedAt = now;
        return artifact;
    }

    UUID getId() {
        return id;
    }

    UUID getDefinitionId() {
        return definitionId;
    }

    ReportFormat getFormat() {
        return format;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getFilterSnapshot() {
        return filterSnapshot;
    }

    Instant getDataWatermark() {
        return dataWatermark;
    }

    UUID getGeneratedBy() {
        return generatedBy;
    }

    Instant getGeneratedAt() {
        return generatedAt;
    }
}
