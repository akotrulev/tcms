package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.DisplayKeys;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, editing, publishing and deleting documentation pages — <strong>the writes that make the
 * documentation reads honest.</strong>
 *
 * <p>Until this class existed the endpoints served a table nothing could write to, so every
 * provisioned company's documentation space was permanently empty and the screen rendering it could
 * only ever show its empty state. Three sessions (§54, §55, §56) recorded that as the reason not to
 * ship the reads alone.
 *
 * <h2>Two permissions, and they are two endpoints</h2>
 *
 * <p>{@code doc:edit} for content, {@code doc:publish} for the publication state. Test Engineer holds
 * the first and not the second (roles spec §A2). They are separate endpoints rather than one that
 * branches on which fields the body carried, because a gate that depends on the payload cannot be
 * rendered as a disabled control with a reason — which is the whole of §31.
 *
 * <p>Delete asks {@code doc:edit} rather than a permission of its own, which is the opposite of the
 * {@code suite:manage} / {@code suite:delete} split. That split exists because deleting a folder
 * reaches <em>other people's cases</em> through one action with no preview; a page has no subtree of
 * anybody else's work under it, and its own children are refused rather than cascaded. The reasoning
 * that justified the split does not apply, and copying it anyway would put a permission in the
 * catalogue that the roles spec does not have.
 *
 * <h2>What commits together</h2>
 *
 * <p>On a create: the counter increment, the page row, its blocks, v1 and the audit row, in one
 * transaction. The counter is in it for {@link DisplayKeys}' reason — its row lock must be held
 * until the row that used the number commits.
 */
@Service
public class DocPageWriteService {

    /** Roles spec §A2. Held by everyone who writes, including Test Engineer. */
    private static final String EDIT_DOC = "doc:edit";

    /** Roles spec §A2. Leads and above; deliberately not held by Test Engineer. */
    private static final String PUBLISH_DOC = "doc:publish";

    private static final String AUDIT_TARGET = "doc_page";

    /**
     * The longest {@code Idempotency-Key} this endpoint will store — {@code CaseCreateService}'s
     * cap, and the sixth copy of this constant. {@code IdempotencyKeyCapTest} is what keeps the
     * copies honest; {@code SuiteWriteService}'s field carries the reason there is no shared symbol.
     */
    private static final int MAX_IDEMPOTENCY_KEY = 200;

    private final ProjectService projects;
    private final DocPageRepository pages;
    private final DocBlockRepository blocks;
    private final BlockLinks blockLinks;
    private final PageAttachments attachments;
    private final DocSpaceRepository spaces;
    private final PageVersions versions;
    private final DisplayKeys displayKeys;
    private final AuditEventRepository auditEvents;
    /** How {@code repository} learns that a mirrored page moved — see {@link #edit}'s notice. */
    private final Outbox outbox;
    private final Clock clock;

    public DocPageWriteService(
            ProjectService projects,
            DocPageRepository pages,
            DocBlockRepository blocks,
            BlockLinks blockLinks,
            PageAttachments attachments,
            DocSpaceRepository spaces,
            PageVersions versions,
            DisplayKeys displayKeys,
            AuditEventRepository auditEvents,
            Outbox outbox,
            Clock clock) {

        this.projects = projects;
        this.pages = pages;
        this.blocks = blocks;
        this.blockLinks = blockLinks;
        this.attachments = attachments;
        this.spaces = spaces;
        this.versions = versions;
        this.displayKeys = displayKeys;
        this.auditEvents = auditEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** What happened. Sealed, so every controller mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Unreachable project, or a page that is not in it — one bodiless 404 (§6t). */
        record NotFound() implements Outcome {
        }

        /** Readable, and the permission the action needs is not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The client's {@code If-Match} did not match the stored {@code lockVersion}. */
        record Stale() implements Outcome {
        }

        /**
         * The write was refused because it would remove something another record points at.
         *
         * <p>Carries counts rather than a rendered sentence — a service that returns prose is a
         * service two callers eventually word differently.
         *
         * @param childPages  child pages standing in the way of a delete
         * @param linkedCases links that would be destroyed by removing blocks
         */
        record WouldBreak(long childPages, long linkedCases) implements Outcome {
        }

        /** A new page. 201. */
        record Created(DocPageView view) implements Outcome {
        }

        /**
         * A retry of a create that already succeeded. 200 rather than 201, so a client can tell "I
         * made this" from "I already had this" — {@code CaseCreateService.Outcome.Replayed}'s
         * terms. Distinct from {@link Updated} on purpose: they carry the same view and mean
         * opposite things, and collapsing them would make "nothing changed" indistinguishable from
         * "your edit landed" in every log that records the outcome name.
         */
        record Replayed(DocPageView view) implements Outcome {
        }

        /** An edited or re-statused page. 200. */
        record Updated(DocPageView view) implements Outcome {
        }

        /** Soft-deleted. 204. */
        record Deleted() implements Outcome {
        }
    }

