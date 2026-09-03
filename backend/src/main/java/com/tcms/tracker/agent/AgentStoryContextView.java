package com.tcms.tracker.agent;

import com.tcms.tracker.AcceptanceCriterion;
import java.util.List;

/**
 * The parent story an assistant's task hangs off — the contract's {@code AgentStoryContext}.
 *
 * <p>It exists for the acceptance criteria and for nothing else. A task says what to change; the
 * story says what "done" means, and §3.1's grounding argument is that an agent that cannot read the
 * second writes code that satisfies the first and nothing anybody asked for.
 *
 * <p>{@link Criterion} carries no id and no position, unlike {@link AcceptanceCriterion}: this is a
 * read for a model, the list arrives in order, and an id it cannot tick anything with is a field
 * that only invites a write path that does not exist here.
 */
public record AgentStoryContextView(
        String key,
        String title,
        String description,
        List<Criterion> acceptanceCriteria) {

    /** One line of the checklist as the assistant reads it. */
    public record Criterion(String text, boolean done) {
    }
}
