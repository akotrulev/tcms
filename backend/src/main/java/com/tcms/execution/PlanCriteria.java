package com.tcms.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The two JSONB columns this module serves, parsed once at the edge.
 *
 * <p>{@code test_plan.entry_criteria} / {@code exit_criteria} and {@code configuration.attributes}
 * are held as {@code String} on their entities for the reason {@link TestPlan} gives; something has
 * to turn them into wire shapes, and doing it here rather than in each service is what stops two
 * readers disagreeing about what a malformed value means.
 *
 * <p><strong>Malformed is empty, not an exception.</strong> Both columns are {@code JSONB}, so
 * Postgres has already refused anything unparseable — a value that fails here changed shape outside
 * the application. The choice is then between a plan detail that 500s and one that renders without
 * its criteria, and the second is the one that lets somebody look at the plan and see what is wrong
 * with it. The alternative is a screen that cannot be opened to diagnose the row that broke it.
 *
 * <p>The injected {@link ObjectMapper} is the application's configured one, for the reason
 * {@code repository.CaseVersions} gives: a private {@code new ObjectMapper()} is a second
 * serialization configuration, and the two drift the moment either is configured.
 */
@Component
class PlanCriteria {

    private final ObjectMapper json;

    PlanCriteria(ObjectMapper json) {
        this.json = json;
    }

    /**
     * A criteria column as a list.
     *
     * <p>{@code null} is an ordinary value and yields {@code []}: a plan with no exit criteria has
     * not failed to declare them, and the contract's array is required, so the SPA cannot null-check
     * it.
     */
    List<CriterionView> parse(String criteria) {
        if (criteria == null || criteria.isBlank()) {
            return List.of();
        }
        JsonNode root = read(criteria);
        List<CriterionView> parsed = new ArrayList<>();
        for (JsonNode entry : root) {
            String metric = text(entry, "metric");
            String op = text(entry, "op");
            if (metric == null || op == null) {
                // A predicate missing its metric or its operator cannot be evaluated and cannot be
                // displayed as a gate. Skipped rather than rendered with blanks, which would put an
                // empty criterion on a page whose whole purpose is that criteria are checkable.
                continue;
            }
            parsed.add(new CriterionView(
                    metric,
                    op,
                    entry.path("value").asDouble(),
                    // Falling back to the predicate itself keeps the label non-null without
                    // inventing prose: "pass_rate >= 95" is what the criterion says.
                    text(entry, "label") != null
                            ? text(entry, "label")
                            : metric + " " + op + " " + entry.path("value").asDouble(),
                    // Never evaluated in this slice — see CriterionView#met.
                    null,
                    null));
        }
        return List.copyOf(parsed);
    }

    /**
     * The same criteria, with {@code met} and {@code actual} filled in for every metric
     * {@code actuals} can answer.
     *
     * <p><strong>A metric with no actual keeps both fields null, and null means "not evaluated" —
     * which is not "unmet".</strong> Of the five metrics plans are written with, this endpoint's own
     * numbers support two: {@code pass_rate} and {@code coverage}. {@code open_critical} needs defect
     * severities from an issue tracker (the {@code IssueTracker} port is M4), {@code cases_approved}
     * needs the review workflow, and {@code build_available} is modelled nowhere in the product.
     * Returning {@code false} for those would say the gate had been checked and failed, and a release
     * meeting would be reading a red cross that means "we did not look".
     *
     * <p>This is the field {@link CriterionView#met} was declared nullable for, and M2 slice 1 left
     * null on every criterion because there was no statistics endpoint to evaluate one. Now some are
     * answerable and some still are not, so the nullability finally does the work it was declared
     * for rather than standing in for an unimplemented endpoint.
     */
    List<CriterionView> evaluate(List<CriterionView> criteria, Map<String, Double> actuals) {
        List<CriterionView> evaluated = new ArrayList<>(criteria.size());
        for (CriterionView criterion : criteria) {
            Double actual = actuals.get(criterion.metric());
            evaluated.add(actual == null
                    ? criterion
                    : new CriterionView(
                            criterion.metric(),
                            criterion.op(),
                            criterion.value(),
                            criterion.label(),
                            satisfies(actual, criterion.op(), criterion.value()),
                            // Rounded to one decimal on the way out, not in the comparison above: a
                            // gate at ">= 95" must not be met by a 94.96 that rounds to 95.0 on the
                            // screen beside it. The displayed number is the approximation; the
                            // verdict is not.
                            Math.round(actual * 10.0) / 10.0));
        }
        return List.copyOf(evaluated);
    }

    /**
     * @return null when the operator is not one of the six, so an unrecognised predicate reads as
     *         "not evaluated" rather than as failed. A criterion authored with {@code =>} instead of
     *         {@code >=} is a typo in the plan, and rendering it as a blocking red gate would send
     *         someone looking for a defect that is really a text field.
     */
    private static Boolean satisfies(double actual, String op, double target) {
        return switch (op) {
            case ">=" -> actual >= target;
            case ">" -> actual > target;
            case "<=" -> actual <= target;
            case "<" -> actual < target;
            // Tolerance rather than ==, because both sides are doubles and the left one is usually a
            // division: "coverage = 100" must be met by 100.00000000000001, which is what
            // 305/305*100 can produce. The tolerance is far below one display decimal, so nothing
            // visibly short of the target can pass.
            case "=", "==" -> Math.abs(actual - target) < 1e-9;
            case "!=", "<>" -> Math.abs(actual - target) >= 1e-9;
            default -> null;
        };
    }

    /** A configuration's attribute map. Values are coerced to text: the contract declares strings. */
    Map<String, String> attributes(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return Map.of();
        }
        JsonNode root = read(attributes);
        Map<String, String> parsed = new LinkedHashMap<>();
        // properties(), not the deprecated fields(): Jackson 2.19 deprecated the iterator form in
        // favour of the Set<Entry> view, and this project compiles with -Werror.
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            if (!field.getValue().isNull()) {
                parsed.put(field.getKey(), field.getValue().asText());
            }
        }
        return Map.copyOf(parsed);
    }

    private JsonNode read(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException malformed) {
            return json.createArrayNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
