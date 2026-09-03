package com.tcms.tracker.agent;

import com.tcms.project.ProjectRepoService;
import com.tcms.tracker.AcceptanceCriterion;
import com.tcms.tracker.DocLinkService;
import com.tcms.tracker.IssueDocLinkView;
import com.tcms.tracker.IssueService;
import com.tcms.tracker.IssueView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /issues/{key}/agent-context} — one task with everything needed to work it.
 *
 * <h2>It composes the module's own reads and adds no gate of its own</h2>
 *
 * <p>Every part comes back through the same service the corresponding endpoint uses, so the
 * composite cannot disagree with the pieces: {@link IssueService#find} for the task, the story and
 * the epic, {@link DocLinkService#list} for the spec excerpts. Each of those is gated on the
 * issue's own project visibility, which is what makes the <strong>scope</strong> property hold
 * without anything here checking for a token: a credential scoped to project A resolves to the
 * empty permission set on project B, {@code readVisible} answers nothing, and this answers a bodiless
 * 404 — the same absence {@code GET /issues} returns, reached by the same machinery.
 *
 * <p>The repositories are the one part with no endpoint of this module behind it, and they come
 * through {@link ProjectRepoService#forProject} — ungated for {@code ProjectSettingsService
 * .effective}'s reason: the caller has already been through the issue's gate, and asking a second,
 * unrelated permission here would make an authorized read fail on reference data.
 */
@Service
public class AgentContextService {

    private final IssueService issues;
    private final DocLinkService docLinks;
    private final ProjectRepoService repositories;

    public AgentContextService(
            IssueService issues, DocLinkService docLinks, ProjectRepoService repositories) {

        this.issues = issues;
        this.docLinks = docLinks;
        this.repositories = repositories;
    }

    /** Empty ⇒ no such issue, or one in a project this caller — or this token — cannot reach. */
    @Transactional(readOnly = true)
    public Optional<AgentTaskContextView> contextFor(UUID userId, String key) {
        return issues.find(userId, key).map(issue -> new AgentTaskContextView(
                issue,
                storyOf(userId, issue),
                epicOf(userId, issue),
                excerptsOf(userId, key),
                repositories.forProject(issue.projectId())));
    }

    /**
     * The parent, when it is a story rather than the epic.
     *
     * <p>{@code parentKey} on a task is its story and on a story is its epic, so the two keys are
     * compared rather than the parent's level read: a story whose parent IS the epic has no story
     * context to offer, and reading its own acceptance criteria back to it would answer the
     * question with the question.
     */
    private AgentStoryContextView storyOf(UUID userId, IssueView issue) {
        if (issue.parentKey() == null || issue.parentKey().equals(issue.epicKey())) {
            return null;
        }
        return issues.find(userId, issue.parentKey())
                .map(story -> new AgentStoryContextView(
                        story.key(),
                        story.title(),
                        story.description(),
                        criteriaOf(story)))
                .orElse(null);
    }

    private AgentEpicContextView epicOf(UUID userId, IssueView issue) {
        if (issue.epicKey() == null) {
            return null;
        }
        return issues.find(userId, issue.epicKey())
                .map(epic -> new AgentEpicContextView(epic.key(), epic.title(), epic.description()))
                .orElse(null);
    }

    private static List<AgentStoryContextView.Criterion> criteriaOf(IssueView story) {
        return story.acceptanceCriteria().stream()
                .map(AgentContextService::criterionOf)
                .toList();
    }

    private static AgentStoryContextView.Criterion criterionOf(AcceptanceCriterion criterion) {
        return new AgentStoryContextView.Criterion(criterion.text(), criterion.done());
    }

    /**
     * The linked specification blocks that actually carry text.
     *
     * <p>A link with no captured excerpt is dropped rather than served with an empty one. The point
     * of this list is what the spec <em>says</em>; a row naming a page and quoting nothing is
     * something a model treats as evidence it has read the spec when it has not, which is the exact
     * failure §3.1's grounding rule is about.
     */
    private List<AgentSpecExcerptView> excerptsOf(UUID userId, String key) {
        return docLinks.list(userId, key).orElse(List.of()).stream()
                .filter(link -> link.blockExcerpt() != null && !link.blockExcerpt().isBlank())
                .map(AgentContextService::excerptOf)
                .toList();
    }

    private static AgentSpecExcerptView excerptOf(IssueDocLinkView link) {
        return new AgentSpecExcerptView(
                link.pageId(),
                link.pageTitle() == null ? link.externalUrl() : link.pageTitle(),
                link.blockExcerpt());
    }
}
