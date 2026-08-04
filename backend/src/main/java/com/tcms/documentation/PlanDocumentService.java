package com.tcms.documentation;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A test plan's <strong>narrative half</strong> — business requirements, test artefacts, what is in
 * scope and what is out, and the scenario outline.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>{@code test_plan} stores the executable half: the milestone, the entry and exit criteria, and
 * the suites and cases in scope. Every test plan people actually write is mostly the other half, and
 * until now the product had nowhere to put it — {@code description} is one flat column, and
 * {@code plan_scope_item} is a list of ids, which answers "what will run" and not "what are we
 * undertaking to test".
 *
 * <h2>It is an ordinary page, and that is the design</h2>
 *
 * <p>The document is a {@link DocPage} in the project's documentation space, carrying
 * {@code subjectType: TEST_PLAN}. It is reachable at {@code /doc-pages/{pageId}} like any other, it
 * versions through {@link PageVersions} like any other, it is found by whatever searches the space,
 * and it is <strong>edited through {@code PATCH /doc-pages/{pageId}} at the cost of
 * {@code doc:edit}</strong>. This service only resolves a plan to it and starts it from the
 * template.
 *
 * <p>The alternative — a plan-scoped editor gated on {@code plan:edit} — was refused for two
 * reasons. It would put a second write path onto {@code doc_block}, and the block reconciliation
 * that keeps a link's target alive across an edit is the last code in this product that should exist
 * twice. And it would make the same page editable by two different permissions depending on which
 * URL the client came through, which is a {@code 403} no SPA can predict and no control can render
 * as disabled — §31's rule.
 *
 * <h2>The ordering of the create's refusals</h2>
 *
 * <p>Permission, then the plan, then whether it already has a document, then the write. Each step
 * only asks a question the previous one has earned: checking the plan first would make this endpoint
 * answer whether a plan exists to somebody with no right to write here, and checking for an existing
 * document first would do the same for a plan of another project.
 */
@Service
public class PlanDocumentService {

    /** Roles spec §A2 — the same key the page's own editor asks for, deliberately. */
    private static final String EDIT_DOC = "doc:edit";

    private final ProjectService projects;
    private final DocPageRepository pages;
    private final DocBlockRepository blocks;
    private final BlockLinks links;
    private final PlanFacts plans;
    private final DocPageWriteService writes;

    public PlanDocumentService(
            ProjectService projects,
            DocPageRepository pages,
            DocBlockRepository blocks,
            BlockLinks links,
            PlanFacts plans,
            DocPageWriteService writes) {

        this.projects = projects;
        this.pages = pages;
        this.blocks = blocks;
        this.links = links;
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

        /** The plan already has a document. 409, naming it. */
        record AlreadyExists(UUID pageId, String key) implements Outcome {
        }

        /** 201 on create. */
        record Created(DocPageView view) implements Outcome {
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
     * Starts the plan's document from {@link PlanDocumentTemplate}.
     *
     * <p>One transaction: the display-key allocation, the page, its blocks, v1 and the audit row all
     * commit together, which is {@link DocPageWriteService}'s own guarantee and the reason the write
     * is delegated rather than reimplemented here.
     */
    @Transactional
    public Outcome create(UUID userId, UUID projectId, UUID planId) {
        GateResult<Outcome> gated = projects.writeGranted(userId, projectId, EDIT_DOC, project -> {

            Optional<PlanFacts.PlanRef> plan = plans.findLivePlanInProject(project.getId(), planId);
            if (plan.isEmpty()) {
                return new Outcome.NotFound();
            }

            // The unique index is the invariant and this is the readable refusal in front of it.
            // Without this the second create would surface as a duplicate-key 500 naming a
            // constraint, on a request whose real answer is "somebody already started this".
            Optional<DocPage> existing =
                    pages.findLiveForSubject(project.getId(), DocSubjectType.TEST_PLAN, planId);
            if (existing.isPresent()) {
                return new Outcome.AlreadyExists(existing.get().getId(), existing.get().getKey());
            }

            DocPageInput seeded = new DocPageInput(
                    PlanDocumentTemplate.titleFor(plan.get().key(), plan.get().name()),
                    // A root page. Nesting it under a "Test plans" folder was considered and
                    // refused: the folder would have to be created by something, would be a page
                    // people could rename or delete, and a create that failed because somebody
                    // tidied the tree is a failure with no useful message.
                    null,
                    PlanDocumentTemplate.blocks());

            DocPageWriteService.Outcome written = writes.writeSubjectPage(
                    userId, project, new DocSubject(DocSubjectType.TEST_PLAN, planId), seeded);

            // The template is this codebase's own input and the validator has already accepted it in
            // every test that runs this path, so anything but Created here is a bug in the template
            // rather than in the request. It is mapped rather than assumed: a silent cast would turn
            // a mistyped block into a ClassCastException in production instead of a 500 with the
            // field error that names the block.
            if (written instanceof DocPageWriteService.Outcome.Created created) {
                return new Outcome.Created(created.view());
            }
            throw new IllegalStateException(
                    "The plan document template was refused by page validation: " + written);
        });

        return switch (gated) {
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
                links.forPage(page.getProjectId(), page.getId()));
    }
}
