package com.tcms.repository;

import java.util.List;

/**
 * {@code Facet} in {@code docs/specs/openapi.yaml} — the counts beside one filter's options.
 *
 * <p>Returned with the page so the filter bar renders its numbers without a second request. That is
 * the contract's stated reason and it is also a correctness one: counts fetched separately are
 * counts fetched against a different moment, and a chip promising 12 rows that delivers 9 is a bug
 * report nobody can reproduce.
 *
 * @param key    the query parameter this facet drives — {@code lifecycleStatus}, {@code priority},
 *               {@code automationStatus}, {@code healthSignal}. The SPA matches on it to bind each
 *               facet to its control, so it is an API identifier and not a label.
 * @param label  the human name of the group. Server-side because the facet list is the server's
 *               shape; the SPA's {@code lib/labels.ts} owns the labels of the <em>values</em>.
 * @param values one entry per value that at least one matching case carries. Values nothing matches
 *               are absent rather than present at zero — see
 *               {@link CaseSearch#tally}.
 */
public record FacetView(String key, String label, List<Value> values) {

    /**
     * One option within a facet.
     *
     * @param id    the value as it goes back on the wire in the query parameter — the enum constant,
     *              not its label. The SPA sends this verbatim, so anything friendlier here would
     *              produce a filter that cannot be applied.
     * @param label what to show. Equal to {@link #id} today: the SPA labels enum values from its own
     *              catalogue, which is where a new member becomes a compile error until it is
     *              labelled, and a second set of labels here would be the one that goes stale.
     * @param count how many of the <em>matching</em> cases carry it
     */
    public record Value(String id, String label, long count) {
    }
}
