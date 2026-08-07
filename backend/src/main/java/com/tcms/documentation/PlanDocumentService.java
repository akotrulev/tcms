package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A test plan's <strong>business requirements</strong> — the page the plan is written against, and
 * the two writes that adopt and release it.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code test_plan} stores the executable half: the milestone, the entry and exit criteria, and
 * the suites, cases, tags and sections in scope. What the release is *for* is prose somebody wrote,
 * and until now the product had nowhere to put it — {@code description} is one flat column, and
 * {@code plan_scope_item} is a list of ids, which answers "what will run" and not "what are we
 * undertaking to test".
 *
 * <h2>Adoption, not creation (§97)</h2>
 *
 * <p><s>The document is started from {@code PlanDocumentTemplate}, a five-section skeleton.</s>
 * → <strong>retired 2026-08-05.</strong> Four of that template's five headings acquired real homes:
 * business requirements is this page, test artefacts is the {@code artifact} register, scope and
 * out of scope are {@code scope_text} / {@code out_of_scope_text} on the plan itself, and the
 * case outline is the plan's own sectioned scope items. What was left was a template seeding a
 * document whose every section is authored somewhere else — a second place to write each one, which
 * is plan §6n's "graveyard of outdated copies" produced by the product rather than by its users.
 *
 * <p>So a plan gets its document by adopting a page that already exists. That is also the order the
 * work actually happens in: a specification is drafted in the documentation space, and the release
 * that will verify it is scheduled afterwards. The template's path required the prose to be copied
 * into a fresh page, which loses its history, its {@code doc_link}s and its place in the search
 * index — and left the original behind as the copy people kept editing.
 *
 * <h2>It is an ordinary page, and that is the design</h2>
 *
 * <p>The document is a {@link DocPage} in the project's documentation space, carrying
 * {@code subjectType: TEST_PLAN}. It is reachable at {@code /doc-pages/{pageId}} like any other, it
 * versions through {@link PageVersions} like any other, it is found by whatever searches the space,
 * and it is <strong>edited through {@code PATCH /doc-pages/{pageId}} at the cost of
 * {@code doc:edit}</strong>. This service only resolves a plan to it, adopts one, and releases one.
 *
 * <p>The alternative — a plan-scoped editor gated on {@code plan:edit} — was refused for two
 * reasons. It would put a second write path onto {@code doc_block}, and the block reconciliation
 * that keeps a link's target alive across an edit is the last code in this product that should exist
 * twice. And it would make the same page editable by two different permissions depending on which
 * URL the client came through, which is a {@code 403} no SPA can predict and no control can render
 * as disabled — §31's rule.
 *
 * <h2>The ordering of the link's refusals</h2>
 *
 * <p>Permission, then the plan, then the page, then whether either end is already taken, then the
 * write. Each step only asks a question the previous one has earned: checking the plan first would
 * make this endpoint answer whether a plan exists to somebody with no right to write here, and
 * asking whether a page is taken before establishing it is <em>this project's</em> page would answer
 * that question about a sibling project's documentation.
 *
 * <p>The two {@code 409}s are ordered as well, and it matters in one case: a caller whose plan
 * already has document A, adopting page B which is already plan C's, satisfies both. The answer is
 * about their own plan, because that is the one they can act on — releasing B from C would leave
 * them exactly where they started.
 */
@Service
public class PlanDocumentService {

    /** Roles spec §A2 — the same key the page's own editor asks for, deliberately. */
    private static final String EDIT_DOC = "doc:edit";

    private final ProjectService projects;
    private final DocPageRepository pages;
    private final DocBlockRepository blocks;
    private final BlockLinks links;
    private final PageAttachments attachments;
    private final PlanFacts plans;
    private final DocPageWriteService writes;

    public PlanDocumentService(
            ProjectService projects,
            DocPageRepository pages,
            DocBlockRepository blocks,
            BlockLinks links,
            PageAttachments attachments,
            PlanFacts plans,
            DocPageWriteService writes) {

        this.projects = projects;
        this.pages = pages;
        this.blocks = blocks;
        this.links = links;
        this.attachments = attachments;
        this.plans = plans;
        this.writes = writes;
    }

    /** What happened. Sealed, so the controller's mapping is exhaustive at compile time. */
    public sealed interface Outcome {

        /**
         * The project is unreachable, the plan is not one of its live plans, or the plan has no
         * document yet — one bodiless 404 for all three (§6t).
         *
         * <p>The third is not an error and is the ordinary first answer: a plan is created long
         * before anybody writes its document. It is not distinguished from the other two because
         * doing so would tell a caller who cannot see this project that a plan by that id exists.
         */
        record NotFound() implements Outcome {
        }

        /** Readable, and {@code doc:edit} is not held. */
        record Forbidden() implements Outcome {
        }

        /** No {@code pageId} in the body. 400, on the field. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        /** The plan already has a document. 409, naming it. */
        record AlreadyExists(UUID pageId, String key) implements Outcome {
        }

