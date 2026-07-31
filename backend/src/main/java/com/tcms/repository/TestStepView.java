package com.tcms.repository;

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
 * @param sharedStepId nullable, and served unresolved: the shape has the id and no room for the
 *                 library step's title, and the shared-step library endpoint is not built. A
 *                 marker for a future consumer, not a lookup this endpoint owes — see
 *                 {@link TestStep#getSharedStepId()}.
 */
public record TestStepView(
        UUID id,
        int position,
        StepKeyword keyword,
        String action,
        String testData,
        String expectedResult,
        UUID sharedStepId) {

    static TestStepView of(TestStep step) {
        return new TestStepView(
                step.getId(),
                step.getPosition(),
                step.getKeyword(),
                step.getAction(),
                step.getTestData(),
                step.getExpectedResult(),
                step.getSharedStepId());
    }
}
