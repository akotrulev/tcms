package com.tcms.execution;

import com.tcms.project.AuthorshipGate;
import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Attaching, serving and removing the proof behind a result — the first writer of {@code V1}'s
 * {@code evidence} and {@code attachment} tables, and {@code evidence:attach}'s first consumer in
 * Java.
 *
 * <h2>Three gates, and they are not the same gate</h2>
 *
 * <ul>
 *   <li><strong>Attach</strong> needs {@code evidence:attach} — held by every role but Viewer, and
 *       unqualified, because attaching is <em>producing</em> (roles spec §A3).</li>
 *   <li><strong>Delete</strong> needs {@code evidence:delete}, which is <strong>{@code OWN}-
 *       qualified for Test Engineer</strong>: unmaking someone else's work is the line §A3 draws.
 *       This is the second caller of the authorship gate, after {@code CaseDeleteService}.</li>
 *   <li><strong>Download</strong> needs no permission at all beyond the project read gate. Anyone
 *       who may see the execution may see its evidence, and the catalogue has no
 *       {@code evidence:read} — inventing one here would be a permission no role is granted.</li>
 * </ul>
 *
 * <h2>Rows first, bytes second — in both directions</h2>
 *
 * <p>A filesystem does not roll back with the transaction. The order is therefore chosen so that
 * every failure leaves the <em>recoverable</em> half of the inconsistency:
 *
 * <ul>
 *   <li><strong>Attach</strong> writes the rows, then the bytes. A failure after the rows leaves a
 *       row whose blob is missing — the download answers 404 and an operator can find it by
 *       joining. Writing bytes first would leave a blob no row names, which is unreachable,
 *       uncollectable and invisible.</li>
 *   <li><strong>Delete</strong> removes the rows, then the bytes. A failure after the rows leaves
 *       an unreferenced blob an operator can sweep. Deleting bytes first would leave a thumbnail
 *       that renders and never opens, which reads as a product defect rather than as a partial
 *       operation.</li>
 * </ul>
 *
 * <p>Neither ordering is compensated automatically, and that is stated rather than hidden: this is
 * the first thing in the product whose state is not in Postgres.
 */
@Service
public class EvidenceService {

    /** Roles spec §A5 — every role but Viewer. Unqualified: attaching is producing. */
    private static final String ATTACH_EVIDENCE = "evidence:attach";

    /**
     * Roles spec §A5 — {@code OWN}-qualified for Test Engineer, which is why this service uses the
     * authorship gate for one of its two writes and the plain one for the other.
     */
    private static final String DELETE_EVIDENCE = "evidence:delete";

    private final ProjectService projects;
    private final TestRunRepository runs;
    private final TestExecutionRepository executions;
    private final EvidenceRepository evidenceRows;
    private final AttachmentRepository attachments;
    private final StepOutcomeRepository stepOutcomes;
    private final EvidenceStore store;
    private final EvidenceViews views;
    private final Clock clock;

    public EvidenceService(
            ProjectService projects,
            TestRunRepository runs,
            TestExecutionRepository executions,
            EvidenceRepository evidenceRows,
            AttachmentRepository attachments,
            StepOutcomeRepository stepOutcomes,
            EvidenceStore store,
            EvidenceViews views,
            Clock clock) {

        this.projects = projects;
        this.runs = runs;
        this.executions = executions;
        this.evidenceRows = evidenceRows;
        this.attachments = attachments;
        this.stepOutcomes = stepOutcomes;
        this.store = store;
        this.views = views;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's switch over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, run, execution or evidence — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** The permission this action needs is not held here at all. */
        record Forbidden() implements Outcome {
        }

        /** Held, but only over evidence the caller attached — and this is not theirs. */
        record NotTheAuthor() implements Outcome {
        }

        /** Field name to messages, for the form to land on its input. */
        record Invalid(java.util.Map<String, List<String>> errors) implements Outcome {
        }

        /** Attached. 201, with the rendered evidence. */
        record Attached(EvidenceView view) implements Outcome {
        }

        /** Removed. 204. */
        record Deleted() implements Outcome {
        }
    }

