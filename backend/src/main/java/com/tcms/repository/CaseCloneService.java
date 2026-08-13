package com.tcms.repository;

import com.tcms.project.ProjectService;
import com.tcms.storage.Attachment;
import com.tcms.storage.AttachmentOwner;
import com.tcms.storage.AttachmentRepository;
import com.tcms.storage.AttachmentStore;
import com.tcms.storage.BlobUpload;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cloning one case — plan §6v, decision round 3 #59, and the second half of step 8.
 *
 * <h2>A clone is a create, and that sentence is the whole design</h2>
 *
 * <p>This class reads the source, builds the {@link CaseUpdateRequest} the create endpoint already
 * takes, and hands it to {@link CaseCreateService}. It writes no {@code test_case} row, allocates no
 * key, validates nothing and decides no version number. The alternative — a clone that assembles its
 * own rows because it "knows" the source was valid — would be a <strong>third</strong> definition of
 * a legal case beside {@link CaseEdit} and the bulk path's reconstruction, and the one that diverges
 * is whichever is edited second. §128 refused a promote endpoint for the same reason one table over.
 *
 * <p>Six of §6v's clone rules then hold <em>by construction</em> rather than by being remembered
 * here, which is the point of the shape:
 *
 * <ul>
 *   <li>the display key is newly allocated, because {@link com.tcms.tenancy.DisplayKeys} is inside
 *       the create path and this class cannot reach it;</li>
 *   <li>the review state resets to {@code DRAFT}, because {@link TestCase#create} decides the status
 *       and no request field can carry one;</li>
 *   <li>version 1 is written by {@link CaseVersions#initial}, not by an edit;</li>
 *   <li>there is no shared history and no linkage, because there is no column to write one into;</li>
 *   <li>the execution history does not come along, because nothing reachable from a create request
 *       names a result;</li>
 *   <li>{@code Idempotency-Key} behaves exactly as it does on a create, because it <em>is</em> the
 *       create's handling — a retried clone answers 200 with the case the first attempt made,
 *       instead of leaving two near-identical cases behind.</li>
 * </ul>
 *
 * <h2>The gate is {@code case:create}, and the read is gated separately</h2>
 *
 * <p>The source is resolved through {@link ProjectService#readVisible} — reading a
 * case costs what reading a case costs — and the write gate is the create path's own
 * {@code case:create}. Nothing about the source changes, so {@code case:edit} would be the wrong
 * key: a caller who may add cases to a project may add this one.
 *
 * <p>The order that produces is deliberate. An unreachable project or a case that is not in it is a
 * bodiless 404 (§6t), and 403 is reached only after that — the caller is looking at the case and
 * pressing Clone, so concealing the refusal as a 404 would tell them their case had been deleted.
 *
 * <h2>What is stripped, and what happens if it is not</h2>
 *
 * <ol>
 *   <li><strong>{@code automationKey}, and with it the {@code AUTOMATED} status.</strong> The key is
 *       unique per company (§6c): copying it violates the constraint — or, if uniqueness were ever
 *       relaxed, makes an ingested CI result match two cases and land on whichever the matcher saw
 *       first (§117). <strong>Dropping it forces the status down</strong>, because
 *       {@link CaseEdit#validate} refuses {@code AUTOMATED} with no key; see
 *       {@link #clonedAutomationStatus}, which is the rule neither §6v nor this slice's plan had and
 *       the code found by refusing to write it.</li>
 *   <li><strong>Step ids.</strong> {@link CaseCreateService} passes an <em>empty</em> adoptable-step
 *       set, so leaving them on is a 400 rather than a theft — but it would be a 400 on the ordinary
 *       path, so they are stripped here. New ids are what keep the source's {@code step_result}
 *       history attached to the source.</li>
 *   <li><strong>{@code changeComment}.</strong> Null, for {@link CaseEdit#of}'s reason: it is
 *       hashed, so "cloned from TC-12" here would make the next ordinary save differ by the comment
 *       alone and write a version whose diff is empty.</li>
 *   <li><strong>{@code ownerId}.</strong> Refused by {@link CaseUpdateRequest} anyway, and the right
 *       answer regardless — a clone is the cloner's work.</li>
 * </ol>
 *
 * <p><del><strong>Condition opt-outs are not copied because none exist.</strong>
 * {@link CaseConditionOptout} has no writer anywhere in {@code src/main} — the table is readable and
 * unwritable, so no case in any deployment carries a row.</del> → <strong>§187 gave it one, and this
 * paragraph's own prescription is what got built</strong>: "a clone landing in the source's suite
 * should carry the opt-outs whose {@code suite_condition} belongs to that suite, and one landing
 * elsewhere should carry none." {@link #derive} does exactly that, through
 * {@link CaseConditions#declinedAtSuite}.
 *
 * <p>The two halves of the rule coincide today and will not always: since §98 flattened the suite
 * tree a case's chain is its own folder or nothing, so "attachments belonging to that suite" and
 * "attachments this case inherits" are the same set. Keep the narrower spelling — it is the one that
 * stays right if inheritance ever widens again, and the {@code declinedAtSuite} name says which
 * question is being asked.
 *
 * <p>They are carried through the <em>request</em> rather than written afterwards, which is not a
 * style choice: {@link CaseEdit} hashes the declines, so a clone that inserted the rows behind the
 * create path's back would record a v1 describing a case that declines nothing, and the next
 * ordinary save would be credited with turning off rules the clone turned off.
 *
 * <h2>Attachments are byte-copied, and §6v asks for the one thing that cannot work</h2>
 *
 * <p>§6v's table says attachments come across "by reference, not byte copy". Two {@code attachment}
 * rows sharing one {@code storage_key} means deleting either destroys the other's bytes:
 * {@link AttachmentStore#delete} is keyed and holds no reference count, and
 * {@link CaseAttachmentService#delete} has no way to learn of a second owner. The survivor then
 * renders as a file that 404s, with nothing having failed. So {@link AttachmentStore#copy} is the
 * fifth method on that port, and the cost is real and stated: cloning a case with a 25 MB file
 * copies 25 MB. It does not pass through heap.
 */
@Service
public class CaseCloneService {

    /**
     * A second action beside {@code case.created}, not a replacement for it — see
     * {@link #recordProvenance}.
     */
    private static final String AUDIT_ACTION = "case.cloned";

    private static final String AUDIT_TARGET = "test_case";

    private final ProjectService projects;
    private final CaseCreateService creation;
    private final TestCaseRepository cases;
    private final TestStepRepository steps;
    private final CaseClassificationRepository classifications;
    private final CaseConditionRepository conditions;
    private final CaseConditions caseConditions;
    private final CaseTagRepository tags;
    private final AttachmentRepository attachments;
    private final AttachmentStore store;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    CaseCloneService(
            ProjectService projects,
            CaseCreateService creation,
            TestCaseRepository cases,
            TestStepRepository steps,
            CaseClassificationRepository classifications,
            CaseConditionRepository conditions,
            CaseConditions caseConditions,
            CaseTagRepository tags,
            AttachmentRepository attachments,
            AttachmentStore store,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.creation = creation;
        this.cases = cases;
        this.steps = steps;
        this.classifications = classifications;
        this.conditions = conditions;
        this.caseConditions = caseConditions;
        this.tags = tags;
        this.attachments = attachments;
        this.store = store;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** {@link CaseCreateService.Outcome}'s five shapes, because a clone is a create. */
    public sealed interface Outcome {

        /** Unreachable project, or no such live case in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code case:create} not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages — the create path's, unedited. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The new case. 201. */
        record Created(TestCaseDetailView view) implements Outcome {
        }

        /** This intent already cloned. 200, and nothing was copied a second time. */
        record Replayed(TestCaseDetailView view) implements Outcome {
        }
    }

    /**
     * Clones a case.
     *
     * <p>One transaction with the create it delegates to — {@code REQUIRED} joins, so the new case,
     * its steps, its version row, the attachment rows and both audit rows commit together. The
     * attachment <em>bytes</em> are the one half that does not roll back, and they are written last
     * for the reason {@code storage}'s package header gives: the failure mode is a row whose blob is
     * missing, never a blob no row names.
     */
    @Transactional
    public Outcome cloneCase(
            UUID userId,
            UUID projectId,
            UUID caseId,
            CaseCloneRequest request,
            String idempotencyKey) {

        UUID companyId = CompanyContext.require();

        Optional<TestCase> found = projects
                .readVisible(userId, projectId,
                        project -> cases.findLiveInProject(project.getId(), caseId))
                // Two Optionals, flattened: "may this caller see the project" and "is this case in
                // it". Both answer the same bodiless 404, so nothing is lost by collapsing them.
                .flatMap(outer -> outer);

        if (found.isEmpty()) {
            return new Outcome.NotFound();
        }
        TestCase source = found.get();

        CaseCreateService.Outcome created =
                creation.create(userId, projectId, derive(source, request), idempotencyKey);

        return switch (created) {
            case CaseCreateService.Outcome.NotFound ignored -> new Outcome.NotFound();
            case CaseCreateService.Outcome.Forbidden ignored -> new Outcome.Forbidden();
            case CaseCreateService.Outcome.Invalid invalid -> new Outcome.Invalid(invalid.errors());
            // A replay copies NOTHING. The first attempt already copied the files and wrote the
            // provenance row; doing either again would attach a second set of identical files to a
            // case that already has them, which is the one way a retry-safe endpoint can still
            // corrupt the thing it protects.
            case CaseCreateService.Outcome.Replayed replayed ->
                    new Outcome.Replayed(replayed.view());
            case CaseCreateService.Outcome.Created fresh -> {
                Instant now = clock.instant();
                copyAttachments(companyId, userId, source, fresh.view().id(), now);
                recordProvenance(userId, projectId, source, fresh.view(), now);
                yield new Outcome.Created(fresh.view());
            }
        };
    }

    /**
     * The source case as a create request.
     *
     * <p>Field for field against {@link CaseUpdateRequest}, deliberately spelled out rather than
     * reusing {@link CaseEdit#of}: that reconstruction exists to <em>re-hash a stored case as
     * itself</em>, so it carries the ids, the automation key and the stored classification as a
     * separate argument. Every one of those is a thing a clone must not carry, and a shared helper
     * with a "cloning" flag would be one boolean away from copying a key that must never be copied.
     */
    private CaseUpdateRequest derive(TestCase source, CaseCloneRequest request) {
        List<CaseUpdateRequest.StepInput> clonedSteps = new ArrayList<>();
        for (TestStep step : steps.findForCase(source.getId())) {
            clonedSteps.add(new CaseUpdateRequest.StepInput(
                    // Null, not the source's id — see the class header. This is what makes the
                    // clone's steps new rows and leaves the source's results where they are.
                    null,
                    step.getKeyword() == null ? null : step.getKeyword().name(),
                    step.getAction(),
                    step.getTestData(),
                    step.getExpectedResult(),
                    step.getSharedStepId()));
        }

        List<UUID> classificationValueIds = new ArrayList<>();
        for (CaseClassification value : classifications.findForCase(source.getId())) {
            classificationValueIds.add(value.getValueId());
        }

        List<CaseConditionInput> preconditions = new ArrayList<>();
        List<CaseConditionInput> postconditions = new ArrayList<>();
        for (CaseCondition attached : conditions.findForCase(source.getId())) {
            CaseConditionInput reference = new CaseConditionInput(attached.getConditionId(), null);
            if (attached.getKind() == ConditionKind.PRE) {
                preconditions.add(reference);
            } else {
                postconditions.add(reference);
            }
        }

        List<String> clonedTags = new ArrayList<>();
        for (CaseTag tag : tags.findForCase(source.getId())) {
            clonedTags.add(tag.getTag());
        }

        // Null means the source's suite — the same reading `suiteId` below takes, resolved once so
        // the two cannot disagree about where this clone is landing.
        UUID targetSuiteId =
                request.suiteId() == null ? source.getSuiteId() : request.suiteId();

        // The class header's rule, §129's sentence with a writer behind it since §187. A clone
        // landing anywhere but the source's own folder carries no declines: it will inherit a
        // different folder's rules, and nobody has decided anything about those.
        List<UUID> clonedDeclines =
                java.util.Objects.equals(targetSuiteId, source.getSuiteId())
                        ? caseConditions.declinedAtSuite(source.getId(), source.getSuiteId())
                        : List.of();

        return new CaseUpdateRequest(
                request.title(),
                // Null means the source's suite, which is what "clone this" means unqualified. A
                // suite from another project is refused as a field error by the create path's own
                // live-suite check — there is no cross-project clone.
                targetSuiteId,
                source.getDescription(),
                source.getObjective(),
                source.getExpectedOutcome(),
                source.getStepFormat(),
                source.getGherkinSource(),
                source.getPriority(),
                source.getSeverity(),
                // AUTOMATED becomes TO_BE_AUTOMATED and the key is dropped — see the class header.
                // These two lines are one decision and must not be separated.
                clonedAutomationStatus(source.getAutomationStatus()),
                null,
                source.getNotAutomatableReason(),
                source.getEstimatedDurationSec(),
                clonedSteps,
                // See the class header: hashed, so a sentence here writes an empty-diff version on
                // the next ordinary save.
                null,
                classificationValueIds,
                clonedTags,
                // The two the create path refuses. Null on purpose: a clone that set an owner would
                // be asserting a membership nothing has checked.
                null,
                null,
                preconditions,
                postconditions,
                clonedDeclines);
    }

    /**
     * {@code AUTOMATED} becomes {@code TO_BE_AUTOMATED}; every other status is copied.
     *
     * <p><strong>This is the one rule the plan did not have, and it was found by the code refusing
     * to write it.</strong> §6v states two rules independently — copy the automation status, never
     * copy the automation key — and they are <em>jointly unsatisfiable</em> for the one status where
     * either matters: {@link CaseEdit#validate} requires a key when the status is {@code AUTOMATED},
     * so a clone carrying the status without the key is refused by the product's own validator and a
     * clone of an automated case is simply impossible. Nothing in either document says so.
     *
     * <p>The tempting fix is to relax the validator. It is wrong: an {@code AUTOMATED} case with no
     * key is one §117's matcher can never match, so it reports as automated for ever and no CI
     * result ever lands on it — a coverage number that counts a test nothing runs.
     *
     * <p>{@code TO_BE_AUTOMATED} is not a compromise, it is the accurate state. The clone genuinely
     * <em>is</em> intended to be automated and genuinely has no automation yet, which is what that
     * value means; §6c makes the key optional for exactly this shape. It also keeps §120's pair
     * honest in both directions — the clone raises neither {@code stale_automation} (it has never had
     * an ingested result) nor {@code undeclared_automation} (it is not claiming to be manual), which
     * is right, because a case nobody has run yet has nothing to be silent about.
     */
    private static AutomationStatus clonedAutomationStatus(AutomationStatus source) {
        return source == AutomationStatus.AUTOMATED ? AutomationStatus.TO_BE_AUTOMATED : source;
    }

    /**
     * Copies the source's files onto the clone, under fresh keys.
     *
     * <p>A source row whose bytes are already gone is <strong>skipped</strong>. The write ordering
     * this product uses produces exactly that orphan on purpose — a row whose download 404s, findable
     * by joining — and cloning one would turn an operator's single missing file into two, the second
     * of which nobody can trace to an incident. The clone simply has one file fewer, which is true.
     */
    private void copyAttachments(
            UUID companyId, UUID userId, TestCase source, UUID cloneId, Instant now) {

        for (Attachment file : attachments.findOwnedBy(
                AttachmentOwner.CASE, List.of(source.getId()))) {

            String key = BlobUpload.key(companyId, cloneId);

            // Asked BEFORE the row is written rather than after: the copy's own false return would
            // otherwise arrive with a row already flushed, and undoing it means either a delete on
            // the happy path's sibling or a known-bad row left behind.
            if (!store.exists(file.getStorageKey())) {
                continue;
            }

            attachments.saveAndFlush(
                    Attachment.copyForCase(file, cloneId, key, userId, now));

            // Last, and after the flush — the half that does not roll back goes after the half that
            // does. CaseAttachmentService's ordering, unchanged.
            store.copy(file.getStorageKey(), key);
        }
    }

    /**
     * The provenance row §6v asks for — a <strong>second</strong> audit event beside the
     * {@code case.created} the create path writes.
     *
     * <p>Two rows for one act, deliberately. The created row is what every existing audit reader
     * already understands and must keep seeing; "where did this case come from" is then answered by
     * filtering the action column for {@code case.cloned} rather than by parsing detail out of every
     * creation — {@link CaseBulkService}'s argument for giving approval its own action name instead
     * of burying it in an argument.
     *
     * <p>The source's <strong>key</strong> and not only its id, because the key is what an audit
     * reader recognises a case by and is the one thing the id does not tell them. Both keys, because
     * a row naming only where it came from makes the reader join to find out what it became.
     */
    private void recordProvenance(
            UUID userId, UUID projectId, TestCase source, TestCaseDetailView clone, Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                AUDIT_ACTION,
                AUDIT_TARGET,
                // The NEW case is the target: this event is about the row that came into existence.
                // The source is named in the payload, where it is a fact about the new case rather
                // than a claim that something happened to the old one — nothing did.
                List.of(clone.id()),
                "{\"key\":" + AuditEvent.jsonString(clone.key())
                        + ",\"clonedFrom\":" + AuditEvent.jsonString(source.getKey())
                        + ",\"clonedFromId\":" + AuditEvent.jsonString(source.getId().toString())
                        + "}",
                now));
    }
}
