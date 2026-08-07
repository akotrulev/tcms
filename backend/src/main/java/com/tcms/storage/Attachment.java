package com.tcms.storage;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * A stored blob's metadata row — {@code V1}'s polymorphic {@code attachment} table, getting its
 * first writer here after being declared since the beginning.
 *
 * <h2>~~Why this lives in {@code execution} and not in a module of its own~~ → moved, §125</h2>
 *
 * <p>This file used to argue for staying in {@code execution}: {@code owner_type} spans six values
 * ({@code CASE · STEP · EXECUTION · STEP_RESULT · DOC_PAGE · ARTIFACT}) and only two of them were
 * that module's, but a {@code storage} module <em>"would need plan §3 amended and two architecture
 * tests changed … to serve exactly <strong>one</strong> caller. So it lives here until there is a
 * second caller to design against, which is the point at which the right shape is knowable rather
 * than guessed."</em>
 *
 * <p>Decision round 3's #49 creates that second caller twice over — {@code CASE}, {@code DOC_PAGE}
 * and {@code ARTIFACT} get writers — and #51 took the move as its own slice <strong>before</strong>
 * them, deliberately: done in the same diff, a module extraction and a feature are
 * indistinguishable, and the extraction is the half a reviewer cannot check by reading the feature.
 *
 * <p>The prediction in the old text held. {@link #forExecution} and {@link #forStepResult} meant
 * <strong>no call site ever spelled an owner type</strong>, so the move was mechanical: a package
 * line, and the two factories going {@code public}. The owner vocabulary is now this module's —
 * a new owner is a factory here, not a string at a call site.
 *
 * <h2>Immutable after insert</h2>
 *
 * <p>Every column is {@code updatable = false}. An attachment row describes bytes that were written
 * once under a key that is never reused; editing the filename or the size after the fact would make
 * the row disagree with the blob, and there is no operation in the product that wants to. Replacing
 * evidence is a delete and a fresh attach, which leaves an honest {@code created_at}.
 */
@Entity
@Table(name = "attachment")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class Attachment {

    /** Evidence attached to the execution as a whole. */
    private static final String OWNER_EXECUTION = "EXECUTION";

    /** Evidence attached to one step's recorded result. */
    private static final String OWNER_STEP_RESULT = "STEP_RESULT";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "owner_type", nullable = false, updatable = false)
    private String ownerType;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    /**
     * The opaque key {@link EvidenceStore} stores the bytes under — three server-generated UUIDs.
     * Never derived from {@link #filename}, which is the whole of the traversal defence.
     */
    @Column(name = "storage_key", nullable = false, updatable = false)
    private String storageKey;

    /**
     * What the caller called the file. A <strong>column</strong>, deliberately — it is echoed in
     * {@code Content-Disposition} and shown in the UI, and it never becomes part of a path.
     */
    @Column(name = "filename", nullable = false, updatable = false)
    private String filename;

    @Column(name = "content_type", nullable = false, updatable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    /** SHA-256 of the bytes. Without it, silent corruption of the blob store is undetectable. */
    @Column(name = "checksum", updatable = false)
    private String checksum;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    /** JPA only. */
    protected Attachment() {
    }

    public static Attachment forExecution(
            UUID companyId,
            UUID executionId,
            String storageKey,
            String filename,
            String contentType,
            long sizeBytes,
            String checksum,
            UUID userId,
            Instant now) {

        return create(companyId, OWNER_EXECUTION, executionId, storageKey, filename, contentType,
                sizeBytes, checksum, userId, now);
    }

    public static Attachment forStepResult(
            UUID companyId,
            UUID stepResultId,
            String storageKey,
            String filename,
            String contentType,
            long sizeBytes,
            String checksum,
            UUID userId,
            Instant now) {

        return create(companyId, OWNER_STEP_RESULT, stepResultId, storageKey, filename, contentType,
                sizeBytes, checksum, userId, now);
    }

    private static Attachment create(
            UUID companyId,
            String ownerType,
            UUID ownerId,
            String storageKey,
            String filename,
            String contentType,
            long sizeBytes,
            String checksum,
            UUID userId,
            Instant now) {

        Attachment attachment = new Attachment();
        attachment.id = UUID.randomUUID();
        attachment.companyId = companyId;
        attachment.ownerType = ownerType;
        attachment.ownerId = ownerId;
        attachment.storageKey = storageKey;
        attachment.filename = filename;
        attachment.contentType = contentType;
        attachment.sizeBytes = sizeBytes;
        attachment.checksum = checksum;
        attachment.createdAt = now;
        attachment.createdBy = userId;
        return attachment;
    }

    public UUID getId() {
        return id;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
