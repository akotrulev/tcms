package com.tcms.repository;

import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One project's suite tree, as the flat list the contract specifies.
 *
 * <h2>The gate is borrowed, not restated</h2>
 *
 * <p>Every read here goes through {@link ProjectService#readVisible}, so a caller passes exactly
 * the two gates that {@code GET /projects/{projectId}} applies — company first, then the explicit
 * project grant. Nothing in this class decides whether a caller may see a project.
 *
 * <p>This is the first endpoint <em>outside</em> the {@code project} module to be built on that
 * method, and it is the case the method was extracted for. The failure it prevents is not the
 * dramatic one: an endpoint that checks only that the project exists in the caller's company
 * passes every cross-company test in the suite — the rows genuinely are in the caller's company —
 * and hands a member who was granted nothing the client's entire test tree. A suite tree names
 * what a client tests, folder by folder, which is a fair sketch of what they are building.
 *
 * <h2>Flat, because the client builds the tree</h2>
 *
 * <p>The contract is explicit: "a flat list with parentId (client builds the tree)". Nesting it
 * server-side would put a second tree-shaped contract beside the one the SPA already implements in
 * {@code SuiteTree.tsx}, and a nested response is also strictly worse to filter — the case list's
 * suite filter needs to find one node, not walk to it.
 *
 * <h2>Three queries, never one per suite</h2>
 *
 * <p>Suites, their case counts, and their condition attachments are three queries whose results
 * are grouped in memory. The alternatives are worse in both directions: mapped {@code @OneToMany}
 * associations would lazily load conditions one suite at a time, and a single joined query would
 * multiply each suite row by its condition count and need de-duplicating anyway. A tree is the
 * shape where an N+1 stops being cheap first — the count is the number of folders, not the number
 * of projects.
 */
@Service
public class SuiteService {

    private final ProjectService projects;
    private final SuiteRepository suites;
    private final SuiteConditionRepository attachments;
    private final ConditionRepository conditions;
    private final SuiteMembership membership;
    private final SuiteTagRuleRepository tagRules;
    private final SuiteCaseRepository addedCases;

    public SuiteService(
            ProjectService projects,
            SuiteRepository suites,
            SuiteConditionRepository attachments,
            ConditionRepository conditions,
            SuiteMembership membership,
            SuiteTagRuleRepository tagRules,
            SuiteCaseRepository addedCases) {

        this.projects = projects;
        this.suites = suites;
        this.attachments = attachments;
        this.conditions = conditions;
        this.membership = membership;
        this.tagRules = tagRules;
        this.addedCases = addedCases;
    }

    /**
     * The project's live suites, or empty when the caller may not see the project.
     *
     * <p>An empty {@link Optional} and a present-but-empty list are different answers and the
     * controller renders them differently: 404 for "you may not see this project", {@code []} for
     * "you may, and it has no suites". Collapsing them would turn a freshly provisioned project —
     * which has no suites at all, since nothing seeds one — into a 404 on the first screen the SPA
     * opens after the picker.
     */
    @Transactional(readOnly = true)
    public Optional<List<SuiteView>> findTree(UUID userId, UUID projectId) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> render(companyId, project.getId()));
    }

    /**
     * The rendered tree for a project whose gate the caller has <strong>already</strong> passed.
     *
     * <p>Package-private and ungated, which is the one thing to be careful about here: it takes a
     * {@code projectId} and checks nothing, so every caller must have come through
     * {@link ProjectService#readVisible} or {@link ProjectService#writeGranted} first — as
     * {@link #findTree} above does and as {@link SuiteWriteService} does, both from inside the
     * gate's own callback, which is the shape that makes it hard to call otherwise.
     *
     * <p>It exists so a write can serve back the same suite shape a read serves. The alternative —
     * {@link SuiteWriteService} assembling a {@link SuiteView} itself — is a second definition of a
     * suite's wire shape, and the field that diverges shows one value on create and another on the
     * reload after it.
     */
    /**
     * One written suite, as the API serves it — the single-row form of {@link #render}.
     *
     * <p>Lives here rather than in each writer, and that placement is the lesson {@code CaseRenderer}
     * paid for twice: a private copy of "read the write back" in every write service is invisible
     * while the copies agree and visible the moment a field is added to one of them. §68 found the
     * <em>read</em> path was a fourth such copy on a case, which is the wrong way round, since it is
     * the render the writers exist to agree with. {@link SuiteWriteService} and
     * {@link SuiteStatusService} both call this.
     *
     * <p>The cost is re-reading the tree, which is one query over a table with as many rows as the
     * project has folders. Callers must already be inside a gate — see {@link #render}.
     */
    SuiteView viewOf(UUID companyId, UUID projectId, Suite suite) {
        return render(companyId, projectId).stream()
                .filter(rendered -> rendered.id().equals(suite.getId()))
                .findFirst()
                // Unreachable: the row was written and flushed in this transaction, so the read that
                // follows sees it. Thrown rather than defaulted because a suite that vanished
                // between its own write and the next SELECT is a broken invariant, not a case to
                // render around.
                .orElseThrow(() -> new IllegalStateException(
                        "Suite " + suite.getId() + " was written and did not read back"));
    }

    List<SuiteView> render(UUID companyId, UUID projectId) {
        List<Suite> live = suites.findLiveForProject(projectId);
        if (live.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error, so the condition query below must not run. An empty tree is the normal state
            // of a new project, not an edge case. Same guard, same reason, as
            // ProjectService.listVisible.
            return List.of();
        }

        List<UUID> suiteIds = live.stream().map(Suite::getId).toList();
        Map<UUID, Long> caseCounts = membership.countsBySuite(companyId, projectId);
        Map<UUID, List<SuiteConditionView>> bySuite = conditionsBySuite(suiteIds);
        Map<UUID, List<String>> tagRules = tagRulesBySuite(suiteIds);
        Map<UUID, List<UUID>> addedCases = addedCasesBySuite(suiteIds);

        return live.stream()
                .map(suite -> new SuiteView(
                        suite.getId(),
                        suite.getKey(),
                        suite.getParentId(),
                        suite.getName(),
                        suite.getDescription(),
                        suite.getPath(),
                        // A suite with no members has no GROUP BY row at all, so this default is
                        // the normal path for an empty folder rather than a fallback for a broken
                        // query.
                        caseCounts.getOrDefault(suite.getId(), 0L),
                        // [] and never null, for `conditions`' reason below: both are required by
                        // the contract, so the SPA cannot null-check either.
                        tagRules.getOrDefault(suite.getId(), List.of()),
                        addedCases.getOrDefault(suite.getId(), List.of()),
                        suite.getLifecycleStatus(),
                        // Must serialize as [] and never as null: the contract marks `conditions`
                        // required, so the SPA cannot null-check it.
                        bySuite.getOrDefault(suite.getId(), List.of()),
                        suite.getLockVersion()))
                .toList();
    }

    /**
     * The tag rules of a page of suites, grouped — one query for the whole tree.
     *
     * <p>Alphabetical, from the repository's own ordering: rules are a set, so there is no authored
     * order to preserve, and a stable one keeps a node's chips from reshuffling between requests.
     */
    private Map<UUID, List<String>> tagRulesBySuite(List<UUID> suiteIds) {
        Map<UUID, List<String>> rules = new HashMap<>();
        for (SuiteTagRule rule : tagRules.findForSuites(suiteIds)) {
            rules.computeIfAbsent(rule.getSuiteId(), key -> new ArrayList<>()).add(rule.getTag());
        }
        return rules;
    }

    /** The individually-added cases of a page of suites, grouped — one query for the whole tree. */
    private Map<UUID, List<UUID>> addedCasesBySuite(List<UUID> suiteIds) {
        Map<UUID, List<UUID>> added = new HashMap<>();
        for (SuiteCase member : addedCases.findForSuites(suiteIds)) {
            added.computeIfAbsent(member.getSuiteId(), key -> new ArrayList<>())
                    .add(member.getCaseId());
        }
        return added;
    }

    /**
     * Resolves each attachment against the live library and groups by suite.
     *
     * <p>Preserves the query's ordering within each suite — {@code ArrayList}, not a set — because
     * a condition list is a sequence of actions and its order is its content.
     */
    private Map<UUID, List<SuiteConditionView>> conditionsBySuite(List<UUID> suiteIds) {
        List<SuiteCondition> live = attachments.findForSuites(suiteIds);
        if (live.isEmpty()) {
            return Map.of();
        }

        // LinkedHashSet: de-duplicated, because two suites referencing one library condition is
        // the ordinary case — that reuse is what the library is for.
        Set<UUID> referenced = new LinkedHashSet<>();
        live.forEach(attachment -> referenced.add(attachment.getConditionId()));

        Map<UUID, Condition> library = new HashMap<>();
        conditions.findLiveByIds(referenced).forEach(condition ->
                library.put(condition.getId(), condition));

        Map<UUID, List<SuiteConditionView>> grouped = new HashMap<>();
        for (SuiteCondition attachment : live) {
            Condition condition = library.get(attachment.getConditionId());
            if (condition == null) {
                // The library entry was retired. Serving it would offer a withdrawn precondition
                // as current; see SuiteConditionView for why the omission is the honest answer and
                // what actually closes it.
                continue;
            }
            grouped.computeIfAbsent(attachment.getSuiteId(), key -> new ArrayList<>())
                    .add(SuiteConditionView.of(attachment, condition));
        }
        return grouped;
    }
}
