package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link GherkinScenarios} as a <strong>pure function</strong> — the parser, its inverse, and the
 * property that the two agree.
 *
 * <h2>No Spring context and no database, deliberately</h2>
 *
 * <p>Nothing here reads or writes a row, so nothing here needs a container. The endpoint test
 * beside it boots the application to prove the two <em>endpoints</em> answer what the contract
 * says; this class proves the text handling, where enumerating constructs costs nothing.
 *
 * <h2>{@code everything.feature} is the contract with the library</h2>
 *
 * <p>{@code integrations.md} X1 names the AST subset this repo depends on —
 * {@code Feature · Rule · Background · Scenario · Step · DocString · DataTable · Examples · Tag ·
 * Comment}. The fixture carries eight of the ten; {@code Rule} and the {@code # language:} header
 * are covered by arms of their own below, because both are whole-document properties that the
 * carve-up cannot put in one scenario. That is what makes a library upgrade a <em>measured</em>
 * change: a major bump that alters any of them fails here, against a file this repo owns, rather
 * than in a customer's import.
 *
 * <p>The fixture is one document with several scenarios, and the round trip runs over each of them
 * <strong>separately</strong> — because {@link GherkinScenarios#parse} refuses a text with more
 * than one, which is itself one of the assertions below. {@link #scenarioTexts()} carves it up.
 *
 * <h2>What the round trip is actually asserting</h2>
 *
 * <p>{@code parse(render(parse(x)))} equals {@code parse(x)} on <strong>every field a save keeps</strong>
 * — keyword, action, testData, expectedResult, docString, dataTable, and the title, objective, tags
 * and background beside them. It is the property that makes "view as .feature" safe to hand to a
 * Cucumber suite: a field with no Gherkin syntax has to survive going through the text, and two of
 * them ({@code expectedResult}, {@code testData}) only do so because the renderer writes comments
 * the parser reads back.
 */
@DisplayName("GherkinScenarios — the one parser, and its inverse")
class GherkinScenariosTest {

    private static final String FIXTURE = "/gherkin/everything.feature";
    private static final String FEATURE_NAME = "Everything the parser has to survive";

    @Nested
    @DisplayName("parse ∘ render ∘ parse is identity on the rows")
    class RoundTrip {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.tcms.repository.GherkinScenariosTest#roundTrippableScenarios")
        @DisplayName("every scenario in everything.feature survives")
        void everyConstructSurvivesTheRoundTrip(String title, String text) {
            GherkinParseResult once = GherkinScenarios.parse(text);
            GherkinParseResult twice =
                    GherkinScenarios.parse(GherkinScenarios.render(once, FEATURE_NAME));

            assertThat(fingerprint(twice))
                    .as("a field was lost or changed by going through the text — '%s'", title)
                    .isEqualTo(fingerprint(once));
        }

        @Test
        @DisplayName("the fixture still carries every construct — the guard on the rule above")
        void theFixtureIsStillTheContractWithTheLibrary() {
            // Without this, a fixture that stopped splitting — or one somebody trimmed — makes the
            // parameterized rule above pass on two trivial scenarios and report success.
            List<String> texts = scenarioTexts();

            assertThat(texts)
                    .as("the carve-up has stopped matching %s", FIXTURE)
                    .hasSizeGreaterThanOrEqualTo(6);
            assertThat(texts.stream().filter(text -> text.contains("Scenario Outline:")).count())
                    .as("exactly one Outline, which is the one scenario the round trip excludes")
                    .isEqualTo(1);
            assertThat(fixture())
                    .as("integrations.md X1 names the AST subset this file has to cover")
                    .contains("Background:", "\"\"\"", "| name", "# expected:", "# data:",
                            "* one thing happens", "Scenario Outline:", "Examples:");
        }

        @Test
        @DisplayName("a postcondition comment block comes back as nothing — not a row, not a "
                + "background")
        void postconditionCommentsAreNotReRead() {
            // The half of the render that is deliberately NOT an inverse. POST conditions have no
            // Gherkin construct, so they leave as comments so a human reading the text is not
            // shown a lossy view of the case — and they must not return, or every re-import would
            // turn a postcondition into a step.
            GherkinParseResult parsed = GherkinScenarios.parse("""
                    Feature: F

                      Scenario: S
                        Given a captured payment
                        Then it is refunded

                        # postconditions:
                        #   Reset the test payment data
                        #   Sign the operator out
                    """);

            assertThat(parsed.steps()).hasSize(2);
            assertThat(parsed.background()).isEmpty();
            assertThat(parsed.steps().get(1).expectedResult())
                    .as("the block is not a continuation of the last step's expectation either")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("the constructs")
    class Constructs {

        @Test
        @DisplayName("all five keywords map, And and But included")
        void everyKeywordMaps() {
            GherkinParseResult parsed = GherkinScenarios.parse(scenarioNamed(
                    "All five keywords, And and But included"));

            assertThat(parsed.steps())
                    .extracting(CaseUpdateRequest.StepInput::keyword)
                    .as("And and But are what the author wrote and what an export has to "
                            + "reproduce; folding them into the preceding keyword makes the round "
                            + "trip lossy for a cosmetic tidy-up")
                    .containsExactly("GIVEN", "AND", "WHEN", "BUT", "THEN");
        }

        @Test
        @DisplayName("a * step has no keyword — the null is the format, not a missing value")
        void starStepsCarryNoKeyword() {
            GherkinParseResult parsed = GherkinScenarios.parse(scenarioNamed(
                    "Format-neutral steps"));

            assertThat(parsed.steps())
                    .extracting(CaseUpdateRequest.StepInput::keyword)
                    .as("the dialect lists * under EVERY keyword, so a membership test in the "
                            + "wrong order reads a format-neutral step as Given")
                    .containsExactly(null, null);
        }

        @Test
        @DisplayName("a doc string keeps its own indentation and its line breaks")
        void docStringsSurvive() {
            CaseUpdateRequest.StepInput step =
                    GherkinScenarios.parse(scenarioNamed("Block arguments")).steps().get(0);

            assertThat(step.docString())
                    .isEqualTo("{\"id\": 7,\n \"name\": \"a doc string keeps its own "
                            + "indentation\"}");
            assertThat(step.dataTable()).isNull();
            assertThat(step.testData())
                    .as("one step, one data spelling — ck_test_step_one_data")
                    .isNull();
        }

        @Test
        @DisplayName("a data table's escapes are unescaped on the way in and re-escaped on the "
                + "way out")
        void dataTableEscapesRoundTrip() {
            CaseUpdateRequest.StepInput step =
                    GherkinScenarios.parse(scenarioNamed("Block arguments")).steps().get(1);

            assertThat(step.dataTable()).containsExactly(
                    List.of("name", "value"),
                    List.of("a pipe", "a|b"),
                    List.of("a backlash", "a\\b"),
                    List.of("a newline", "a\nb"));

            // The escaping order is the assertion: escaping the pipe before the backslash turns
            // `a\b` into `a\\b` and then into `a\\\\b`, so a cell containing one grows on every
            // round trip. Rendering and re-reading is what makes that visible at all.
            String rendered = GherkinScenarios.render(
                    GherkinScenarios.parse(scenarioNamed("Block arguments")), FEATURE_NAME);

            assertThat(GherkinScenarios.parse(rendered).steps().get(1).dataTable())
                    .isEqualTo(step.dataTable());
        }

        @Test
        @DisplayName("# expected: and # data: comments fill the two fields Gherkin cannot spell")
        void commentsFillTheTwoFieldsWithNoSyntax() {
            List<CaseUpdateRequest.StepInput> steps = GherkinScenarios.parse(
                    scenarioNamed("The two fields Gherkin has no syntax for")).steps();

            assertThat(steps.get(0).testData()).isEqualTo("alice / hunter2");
            assertThat(steps.get(1).expectedResult())
                    .isEqualTo("the dashboard opens within two seconds");
            assertThat(steps.get(2).expectedResult())
                    .as("a comment belongs to the step ABOVE it, so the last step gets neither")
                    .isNull();
        }

        @Test
        @DisplayName("a multi-line value comes back joined, from its continuation lines")
        void aMultiLineAnnotationIsJoined() {
            GherkinParseResult parsed = GherkinScenarios.parse("""
                    Scenario: S
                      Given a refund is requested
                      # expected: the balance falls
                      #   and an audit row is written
                    """);

            assertThat(parsed.steps().get(0).expectedResult())
                    .isEqualTo("the balance falls\nand an audit row is written");
        }

        @Test
        @DisplayName("a Background is ONE entry per step, not one blob (AM1)")
        void aBackgroundIsOneEntryPerStep() {
            GherkinParseResult parsed = GherkinScenarios.parse(scenarioNamed("Plain steps"));

            assertThat(parsed.background())
                    .as("a Background is N preconditions in this product's model; collapsing them "
                            + "makes one library entry no other case can reuse half of")
                    .extracting(GherkinParseResult.ParsedBackground::title)
                    .containsExactly(
                            "Given the seed catalogue is loaded",
                            "And a signed-in test engineer");

            assertThat(parsed.background().get(0).body())
                    .as("the body opens with the step itself — a condition that showed only its "
                            + "attachments would show a tester nothing")
                    .containsExactly("Given the seed catalogue is loaded");
        }

        @Test
        @DisplayName("feature tags and scenario tags merge, in that order, without their @")
        void tagsMerge() {
            assertThat(GherkinScenarios.parse(scenarioNamed("Plain steps")).tags())
                    .containsExactly("catalogue", "regression", "plain");
        }

        @Test
        @DisplayName("the objective is dedented — the file's indentation is not the author's text")
        void theObjectiveIsDedented() {
            assertThat(GherkinScenarios.parse(scenarioNamed("Block arguments")).objective())
                    .isEqualTo("the objective, which runs to two lines\n"
                            + "and this is its second one");
        }

        @Test
        @DisplayName("a Rule around the scenario is flattened, not refused")
        void aRuleIsFlattened() {
            // Not an accepted shape of the paste endpoint, and read anyway: answering "No steps
            // found" about text that plainly has steps is the wrong refusal.
            GherkinParseResult parsed = GherkinScenarios.parse("""
                    Feature: F
                      Rule: refunds are logged
                        Scenario: S
                          Given a captured payment
                    """);

            assertThat(parsed.title()).isEqualTo("S");
            assertThat(parsed.steps()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the shapes a paste actually arrives in")
    class AcceptedShapes {

        @Test
        @DisplayName("a bare step list parses — it is not a Gherkin document, and it is the "
                + "commonest paste")
        void aBareStepListParses() {
            GherkinParseResult parsed = GherkinScenarios.parse("""
                    Given a captured payment
                    When I refund it
                    Then the balance falls
                    """);

            assertThat(parsed.steps()).hasSize(3);
            assertThat(parsed.title())
                    .as("inventing a title would put a value in the editor's title field that "
                            + "nobody typed")
                    .isNull();
        }

        @Test
        @DisplayName("a lone Scenario: block parses, and is NOT read as two scenarios")
        void aScenarioBlockParses() {
            // The failure this arm exists for: wrapping the text in a synthetic Scenario when it
            // already has one makes a perfectly ordinary paste refuse as "two scenarios".
            GherkinParseResult parsed = GherkinScenarios.parse("""
                    Scenario: A refund lands on the ledger
                      Given a captured payment
                      Then it is refunded
                    """);

            assertThat(parsed.title()).isEqualTo("A refund lands on the ledger");
            assertThat(parsed.steps()).hasSize(2);
        }

        @Test
        @DisplayName("# language: de is honoured, and the keywords still map")
        void theDeclaredDialectIsHonoured() {
            GherkinParseResult parsed = GherkinScenarios.parse("""
                    # language: de
                    Funktionalität: Erstattungen
                      Szenario: Eine Erstattung wird gebucht
                        Angenommen eine erfasste Zahlung
                        Und ein angemeldeter Tester
                        Wenn ich sie erstatte
                        Aber sonst ändert sich nichts
                        Dann sinkt der Saldo
                    """);

            assertThat(parsed.steps())
                    .extracting(CaseUpdateRequest.StepInput::keyword)
                    .as("the AST normalises keywords per dialect, so a German scenario is the "
                            + "same five members — a team that writes them must be able to paste")
                    .containsExactly("GIVEN", "AND", "WHEN", "BUT", "THEN");
        }

        @Test
        @DisplayName("a German BARE step list parses too — the header moves, the dialect holds")
        void aBareListInAnotherDialectParses() {
            GherkinParseResult parsed = GherkinScenarios.parse("""
                    # language: de
                    Angenommen eine erfasste Zahlung
                    Dann sinkt der Saldo
                    """);

            assertThat(parsed.steps())
                    .extracting(CaseUpdateRequest.StepInput::keyword)
                    .containsExactly("GIVEN", "THEN");
        }
    }

    @Nested
    @DisplayName("the four refusals")
    class Refusals {

        @Test
        @DisplayName("a syntax error carries the parser's line, column and wording")
        void aSyntaxErrorNamesTheLine() {
            assertThatThrownBy(() -> GherkinScenarios.parse("""
                    Feature: F
                      Scenario: S
                        Given a captured payment
                      this line is not anything
                    """))
                    .isInstanceOfSatisfying(GherkinSyntaxException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(GherkinSyntaxException.Reason.SYNTAX))
                    .hasMessageStartingWith("Line 4, column 3: ")
                    .as("the library's own `(4:3):` is a spelling no end user reads")
                    .hasMessageNotContaining("(4:3)");
        }

        @Test
        @DisplayName("a Scenario Outline is refused, and the refusal names ITS line")
        void anOutlineIsRefusedWithItsLine() {
            assertThatThrownBy(() -> GherkinScenarios.parse("""
                    Feature: F

                      Scenario Outline: a template
                        Given a balance of <amount>

                        Examples:
                          | amount |
                          | 10     |
                    """))
                    .isInstanceOfSatisfying(GherkinSyntaxException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(GherkinSyntaxException.Reason.OUTLINE))
                    .hasMessage("Line 3: Scenario Outline is not supported yet — expand the "
                            + "examples into separate scenarios");
        }

        @Test
        @DisplayName("an Outline whose Examples block is still to be written is refused too")
        void aKeywordOnlyOutlineIsRefused() {
            // The keyword and the examples are BOTH checked. Neither alone is enough: a template
            // half written has no examples, and an author reads the keyword — so a check on one
            // lets `Given <input>` through as a literal step.
            assertThatThrownBy(() -> GherkinScenarios.parse("""
                    Feature: F
                      Scenario Outline: a template
                        Given a balance of <amount>
                    """))
                    .isInstanceOfSatisfying(GherkinSyntaxException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(GherkinSyntaxException.Reason.OUTLINE));
        }

        @Test
        @DisplayName("two scenarios are refused, and the count is in the sentence")
        void twoScenariosAreRefused() {
            assertThatThrownBy(() -> GherkinScenarios.parse("""
                    Feature: F
                      Scenario: A
                        Given a captured payment
                      Scenario: B
                        Given a second payment
                    """))
                    .isInstanceOfSatisfying(GherkinSyntaxException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(GherkinSyntaxException.Reason.MANY_SCENARIOS))
                    .hasMessage("This text holds 2 scenarios; paste one, or import the file");
        }

        /**
         * The third case is the interesting one: a document that <em>parsed</em> and simply has no
         * scenario. It has to report its own outcome, because wrapping it would bury its
         * {@code Feature:} line inside a synthetic scenario — where it reads as a
         * <strong>description</strong>, and the refusal becomes a plausible success carrying the
         * file's own syntax as prose.
         */
        @ParameterizedTest(name = "[{index}]")
        @ValueSource(strings = {
            "",
            "# just a note\n",
            "Feature: F\n  Background:\n    Given a captured payment\n"})
        @DisplayName("empty text, a comment alone, and a Background with no scenario are all "
                + "'No steps found'")
        void nothingToParseIsRefused(String nothing) {
            assertThatThrownBy(() -> GherkinScenarios.parse(nothing))
                    .isInstanceOfSatisfying(GherkinSyntaxException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(GherkinSyntaxException.Reason.EMPTY))
                    .hasMessage("No steps found");
        }

        @Test
        @DisplayName("null is the empty paste, not a crash")
        void nullIsRefusedAsEmpty() {
            assertThatThrownBy(() -> GherkinScenarios.parse(null))
                    .isInstanceOfSatisfying(GherkinSyntaxException.class, refused ->
                            assertThat(refused.reason())
                                    .isEqualTo(GherkinSyntaxException.Reason.EMPTY));
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /**
     * Every field a save keeps, as one string.
     *
     * <p>One value rather than a field-by-field comparison so a failure prints both documents side
     * by side: the interesting failures here are "one cell gained a backslash", and a per-field
     * assertion reports that as a boolean.
     */
    private static String fingerprint(GherkinParseResult parsed) {
        StringBuilder out = new StringBuilder()
                .append("title=").append(parsed.title()).append('\n')
                .append("objective=").append(parsed.objective()).append('\n')
                .append("tags=").append(parsed.tags()).append('\n')
                .append("background=").append(parsed.background()).append('\n');
        for (CaseUpdateRequest.StepInput step : parsed.steps()) {
            out.append("step ").append(step.keyword()).append(" | ").append(step.action())
                    .append(" | data=").append(step.testData())
                    .append(" | expected=").append(step.expectedResult())
                    .append(" | doc=").append(step.docString())
                    .append(" | table=").append(step.dataTable())
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * The fixture's scenarios, minus the Outline — the round trip's cases.
     *
     * <p>Static and named by its fully-qualified path because the rule that consumes it lives in a
     * {@code @Nested} class, which cannot host one of its own.
     */
    static Stream<Arguments> roundTrippableScenarios() {
        return scenarioTexts().stream()
                .filter(text -> !text.contains("Scenario Outline:"))
                .map(text -> Arguments.of(GherkinScenarios.parse(text).title(), text));
    }

    private static String scenarioNamed(String title) {
        return scenarioTexts().stream()
                .filter(text -> text.contains("Scenario: " + title))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no scenario titled '" + title + "' in " + FIXTURE));
    }

    /**
     * The fixture carved into one parseable document per scenario.
     *
     * <p>Necessary because the parser refuses a text with more than one scenario, which is one of
     * the rules under test — so the fixture cannot be fed to it whole. Each text is the file's
     * header (its tags, {@code Feature:}, description and {@code Background:}) followed by exactly
     * one scenario and its own tag lines.
     */
    private static List<String> scenarioTexts() {
        List<String> lines = List.of(fixture().split("\n", -1));

        int firstScenario = -1;
        for (int i = 0; i < lines.size() && firstScenario < 0; i++) {
            if (lines.get(i).strip().startsWith("Scenario")) {
                firstScenario = i;
                while (firstScenario > 0 && lines.get(firstScenario - 1).strip().startsWith("@")) {
                    firstScenario--;
                }
            }
        }
        if (firstScenario < 0) {
            throw new AssertionError(FIXTURE + " has no scenario at all");
        }

        List<Integer> starts = new ArrayList<>();
        for (int i = firstScenario; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("Scenario")) {
                int start = i;
                while (start > firstScenario && lines.get(start - 1).strip().startsWith("@")) {
                    start--;
                }
                starts.add(start);
            }
        }

        String header = String.join("\n", lines.subList(0, firstScenario));
        List<String> texts = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : lines.size();
            texts.add(header + "\n"
                    + String.join("\n", lines.subList(starts.get(i), end)) + "\n");
        }
        return List.copyOf(texts);
    }

    private static String fixture() {
        try (InputStream stream = GherkinScenariosTest.class.getResourceAsStream(FIXTURE)) {
            if (stream == null) {
                throw new AssertionError(FIXTURE + " is not on the test classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
