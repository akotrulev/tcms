package com.tcms.repository;

import java.util.List;

/**
 * The {@code GET /projects/{projectId}/cases} response — {@code PageMeta} plus items plus facets,
 * which the contract composes with {@code allOf}.
 *
 * <p>Flattened into one record rather than modelled as composition, because {@code allOf} is a
 * schema-authoring device and the wire shape it describes is a single flat object. A nested
 * {@code meta} object here would serialize something the contract does not describe.
 *
 * @param page  echoed back rather than assumed, so a client that clamped or defaulted differently
 *              finds out which page it actually got
 * @param size  echoed for the same reason, and it is the <em>effective</em> size: a request for
 *              1000 is served 200 and told so, rather than silently short-changed
 * @param total matching cases before paging — the number the pager needs, and the one a count over
 *              {@link #items} would get wrong by exactly the amount that matters
 */
public record CasePageView(
        List<TestCaseSummaryView> items,
        int page,
        int size,
        long total,
        List<FacetView> facets) {
}
