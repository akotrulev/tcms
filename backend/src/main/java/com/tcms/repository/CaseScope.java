package com.tcms.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Everything a case query needs that the request could not carry — the resolved half of a
 * {@link CaseQuery}.
 *
 * <p>The request names a project, a suite and up to three classification values. None of those can
 * become a predicate on their own: a suite means its resolved membership, a Feature means its
 * subtree, and both live in tables the predicate builder has no business loading. So they are
 * resolved once by {@link CaseService} — which has the suites and the vocabulary in hand for
 * rendering anyway — and handed down already expanded.
 *
 * <p>Separating them from {@link CaseQuery} rather than rebuilding one with wider fields keeps the
 * request record honestly shaped as "what was asked" and this one as "what that turned out to
 * mean". The two go out of step otherwise: a rebuilt request whose {@code featureId} now holds a
 * list of ids is a record whose field name has started lying.
 *
 * @param projectId    the gated project. Present as a predicate rather than trusted from the path:
 *                     the caller passes the id it was <em>handed back</em> by the gate, never the
 *                     one it received.
 * @param memberCaseIds the cases one named suite RESOLVES to — filed ∪ added ∪ tagged, from
 *                     {@link SuiteMembership} — or {@code null} when the request named no suite.
 *                     <p><strong>§98, and it is a fix rather than a rename.</strong> The suite
 *                     filter used to expand to a set of suite ids and match {@code suite_id IN
 *                     (…)}, which is the FILED arm alone — while {@code Suite.caseCount} has been
 *                     the full union since §96. A suite defined by a tag rule therefore showed a
 *                     count of twelve beside a grid of none. Both now come from one query, so the
 *                     number and the list cannot disagree.
 *                     <p>Empty is unreachable here: the caller answers an empty page before
 *                     building a scope, because an empty {@code IN ()} is a SQL syntax error
 * @param liveSuiteIds every suite a matching case may be FILED in, and never a soft-deleted one —
 *                     the bound when no suite was named. May be empty since §96, but only together
 *                     with {@code includeUnfiled}. Ignored when {@code memberCaseIds} is present
 * @param includeUnfiled whether cases filed in NO suite match. True unless the request named a
 *                     suite: a case cannot be both filed somewhere and filed nowhere.
 *                     <strong>This is a bound, not a convenience</strong> — the filed predicate is
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
        Collection<UUID> memberCaseIds,
        Collection<UUID> liveSuiteIds,
        boolean includeUnfiled,
        Collection<UUID> featureIds,
        Collection<UUID> testTypeIds,
        Collection<UUID> testLevelIds) {

    CaseScope {
        memberCaseIds = memberCaseIds == null ? null : List.copyOf(memberCaseIds);
        liveSuiteIds = List.copyOf(liveSuiteIds);
        featureIds = featureIds == null ? null : List.copyOf(featureIds);
        testTypeIds = testTypeIds == null ? null : List.copyOf(testTypeIds);
        testLevelIds = testLevelIds == null ? null : List.copyOf(testLevelIds);
    }
}
