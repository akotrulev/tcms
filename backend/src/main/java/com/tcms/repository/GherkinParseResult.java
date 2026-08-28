package com.tcms.repository;

import java.util.List;

/**
 * {@code GherkinParseResult} in {@code docs/specs/openapi.yaml} — pasted text, read as the fields
 * the case editor holds. <strong>Nothing here is persisted</strong>: the endpoint that serves it is
 * a pure function, and every value below is a suggestion the author still has to save.
 *
 * @param title the {@code Scenario:} name, or null when the text starts at a step — a bare step
 *              list is an accepted paste, and inventing a title for one would put a value in the
 *              editor's title field that nobody typed.
 * @param objective the free text under {@code Scenario:}, dedented, or null.
 * @param tags feature tags and scenario tags merged in that order, each without its {@code @}.
 *             Merged rather than kept apart because a case has one tag list: the file's two levels
 *             are a property of the file, and the distinction has nothing on this side to land on.
 * @param background <strong>one entry per Background step</strong>, in order (amendment AM1). Not
 *              one entry for the whole block: a Background is N preconditions in this product's
 *              model, and collapsing them into a single condition would make "reset the payment
 *              data" and "sign in as admin" one library entry that no other case can reuse half of.
 *              Empty when there is no Background. <strong>Nothing is created</strong> — the SPA
 *              offers each entry as a precondition to create and attach.
 * @param steps the scenario's steps, ids all null, {@code keyword} set on every row except a
 *              {@code *} step. A {@code # expected:} or {@code # data:} comment after a step fills
 *              that step's {@code expectedResult} or {@code testData}, which is what makes the
 *              render on the other side lossless.
 */
public record GherkinParseResult(
        String title,
        String objective,
        List<String> tags,
        List<ParsedBackground> background,
        List<CaseUpdateRequest.StepInput> steps) {

    /**
     * One Background step, shaped as the {@code ConditionInput} it would become.
     *
     * @param title the step line with its keyword — what the library entry is called.
     * @param body  the step line plus its block-argument lines. The first entry repeats
     *              {@link #title} on purpose: {@code Condition.body} is the ordered text a tester
     *              reads, and a body that omitted the step itself would show them only its
     *              attachments.
     */
    public record ParsedBackground(String title, List<String> body) {
    }
}
