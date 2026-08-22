package com.tcms.repository;

import jakarta.persistence.criteria.AbstractQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One {@link CaseQuery} → one {@code WHERE} clause, built once and reused by the page, the count
 * and every facet.
 *
 * <h2>Why one builder rather than a predicate per query</h2>
 *
 * <p>This endpoint runs five queries per request: the page, its total, and three grouped facet
 * counts (plus a fourth over health). Every one of them must apply <em>exactly</em> the same
 * filter, and the failure when they do not is quiet in both directions — a total larger than the
 * rows can reach makes a last page that renders empty, and a facet count that disagrees with the
 * grid makes a filter chip promise 12 rows and deliver 9. Neither errors. So the predicate is
 * written once and the queries differ only in what they select.
 *
 * <h2>The two gates are not here, and that is deliberate</h2>
 *
 * <p>Nothing in this class asks whether the caller may see anything. The company gate is the
 * Hibernate filter plus RLS, established per transaction; the project gate is
 * {@code ProjectService.readVisible}, applied by {@link CaseService} before this is reached. A
 * third answer to "may they?" here would be the bug class plan §6b names, and it would be the one
 * that diverges quietly, since it would be the only one written as a filter rather than as a gate.
 *
 * <p>What <em>is</em> here is scoping the caller already passed: the project, the live suites, and
 * the not-deleted predicate.
 */
final class CaseSpecifications {

    private CaseSpecifications() {
    }

    /**
     * The full predicate for one request.
     *
     * @param scope the resolved half — the gated project, the live suites, and the classification
     *              filters already expanded to include their descendants. See {@link CaseScope}.
     */
    static Predicate where(
            CaseQuery request,
            CaseScope scope,
            Root<TestCase> root,
            AbstractQuery<?> query,
            CriteriaBuilder cb) {

        List<Predicate> all = new ArrayList<>();

        // ~~Removing this line is a mutation that SURVIVES, and it survives structurally rather
        // than for want of a test.~~ → TRUE OF THE PROJECT-SCOPED READ ONLY, AND FALSE SINCE THE
        // CROSS-PROJECT SEARCH (§249).
        //
        // The old argument, kept because it was right and is still right on the grid: on
        // GET /projects/{id}/cases the suite scope below is the project's own live tree, so a case
        // in another project hangs off a suite never in the set and no fixture could make the
        // difference observable. §22's documented survivor on RoleAssignmentRepository is the same
        // family. (SuiteRepository's countLiveCasesBySuite documented it too, until §152 deleted it
        // as callerless.)
        //
        // On GET /cases/search there IS no suite scope — the projects are many and their trees are
        // not enumerated, which is what CaseScope.boundByTree says — so THIS LINE IS THE WHOLE
        // PROJECT BOUNDARY, and RLS is not behind it: the policy predicate is the COMPANY, and
        // every project the caller is not granted is in the caller's own company and therefore
        // RLS-visible to this very session.
        //
        // The ids are the ones ProjectService handed back (readVisible / readAllVisible), never
        // ones the request supplied. Because the predicate is HERE rather than in Java after the
        // read, the total and the paging are computed over exactly the rows the caller may see —
        // which is what CaseSearchTest.theTotalIsNarrowedToo and .thePageCannotHideTheCount assert,
        // and what a page-only assertion cannot.
        //
        // It also still does what it always did: it is what makes the query reachable through
        // test_case_lifecycle_idx (company_id, project_id, lifecycle_status), and it states the
        // bound directly instead of leaving it implied by a set of ids assembled elsewhere.
        all.add(root.get("projectId").in(scope.projectIds()));

        // Soft-deleted cases are gone from every read: past executions still reference them and
        // pin the version they ran, which is why the row survives at all.
        all.add(cb.isNull(root.get("deletedAt")));

        suiteScope(scope, root, cb).ifPresent(all::add);

        // B8 — the by-ids read, and it is a FILTER like any other rather than a mode. Placed
        // after the two bounds above and before everything else so the reading is unambiguous:
        // naming ids narrows what the other filters then narrow again, and an id from another
        // project is excluded by the project bound rather than by anything here.
        ids(request, root).ifPresent(all::add);

        tags(request, root, query, cb).ifPresent(all::add);

        keyOrText(request, root, cb).ifPresent(all::add);

        classified(scope.featureIds(), root, query, cb).ifPresent(all::add);
        classified(scope.testTypeIds(), root, query, cb).ifPresent(all::add);
        classified(scope.testLevelIds(), root, query, cb).ifPresent(all::add);

        owner(request, root, cb).ifPresent(all::add);

        anyOf(request.lifecycleStatus(), root.get("lifecycleStatus"), cb).ifPresent(all::add);
        anyOf(request.automationStatus(), root.get("automationStatus"), cb).ifPresent(all::add);
        anyOf(request.priority(), root.get("priority"), cb).ifPresent(all::add);

        health(request, root, query, cb).ifPresent(all::add);
        release(request, cb).ifPresent(all::add);

        return cb.and(all.toArray(Predicate[]::new));
    }


