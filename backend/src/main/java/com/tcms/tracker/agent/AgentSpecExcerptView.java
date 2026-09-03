package com.tcms.tracker.agent;

import java.util.UUID;

/**
 * One linked specification block, as the assistant reads it — the contract's
 * {@code AgentSpecExcerpt}.
 *
 * <p>{@code excerpt} is <strong>what was linked, not what the block says now</strong>, which is
 * {@code IssueDocLinkView}'s own property and is left alone here rather than resolved live: this
 * module reads its mirror of the links and never {@code doc_link}, so resolving the current text
 * would mean naming a {@code documentation} table from a request path.
 *
 * @param pageId nullable — an external link has no page here
 */
public record AgentSpecExcerptView(UUID pageId, String title, String excerpt) {
}
