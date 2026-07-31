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

        // Removing this line is a mutation that SURVIVES, and it survives structurally rather than
        // for want of a test. The suite scope below is already the project's own live tree, and a
        // suite id is a UUID primary key — so a case in another project hangs off a suite that is
        // never in the set, and no fixture can make the difference observable. Recorded rather
        // than quietly left as a passing predicate nobody can account for; SuiteRepository's
        // countLiveCasesBySuite documents the same family.
        //
        // It stays for two reasons that are not "in case the join key changes". It is what makes
        // the query reachable through test_case_lifecycle_idx (company_id, project_id,
        // lifecycle_status), and it states the bound directly instead of leaving it implied by a
        // set of ids assembled elsewhere. The intent it states is the one RLS and the suite scope
        // enforce, and the three agreeing is what makes a future mismatch return nothing rather
        // than something.
        all.add(cb.equal(root.get("projectId"), scope.projectId()));

        // Soft-deleted cases are gone from every read: past executions still reference them and
        // pin the version they ran, which is why the row survives at all.
        all.add(cb.isNull(root.get("deletedAt")));

        all.add(suiteScope(scope.liveSuiteIds(), root));

        keyOrText(request, root, cb).ifPresent(all::add);

        classified(scope.featureIds(), root, query, cb).ifPresent(all::add);
        classified(scope.testTypeIds(), root, query, cb).ifPresent(all::add);
        classified(scope.testLevelIds(), root, query, cb).ifPresent(all::add);

        anyOf(request.lifecycleStatus(), root.get("lifecycleStatus"), cb).ifPresent(all::add);
        anyOf(request.automationStatus(), root.get("automationStatus"), cb).ifPresent(all::add);
        anyOf(request.priority(), root.get("priority"), cb).ifPresent(all::add);

        health(request, root, query, cb).ifPresent(all::add);
        release(request, cb).ifPresent(all::add);

        return cb.and(all.toArray(Predicate[]::new));
    }

    /**
     * The suites this request may draw from — the {@code suiteId} filter's subtree when one is
     * given, and the project's whole live tree otherwise.
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
     */
    private static Predicate suiteScope(Collection<UUID> liveSuiteIds, Root<TestCase> root) {
        return root.get("suiteId").in(liveSuiteIds);
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
     * it. Two reasons, both concrete. Criteria cannot express {@code @@} without registering a
     * custom function, so using it here would mean a native query — which drops the Hibernate
     * company filter and leaves RLS as the only boundary, the single-point-of-defence trade
     * {@code ProjectRepository}'s header refuses. And full-text search matches <em>lexemes</em>:
     * {@code refund} would not find "refunds" typed halfway through a word, which is what this box
     * is used for — a type-ahead over titles the user is halfway through remembering.
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
