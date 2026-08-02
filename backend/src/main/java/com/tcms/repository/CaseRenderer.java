package com.tcms.repository;

import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ProjectConfigurationService;
import java.util.List;
import java.util.UUID;
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

    CaseRenderer(
            ProjectConfigurationService configuration,
            CaseSummaries summaries,
            CaseConditions conditions,
            CaseClassifications classifications,
            CaseAutomationTagRepository automationTags,
            TestStepRepository stepRows) {

        this.configuration = configuration;
        this.summaries = summaries;
        this.conditions = conditions;
        this.classifications = classifications;
        this.automationTags = automationTags;
        this.stepRows = stepRows;
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
                conditions.forCase(row, liveSuites));
    }

    /** The project's classification dimensions, or none when the caller cannot read them. */
    List<ClassificationDimensionView> vocabularies(UUID userId, UUID projectId) {
        return configuration.findClassification(userId, projectId).orElse(List.of());
    }
}
