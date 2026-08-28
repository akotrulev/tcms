package com.tcms.repository;

import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialects;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Comment;
import io.cucumber.messages.types.DataTable;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.Location;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;
import io.cucumber.messages.types.TableCell;
import io.cucumber.messages.types.TableRow;
import io.cucumber.messages.types.Tag;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <strong>The one Gherkin parser in this product, and its inverse.</strong> Text in, step rows out
 * ({@link #parse}); a case's rows out as canonical {@code .feature} text ({@link #render}).
 *
 * <h2>Pure, and that is the load-bearing property</h2>
 *
 * <p>Nothing here reads or writes a row. {@link #parse} is a function of its argument and
 * {@link #render} a function of the view it is handed, which is what lets the paste endpoint have
 * no write path at all: an author pastes, sees the rows, and saves through the ordinary case write
 * with the ordinary validation ({@link CaseEdit}). The two consumers that <em>do</em> persist —
 * the backfill and the {@code .feature} import — call these same two methods rather than each
 * growing a parser, which is plan §6a rule 3.
 *
 * <h2>{@code parse ∘ render ∘ parse} is identity on the rows</h2>
 *
 * <p>Not a nice-to-have: it is what makes "view as .feature" safe to hand to a Cucumber suite and
 * what makes an import round trip. Every field that survives a save has a spelling in the text, so
 * <em>none of them is dropped by going through it</em> — including the two Gherkin has no syntax
 * for. {@code expectedResult} and {@code testData} become {@code # expected:} / {@code # data:}
 * comment lines that {@link #parse} reads back, and a case's {@code POST} conditions become a
 * trailing {@code # postconditions:} block that it deliberately does <strong>not</strong>.
 *
 * <h2>A parser failure is a 400, never a 500</h2>
 *
 * <p>{@link GherkinSyntaxException} is the only failure this class raises, and it carries the line
 * the author has to look at. The library helps rather than hinders here: since v28 a syntax error
 * arrives as a {@code ParseError} <em>envelope</em> in the message stream instead of an exception,
 * so there is no throw to forget to catch — see {@code integrations.md} X1.
 *
 * <h2>Parsing follows the file's dialect; rendering is always English</h2>
 *
 * <p>{@code # language: de} is honoured on the way in, because the AST normalises keywords and a
 * team writing German scenarios must be able to paste them. On the way out there is one dialect,
 * because the rendered text's audience is a Cucumber runner and {@code StepKeyword} has five
 * members with one spelling each — deriving an output language from the case would mean storing one,
 * which is a column nobody asked for.
 */
final class GherkinScenarios {

    /**
     * The paste cap, in bytes of UTF-8 — {@code api-surface.md} §5.1.
     *
     * <p>Bytes rather than characters because it bounds what the server reads, and a 256 KiB cap
     * measured in characters is a 1 MiB read for text that is mostly non-ASCII.
     */
    static final int MAX_PASTE_BYTES = 256 * 1024;

    private static final String URI = "paste.feature";

    /** Both spellings the renderer emits and the parser reads back. */
    private static final String EXPECTED_PREFIX = "# expected:";
    private static final String DATA_PREFIX = "# data:";
    private static final String POSTCONDITIONS_HEADER = "# postconditions:";
    /** A continuation of the comment above it — the one shape a multi-line value renders as. */
    private static final String CONTINUATION_PREFIX = "#   ";

    private static final String STEP_INDENT = "    ";
    private static final String BLOCK_INDENT = "  ";

    private static final Pattern LANGUAGE_HEADER =
            Pattern.compile("^\\s*#\\s*language\\s*:\\s*([A-Za-z0-9_-]+)\\s*$");

    private GherkinScenarios() {
    }

    // =====================================================================
    // parse
    // =====================================================================

    /**
     * Pasted text, read as the fields the case editor holds.
     *
     * <p>Three shapes are accepted and two of them are not Gherkin documents: neither a bare step
     * list nor a lone {@code Scenario:} block has a {@code Feature:}, and the parser refuses both at
     * line 1. So the text is tried as written, and only a text that produced <strong>no feature at
     * all</strong> is retried inside a nameless wrapper of its own dialect — a {@code Feature:} when
     * it already names a scenario, a {@code Feature:} and a {@code Scenario:} when it starts at a
     * step.
     *
     * <p>The wrapping is conditioned on the parse and not on the text for a reason worth keeping:
     * wrapping a document that <em>did</em> parse — a {@code Feature:} with a {@code Background:}
     * and no scenario, say — buries its own {@code Feature:} line inside a synthetic scenario, where
     * it reads as a <em>description</em>. The result is a plausible success carrying the file's own
     * syntax as prose, which is worse than the refusal it replaced.
     *
     * <p>The refusal reported is always the <em>unwrapped</em> one, because its line numbers are the
     * ones the author can see.
     *
     * @throws GherkinSyntaxException for each of the four refusals in {@code api-surface.md} §5.1
     */
    static GherkinParseResult parse(String text) {
        String source = text == null ? "" : text;

        Attempt direct = attempt(source);
        if (direct.usable()) {
            return build(direct);
        }
        if (direct.feature() == null) {
            Attempt wrapped = attempt(wrap(source));
            if (wrapped.usable()) {
                return build(wrapped);
            }
        }
        if (!direct.errors().isEmpty()) {
            throw new GherkinSyntaxException(
                    GherkinSyntaxException.Reason.SYNTAX, sentence(direct.errors().get(0)));
        }
        throw new GherkinSyntaxException(GherkinSyntaxException.Reason.EMPTY, "No steps found");
    }

    /**
     * A whole {@code .feature} file, as the import reads it — {@link #parse}'s sibling, not its
     * caller.
     *
     * <h2>Three differences from {@link #parse}, each of them the import's requirement</h2>
     *
     * <ol>
     *   <li><strong>No wrapping.</strong> A file that does not open with {@code Feature:} is not a
     *       file this product can import, so the retry-inside-a-wrapper that makes a bare paste
     *       work is deliberately absent: wrapping a file would import somebody's syntax error as a
     *       scenario named after it.</li>
     *   <li><strong>Every scenario, and the refusals are DATA.</strong> A {@code Scenario Outline}
     *       in one file must refuse one row and leave the other thirty-nine files alone, so it
     *       becomes {@link ParsedFeature.ParsedScenario#refusal()} rather than an exception.
     *       Only a failure of the <em>file</em> throws.</li>
     *   <li><strong>Each scenario carries its own text.</strong> {@code imported_source} is what
     *       makes a re-import of an unchanged folder write nothing, and it can only be cut out of
     *       the original text — the AST has no verbatim form.</li>
     * </ol>
     *
     * <p>A file with no {@code Feature:} at all and no syntax error — an empty file, or one holding
     * only comments — answers a {@link ParsedFeature} with a null name and no scenarios. That is
     * the caller's {@code "Feature has no name"} row: making it an exception would need a fifth
     * {@link GherkinSyntaxException.Reason}, and every {@code Reason} is a {@code type} slug on the
     * paste endpoint's contract for a condition a paste cannot produce.
     *
     * @throws GherkinSyntaxException {@code SYNTAX} only, carrying the first parse error with its
     *                                line — the whole file is one {@code REFUSE} row
     */
    static ParsedFeature parseFeature(String text) {
        String source = text == null ? "" : text;
        Attempt attempt = attempt(source);

        if (!attempt.errors().isEmpty()) {
            throw new GherkinSyntaxException(
                    GherkinSyntaxException.Reason.SYNTAX, sentence(attempt.errors().get(0)));
        }
        Feature feature = attempt.feature();
        if (feature == null) {
            return new ParsedFeature(null, List.of());
        }

        GherkinDialect dialect = dialectOf(feature.getLanguage());
        List<String> lines = List.of(source.split("\n", -1));
        List<Integer> boundaries = childLines(feature);
        List<GherkinParseResult.ParsedBackground> featureBackground =
                backgroundSteps(dialect, feature.getChildren().stream()
                        .map(FeatureChild::getBackground)
                        .flatMap(Optional::stream)
                        .toList());

        List<ParsedFeature.ParsedScenario> scenarios = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            child.getScenario().ifPresent(scenario -> scenarios.add(scenarioOf(
                    dialect, attempt.comments(), lines, boundaries, scenario,
                    featureBackground, List.of(), null, null, List.of())));
            child.getRule().ifPresent(rule -> {
                List<GherkinParseResult.ParsedBackground> ruleBackground =
                        backgroundSteps(dialect, rule.getChildren().stream()
                                .map(RuleChild::getBackground)
                                .flatMap(Optional::stream)
                                .toList());
                List<String> ruleTags = rule.getTags().stream()
                        .map(tag -> withoutAt(tag.getName()))
                        .toList();
                for (RuleChild ruleChild : rule.getChildren()) {
                    ruleChild.getScenario().ifPresent(scenario -> scenarios.add(scenarioOf(
                            dialect, attempt.comments(), lines, boundaries, scenario,
                            featureBackground, ruleBackground,
                            blankToNull(rule.getName()), dedent(rule.getDescription()), ruleTags)));
                }
            });
        }
        return new ParsedFeature(blankToNull(feature.getName()), List.copyOf(scenarios));
    }

    /**
     * One scenario of a file, refusal included.
     *
     * <p>The {@code Rule:} contributions are three and they are kept separate on purpose: its
     * {@code Background:} steps attach AFTER the feature's ({@code api-surface.md} §5.4), its name
     * and description become a prefix on the objective rather than being dropped, and its tags join
     * the case's. A reader looking for "where does the Rule go" finds all three here.
     */
    private static ParsedFeature.ParsedScenario scenarioOf(
            GherkinDialect dialect,
            List<Comment> comments,
            List<String> lines,
            List<Integer> boundaries,
            Scenario scenario,
            List<GherkinParseResult.ParsedBackground> featureBackground,
            List<GherkinParseResult.ParsedBackground> ruleBackground,
            String ruleName,
            String ruleDescription,
            List<String> ruleTags) {

        int line = scenario.getLocation().getLine();
        int endsBefore = boundaries.stream()
                .filter(at -> at > line)
                .min(Integer::compareTo)
                .orElse(lines.size() + 1);

        LinkedHashSet<String> tags = new LinkedHashSet<>(ruleTags);
        for (Tag tag : scenario.getTags()) {
            tags.add(withoutAt(tag.getName()));
        }

        List<GherkinParseResult.ParsedBackground> background =
                new ArrayList<>(featureBackground);
        background.addAll(ruleBackground);

        String refusal = isOutline(dialect, scenario)
                ? "Scenario Outline is not supported yet"
                : null;

        return new ParsedFeature.ParsedScenario(
                blankToNull(scenario.getName()),
                line,
                objectiveOf(scenario, ruleName, ruleDescription),
                List.copyOf(tags),
                List.copyOf(background),
                refusal == null
                        ? stepsOf(dialect, scenario, comments, endsBefore)
                        : List.of(),
                slice(lines, line, endsBefore),
                refusal);
    }

    /** The scenario's description, with its enclosing {@code Rule:} written in front of it. */
    private static String objectiveOf(
            Scenario scenario, String ruleName, String ruleDescription) {

        String own = dedent(scenario.getDescription());
        if (ruleName == null) {
            return blankToNull(own);
        }
        StringBuilder prefix = new StringBuilder("Rule: ").append(ruleName).append('\n');
        if (ruleDescription != null && !ruleDescription.isBlank()) {
            prefix.append(ruleDescription).append('\n');
        }
        prefix.append('\n');
        return blankToNull(own == null ? prefix.toString().strip() : prefix + own);
    }

    /**
     * Every line a child of this feature begins on — scenarios, backgrounds, rules and a rule's own
     * children.
     *
     * <p>This is how a scenario's text ends: at the line before the next thing that starts. Reading
     * the step list instead would stop at the last step's FIRST line and cut off its doc string, its
     * table and the {@code # expected:} comment underneath it.
     */
    private static List<Integer> childLines(Feature feature) {
        List<Integer> lines = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            child.getBackground().ifPresent(at -> lines.add(at.getLocation().getLine()));
            child.getScenario().ifPresent(at -> lines.add(at.getLocation().getLine()));
            child.getRule().ifPresent(rule -> {
                lines.add(rule.getLocation().getLine());
                for (RuleChild ruleChild : rule.getChildren()) {
                    ruleChild.getBackground().ifPresent(at -> lines.add(at.getLocation().getLine()));
                    ruleChild.getScenario().ifPresent(at -> lines.add(at.getLocation().getLine()));
                }
            });
        }
        return List.copyOf(lines);
    }

    /** Lines {@code [from, endsBefore)}, 1-based and inclusive of {@code from}, right-trimmed. */
    private static String slice(List<String> lines, int from, int endsBefore) {
        int last = Math.min(endsBefore - 1, lines.size());
        List<String> taken = new ArrayList<>(lines.subList(from - 1, Math.max(from - 1, last)));
        while (!taken.isEmpty() && taken.get(taken.size() - 1).isBlank()) {
            taken.remove(taken.size() - 1);
        }
        return String.join("\n", taken);
    }

    /** One entry per Background STEP, in order — amendment AM1, and {@link #backgroundOf}'s core. */
    private static List<GherkinParseResult.ParsedBackground> backgroundSteps(
            GherkinDialect dialect, List<Background> backgrounds) {

        List<GherkinParseResult.ParsedBackground> entries = new ArrayList<>();
        for (Background background : backgrounds) {
            for (Step step : background.getSteps()) {
                List<String> body = stepLines(toInput(dialect, step));
                entries.add(new GherkinParseResult.ParsedBackground(body.get(0), body));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * One pass of the library over one text.
     *
     * @param usable whether {@link #build} can be run on it — no refusals, a feature, and at least
     *               one scenario. A document with a {@code Background:} and nothing else is
     *               deliberately <em>not</em> usable: it has no steps to become a procedure, and
     *               the caller then reports "No steps found" rather than an empty success.
     */
    private record Attempt(List<ParseError> errors, Feature feature, List<Comment> comments) {

        boolean usable() {
            return errors.isEmpty() && feature != null && !scenariosOf(feature).isEmpty();
        }
    }

    private static Attempt attempt(String source) {
        // A parser per call rather than a shared instance: the builder's id generator is mutable
        // state, and this method is reached concurrently by every request thread.
        List<Envelope> envelopes = GherkinParser.builder()
                .includeSource(false)
                .includePickles(false)
                .build()
                .parse(URI, source.getBytes(StandardCharsets.UTF_8))
                .toList();

        List<ParseError> errors = new ArrayList<>();
        List<Comment> comments = new ArrayList<>();
        Feature feature = null;
        for (Envelope envelope : envelopes) {
            Optional<ParseError> error = envelope.getParseError();
            if (error.isPresent()) {
                errors.add(error.get());
                continue;
            }
            Optional<GherkinDocument> document = envelope.getGherkinDocument();
            if (document.isPresent()) {
                comments.addAll(document.get().getComments());
                feature = document.get().getFeature().orElse(feature);
            }
        }
        return new Attempt(List.copyOf(errors), feature, List.copyOf(comments));
    }

    private static GherkinParseResult build(Attempt attempt) {
        Feature feature = attempt.feature();
        GherkinDialect dialect = dialectOf(feature.getLanguage());
        List<Scenario> scenarios = scenariosOf(feature);

        for (Scenario scenario : scenarios) {
            if (isOutline(dialect, scenario)) {
                throw new GherkinSyntaxException(GherkinSyntaxException.Reason.OUTLINE,
                        "Line " + scenario.getLocation().getLine() + ": Scenario Outline is not "
                                + "supported yet — expand the examples into separate scenarios");
            }
        }
        if (scenarios.size() > 1) {
            throw new GherkinSyntaxException(GherkinSyntaxException.Reason.MANY_SCENARIOS,
                    "This text holds " + scenarios.size()
                            + " scenarios; paste one, or import the file");
        }

        Scenario scenario = scenarios.get(0);
        if (scenario.getSteps().isEmpty()) {
            throw new GherkinSyntaxException(
                    GherkinSyntaxException.Reason.EMPTY, "No steps found");
        }

        return new GherkinParseResult(
                blankToNull(scenario.getName()),
                blankToNull(dedent(scenario.getDescription())),
                tagsOf(feature, scenario),
                backgroundOf(dialect, feature),
                stepsOf(dialect, scenario, attempt.comments()));
    }

    /**
     * An Outline, by either of the two things that make one.
     *
     * <p>The keyword alone is not enough — an Outline with its {@code Examples:} block still to be
     * written parses as one and must be refused the same way — and the examples alone are not
     * either, since the keyword is what an author reads. Both are checked so that neither half
     * being absent lets a template through as an ordinary scenario, which would import
     * {@code Given <input>} as a literal step.
     */
    private static boolean isOutline(GherkinDialect dialect, Scenario scenario) {
        return !scenario.getExamples().isEmpty()
                || matches(dialect.getScenarioOutlineKeywords(), scenario.getKeyword());
    }

    private static List<String> tagsOf(Feature feature, Scenario scenario) {
        // A set, and an insertion-ordered one: the same tag at both levels is one tag on the case,
        // and the order is the file's rather than the alphabet's so a re-render reproduces it.
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (Tag tag : feature.getTags()) {
            tags.add(withoutAt(tag.getName()));
        }
        for (Tag tag : scenario.getTags()) {
            tags.add(withoutAt(tag.getName()));
        }
        return List.copyOf(tags);
    }

    private static List<GherkinParseResult.ParsedBackground> backgroundOf(
            GherkinDialect dialect, Feature feature) {

        return backgroundSteps(dialect, backgroundsOf(feature));
    }

    private static List<CaseUpdateRequest.StepInput> stepsOf(
            GherkinDialect dialect, Scenario scenario, List<Comment> comments) {

        return stepsOf(dialect, scenario, comments, Integer.MAX_VALUE);
    }

    /**
     * @param lastStepEndsAt the first line that is NOT part of this scenario, so a {@code #
     *                       expected:} comment written above the NEXT scenario cannot be read as
     *                       the last step's. {@link Integer#MAX_VALUE} for a paste, which holds one
     *                       scenario and nothing after it; the next child's line for a file, which
     *                       holds many.
     */
    private static List<CaseUpdateRequest.StepInput> stepsOf(
            GherkinDialect dialect, Scenario scenario, List<Comment> comments,
            int lastStepEndsAt) {

        List<Step> parsed = scenario.getSteps();
        List<StepComment> annotations = stepComments(comments);

        List<CaseUpdateRequest.StepInput> steps = new ArrayList<>(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            CaseUpdateRequest.StepInput step = toInput(dialect, parsed.get(i));
            steps.add(new CaseUpdateRequest.StepInput(
                    null,
                    step.keyword(),
                    step.action(),
                    annotationFor(annotations, parsed, i, false, lastStepEndsAt),
                    annotationFor(annotations, parsed, i, true, lastStepEndsAt),
                    step.docString(),
                    step.dataTable()));
        }
        return List.copyOf(steps);
    }

    /**
     * The {@code # expected:} or {@code # data:} value belonging to one step, or null.
     *
     * <p>Ownership is by position in the file: a comment belongs to the <em>last</em> step that
     * begins at or above it. That is what makes the comment survive a block argument — the
     * {@code # expected:} line the renderer writes sits after a doc string or a table, not beside
     * the step line — and it is why the location rather than the ordering is consulted.
     */
    private static String annotationFor(
            List<StepComment> annotations, List<Step> steps, int index, boolean expected,
            int lastStepEndsAt) {

        int from = steps.get(index).getLocation().getLine();
        int until = index + 1 < steps.size()
                ? steps.get(index + 1).getLocation().getLine()
                : lastStepEndsAt;

        return annotations.stream()
                .filter(annotation -> annotation.expected() == expected)
                .filter(annotation -> annotation.line() > from && annotation.line() < until)
                .map(StepComment::text)
                .findFirst()
                .orElse(null);
    }

    /** One {@code # expected:} / {@code # data:} comment, with its continuation lines joined. */
    private record StepComment(int line, boolean expected, String text) {
    }

    private static List<StepComment> stepComments(List<Comment> comments) {
        List<StepComment> found = new ArrayList<>();
        Boolean expected = null;
        StringBuilder text = new StringBuilder();
        int line = 0;
        int previous = 0;

        for (Comment comment : comments) {
            String content = comment.getText().trim();
            int at = comment.getLocation().getLine();
            boolean isExpected = content.startsWith(EXPECTED_PREFIX);
            boolean isData = content.startsWith(DATA_PREFIX);

            if (isExpected || isData) {
                flush(found, expected, line, text);
                expected = isExpected;
                line = at;
                text.setLength(0);
                text.append(content.substring(
                        (isExpected ? EXPECTED_PREFIX : DATA_PREFIX).length()).trim());
            } else if (expected != null && at == previous + 1
                    && comment.getText().stripLeading().startsWith(CONTINUATION_PREFIX)) {
                text.append('\n').append(content.substring(1).trim());
            } else {
                flush(found, expected, line, text);
                expected = null;
            }
            previous = at;
        }
        flush(found, expected, line, text);
        return List.copyOf(found);
    }

    private static void flush(
            List<StepComment> found, Boolean expected, int line, StringBuilder text) {

        if (expected != null) {
            found.add(new StepComment(line, expected, text.toString()));
        }
    }

    private static CaseUpdateRequest.StepInput toInput(GherkinDialect dialect, Step step) {
        return new CaseUpdateRequest.StepInput(
                null,
                keywordOf(dialect, step.getKeyword()),
                step.getText(),
                null,
                null,
                step.getDocString().map(docString -> docString.getContent()).orElse(null),
                step.getDataTable().map(GherkinScenarios::cells).orElse(null));
    }

    private static List<List<String>> cells(DataTable table) {
        List<List<String>> rows = new ArrayList<>(table.getRows().size());
        for (TableRow row : table.getRows()) {
            rows.add(row.getCells().stream().map(TableCell::getValue).toList());
        }
        return List.copyOf(rows);
    }

    /**
     * The keyword as this product spells it, or null.
     *
     * <p>{@code *} is checked first and answers null, which is the whole reason this is a cascade
     * rather than a lookup: the dialect lists {@code *} under <em>every</em> keyword, so a
     * membership test in any other order classifies a format-neutral step as Given.
     *
     * <p>The keyword is bound as a {@code String} on the way to {@link CaseEdit} — an unrecognised
     * one is that class's field error, not an exception here, so a paste with a keyword this
     * dialect does not know still reaches the editor with the rest of its rows intact.
     */
    private static String keywordOf(GherkinDialect dialect, String raw) {
        String keyword = raw == null ? "" : raw.trim();
        if (keyword.isEmpty() || "*".equals(keyword)) {
            return null;
        }
        if (matches(dialect.getAndKeywords(), keyword)) {
            return StepKeyword.AND.name();
        }
        if (matches(dialect.getButKeywords(), keyword)) {
            return StepKeyword.BUT.name();
        }
        if (matches(dialect.getGivenKeywords(), keyword)) {
            return StepKeyword.GIVEN.name();
        }
        if (matches(dialect.getWhenKeywords(), keyword)) {
            return StepKeyword.WHEN.name();
        }
        if (matches(dialect.getThenKeywords(), keyword)) {
            return StepKeyword.THEN.name();
        }
        return null;
    }

    // =====================================================================
    // render
    // =====================================================================

    /**
     * One case as canonical {@code .feature} text.
     *
     * <p>A case with <strong>no step rows and a stored scenario</strong> renders that blob
     * verbatim. It is the un-migrated population §255 left behind, and the alternative — an empty
     * {@code Scenario:} — would tell the reader the case has no procedure when the product is
     * still showing them one.
     */
    static String render(TestCaseDetailView view) {
        if (view.steps().isEmpty() && view.gherkinSource() != null) {
            return view.gherkinSource();
        }
        return document(
                // The suite name is the Feature, because that is the grouping a Cucumber report
                // keys on (`<Feature name>#<Scenario name>`). An unfiled case has no suite and
                // falls back to its own key, which is at least stable and unique.
                view.suiteName() != null ? view.suiteName() : view.key(),
                view.automationTags(),
                bodies(view.preconditions()),
                view.title(),
                view.objective(),
                view.steps().stream().map(GherkinScenarios::toInput).toList(),
                bodies(view.postconditions()));
    }

    /** The same render over a parse result — what makes the round trip assertable. */
    static String render(GherkinParseResult parsed, String featureName) {
        return document(
                featureName,
                parsed.tags(),
                parsed.background().stream()
                        .map(GherkinParseResult.ParsedBackground::body)
                        .toList(),
                parsed.title(),
                parsed.objective(),
                parsed.steps(),
                List.of());
    }

    private static String document(
            String featureName,
            List<String> tags,
            List<List<String>> background,
            String title,
            String objective,
            List<CaseUpdateRequest.StepInput> steps,
            List<List<String>> postconditions) {

        StringBuilder out = new StringBuilder();
        if (!tags.isEmpty()) {
            out.append(tags.stream().map(tag -> "@" + tag).collect(Collectors.joining(" ")))
                    .append('\n');
        }
        out.append("Feature: ").append(featureName == null ? "" : featureName).append('\n');

        if (!background.isEmpty()) {
            out.append("\n  Background:\n");
            for (List<String> body : background) {
                for (String line : body) {
                    out.append(STEP_INDENT).append(line).append('\n');
                }
            }
        }

        out.append("\n  Scenario:");
        if (title != null && !title.isBlank()) {
            out.append(' ').append(title);
        }
        out.append('\n');
        if (objective != null && !objective.isBlank()) {
            for (String line : objective.split("\n", -1)) {
                out.append(STEP_INDENT).append(line).append('\n');
            }
        }

        for (CaseUpdateRequest.StepInput step : steps) {
            for (String line : stepLines(step)) {
                out.append(STEP_INDENT).append(line).append('\n');
            }
            annotation(out, EXPECTED_PREFIX, step.expectedResult());
            annotation(out, DATA_PREFIX, step.testData());
        }

        if (!postconditions.isEmpty()) {
            // Gherkin has no postcondition construct, so they leave as comments — which means the
            // parser reads them back as nothing, deliberately. A case's POST conditions are not
            // steps and must not return as rows; the block exists so the text is not a lossy view
            // of the case for a human reading it.
            out.append('\n').append(STEP_INDENT).append(POSTCONDITIONS_HEADER).append('\n');
            for (List<String> body : postconditions) {
                for (String line : body) {
                    out.append(STEP_INDENT).append(CONTINUATION_PREFIX).append(line).append('\n');
                }
            }
        }
        return out.toString();
    }

    /**
     * One step's lines, indented relative to the step rather than to the file.
     *
     * <p>The same list is a scenario step (indented once more by the caller) and a Background
     * step's condition body, which is what keeps a parsed Background and a rendered one the same
     * text — one shape, rendered by one method, rather than two that agree until somebody edits
     * one of them.
     */
    private static List<String> stepLines(CaseUpdateRequest.StepInput step) {
        List<String> lines = new ArrayList<>();
        String keyword = step.keyword() == null
                ? "*"
                : step.keyword().charAt(0) + step.keyword().substring(1).toLowerCase(Locale.ROOT);
        lines.add(keyword + " " + (step.action() == null ? "" : step.action()));

        if (step.docString() != null) {
            lines.add(BLOCK_INDENT + "\"\"\"");
            for (String line : step.docString().split("\n", -1)) {
                lines.add(BLOCK_INDENT + line.replace("\"\"\"", "\\\"\\\"\\\""));
            }
            lines.add(BLOCK_INDENT + "\"\"\"");
        }
        if (step.dataTable() != null) {
            for (List<String> row : step.dataTable()) {
                lines.add(BLOCK_INDENT + row.stream()
                        .map(GherkinScenarios::cell)
                        .collect(Collectors.joining(" | ", "| ", " |")));
            }
        }
        return List.copyOf(lines);
    }

    /**
     * One cell, escaped per the Gherkin spec.
     *
     * <p>The backslash goes first and it is not a style point: escaping the pipe before the
     * backslash turns {@code a\b} into {@code a\\b} and then into {@code a\\\\b}, so a cell
     * containing a backslash grows on every round trip.
     */
    private static String cell(String value) {
        return value == null
                ? ""
                : value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n");
    }

    private static void annotation(StringBuilder out, String prefix, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String[] lines = value.split("\n", -1);
        out.append(STEP_INDENT).append(prefix).append(' ').append(lines[0]).append('\n');
        for (int i = 1; i < lines.length; i++) {
            out.append(STEP_INDENT).append(CONTINUATION_PREFIX).append(lines[i]).append('\n');
        }
    }

    private static CaseUpdateRequest.StepInput toInput(TestStepView step) {
        return new CaseUpdateRequest.StepInput(
                step.id(),
                step.keyword() == null ? null : step.keyword().name(),
                step.action(),
                step.testData(),
                step.expectedResult(),
                step.docString(),
                step.dataTable());
    }

    private static List<List<String>> bodies(List<CaseConditionRefView> conditions) {
        return conditions.stream().map(CaseConditionRefView::body).toList();
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /**
     * The text inside a nameless wrapper of its own dialect — a {@code Feature:}, and a
     * {@code Scenario:} as well when the text does not already name one.
     *
     * <p>Reached only for a text that produced no feature at all, so it never buries a
     * {@code Feature:} line. The {@code # language:} header moves to the top because it is only a
     * header on line one; leaving it where it was would make the wrapped text refuse for a reason
     * the author cannot see, since this text is never the one reported on.
     */
    private static String wrap(String source) {
        String language = languageOf(source);
        GherkinDialect dialect = dialectOf(language);

        StringBuilder wrapped = new StringBuilder();
        if (language != null) {
            wrapped.append("# language: ").append(language).append('\n');
        }
        wrapped.append(dialect.getFeatureKeywords().get(0)).append(":\n");
        if (!declaresScenario(dialect, source)) {
            wrapped.append("  ").append(dialect.getScenarioKeywords().get(0)).append(":\n");
        }
        for (String line : source.split("\n", -1)) {
            if (!LANGUAGE_HEADER.matcher(line).matches()) {
                wrapped.append(line).append('\n');
            }
        }
        return wrapped.toString();
    }

    /**
     * Whether the text opens a scenario of its own.
     *
     * <p>Read off the text rather than off the AST because there is no AST — this is only asked
     * about text the parser has already refused, and what it decides is which half of the wrapper
     * to supply. Getting it wrong the other way is what made a pasted {@code Scenario:} block
     * refuse as "two scenarios": its own, plus the one wrapped around it.
     */
    private static boolean declaresScenario(GherkinDialect dialect, String source) {
        List<String> openers = new ArrayList<>(dialect.getScenarioKeywords());
        openers.addAll(dialect.getScenarioOutlineKeywords());
        for (String line : source.split("\n", -1)) {
            String stripped = line.strip();
            for (String opener : openers) {
                if (stripped.startsWith(opener.trim() + ":")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String languageOf(String source) {
        for (String line : source.split("\n", -1)) {
            Matcher header = LANGUAGE_HEADER.matcher(line);
            if (header.matches()) {
                return header.group(1);
            }
            if (!line.isBlank()) {
                return null;
            }
        }
        return null;
    }

    private static GherkinDialect dialectOf(String language) {
        return GherkinDialects.getDialect(language == null ? "en" : language)
                .orElseGet(() -> GherkinDialects.getDialect("en").orElseThrow());
    }

    private static List<Scenario> scenariosOf(Feature feature) {
        List<Scenario> scenarios = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            child.getScenario().ifPresent(scenarios::add);
            // A `Rule:` is flattened rather than refused. This endpoint's accepted shapes do not
            // include one, but a paste that has a Rule around its single scenario is a paste with
            // one scenario in it — reading it as none would answer "No steps found" about text
            // that plainly has steps.
            child.getRule().ifPresent(rule -> {
                for (RuleChild ruleChild : rule.getChildren()) {
                    ruleChild.getScenario().ifPresent(scenarios::add);
                }
            });
        }
        return List.copyOf(scenarios);
    }

    private static List<Background> backgroundsOf(Feature feature) {
        List<Background> backgrounds = new ArrayList<>();
        for (FeatureChild child : feature.getChildren()) {
            child.getBackground().ifPresent(backgrounds::add);
            child.getRule().ifPresent(rule -> {
                for (RuleChild ruleChild : rule.getChildren()) {
                    ruleChild.getBackground().ifPresent(backgrounds::add);
                }
            });
        }
        return List.copyOf(backgrounds);
    }

    /**
     * The refusal as {@code errors.text} carries it — {@code "Line 4, column 3: …"}.
     *
     * <p>The library's own message already opens with {@code (4:3):}, which is a spelling no
     * end user reads. It is stripped and recomposed rather than passed through, so the sentence
     * beside the paste box is the one the SPA can put a line number badge on.
     */
    private static String sentence(ParseError error) {
        String message = error.getMessage();
        int close = message.indexOf("): ");
        if (message.startsWith("(") && close > 0) {
            message = message.substring(close + "): ".length());
        }
        String detail = message;

        Optional<Location> location = error.getSource().getLocation();
        return location
                .map(at -> "Line " + at.getLine()
                        + at.getColumn().map(column -> ", column " + column).orElse("")
                        + ": " + detail)
                .orElse(detail);
    }

    /**
     * A description block with its common leading whitespace removed.
     *
     * <p>Gherkin keeps a description exactly as it sits in the file, indentation included, so a
     * scenario indented two levels would arrive as an objective whose every line starts with four
     * spaces — and the editor would show it that way, and a save would store it that way.
     */
    private static String dedent(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        List<String> lines = List.of(description.split("\n", -1));
        int margin = lines.stream()
                .filter(line -> !line.isBlank())
                .mapToInt(line -> line.length() - line.stripLeading().length())
                .min()
                .orElse(0);
        return lines.stream()
                .map(line -> line.length() < margin ? line.strip() : line.substring(margin))
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static boolean matches(List<String> keywords, String keyword) {
        String trimmed = keyword == null ? "" : keyword.trim();
        return keywords.stream().anyMatch(candidate -> candidate.trim().equals(trimmed));
    }

    private static String withoutAt(String tag) {
        return tag.startsWith("@") ? tag.substring(1) : tag;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
