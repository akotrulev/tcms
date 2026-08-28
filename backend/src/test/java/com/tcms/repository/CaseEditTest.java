package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CaseEdit#validate} as a <strong>pure function</strong> — the §255 lens rules, in process.
 *
 * <h2>No Spring context, and that is what this class is for</h2>
 *
 * <p>Every other test of these rules is an endpoint test that boots Postgres through
 * Testcontainers, and each one costs a request to assert one sentence. The rules are cross-field
 * arithmetic over a payload, so the arms that enumerate them belong where enumerating is cheap:
 * {@code CaseUpdateEndpointTest} keeps the arms that prove the sentences reach the WIRE keyed on
 * {@code steps}, and this class proves the arithmetic itself.
 *
 * <p><strong>The four rules are blueprint DEC-2's.</strong> R1 and R2 are the {@code GHERKIN}
 * lens's; R3 holds in either; R4 is the procedure rule, whose {@code GHERKIN} branch §255 deleted
 * because there is no longer a second spelling of a procedure to require instead.
 *
 * <h2>Errors are collected, never short-circuited</h2>
 *
 * <p>A scenario arrives by paste, so ten keyword-less rows must produce ten sentences in one
 * answer. A validator that returned on the first fault would make fixing a pasted scenario one
 * round trip per row, and the arms below assert the count rather than merely the presence.
 */
@DisplayName("CaseEdit.validate — the lens rules (§255, blueprint DEC-2)")
class CaseEditTest {

    private static final UUID SUITE = UUID.fromString("ce111000-0000-4000-8000-000000000001");
    private static final UUID STEP_ONE = UUID.fromString("ce112000-0000-4000-8000-000000000001");

    @Nested
    @DisplayName("R1 — every step in the GHERKIN lens carries a keyword")
    class EveryGherkinStepHasAKeyword {

        @Test
        @DisplayName("a keyword-less step names its number")
        void aKeywordlessStepIsRefused() {
            Map<String, List<String>> errors = errorsOf(gherkin(
                    step(null, "GIVEN", "a captured payment"),
                    step(null, null, "I refund it")));

            assertThat(errors.get("steps")).containsExactly("Step 2 needs a keyword.");
        }

