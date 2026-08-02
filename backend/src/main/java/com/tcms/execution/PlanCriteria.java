package com.tcms.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * The metrics a plan may be gated on — <strong>a closed set, checked on the way in since the
     * editor exists</strong>.
     *
     * <p>Two of the five are answerable from {@link PlanStatisticsService}'s own numbers today
     * ({@code pass_rate}, {@code coverage}); the other three need machinery the product does not
     * have — {@code open_critical} the M4 issue tracker, {@code cases_approved} the review workflow,
     * {@code build_available} nothing at all — and {@link #evaluate} leaves them "not evaluated" on
     * purpose.
     *
     * <p><strong>Which is exactly why the input is validated against this list.</strong> Unchecked,
     * a plan gated on {@code pass_rte} renders identically to one gated on {@code open_critical}:
     * both show a criterion with no verdict. One of those is "we cannot check this yet" and the
     * other is a typo that will never be checked at all, and a release meeting cannot tell them
     * apart by looking. A field error at authoring time is the only moment the difference is visible.
     *
     * <p>Adding a metric here is a deliberate act with a consequence — it becomes authorable
     * immediately and evaluable only when {@link PlanStatisticsService} supplies an actual for it.
     */
    static final Set<String> METRICS = Set.of(
            "pass_rate", "coverage", "open_critical", "cases_approved", "build_available");

    private final ObjectMapper json;

    PlanCriteria(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Whether {@link #satisfies} can apply this operator — asked by the validator, answered by the
     * evaluator itself.
     *
     * <p>Deliberately implemented by <em>calling</em> {@code satisfies} rather than by listing the
     * six operators a second time. A set beside the switch is the hand-maintained copy §58's E1 and
     * §70's verb list are both about: it would be correct on the day it was written and silently
     * wrong the first time somebody adds an operator to one of the two. The arguments are zeros
     * because only the operator is under test; every arm is total over the reals.
     */
    static boolean isKnownOperator(String op) {
        return op != null && satisfies(0d, op, 0d) != null;
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

    /**
     * A validated criteria list as the column stores it — <strong>the first writer either JSONB
     * column has ever had</strong>.
     *
     * <p>Written by the same class that reads them, so the shape {@link #parse} expects and the
     * shape stored cannot drift: they are one file, and a change to either is a change to a
     * neighbour twenty lines away rather than to a module somebody has to remember.
     *
     * <p>{@code null} for a list that was never given rather than {@code "[]"}, keeping the
     * distinction {@code TestPlan.create} deliberately preserved — <em>nobody has decided</em>
     * versus <em>there are deliberately none</em>. Both render as {@code []} on the wire, which is
     * correct: the difference is authoring history and not something a plan page can act on.
     */
    String serialize(List<CriterionInput> criteria) {
        if (criteria == null) {
            return null;
        }
        try {
            return json.writeValueAsString(criteria);
        } catch (JsonProcessingException cannotSerialize) {
            // Unreachable: a validated CriterionInput is four scalars. Thrown rather than swallowed
            // for CaseVersions' reason — a write that silently stored nothing is worse than a 500,
            // because the author watched the save succeed.
            throw new IllegalStateException("A CriterionInput must be serializable", cannotSerialize);
        }
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
