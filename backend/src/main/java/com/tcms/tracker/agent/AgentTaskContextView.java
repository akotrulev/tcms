package com.tcms.tracker.agent;

import com.tcms.project.ProjectRepoView;
import com.tcms.tracker.IssueView;
import java.util.List;

/**
 * Everything needed to work one task, assembled in one read — the contract's
 * {@code AgentTaskContext}.
 *
 * <p><strong>One composite endpoint rather than six calls</strong>, which is §3.1's grounding rule
 * and not a convenience: an agent that has to assemble its own context from six requests will make
 * four of them and generate from what it has. The {@link IssueView} already carries the target
 * release, the parent and epic keys and the caller-scoped {@code allowedTransitions}; this adds the
 * four things it does not — what "done" means, why the epic exists, what the spec says, and where
 * the code is and how it is built.
 *
 * @param story  null for a task with no story parent, which is legal and not degraded
 * @param epic   null for a task under no epic
 */
public record AgentTaskContextView(
        IssueView issue,
        AgentStoryContextView story,
        AgentEpicContextView epic,
        List<AgentSpecExcerptView> specExcerpts,
        List<ProjectRepoView> repositories) {
}
