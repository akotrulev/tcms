package com.tcms.reporting;

import jakarta.persistence.Query;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an aggregate execution report was asked to cover — §6e's <em>selection</em>, parsed once and
 * compiled to one set of predicates over {@code execution_fact}.
 *
 * <h2>Why the selection is a type and not seven parameters threaded through five queries</h2>
 *
 * <p>{@link AggregateExecutionReport} runs several statements over the same selection — headline
 * totals, the status breakdown, failures by suite, failures by feature. Every one of them must
 * narrow <strong>identically</strong>, because the sections are read together and a table whose rows
 * do not sum to the headline above them is the defect this whole milestone is written against. One
 * object that emits one {@code WHERE} fragment is what makes that structural instead of a thing four
 * queries have to remember.
 *
 * <h2>An arm is appended only when it has members — §99's defect, from the SQL side</h2>
 *
 * <p>{@code IN ()} is a syntax error, and the shape that survives compilation — {@code IN (NULL)} —
 * is <strong>never true</strong> and reads like an arm that matched. §99 paid for exactly this with
 * a run scoped by a suite that filed nothing. So {@link #where()} appends a clause only for a facet
 * the caller actually sent, and a selection with no facets at all is the whole project, which is the
 * correct reading of "I did not narrow on anything".
 *
 * <p>The corollary is {@link ReportParameters#idList}'s: <em>absent</em> and <em>sent empty</em> are
 * one answer, because the other reading turns {@code {"planIds": []}} into a clean report of nothing.
 *
 * <h2>The date range excludes rows with no timestamp, and that is a semantic rather than a bug</h2>
 *
 * <p>{@code execution_fact.executed_at} is nullable — a result recorded with no time carries none —
 * so any bound at all drops those rows. That is what a date range means; the alternative (keep
 * undated rows inside every range) makes the same execution appear in two adjacent monthly reports.
 * It is stated in the header's selection block rather than left to be inferred from a total.
 */
record AggregateSelection(
        AggregationPolicy policy,
        List<UUID> planIds,
        List<UUID> configurationIds,
        List<UUID> featureIds,
        List<String> suitePaths,
        List<String> automationStatuses,
        List<String> sources,
        Instant from,
        Instant to) {

    /**
     * Reads the selection off the job's {@code parameters}.
     *
     * @throws IllegalArgumentException for an unrecognised policy or a malformed facet.
     *     <p><strong>These surface as a {@code FAILED} job carrying the sentence, not as a 400</strong>
     *     — corrected in R6 phase 3, where the first draft of this line claimed the opposite.
     *     {@link ReportJobService} validates the type, the format and the JSON <em>parse</em> at
     *     request time and deliberately leaves each report's own parameters to the worker, because
     *     the alternative is a second copy of every report's rules on the request path (§58's E1).
     *     That is a defensible trade and it has a cost this class cannot pay off on its own: the
     *     refusal arrives after the dialog has closed. Which is why {@code GenerateReportDialog}
     *     makes the two reachable mistakes unexpressible — the policy is a picker, and the date
     *     order is checked before the request — rather than relying on these sentences being read.
     */
    static AggregateSelection parse(ReportParameters parameters) {
        String requested = parameters.optionalText("policy").orElse(null);
        AggregationPolicy policy = requested == null
                ? AggregationPolicy.DEFAULT
                : AggregationPolicy.parse(requested).orElseThrow(() -> new IllegalArgumentException(
                        "'" + requested + "' is not an aggregation policy. Use one of: "
                                + AggregationPolicy.accepted()));

        Instant from = parameters.optionalInstant("from").orElse(null);
        Instant to = parameters.optionalInstant("to").orElse(null);
        if (from != null && to != null && to.isBefore(from)) {
            // Refused rather than swapped. Swapping guesses at an intent, and the report that comes
            // back would carry a header saying the range was what the caller typed while the numbers
            // came from a different one.
            throw new IllegalArgumentException("'to' is before 'from', so the range covers nothing");
        }

        return new AggregateSelection(
                policy,
                parameters.idList("planIds"),
                parameters.idList("configurationIds"),
                parameters.idList("featureIds"),
                parameters.textList("suitePaths"),
                parameters.textList("automationStatuses"),
                parameters.textList("sources"),
                from,
                to);
    }

    /**
     * The {@code WHERE} fragment, without the leading keyword and always beginning with {@code AND}
     * so a caller can concatenate it after its own project predicate.
     *
     * <p>Every value is a <strong>named bind</strong> and nothing is interpolated, including the
     * ltree prefixes — a suite path is caller-supplied text and this is the one facet whose natural
     * spelling is string concatenation.
     */
    String where() {
        StringBuilder sql = new StringBuilder();

        if (!planIds.isEmpty()) {
            // Note this is an inner-list membership test on a NULLABLE column: `plan_id` is null for
            // a run created outside any plan, and such a row is correctly excluded when the caller
            // named plans.
            sql.append(" AND f.plan_id IN (:planIds)");
        }
        if (!configurationIds.isEmpty()) {
            sql.append(" AND f.configuration_id IN (:configurationIds)");
        }
        if (!automationStatuses.isEmpty()) {
            sql.append(" AND f.automation_status IN (:automationStatuses)");
        }
        if (!sources.isEmpty()) {
            sql.append(" AND f.source IN (:sources)");
        }
        if (!featureIds.isEmpty()) {
            // Array overlap, so the GIN index on feature_ids serves it. `&&` is OR across the
            // requested features — a case classified under Payments OR Refunds — which is the same
            // reading `?tag=` gets nowhere else in the product and the same one a suite's tag rules
            // get everywhere: a facet list ASSEMBLES a selection. Intersection would mean "cases
            // classified under all of these at once", which no screen offers and which silently
            // returns nothing for the ordinary two-feature request.
            sql.append(" AND f.feature_ids && ").append(uuidArrayLiteral("feature", featureIds));
        }
        if (!suitePaths.isEmpty()) {
            // `<@` is ltree containment — descendant-or-self — so naming a suite covers everything
            // filed beneath it in the tree AS IT STOOD when each result was recorded. suite_path is
            // frozen history (ExecutionSource), and it outlived `suite.path` itself (§98/V33), so a
            // report spanning the flattening matches deep paths for old runs and single labels for
            // new ones. GIST index: execution_fact_suite_idx.
            sql.append(" AND (");
            for (int i = 0; i < suitePaths.size(); i++) {
                if (i > 0) {
                    sql.append(" OR ");
                }
                sql.append("f.suite_path <@ CAST(:suitePath").append(i).append(" AS ltree)");
            }
            sql.append(')');
        }
        if (from != null) {
            sql.append(" AND f.executed_at >= :from");
        }
        if (to != null) {
            // Half-open. A caller asking for August sends 2026-09-01T00:00:00Z as `to` and gets
            // August; an inclusive upper bound would include or exclude the boundary instant
            // depending on sub-second precision, so two adjacent reports would double-count or drop
            // whatever landed exactly on midnight.
            sql.append(" AND f.executed_at < :to");
        }
        return sql.toString();
    }

    /** Binds every value {@link #where()} named. Must be called for every query built from it. */
    void bind(Query query) {
        if (!planIds.isEmpty()) {
            query.setParameter("planIds", planIds);
        }
        if (!configurationIds.isEmpty()) {
            query.setParameter("configurationIds", configurationIds);
        }
        if (!automationStatuses.isEmpty()) {
            query.setParameter("automationStatuses", automationStatuses);
        }
        if (!sources.isEmpty()) {
            query.setParameter("sources", sources);
        }
        for (int i = 0; i < featureIds.size(); i++) {
            query.setParameter("feature" + i, featureIds.get(i));
        }
        for (int i = 0; i < suitePaths.size(); i++) {
            query.setParameter("suitePath" + i, suitePaths.get(i));
        }
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
    }

    /** Whether the caller narrowed on anything at all — the header prints "everything" when not. */
    boolean isWholeProject() {
        return planIds.isEmpty()
                && configurationIds.isEmpty()
                && featureIds.isEmpty()
                && suitePaths.isEmpty()
                && automationStatuses.isEmpty()
                && sources.isEmpty()
                && from == null
                && to == null;
    }

    /**
     * What the report prints to say what it covered, in a fixed order.
     *
     * <p>This is deliberately <em>not</em> {@link ReportParameters#entries()}, which prints what the
     * client sent. Both exist and they say different things: the header's selection block is the
     * request verbatim (a parameter this report ignored still appears there, which is
     * {@link ReportHeaders}' whole argument), and this is what this report actually narrowed on. A
     * reader comparing the two can see a facet that was sent and not understood.
     */
    List<ReportModel.Entry> applied() {
        List<ReportModel.Entry> entries = new ArrayList<>();
        entries.add(new ReportModel.Entry("Aggregation policy", policy.wireValue()));
        if (isWholeProject()) {
            entries.add(new ReportModel.Entry(
                    "Selection", "Every recorded execution in this project"));
            return List.copyOf(entries);
        }
        addCount(entries, "Plans", planIds.size());
        addCount(entries, "Configurations", configurationIds.size());
        addCount(entries, "Features", featureIds.size());
        if (!suitePaths.isEmpty()) {
            entries.add(new ReportModel.Entry("Suites", String.join(", ", suitePaths)));
        }
        if (!automationStatuses.isEmpty()) {
            entries.add(new ReportModel.Entry(
                    "Automation status", String.join(", ", automationStatuses)));
        }
        if (!sources.isEmpty()) {
            entries.add(new ReportModel.Entry("Source", String.join(", ", sources)));
        }
        if (from != null || to != null) {
            entries.add(new ReportModel.Entry("Executed between",
                    (from == null ? "the beginning" : from.toString())
                            + " and " + (to == null ? "now" : to.toString())
                            + " (upper bound excluded)"));
        }
        return List.copyOf(entries);
    }

    private static void addCount(List<ReportModel.Entry> entries, String label, int size) {
        if (size > 0) {
            entries.add(new ReportModel.Entry(label, size + " selected"));
        }
    }

    /**
     * {@code ARRAY[:feature0, :feature1]::uuid[]} — placeholders only, never values.
     *
     * <p>Hibernate cannot bind a Java {@code List} to a native-SQL array position, so the literal is
     * assembled from the list's <em>size</em> and each element is still a named parameter.
     */
    private static String uuidArrayLiteral(String prefix, List<UUID> values) {
        StringBuilder out = new StringBuilder("ARRAY[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(':').append(prefix).append(i);
        }
        return out.append("]::uuid[]").toString();
    }

    /** Named binds in a stable order, for the tests that assert the fragment and its parameters. */
    Map<String, Object> boundValues() {
        Map<String, Object> bound = new LinkedHashMap<>();
        if (!planIds.isEmpty()) {
            bound.put("planIds", planIds);
        }
        if (!configurationIds.isEmpty()) {
            bound.put("configurationIds", configurationIds);
        }
        if (!automationStatuses.isEmpty()) {
            bound.put("automationStatuses", automationStatuses);
        }
        if (!sources.isEmpty()) {
            bound.put("sources", sources);
        }
        for (int i = 0; i < featureIds.size(); i++) {
            bound.put("feature" + i, featureIds.get(i));
        }
        for (int i = 0; i < suitePaths.size(); i++) {
            bound.put("suitePath" + i, suitePaths.get(i));
        }
        if (from != null) {
            bound.put("from", from);
        }
        if (to != null) {
            bound.put("to", to);
        }
        return Map.copyOf(bound);
    }
}
