package com.tcms.repository;

import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ProjectConfigurationService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The case as the API serves it — <strong>the one assembler of a case's shape</strong>.
 *
 * <h2>Why this is a component and not a private method</h2>
 *
 * <p>It was a private method, twice: {@link CaseCreateService} and {@link CaseUpdateService} each
 * carried a byte-identical copy, and each copy's javadoc argued against there being another one
 * ("not a third implementation", then "not a fourth"). {@link CaseBulkService} would have been the
 * third copy, which is the point at which the argument stops being a comment and becomes a class.
 *
 * <p>The failure it guards is specific and was already named at both call sites: a field rendered
 * differently by one writer shows one value on save and another on the reload that follows, and
 * neither screen reveals which one is stored. The version snapshot makes it worse — the snapshot and
 * the response are supposed to be one render, so two renderers means a case's recorded history says
 * something the response never said.
 *
 * <p>Nothing here is new behaviour. It is the two methods, moved, with their reasoning kept.
 */
@Component
class CaseRenderer {

    private final ProjectConfigurationService configuration;
    private final CaseSummaries summaries;
    private final CaseConditions conditions;
    private final CaseClassifications classifications;
    private final CaseAutomationTagRepository automationTags;
    private final TestStepRepository stepRows;
    private final CaseFlakeRepository flakes;
    private final CaseOwners people;

    CaseRenderer(
            ProjectConfigurationService configuration,
            CaseSummaries summaries,
            CaseConditions conditions,
            CaseClassifications classifications,
            CaseAutomationTagRepository automationTags,
            TestStepRepository stepRows,
            CaseFlakeRepository flakes,
            CaseOwners people) {

        this.configuration = configuration;
        this.summaries = summaries;
        this.conditions = conditions;
        this.classifications = classifications;
        this.automationTags = automationTags;
        this.stepRows = stepRows;
        this.flakes = flakes;
        this.people = people;
    }

    /**
     * One case, rendered exactly as {@code GET /cases/&#123;caseId&#125;} serves it.
     *
     * @param liveSuites the project's live suites, passed in because every caller already holds them
     *                   — re-reading them here would put a query inside a loop that runs once per
     *                   changed case
     */
    TestCaseDetailView render(
            UUID companyId, UUID userId, UUID projectId, TestCase row, List<Suite> liveSuites) {

        return render(row, liveSuites, vocabularies(userId, projectId), companyId);
    }

    /**
     * The same render with the project's vocabularies already resolved.
     *
     * <p>Split out for {@link CaseBulkService}, which renders up to 500 cases in one operation:
     * {@link #vocabularies} is a project-level read that does not vary per case, so calling the
     * method above in a loop would repeat it once per row. The two-step form is the reason the loop
     * is not quietly N+1.
     */
    TestCaseDetailView render(
            TestCase row,
            List<Suite> liveSuites,
            List<ClassificationDimensionView> vocabularies,
            UUID companyId) {

        return render(row, liveSuites, vocabularies, companyId, flakesFor(companyId, List.of(row)));
    }

    /**
     * Resolves the flake half for a whole batch of cases — <strong>two queries for any number of
     * rows, never two per row.</strong>
     *
     * <p>{@link CaseBulkService} renders up to 500 cases in one operation, and this is the third
     * lookup in this class that would otherwise be N+1 (the class header's two-step form exists for
     * the first two). {@link CaseFlakeRepository#findForCases} was written in §121 phase 1 with
     * <strong>no caller at all</strong> — the same consumerless shape as the {@code findQuarantined}
     * phase 3 deleted, and this is the consumer it was actually waiting for.
     *
     * <p>The second query names the deciders, and it runs <strong>only when some row in the batch is
     * quarantined</strong>: the id set is empty on the overwhelmingly common path, and
     * {@link CaseOwners#resolve} over an empty collection is not worth a round trip.
     */
    Map<UUID, CaseFlakeView> flakesFor(UUID companyId, List<TestCase> rows) {
        List<CaseFlake> found = flakes.findForCases(rows.stream().map(TestCase::getId).toList());
        if (found.isEmpty()) {
            return Map.of();
        }

        Set<UUID> deciders = found.stream()
                .map(CaseFlake::getQuarantinedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // A HashMap on BOTH arms, never Map.of() on the empty one — §38's trap, and it bites here
        // rather than in CaseOwners because the lookup below is keyed on a NULLABLE column.
        // Map.of().get(null) throws NPE by contract, so the short-circuit made every unquarantined
        // case a 500 in exactly the batches where nobody was quarantined: the common one. The batch
        // containing one quarantined case worked, which is why this reads as fine.
        Map<UUID, UserRefView> named = deciders.isEmpty()
                ? new HashMap<>()
                : people.resolve(companyId, deciders);

        // A HashMap, never Map.of() / toMap() — CaseOwners' own note applies verbatim: an immutable
        // map throws on get(null) by contract, so behaviour on an unresolvable decider would depend
        // on whether the batch happened to contain a resolvable one.
        Map<UUID, CaseFlakeView> byCase = new HashMap<>();
        for (CaseFlake flake : found) {
            byCase.put(flake.getCaseId(), CaseFlakeView.of(flake, named.get(flake.getQuarantinedBy())));
        }
        return byCase;
    }

    /**
     * The same render with the batch's flake rows already resolved — see {@link #flakesFor}.
     *
     * @param flakeViews keyed by case id; a case absent from the map has no {@code case_flake} row
     *                   and renders a null {@code flake}, which is the "never scored" state and not
     *                   the "scored, no evidence" one
     */
    TestCaseDetailView render(
            TestCase row,
            List<Suite> liveSuites,
            List<ClassificationDimensionView> vocabularies,
            UUID companyId,
            Map<UUID, CaseFlakeView> flakeViews) {

        TestCaseSummaryView summary =
                summaries.render(companyId, List.of(row), liveSuites, vocabularies).getFirst();

        return TestCaseDetailView.of(
                row,
                summary,
                automationTags.findForCase(row.getId()).stream()
                        .map(CaseAutomationTag::getTag)
                        .toList(),
                // Sorted, so the response is stable between two requests that changed nothing —
                // the set has no order of its own, and an unstable one would make the version
                // SNAPSHOT (this same render, serialized) differ from itself.
                classifications
                        .liveValueIdsOf(row.getId(), ClassificationVocabulary.of(vocabularies))
                        .stream()
                        .sorted()
                        .toList(),
                stepRows.findForCase(row.getId()).stream().map(TestStepView::of).toList(),
                conditions.forCase(row, liveSuites),
                flakeViews.get(row.getId()));
    }

    /** The project's classification dimensions, or none when the caller cannot read them. */
    List<ClassificationDimensionView> vocabularies(UUID userId, UUID projectId) {
        return configuration.findClassification(userId, projectId).orElse(List.of());
    }
}