    /**
     * {@code id IN (…)} when the caller named any (B8).
     *
     * <p>Empty is <strong>no predicate</strong> and never {@code IN ()} — which is a syntax error
     * in SQL and, worse, would be an empty-set filter if it were expressible: every request without
     * the parameter would answer nothing. {@link CaseQuery} has already de-duplicated and capped
     * the list, so this only has to decide between a predicate and none.
     */
    private static Optional<Predicate> ids(CaseQuery request, Root<TestCase> root) {
        return request.ids().isEmpty()
                ? Optional.empty()
                : Optional.of(root.get("id").in(request.ids()));
    }

    /**
     * The cases this request may draw from — one suite's resolved membership when a
     * {@code suiteId} is given (§98), and the project's whole live suite set plus the unfiled
     * otherwise.
     *
     * <h2>Bounding every request by the live tree is a decision, not a side effect</h2>
     *
     * <p>A case in a <em>soft-deleted</em> suite is excluded from the list even with no
     * {@code suiteId} filter. Soft-deleting a folder does not touch the cases under it, so without
     * this the grid would keep listing them under a folder the tree no longer shows — a row whose
     * "Suite" column names a place the user cannot navigate to. The suite endpoint's own comment
     * states the intended semantics from the other side: a deleted folder's cases "are already gone
     * from the case list". This is what makes that true.
     *
     * <p>The subtree itself is resolved from {@code parentId} in memory by {@link CaseService},
     * rather than through {@code ltree}'s {@code <@} operator, because the tree is folders — tens of
     * rows — and it is already being fetched for the suite names. A native {@code <@} query would
     * be one more query and would lose the Hibernate filter on the way.
     *
     * <h2>And a case may now be in no suite at all</h2>
     *
     * <p>{@code test_case.suite_id} is nullable since {@code V31}, and {@code IN (…)} is never true
     * of NULL — so the spelling this method had until §96 made <strong>every unfiled case invisible
     * to the grid</strong>, in the one place somebody would go looking for a case that belongs
     * nowhere. {@link CaseScope#includeUnfiled()} is the arm that admits them, and it is off exactly
     * when the request named a folder, because a case cannot be both inside a subtree and outside
     * every folder.
     *
     * <p>When the scope is empty <em>and</em> unfiled cases are included, the {@code IN} is dropped
     * rather than bound to nothing: an empty {@code IN ()} is a syntax error, and a project with no
     * suites at all — the state of every freshly provisioned one — can now hold cases.
     */
    /**
     * The suite bound, or none.
     *
     * <p><strong>{@code Optional.empty()} is "no suite predicate" and is NOT the same as any
     * combination of the fields below.</strong> In particular it is not an empty
     * {@code liveSuiteIds} with {@code includeUnfiled}, which is the "unfiled only" filter and
     * reads as {@code suite_id IS NULL} — a cross-project search spelled that way would answer the
     * few cases filed nowhere and silently drop every filed case in the company. That is why
     * {@link CaseScope#boundByTree} is a field: the state had to be sayable.
     */
    private static Optional<Predicate> suiteScope(
            CaseScope scope, Root<TestCase> root, CriteriaBuilder cb) {

        if (!scope.boundByTree()) {
            return Optional.empty();
        }

        // §98: a named suite is already resolved to the cases it holds — filed ∪ added ∪ tagged —
        // so the predicate is on the case's own id and the three arms are not re-derived here.
        // It matched `suite_id IN (subtree)` until then, which is the FILED arm alone, so a suite
        // defined by a tag rule listed nothing under a count of twelve.
        if (scope.memberCaseIds() != null) {
            return Optional.of(root.get("id").in(scope.memberCaseIds()));
        }

        Path<UUID> suiteId = root.get("suiteId");
        if (!scope.includeUnfiled()) {
            return Optional.of(suiteId.in(scope.liveSuiteIds()));
        }
        if (scope.liveSuiteIds().isEmpty()) {
            return Optional.of(cb.isNull(suiteId));
        }
        return Optional.of(cb.or(suiteId.in(scope.liveSuiteIds()), cb.isNull(suiteId)));
    }

