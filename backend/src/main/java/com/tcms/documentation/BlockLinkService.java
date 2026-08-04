package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>The first writer {@code doc_link} has ever had.</strong>
 *
 * <p>{@code V1} designed the table; three read paths were specified against it and two of them said
 * so in their own headers. {@link DocBlockView} served no {@code coverage} because nothing could
 * create a link; {@link com.tcms.repository.TestCaseDetailView} omitted {@code relatedDocuments} for
 * the same reason; and {@link DocPageWriteService}'s two {@code 409}s — refusing to remove a block or
 * a page that something links to — could not fire on any real request. All three were waiting on
 * this class.
 *
 * <h2>The gate is {@code doc:edit}</h2>
 *
 * <p>A link is a property of the documentation, so it costs what editing the documentation costs. The
 * alternatives were considered and refused. {@code case:edit} reads plausibly — somebody is
 * recording what their case covers — but it would let a person with no documentation rights write
 * into a page's rendering, and the same act would then be gated differently depending on which end
 * the client started from. A new key ({@code doc:link}) is the shape §67 and §79 both refused: a key
 * seeded to nobody is a {@code 403} no administrator can grant their way out of.
 *
 * <h2>Re-asserting a recorded fact is not an error</h2>
 *
 * <p>Linking the same source to the same block twice answers {@code 200} with the link that is
 * already there and writes nothing. A {@code 409} would be technically defensible and practically
 * hostile — two people recording the same coverage is agreement, not conflict — and a second row
 * would double every count drawn off this table. §79's re-stamp defect is the version of this that
 * shipped broken: the unconditional write handed the record to whoever clicked last.
 *
 * <h2>Removing a link is a hard delete</h2>
 *
 * <p>The opposite of a page's soft delete, and the same call {@code ReviewAssignment} made against
 * {@code ReviewComment}'s. The distinction those two drew is the one that applies here: a remark is
 * something somebody said and stays said; a link is a claim that is currently being made, and a
 * withdrawn claim that is still in the table still reads as one to every count over it.
 */
@Service
public class BlockLinkService {

    /** Roles spec §A2 — the key that owns the documentation's content. */
    private static final String EDIT_DOC = "doc:edit";

    private static final String AUDIT_TARGET = "doc_link";

    private final ProjectService projects;
    private final DocPageRepository pages;
    private final DocBlockRepository blocks;
    private final DocLinkRepository links;
    private final CatalogueFacts catalogue;
    private final AuditEventRepository auditEvents;
    private final Clock clock;

    public BlockLinkService(
            ProjectService projects,
            DocPageRepository pages,
            DocBlockRepository blocks,
            DocLinkRepository links,
            CatalogueFacts catalogue,
            AuditEventRepository auditEvents,
            Clock clock) {

        this.projects = projects;
        this.pages = pages;
        this.blocks = blocks;
        this.links = links;
        this.catalogue = catalogue;
        this.auditEvents = auditEvents;
        this.clock = clock;
    }

