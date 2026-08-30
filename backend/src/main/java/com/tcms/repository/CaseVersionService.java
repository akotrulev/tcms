package com.tcms.repository;

import com.tcms.api.UserRef;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /projects/{projectId}/cases/{caseId}/versions} — a case's authored history, and the
 * first reader {@code test_case_version} has ever had.
 *
 * <h2>What this endpoint is for, and what it is not</h2>
 *
 * <p>It answers <em>who changed this case, when, and why</em> — the question somebody asks when a
 * test that passed for six months starts failing. It is deliberately not
 * {@code /cases/{caseId}/history}, which is tagged {@code [repository, reporting]} and answers
 * <em>which runs executed which version</em>; that one needs the {@code execution} module and
 * cannot be built yet (handoff §28). Two questions, two endpoints, and conflating them would make
 * this one unbuildable for the same reason.
 *
 * <h2>The gate is the read gate, unchanged and borrowed</h2>
 *
 * <p>{@link ProjectService#readVisible} — company membership, then the explicit project grant,
 * asking {@code project:read}, which every {@code PROJECT}-scope role holds. That is correct
 * <strong>here</strong> and would be wrong for a write: a version list discloses exactly what the
 * case detail already discloses to the same caller (§23's shared gate, §28's argument for why a
 * write needs its own). Nothing in this class asks what a caller holds.
 *
 * <p>{@link TestCaseRepository#findLiveInProject} is the second gate, and the one a two-segment path
 * makes necessary: without its {@code projectId} predicate,
 * {@code /projects/{granted}/cases/{ungranted}/versions} would serve the change comments — free
 * text an author wrote about a case in a project the caller cannot open — and every cross-company
 * test in the suite would still pass, because the row genuinely is in the caller's company. Same
 * leak §27 documents on the detail endpoint, one level narrower and just as invisible.
 *
 * <h2>An empty list is a 200, and the distinction is load-bearing</h2>
 *
 * <p>Every case authored before the write path existed has <strong>no version rows at all</strong>
 * (handoff §28), and that is an ordinary state of a perfectly readable case — not a missing
 * resource. So the empty {@link Optional} means "you may not see this case" and answers 404, while
 * an empty <em>list</em> means "there is no recorded history" and answers {@code 200 []}. Collapsing
 * the two would 404 the version panel on most of the cases in an existing installation, and it would
 * read as the case having been deleted.
 *
 * <h2>The v1 gap is served, not filled</h2>
 *
 * <p>A case that predates the write path starts at v2 and its v1 is permanently absent; a case
 * created through {@code POST} starts at v1 (§29). This class serves whatever rows exist and
 * synthesizes nothing. Manufacturing a v1 from the current row would assert that the pre-edit
 * content was the post-edit content, in the one table whose purpose is that past claims stay true —
 * the same call {@link CaseVersions} records for the writer, and the reader must not undo it. The
 * SPA says "earliest recorded" when the oldest row it received is not v1, which is what makes the
 * gap legible rather than merely present.
 */
@Service
public class CaseVersionService {

    private final ProjectService projects;
    private final TestCaseRepository cases;
    private final TestCaseVersionRepository versions;
    private final CaseOwners people;

    public CaseVersionService(
            ProjectService projects,
            TestCaseRepository cases,
            TestCaseVersionRepository versions,
            CaseOwners people) {

        this.projects = projects;
        this.cases = cases;
        this.versions = versions;
        this.people = people;
    }

    /**
     * A case's versions newest first, or empty when the caller may not see the project or the case
     * is not in it.
     *
     * <p>Three queries, and none of them per row: the gate, the versions, and one batch resolution
     * of every distinct author. Resolving an author per version is the N+1 that looks harmless
     * because each query is fast and because a version list is usually short — right up to the case
     * somebody has been editing for a year.
     */
    @Transactional(readOnly = true)
    public Optional<List<EntityVersionView>> findForCase(UUID userId, UUID projectId, UUID caseId) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project -> project)
                .flatMap(project -> cases.findLiveInProject(project.getId(), caseId))
                .map(row -> render(companyId, versions.findForCase(row.getId())));
    }

    private List<EntityVersionView> render(UUID companyId, List<TestCaseVersion> rows) {
        Map<UUID, UserRef> authors = people.resolve(companyId, authorIds(rows));

        List<EntityVersionView> rendered = new ArrayList<>(rows.size());
        for (TestCaseVersion row : rows) {
            // A null author renders as an unattributed version rather than as an invented name:
            // created_by is nullable and has no FK to app_user (handoff §28, finding 1), so both
            // "nobody was recorded" and "the recorded id names no account" are states a real row is
            // in. Same call TestCaseSummaryView.owner() makes, for the same reason.
            //
            // This check USED to be load-bearing against a 500 as well: CaseOwners.resolve answered
            // Map.of() when there was nothing to resolve, and Map.of().get(null) throws NPE by
            // contract, so a case whose every version is unattributed — an imported one, the
            // commonest kind in an installation being migrated — took the whole panel down. That
            // trap is gone; resolve now answers a null-tolerant map whichever branch it takes, so
            // the map no longer behaves differently depending on who happens to be in the data.
            // The check stays because rendering an unattributed version is a decision, not a guard.
            rendered.add(EntityVersionView.of(
                    row, row.getCreatedBy() == null ? null : authors.get(row.getCreatedBy())));
        }
        return rendered;
    }

    /**
     * The distinct, non-null author ids.
     *
     * <p>A {@link LinkedHashSet} so a case whose every version has one author resolves one id rather
     * than fifty copies of it, and so the parameter list bound into {@code IN (…)} is stable across
     * calls — a set whose iteration order moves produces a different SQL statement each time and
     * defeats the driver's own statement cache.
     */
    private static Set<UUID> authorIds(List<TestCaseVersion> rows) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (TestCaseVersion row : rows) {
            if (row.getCreatedBy() != null) {
                ids.add(row.getCreatedBy());
            }
        }
        return ids;
    }
}
