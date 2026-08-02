package com.tcms.execution;

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
 * <h2>Why this lives in {@code execution} and not in a module of its own</h2>
 *
 * <p>{@code owner_type} spans six values ({@code CASE · STEP · EXECUTION · STEP_RESULT · DOC_PAGE ·
 * ARTIFACT}) and only two of them are this module's. The table is built to serve doc pages and
 * artifacts too, and {@code V1}'s own comment says why it is one table rather than six: <em>six
 * near-identical attachment tables is the worse trade</em>.
 *
 * <p>A {@code storage} module owning this would be the tidier home. It would also need plan §3
 * amended and two architecture tests changed — {@code ModuleStructureTest} hard-codes both the
 * module list and the rule that cross-module FKs point only at {@code tenancy}/{@code identity}/
 * {@code project} — to serve exactly <strong>one</strong> caller. So it lives here until there is a
 * second caller to design against, which is the point at which the right shape is knowable rather
 * than guessed.
 *
 * <p>What keeps that honest is {@link #forExecution} and {@link #forStepResult}: <strong>no call
 * site ever spells an owner type</strong>, so the day this moves, the writers that must move with it
 * are exactly the ones this file names.
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

    static Attachment forExecution(
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

    static Attachment forStepResult(
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

    UUID getId() {
        return id;
    }

    String getStorageKey() {
        return storageKey;
    }

    String getFilename() {
        return filename;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }
}
