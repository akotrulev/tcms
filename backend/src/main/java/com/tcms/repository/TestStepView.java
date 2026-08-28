package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * {@code TestStep} in {@code docs/specs/openapi.yaml} — one step of one case.
 *
 * @param id       the step's own id, and <strong>stable</strong>: {@code step_result} anchors
 *                 evidence here, so a client keying its list items on this keeps a screenshot
 *                 attached to the step it was taken for across any reorder (plan §6m). The one
 *                 field on this shape it would be actively harmful to derive from the position.
 * @param position 1-based in the response and 0-based nowhere — the contract's field is what the
 *                 execution screen numbers its checklist from, and the stored value is whatever
 *                 the author's ordering produced. Passed through rather than renumbered: a step
 *                 whose displayed number disagrees with the {@code position} a future PATCH sends
 *                 back is a reorder that silently moves the wrong step.
 * @param keyword  <strong>null for a structured step</strong>, and that is the format rather than a
 *                 missing value — see {@link StepKeyword}.
 * @param testData nullable. Separate from {@link #action} because a step's data is what varies
 *                 between otherwise identical runs, and merging it into the prose is what makes a
 *                 case impossible to parameterize later.
 * @param docString Gherkin's {@code """} block, nullable. <strong>At most one of
 *                 {@link #testData}, this and {@link #dataTable} is non-null</strong> — one step,
 *                 one data spelling.
 * @param dataTable Gherkin's {@code |}-table as rows of cells, nullable. Rows may be ragged: the
 *                 contract does not require a rectangle, so a reader that indexes blindly across
 *                 rows is the thing that breaks.
 *
 *                 <p>This record IS the version snapshot's step shape — {@code CaseVersions}
 *                 serializes {@link TestCaseDetailView} — so these two fields land in every
 *                 version written from now on and reach the execution screen through
 *                 {@code CaseFacts.PinnedStep}. A snapshot written before §255 has neither key and
 *                 reads as null, which is correct: that version did not record one.
 */
public record TestStepView(
        UUID id,
        int position,
        StepKeyword keyword,
        String action,
        String testData,
        String expectedResult,
        String docString,
        List<List<String>> dataTable) {

    static TestStepView of(TestStep step) {
        return new TestStepView(
                step.getId(),
                step.getPosition(),
                step.getKeyword(),
                step.getAction(),
                step.getTestData(),
                step.getExpectedResult(),
                step.getDocstring(),
                step.getDatatable());
    }
}
