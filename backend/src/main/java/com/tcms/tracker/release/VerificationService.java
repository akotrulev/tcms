package com.tcms.tracker.release;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.Issue;
import com.tcms.tracker.IssueLevel;
import com.tcms.tracker.IssueRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story H7 — <em>a story and the test cases that prove it</em>, in both directions.
 *
 * <p>This is the differentiator §4 names and it is nearly free: the product already owns cases,
 * suites, plans, runs and executions, so linking a story to its cases means the roadmap can answer
 * <em>is this done, and is it proven?</em> — the one question a tracker cannot answer without a
 * plugin, and the reason to build this rather than buy it. It costs one target type on a table
 * {@code V3} already created and left empty.
 *
 * <h2>{@code issue:edit}, and there is no {@code link:*} key</h2>
 *
 * <p>Linking a case to a story edits the story. A second key for an act an existing key already
 * covers is a permission somebody can hold without being able to use it — {@code LabelService}'s
 * D-e argument, one edge over — and adding one is a three-document edit plus the copies pinned by
 * {@code matrixCopies.test.ts}. Reading is {@code issue:read}.
 *
 * <h2>The reverse direction crosses no boundary at all</h2>
 *
 * <p>{@link #verificationsIn} answers {@code test-cases.html}'s new column from {@code issue_link}
 * and {@code issue}, <strong>both this module's tables</strong>, so it needs no {@link ReleaseFacts}
 * entry. The alternative — widening {@code TestCaseSummary} — would make {@code repository} read
 * this module's tables on the request path of the product's busiest list (plan 013 D-i).
 */
@Service
public class VerificationService {

    private static final String READ_ISSUE = "issue:read";
    private static final String EDIT_ISSUE = "issue:edit";

    /**
     * Key, title and level for the issues named by a set of links — this module's own {@code issue}
     * table, so a native read rather than a {@link ReleaseFacts} crossing.
     *
     * <p>{@code deleted_at IS NULL}: an issue's delete is soft (scope §3's mitigation for
     * {@code tracker} not being a foundation module), so without this a case would list a story that
     * is gone from every screen — a link naming work nobody can open.
     */
    private static final String ISSUE_REFS = """
            SELECT i.id, i.issue_key, i.title, t.level
              FROM issue      i
              JOIN issue_type t ON t.id = i.type_id
             WHERE i.id IN (:issueIds)
               AND i.deleted_at IS NULL
            """;

    private final ProjectService projects;
    private final IssueRepository issues;
    private final VerificationLinkRepository links;
    private final ReleaseFacts facts;
    private final EntityManager entities;

    VerificationService(
            ProjectService projects,
            IssueRepository issues,
            VerificationLinkRepository links,
            ReleaseFacts facts,
            EntityManager entities) {

        this.projects = projects;
        this.issues = issues;
        this.links = links;
        this.facts = facts;
        this.entities = entities;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /** Case ids this project does not have — named as a field, never as a 404 on the issue. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Replaced(List<IssueTestCaseView> views) implements Outcome {
        }
    }

    /** {@code GET /issues/{key}/test-cases}. */
    @Transactional(readOnly = true)
    public GateResult<List<IssueTestCaseView>> list(UUID userId, String key) {
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.readGranted(userId, issue.get().getProjectId(), READ_ISSUE, project ->
                linkedCasesOf(project.getId(), issue.get().getId()));
    }

    /** {@code PUT /issues/{key}/test-cases} — the whole set. */
    public GateResult<Outcome> replace(UUID userId, String key, IssueTestCasesInput input) {
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, issue.get().getProjectId(), EDIT_ISSUE,
                project -> applyReplace(project, key, input));
    }

    /** {@code GET /projects/{projectId}/case-verifications}. */
    @Transactional(readOnly = true)
    public GateResult<List<CaseVerificationView>> verificationsIn(UUID userId, UUID projectId) {
        return projects.readGranted(userId, projectId, READ_ISSUE, project -> {
            List<VerificationLink> all =
                    links.findInProject(project.getId(), VerificationLink.TEST_CASE);
            if (all.isEmpty()) {
                return List.of();
            }
            Map<UUID, IssueRefView> byIssue = issueRefsOf(
                    all.stream().map(VerificationLink::getIssueId).collect(
                            java.util.stream.Collectors.toCollection(LinkedHashSet::new)));

            Map<UUID, List<IssueRefView>> byCase = new LinkedHashMap<>();
            for (VerificationLink link : all) {
                IssueRefView ref = byIssue.get(link.getIssueId());
                if (ref != null) {
                    byCase.computeIfAbsent(link.getTargetId(), id -> new ArrayList<>()).add(ref);
                }
            }
            return byCase.entrySet().stream()
                    .map(entry -> new CaseVerificationView(entry.getKey(), List.copyOf(entry.getValue())))
                    .toList();
        });
    }

    // =====================================================================

    private Outcome applyReplace(Project project, String key, IssueTestCasesInput input) {
        Issue issue = issues.findByIssueKeyAndDeletedAtIsNull(key).orElseThrow();
        Set<UUID> wanted = new LinkedHashSet<>(
                input == null || input.caseIds() == null ? List.of() : input.caseIds());

        Map<UUID, ReleaseFacts.CaseRef> known = facts.casesOf(project.getId(), wanted);
        Set<UUID> foreign = new LinkedHashSet<>(wanted);
        foreign.removeAll(known.keySet());
        if (!foreign.isEmpty()) {
            // A field error and NOT a 404: the issue is reachable — the caller is looking at it —
            // and a 404 here would say their story had been deleted, which is both false and
            // alarming about the one thing a tracker must never seem to have done.
            return new Outcome.Invalid(Map.of("caseIds", List.of(
                    foreign.size() + " of those cases belong to another project, or have been "
                            + "deleted. Link only cases from this project.")));
        }

        // Delete-then-insert rather than a diff. The set is small, the table has no surrogate key
        // to update against, and a diff would need the same two statements plus a comparison to
        // decide which rows to leave alone.
        links.deleteAll(links.findByIssueIdAndTargetTypeOrderByTargetId(
                issue.getId(), VerificationLink.TEST_CASE));
        links.flush();
        for (UUID caseId : wanted) {
            links.save(VerificationLink.toCase(
                    CompanyContext.require(), issue.getId(), caseId));
        }
        return new Outcome.Replaced(linkedCasesOf(project.getId(), issue.getId()));
    }

    private List<IssueTestCaseView> linkedCasesOf(UUID projectId, UUID issueId) {
        List<UUID> caseIds = links
                .findByIssueIdAndTargetTypeOrderByTargetId(issueId, VerificationLink.TEST_CASE)
                .stream()
                .map(VerificationLink::getTargetId)
                .toList();

        Map<UUID, ReleaseFacts.CaseRef> cases = facts.casesOf(projectId, caseIds);
        Map<UUID, ReleaseFacts.LastResult> results = facts.lastResultsOf(cases.keySet());

        return cases.values().stream()
                .sorted(java.util.Comparator.comparing(ReleaseFacts.CaseRef::key))
                .map(known -> {
                    ReleaseFacts.LastResult last = results.get(known.id());
                    return new IssueTestCaseView(
                            known.id(), known.key(), known.title(),
                            last == null ? null : last.statusKind(),
                            last == null ? null : last.executedAt(),
                            last == null ? null : last.releaseVersion());
                })
                .toList();
    }

    private Map<UUID, IssueRefView> issueRefsOf(Set<UUID> issueIds) {
        Map<UUID, IssueRefView> refs = new LinkedHashMap<>();
        if (issueIds.isEmpty()) {
            return refs;
        }
        for (Object result : entities.createNativeQuery(ISSUE_REFS)
                .setParameter("issueIds", issueIds)
                .getResultList()) {

            Object[] row = (Object[]) result;
            refs.put(UUID.fromString(row[0].toString()), new IssueRefView(
                    (String) row[1], (String) row[2], IssueLevel.valueOf((String) row[3])));
        }
        return refs;
    }
}
