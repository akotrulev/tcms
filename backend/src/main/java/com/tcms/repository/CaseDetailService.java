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
 * One whole case — the last read the {@code repository} module owes the SPA.
 *
 * <h2>Separate from {@link CaseService}, and the reason is not file size</h2>
 *
 * <p>The two endpoints share a gate and a renderer and nothing else. A list resolves a scope,
 * pages, sorts and tallies facets; a detail resolves an inheritance chain and two condition lists.
 * Neither half is reachable from the other's entry point, and the shared parts are already
 * components ({@link CaseSummaries}, {@link CaseConditions}) rather than private methods — which is
 * what makes splitting them a boundary rather than a copy.
 *
 * <h2>Two gates, and the second one is inside the query</h2>
 *
 * <p>{@link ProjectService#readVisible} answers "may this caller see this project" — company
 * membership, then the explicit project grant — and is borrowed unchanged, as every read in this
 * module borrows it. {@link TestCaseRepository#findLiveInProject} answers the second question a
 * two-segment path raises and a one-segment path does not: <strong>is this case in that
 * project</strong>. Checking only the first would serve any case in the company through a path
 * naming a project the caller happens to hold — and every cross-company test in the suite would
 * still pass, because the row genuinely is in the caller's company.
 *
 * <p>Both failures collapse into the same empty {@link Optional} and the same bodiless 404. A
 * response that distinguished "no such case" from "not yours" would make the endpoint an oracle
 * for case ids in projects the caller cannot open — the inference channel §6t and handoff §3
 * lesson 13 exist to close.
 *
 * <h2>Six queries, fixed</h2>
 *
 * <ol>
 *   <li>the project gate (through the permission evaluator),</li>
 *   <li>the case,</li>
 *   <li>the project's live suites — for the folder name and the inheritance chain,</li>
 *   <li>the classification vocabularies,</li>
 *   <li>{@link CaseSummaries}' four bulk lookups over a batch of one,</li>
 *   <li>{@link CaseConditions}' four, plus the steps and the automation tags.</li>
 * </ol>
 *
 * <p>Around fifteen for one case, and none of them proportional to the case's size — no query here
 * runs per step, per condition or per ancestor. That is worth stating because a detail endpoint is
 * where a mapped {@code @OneToMany} looks harmless: it would be, for this endpoint, and then the
 * grid loads the same entity two hundred times.
 */
@Service
public class CaseDetailService {

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseRenderer renderer;
    private final ConditionRepository conditions;

    public CaseDetailService(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseRenderer renderer,
            ConditionRepository conditions) {

        this.projects = projects;
        this.cases = cases;
        this.suites = suites;
        this.renderer = renderer;
        this.conditions = conditions;
    }

    /**
     * One case, or empty when the caller may not see the project or the case is not in it.
     *
     * <p>Unlike {@link CaseService#search}, there is no "you may, and there is nothing" answer to
     * distinguish: a case either resolves or it does not. So the empty {@link Optional} carries
     * both failures and the controller renders one 404 for them.
     */
    @Transactional(readOnly = true)
    public Optional<TestCaseDetailView> findOne(UUID userId, UUID projectId, UUID caseId) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> cases.findLiveInProject(project.getId(), caseId))
                .map(row -> detail(companyId, userId, projectId, row));
    }

    /**
     * The rows pasted Gherkin text would become — or empty when the caller may not see the project.
     *
     * <h2>Here rather than in a service of its own</h2>
     *
     * <p>This endpoint has no state and no write: all it needs is the gate {@link #findOne} already
     * borrows, and the pure function next to it. A bean whose only body is
     * {@code projects.readVisible(…, GherkinScenarios::parse)} would be a layer that forwards, which
     * the case editor's other read does not have either.
     *
     * <p><strong>Nothing is persisted and nothing is looked up.</strong> The project is resolved to
     * answer "may this caller see it" and then discarded; the answer is a function of the argument
     * alone, which is what lets the same text be pasted repeatedly with no effect.
     *
     * @throws GherkinSyntaxException when the text will not parse. It leaves the transaction as it
     *         leaves this method — the transaction is read-only and has nothing to unwind, and the
     *         controller turns it into the 400 with the line on it.
     */
    @Transactional(readOnly = true)
    public Optional<GherkinParseResult> parseGherkin(UUID userId, UUID projectId, String text) {
        return projects.readVisible(userId, projectId, project -> GherkinScenarios.parse(text));
    }

    /**
     * The {@code .feature} text an unsaved case would render as — {@link #parseGherkin}'s inverse
     * (003 Phase 2, C1).
     *
     * <p>Beside its twin for that method's reason, and it takes the <strong>same read gate and no
     * write permission</strong>: it stores nothing, so a caller with no grant on the project gets
     * the same empty {@link Optional} and the same bodiless 404 every other read here answers.
     *
     * <p><strong>Two lookups, and they are the only reason this is not a pure function.</strong> The
     * {@code Feature:} line is the suite's name and the {@code Background:} block is the bodies of
     * the conditions the request names by id — neither of which the editor holds. Resolving them
     * server-side is what makes an unsaved render equal to the saved one; asking the client to send
     * them would put the render's inputs under the control of the thing being rendered.
     *
     * <p>Ids that name nothing live in this project are <strong>skipped</strong> rather than
     * refused. A render is a view, not a save: the write path already refuses an unknown condition
     * id with a field error, and answering 400 here would leave an author unable to <em>see</em> the
     * case they are being told is wrong.
     */
    @Transactional(readOnly = true)
    public Optional<String> renderGherkin(UUID userId, UUID projectId, CaseUpdateRequest request) {
        return projects.readVisible(userId, projectId, project -> {
            String featureName = request.suiteId() == null
                    ? null
                    : suites.findLiveInProject(project.getId(), request.suiteId())
                            .map(Suite::getName)
                            .orElse(null);

            Map<UUID, List<String>> library = bodiesById(request);
            return GherkinScenarios.renderInput(
                    featureName,
                    request,
                    bodies(request.preconditions(), library),
                    bodies(request.postconditions(), library));
        });
    }

    /**
     * The library bodies the request's two condition lists name, in one query for both lists.
     *
     * <p>One round trip whatever the case's size — the rule every read in this module follows, and
     * the one a render assembled condition by condition would break for a case with twenty of them.
     */
    private Map<UUID, List<String>> bodiesById(CaseUpdateRequest request) {
        Set<UUID> ids = new LinkedHashSet<>();
        collectIds(request.preconditions(), ids);
        collectIds(request.postconditions(), ids);
        if (ids.isEmpty()) {
            // Not merely an optimization: an empty collection bound into SQL `IN ()` is a syntax
            // error, and a case whose conditions are all inline is an ordinary case.
            return Map.of();
        }
        Map<UUID, List<String>> bodies = new HashMap<>();
        for (Condition condition : conditions.findLiveByIds(ids)) {
            bodies.put(condition.getId(), condition.getBody());
        }
        return bodies;
    }

    private static void collectIds(List<CaseConditionInput> attachments, Set<UUID> into) {
        if (attachments == null) {
            return;
        }
        for (CaseConditionInput attachment : attachments) {
            if (attachment != null && attachment.conditionId() != null) {
                into.add(attachment.conditionId());
            }
        }
    }

    /**
     * One list of attachments as bodies, in the author's order.
     *
     * <p>An inline body is its own answer; a library reference resolves through the map. An
     * attachment that is neither — no id and no body — contributes nothing, which is what an empty
     * row in the editor is.
     */
    private static List<List<String>> bodies(
            List<CaseConditionInput> attachments, Map<UUID, List<String>> library) {

        if (attachments == null) {
            return List.of();
        }
        List<List<String>> resolved = new ArrayList<>(attachments.size());
        for (CaseConditionInput attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            if (attachment.inlineBody() != null && !attachment.inlineBody().isEmpty()) {
                resolved.add(List.copyOf(attachment.inlineBody()));
            } else if (attachment.conditionId() != null) {
                List<String> body = library.get(attachment.conditionId());
                if (body != null) {
                    resolved.add(body);
                }
            }
        }
        return List.copyOf(resolved);
    }

    private TestCaseDetailView detail(
            UUID companyId, UUID userId, UUID projectId, TestCase row) {

        // Through CaseRenderer, which is the one assembler of a case's shape — this method WAS a
        // fourth byte-identical copy of it, and the oldest: CaseRenderer was extracted from the two
        // WRITE paths and the read it had been copied from was never moved onto it. That is the
        // wrong way round, since this is the render the writers exist to agree with.
        //
        // It stayed invisible because a duplicate that agrees is indistinguishable from a
        // delegation. It became visible the moment a field was added to the view, which is the only
        // moment it could ever have — and that is exactly the failure the class header describes:
        // one writer serving a field GET does not, so a case shows one thing on save and another on
        // reload with neither screen revealing which is stored.
        //
        // The vocabulary is still resolved through project's own service rather than its
        // repositories, for the reason CaseService gives — CaseRenderer#vocabularies does the same
        // call, so asking it there keeps this module free of a second reading of another module's
        // tables.
        return renderer.render(companyId, userId, projectId, row, suites.findLiveForProject(projectId));
    }
}
