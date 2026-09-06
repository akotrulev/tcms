package com.tcms.reporting.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A hand-rolled {@link DeliveryFacts} that answers from lists handed to it.
 *
 * <h2>A fake, not a mock, and not a database</h2>
 *
 * <p>The three services are arithmetic over rows: a percentile, a weekly fold, a per-date
 * classification. None of that needs Postgres, and every one of the properties worth pinning — that
 * scope added late is counted apart, that a median is per type, that the fourth flow band is empty
 * at the newest point — is a statement about the arithmetic rather than about SQL. Booting a
 * container to assert them would make the suite slower and the failures further from the cause.
 *
 * <p>The SQL itself is not untested: {@code AnalyticsIsolationTest} beside this drives the real
 * queries against a real database with RLS on, which is the half a fake cannot answer.
 *
 * <p>{@code null} for the {@code EntityManager} is safe and is the point — every method is
 * overridden, so a query that escaped the fake would be an immediate NPE naming the arm that leaked
 * rather than a silent empty result.
 */
class AnalyticsFacts extends DeliveryFacts {

    private ReleaseBaseline release;
    private List<ReleaseIssue> scope = List.of();
    private List<CompletedIssue> completed = List.of();
    private List<FlowIssue> population = List.of();

    AnalyticsFacts() {
        super(null);
    }

    AnalyticsFacts withRelease(ReleaseBaseline value) {
        this.release = value;
        return this;
    }

    AnalyticsFacts withScope(List<ReleaseIssue> value) {
        this.scope = value;
        return this;
    }

    AnalyticsFacts withCompleted(List<CompletedIssue> value) {
        this.completed = value;
        return this;
    }

    AnalyticsFacts withPopulation(List<FlowIssue> value) {
        this.population = value;
        return this;
    }

    @Override
    Optional<ReleaseBaseline> release(UUID projectId, UUID releaseId) {
        return Optional.ofNullable(release);
    }

    @Override
    List<ReleaseIssue> releaseScope(UUID projectId, UUID releaseId) {
        return scope;
    }

    @Override
    List<CompletedIssue> completedSince(UUID projectId, Instant since) {
        return completed;
    }

    @Override
    List<FlowIssue> flowPopulation(UUID projectId, Instant createdBefore) {
        return population;
    }
}
