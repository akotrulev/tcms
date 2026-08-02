package com.tcms.repository;

import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
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
    private final TestCaseRepository cases;
    private final SuiteRepository suites;
    private final CaseRenderer renderer;

    public CaseDetailService(
            ProjectService projects,
            TestCaseRepository cases,
            SuiteRepository suites,
            CaseRenderer renderer) {

        this.projects = projects;
        this.cases = cases;
        this.suites = suites;
        this.renderer = renderer;
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
