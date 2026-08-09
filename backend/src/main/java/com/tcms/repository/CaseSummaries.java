package com.tcms.repository;

import com.tcms.api.UserRef;
import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ClassificationValueView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cases rendered as {@link TestCaseSummaryView} — the shape the grid draws a row from and the
 * shape the detail response's first half <em>is</em>.
 *
 * <h2>Why this is a component and not two private methods</h2>
 *
 * <p>{@code TestCase} in {@code openapi.yaml} is {@code allOf: [TestCaseSummary, …]}, so the
 * detail endpoint owes every field the grid owes: the suite's name, the owner resolved through the
 * active company's membership, the classification values resolved to names and dropped when
 * retired, the tags, the health signals. Building that a second time inside a detail service is
 * how "a hundred consistent pages become a hundred slightly different ones" — the same rule
 * {@code CLAUDE.md} states for the SPA's shared display layer, arriving on the server.
 *
 * <p>The failure it prevents is specific and quiet: the grid drops a retired classification value
 * and a second implementation renders it, so a case shows one Test Type in the list and another on
 * its own page. Nothing errors, both look plausible, and which one is right is not visible from
 * either screen.
 *
 * <p>Extracted from {@code CaseService} rather than written fresh — same code, same comments,
 * moved — so the grid's behaviour is the behaviour under test, not a re-derivation of it.
 *
 * <h2>A fixed number of queries, never a number per row</h2>
 *
 * <p>Tags, classifications, health and owners are four bulk lookups (owners is two — memberships
 * and accounts) over the whole batch, regardless of how many cases are in it. That is the property
 * a mapped {@code @ManyToOne} or {@code @ElementCollection} would take away for free, and it holds
 * for a batch of one exactly as it does for a page of two hundred.
 */
@Component
public class CaseSummaries {

    private final CaseTagRepository tags;
    private final CaseClassificationRepository classifications;
    private final CaseHealthRepository health;
    private final CaseOwners owners;

    public CaseSummaries(
            CaseTagRepository tags,
            CaseClassificationRepository classifications,
            CaseHealthRepository health,
            CaseOwners owners) {

        this.tags = tags;
        this.classifications = classifications;
        this.health = health;
        this.owners = owners;
    }

    /**
     * Renders a batch of cases, preserving the order it was given.
     *
     * @param liveSuites the project's live suites — the source of every row's {@code suiteName}.
     *                   Passed in rather than queried here because both callers have already loaded
     *                   the tree for their own reasons, and a second read of it would be one query
     *                   per request spent re-fetching what the caller is holding.
     * @param vocabularies the project's classification dimensions, live values only. Values absent
     *                   from it are dropped from the output — see {@link #classify}.
     */
    List<TestCaseSummaryView> render(
            UUID companyId,
            List<TestCase> cases,
            List<Suite> liveSuites,
            List<ClassificationDimensionView> vocabularies) {

        if (cases.isEmpty()) {
            // Not merely an optimization: every lookup below binds the case ids into SQL `IN ()`,
            // and an empty collection there is a syntax error.
            return List.of();
        }

        List<UUID> caseIds = cases.stream().map(TestCase::getId).toList();

        Map<UUID, String> suiteNames = new HashMap<>();
        liveSuites.forEach(suite -> suiteNames.put(suite.getId(), suite.getName()));

        Map<UUID, List<String>> tagsByCase = tagsByCase(caseIds);
        Map<UUID, List<HealthSignal>> healthByCase = healthByCase(caseIds);
        // Owners and authors resolve through ONE call, not two. They are the same kind of lookup
        // over the same two tables, they overlap heavily in practice (most cases are still owned by
        // whoever wrote them), and `CaseOwners.resolve` is two queries regardless of how many ids
        // it is given. Two calls would double that for a column, and the duplicate ids would be
        // fetched twice — which is the shape of N+1 that looks fine because each query is fast.
        Map<UUID, UserRef> peopleById = owners.resolve(companyId, peopleIds(cases));
        ClassifiedCases classified = classify(caseIds, vocabularies);

        return cases.stream()
                .map(row -> new TestCaseSummaryView(
                        row.getId(),
                        row.getKey(),
                        row.getKeySeq(),
                        row.getTitle(),
                        row.getSuiteId(),
                        // Every case the grid renders is in a live suite by construction — its
                        // query is bounded by that set — so for that caller this default is
                        // unreachable. The detail endpoint reads one case by id and is NOT so
                        // bounded: a case whose folder was soft-deleted is still live itself and
                        // still reachable by a bookmarked link. Empty rather than a placeholder,
                        // because a name this endpoint could not resolve is not a name it should
                        // invent.
                        //
                        // NULL, though, for a case filed in no suite at all (§96) — a different
                        // answer to a different question: "" is a folder this read could not name,
                        // null is a case that has no folder, and the contract marks the id and the
                        // name nullable together so the client can render its "unfiled" affordance
                        // off the id. Without this branch a null id takes the getOrDefault path —
                        // HashMap looks up a null key happily — and the case arrives unfiled with
                        // a suite name of "", which is the one combination the contract forbids.
                        row.getSuiteId() == null
                                ? null
                                : suiteNames.getOrDefault(row.getSuiteId(), ""),
                        row.getPriority(),
                        row.getLifecycleStatus(),
                        row.getAutomationStatus(),
                        row.getStepFormat(),
                        // Null for an unowned case, and null for one whose owner account no longer
                        // resolves. Both are honest; see TestCaseSummaryView.
                        row.getOwnerId() == null ? null : peopleById.get(row.getOwnerId()),
                        // Null for a case written before authorship was recorded, and null for one
                        // whose author's account no longer resolves. The first is the arm the
                        // delete gate turns on: canDoOnAuthored answers a null author `false` for
                        // an OWN grant, so the SPA must be able to see the absence rather than
                        // infer it from a missing key.
                        row.getCreatedBy() == null ? null : peopleById.get(row.getCreatedBy()),
                        classified.single(row.getId(), DimensionKeys.TEST_TYPE),
                        classified.single(row.getId(), DimensionKeys.TEST_LEVEL),
                        classified.all(row.getId(), DimensionKeys.FEATURE),
                        tagsByCase.getOrDefault(row.getId(), List.of()),
                        healthByCase.getOrDefault(row.getId(), List.of()),
                        row.getUpdatedAt()))
                .toList();
    }

