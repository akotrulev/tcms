package com.tcms.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Cucumber's JSON formatter — features containing elements containing steps.
 *
 * <pre>{@code
 * [{"uri":"features/orders.feature","name":"Orders","elements":[
 *   {"type":"scenario","name":"Cancel an order","line":12,"steps":[
 *     {"keyword":"When ","name":"I cancel","result":{"status":"failed","duration":420000000,
 *      "error_message":"expected 3"}}]}]}]
 * }</pre>
 *
 * <h2>A scenario has no status of its own — it is the worst of its steps</h2>
 *
 * <p>This is the whole difficulty of the dialect. Cucumber records a result per <em>step</em>, and
 * what TCMS records is a verdict per <em>case</em>, so the scenario's outcome has to be derived. The
 * rule is severity: any failed step fails the scenario, then errored, then a scenario every one of
 * whose steps was skipped or undefined is skipped, and only an all-passed scenario passes.
 *
 * <p>Written as "take the last step's status" — which is tempting, because the last step is usually
 * the assertion — a scenario that fails in the middle reads as <strong>passed</strong>, because
 * Cucumber marks every step after a failure as {@code skipped} and the last one is therefore not
 * failed. That is the all-green direction, on the format most likely to be used for acceptance
 * tests.
 *
 * <p>{@code undefined} and {@code pending} are steps with no implementation. They are
 * {@link IngestedOutcome#ERRORED} rather than skipped: a scenario nobody wrote the glue for did not
 * decline to run, it could not run, and reporting it as a skip hides an incomplete suite behind a
 * status that reads as a deliberate choice.
 *
 * <h2>Background steps and the {@code type} field</h2>
 *
 * <p>An {@code element} may be a {@code background} rather than a {@code scenario}. Backgrounds are
 * not tests and are skipped — the same rule {@link TestNgReportParser} applies to {@code is-config},
 * and the same defect if missed: one extra "test" per feature file, failing whenever setup does.
 *
 * <h2>The feature name is the class, and the scenario name is the test</h2>
 *
 * <p>So {@code fullName()} is {@code Orders#Cancel an order}. Two scenarios in different features
 * may share a name — that is normal Gherkin — and without the feature the two would normalize to
 * one learned mapping, binding both to whichever case a human confirmed first.
 */
@Component
class CucumberReportParser implements ReportParser {

    private final ObjectMapper json;

    CucumberReportParser(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.CUCUMBER;
    }

    @Override
    public List<IngestedResult> parse(String report) {
        JsonNode root = ReportJson.parse(json, report);
        if (!root.isArray()) {
            throw new ReportXml.MalformedReportException(
                    "That does not look like a Cucumber report — expected an array of features.",
                    null);
        }

        List<IngestedResult> results = new ArrayList<>();
        for (JsonNode feature : root) {
            String featureName = ReportJson.text(feature, "name");
            if (featureName == null) {
                featureName = ReportJson.text(feature, "uri");
            }
            for (JsonNode element : feature.path("elements")) {
                String type = ReportJson.text(element, "type");
                if (type != null && !"scenario".equalsIgnoreCase(type)) {
                    continue;
                }
                String name = ReportJson.text(element, "name");
                if (name == null) {
                    continue;
                }
                Scenario scenario = walk(element);
                results.add(new IngestedResult(
                        featureName, name, scenario.outcome(), scenario.durationMs(),
                        scenario.message()));
            }
        }
        return results;
    }

    private record Scenario(IngestedOutcome outcome, Long durationMs, String message) {
    }

    /**
     * The scenario's outcome, total duration and first failure message, in one pass over its steps.
     *
     * <p>A scenario with no steps at all is {@link IngestedOutcome#SKIPPED}: it ran nothing, and
     * calling it passed would credit a green tick to an empty scenario — the same all-green
     * direction the class header warns about, from the other end.
     */
    private static Scenario walk(JsonNode element) {
        IngestedOutcome worst = null;
        String message = null;
        long nanos = 0;
        boolean anyDuration = false;
        boolean anyStep = false;

        for (JsonNode step : element.path("steps")) {
            anyStep = true;
            JsonNode result = step.path("result");
            IngestedOutcome outcome = outcomeOf(ReportJson.text(result, "status"));
            if (worst == null || severity(outcome) > severity(worst)) {
                worst = outcome;
            }
            if (message == null) {
                String error = ReportJson.text(result, "error_message");
                if (error != null) {
                    // The FIRST error, not the last: Cucumber marks everything after a failure as
                    // skipped, so a later message would come from teardown rather than from the
                    // step that actually broke.
                    message = keyword(step) + error;
                }
            }
            Long duration = ReportJson.number(result, "duration");
            if (duration != null) {
                anyDuration = true;
                nanos += duration;
            }
        }

        if (!anyStep) {
            return new Scenario(IngestedOutcome.SKIPPED, null, null);
        }
        // Cucumber's step duration is NANOseconds, where Allure's is millis and JUnit's is decimal
        // seconds. Three formats, three units, and every one of them is called "duration".
        return new Scenario(worst, anyDuration ? nanos / 1_000_000 : null, message);
    }

    private static String keyword(JsonNode step) {
        String keyword = ReportJson.text(step, "keyword");
        String name = ReportJson.text(step, "name");
        if (keyword == null && name == null) {
            return "";
        }
        return ((keyword == null ? "" : keyword.trim() + " ") + (name == null ? "" : name)).trim()
                + ": ";
    }

    private static IngestedOutcome outcomeOf(String status) {
        if (status == null) {
            return IngestedOutcome.ERRORED;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "passed" -> IngestedOutcome.PASSED;
            case "failed" -> IngestedOutcome.FAILED;
            case "skipped" -> IngestedOutcome.SKIPPED;
            // See the class header: no glue is not a decision not to run.
            case "undefined", "pending", "ambiguous" -> IngestedOutcome.ERRORED;
            default -> IngestedOutcome.ERRORED;
        };
    }

    /** Failed beats errored beats skipped beats passed — {@link #walk}'s ordering. */
    private static int severity(IngestedOutcome outcome) {
        return switch (outcome) {
            case FAILED -> 3;
            case ERRORED -> 2;
            case SKIPPED -> 1;
            case PASSED -> 0;
        };
    }
}
