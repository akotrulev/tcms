package com.tcms.documentation;

import com.tcms.project.ProjectService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The documentation reads — the page tree, and one page with its blocks.
 *
 * <p>Both go through {@link ProjectService#readVisible}, which collapses "no such project", "a
 * project in another company" and "a project you hold no grant on" into one empty
 * {@link Optional}. The controller therefore has nothing to un-collapse and cannot accidentally
 * answer 403 where its sibling answers 404 — the pair that turns an endpoint into an oracle for
 * another client's ids (§6t).
 *
 * <p>The gate is the project read gate and <strong>not</strong> {@code doc:read}. Every role that
 * can reach a project holds {@code doc:read} (roles spec §A2 grants it to five of the six, and the
 * sixth is Automation Service, which holds no {@code project:read} either) — so asking for it would
 * add a second gate that has never yet answered differently from the first. When a company can
 * define its own roles, that stops being true and this is where the check goes; recorded rather than
 * pre-built, because a permission check no test can drive is one nobody can tell is inverted.
 */
@Service
public class DocPageService {

    private final ProjectService projects;
    private final DocPageRepository pages;
    private final DocBlockRepository blocks;
    private final BlockLinks links;
    private final PageAttachments attachments;

    public DocPageService(
            ProjectService projects,
            DocPageRepository pages,
            DocBlockRepository blocks,
            BlockLinks links,
            PageAttachments attachments) {

        this.projects = projects;
        this.pages = pages;
        this.blocks = blocks;
        this.links = links;
        this.attachments = attachments;
    }

    /**
     * One project's page tree, as a flat list carrying {@code parentId}.
     *
     * <p>{@code []} is a valid answer and means the project has no pages, which is the state of every
     * freshly provisioned project — {@code V18} seeds the space, deliberately not a page. Seeding a
     * "Getting started" page would be inventing a client's specification, the same mistake as
     * seeding their Feature tree or a Default suite.
     */
    @Transactional(readOnly = true)
    public Optional<List<DocPageSummaryView>> findTree(UUID userId, UUID projectId) {
        return projects.readVisible(userId, projectId, project ->
                pages.findLiveForProject(project.getId()).stream()
                        .map(DocPageSummaryView::of)
                        .toList());
    }

    /**
     * One page with its blocks.
     *
     * <p>Two nested {@link Optional}s collapsed into one, and both empties mean 404: an unreachable
     * project and a page that is not in a reachable project are the same answer for the same reason
     * — telling them apart would confirm that a page id names something real.
     */
    @Transactional(readOnly = true)
    public Optional<DocPageView> findPage(UUID userId, UUID projectId, UUID pageId) {
        return projects.readVisible(userId, projectId, project ->
                        pages.findLiveInProject(project.getId(), pageId))
                .flatMap(found -> found)
                .map(page -> DocPageView.of(
                        page,
                        blocks.findForPage(page.getId()),
                        links.forPage(page.getProjectId(), page.getId()),
                        attachments.forPage(page.getProjectId(), page.getId())));
    }
}