    /**
     * Creates a page.
     *
     * <p>~~No {@code Idempotency-Key}, for {@code SuiteWriteService#create}'s reason: a duplicate
     * page is visible in the tree the moment the create returns, next to its twin, and is removed
     * by the delete this same slice ships. The header earns its column where a duplicate is
     * expensive to spot among hundreds of rows.~~ → <strong>superseded by {@code V42}</strong>.
     *
     * <p>It was the weakest of the copies of that argument, and this is the module where it fails
     * hardest. A page is a <em>link target</em>: {@code doc_link} points at
     * {@code (page_id, block_id)}, and ERD resolved question #18 makes a block this product's whole
     * model of a requirement. So a duplicate page is two sets of block ids, traceability split
     * across both, and a delete of the loser that destroys its links rather than merging them —
     * the one duplicate in this family a person cannot clean up by pressing delete. {@code V42}'s
     * header carries the rest.
     *
     * @param idempotencyKey the request's {@code Idempotency-Key} header, or null when absent
     */
    @Transactional
    public Outcome create(
            UUID userId, UUID projectId, DocPageInput input, String idempotencyKey) {
        return gated(projects.writeGranted(userId, projectId, EDIT_DOC,
                project -> applyCreate(userId, project, input, null, idempotencyKey)));
    }

    /**
     * Creates a page that documents another artifact — see {@link DocSubject}.
     *
     * <p>Package-private, ungated and <strong>not</strong> {@code @Transactional}: the caller has
     * already opened the transaction and run its own gate, and it has checks of its own to make in
     * between. {@link PlanDocumentService} is the only caller and its ordering is the reason this is
     * shaped this way — the permission, then the subject's existence, then whether it already has a
     * document, and only then this.
     *
     * <p>Re-running {@code writeGranted} here instead would evaluate the same permission twice per
     * request and, worse, would make the refusal order depend on which of two services happened to
     * ask first.
     */
    Outcome writeSubjectPage(UUID userId, Project project, DocSubject subject, DocPageInput input) {
        // No idempotency key, and not because one was forgotten: this path is an internal call with
        // no header to carry, and uq_doc_page_subject already permits exactly one live page per
        // subject — a stronger constraint than the one V42 adds. See V42's header.
        return applyCreate(userId, project, input, subject, null);
    }

    /**
     * Adopts an existing page as {@code subject}'s document, and audits it.
     *
     * <p>Package-private, ungated and not {@code @Transactional} for {@link #writeSubjectPage}'s
     * reason, and it is here rather than in {@link PlanDocumentService} for one of its own: the
     * clock and the audit row are this class's, and a second writer stamping {@code doc_page} would
     * be the second place a page's history is written.
     *
     * <p>The caller has established the permission, the subject, and that neither end is already
     * taken. {@link DocPage#linkTo} throws if it was wrong about the last of those, which is
     * deliberate — the alternative is a silent reassignment.
     *
     * <p>Flushed, so {@code uq_doc_page_subject} decides. The read the caller performed is a
     * readable refusal in front of the index and never a replacement for it: two adoptions of one
     * page in the same instant both pass that read, and only the unique index refuses the second.
     */
    void adoptPage(UUID userId, Project project, DocPage page, DocSubject subject) {
        Instant now = clock.instant();
        page.linkTo(subject, userId, now);
        pages.saveAndFlush(page);

        // No version row: nothing was authored. Same call PageVersions makes on a status change,
        // and §96's on a tag — a history entry whose diff says nothing about the content changing
        // is one that buries the entries that do.
        auditSubject(project.getId(), userId, "doc_page.subject_linked", page, subject, now);
    }

