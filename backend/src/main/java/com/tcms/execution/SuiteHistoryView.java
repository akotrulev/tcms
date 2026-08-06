package com.tcms.execution;

import com.tcms.execution.CaseHistoryView.MarkerView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code SuiteHistoryResponse} in {@code docs/specs/openapi.yaml} — one suite's pass rate, run by
 * run (plan §6p).
 *
 * <p>The field is {@code entries}, and that spelling is the slice this record was written in. The
 * SPA had typed its own {@code { runs, versionMarkers }} literal at the call site since Phase 1
 * while the contract said {@code entries}; nothing compared them, because a hand-written type
 * argument is generated from nothing. Naming the response schema is what put the compiler between
 * the two (§52).
 *
 * @param versionMarkers reuses {@link MarkerView} rather than declaring a twin, because the contract
 *                       reuses {@code VersionMarker}: a marker is a marker, and a second record with
 *                       the same four components is how one of them acquires a field the other does
 *                       not serialize.
 * @param computedAt     when the {@code suite_run_summary} rollup behind these entries was last
 *                       written, or <strong>null when the answer was computed live</strong> (§101).
 *                       Null is a positive statement — <em>current as of this request</em> — and not
 *                       a missing value, which is why the contract marks it required.
 */
public record SuiteHistoryView(
        List<EntryView> entries, List<MarkerView> versionMarkers, Instant computedAt) {

    /**
     * One run's pass over this suite.
     *
     * <p>Components are declared in the contract's property order — Jackson serializes a record in
     * declaration order, and a response whose fields arrive in a different order from the schema is
     * a diff nobody can read.
     *
     * @param suiteVersionNo the suite's composition <em>at the time of the run</em>, resolved from
     *                       the version marks rather than from {@code suite.current_version_no}.
     *                       Today's number beside an old run would put this year's membership under
     *                       last year's pass rate, which is the exact comparison this screen exists
     *                       to make safe.
     * @param configuration  the run's environment, by name. A run is by definition one pass in one
     *                       configuration (V1 puts {@code configuration_id} on {@code test_run}), so
     *                       the contract's {@code (run, suite, configuration)} grouping collapses to
     *                       one row per run — the third key is carried, not grouped on.
     * @param executedAt     the most recent result in this run, or the run's start when it has
     *                       recorded none. Never null: the page sorts by it.
     * @param runDeleted     §6p — deleted runs must not orphan history, so the entry stays and says
     *                       so. Same field and same reason as {@code ExecutionHistoryEntry} (§49).
     */
    public record EntryView(
            UUID runId,
            String runKey,
            String runName,
            int suiteVersionNo,
            String configuration,
            String releaseVersion,
            Instant executedAt,
            ResultCountsView counts,
            boolean runDeleted) {
    }
}
