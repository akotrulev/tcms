package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.AuditEvent;
import com.tcms.tenancy.AuditEventRepository;
import com.tcms.tenancy.Outbox;
import com.tcms.tenancy.OutboxEvents;
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
    /**
     * How {@code repository} learns that a case's links moved — see {@link OutboxEvents#DOC_LINKS_CHANGED}.
     *
     * <p>An event and not a call: {@code repository} may not be imported from here and this module
     * may not be imported from there, so the case detail's traceability panel is fed by a read model
     * that module owns ({@code case_doc_link}) rather than by a join anybody could write (§3 lesson 4).
     */
    private final Outbox outbox;
    private final Clock clock;

    public BlockLinkService(
            ProjectService projects,
            DocPageRepository pages,
            DocBlockRepository blocks,
            DocLinkRepository links,
            CatalogueFacts catalogue,
            AuditEventRepository auditEvents,
            Outbox outbox,
            Clock clock) {

        this.projects = projects;
        this.pages = pages;
        this.blocks = blocks;
        this.links = links;
        this.catalogue = catalogue;
        this.auditEvents = auditEvents;
        this.outbox = outbox;
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

        /**
         * The relationship was corrected, or was already what the caller asked for. 200 either way
         * (§174).
         *
         * <p>One record and not two, deliberately. The {@code POST} distinguishes {@link Created}
         * from {@link Existing} because they differ in <em>status code</em> — 201 against 200 — and
         * a client has to know whether a row appeared. Here both arms are 200 with the same body,
         * so a second record would be a distinction no response could carry, which is a fact stored
         * where nobody can read it. What the two arms really differ in is whether anything was
         * written, and {@link DocLink#relabel} already answers that where it matters: no audit row
         * and no outbox notice for a correction that corrected nothing.
         */
        record Relabelled(BlockLinkView view) implements Outcome {
        }
    }

    /** Links a case, a suite or an issue to a block. */
    @Transactional
    public Outcome link(
            UUID userId, UUID projectId, UUID pageId, UUID blockId, BlockLinkInput input) {

        return gated(projects.writeGranted(userId, projectId, EDIT_DOC, project -> {

            Map<String, List<String>> errors = new java.util.LinkedHashMap<>();

            LinkSourceType sourceType = parseSourceType(input.sourceType());
            if (sourceType == null) {
                errors.put("sourceType",
                        List.of("Choose what is linking: TEST_CASE, SUITE or ISSUE."));
            }
            if (input.sourceId() == null) {
                errors.put("sourceId", List.of("Choose a test case, a suite or an issue."));
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
            // On the Created arm alone. The Existing arm above writes no row and no audit event for
            // the reason stated there, and an outbox notice that nothing changed is the same claim
            // in a second place — it would cost a whole-company recompute per duplicate click.
            outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);
            return new Outcome.Created(view(created, source.get()));
        }));
    }

    /**
     * Records the link a person consented to when they accepted a generated proposal (022 §5).
     *
     * <h2>Ungated, and the gate is not missing — it was passed elsewhere</h2>
     *
     * <p>{@code ApiTokenService.mintOwned}'s shape: a package-private seam beside a gated public
     * one, for a caller that has already cleared its own gate. Here the caller is
     * {@code LinkAcceptedProposal}, running on an outbox thread with no request and no session, and
     * what it is delivering is a decision a <em>person</em> made minutes ago under
     * {@code case:create} — plus the credential refusal that makes accepting a human act at all.
     *
     * <p>Re-asking for {@code doc:edit} here would be a second gate on a decision already taken, and
     * it could only ever fail <em>after</em> the case exists: the links would be dropped, silently,
     * on a thread with nowhere to report it, and the reviewer would see an accepted case claiming
     * twelve requirements and linked to none. The roles say the check would also decide nothing —
     * every role that can accept holds {@code doc:edit} (roles spec §A3; Automation Service is the
     * one holder of {@code case:create} without it, and a credential may not accept). That is the
     * weaker half of the argument and is stated second on purpose: the reason is the shape of the
     * consent, not the current contents of a matrix somebody may edit.
     *
     * <p>What is <strong>not</strong> relaxed is the target check. The page must still be a live page
     * of this project and the block must still be on it — {@link #link}'s two predicates, run again
     * here, because minutes pass between the accept and the delivery and the accept's own validation
     * cannot see them.
     *
     * <h2>NO {@code @Transactional} HERE, and that is not an omission</h2>
     *
     * <p>The method is package-private, and Spring's proxy-based transaction management is
     * {@code publicMethodsOnly} — an annotation on a non-public method is <strong>silently
     * ignored</strong>, which is a missing boundary with no symptom until two writes that had to
     * commit together do not. {@code ApiTokenService}'s two ungated seams carry the same rule for
     * the same reason. The boundary is on {@link LinkAcceptedProposal#handle}, which is public and
     * is the only caller.
     *
     * @return whether a row was written. False means the target has gone since the accept, which is
     *         a delivery rather than a failure ({@code TrackerDefectLinkBackstop}'s rule: retrying
     *         cannot make a deleted block exist), or that the link was already there — the
     *         idempotent case the handler leans on rather than keys itself.
     */
    boolean linkAccepted(
            UUID projectId,
            UUID pageId,
            UUID blockId,
            UUID caseId,
            LinkRelationship relationship,
            UUID acceptedBy) {

        Optional<DocPage> page = pages.findLiveInProject(projectId, pageId);
        if (page.isEmpty()) {
            return false;
        }
        boolean onThisPage = blocks.findForPage(page.get().getId()).stream()
                .anyMatch(block -> block.getId().equals(blockId));
        if (!onThisPage) {
            return false;
        }

        Optional<CatalogueFacts.CatalogueRef> source =
                catalogue.find(projectId, LinkSourceType.TEST_CASE, caseId);
        if (source.isEmpty()) {
            // The accepted case was deleted between the decision and this delivery. Nothing to link
            // from, and no retry makes it come back.
            return false;
        }

        if (links.findForSource(blockId, LinkSourceType.TEST_CASE, caseId).isPresent()) {
            // The re-delivery arm, and the whole of this handler's idempotency: at-least-once
            // means this runs again for links it already wrote, and the create path's own "two
            // people recording the same coverage is agreement, not conflict" answers it. No row,
            // no audit line, no outbox notice — see the Existing arm above.
            return false;
        }

        Instant now = clock.instant();
        DocLink created = links.save(DocLink.toBlock(
                LinkSourceType.TEST_CASE, caseId, blockId, relationship, acceptedBy, now));

        // The same audit action a person's link writes, deliberately: the row IS a person's link —
        // made on their instruction, attributed to them, indistinguishable from one they clicked.
        // A separate action would put the distinction §5 spent its argument removing back into the
        // audit trail.
        audit(projectId, acceptedBy, "doc_link.created", created, source.get(), now);
        outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);
        return true;
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
            // The removal matters to the mirror more than the creation does: a withdrawn claim left
            // standing in case_doc_link is exactly what this class refuses to leave in doc_link.
            outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);

            return new Outcome.Deleted();
        }));
    }

    /**
     * Corrects what an existing link claims (§174).
     *
     * <p>The one field of a link that may be changed in place, and the reason is
     * {@link DocLink#relabel}'s: the coverage matrix counts {@code VERIFIES} and ignores the other
     * two, so a mislabelled link is a false coverage number rather than a cosmetic error — and the
     * alternative the product offered until now was to withdraw the link and make another, which
     * silently rewrites {@code created_by} and {@code created_at} to whoever noticed the mistake.
     *
     * <p><strong>The gate is {@code doc:edit}, exactly as the create and the delete are.</strong>
     * Correcting a claim is not a lesser act than making one — if anything it is the more
     * consequential, because the link is already being counted.
     *
     * <p>The source is resolved for the response body for the same reason the create resolves it:
     * a {@link BlockLinkView} names the case, and a client that has just relabelled a link needs
     * the row back in the shape it renders. A link whose source can no longer be named is a
     * {@code 404} here rather than a view with null fields — unlike the delete, which writes its
     * audit row with the id alone, this path has to <em>return</em> the thing.
     */
    @Transactional
    public Outcome relabel(
            UUID userId, UUID projectId, UUID pageId, UUID linkId, LinkRelabelInput input) {

        return gated(projects.writeGranted(userId, projectId, EDIT_DOC, project -> {

            LinkRelationship relationship = LinkRelationship.parse(input.relationship());
            if (relationship == null) {
                // No default here, unlike the create. An absent relationship on a POST means "the
                // ordinary one"; on a correction it means the caller did not say what to correct it
                // to, and choosing VERIFIES for them would be the product inventing a claim.
                return new Outcome.Invalid(Map.of("relationship",
                        List.of("Choose a relationship: VERIFIES, DERIVED_FROM or REFERENCES.")));
            }

            Optional<DocPage> page = pages.findLiveInProject(project.getId(), pageId);
            if (page.isEmpty()) {
                return new Outcome.NotFound();
            }

            Optional<DocLink> found = links.findInPage(page.get().getId(), linkId);
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            DocLink link = found.get();

            Optional<CatalogueFacts.CatalogueRef> source =
                    catalogue.find(project.getId(), link.getSourceType(), link.getSourceId());
            if (source.isEmpty()) {
                return new Outcome.NotFound();
            }

            Instant now = clock.instant();
            if (link.relabel(relationship, userId, now)) {
                audit(project.getId(), userId, "doc_link.relabelled", link, source.get(), now);
                // The mirror carries `relationship` (V46), and the coverage matrix reads it: a
                // correction nobody propagates leaves the case end and every count still stating
                // the claim that was just withdrawn.
                outbox.recordCoalesced(OutboxEvents.DOC_LINKS_CHANGED);
            }
            return new Outcome.Relabelled(view(link, source.get()));
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