    /**
     * {@code tag} — cases carrying <strong>every</strong> tag named, one {@code EXISTS} per tag.
     *
     * <p>AND rather than OR, and deliberately the opposite of what a suite's tag rules do: a suite
     * claims everything tagged {@code smoke} <em>or</em> {@code payments} (it is a collection being
     * assembled), while a person adding a second chip to a grid is narrowing (they are looking for
     * one case). An OR here would widen the list they are trying to shorten, which reads as the
     * filter being broken rather than as a policy.
     *
     * <p>One correlated subquery per tag rather than {@code GROUP BY … HAVING COUNT(*) = n}: the
     * subquery form composes with every other predicate here without changing the shape of the
     * outer query, and the count form silently degrades to OR the moment the outer query grows a
     * join that duplicates rows.
     *
     * <p>Matched exactly — same case, same spelling, the way {@code suite_tag_rule} resolves. A
     * case-insensitive match here and an exact one there would put a case in the suite and leave it
     * out of the filter that named the suite's own tag.
     */
    private static Optional<Predicate> tags(
            CaseQuery request,
            Root<TestCase> root,
            AbstractQuery<?> query,
            CriteriaBuilder cb) {

        if (request.tags().isEmpty()) {
            return Optional.empty();
        }

        List<Predicate> each = new ArrayList<>();
        for (String tag : request.tags()) {
            Subquery<UUID> carried = query.subquery(UUID.class);
            Root<CaseTag> row = carried.from(CaseTag.class);
            carried.select(row.get("caseId"))
                    .where(cb.and(
                            cb.equal(row.get("caseId"), root.get("id")),
                            cb.equal(row.get("tag"), tag)));
            each.add(cb.exists(carried));
        }
        return Optional.of(cb.and(each.toArray(Predicate[]::new)));
    }

