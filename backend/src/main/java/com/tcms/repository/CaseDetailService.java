package com.tcms.repository;

import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ProjectConfigurationService;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.List;
import java.util.Optional;
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
    private final ProjectConfigurationService configuration;
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseSummaries summaries;
    private final CaseConditions conditions;
    private final TestStepRepository steps;
    private final CaseAutomationTagRepository automationTags;

    public CaseDetailService(
            ProjectService projects,
            ProjectConfigurationService configuration,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseSummaries summaries,
            CaseConditions conditions,
            TestStepRepository steps,
            CaseAutomationTagRepository automationTags) {

        this.projects = projects;
        this.configuration = configuration;
        this.cases = cases;
        this.suites = suites;
        this.summaries = summaries;
        this.conditions = conditions;
        this.steps = steps;
        this.automationTags = automationTags;
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

    private TestCaseDetailView detail(
            UUID companyId, UUID userId, UUID projectId, TestCase row) {

        List<Suite> liveSuites = suites.findLiveForProject(projectId);
        // Through project's own service rather than its repositories, for the reason CaseService
        // gives: the vocabulary is that module's to shape, and asking it through the gate a second
        // time costs one permission evaluation and keeps this module free of a second reading of
        // another module's tables.
        List<ClassificationDimensionView> vocabularies =
                configuration.findClassification(userId, projectId).orElse(List.of());

        // A batch of one, through the same renderer the grid uses — see CaseSummaries for what
        // that buys and what a second implementation would cost. getFirst() is safe because the
        // renderer returns one view per case it was given, in order.
        TestCaseSummaryView summary =
                summaries.render(companyId, List.of(row), liveSuites, vocabularies).getFirst();

        return TestCaseDetailView.of(
                row,
                summary,
                automationTags.findForCase(row.getId()).stream()
                        .map(CaseAutomationTag::getTag)
                        .toList(),
                steps.findForCase(row.getId()).stream().map(TestStepView::of).toList(),
                conditions.forCase(row, liveSuites));
    }
}
