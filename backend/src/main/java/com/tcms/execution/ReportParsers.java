package com.tcms.execution;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The four parsers, keyed by the format they read — {@code ReportComputations}' registry shape
 * (§115), for its reason: adding a dialect is a bean, not an edit to a switch somebody has to find.
 *
 * <h2>A missing parser fails at STARTUP, not on the request that needs it</h2>
 *
 * <p>{@link ReportFormat}'s values are {@code ingestion_job.format}'s {@code CHECK} values, so the
 * two lists are the same list and a format with no bean is a wiring defect rather than a runtime
 * condition. The constructor asserts every value is covered, which turns "somebody added a fifth
 * format to the enum and the migration and forgot the parser" into a context that will not start —
 * where the alternative is a 500 on the first CI job that posts that dialect, weeks later, in
 * production.
 *
 * <p>The reciprocal check has no home here and does not need one: a bean whose {@link
 * ReportParser#format()} is not in the enum cannot be written, because the enum is the type.
 */
@Component
class ReportParsers {

    private final Map<ReportFormat, ReportParser> byFormat;

    ReportParsers(List<ReportParser> parsers) {
        Map<ReportFormat, ReportParser> registry = new EnumMap<>(ReportFormat.class);
        for (ReportParser parser : parsers) {
            ReportParser clash = registry.put(parser.format(), parser);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two parsers claim " + parser.format() + ": "
                                + clash.getClass().getSimpleName() + " and "
                                + parser.getClass().getSimpleName());
            }
        }
        for (ReportFormat format : ReportFormat.values()) {
            if (!registry.containsKey(format)) {
                throw new IllegalStateException(
                        "No ReportParser for " + format + ". Every value of the enum is a value of "
                                + "ingestion_job.format's CHECK, so this is a missing bean.");
            }
        }
        this.byFormat = Map.copyOf(registry);
    }

    /**
     * Reads the report in the dialect the caller named.
     *
     * @throws ReportXml.MalformedReportException when the payload is not that dialect
     */
    List<IngestedResult> parse(ReportFormat format, String report) {
        return byFormat.get(format).parse(report);
    }
}
