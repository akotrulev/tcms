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
 * and hands a member who was granted nothing the client's entire suite list. A suite list names
 * what a client tests, collection by collection, which is a fair sketch of what they are building.
 *
 * <h2>Flat, and since §98 that is the data rather than a serialization choice</h2>
 *
 * <p>It was already flat on the wire — "a flat list with parentId (client builds the tree)" —
 * because nesting server-side would have put a second tree-shaped contract beside the one the SPA
 * implemented, and a nested response is strictly worse to filter. There is no tree to nest now.
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
     * One suite, or empty when the caller may not see the project <em>or</em> the suite is not in
     * it — both 404, per this controller's "404, never 403" rule.
     *
     * <p>Collapsing the two is deliberate here in a way it is not on {@link #findTree}: an id that
     * answers differently for "no such suite" and "not your suite" is the oracle that rule exists to
     * close, and unlike the empty-tree case there is no legitimate state a client has to tell apart.
     *
     * <p>It renders the project's suites and picks one out, which is {@link #viewOf}'s shape and
     * {@link #viewOf}'s reason: the alternative is a second definition of a suite's wire shape, and
     * the field that diverges shows one value on the list and another on the detail beside it. The
     * cost is bounded by the number of folders in one project.
     *
     * <p><strong>This is the operation the contract has declared since M0 and nothing implemented.</strong>
     * The SPA never needed it — it builds every screen from the list — so the gap survived until
     * §140 and §144 wrote three consumers against it: the MCP {@code tcms_list_suites} "open one"
     * arm, and the read half of the read-modify-write behind both clients' {@code suite edit}. All
     * three answered 405 in production while every gate was green. See
     * {@code ContractParameterBindingTest.everyDeclaredOperationHasAHandler}, which is now the check
     * that says so.
     */
    @Transactional(readOnly = true)
    public Optional<SuiteView> findOne(UUID userId, UUID projectId, UUID suiteId) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> render(companyId, project.getId()))
                .flatMap(rendered -> rendered.stream()
                        .filter(suite -> suite.id().equals(suiteId))
                        .findFirst());
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
        Map<ConditionKind, Map<UUID, List<SuiteConditionView>>> byKind = conditionsBySuite(suiteIds);
        Map<UUID, List<SuiteConditionView>> pre = byKind.get(ConditionKind.PRE);
        Map<UUID, List<SuiteConditionView>> post = byKind.get(ConditionKind.POST);
        Map<UUID, List<String>> tagRules = tagRulesBySuite(suiteIds);
        Map<UUID, List<UUID>> addedCases = addedCasesBySuite(suiteIds);

        return live.stream()
                .map(suite -> new SuiteView(
                        suite.getId(),
                        suite.getKey(),
                        suite.getName(),
                        suite.getDescription(),
                        // A suite with no members has no GROUP BY row at all, so this default is
                        // the normal path for an empty suite rather than a fallback for a broken
                        // query.
                        caseCounts.getOrDefault(suite.getId(), 0L),
                        // [] and never null, for `conditions`' reason below: both are required by
                        // the contract, so the SPA cannot null-check either.
                        tagRules.getOrDefault(suite.getId(), List.of()),
                        addedCases.getOrDefault(suite.getId(), List.of()),
                        suite.getLifecycleStatus(),
                        // Must serialize as [] and never as null: the contract marks both lists
                        // required, so the SPA cannot null-check either. A suite with setup and no
                        // cleanup is the ordinary case, so the empty side is the normal path here
                        // rather than a fallback.
                        pre.getOrDefault(suite.getId(), List.of()),
                        post.getOrDefault(suite.getId(), List.of()),
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
     * Resolves each attachment against the live library and groups it by <strong>kind, then
     * suite</strong>.
     *
     * <p>Preserves the query's ordering within each suite — {@code ArrayList}, not a set — because
     * a condition list is a sequence of actions and its order is its content.
     *
     * <p>Both kinds are always present in the outer map, even when nothing of that kind exists
     * anywhere in the project. The alternative is a {@code null} the caller has to remember to
     * default, on a path whose whole job is producing two arrays that are never null — see
     * {@link #render}, and see {@code ConditionKind} for why there are exactly two of them to
     * enumerate rather than a set discovered from the data.
     */
    private Map<ConditionKind, Map<UUID, List<SuiteConditionView>>> conditionsBySuite(
            List<UUID> suiteIds) {

        Map<ConditionKind, Map<UUID, List<SuiteConditionView>>> byKind = new HashMap<>();
        for (ConditionKind kind : ConditionKind.values()) {
            byKind.put(kind, new HashMap<>());
        }

        List<SuiteCondition> live = attachments.findForSuites(suiteIds);
        if (live.isEmpty()) {
            return byKind;
        }

        // LinkedHashSet: de-duplicated, because two suites referencing one library condition is
        // the ordinary case — that reuse is what the library is for.
        Set<UUID> referenced = new LinkedHashSet<>();
        live.forEach(attachment -> referenced.add(attachment.getConditionId()));

        Map<UUID, Condition> library = new HashMap<>();
        conditions.findLiveByIds(referenced).forEach(condition ->
                library.put(condition.getId(), condition));

        for (SuiteCondition attachment : live) {
            Condition condition = library.get(attachment.getConditionId());
            if (condition == null) {
                // The library entry was retired. Serving it would offer a withdrawn precondition
                // as current; see SuiteConditionView for why the omission is the honest answer and
                // what actually closes it.
                continue;
            }
            byKind.get(attachment.getKind())
                    .computeIfAbsent(attachment.getSuiteId(), key -> new ArrayList<>())
                    .add(SuiteConditionView.of(attachment, condition));
        }
        return byKind;
    }
}
