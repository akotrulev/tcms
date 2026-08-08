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
     * The opaque key {@link AttachmentStore} stores the bytes under — three server-generated UUIDs.
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
            UUID companyId, UUID executionId, String storageKey, BlobUpload upload, UUID userId,
            Instant now) {

        return create(
                companyId, AttachmentOwner.EXECUTION, executionId, storageKey, upload, userId, now);
    }

    public static Attachment forStepResult(
            UUID companyId, UUID stepResultId, String storageKey, BlobUpload upload, UUID userId,
            Instant now) {

        return create(companyId, AttachmentOwner.STEP_RESULT, stepResultId, storageKey, upload,
                userId, now);
    }

    /** A file on a case's definition — {@code repository.CaseAttachmentService}'s only writer. */
    public static Attachment forCase(
            UUID companyId, UUID caseId, String storageKey, BlobUpload upload, UUID userId,
            Instant now) {

        return create(companyId, AttachmentOwner.CASE, caseId, storageKey, upload, userId, now);
    }

    /**
     * The same file on a <em>different</em> case — §129's clone, and the one factory that copies a
     * row rather than describing an upload.
     *
     * <p><strong>The checksum is copied, not recomputed, and that is the whole reason this is not a
     * {@code BlobUpload} round trip.</strong> Rebuilding an upload would mean reading the bytes into
     * heap purely to hash them — every byte of every file, on a path whose input size the product
     * does not choose — and it would hash <em>the bytes as they are now</em>. If the source blob has
     * silently corrupted since it was stored, its recorded checksum already disagrees with it; a
     * recomputed one on the copy would <em>agree</em>, so the clone would carry a fresh attestation
     * that corrupt bytes are correct and the corruption would survive exactly one clone. Carrying
     * the original forward keeps both rows saying the same true thing.
     *
     * <p>{@code createdBy} is the <strong>cloner</strong> and {@code createdAt} is now: this row is
     * a new file on a new case, and attributing it to whoever uploaded the original would put a name
     * against a file that person has never seen.
     */
    public static Attachment copyForCase(
            Attachment source, UUID caseId, String storageKey, UUID userId, Instant now) {

        Attachment attachment = new Attachment();
        attachment.id = UUID.randomUUID();
        attachment.companyId = source.companyId;
        attachment.ownerType = AttachmentOwner.CASE.name();
        attachment.ownerId = caseId;
        attachment.storageKey = storageKey;
        attachment.filename = source.filename;
        attachment.contentType = source.contentType;
        attachment.sizeBytes = source.sizeBytes;
        attachment.checksum = source.checksum;
        attachment.createdAt = now;
        attachment.createdBy = userId;
        return attachment;
    }

    /** A file on a documentation page — {@code documentation.DocPageAttachmentService}'s. */
    public static Attachment forDocPage(
            UUID companyId, UUID pageId, String storageKey, BlobUpload upload, UUID userId,
            Instant now) {

        return create(companyId, AttachmentOwner.DOC_PAGE, pageId, storageKey, upload, userId, now);
    }

    /** The bytes behind a {@code FILE} artifact — {@code documentation.ArtifactWriteService}'s. */
    public static Attachment forArtifact(
            UUID companyId, UUID artifactId, String storageKey, BlobUpload upload, UUID userId,
            Instant now) {

        return create(
                companyId, AttachmentOwner.ARTIFACT, artifactId, storageKey, upload, userId, now);
    }

    /**
     * The one place an {@code owner_type} string is written for an upload, and the five factories
     * above are the one place each is <em>chosen</em>. {@code copyForCase} spells {@code CASE} for
     * itself for the same reason it does not route through here — it has a checksum already, and a
     * shared body would have to take one as a parameter and would then be a factory that lets a
     * caller assert any digest it likes about bytes it never read.
     *
     * <p>No call site anywhere spells an owner type — the property §125 said
     * made the module extraction mechanical, kept deliberately as the owner set triples.
     */
    private static Attachment create(
            UUID companyId,
            AttachmentOwner ownerType,
            UUID ownerId,
            String storageKey,
            BlobUpload upload,
            UUID userId,
            Instant now) {

        Attachment attachment = new Attachment();
        attachment.id = UUID.randomUUID();
        attachment.companyId = companyId;
        attachment.ownerType = ownerType.name();
        attachment.ownerId = ownerId;
        attachment.storageKey = storageKey;
        attachment.filename = upload.filename();
        attachment.contentType = upload.contentType();
        attachment.sizeBytes = upload.sizeBytes();
        attachment.checksum = upload.checksum();
        attachment.createdAt = now;
        attachment.createdBy = userId;
        return attachment;
    }

    public UUID getId() {
        return id;
    }

    /** Which case, page, artifact, execution or step result this hangs off. Never resolved here. */
    public UUID getOwnerId() {
        return ownerId;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
