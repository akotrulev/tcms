package com.tcms.project;

import java.util.List;
import java.util.UUID;

/**
 * {@code ClassificationDimension} in {@code docs/specs/openapi.yaml} — the wire shape.
 *
 * <p>Note what is <em>not</em> here, in both cases because the contract does not ask for it and
 * serving a field nothing renders commits the SPA to it:
 *
 * <ul>
 *   <li>{@code companyId} — never, on any response (ERD §0.1 point 2).</li>
 *   <li>{@code projectId} — the response is already scoped to the project in the path, and
 *       exposing it would tell a client which of these dimensions are company-level. That is not
 *       secret, but it is also not something any screen needs, and it is exactly the field a
 *       future author would try to <em>send</em> in order to pick a scope.</li>
 *   <li>{@code controlled} — mapped on the entity, unrendered, no consumer.</li>
 *   <li>{@code position} — array order carries it. One authority for ordering.</li>
 * </ul>
 *
 * @param cardinality SINGLE or MULTI — how many values one case may carry (plan §6d)
 * @param hierarchical render {@link #values} as a tree, from each value's {@code parentId}
 * @param locked      the dimension belongs to the <em>company</em> rather than to this project and
 *                    cannot be overridden per project, so a client's projects stay comparable.
 *                    True for Test Type, Test Level and Execution Phase.
 *                    <p>~~"the value list is not editable by the client"~~ → <strong>superseded
 *                    when the vocabulary got a writer.</strong> That reading made
 *                    {@code company:vocabulary} — granted to Instance and Company Admin since M0,
 *                    covering in the roles spec's own words "Test Type / Level / Phase values" —
 *                    a permission that covers nothing, and {@code V2}'s seed says the opposite in
 *                    as many words ("a client editing them is fine"). Two of the three documents
 *                    said editable; this one was the outlier. What the flag actually decides now
 *                    is <em>which permission, at which scope</em>: see
 *                    {@link ClassificationValueWriteService}, which reads {@code project_id}
 *                    rather than this flag for the reason recorded there.
 * @param values      never null and never absent — an empty array for a dimension with no values,
 *                    which is the <em>normal</em> state of a freshly seeded Feature tree rather
 *                    than an edge case
 */
public record ClassificationDimensionView(
        UUID id,
        String key,
        String name,
        Cardinality cardinality,
        boolean hierarchical,
        boolean locked,
        List<ClassificationValueView> values) {
}
