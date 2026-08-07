package com.tcms.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Allure's result objects, submitted as a JSON array.
 *
 * <pre>{@code
 * [{"name":"cancels an order","fullName":"com.x.OrderTest.cancels","status":"failed",
 *   "start":1690000000000,"stop":1690000000420,
 *   "statusDetails":{"message":"expected 3","trace":"…"},
 *   "labels":[{"name":"testClass","value":"com.x.OrderTest"}]}]
 * }</pre>
 *
 * <h2>Allure writes one file per test, and this endpoint takes one payload</h2>
 *
 * <p>An {@code allure-results/} directory is N files, so a CI job concatenates them into an array
 * before posting. A single object is accepted too, because that is what somebody testing the
 * endpoint by hand will send and refusing it teaches nothing. Both shapes, one branch — see
 * {@link #resultsIn}.
 *
 * <h2>{@code fullName} is Allure's own and it is NOT {@link IngestedResult#fullName()}</h2>
 *
 * <p>Allure joins with a dot ({@code com.x.OrderTest.cancels}); §6c's {@code automationKey} is
 * {@code FQCN#method}. Passing Allure's string through as the joined name would make the same test
 * normalize differently depending on which runner reported it, so the class is taken from the
 * {@code testClass} label and the join is left to {@link IngestedResult#fullName()} — the one place
 * that separator is decided.
 *
 * <p>The label, and not a split of {@code fullName} on its last dot: a parameterised test is
 * {@code com.x.OrderTest.cancels[EUR, 3]}, a Kotlin test name contains spaces and dots, and the
 * split silently produces a class of {@code com.x.OrderTest.cancels[EUR,} on both.
 *
 * <h2>Duration is a subtraction, and the two fields are both optional</h2>
 *
 * <p>{@code start} and {@code stop} are epoch millis; either may be absent on a test that never
 * ran. Computed only when both are present — a stop with no start would otherwise read as a test
 * that took fifty-four years.
 */
@Component
class AllureReportParser implements ReportParser {

    private final ObjectMapper json;

    AllureReportParser(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.ALLURE;
    }

    @Override
    public List<IngestedResult> parse(String report) {
        JsonNode root = ReportJson.parse(json, report);
        List<JsonNode> nodes = resultsIn(root);

        List<IngestedResult> results = new ArrayList<>();
        for (JsonNode node : nodes) {
            String name = ReportJson.text(node, "name");
            if (name == null) {
                name = ReportJson.text(node, "fullName");
            }
            if (name == null) {
                continue;
            }
            results.add(new IngestedResult(
                    label(node, "testClass"),
                    name,
                    outcomeOf(ReportJson.text(node, "status")),
                    duration(node),
                    message(node)));
        }
        return results;
    }

    /** An array of results, a single result object, or a refusal. */
    private static List<JsonNode> resultsIn(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
            return nodes;
        }
        if (root.isObject() && (root.has("name") || root.has("fullName") || root.has("uuid"))) {
            nodes.add(root);
            return nodes;
        }
        throw new ReportXml.MalformedReportException(
                "That does not look like an Allure report — expected an array of result objects.",
                null);
    }

    private static IngestedOutcome outcomeOf(String status) {
        if (status == null) {
            return IngestedOutcome.ERRORED;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "passed" -> IngestedOutcome.PASSED;
            // Allure's own split, and it lines up with JUnit's exactly: "failed" is an assertion,
            // "broken" is an exception nobody expected.
            case "failed" -> IngestedOutcome.FAILED;
            case "broken" -> IngestedOutcome.ERRORED;
            case "skipped" -> IngestedOutcome.SKIPPED;
            // "unknown" is a real Allure status and it is not a pass. Same rule as TestNG's
            // default arm — an outcome nobody understood is never a green tick.
            default -> IngestedOutcome.ERRORED;
        };
    }

    private static String label(JsonNode node, String name) {
        JsonNode labels = node.path("labels");
        if (!labels.isArray()) {
            return null;
        }
        for (JsonNode label : labels) {
            if (name.equals(ReportJson.text(label, "name"))) {
                return ReportJson.text(label, "value");
            }
        }
        return null;
    }

    private static Long duration(JsonNode node) {
        Long start = ReportJson.number(node, "start");
        Long stop = ReportJson.number(node, "stop");
        return start == null || stop == null || stop < start ? null : stop - start;
    }

    private static String message(JsonNode node) {
        JsonNode details = node.path("statusDetails");
        if (!details.isObject()) {
            return null;
        }
        String message = ReportJson.text(details, "message");
        String trace = ReportJson.text(details, "trace");
        if (message == null) {
            return trace;
        }
        return trace == null ? message : message + "\n" + trace;
    }
}