    /** What happened. Sealed, so the controller's mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /**
         * The project, the page, the block, the link or the source is not reachable here — one
         * bodiless 404 for all of them (§6t).
         *
         * <p>The source is in that list on purpose. A case of a sibling project is <em>this
         * company's</em>, so RLS returns it and only {@link CatalogueFacts}' project predicate
         * refuses it; answering 400 "no such case" would be a lie, and answering 403 would confirm
         * that the id names something.
         */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code doc:edit} is not held. */
        record Forbidden() implements Outcome {
        }

        /** Field name to messages, for the form to land on its inputs. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** A new link. 201. */
        record Created(BlockLinkView view) implements Outcome {
        }

        /** The link already existed and nothing was written. 200. */
        record Existing(BlockLinkView view) implements Outcome {
        }

        /** Removed. 204. */
        record Deleted() implements Outcome {
        }
    }

    /** Links a case or suite to a block. */
    @Transactional
    public Outcome link(
            UUID userId, UUID projectId, UUID pageId, UUID blockId, BlockLinkInput input) {

        return gated(projects.writeGranted(userId, projectId, EDIT_DOC, project -> {

            Map<String, List<String>> errors = new java.util.LinkedHashMap<>();

            LinkSourceType sourceType = parseSourceType(input.sourceType());
            if (sourceType == null) {
                errors.put("sourceType", List.of("Choose what is linking: TEST_CASE or SUITE."));
            }
            if (input.sourceId() == null) {
                errors.put("sourceId", List.of("Choose a test case or suite."));
            }
            LinkRelationship relationship = input.relationship() == null
                    ? LinkRelationship.VERIFIES
                    : LinkRelationship.parse(input.relationship());
            if (relationship == null) {
                errors.put("relationship",
                        List.of("Choose a relationship: VERIFIES, DERIVED_FROM or REFERENCES."));
            }
            if (!errors.isEmpty()) {
                return new Outcome.Invalid(errors);
            }

            // The page before the block, and the block scoped to the page. A block id from another
            // page would otherwise resolve — doc_block has no project column, so only the page it
            // belongs to bounds it — and the link would render on a document nobody meant.
            Optional<DocPage> page = pages.findLiveInProject(project.getId(), pageId);
            if (page.isEmpty()) {
                return new Outcome.NotFound();
            }
            boolean onThisPage = blocks.findForPage(page.get().getId()).stream()
                    .anyMatch(block -> block.getId().equals(blockId));
            if (!onThisPage) {
                return new Outcome.NotFound();
            }

            Optional<CatalogueFacts.CatalogueRef> source =
                    catalogue.find(project.getId(), sourceType, input.sourceId());
            if (source.isEmpty()) {
                return new Outcome.NotFound();
            }

            Optional<DocLink> already =
                    links.findForSource(blockId, sourceType, input.sourceId());
            if (already.isPresent()) {
                // Nothing written, and no audit row: an audit trail that records a request which
                // changed nothing buries the one that did.
                return new Outcome.Existing(view(already.get(), source.get()));
            }

            Instant now = clock.instant();
            DocLink created = links.save(DocLink.toBlock(
                    sourceType, input.sourceId(), blockId, relationship, userId, now));

            audit(project.getId(), userId, "doc_link.created", created, source.get(), now);
            return new Outcome.Created(view(created, source.get()));
        }));
    }

    /** Removes a link. */
    @Transactional
    public Outcome unlink(UUID userId, UUID projectId, UUID pageId, UUID linkId) {
        return gated(projects.writeGranted(userId, projectId, EDIT_DOC, project -> {

            Optional<DocPage> page = pages.findLiveInProject(project.getId(), pageId);
            if (page.isEmpty()) {
                return new Outcome.NotFound();
            }

            Optional<DocLink> found = links.findInPage(page.get().getId(), linkId);
            if (found.isEmpty()) {
                // Not an idempotent 204. Unlike the create, there is no fact here that is already
                // true — the caller named a row, and naming one that is not there is a mistake worth
                // reporting rather than a request that has already had its effect.
                return new Outcome.NotFound();
            }
            DocLink link = found.get();

            // Resolved BEFORE the delete, for the audit row. A link to a case somebody has since
            // deleted resolves to nothing, and the audit is written anyway with the id alone: the
            // record of a removal must not depend on the removed thing still being nameable.
            Optional<CatalogueFacts.CatalogueRef> source =
                    catalogue.find(project.getId(), link.getSourceType(), link.getSourceId());

            links.delete(link);
            audit(project.getId(), userId, "doc_link.deleted", link, source.orElse(null),
                    clock.instant());

            return new Outcome.Deleted();
        }));
    }

    private static BlockLinkView view(DocLink link, CatalogueFacts.CatalogueRef source) {
        return new BlockLinkView(
                link.getId(),
                link.getSourceType().name(),
                source.id(),
                source.key(),
                source.title(),
                link.getRelationship().name());
    }

    /** Tolerant of case and whitespace; unknown values are a field error, not an exception. */
    private static LinkSourceType parseSourceType(String submitted) {
        if (submitted == null) {
            return null;
        }
        for (LinkSourceType type : LinkSourceType.values()) {
            if (type.name().equals(submitted.trim().toUpperCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }

    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    private void audit(
            UUID projectId,
            UUID userId,
            String action,
            DocLink link,
            CatalogueFacts.CatalogueRef source,
            Instant now) {

        auditEvents.save(AuditEvent.record(
                projectId,
                userId,
                action,
                AUDIT_TARGET,
                List.of(link.getId()),
                // The source's key rather than its id: an audit reader recognises TC-42, and the id
                // is already in the target list. Null when the source can no longer be named, which
                // a delete can legitimately reach.
                "{\"sourceType\":\"" + link.getSourceType() + "\",\"sourceKey\":"
                        + (source == null ? "null" : AuditEvent.jsonString(source.key()))
                        + ",\"blockId\":\"" + link.getTargetBlockId()
                        + "\",\"relationship\":\"" + link.getRelationship() + "\"}",
                now));
    }
}
