package com.tcms.execution;

import java.util.List;
import java.util.UUID;

/**
 * {@code StepResult} in {@code docs/specs/openapi.yaml} — one step of the pinned case version,
 * carrying whatever result has been recorded against it.
 *
 * <h2>The two halves come from different places and one of them is history</h2>
 *
 * <p>{@link #keyword}, {@link #action}, {@link #testData}, {@link #expectedResult},
 * {@link #docString} and {@link #dataTable} are the
 * <strong>pinned version's</strong> text, out of the snapshot. {@link #statusId} and
 * {@link #comment} are the live {@code step_result} row. Merging them into one shape is the
 * contract's decision and it is the right one: the alternative is two arrays the client joins by
 * {@code stepId}, and a client that gets that join wrong shows a tester the wrong instructions
 * beside the right checkbox.
 *
 * @param stepId the {@code test_step} id, stable across reorders precisely so evidence and results
 *               stay attached to the step they were recorded for (plan §6m). It is what a PATCH
 *               names, which is why it is on the wire rather than the position.
 * @param docString Gherkin's block argument, pinned. Null in a snapshot written before §255 — that
 *               version did not record one.
 * @param dataTable the pinned table as rows of cells, on {@link #docString}'s terms. This is what
 *               a tester executing a Gherkin case actually reads, and it is why §255 carried the
 *               two fields all the way through the snapshot rather than stopping at the editor.
 */
public record StepResultView(
        UUID stepId,
        int position,
        String keyword,
        String action,
        String testData,
        String expectedResult,
        String docString,
        List<List<String>> dataTable,
        UUID statusId,
        String comment,
        List<EvidenceView> evidence) {
}
