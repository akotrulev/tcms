package com.tcms.repository;

import com.tcms.api.UserRef;
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
 * Files on a test case's definition — <strong>{@code attachment.owner_type = 'CASE'}'s first writer
 * since {@code V1} declared the value.</strong>
 *
 * <p>Decision round 3's #49, and an M1 deliverable four rounds of scope assessment have counted as
 * unbuilt. The table has been polymorphic over six owner types since the beginning and
 * {@code execution.EvidenceService} has written two of them since §67; this is the third, and the
 * first that is not a record of something that happened.
 *
 * <h2>The gate is {@code case:edit} on BOTH writes, and the {@code OWN} arm is deliberately absent</h2>
 *
 * <p>The tempting move is to copy {@code evidence:delete}'s authorship arm — a Test Engineer may
 * remove evidence they attached and not a colleague's. It is wrong here, and the reason is what the
 * two things <em>are</em>. Evidence is a record of something that happened, and unmaking somebody
 * else's record is the line the roles spec §A3 draws. <strong>A case attachment is part of the case
 * definition</strong>, which {@code case:edit} already owns wholesale: a Test Engineer holding it may
 * today rewrite every step, the title, the objective and the preconditions of a lead's case.
 *
 * <p>An authorship arm here would make removing the screenshot <em>stricter than removing the step
 * that references it</em> — incoherent, and incoherent in the direction that reads as security, which
 * is the kind that survives review. So both writes cost the same key, and the honesty is that this
 * is a permission the product already granted, not a new one.
 *
 * <p>No key was invented, which is the standing rule: the roles spec is authoritative, and a key
 * seeded to nobody is a 403 no administrator can grant their way out of (the argument that has kept
 * {@code evidence:read} and {@code suite:comment} out of the catalogue since M0).
 *
 * <h2>The download costs nothing beyond reading the case</h2>
 *
 * <p>There is no {@code attachment:read} and there must not be. Anyone who may read the case may
 * read what is attached to it — which is also the only rule that does not require the SPA to reason
 * about two permissions per row. {@link #open} therefore resolves through the same
 * {@link ProjectService#readVisible} path the detail endpoint uses and asks nothing further.
 *
 * <h2>Rows first, bytes second — inherited, and stated rather than assumed</h2>
 *
 * <p>{@code EvidenceService}'s argument carries over unchanged because the failure is the same one:
 * a filesystem does not roll back with the transaction. Attach writes the row then the bytes, so a
 * failure between them leaves a row whose blob is missing — a download that 404s, findable by
 * joining. Delete removes the row then the bytes, so a failure leaves an unreferenced blob an
 * operator can sweep. The reverse orderings leave, respectively, a blob no row names (unreachable,
 * uncollectable, invisible) and a thumbnail that renders and never opens.
 *
 * <p><strong>What does NOT carry over is the closed-run freeze.</strong> Evidence is frozen once its
 * run ends, because it documents that run. A case has no such moment — it is edited for as long as
 * it exists — so there is no equivalent conflict arm here, and inventing one keyed on the case's
 * lifecycle status would make an archived case's files unremovable by anybody.
 */
@Service
public class CaseAttachmentService {

    /** The same key {@link CaseUpdateService} and {@link CaseQuarantineService} cost. */
    private static final String EDIT_CASE = "case:edit";

    private static final String AUDIT_TARGET = "test_case";

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final AttachmentRepository attachments;
    private final AttachmentStore store;
    private final AuditEventRepository auditEvents;
    private final CaseOwners people;
    private final Clock clock;

    CaseAttachmentService(
            ProjectService projects,
            TestCaseRepository cases,
            AttachmentRepository attachments,
            AttachmentStore store,
            AuditEventRepository auditEvents,
            CaseOwners people,
            Clock clock) {

        this.projects = projects;
        this.cases = cases;
        this.attachments = attachments;
        this.store = store;
        this.auditEvents = auditEvents;
        this.people = people;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, a case that is not in it, or no such attachment — bodiless 404. */
        record NotFound() implements Outcome {
        }

        /** {@code case:edit} is not held here. */
        record Forbidden() implements Outcome {
        }

        /** Field name to message, for the form to land on its input. */
        record Invalid(String field, String message) implements Outcome {
        }

        /**
         * Attached. 201, with the file already rendered.
         *
         * <p>A view rather than the row, so the uploader is resolved to a name <em>inside</em> this
         * service's transaction. {@link CaseOwners} reads {@code app_user} through the tenancy
         * filter, and a controller resolving it after the transaction closed would run with no
         * {@code app.current_company} and name nobody — fail-closed, so the field would simply be
         * null on every successful upload and nothing would fail. §121's finding, one slice on.
         */
        record Attached(CaseAttachmentView file) implements Outcome {
        }

        /** Removed. 204. */
        record Deleted() implements Outcome {
        }
    }

    /** The bytes plus what the response needs to describe them. */
    public record Download(String filename, String contentType, StoredBlob blob) {
    }

    @Transactional
    public Outcome attach(UUID userId, UUID projectId, UUID caseId, BlobUpload upload) {
        GateResult<Outcome> gated = projects.writeGranted(
                userId, projectId, EDIT_CASE, project -> write(userId, project, caseId, upload));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID caseId, UUID attachmentId) {
        GateResult<Outcome> gated = projects.writeGranted(
                userId, projectId, EDIT_CASE,
                project -> remove(userId, project, caseId, attachmentId));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The bytes, for anybody who may read the case.
     *
     * <p>Not annotated {@code @Transactional}: the returned {@link StoredBlob} holds an open stream
     * the controller writes to the response <em>after</em> this returns, and the rows it needs come
     * from {@link #resolve}, which brings its own. A transaction spanning the body write would hold
     * a database connection for the length of a download — {@code EvidenceService#open}'s reasoning,
     * and the reason this method is not simply annotated for tidiness.
     */
    public Optional<Download> open(UUID userId, UUID projectId, UUID caseId, UUID attachmentId) {
        return resolve(userId, projectId, caseId, attachmentId)
                // Empty when the row exists and its blob does not — the orphan the write ordering
                // deliberately produces rather than the reverse. 404 is the honest answer; an empty
                // 200 would hand the browser a corrupt file.
                .flatMap(row -> store.open(row.getStorageKey())
                        .map(blob -> new Download(row.getFilename(), row.getContentType(), blob)));
    }

    /** The row, if the caller may read this case and the attachment is actually on it. */
    @Transactional(readOnly = true)
    Optional<Attachment> resolve(UUID userId, UUID projectId, UUID caseId, UUID attachmentId) {
        return projects
                .readVisible(userId, projectId,
                        project -> cases.findLiveInProject(project.getId(), caseId))
                // Two Optionals, flattened: the outer is "may this caller see the project", the
                // inner is "is this case in it". Collapsing them loses nothing here because both
                // answer the same bodiless 404 — §6t's rule, and the reason the gate is not asked
                // to distinguish them.
                .flatMap(found -> found)
                .flatMap(row -> attachments.findOwnedById(
                        AttachmentOwner.CASE, row.getId(), attachmentId));
    }

    private Outcome write(UUID userId, Project project, UUID caseId, BlobUpload upload) {
        // The second gate a two-segment path needs: is this case in THAT project. Without it,
        // /projects/{granted}/cases/{ungranted} attaches to a case the caller was never given.
        Optional<TestCase> found = cases.findLiveInProject(project.getId(), caseId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestCase testCase = found.get();

        if (upload.sizeBytes() <= 0) {
            // The controller already refuses an empty part; this is the same refusal one layer down,
            // where a future caller that is not that controller would otherwise write a zero-byte
            // row and a zero-byte blob that downloads as a corrupt file.
            return new Outcome.Invalid("file", "Choose a file to attach.");
        }

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        String key = BlobUpload.key(companyId, testCase.getId());

        Attachment row = Attachment.forCase(companyId, testCase.getId(), key, upload, userId, now);
        attachments.saveAndFlush(row);

        // JSONB, not text — audit_event.changes is a jsonb column and a bare string is a 500 at
        // flush time rather than a compile error. The filename is client-supplied free text, so it
        // goes through the same escaper its siblings use; a quote in a filename is ordinary.
        auditEvents.save(AuditEvent.record(
                project.getId(), userId, "case.attachment.add", AUDIT_TARGET,
                List.of(testCase.getId()),
                "{\"key\":" + AuditEvent.jsonString(testCase.getKey())
                        + ",\"filename\":" + AuditEvent.jsonString(row.getFilename())
                        + ",\"sizeBytes\":" + row.getSizeBytes() + "}",
                now));

        // LAST, and after the flush — the half that does not roll back goes after the half that
        // does. See the class header.
        store.put(key, upload.bytes());

        return new Outcome.Attached(CaseAttachmentView.of(
                project.getId(), testCase.getId(), row, uploader(companyId, userId)));
    }

    private Outcome remove(UUID userId, Project project, UUID caseId, UUID attachmentId) {
        Optional<TestCase> foundCase = cases.findLiveInProject(project.getId(), caseId);
        if (foundCase.isEmpty()) {
            return new Outcome.NotFound();
        }

        Optional<Attachment> found = attachments.findOwnedById(
                AttachmentOwner.CASE, foundCase.get().getId(), attachmentId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Attachment row = found.get();

        Instant now = clock.instant();

        // HARD delete, like evidence's and unlike a case's. A file removed on request has to
        // actually leave the disk: a soft-deleted row pointing at retained bytes is a deletion that
        // did not delete, which is the wrong answer for the one thing here a customer may need
        // removed for privacy reasons rather than for tidiness.
        attachments.delete(row);
        attachments.flush();

        // The filename is recorded here and NOT recoverable anywhere else: the row is hard-deleted
        // and the bytes go with it, so this line is the only surviving statement that the file ever
        // existed. §121's argument for keeping a quarantine reason in the trail, one entity over.
        auditEvents.save(AuditEvent.record(
                project.getId(), userId, "case.attachment.remove", AUDIT_TARGET,
                List.of(foundCase.get().getId()),
                "{\"key\":" + AuditEvent.jsonString(foundCase.get().getKey())
                        + ",\"filename\":" + AuditEvent.jsonString(row.getFilename()) + "}",
                now));

        store.delete(row.getStorageKey());

        return new Outcome.Deleted();
    }

    /**
     * The uploader, named. Always the caller, so this is one lookup and never a batch — the batch
     * form is {@link CaseRenderer#attachmentsFor}, which resolves a page's worth at once.
     */
    private UserRef uploader(UUID companyId, UUID userId) {
        Map<UUID, UserRef> named = people.resolve(companyId, List.of(userId));
        return named.get(userId);
    }
}
