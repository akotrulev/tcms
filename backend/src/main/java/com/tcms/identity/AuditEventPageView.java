package com.tcms.identity;

import java.util.List;

/**
 * Both audit-trail responses — {@code PageMeta} plus items, flattened for
 * {@code AiProposalPageView}'s reason: {@code allOf} is a schema-authoring device and the wire
 * shape it describes is one flat object.
 *
 * <p>One record for two operations, because the two trails differ in their <em>gate</em> and in
 * nothing else. A second record would be a shape nothing could tell apart from this one and a
 * second place the paging fields can be renamed by half.
 *
 * @param total acts matching the filters before paging. The pager reads this, so it must be the
 *              count of the filter and not the length of the page — and on this table it is also
 *              the only honest answer to "how much happened", which a page length would understate
 *              by exactly the amount that matters.
 */
public record AuditEventPageView(
        List<AuditEventView> items,
        int page,
        int size,
        long total) {
}
