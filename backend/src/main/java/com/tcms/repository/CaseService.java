package com.tcms.repository;

import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ClassificationValueView;
import com.tcms.project.ProjectConfigurationService;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One project's cases — filtered, sorted, paged and faceted.
 *
 * <p>The biggest screen in the product, and the first endpoint whose response the SPA renders as a
 * <em>working</em> screen rather than as reference data or a navigation tree.
 *
 * <h2>The gate is borrowed, again and unchanged</h2>
 *
 * <p>Every read goes through {@link ProjectService#readVisible}, so a caller passes exactly the two
 * gates {@code GET /projects/{projectId}} applies — company first, then the explicit project grant.
 * Nothing in this class or in {@link CaseSpecifications} decides whether a caller may see a project.
 * That property is what makes deleting the grant check inside that one method fail tests across
 * every endpoint built on it rather than only the one whose author removed it.
 *
 * <p>The leak this prevents is the largest one available so far: a suite tree sketches what a
 * client is building, and the case list <em>is</em> what they are building — every title, every
 * feature, every owner's name and email.
 *
 * <h2>Query count, stated rather than discovered</h2>
 *
 * <p>A request runs a fixed number of queries, none of them per row:
 *
 * <ol>
 *   <li>the project gate (through the permission evaluator),</li>
 *   <li>the project's live suites — for the names, the subtree scope, and the live-tree bound,</li>
 *   <li>the classification vocabularies — for the filters' descendants and the rendered names,</li>
 *   <li>the page, and its total,</li>
 *   <li>four facet tallies,</li>
 *   <li>tags, classifications and health for the page's cases — one query each,</li>
 *   <li>memberships and accounts for the page's owners — one query each.</li>
 * </ol>
 *
 * <p>Thirteen or so for any page size, rather than three plus five per row. The number is worth
 * writing down because the shape that produces it is unremarkable to read and one mapped
 * {@code @ManyToOne} away from being 5N. Items 6 and 7 now happen inside {@link CaseSummaries};
 * moving them did not change the count.
 *
 * <h2>What this class no longer does</h2>
 *
 * <p>Turning {@link TestCase} rows into {@link TestCaseSummaryView} lives in {@link CaseSummaries},
 * because {@code GET /projects/{projectId}/cases/{caseId}} owes the same fields — {@code TestCase}
 * is {@code allOf: [TestCaseSummary, …]} — and a second implementation of "which Test Type does
 * this case show" is how a grid and a detail page come to disagree about one case. What stays here
 * is what only a <em>list</em> has: the scope resolution, the paging, and the facets.
 */
@Service
public class CaseService {

    private final ProjectService projects;
    private final ProjectConfigurationService configuration;
    private final SuiteRepository suites;
    private final CaseSearch search;
    private final CaseSummaries summaries;

    public CaseService(
            ProjectService projects,
            ProjectConfigurationService configuration,
            SuiteRepository suites,
            CaseSearch search,
            CaseSummaries summaries) {

        this.projects = projects;
        this.configuration = configuration;
        this.suites = suites;
        this.search = search;
        this.summaries = summaries;
    }

    /**
     * One page of the project's cases, or empty when the caller may not see the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty page are different answers the
     * controller renders differently: 404 for "you may not see this project", an empty
     * {@code items} array for "you may, and nothing matches". Collapsing them would turn both a
     * freshly provisioned project and an over-narrow filter into a 404 — the first unreachable, the
     * second unrecoverable, since the user could not clear a filter on a page that will not load.
     */
    @Transactional(readOnly = true)
    public Optional<CasePageView> search(UUID userId, UUID projectId, CaseQuery request) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project ->
                page(companyId, userId, project.getId(), request));
    }

    private CasePageView page(UUID companyId, UUID userId, UUID projectId, CaseQuery request) {
        List<Suite> liveSuites = suites.findLiveForProject(projectId);
        // Through project's own service rather than its repositories: the vocabulary is that
        // module's to shape, and asking it through the gate a second time costs one permission
        // evaluation and keeps this module free of a second reading of another module's tables.
        List<ClassificationDimensionView> vocabularies =
                configuration.findClassification(userId, projectId).orElse(List.of());

        Collection<UUID> suiteIds = suiteScope(request, liveSuites);
        // Unfiled cases are in scope unless the request named a folder — a case cannot be both
        // inside a subtree and outside every folder — and `unfiled=true` asks for them alone.
        // Before §96 the suite scope WAS the bound, so an empty one meant "no case can match";
        // now it means that only when unfiled cases are excluded too.
        boolean includeUnfiled = request.suiteId() == null;
        // `unfiled` is IGNORED when a folder is named, per the contract, rather than refused with a
        // 400: the pair is unsatisfiable, and refusing it would break the tree's own "clear the
        // filter" affordance. It matters here for a second reason — with the folder's subtree
        // dropped AND unfiled cases excluded there would be no suite arm at all, and an empty
        // `IN ()` is a syntax error.
        boolean unfiledOnly = request.unfiled() && includeUnfiled;
        if (suiteIds.isEmpty() && !includeUnfiled) {
            // A suiteId naming a folder that is deleted or belongs elsewhere, or a subtree that is
            // entirely deleted. Both mean "no case can match", identically — and the guard is not
            // optional, because an empty collection bound into SQL `IN ()` is a syntax error rather
            // than an empty result. A project with no suites at all used to land here and no longer
            // does: since §96 it can hold cases, all of them unfiled, and answering an empty page
            // would hide every case in a freshly provisioned project.
            return empty(request);
        }

        CaseScope scope = new CaseScope(
                projectId,
                // Only the unfiled ones when the caller asked for those: the filter is a narrowing,
                // so it drops the suite arm rather than adding to it.
                unfiledOnly ? List.of() : suiteIds,
                includeUnfiled,
                subtreeOf(request.featureId(), DimensionKeys.FEATURE, vocabularies),
                subtreeOf(request.testTypeId(), DimensionKeys.TEST_TYPE, vocabularies),
                subtreeOf(request.testLevelId(), DimensionKeys.TEST_LEVEL, vocabularies));

        List<TestCase> cases = search.page(request, scope);

        return new CasePageView(
                summaries.render(companyId, cases, liveSuites, vocabularies),
                request.page(),
                request.size(),
                search.total(request, scope),
                facets(
                        search.tally(request, scope, "lifecycleStatus", LifecycleStatus.class),
                        search.tally(request, scope, "automationStatus", AutomationStatus.class),
                        search.tally(request, scope, "priority", Priority.class),
                        search.tallyHealth(request, scope)));
    }

    /**
     * The response when no case can match before a query is even built.
     *
     * <p>Facets are present and empty rather than omitted: the contract's response always carries
     * the array, and the SPA reads it to render counts beside chips it already knows about. An
     * absent {@code facets} would be a different shape for the empty case, which is the one shape a
     * consumer is least likely to have exercised.
     */
    private static CasePageView empty(CaseQuery request) {
        return new CasePageView(
                List.of(),
                request.page(),
                request.size(),
                0,
                facets(Map.of(), Map.of(), Map.of(), Map.of()));
    }

    // =====================================================================
    // scope resolution
    // =====================================================================

    /**
     * The suite ids a request may draw cases from: the {@code suiteId} filter's subtree, or the
     * whole live tree.
     *
     * <p>Walked from {@code parentId} in memory rather than through {@code ltree}'s {@code <@}
     * operator. The tree is already loaded — its names are needed for every row — so the walk is
     * free, whereas the operator needs a native query that would drop the Hibernate company filter.
     * And the set is folders, which a project has tens of, not cases, which it has thousands of.
     *
     * <p><strong>Only live suites are ever in the set</strong>, filter or no filter — see
     * {@link CaseSpecifications#where} for why a soft-deleted folder's cases must leave the list
     * with it.
     */
    private Collection<UUID> suiteScope(CaseQuery request, List<Suite> liveSuites) {
        if (request.suiteId() == null) {
            return liveSuites.stream().map(Suite::getId).toList();
        }

        Map<UUID, List<UUID>> children = new HashMap<>();
        Set<UUID> live = new HashSet<>();
        for (Suite suite : liveSuites) {
            live.add(suite.getId());
            if (suite.getParentId() != null) {
                children.computeIfAbsent(suite.getParentId(), key -> new ArrayList<>())
                        .add(suite.getId());
            }
        }
        if (!live.contains(request.suiteId())) {
            // A deleted folder, one in another project, or one that never existed. All three mean
            // no cases, identically — telling them apart would make the parameter an oracle for
            // suite ids the caller cannot otherwise reach.
            return List.of();
        }
        return descendants(request.suiteId(), children);
    }

    /**
     * One classification filter's value <em>and its descendants</em>, per the contract ("includes
     * descendant features by default", plan §6q).
     *
     * <p>Without the expansion, picking a parent Feature returns nothing whenever cases are
     * classified on leaves — which is how a Feature tree is normally used — and the filter reads as
     * broken rather than as precise. It happens here rather than in the predicate because it needs
     * the vocabulary, which the predicate has no business loading.
     *
     * <p>The id is looked up in <strong>its own dimension only</strong>. A {@code featureId} that
     * names a Test Type value resolves to nothing and the filter then matches nothing, rather than
     * quietly working: the three parameters are separate because the dimensions are, and accepting
     * any value on any axis would make {@code testTypeId} and {@code featureId} two names for one
     * parameter.
     *
     * @return null when unfiltered, empty when the id named nothing live in that dimension — two
     *         different answers, and {@link CaseSpecifications} treats them as such
     */
    private static Collection<UUID> subtreeOf(
            UUID valueId, String dimensionKey, List<ClassificationDimensionView> vocabularies) {

        if (valueId == null) {
            return null;
        }

        Map<UUID, List<UUID>> children = new HashMap<>();
        Set<UUID> live = new HashSet<>();
        for (ClassificationDimensionView dimension : vocabularies) {
            if (!dimension.key().equals(dimensionKey)) {
                continue;
            }
            for (ClassificationValueView value : dimension.values()) {
                live.add(value.id());
                if (value.parentId() != null) {
                    children.computeIfAbsent(value.parentId(), key -> new ArrayList<>())
                            .add(value.id());
                }
            }
        }
        // A retired value, one from another dimension, one from another client, or one that never
        // existed. All four mean no cases, identically — distinguishing them would make the
        // parameter an oracle for value ids the caller cannot otherwise reach.
        return live.contains(valueId) ? descendants(valueId, children) : List.of();
    }

    /** Breadth-first from one node, inclusive of it. */
    private static Set<UUID> descendants(UUID root, Map<UUID, List<UUID>> children) {
        Set<UUID> scope = new LinkedHashSet<>();
        Deque<UUID> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            UUID next = pending.removeFirst();
            if (scope.add(next)) {
                pending.addAll(children.getOrDefault(next, List.of()));
            }
        }
        return scope;
    }

    // =====================================================================
    // facets
    // =====================================================================

    /**
     * The four facet groups, in the order the SPA's filter bar renders them.
     *
     * <p>Labels are the group names; the <em>values</em> carry their enum constant as both id and
     * label, because the SPA labels enum values from its own catalogue — the one place a new member
     * is a compile error until it is labelled. A second set of labels here would be the one that
     * goes stale, and the symptom would be a filter chip and a grid cell disagreeing about the name
     * of the same status.
     */
    private static List<FacetView> facets(
            Map<LifecycleStatus, Long> lifecycle,
            Map<AutomationStatus, Long> automation,
            Map<Priority, Long> priority,
            Map<HealthSignal, Long> health) {

        return List.of(
                facet("lifecycleStatus", "Status", lifecycle),
                facet("automationStatus", "Automation", automation),
                facet("priority", "Priority", priority),
                facet("healthSignal", "Health", health));
    }

    private static <E extends Enum<E>> FacetView facet(
            String key, String label, Map<E, Long> counts) {

        List<FacetView.Value> values = counts.entrySet().stream()
                // Zero-count entries are dropped rather than shown: a chip offering "0" is a filter
                // the user can select and get an empty grid from. Only the health tally can produce
                // one — a GROUP BY cannot return an empty group — and today it produces nine.
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> new FacetView.Value(
                        entry.getKey().name(), entry.getKey().name(), entry.getValue()))
                .toList();
        return new FacetView(key, label, values);
    }
}