    /**
     * Releases a page's subject, keeping the page.
     *
     * <p>The audit row carries the subject the page <em>had</em>, read before the columns are
     * cleared: afterwards there is nothing on the row that says which plan this page documented, so
     * an audit trail assembled from the page alone could not answer the question the unlink exists
     * to make answerable.
     */
    void releasePage(UUID userId, Project project, DocPage page) {
        DocSubject released = page.getSubject();
        Instant now = clock.instant();
        page.unlinkSubject(userId, now);
        pages.saveAndFlush(page);

        auditSubject(project.getId(), userId, "doc_page.subject_unlinked", page, released, now);
    }

    private Outcome applyCreate(
            UUID userId,
            Project project,
            DocPageInput input,
            DocSubject subject,
            String idempotencyKey) {

        // FIRST, before validating anything — CaseCreateService's ordering and its reason: a retry
        // of a create that succeeded must answer with the page it made even if the payload has
        // since drifted, because the client is asking "did my intent land" and a 400 would send it
        // to fix fields on a page that exists.
        if (idempotencyKey != null) {
            if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY) {
                return new Outcome.Invalid(Map.of("Idempotency-Key",
                        List.of("Send a key of at most " + MAX_IDEMPOTENCY_KEY + " characters.")));
            }
            Optional<DocPage> already =
                    pages.findByIdempotencyKey(project.getId(), idempotencyKey);
            if (already.isPresent()) {
                DocPage page = already.get();
                // Two-arg, unlike the GET: a replay answers with what the create answered, and the
                // create could not carry links because nothing could link to a block created a
                // statement earlier. Serving links here would make a replay richer than the
                // response it is replaying.
                return new Outcome.Replayed(
                        DocPageView.of(page, blocks.findForPage(page.getId())));
            }
        }

        List<DocPage> live = pages.findLiveForProject(project.getId());

        Set<UUID> liveIds = new HashSet<>();
        for (DocPage page : live) {
            liveIds.add(page.getId());
        }

        PageContent.Outcome validated = PageContent.validateCreate(input, liveIds);
        if (validated instanceof PageContent.Outcome.Invalid invalid) {
            return new Outcome.Invalid(invalid.errors());
        }
        PageContent.Outcome.Valid valid = (PageContent.Outcome.Valid) validated;

        // The parent's path, from the list already fetched rather than by a second query.
        String parentPath = null;
        if (valid.parentId() != null) {
            for (DocPage page : live) {
                if (page.getId().equals(valid.parentId())) {
                    parentPath = page.getPath();
                    break;
                }
            }
        }

        // AFTER validation, so a rejected payload does not burn a number — §29's rule. The counter
        // only moves forward, so a burned number is a permanent hole in the company's DOC- series.
        DisplayKeys.Allocation allocated = displayKeys.allocate(DisplayKeys.EntityType.DOC_PAGE, 1);

        Instant now = clock.instant();
        DocPage created = DocPage.create(
                project.getId(),
                spaces.requireForProject(project.getId()).getId(),
                valid.parentId(),
                parentPath,
                allocated.keyAt(0),
                allocated.seqAt(0),
                valid.title(),
                subject,
                userId,
                idempotencyKey,
                now);

        // Flushed before the blocks so the page exists for doc_block.page_id to reference, and so a
        // constraint this class did not validate — the unique key — fails at its cause rather than
        // underneath the rows written on top of it.
        pages.saveAndFlush(created);

        List<DocBlock> written = new ArrayList<>();
        List<DocBlockInput> submitted = valid.blocks() == null ? List.of() : valid.blocks();
        for (int position = 0; position < submitted.size(); position++) {
            DocBlockInput block = submitted.get(position);
            written.add(DocBlock.create(
                    created.getId(), position, block.blockType(), BlockContent.of(block)));
        }
        blocks.saveAll(written);

