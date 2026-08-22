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
 * @param projectIds   the gated projects — <strong>one</strong> for every project-scoped read, and
 *                     the caller's whole visible set for the cross-project search (§249). Present
 *                     as a predicate rather than trusted from the path or the session: the caller
 *                     passes exactly the ids it was <em>handed back</em> by
 *                     {@link com.tcms.project.ProjectService#readVisible} or
 *                     {@code readAllVisible}, never ones it received.
 *
 *                     <p><strong>Never empty, and that is enforced rather than documented.</strong>
 *                     An empty collection bound into a SQL {@code IN ()} is a syntax error, and the
 *                     repair somebody reaches for under time pressure — dropping the predicate when
 *                     the set is empty — turns "this caller may see nothing" into "this caller may
 *                     see everything". The caller answers an empty page before building a scope;
 *                     this constructor is what makes forgetting fail loudly instead of quietly.
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
 * @param boundByTree  whether the query is bounded by a project's suite tree at all.
 *
 *                     <p><strong>False ONLY for the cross-project search, and it is a field rather
 *                     than a combination of the two above because the combination that looks like
 *                     it means this means something else entirely.</strong> An empty
 *                     {@code liveSuiteIds} with {@code includeUnfiled} reads, in
 *                     {@link CaseSpecifications}, as {@code suite_id IS NULL} — <em>unfiled cases
 *                     only</em>, which is what {@code CaseService} builds it to mean for the
 *                     grid's "unfiled" filter. A cross-project read spelled that way would answer
 *                     the handful of cases filed nowhere and silently drop every filed case in the
 *                     company: plausible, non-empty, and wrong.
 *
 *                     <p>There is no tree to bound by across projects — the projects are many and
 *                     their trees are not enumerated — so the honest state is "no suite predicate",
 *                     and the honest way to say it is to say it. The consequence is deliberate and
 *                     is §249's open question Q4: a case filed in a soft-deleted suite IS findable
 *                     by search, where it is absent from that project's own grid. A search is for
 *                     finding a case you know exists, and a case hidden behind a deleted folder is
 *                     precisely the one somebody cannot find.
 * @param featureIds   the Feature filter's value and its descendants; {@code null} when unfiltered,
 *                     empty when the id named nothing live. The two are different answers: no
 *                     predicate versus a predicate that matches nothing.
 * @param testTypeIds  the Test Type filter, on the same terms
 * @param testLevelIds the Test Level filter, on the same terms
 */
record CaseScope(
        Collection<UUID> projectIds,
        Collection<UUID> memberCaseIds,
        Collection<UUID> liveSuiteIds,
        boolean includeUnfiled,
        boolean boundByTree,
        Collection<UUID> featureIds,
        Collection<UUID> testTypeIds,
        Collection<UUID> testLevelIds) {

    CaseScope {
        projectIds = List.copyOf(projectIds);
        if (projectIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "a case scope with no projects would be an IN () — answer the empty page above");
        }
        memberCaseIds = memberCaseIds == null ? null : List.copyOf(memberCaseIds);
        liveSuiteIds = List.copyOf(liveSuiteIds);
        // The two suite fields are meaningless when nothing is bounded by the tree, so a scope
        // carrying both a resolved suite and no tree bound is a contradiction rather than a
        // preference. Refused here so no reader has to decide which half won.
        if (!boundByTree && (memberCaseIds != null || !liveSuiteIds.isEmpty())) {
            throw new IllegalArgumentException(
                    "a scope that is not bound by the tree cannot also name suites");
        }
        featureIds = featureIds == null ? null : List.copyOf(featureIds);
        testTypeIds = testTypeIds == null ? null : List.copyOf(testTypeIds);
        testLevelIds = testLevelIds == null ? null : List.copyOf(testLevelIds);
    }

    /** The whole visible set, with no suite bound of any kind — the cross-project search's scope. */
    static CaseScope acrossProjects(
            Collection<UUID> projectIds,
            Collection<UUID> featureIds,
            Collection<UUID> testTypeIds,
            Collection<UUID> testLevelIds) {

        return new CaseScope(
                projectIds, null, List.of(), true, false, featureIds, testTypeIds, testLevelIds);
    }
}
