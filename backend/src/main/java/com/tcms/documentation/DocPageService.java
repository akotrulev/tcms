package com.tcms.documentation;

import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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
 * can reach a project holds {@code doc:read} (roles spec §A3 grants it to five of the six, and the
 * sixth is Automation Service — <del>which holds no {@code project:read} either</del> → {@code V16}
 * granted it the visibility precondition, so this is the one role for which the two gates genuinely
 * diverge: an AS credential that can see the project can read its pages, and the {@code —} in the
 * spec's Documentation table is not enforced). Annotated in the roles spec under that table
 * (2026-08-16, flow audit C8) and accepted: when a company can
 * define its own roles, the key gets its own check and this is where it goes; recorded rather than
 * pre-built, because a permission check no test can drive is one nobody can tell is inverted.
 */
@Service
public class DocPageService {

    /**
     * The search's hard ceiling, and there is no paging past it — a reader who needs the fifty-first
     * hit needs different words, and every alternative (an offset, a cursor) is a contract for
     * walking a relevance ranking that changes under the walker on every edit.
     */
    private static final int MAX_HITS = 50;

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
     * One project's pages matching a full-text query, best first.
     *
     * <p>Two reads rather than one, deliberately. {@link DocPageRepository#search} ranks and returns
     * ids, and the entities are then loaded through the filtered JPQL path and assembled by the same
     * {@link DocPageSummaryView} the tree uses. The alternative — selecting the columns in the
     * native query — is a second assembly of one view and a second place for a search row to
     * disagree with the sidebar row beside it.
     *
     * <p>The order is re-imposed here because {@code IN} has none. A page the ranking named and the
     * entity read did not return is <em>dropped</em>, not served empty: the two queries can only
     * disagree if a page was deleted between them, and a hit that has stopped existing is not a hit.
     */
    @Transactional(readOnly = true)
    public Optional<List<DocPageSearchHitView>> search(UUID userId, UUID projectId, String query) {
        return projects.readVisible(userId, projectId, project -> {
            List<Object[]> ranked = pages.search(
                    CompanyContext.require(), project.getId(), query, MAX_HITS);
            if (ranked.isEmpty()) {
                return List.<DocPageSearchHitView>of();
            }

            Map<UUID, String> snippets = new LinkedHashMap<>();
            ranked.forEach(row -> snippets.put((UUID) row[0], (String) row[1]));

            Map<UUID, DocPage> found = pages
                    .findLiveInProjectByIds(project.getId(), snippets.keySet()).stream()
                    .collect(Collectors.toMap(DocPage::getId, page -> page));

            return snippets.entrySet().stream()
                    .filter(hit -> found.containsKey(hit.getKey()))
                    .map(hit -> DocPageSearchHitView.of(found.get(hit.getKey()), hit.getValue()))
                    .toList();
        });
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
