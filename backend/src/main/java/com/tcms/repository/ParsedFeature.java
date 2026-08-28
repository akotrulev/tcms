package com.tcms.repository;

import java.util.List;

/**
 * One whole {@code .feature} file, as the import reads it — {@link GherkinScenarios#parseFeature}'s
 * answer.
 *
 * <h2>Why this is not {@link GherkinParseResult}</h2>
 *
 * <p>{@code GherkinParseResult} is the <em>paste</em> shape: exactly one scenario, because that is
 * what the case editor holds. A file holds many, each with its own tags, its own objective and its
 * own answer to "does this parse", and the import has to report a row for each — including for the
 * ones it refuses. Reusing the paste shape would have meant either calling the parser once per
 * scenario (a second pass over the same text, with the file's {@code Background:} re-read every
 * time) or folding the refusals into an exception, which is exactly what the import must NOT do:
 * one {@code Scenario Outline} in a folder of forty files refuses that scenario, not the request.
 *
 * <p>Both shapes come out of the one parser. There is no second one — plan §6a rule 3, and
 * {@link GherkinScenarios}' header.
 *
 * @param name      the {@code Feature:} name, or null when the file names none. Not refused here:
 *                  the import turns it into a whole-file {@code REFUSE} row reading
 *                  {@code "Feature has no name"} ({@code api-surface.md} §5.4), because a
 *                  parser exception would have to carry a fifth {@code gherkin-*} type onto the
 *                  paste endpoint's contract for a condition the paste endpoint cannot reach.
 * @param scenarios every {@code Scenario:} in the file, in file order, INCLUDING the ones that are
 *                  refused — see {@link ParsedScenario#refusal()}.
 */
record ParsedFeature(String name, List<ParsedScenario> scenarios) {

    /**
     * One scenario, with everything the import needs to plan a row for it.
     *
     * @param name       the {@code Scenario:} name. Null only for a nameless scenario, which cannot
     *                   be keyed and is refused by the caller.
     * @param line       1-based line of the {@code Scenario:} keyword — {@code test_case
     *                   .imported_line}, and the {@code line} on the plan's row.
     * @param objective  the scenario's description, dedented; prefixed with
     *                   {@code "Rule: <name>\n<rule description>\n\n"} when the scenario sits under
     *                   a {@code Rule:}, because a rule is context the scenario was written under
     *                   and this product has no other place to put it.
     * @param tags       feature ∪ rule ∪ scenario tags, insertion-ordered and de-duplicated,
     *                   without the {@code @}. The rule level is included because Gherkin applies a
     *                   {@code Rule:}'s tags to every scenario inside it; dropping them would lose
     *                   a tag the file plainly states.
     * @param background the feature's {@code Background:} steps followed by the enclosing
     *                   {@code Rule:}'s, one entry per STEP (amendment AM1) and in that order —
     *                   which is the order the library conditions attach in.
     * @param steps      the procedure, already in the shape the case write takes.
     * @param source     the scenario's text verbatim, from its {@code Scenario:} line to the last
     *                   line before the next child of the file, trailing blank lines removed. This
     *                   is {@code test_case.imported_source} and its ONLY consumer is the
     *                   re-import's equality test, so what matters about the definition is that it
     *                   is deterministic rather than that it is minimal. Tags are NOT included:
     *                   they sit above the {@code Scenario:} line and are compared through the
     *                   case's own tags instead.
     * @param refusal    null when the scenario is importable; otherwise the fixed sentence the plan
     *                   row carries — today only {@code "Scenario Outline is not supported yet"}.
     */
    record ParsedScenario(
            String name,
            int line,
            String objective,
            List<String> tags,
            List<GherkinParseResult.ParsedBackground> background,
            List<CaseUpdateRequest.StepInput> steps,
            String source,
            String refusal) {
    }
}