        /**
         * The page is already another plan's document. 409, naming that plan.
         *
         * <p>A different refusal from {@link AlreadyExists} and not a variant of it: that one is
         * resolved on this plan, this one on a page somebody else's release depends on.
         *
         * @param planKey the other plan's key, or null when the plan row cannot be named — see
         *                {@link PlanFacts#findAnyPlanKeyInProject}
         */
        record TakenByAnotherPlan(UUID planId, String planKey) implements Outcome {
        }

        /** 200 on link, with the page as {@code GET} would now answer it. */
        record Linked(DocPageView view) implements Outcome {
        }

        /** 204 on unlink. The page still exists. */
        record Unlinked() implements Outcome {
        }
    }

    /**
     * The plan's document, or empty.
     *
     * <p>Gated on project visibility alone, like every other documentation read: a document is
     * readable by everyone who can see the project, and putting {@code plan:read} on it would hide a
     * plan's requirements from the people who are meant to be reviewing them.
     */
    @Transactional(readOnly = true)
    public Optional<DocPageView> find(UUID userId, UUID projectId, UUID planId) {
        return projects.readVisible(userId, projectId, project ->
                        pages.findLiveForSubject(project.getId(), DocSubjectType.TEST_PLAN, planId)
                                .map(this::render))
                .flatMap(found -> found);
    }

    /**
     * Adopts an existing page as the plan's document.
     *
     * <p>Writes the page's subject and <strong>nothing on the plan</strong>, which is why it costs
     * {@code doc:edit} rather than {@code plan:edit}: it is the same act as editing the page, seen
     * from the other end. A caller who may write this project's documentation may say what a page
     * is about.
     */
    @Transactional
    public Outcome link(UUID userId, UUID projectId, UUID planId, PlanDocumentLinkInput input) {
        if (input == null || input.pageId() == null) {
            return new Outcome.Invalid(Map.of("pageId", List.of("Choose a page to link.")));
        }

        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_DOC, project -> {

            if (plans.findLivePlanInProject(project.getId(), planId).isEmpty()) {
                return new Outcome.NotFound();
            }

            // The project predicate, not RLS, is what refuses a sibling project's page — the row is
            // this company's and therefore readable. findLiveInProject carries both predicates.
            Optional<DocPage> found = pages.findLiveInProject(project.getId(), input.pageId());
            if (found.isEmpty()) {
                return new Outcome.NotFound();
            }
            DocPage page = found.get();

            // The unique index is the invariant and these two are the readable refusals in front of
            // it. Without them the second link surfaces as a duplicate-key 500 naming a constraint,
            // on a request whose real answer names a plan or a page the caller can go and look at.
            Optional<DocPage> existing =
                    pages.findLiveForSubject(project.getId(), DocSubjectType.TEST_PLAN, planId);
            if (existing.isPresent()) {
                // Including the case where it is the page being offered — a repeated PUT. The
                // problem carries pageId, so a client that double-submitted can see the document it
                // asked for is the document it has, and the answer is still not a 200 claiming this
                // request linked anything.
                return new Outcome.AlreadyExists(existing.get().getId(), existing.get().getKey());
            }

            DocSubject taken = page.getSubject();
            if (taken != null) {
                return new Outcome.TakenByAnotherPlan(
                        taken.id(),
                        plans.findAnyPlanKeyInProject(project.getId(), taken.id()).orElse(null));
            }

            writes.adoptPage(userId, project, page, new DocSubject(DocSubjectType.TEST_PLAN, planId));
            return new Outcome.Linked(render(page));
        });

        return gated(gated);
    }

    /**
     * Releases the plan's document, keeping the page.
     *
     * <p>Answers {@link Outcome.NotFound} when there is nothing linked — the same answer
     * {@link #find} gives, rather than a 204 telling a caller their unlink succeeded when there was
     * nothing to unlink. It is the delete's own rule (§56) arriving at a link.
     */
    @Transactional
    public Outcome unlink(UUID userId, UUID projectId, UUID planId) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_DOC, project -> {

            if (plans.findLivePlanInProject(project.getId(), planId).isEmpty()) {
                return new Outcome.NotFound();
            }

            Optional<DocPage> linked =
                    pages.findLiveForSubject(project.getId(), DocSubjectType.TEST_PLAN, planId);
            if (linked.isEmpty()) {
                return new Outcome.NotFound();
            }

            writes.releasePage(userId, project, linked.get());
            return new Outcome.Unlinked();
        });

        return gated(gated);
    }

    /** The gate's three states onto this class's own — one place rather than two switches. */
    private Outcome gated(GateResult<Outcome> result) {
        return switch (result) {
            case GateResult.Unreachable<Outcome> ignored -> new Outcome.NotFound();
            case GateResult.Denied<Outcome> ignored -> new Outcome.Forbidden();
            case GateResult.Allowed<Outcome> allowed -> allowed.value();
        };
    }

    /** The page with its blocks and each block's links — the same assembly the page endpoint does. */
    private DocPageView render(DocPage page) {
        return DocPageView.of(
                page,
                blocks.findForPage(page.getId()),
                links.forPage(page.getProjectId(), page.getId()),
                attachments.forPage(page.getProjectId(), page.getId()));
    }
}