    /** The bytes plus what the response needs to describe them. */
    public record Download(String filename, String contentType, StoredBlob blob) {
    }

    @Transactional
    public Outcome attach(
            UUID userId, UUID projectId, UUID runId, UUID executionId, UUID stepId,
            EvidenceUpload upload) {

        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();

        // The read gates first, so an unreachable target is a bodiless 404 before any permission is
        // consulted — otherwise a 403 tells a caller that a run they cannot see exists.
        Optional<TestExecution> found = resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestExecution execution = found.get();

        GateResult<Outcome> gate = projects.writeGranted(userId, projectId, ATTACH_EVIDENCE,
                project -> write(
                        companyId, projectId, runId, userId, execution, stepId, upload, now));

        return switch (gate) {
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            // Answered by the read above, which ran first. The arm stays written and returns the
            // same 404 rather than throwing: the two gates could disagree only if a grant were
            // revoked between them, and 404 is still the right answer then.
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
        };
    }

    @Transactional
    public Outcome delete(
            UUID userId, UUID projectId, UUID runId, UUID executionId, UUID evidenceId) {

        Optional<TestExecution> found = resolve(userId, projectId, runId, executionId);
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestExecution execution = found.get();

        GateResult<Outcome> gated = projects.writeGrantedByAuthorship(
                userId, projectId, DELETE_EVIDENCE,
                (project, authorship) -> remove(execution, evidenceId, authorship));

        return switch (gated) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /**
     * The bytes, for a caller who may read the execution.
     *
     * <p>Not annotated {@code @Transactional}: the returned {@link StoredBlob} holds an open stream
     * the controller writes to the response <em>after</em> this returns, and the rows it needs are
     * read through {@link #resolve}, which brings its own. A transaction spanning the body write
     * would hold a database connection for the length of a download.
     */
    public Optional<Download> open(
            UUID userId, UUID projectId, UUID runId, UUID executionId, UUID evidenceId) {

        Optional<Evidence> found = readEvidence(userId, projectId, runId, executionId, evidenceId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Evidence evidence = found.get();

        Optional<Attachment> file = attachments.findById(evidence.getAttachmentId());
        if (file.isEmpty()) {
            return Optional.empty();
        }
        Attachment attachment = file.get();

        // Empty when the row exists and its blob does not — the orphan the write ordering
        // deliberately produces rather than the reverse. 404 is the honest answer: the product
        // cannot serve bytes it does not have, and pretending otherwise with an empty 200 would
        // hand the browser a corrupt file.
        return store.open(attachment.getStorageKey())
                .map(blob -> new Download(
                        attachment.getFilename(), attachment.getContentType(), blob));
    }

    /** One execution's evidence, rendered — for {@code ExecutionService}'s detail. */
    @Transactional(readOnly = true)
    public List<EvidenceView> forExecution(
            UUID companyId, UUID projectId, UUID runId, UUID executionId) {

        return views.render(companyId, projectId, runId,
                evidenceRows.findByExecutionIdOrderByCreatedAtAsc(executionId));
    }

    @Transactional(readOnly = true)
    Optional<Evidence> readEvidence(
            UUID userId, UUID projectId, UUID runId, UUID executionId, UUID evidenceId) {

        return resolve(userId, projectId, runId, executionId)
                .flatMap(execution ->
                        evidenceRows.findByIdAndExecutionId(evidenceId, execution.getId()));
    }

    private Outcome write(
            UUID companyId,
            UUID projectId,
            UUID runId,
            UUID userId,
            TestExecution execution,
            UUID stepId,
            EvidenceUpload upload,
            Instant now) {

        // Resolved INSIDE the gate's callback, so a caller who may not attach anything cannot use
        // this endpoint to discover which steps of a case have been marked — the same structural
        // reason ExecutionService validates a statusId only once the caller is allowed.
        UUID stepResultId = null;
        if (stepId != null) {
            Optional<StepOutcome> outcome =
                    stepOutcomes.findByExecutionIdAndStepId(execution.getId(), stepId);
            if (outcome.isEmpty()) {
                // Covers both "not a step of this case" and "a step nobody has marked yet". They
                // are one message on purpose: distinguishing them would answer whether a step id
                // exists to a caller who guessed it, and the fix is the same either way — record a
                // result on the step first. Inventing a step_result row here would record a verdict
                // nobody gave, which is worse than refusing.
                return new Outcome.Invalid(java.util.Map.of("stepId", List.of(
                        "Record a result for this step before attaching evidence to it.")));
            }
            stepResultId = outcome.get().getId();
        }

        String key = storageKey(companyId, execution.getId());

        // The attachment's owner is the step result when there is one, so attachment.owner_type
        // tells the truth about what the file documents — and a future sweep by owner finds it
        // where V1's polymorphic table says to look.
        Attachment attachment = stepResultId == null
                ? Attachment.forExecution(
                        companyId, execution.getId(), key, upload.filename(), upload.contentType(),
                        upload.sizeBytes(), upload.checksum(), userId, now)
                : Attachment.forStepResult(
                        companyId, stepResultId, key, upload.filename(), upload.contentType(),
                        upload.sizeBytes(), upload.checksum(), userId, now);
        attachments.saveAndFlush(attachment);

        Evidence evidence = Evidence.attach(
                companyId, execution.getId(), stepResultId, attachment.getId(), upload.caption(),
                userId, now);
        evidenceRows.saveAndFlush(evidence);

        store.put(key, upload.bytes());

        return new Outcome.Attached(
                views.render(companyId, projectId, runId, List.of(evidence)).getFirst());
    }

    private Outcome remove(TestExecution execution, UUID evidenceId, AuthorshipGate authorship) {
        Optional<Evidence> found =
                evidenceRows.findByIdAndExecutionId(evidenceId, execution.getId());
        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        Evidence evidence = found.get();

        // AFTER the lookup, because the answer depends on the row — and a caller who fails this
        // check has already been able to read the evidence, so 403 discloses nothing a 404 would
        // conceal. The same ordering CaseDeleteService uses, for the same reason.
        if (!authorship.mayActOn(evidence.getCreatedBy())) {
            return new Outcome.NotTheAuthor();
        }

        Optional<Attachment> file = attachments.findById(evidence.getAttachmentId());

        // HARD delete, unlike a case's. A screenshot removed on request has to actually leave the
        // disk: a soft-deleted row pointing at retained bytes is a deletion that did not delete,
        // which is the wrong answer for the one thing here a customer may need removed for privacy
        // reasons rather than for tidiness.
        evidenceRows.delete(evidence);
        file.ifPresent(attachments::delete);
        evidenceRows.flush();

        file.ifPresent(attachment -> store.delete(attachment.getStorageKey()));

        return new Outcome.Deleted();
    }

    /**
     * Where one file's bytes live. <strong>Every segment is a UUID this server produced</strong> —
     * the company, the execution, and a fresh random id per file.
     *
     * <p>Nothing the client sent appears here, which is the whole traversal defence: the uploaded
     * filename is stored in a column and rendered from there. The company leads so an operator
     * looking at the directory can see whose bytes are whose, and so a per-company sweep is a
     * subtree rather than a scan.
     */
    private static String storageKey(UUID companyId, UUID executionId) {
        return companyId + "/" + executionId + "/" + UUID.randomUUID();
    }

    /**
     * The execution, through every gate a two-segment path needs — the same three predicates
     * {@code ExecutionService#resolve} applies, and for the same reasons: dropping the last would
     * serve evidence from a different run through a path naming the run the client is looking at.
     */
    private Optional<TestExecution> resolve(
            UUID userId, UUID projectId, UUID runId, UUID executionId) {

        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> runs.findLiveInProject(project.getId(), runId))
                .flatMap(run -> executions.findInRun(run.getProjectId(), run.getId(), executionId));
    }
}
