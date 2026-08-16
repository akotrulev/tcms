package com.tcms.execution;

import com.tcms.api.UserRef;
import com.tcms.execution.CaseFacts.PinnedCase;
import com.tcms.project.CaseStatusView;
import com.tcms.project.StatusKind;
import com.tcms.tenancy.CompanyContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Renders a batch of {@link TestExecution} rows into the wire shape — <strong>the one assembler</strong>.
 *
 * <p>Both the run detail and the single-execution endpoint go through here, so a checklist row and
 * the screen it opens cannot disagree about the same execution. That is the property
 * {@code repository.CaseSummaries} exists for one entity over, and it is why the extra fields the
 * detail needs are added <em>around</em> this output ({@link TestExecutionView#of}) rather than by a
 * second renderer.
 *
 * <h2><del>Four</del> Five bulk lookups, never per row</h2>
 *
 * <ol>
 *   <li>the pinned snapshots, one query for the whole run ({@link CaseFacts#pinnedCases});</li>
 *   <li>the people, two queries ({@link ExecutionPeople});</li>
 *   <li>the defect links, one query;</li>
 *   <li>the statuses, one query, done by the caller because it also needs them for the tally;</li>
 *   <li>the quarantined cases, one query ({@link CaseFacts#quarantinedCaseIds}, §237).</li>
 * </ol>
 *
 * <p>None is proportional to a run's size in queries, which matters here more than on any other
 * screen in the product: a run is the one list that routinely holds hundreds of rows.
 */
@Component
class ExecutionViews {

    private final CaseFacts cases;
    private final ExecutionPeople people;
    private final TestExecutionRepository executions;

    ExecutionViews(CaseFacts cases, ExecutionPeople people, TestExecutionRepository executions) {
        this.cases = cases;
        this.people = people;
        this.executions = executions;
    }

    /**
     * The rendered rows, <strong>ordered by the pinned case's key sequence</strong>.
     *
     * <p>Ordering here rather than in SQL because the key lives in the snapshot, which is the only
     * place this module may read it from. The cost is a sort over one run's rows in memory, which is
     * the same set already loaded; the benefit is that a tester who leaves a run half-done finds it
     * in the same order when they come back, and that the order is the one they saw in the case
     * grid.
     */
    List<TestExecutionSummaryView> render(
            UUID companyId, List<TestExecution> rows, Map<UUID, CaseStatusView> statuses) {

        if (rows.isEmpty()) {
            return List.of();
        }

        Map<UUID, PinnedCase> pinned = cases.pinnedCases(
                rows.stream().map(TestExecution::getCaseVersionId).collect(java.util.stream.Collectors.toSet()));

        Set<UUID> userIds = new HashSet<>();
        for (TestExecution row : rows) {
            if (row.getAssigneeId() != null) {
                userIds.add(row.getAssigneeId());
            }
            if (row.getExecutedBy() != null) {
                userIds.add(row.getExecutedBy());
            }
        }
        Map<UUID, UserRef> resolved = people.resolve(companyId, userIds);
        Map<UUID, List<DefectLinkView>> defects = defectsByExecution(
                rows.stream().map(TestExecution::getId).toList());
        // Keyed by CASE, not by execution — quarantine is a decision about the test, and a run that
        // holds the same case twice would badge both rows from this one set.
        Set<UUID> quarantined = cases.quarantinedCaseIds(
                rows.stream().map(TestExecution::getCaseId).collect(Collectors.toSet()));

        List<TestExecutionSummaryView> views = new ArrayList<>(rows.size());
        // Built alongside the views rather than looked up inside the comparator: a comparator that
        // resolves its own key turns one sort into O(n log n) map builds, which on the one list in
        // the product that routinely holds hundreds of rows is the difference nobody measures.
        Map<UUID, Long> keySeqByExecution = new HashMap<>(rows.size());
        for (TestExecution row : rows) {
            PinnedCase pin = pinned.get(row.getCaseVersionId());
            // An unresolvable pin sorts last rather than first: it is the exceptional row, and
            // putting it at the top of a checklist would displace the work.
            keySeqByExecution.put(row.getId(), pin == null ? Long.MAX_VALUE : pin.keySeq());
            views.add(new TestExecutionSummaryView(
                    row.getId(),
                    row.getCaseId(),
                    // An unresolvable pin is a row whose version the caller's company does not own,
                    // which RLS has already refused. Rendering blanks rather than throwing keeps one
                    // unreadable row from taking down the whole run's checklist.
                    pin == null ? "" : pin.caseKey(),
                    pin == null ? "" : pin.title(),
                    pin == null ? 0 : pin.versionNo(),
                    row.getStatusId() == null ? null : statuses.get(row.getStatusId()),
                    // The null checks are load-bearing rather than defensive: an unassigned,
                    // unexecuted execution is the ordinary state of a run nobody has started, and
                    // `resolve` answers an IMMUTABLE empty map when it was given no ids — which
                    // throws on a null key rather than returning null. Found by the first run of
                    // this module's own tests, which is what a fixture with an empty run is for.
                    row.getAssigneeId() == null ? null : resolved.get(row.getAssigneeId()),
                    row.getExecutedBy() == null ? null : resolved.get(row.getExecutedBy()),
                    row.getExecutedAt(),
                    row.getDurationMs(),
                    defects.getOrDefault(row.getId(), List.of()),
                    quarantined.contains(row.getCaseId())));
        }

        views.sort(Comparator.comparingLong(view -> keySeqByExecution.get(view.id())));
        return List.copyOf(views);
    }

    /**
     * The pinned steps of one execution, with the recorded results merged in.
     *
     * <p>Driven by the <strong>snapshot's</strong> step list, not by the {@code step_result} rows: a
     * step nobody has marked must still appear, or a tester opens a case and sees only the steps they
     * already did. The results are looked up into it, which also means a stored result for a step the
     * pinned version does not contain is silently absent from the response — the row is history that
     * no longer has anywhere to render, and inventing a step for it would put text on the screen that
     * the version never said.
     */
    List<StepResultView> steps(
            PinnedCase pin,
            Map<UUID, StepOutcome> recorded,
            Map<UUID, List<EvidenceView>> evidenceByStepResult) {

        if (pin == null) {
            return List.of();
        }
        List<StepResultView> steps = new ArrayList<>(pin.steps().size());
        for (CaseFacts.PinnedStep step : pin.steps()) {
            StepOutcome outcome = step.stepId() == null ? null : recorded.get(step.stepId());
            steps.add(new StepResultView(
                    step.stepId(),
                    step.position(),
                    step.keyword(),
                    step.action(),
                    step.testData(),
                    step.expectedResult(),
                    outcome == null ? null : outcome.getStatusId(),
                    outcome == null ? null : outcome.getComment(),
                    // Keyed by the step RESULT's id, not the step's: evidence.step_result_id is
                    // what the row carries, and a step with no recorded result has no result id
                    // and therefore cannot have evidence — which is the rule the write enforces.
                    outcome == null
                            ? List.of()
                            : evidenceByStepResult.getOrDefault(outcome.getId(), List.of())));
        }
        return List.copyOf(steps);
    }

    /**
     * The tally behind a run's result bar.
     *
     * <p>Reads {@link StatusKind}, never a status name, so a project that renamed its vocabulary
     * still counts (see {@link ResultStatuses}). {@code total} is the number of executions rather
     * than the sum of the buckets — they are equal by construction, and taking it from the row count
     * means a kind this method somehow failed to place shows up as a total that does not add up,
     * rather than vanishing.
     */
    static ResultCountsView tally(Map<UUID, Long> byStatusId, Map<UUID, CaseStatusView> statuses) {
        long passed = 0;
        long failed = 0;
        long blocked = 0;
        long skipped = 0;
        long notRun = 0;
        long total = 0;

        for (Map.Entry<UUID, Long> entry : byStatusId.entrySet()) {
            long count = entry.getValue();
            total += count;
            CaseStatusView status = entry.getKey() == null ? null : statuses.get(entry.getKey());
            switch (ResultStatuses.countable(status)) {
                case PASSED -> passed += count;
                case FAILED -> failed += count;
                case BLOCKED -> blocked += count;
                case SKIPPED -> skipped += count;
                // UNTESTED, RETEST and WIP all mean "still owed a verdict" — see ResultCountsView.
                case UNTESTED, RETEST, WIP -> notRun += count;
            }
        }
        return new ResultCountsView(passed, failed, blocked, skipped, notRun, total);
    }

    private Map<UUID, List<DefectLinkView>> defectsByExecution(List<UUID> executionIds) {
        Map<UUID, List<DefectLinkView>> byExecution = new HashMap<>();
        for (Object[] row : executions.findDefectLinks(CompanyContext.require(), executionIds)) {
            byExecution
                    .computeIfAbsent((UUID) row[1], id -> new ArrayList<>())
                    .add(new DefectLinkView(
                            (UUID) row[0],
                            (String) row[2],
                            (String) row[3],
                            (String) row[4],
                            (String) row[5]));
        }
        return byExecution;
    }

}
