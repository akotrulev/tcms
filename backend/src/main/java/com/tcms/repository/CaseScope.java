package com.tcms.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Everything a case query needs that the request could not carry — the resolved half of a
 * {@link CaseQuery}.
 *
 * <p>The request names a project, a folder and up to three classification values. None of those can
 * become a predicate on their own: a folder means its subtree, a Feature means its subtree, and
 * both subtrees live in tables the predicate builder has no business loading. So they are resolved
 * once by {@link CaseService} — which has the tree and the vocabulary in hand for rendering anyway
 * — and handed down already expanded.
 *
 * <p>Separating them from {@link CaseQuery} rather than rebuilding one with wider fields keeps the
 * request record honestly shaped as "what was asked" and this one as "what that turned out to
 * mean". The two go out of step otherwise: a rebuilt request whose {@code featureId} now holds a
 * list of ids is a record whose field name has started lying.
 *
 * @param projectId    the gated project. Present as a predicate rather than trusted from the path:
 *                     the caller passes the id it was <em>handed back</em> by the gate, never the
 *                     one it received.
 * @param liveSuiteIds every suite a matching case may sit in, and never a soft-deleted one. May be
 *                     empty since §96 — but only together with {@code includeUnfiled}, since an
 *                     empty {@code IN ()} is a SQL syntax error and the caller guards the rest
 * @param includeUnfiled whether cases filed in NO suite match. True unless the request named a
 *                     folder: a case cannot be both inside a subtree and outside every folder.
 *                     <strong>This is a bound, not a convenience</strong> — the suite predicate is
 *                     {@code suite_id IN (…)}, so without it every unfiled case is silently absent
 *                     from the grid, which is where a case that belongs nowhere is most likely to
 *                     be looked for
 * @param featureIds   the Feature filter's value and its descendants; {@code null} when unfiltered,
 *                     empty when the id named nothing live. The two are different answers: no
 *                     predicate versus a predicate that matches nothing.
 * @param testTypeIds  the Test Type filter, on the same terms
 * @param testLevelIds the Test Level filter, on the same terms
 */
record CaseScope(
        UUID projectId,
        Collection<UUID> liveSuiteIds,
        boolean includeUnfiled,
        Collection<UUID> featureIds,
        Collection<UUID> testTypeIds,
        Collection<UUID> testLevelIds) {

    CaseScope {
        liveSuiteIds = List.copyOf(liveSuiteIds);
        featureIds = featureIds == null ? null : List.copyOf(featureIds);
        testTypeIds = testTypeIds == null ? null : List.copyOf(testTypeIds);
        testLevelIds = testLevelIds == null ? null : List.copyOf(testLevelIds);
    }
}
