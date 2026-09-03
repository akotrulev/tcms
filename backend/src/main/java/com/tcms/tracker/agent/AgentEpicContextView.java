package com.tcms.tracker.agent;

/**
 * The epic above the task — the contract's {@code AgentEpicContext}.
 *
 * @param goal the epic's <em>description</em>, named for what it is used as here. There is no
 *             separate goal column and this does not propose one: an epic's description is where
 *             the goal is already written, and a second field would be a second place to write it
 *             and a first place for the two to disagree.
 */
public record AgentEpicContextView(String key, String title, String goal) {
}
