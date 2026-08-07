package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.storage.Attachment;
import com.tcms.storage.AttachmentOwner;
import com.tcms.storage.AttachmentRepository;
import com.tcms.storage.AttachmentStore;
import com.tcms.storage.BlobUpload;
import com.tcms.storage.StoredBlob;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Files on a documentation page — <strong>{@code attachment.owner_type = 'DOC_PAGE'}'s first writer
 * since {@code V1} declared the value.</strong>
 *
 * <p>Decision round 3's #49, phase 2, and the second of the three owners that phase 1 built the
 * shared vocabulary for. A specification is not only prose: it is the wireframe, the signed-off
 * mock-up and the data sheet the paragraphs describe, and until this class existed the product could
 * hold the paragraph and not the picture it was written about.
 *
 * <h2>The gate is {@code doc:edit} on both writes, and it is not {@code doc:publish}</h2>
 *
 * <p>This module's defining split is that {@code doc:edit} writes content and {@code doc:publish}
 * moves the publication state, with Test Engineer holding the first and not the second (roles spec
 * §A2). <strong>An attachment is content.</strong> Somebody who may rewrite every paragraph on the
 * page may attach the diagram those paragraphs describe, and gating the file on {@code doc:publish}
 * would mean a Test Engineer could author a specification they could not illustrate.
 *
 * <p>The delete costs the same key, with no authorship arm — {@code CaseAttachmentService}'s
 * argument transferred, and the transfer is exact rather than by analogy. Its own header states the
 * rule and this module's package comment states the same one about blocks: {@code doc:edit} already
 * lets its holder delete a lead's paragraph, so refusing them the image beside it would make the
 * attachment stricter than the text that references it. No key was invented; the catalogue has no
 * {@code attachment:*}, and one seeded to nobody is a 403 no administrator can grant their way out
 * of.
 *
 * <h2>The download costs nothing beyond reading the page</h2>
 *
 * <p>Anyone who may read the page may read what is attached to it. {@link #open} resolves through
 * the same {@link ProjectService#readVisible} path {@link DocPageService#findPage} uses and asks
 * nothing further — which is also the only rule that does not require the SPA to reason about two
 * permissions per thumbnail.
 *
 * <h2>What deliberately does NOT carry over from the page's own writes</h2>
 *
 * <p><strong>No {@code If-Match}, and no version row.</strong> {@link DocPageWriteService} makes
 * both an edit's business because a page's blocks are its authored content, reconciled by id, and a
 * stale client carries stale block ids. An attachment is addressed by its own id and never
 * reconciled: two people attaching two files are not in conflict, they are both attaching, and a
 * 409 there would refuse a write nothing was racing. {@code doc_page_version} snapshots the blocks —
 * so writing a version row here would produce a history entry whose diff says nothing changed, which
 * is the call {@link PageVersions} already makes for a status change and §96's for a tag.
 *
 * <p><strong>An archived page still accepts files.</strong> There is no status arm, deliberately:
 * {@code ARCHIVED} exists to take a page out of circulation while keeping its links resolvable, and
 * a status that made a page's files unremovable would be a retention decision taken by a publishing
 * control. Inventing the arm would also make the attachment stricter than the blocks again, since
 * {@link DocPageWriteService#edit} has no status arm either.
 *
 * <h2>Rows first, bytes second</h2>
 *
 * <p>{@code EvidenceService}'s ordering, and the argument is the failure rather than the convention:
 * a filesystem does not roll back with the transaction. Attach writes the row then the bytes, so a
 * crash between them leaves a row whose blob is missing — a download that 404s, findable by joining.
 * Delete removes the row then the bytes, so a crash leaves an unreferenced blob an operator can
 * sweep. The reverse orderings leave a blob no row names (unreachable, uncollectable, invisible) and
 * a thumbnail that renders and never opens.
 */
@Service
public class DocPageAttachmentService {

    /** Roles spec §A2 — the same key {@link DocPageWriteService} costs for content. */
    private static final String EDIT_DOC = "doc:edit";

    private static final String AUDIT_TARGET = "doc_page";

    private final ProjectService projects;
    private final DocPageRepository pages;
    private final AttachmentRepository attachments;
    private final AttachmentStore store;
    private final AuditEventRepository auditEvents;
    private final ArtifactPeople people;
    private final Clock clock;

    DocPageAttachmentService(
            ProjectService projects,
            DocPageRepository pages,
            AttachmentRepository attachments,
            AttachmentStore store,
            AuditEventRepository auditEvents,
            ArtifactPeople people,
            Clock clock) {

        this.projects = projects;
        this.pages = pages;
        this.attachments = attachments;
        this.store = store;
        this.auditEvents = auditEvents;
        this.people = people;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, a page that is not in it, or no such attachment — bodiless 404. */
        record NotFound() implements Outcome {
        }

        /** {@code doc:edit} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** Field name to message, for the form to land on its input. */
        record Invalid(String field, String message) implements Outcome {
        }

        /**
         * Attached. 201, with the file already rendered.
         *
         * <p>A view rather than the row, so the uploader is resolved to a name <em>inside</em> this
         * service's transaction. {@link ArtifactPeople} reads {@code app_user} through the tenancy
         * filter, and a controller resolving it after the transaction closed would run with no
         * {@code app.current_company} and name nobody — fail-closed, so the field would simply be
         * null on every successful upload and nothing would fail anywhere. §121's finding.
         */
        record Attached(DocPageAttachmentView file) implements Outcome {
        }

        /** Removed. 204. */
        record Deleted() implements Outcome {
        }
    }

    /** The bytes plus what the response needs to describe them. */
    public record Download(String filename, String contentType, StoredBlob blob) {
    }

    @Transactional
    public Outcome attach(UUID userId, UUID projectId, UUID pageId, BlobUpload upload) {
        return gated(projects.writeGranted(
                userId, projectId, EDIT_DOC, project -> write(userId, project, pageId, upload)));
    }

    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID pageId, UUID attachmentId) {
        return gated(projects.writeGranted(
                userId, projectId, EDIT_DOC,
                project -> remove(userId, project, pageId, attachmentId)));
    }

    /**
     * The bytes, for anybody who may read the page.
     *
     * <p>Not annotated {@code @Transactional}: the returned {@link StoredBlob} holds an open stream
     * the controller writes to the response <em>after</em> this returns, and the rows it needs come
     * from {@link #resolve}, which brings its own. A transaction spanning the body write would hold
     * a database connection for the length of a download.
     */
    public Optional<Download> open(UUID userId, UUID projectId, UUID pageId, UUID attachmentId) {
        return resolve(userId, projectId, pageId, attachmentId)
                // Empty when the row exists and its blob does not — the orphan the write ordering
                // deliberately produces rather than the reverse. 404 is the honest answer; an empty
                // 200 would hand the browser a corrupt file.
                .flatMap(row -> store.open(row.getStorageKey())
                        .map(blob -> new Download(row.getFilename(), row.getContentType(), blob)));
    }

    /** The row, if the caller may read this page and the attachment is actually on it. */
    @Transactional(readOnly = true)
    Optional<Attachment> resolve(UUID userId, UUID projectId, UUID pageId, UUID attachmentId) {
        return projects
                .readVisible(userId, projectId,
                        project -> pages.findLiveInProject(project.getId(), pageId))
                // Two Optionals, flattened: the outer is "may this caller see the project", the
                // inner is "is this page in it". Collapsing them loses nothing because both answer
                // the same bodiless 404 — §6t's rule, and the reason the gate is not asked to tell
                // them apart.
                .flatMap(found -> found)
                .flatMap(page -> attachments.findOwnedById(
                        AttachmentOwner.DOC_PAGE, page.getId(), attachmentId));
    }

    private Outcome write(UUID userId, Project project, UUID pageId, BlobUpload upload) {
        // The second gate a two-segment path needs: is this page in THAT project. Without it,
        // /projects/{granted}/doc-pages/{ungranted} attaches to a page the caller never had.
        Optional<DocPage> found = pages.findLiveInProject(project.getId(), pageId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        DocPage page = found.get();

        if (upload.sizeBytes() <= 0) {
            // The controller already refuses an empty part; this is the same refusal one layer down,
            // where a future caller that is not that controller would otherwise write a zero-byte
            // row and a zero-byte blob that downloads as a corrupt file.
            return new Outcome.Invalid("file", "Choose a file to attach.");
        }

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        String key = BlobUpload.key(companyId, page.getId());

        Attachment row = Attachment.forDocPage(companyId, page.getId(), key, upload, userId, now);
        attachments.saveAndFlush(row);

        audit(project.getId(), userId, "doc_page.attachment.add", page, row.getFilename(),
                ",\"sizeBytes\":" + row.getSizeBytes(), now);

        // LAST, and after the flush — the half that does not roll back goes after the half that
        // does. See the class header.
        store.put(key, upload.bytes());

        return new Outcome.Attached(DocPageAttachmentView.of(
                project.getId(), page.getId(), row, uploader(companyId, userId)));
    }

    private Outcome remove(UUID userId, Project project, UUID pageId, UUID attachmentId) {
        Optional<DocPage> foundPage = pages.findLiveInProject(project.getId(), pageId);
        if (foundPage.isEmpty()) {
            return new Outcome.NotFound();
        }
        DocPage page = foundPage.get();

        Optional<Attachment> found = attachments.findOwnedById(
                AttachmentOwner.DOC_PAGE, page.getId(), attachmentId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Attachment row = found.get();

        Instant now = clock.instant();

        // HARD delete, unlike the page's own soft delete and like evidence's. A file removed on
        // request has to actually leave the disk: a soft-deleted row pointing at retained bytes is a
        // deletion that did not delete, which is the wrong answer for the one thing here a customer
        // may need removed for privacy reasons rather than for tidiness.
        attachments.delete(row);
        attachments.flush();

        // The filename is recorded here and NOT recoverable anywhere else: the row is hard-deleted
        // and the bytes go with it, so this line is the only surviving statement that the file ever
        // existed.
        audit(project.getId(), userId, "doc_page.attachment.remove", page, row.getFilename(), "",
                now);

        store.delete(row.getStorageKey());

        return new Outcome.Deleted();
    }

    /**
     * Collapses the gate's three states onto this class's own — {@link DocPageWriteService}'s
     * reason for the same helper, in a second service: two writes, and a fourth gate state must not
     * be handled in only one of them.
     */
    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The uploader, named. Always the caller, so this is one lookup and never a batch — the batch
     * form is {@link PageAttachments}, which resolves a whole page's worth at once.
     */
    private UserRefView uploader(UUID companyId, UUID userId) {
        Map<UUID, UserRefView> named = people.resolve(companyId, List.of(userId));
        return named.get(userId);
    }

    /**
     * The page's own audit shape, plus the filename.
     *
     * <p>{@code doc_page} as the target rather than an {@code attachment} target of its own: the
     * question an audit reader asks is "what happened to DOC-14", and a row keyed on an id that no
     * longer exists after the hard delete answers nothing. Same key/title/status prefix
     * {@link DocPageWriteService} writes, so the page's history reads as one sequence.
     */
    private void audit(
            UUID projectId, UUID userId, String action, DocPage page, String filename, String extra,
            Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(page.getId()),
                // JSONB, not text — audit_event.changes is a jsonb column, so a bare string is a
                // 500 at flush time rather than a compile error. The filename is client-supplied
                // free text and goes through the escaper its siblings use; a quote in a filename is
                // ordinary.
                "{\"key\":\"" + page.getKey() + "\",\"title\":"
                        + AuditEvent.jsonString(page.getTitle())
                        + ",\"status\":\"" + page.getStatus() + "\",\"filename\":"
                        + AuditEvent.jsonString(filename) + extra + "}",
                now));
    }
}
