package com.tcms.repository;

import java.util.List;

/**
 * The {@code GET /projects/{projectId}/ai-proposals} response — {@code PageMeta} plus items,
 * flattened for {@link CasePageView}'s reason: {@code allOf} is a schema-authoring device and the
 * wire shape it describes is one flat object.
 *
 * @param total proposals in the requested status before paging. The queue's badge reads this, so it
 *              must be the count of the filter and not the length of the page.
 */
public record AiProposalPageView(
        List<AiProposalSummaryView> items,
        int page,
        int size,
        long total) {
}
