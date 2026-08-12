package com.tcms.repository;

import com.tcms.api.UserRef;
import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ProjectConfigurationService;
import com.tcms.storage.Attachment;
import com.tcms.storage.AttachmentOwner;
import com.tcms.storage.AttachmentRepository;
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
    private final AttachmentRepository attachments;
    private final CaseDocLinkRepository docLinks;
    private final CaseOwners people;

    CaseRenderer(
            ProjectConfigurationService configuration,
            CaseSummaries summaries,
            CaseConditions conditions,
            CaseClassifications classifications,
            CaseAutomationTagRepository automationTags,
            TestStepRepository stepRows,
            CaseFlakeRepository flakes,
            AttachmentRepository attachments,
            CaseDocLinkRepository docLinks,
            CaseOwners people) {

        this.configuration = configuration;
        this.summaries = summaries;
        this.conditions = conditions;
        this.classifications = classifications;
        this.automationTags = automationTags;
        this.stepRows = stepRows;
        this.flakes = flakes;
        this.attachments = attachments;
        this.docLinks = docLinks;
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

        return render(row, liveSuites, vocabularies, companyId,
                flakesFor(companyId, List.of(row)), attachmentsFor(companyId, List.of(row)),
                docLinksFor(List.of(row)));
    }

    /**
     * Resolves the documentation links for a whole batch of cases — <strong>one query for any
     * number of rows</strong>, on the same terms as {@link #flakesFor} and {@link #attachmentsFor}
     * and for the same caller.
     *
     * <p>One query rather than their two: there is nobody to name. A link's author is not on the
     * wire — {@code DocLink} has no {@code createdBy} property, and the doc page is where a link's
     * provenance is read — so the second lookup those two make has nothing to fetch.
     *
     * <p>Reads {@code case_doc_link}, the read model, and <strong>never {@code doc_link}</strong>:
     * this runs on a request path and {@code documentation} is a module this one may not name there.
     * {@code DocumentationFacts} is the seam that fills it, on the
     * {@code documentation.links_changed} event and hourly.
     *
     * <p>A case absent from the map has no links, and a case with no links renders an empty list
     * rather than a null one — the {@code attachments} call one method up, not the {@code flake} one:
     * there is no third state to distinguish here, because "nobody has looked" is not something this
     * table can say (V46 §5).
     */
    Map<UUID, List<DocLinkView>> docLinksFor(List<TestCase> rows) {
        List<CaseDocLink> found = docLinks.findForCases(rows.stream().map(TestCase::getId).toList());
        if (found.isEmpty()) {
            return Map.of();
        }

        // A HashMap, keyed on a NOT NULL column, so §38's get(null) trap is not in play here — kept
        // as a HashMap anyway because the values are built by accumulation and the query's order is
        // load-bearing (CaseDocLinkRepository#findForCases says why it has one).
        Map<UUID, List<DocLinkView>> byCase = new HashMap<>();
        for (CaseDocLink link : found) {
            byCase.computeIfAbsent(link.getCaseId(), ignored -> new java.util.ArrayList<>())
                    .add(DocLinkView.of(link));
        }
        return byCase;
    }

    /**
     * Resolves the attached files for a whole batch of cases — <strong>two queries for any number
     * of rows</strong>, on the same terms as {@link #flakesFor} and for the same caller.
     *
     * <p>The second query names the uploaders and runs only when something is attached, which on the
     * bulk path is almost never: {@link CaseOwners#resolve} over an empty collection is not worth a
     * round trip. {@code created_by} is <strong>nullable</strong>, so the map is a {@link HashMap} on
     * both arms — §38's trap, which §121 phase 4 shipped a 500 for by copying the words of that
     * comment and not its behaviour. The same nullable column is being keyed on here.
     */
    Map<UUID, List<CaseAttachmentView>> attachmentsFor(UUID companyId, List<TestCase> rows) {
        List<Attachment> found = attachments.findOwnedBy(
                AttachmentOwner.CASE, rows.stream().map(TestCase::getId).toList());
        if (found.isEmpty()) {
            return Map.of();
        }

        Set<UUID> uploaders = found.stream()
                .map(Attachment::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UserRef> named = uploaders.isEmpty()
                ? new HashMap<>()
                : people.resolve(companyId, uploaders);

        // Keyed on the case, and each list keeps the query's created_at order — the panel reads as
        // a history of what somebody added, and re-sorting by filename here would make the newest
        // upload land in the middle of the list it was just appended to.
        Map<UUID, List<CaseAttachmentView>> byCase = new HashMap<>();
        for (Attachment row : found) {
            byCase.computeIfAbsent(row.getOwnerId(), ignored -> new java.util.ArrayList<>());
        }
        for (Attachment row : found) {
            byCase.get(row.getOwnerId()).add(CaseAttachmentView.of(
                    projectOf(rows, row.getOwnerId()), row.getOwnerId(), row,
                    named.get(row.getCreatedBy())));
        }
        return byCase;
    }

    /**
     * The project the case in this batch belongs to — needed only to build the download URL.
     *
     * <p>Taken from the batch rather than from a second read: every row here was loaded by a caller
     * that had already resolved the project, and {@code TestCase.projectId} is on the row.
     */
    private static UUID projectOf(List<TestCase> rows, UUID caseId) {
        return rows.stream()
                .filter(row -> row.getId().equals(caseId))
                .findFirst()
                .map(TestCase::getProjectId)
                .orElseThrow(() -> new IllegalStateException(
                        "attachment for a case that is not in the batch that asked for it: "
                                + caseId));
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
        Map<UUID, UserRef> named = deciders.isEmpty()
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
            Map<UUID, CaseFlakeView> flakeViews,
            Map<UUID, List<CaseAttachmentView>> attachmentViews,
            Map<UUID, List<DocLinkView>> docLinkViews) {

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
                flakeViews.get(row.getId()),
                // Never null, unlike `flake` one line up, and the difference is what the absence
                // means: no case_flake row is a THIRD state ("nobody has looked"), while no
                // attachment row is simply an empty list. A nullable list would make the SPA
                // distinguish two spellings of nothing.
                attachmentViews.getOrDefault(row.getId(), List.of()),
                // Never null, for the reason directly above and one more of its own: the empty list
                // is what the SPA has always rendered here — `data.relatedDocuments ?? []` — so the
                // panel that has said "Not linked to any specification" about every case in the
                // product since §93 keeps saying it, and now it is true when it says it.
                docLinkViews.getOrDefault(row.getId(), List.of()));
    }

    /** The project's classification dimensions, or none when the caller cannot read them. */
    List<ClassificationDimensionView> vocabularies(UUID userId, UUID projectId) {
        return configuration.findClassification(userId, projectId).orElse(List.of());
    }
}