        @Test
        @DisplayName("the same payload in the STRUCTURED lens is accepted — it is a LENS rule")
        void aStructuredStepNeedsNoKeyword() {
            // The bound that makes R1 a rule about the lens rather than about steps. Without this
            // arm, an implementation that required a keyword everywhere passes the arm above and
            // makes every structured case in the product unsavable.
            assertThat(errorsOf(structured(
                    step(null, null, "Open the refund screen"),
                    step(null, null, "Submit"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("an unrecognized keyword produces ONE sentence, not two")
        void aBadKeywordIsNotAlsoAMissingOne() {
            // The guard is on the RAW value: a step that sent "Givne" already has its own
            // sentence, and adding "needs a keyword" beside it tells the author they sent nothing
            // when they sent something wrong. Two messages, one fault, and the second is false.
            Map<String, List<String>> errors =
                    errorsOf(gherkin(step(null, "Givne", "a captured payment")));

            assertThat(errors.get("steps"))
                    .containsExactly("Step 1 has an unrecognized keyword.");
        }

        @Test
        @DisplayName("every broken row is reported, not the first")
        void allTheFaultsCollect() {
            Map<String, List<String>> errors = errorsOf(gherkin(
                    step(null, null, "a captured payment"),
                    step(null, null, "I refund it"),
                    step(null, null, "it is refunded")));

            assertThat(errors.get("steps")).containsExactly(
                    "Step 1 needs a keyword.",
                    "Step 2 needs a keyword.",
                    "Step 3 needs a keyword.");
        }
    }

    @Nested
    @DisplayName("R2 — the first step cannot be And/But")
    class TheFirstStepCannotContinueNothing {

        @Test
        @DisplayName("a leading AND is refused")
        void aLeadingAndIsRefused() {
            assertThat(errorsOf(gherkin(
                    step(null, "AND", "a captured payment"),
                    step(null, "THEN", "it is refunded"))).get("steps"))
                    .containsExactly("The first step cannot be And/But.");
        }

        @Test
        @DisplayName("a leading BUT is refused too")
        void aLeadingButIsRefused() {
            assertThat(errorsOf(gherkin(step(null, "BUT", "nothing happens"))).get("steps"))
                    .containsExactly("The first step cannot be And/But.");
        }

        @Test
        @DisplayName("AND anywhere else is ordinary")
        void aLaterAndIsFine() {
            assertThat(errorsOf(gherkin(
                    step(null, "GIVEN", "a captured payment"),
                    step(null, "AND", "an open refund window"),
                    step(null, "THEN", "it is refunded"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("a leading AND in the STRUCTURED lens is not R2's business")
        void theStructuredLensIsUntouched() {
            Map<String, Object> leading = step(null, "AND", "a captured payment");
            assertThat(errorsOf(structured(leading))).isEmpty();
        }
    }

    @Nested
    @DisplayName("R3 — one data spelling per step, in EITHER lens")
    class OneDataSpellingPerStep {

        @Test
        @DisplayName("test data and a doc string together are refused")
        void twoSpellingsAreRefused() {
            Map<String, Object> step = step(null, null, "Enter the amount");
            step.put("testData", "100.00");
            step.put("docString", "the whole captured amount");

            assertThat(errorsOf(structured(step)).get("steps"))
                    .containsExactly("Step 1 has more than one data field — keep test data, a "
                            + "doc string, or a table.");
        }

        @Test
        @DisplayName("a doc string and a table together are refused")
        void theOtherPairIsRefusedToo() {
            Map<String, Object> step = step(null, "GIVEN", "a request");
            step.put("docString", "{}");
            step.put("dataTable", List.of(List.of("a")));

            assertThat(errorsOf(gherkin(step)).get("steps")).hasSize(1);
        }

        @Test
        @DisplayName("exactly one is fine, and so is none")
        void oneOrNoneIsFine() {
            Map<String, Object> withTable = step(null, null, "Enter these");
            withTable.put("dataTable", List.of(List.of("id", "amount"), List.of("p-1", "100")));

            assertThat(errorsOf(structured(withTable, step(null, null, "Submit")))).isEmpty();
        }

        @Test
        @DisplayName("an EMPTY table is not a table — it collapses to null rather than counting")
        void anEmptyTableIsNotASpelling() {
            // `[]` renders as a grid with no rows the editor offers no way to remove, so the two
            // spellings of "no table" are collapsed. If it counted as a spelling, a step with test
            // data and a `dataTable: []` — which is what a form submits for a mode nobody chose —
            // would be refused for a table the author never wrote.
            Map<String, Object> step = step(null, null, "Enter the amount");
            step.put("testData", "100.00");
            step.put("dataTable", List.of());

            assertThat(errorsOf(structured(step))).isEmpty();
        }

        @Test
        @DisplayName("blank text is absent text — a whitespace doc string is not a second spelling")
        void blankIsAbsent() {
            Map<String, Object> step = step(null, null, "Enter the amount");
            step.put("testData", "100.00");
            step.put("docString", "   ");

            assertThat(errorsOf(structured(step))).isEmpty();
        }

        @Test
        @DisplayName("a cell over 4 KiB is refused rather than truncated")
        void anOverlongCellIsRefused() {
            // Truncation would store a step whose data stops mid-value and reads as authored
            // content — the same argument the tag length check makes one field over.
            Map<String, Object> step = step(null, null, "Enter these");
            step.put("dataTable", List.of(List.of("x".repeat(4 * 1024 + 1))));

            assertThat(errorsOf(structured(step)).get("steps")).hasSize(1);
        }

        @Test
        @DisplayName("a table over 200 rows is refused")
        void anOverlongTableIsRefused() {
            List<List<String>> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 201; i++) {
                rows.add(List.of("row " + i));
            }
            Map<String, Object> step = step(null, null, "Enter these");
            step.put("dataTable", rows);

            assertThat(errorsOf(structured(step)).get("steps")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("R4 — the procedure rule, with its GHERKIN branch deleted")
    class TheProcedureRule {

        @Test
        @DisplayName("REQUIRED and no steps is one sentence that no longer offers a second lens")
        void anEmptyProcedureIsRefused() {
            // ~~"Add at least one step, or switch to Gherkin."~~ → §255: switching lenses is not a
            // way to avoid writing a procedure any more, because both lenses store steps. A
            // message offering it would send the author to a toggle that changes nothing.
            assertThat(errorsOf(CaseEdit.ProcedureRule.REQUIRED, payload("STRUCTURED", List.of()))
                    .get("steps"))
                    .containsExactly("Add at least one step.");
        }

        @Test
        @DisplayName("the GHERKIN lens gets the same sentence, keyed on the same field")
        void theRuleDoesNotDifferByLens() {
            assertThat(errorsOf(CaseEdit.ProcedureRule.REQUIRED, payload("GHERKIN", List.of()))
                    .get("steps"))
                    .containsExactly("Add at least one step.");
        }

        @Test
        @DisplayName("DEFERRED accepts an empty procedure in either lens")
        void createStillDefers() {
            assertThat(errorsOf(CaseEdit.ProcedureRule.DEFERRED, payload("GHERKIN", List.of())))
                    .isEmpty();
            assertThat(errorsOf(CaseEdit.ProcedureRule.DEFERRED, payload("STRUCTURED", List.of())))
                    .isEmpty();
        }

        @Test
        @DisplayName("a rejected step does not ALSO report an empty procedure")
        void oneFaultIsOneMessage() {
            // §64's pair: normalizeSteps drops a step it has complained about, so a payload whose
            // only step was refused arrives at the procedure check with an empty list — and the
            // author is told to add a step they demonstrably just added, underneath the sentence
            // explaining why the one they added was refused.
            assertThat(errorsOf(CaseEdit.ProcedureRule.REQUIRED,
                    payload("STRUCTURED", List.of(step(null, null, "   ")))).get("steps"))
                    .containsExactly("Describe the action for step 1.");
        }
    }

    @Nested
    @DisplayName("What the valid edit carries")
    class TheEditItself {

        @Test
        @DisplayName("the GHERKIN lens KEEPS its steps — the §255 inversion")
        void gherkinKeepsItsSteps() {
            // Before §255 this arm asserted List.of(): the validator cleared the steps of a
            // Gherkin case, so a save answered 200 having discarded the rows the author had just
            // written, and took their ids — and therefore every step_result — with them.
            Map<String, Object> given = step(STEP_ONE, "GIVEN", "a captured payment");
            given.put("docString", "settled at 10.01 EUR");

            CaseEdit edit = validOf(gherkin(given, step(null, "THEN", "it is refunded")));

            assertThat(edit.steps()).hasSize(2);
            assertThat(edit.steps().getFirst().keyword()).isEqualTo(StepKeyword.GIVEN);
            assertThat(edit.steps().getFirst().docString()).isEqualTo("settled at 10.01 EUR");
            assertThat(edit.steps().getFirst().id()).isEqualTo(STEP_ONE);
        }

        @Test
        @DisplayName("a table's cells are NOT trimmed, unlike every other text field here")
        void cellsKeepTheirWhitespace() {
            // Leading whitespace inside a |-cell is escaped content in the Gherkin spec, so
            // trimming it makes the render of a parsed table differ from the file it came from —
            // which is the round-trip property this initiative exists to establish.
            Map<String, Object> step = step(null, null, "Enter these");
            step.put("dataTable", List.of(List.of("  padded  ")));

            CaseEdit edit = validOf(structured(step));

            assertThat(edit.steps().getFirst().dataTable())
                    .containsExactly(List.of("  padded  "));
        }

        @Test
        @DisplayName("a null cell becomes an empty string — a row has no holes")
        void nullCellsAreEmptyStrings() {
            List<String> withNull = new java.util.ArrayList<>();
            withNull.add("a");
            withNull.add(null);
            Map<String, Object> step = step(null, null, "Enter these");
            step.put("dataTable", List.of(withNull));

            assertThat(validOf(structured(step)).steps().getFirst().dataTable())
                    .containsExactly(List.of("a", ""));
        }
    }

    // =====================================================================
    // helpers — a payload, and the validation of one
    // =====================================================================

    private static Map<String, Object> step(UUID id, String keyword, String action) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("keyword", keyword);
        step.put("action", action);
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("docString", null);
        step.put("dataTable", null);
        return step;
    }

    /**
     * The two lens doors. Each copies its varargs array into a list <em>in place</em>.
     *
     * <p>The loop is not style, and factoring it into a shared helper is what does not compile:
     * {@code @SafeVarargs} promises the array does not ESCAPE, so javac warns wherever one is
     * handed to another method at all — {@code List.of(steps)}, {@code Arrays.asList(steps)},
     * {@code Arrays.stream(steps)} and a private {@code asRows(steps)} alike — and this module
     * compiles with {@code -Werror}. Reading the array in place does not.
     */
    @SafeVarargs
    private static CaseUpdateRequest gherkin(Map<String, Object>... steps) {
        List<Map<String, Object>> rows = new ArrayList<>(steps.length);
        for (Map<String, Object> step : steps) {
            rows.add(step);
        }
        return payload("GHERKIN", rows);
    }

    @SafeVarargs
    private static CaseUpdateRequest structured(Map<String, Object>... steps) {
        List<Map<String, Object>> rows = new ArrayList<>(steps.length);
        for (Map<String, Object> step : steps) {
            rows.add(step);
        }
        return payload("STRUCTURED", rows);
    }

    /**
     * The request, built from loose maps so a step's fields can be overridden one at a time.
     *
     * <p>Everything outside {@code steps} is a fixed valid value: this class is about the step
     * rules, and a payload that failed for a missing title would make every arm here pass for the
     * wrong reason.
     */
    @SuppressWarnings("unchecked")
    private static CaseUpdateRequest payload(String lens, List<Map<String, Object>> steps) {
        List<CaseUpdateRequest.StepInput> inputs = steps.stream()
                .map(step -> new CaseUpdateRequest.StepInput(
                        (UUID) step.get("id"),
                        (String) step.get("keyword"),
                        (String) step.get("action"),
                        (String) step.get("testData"),
                        (String) step.get("expectedResult"),
                        (String) step.get("docString"),
                        (List<List<String>>) step.get("dataTable")))
                .toList();

        return new CaseUpdateRequest(
                "Refund the whole captured amount",
                SUITE,
                null, null, null,
                StepFormat.valueOf(lens),
                Priority.HIGH,
                null,
                AutomationStatus.MANUAL,
                null, null, null,
                inputs,
                null,
                null, null, null, null, null, null, null);
    }

    private static Map<String, List<String>> errorsOf(CaseUpdateRequest request) {
        return errorsOf(CaseEdit.ProcedureRule.DEFERRED, request);
    }

    private static Map<String, List<String>> errorsOf(
            CaseEdit.ProcedureRule procedure, CaseUpdateRequest request) {

        return switch (validate(procedure, request)) {
            case CaseEdit.Outcome.Invalid invalid -> invalid.errors();
            case CaseEdit.Outcome.Valid ignored -> Map.of();
        };
    }

    private static CaseEdit validOf(CaseUpdateRequest request) {
        CaseEdit.Outcome outcome = validate(CaseEdit.ProcedureRule.DEFERRED, request);
        assertThat(outcome)
                .as("the payload was expected to validate: %s", outcome)
                .isInstanceOf(CaseEdit.Outcome.Valid.class);
        return ((CaseEdit.Outcome.Valid) outcome).edit();
    }

    /**
     * {@link CaseEdit#validate} with every collaborator neutral.
     *
     * <p>The empty sets and the always-false predicate are not stubs standing in for behaviour —
     * the payload names no condition, no classification and no automation key, so none of them is
     * ever consulted. That is what keeps these arms about the step rules and nothing else.
     */
    private static CaseEdit.Outcome validate(
            CaseEdit.ProcedureRule procedure, CaseUpdateRequest request) {

        return CaseEdit.validate(
                request,
                Set.of(SUITE),
                Set.of(STEP_ONE),
                ClassificationVocabulary.none(),
                Set.of(),
                Set.of(),
                List.of(),
                List.of(),
                Set.of(),
                Set.of(),
                List.of(),
                key -> false,
                null,
                null,
                procedure);
    }
}