    /**
     * {@code q} — an exact key lookup, or a substring over title and description.
     *
     * <p>Never both: a value that parses as {@code TC-n} short-circuits (plan §6q), because someone
     * who pastes a key means that case and a substring search would bury the single exact match
     * under {@code TC-70}, {@code TC-700} and every title that mentions it.
     *
     * <h2>{@code LIKE}, not the tsvector, and why that is not a shortcut</h2>
     *
     * <p>{@code test_case.search_vector} exists and a V2 trigger maintains it, and this does not use
     * it. Two reasons were given, and <strong>only the second one survives</strong> — §235, which
     * shipped the {@code @@} path for documentation, checked both.
     *
     * <p>~~Criteria cannot express {@code @@} without registering a custom function, so using it
     * here would mean a native query — which drops the Hibernate company filter and leaves RLS as
     * the only boundary, the single-point-of-defence trade {@code ProjectRepository}'s header
     * refuses.~~ → <strong>that header refuses no such thing.</strong> It writes a native query and
     * spells {@code company_id = :companyId} out beside RLS, because the predicate is the intent and
     * the two agreeing is what makes a mismatch return nothing rather than something.
     * {@code DocPageRepository#search} follows it. The Criteria half is still true — this is a
     * dynamic specification and a native query is not available to it at all — but "native means one
     * boundary" was never the codebase's position.
     *
     * <p>The reason that stands: full-text search matches <em>lexemes</em>, so a <em>prefix</em>
     * finds nothing — {@code refun} would not find "refunds", and this box is a type-ahead fired on
     * every keystroke over titles the user is halfway through remembering. Stemming is the right
     * behaviour for prose searched on submit and the wrong one here, which is the whole of why the
     * two searches in this product are built differently.
     *
     * <p>{@code title} is covered by {@code test_case_title_trgm_idx}, a GIN trigram index that
     * serves {@code LIKE '%…%'}. {@code description} has no such index and is scanned; the contract
     * asks for both, the row set is already bounded to one project, and a second trigram index is a
     * schema change that belongs with whoever measures it.
     */
    private static Optional<Predicate> keyOrText(
            CaseQuery request, Root<TestCase> root, CriteriaBuilder cb) {

        Long keySeq = request.keySeq();
        if (keySeq != null) {
            return Optional.of(cb.equal(root.get("keySeq"), keySeq));
        }
        if (!request.hasFreeText()) {
            return Optional.empty();
        }

        // Escaped so a user typing a literal % or _ searches for that character rather than
        // silently matching everything — the difference between "no results" and "all results" on
        // a keystroke, which reads as the filter being broken.
        String needle = "%" + escapeLike(request.q().toLowerCase(Locale.ROOT)) + "%";
        return Optional.of(cb.or(
                cb.like(cb.lower(root.get("title")), needle, '\\'),
                cb.like(cb.lower(root.get("description")), needle, '\\')));
    }

    /**
     * {@code EXISTS (…)} against {@code case_classification} for one dimension's selected value.
     *
     * <p><strong>An EXISTS rather than a join</strong>, and that is the whole point: a case carries
     * one row per classification, so joining would multiply it — a case tagged with three features
     * would appear three times in the page, three times in the total, and three times in every
     * facet count. The de-duplication that fixes it (a {@code DISTINCT}) also breaks the count and
     * fights every {@code ORDER BY}. An EXISTS asks the question without changing the row set.
     *
     * <p>The value ids are the selected value <em>and its descendants</em>, resolved by
     * {@link CaseService}: the contract says a Feature filter includes descendants by default
     * (plan §6q), because picking "Payments" and being shown nothing — since every case is
     * classified under a leaf like "Partial refund" — reads as a broken filter rather than as a
     * precise one.
     *
     * <p>An empty set means the id named nothing live, and the predicate then matches nothing. That
     * is deliberate: a filter for a value that does not exist must return no cases, not all of them.
     */
    private static Optional<Predicate> classified(
            Collection<UUID> valueIds,
            Root<TestCase> root,
            AbstractQuery<?> query,
            CriteriaBuilder cb) {

        if (valueIds == null) {
            return Optional.empty();
        }
        if (valueIds.isEmpty()) {
            return Optional.of(cb.disjunction());
        }

        Subquery<UUID> matching = query.subquery(UUID.class);
        Root<CaseClassification> classification = matching.from(CaseClassification.class);
        matching.select(classification.get("caseId"))
                .where(cb.and(
                        cb.equal(classification.get("caseId"), root.get("id")),
                        classification.get("valueId").in(valueIds)));
        return Optional.of(cb.exists(matching));
    }