    /**
     * Every distinct, non-null person id the batch names — owners and authors together, the input
     * to one membership query and one account query.
     *
     * <p>A set, so a case owned by the person who wrote it contributes one id rather than two, and
     * so two cases by the same author do not widen the {@code IN ()} list.
     */
    private static Set<UUID> peopleIds(List<TestCase> cases) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (TestCase row : cases) {
            if (row.getOwnerId() != null) {
                ids.add(row.getOwnerId());
            }
            if (row.getCreatedBy() != null) {
                ids.add(row.getCreatedBy());
            }
        }
        return ids;
    }

    private Map<UUID, List<String>> tagsByCase(List<UUID> caseIds) {
        Map<UUID, List<String>> grouped = new HashMap<>();
        for (CaseTag tag : tags.findForCases(caseIds)) {
            grouped.computeIfAbsent(tag.getCaseId(), key -> new ArrayList<>()).add(tag.getTag());
        }
        return grouped;
    }

    private Map<UUID, List<HealthSignal>> healthByCase(List<UUID> caseIds) {
        Map<UUID, List<HealthSignal>> grouped = new HashMap<>();
        for (CaseHealth row : health.findForCases(caseIds)) {
            grouped.put(row.getCaseId(), row.signals());
        }
        return grouped;
    }

    /**
     * The batch's classifications, resolved to names and bucketed by dimension key.
     *
     * <p>Values absent from the live vocabulary are dropped: a retired value is still attached to
     * every case that carried it, and rendering it would present a withdrawn classification as
     * current — the same omission {@code SuiteConditionView} makes for a retired condition, for the
     * same reason.
     */
    private ClassifiedCases classify(
            List<UUID> caseIds, List<ClassificationDimensionView> vocabularies) {

        Map<UUID, String> nameByValue = new HashMap<>();
        Map<UUID, String> keyByDimension = new HashMap<>();
        for (ClassificationDimensionView dimension : vocabularies) {
            keyByDimension.put(dimension.id(), dimension.key());
            for (ClassificationValueView value : dimension.values()) {
                nameByValue.put(value.id(), value.name());
            }
        }

        Map<UUID, Map<String, List<String>>> byCase = new HashMap<>();
        for (CaseClassification assignment : classifications.findForCases(caseIds)) {
            String name = nameByValue.get(assignment.getValueId());
            String dimension = keyByDimension.get(assignment.getDimensionId());
            if (name == null || dimension == null) {
                continue;
            }
            byCase.computeIfAbsent(assignment.getCaseId(), key -> new LinkedHashMap<>())
                    .computeIfAbsent(dimension, key -> new ArrayList<>())
                    .add(name);
        }
        byCase.values().forEach(dimensions -> dimensions.values().forEach(names -> names.sort(null)));
        return new ClassifiedCases(byCase);
    }

    /**
     * One batch's classifications, keyed case → dimension key → value names.
     *
     * <p>{@link #single} exists because Test Type and Test Level are SINGLE-cardinality dimensions
     * and the contract gives each a scalar field. It returns the first name rather than refusing a
     * second: the cardinality is enforced by a V2 trigger at write time, so a second value here
     * would mean the trigger had been bypassed — and a read endpoint is the wrong place to discover
     * that, and the worst place to fail the whole response over it.
     */
    private record ClassifiedCases(Map<UUID, Map<String, List<String>>> byCase) {

        String single(UUID caseId, String dimensionKey) {
            List<String> names = all(caseId, dimensionKey);
            return names.isEmpty() ? null : names.getFirst();
        }

        List<String> all(UUID caseId, String dimensionKey) {
            return byCase.getOrDefault(caseId, Map.of()).getOrDefault(dimensionKey, List.of());
        }
    }
}