        versions.writeInitial(created, written, userId, now);
        audit(project.getId(), userId, "doc_page.created", created, now);

        // Two-arg: every block was created a statement ago, so nothing can link to one yet. Asking
        // BlockLinks here would be one guaranteed-empty query per create.
        return new Outcome.Created(DocPageView.of(created, written));
    }

    /**
     * Retitles a page and replaces its blocks.
     *
     * @param expectedVersion the {@code If-Match} the client sent, or null for last-write-wins
     */
    @Transactional
    public Outcome edit(
            UUID userId, UUID projectId, UUID pageId, DocPageInput input, Long expectedVersion) {

        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, EDIT_DOC, project -> {
            Optional<DocPage> found = pages.findLiveInProject(project.getId(), pageId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            DocPage page = found.get();

            // Before validation: a client holding a stale copy should be told to reload rather than
            // told its content is wrong, because the content it is arguing about is not the current
            // one — and on this endpoint that matters more than usual, since a stale copy also
            // carries stale block ids.
            if (expectedVersion != null && expectedVersion != page.getLockVersion()) {
                return new Outcome.Stale();
            }

            List<DocBlock> existing = blocks.findForPage(page.getId());
            Set<UUID> existingIds = new HashSet<>();
            for (DocBlock block : existing) {
                existingIds.add(block.getId());
            }

            // Read ONCE, before validation, and reused for the response below. An edit cannot
            // change what is attached — the file endpoints are separate and this transaction holds
            // — so a second read would be a second answer to one question, and the one an `image`
            // block was validated against is the one the client must be shown.
            List<DocPageAttachmentView> files =
                    attachments.forPage(page.getProjectId(), page.getId());

            PageContent.Outcome validated = PageContent.validateEdit(
                    input, page.getParentId(), existingIds, PageAttachments.idsOf(files));
            if (validated instanceof PageContent.Outcome.Invalid invalid) {
                return new Outcome.Invalid(invalid.errors());
            }
            PageContent.Outcome.Valid valid = (PageContent.Outcome.Valid) validated;

            Instant now = clock.instant();

            List<DocBlock> content = existing;
            PageContent.Reconciliation plan = null;
            if (valid.blocks() != null) {
                plan = PageContent.reconcile(existing, valid.blocks());

                // BEFORE any write. A 409 raised after the rewrites have been flushed is a refusal
                // whose correctness depends on the rollback actually happening, and the reader of
                // this method should not have to reason about that to know a link is safe.
                if (!plan.removedIds().isEmpty()) {
                    long links = blocks.countLinksTargeting(companyId, plan.removedIds());
                    if (links > 0) {
                        return new Outcome.WouldBreak(0, links);
                    }
                }
            }

            /*
              THE RETITLE BELONGS HERE, BELOW THE GUARD, AND IT WAS ABOVE IT UNTIL §93.

              `Outcome.WouldBreak` is a RETURN, not an exception, and this method is
              `@Transactional` — so a normal return COMMITS. A `retitle` applied before the refusal
              leaves the entity dirty, and Hibernate flushes it on the way out: the client received
              a 409 saying the edit was refused, and the page came back renamed.

              It was unreachable until this slice, because nothing could create the `doc_link` the
              guard counts. `PlanDocumentEndpointTest.editRefusesToBreakALink` asserts the title as
              well as the status for exactly this reason — the status alone passed throughout.

              The general rule, which the comment above states for the blocks and did not enforce
              for the title: in a method whose refusals are returned values, *nothing* may be
              mutated before the last refusal.
            */
            page.retitle(valid.title(), userId, now);
            if (plan != null) {
                content = applyContent(page, existing, plan);
            }

            // The page row is written whether or not the content changed: the title may have, and
            // the version decision below is about content, not about whether a save occurred.
            pages.saveAndFlush(page);
            versions.recordEdit(page, content, userId, now);
            pages.saveAndFlush(page);

            audit(project.getId(), userId, "doc_page.edited", page, now);
            // §167: `repository` mirrors this page's title and its blocks' text into case_doc_link,
            // so an edit here changes what a case's traceability panel says — and a content edit
            // writes a version, which is what makes every link into this page stale. Coalesced, so
            // a save that rewrites nine blocks is one recompute (OutboxEvents#DOC_LINKS_CHANGED).
            //
            // Unconditional on the success arm rather than gated on versions.recordEdit's answer:
            // the title moves on a save that authors no version, and the mirror carries it.
            //
            // THE OTHER THREE WRITE PATHS IN THIS CLASS ARE NOT PRODUCERS, and #delete is the one
            // worth stating: it answers 409 WouldBreak when anything links to the page's blocks, so
            // a page that reaches softDelete has no links and no mirror row to remove. #create makes
            // a page nothing links to yet, and #setStatus moves a column the mirror does not carry.
            outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);

            // The attachments are carried from the read above rather than omitted: an edit does not
            // touch them, but the response is what the SPA renders next, and serving an empty list
            // here would make a save appear to delete every file on the page until the next GET put
            // them back.
            return new Outcome.Updated(DocPageView.of(
                    page,
                    content,
                    blockLinks.forPage(page.getProjectId(), page.getId()),
                    files));
        }));
    }

    /**
     * Performs a reconciliation and returns the page's blocks afterwards, in order.
     *
     * <p>Deletes first, then rewrites, then additions — and the order is not cosmetic.
     * {@code doc_block} has no unique constraint on {@code (page_id, position)}, so nothing would
     * fail if two blocks briefly shared a position, which is exactly why the ordering is written
     * down: the day that constraint is added, a rewrite that moves block 3 to position 1 before
     * block 1 has been deleted is the statement that starts failing.
     */
    private List<DocBlock> applyContent(
            DocPage page, List<DocBlock> existing, PageContent.Reconciliation plan) {

        if (!plan.removedIds().isEmpty()) {
            List<DocBlock> doomed = new ArrayList<>();
            for (DocBlock block : existing) {
                if (plan.removedIds().contains(block.getId())) {
                    doomed.add(block);
                }
            }
            blocks.deleteAll(doomed);
            blocks.flush();
        }

        List<DocBlock> result = new ArrayList<>();
        for (PageContent.Reconciliation.Rewrite rewrite : plan.rewrites()) {
            rewrite.block().rewrite(rewrite.position(), rewrite.type(), rewrite.content());
            result.add(rewrite.block());
        }
        for (PageContent.Reconciliation.Addition addition : plan.additions()) {
            result.add(DocBlock.create(
                    page.getId(), addition.position(), addition.type(), addition.content()));
        }
        blocks.saveAll(result);
        blocks.flush();

        result.sort((left, right) -> Integer.compare(left.getPosition(), right.getPosition()));
        return result;
    }

    /**
     * Publishes, unpublishes or archives a page.
     *
     * <p>Idempotent: setting the status a page already has answers 200 with the page. A 409 would be
     * technically defensible and practically hostile — two administrators publishing the same page
     * is not a conflict, it is agreement.
     */
    @Transactional
    public Outcome setStatus(UUID userId, UUID projectId, UUID pageId, DocPageStatusInput input) {
        return gated(projects.writeGranted(userId, projectId, PUBLISH_DOC, project -> {
            DocPageStatus target = parseStatus(input.status());
            if (target == null) {
                return new Outcome.Invalid(Map.of("status", List.of(
                        "Choose a status: DRAFT, PUBLISHED or ARCHIVED.")));
            }

            Optional<DocPage> found = pages.findLiveInProject(project.getId(), pageId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            DocPage page = found.get();

            Instant now = clock.instant();
            DocPageStatus previous = page.getStatus();
            page.setStatus(target, userId, now);
            pages.saveAndFlush(page);

            // No version row and no version bump — see PageVersions: nobody authored anything, and a
            // history whose numbers count publications no longer counts edits. Only audited when the
            // status actually moved: an audit row per repeated click buries the change that happened.
            if (previous != target) {
                audit(project.getId(), userId, "doc_page.status_changed", page, now);
            }

            return new Outcome.Updated(DocPageView.of(
                    page,
                    blocks.findForPage(page.getId()),
                    blockLinks.forPage(page.getProjectId(), page.getId()),
                    attachments.forPage(page.getProjectId(), page.getId())));
        }));
    }

    /**
     * Soft-deletes a page with no children.
     *
     * <p>Refused when a case links to one of its blocks, for the reason the edit refuses removing a
     * linked block: traceability is somebody's record of what verifies what, and it is not collateral
     * of a page somebody tidied away. Archiving is what removes a page from circulation while
     * keeping its links resolvable, which is why {@code ARCHIVED} exists and is reachable.
     */
    @Transactional
    public Outcome delete(UUID userId, UUID projectId, UUID pageId) {
        UUID companyId = CompanyContext.require();

        return gated(projects.writeGranted(userId, projectId, EDIT_DOC, project -> {
            Optional<DocPage> found = pages.findLiveInProject(project.getId(), pageId);
            if (found.isEmpty()) {
                // Includes the already-deleted case, which answers 404 rather than a second 204:
                // reporting success for a delete this request did not perform is the same untruth
                // as a 201 on a replayed create.
                return new Outcome.NotFound();
            }
            DocPage page = found.get();

            long children = pages.countLiveChildren(page.getId());
            if (children > 0) {
                return new Outcome.WouldBreak(children, 0);
            }

            List<DocBlock> content = blocks.findForPage(page.getId());
            if (!content.isEmpty()) {
                List<UUID> blockIds = content.stream().map(DocBlock::getId).toList();
                long links = blocks.countLinksTargeting(companyId, blockIds);
                if (links > 0) {
                    return new Outcome.WouldBreak(0, links);
                }
            }

            Instant now = clock.instant();
            page.softDelete(userId, now);
            pages.saveAndFlush(page);

            // The blocks are NOT deleted. The page row carries deleted_at and every read excludes
            // it, so the blocks are already unreachable; removing them would destroy the content a
            // restore would need and would cascade doc_link rows for a page that still exists.
            audit(project.getId(), userId, "doc_page.deleted", page, now);

            return new Outcome.Deleted();
        }));
    }

    /** Tolerant of case and whitespace; unknown values are a field error, not an exception. */
    private static DocPageStatus parseStatus(String submitted) {
        if (submitted == null) {
            return null;
        }
        for (DocPageStatus status : DocPageStatus.values()) {
            if (status.name().equals(submitted.trim().toUpperCase(Locale.ROOT))) {
                return status;
            }
        }
        return null;
    }

    /**
     * Collapses the gate's three states onto this class's own — one place rather than four identical
     * switches, so a fourth gate state cannot be handled in three of the four writes.
     */
    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private void audit(UUID projectId, UUID userId, String action, DocPage page, Instant now) {
        audit(projectId, userId, action, page, "", now);
    }

    /**
     * The two subject rows, which say which artifact as well as which page.
     *
     * <p>Without the subject these two actions read as "somebody touched this page" — and a link
     * and an unlink of two different plans produce identical rows, which is the history the
     * contract's own argument for making relinking two steps says must exist.
     */
    private void auditSubject(
            UUID projectId, UUID userId, String action, DocPage page, DocSubject subject,
            Instant now) {

        audit(projectId, userId, action, page,
                ",\"subjectType\":\"" + subject.type() + "\",\"subjectId\":\"" + subject.id() + "\"",
                now);
    }

    private void audit(
            UUID projectId, UUID userId, String action, DocPage page, String extra, Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(page.getId()),
                // The key, the title and the status. The key is what an audit reader recognises the
                // page by; the title is here because a deleted page's title is otherwise only
                // recoverable by reading a soft-deleted row, which is what an audit trail should not
                // require; the status is what makes the publish action's row say what it did.
                "{\"key\":\"" + page.getKey() + "\",\"title\":"
                        + AuditEvent.jsonString(page.getTitle())
                        + ",\"status\":\"" + page.getStatus() + "\"" + extra + "}",
                now));
    }
}