    /**
     * {@code EXISTS (…)} against {@code case_health} for any of the requested signals.
     *
     * <p>OR within the signals, matching every other multi-valued filter here: a user asking for
     * "stale approval or never executed" wants either, and an AND would make selecting two chips
     * return fewer rows than selecting one, which nobody reads as a feature.
     *
     * <p><strong>This matches nothing today</strong>, because nothing writes {@code case_health}
     * yet — see {@link HealthSignal}. That is the correct answer rather than a stub: no case has
     * had a signal computed, so no case has one. It starts returning rows when the computation
     * lands, with no change here.
     */
    private static Optional<Predicate> health(
            CaseQuery request,
            Root<TestCase> root,
            AbstractQuery<?> query,
            CriteriaBuilder cb) {

        if (request.healthSignal().isEmpty()) {
            return Optional.empty();
        }

        Subquery<UUID> raised = query.subquery(UUID.class);
        Root<CaseHealth> health = raised.from(CaseHealth.class);

        List<Predicate> anySignal = new ArrayList<>();
        for (HealthSignal signal : request.healthSignal()) {
            anySignal.add(cb.isTrue(health.get(signal.attribute())));
        }

        raised.select(health.get("caseId"))
                .where(cb.and(
                        cb.equal(health.get("caseId"), root.get("id")),
                        cb.or(anySignal.toArray(Predicate[]::new))));
        return Optional.of(cb.exists(raised));
    }

    /**
     * {@code ownerId} — the cases one person owns.
     *
     * <p>A plain equality on the column, deliberately without a membership check. Whether the id
     * names anyone at all is not asked: an id belonging to another company matches nothing, because
     * {@code owner_id} on a case in <em>this</em> company can only ever hold a user this company
     * knows — and answering differently for "no such person" than for "that person owns nothing"
     * would make the parameter an oracle for user ids the caller cannot otherwise reach, the same
     * reasoning {@link CaseService#subtreeOf} gives for classification values.
     *
     * <p>Unowned cases are reachable through no value of this filter, which is correct rather than a
     * gap: {@code ownerId=} empty means "no opinion" over HTTP, so an "unassigned" filter would need
     * a sentinel the contract does not define. The dashboard's by-owner breakdown has the matching
     * shape — it lists people, and cases with no owner are counted in no row.
     */
    private static Optional<Predicate> owner(
            CaseQuery request, Root<TestCase> root, CriteriaBuilder cb) {

        return request.ownerId() == null
                ? Optional.empty()
                : Optional.of(cb.equal(root.get("ownerId"), request.ownerId()));
    }

    /**
     * {@code releaseVersion} — "cases executed in this release", which today is none of them.
     *
     * <h2>Matching nothing is the honest answer; ignoring the parameter is not</h2>
     *
     * <p>The filter is defined over executions ({@code test_execution.release_version}), and the
     * {@code execution} module does not exist — nothing in the product can record an execution, so
     * <em>zero</em> cases have been executed in any release and an empty result is literally
     * correct.
     *
     * <p>The alternative — dropping the parameter — is what makes this worth spelling out. An
     * ignored filter returns the <em>entire</em> project and labels it "executed in 4.3.0", which is
     * the response asserting something the data never said. Same failure family as §22's dangling
     * "Your role:" and §24's title-less inline condition, and worse than either, because a filter
     * that silently does nothing is indistinguishable from one that found everything.
     *
     * <p>{@code CaseListEndpointTest} pins this, so the day {@code execution} lands the test fails
     * and names its own replacement rather than leaving a permanently-empty filter behind.
     */
    private static Optional<Predicate> release(CaseQuery request, CriteriaBuilder cb) {
        return request.releaseVersion() == null
                ? Optional.empty()
                : Optional.of(cb.disjunction());
    }

    /** {@code col IN (…)} for a multi-valued filter; absent when the caller expressed no opinion. */
    private static <T> Optional<Predicate> anyOf(
            Collection<T> values, Path<T> column, CriteriaBuilder cb) {

        // Empty means "unset", never "match nothing": an omitted parameter and an explicitly-empty
        // one are the same bytes over HTTP, so they cannot be told apart and the useful reading is
        // the only one available.
        return values.isEmpty() ? Optional.empty() : Optional.of(column.in(values));
    }

    /** Escapes the {@code LIKE} metacharacters, with backslash as the escape character. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
