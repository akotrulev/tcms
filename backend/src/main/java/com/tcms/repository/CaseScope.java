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
 * @param liveSuiteIds every suite a matching case may sit in — never empty, guarded by the caller,
 *                     and never containing a soft-deleted suite
 * @param featureIds   the Feature filter's value and its descendants; {@code null} when unfiltered,
 *                     empty when the id named nothing live. The two are different answers: no
 *                     predicate versus a predicate that matches nothing.
 * @param testTypeIds  the Test Type filter, on the same terms
 * @param testLevelIds the Test Level filter, on the same terms
 */
record CaseScope(
        UUID projectId,
        Collection<UUID> liveSuiteIds,
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
