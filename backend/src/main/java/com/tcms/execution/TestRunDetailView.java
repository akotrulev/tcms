package com.tcms.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code TestRunDetail} in {@code docs/specs/openapi.yaml} — the run, plus the checklist.
 *
 * <p>Flat for the {@code allOf} reason, and built from the {@link TestRunView} the list renders, so
 * a run's counts cannot differ between the two screens that show them.
 */
public record TestRunDetailView(
        UUID id,
        String key,
        String name,
        UUID planId,
        String planKey,
        ConfigurationView configuration,
        RunStatus status,
        String releaseVersion,
        String buildNumber,
        RunSource source,
        Instant startedAt,
        Instant completedAt,
        ResultCountsView counts,
        List<TestExecutionSummaryView> executions) {

    static TestRunDetailView of(TestRunView run, List<TestExecutionSummaryView> executions) {
        return new TestRunDetailView(
                run.id(),
                run.key(),
                run.name(),
                run.planId(),
                run.planKey(),
                run.configuration(),
                run.status(),
                run.releaseVersion(),
                run.buildNumber(),
                run.source(),
                run.startedAt(),
                run.completedAt(),
                run.counts(),
                executions);
    }
}
