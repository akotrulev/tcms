package com.tcms.reporting.analytics;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * <strong>The only file in {@code com.tcms.reporting} that names {@code issue_fact} or
 * {@code release}</strong> — 020 D-g, and the sixth instance of a pattern this codebase settled on
 * before this module existed: {@code work.BoardFacts} reads {@code tracker},
 * {@code execution.CaseFacts} reads {@code repository}, {@code tracker.release.ReleaseFacts} reads
 * {@code execution}, and {@code time.IssueFacts} and {@code billing.IssueSubjectFacts} both read
 * {@code tracker}.
 *
 * <h2>ArchUnit cannot see this crossing, which is why there is a test that reads text</h2>
 *
 * <p>{@code reporting}'s {@code allowedDependencies} names the five foundation modules, so the Java
 * half of the rule — no import of {@code tracker} — is enforced by ArchUnit and Spring Modulith.
 * The <em>table</em> half is invisible to both, because SQL is not a Java dependency.
 * {@code ReportingReadsAndNeverWritesTest} closes it by reading this module's own source, the way
 * {@code StaffingReadsNoTimeEntryTest} does one module over.
 *
 * <h2>What is read, and what it costs to read it</h2>
 *
 * <p>{@code issue_fact} is a projection built for exactly this — its table comment says it exists
 * "for reporting, time and billing to join WITHOUT importing tracker". {@code release} is not, and
 * exactly two of its columns are read here: {@code created_at}, the burn-up's baseline, and
 * {@code version}, the chart's title. Taking that baseline from the request instead was rejected
 * outright — a computation baseline supplied by the caller is a burn-up anyone can reshape with a
 * query string.
 *
 * <p><strong>Every arm carries {@code deleted_at IS NULL}.</strong> {@code issue_fact} mirrors
 * {@code issue.deleted_at} rather than removing the row, precisely so that reporting over a closed
 * period does not change when somebody tidies the backlog afterwards. Omitting it would make every
 * historical point drift, silently and only for projects that clean up.
 *
 * <p>Native SQL through {@link EntityManager} rather than a Spring Data interface, following
 * {@code billing.IssueSubjectFacts}: there is no entity for {@code issue_fact} in this module and
 * there must not be one — an {@code @Entity} here would let Hibernate's dirty checking write a
 * table whose single writer lives in {@code tracker}.
 */
@Component
class DeliveryFacts {

    /** A release's identity and its burn-up baseline. */
    record ReleaseBaseline(UUID releaseId, String version, Instant createdAt) {
    }

    /** One counted issue in a release, reduced to the two moments the burn-up needs. */
    record ReleaseIssue(Instant assignedAt, Instant doneAt) {
    }

    /** One completed issue, reduced to its type and its elapsed days. */
    record CompletedIssue(String typeName, double elapsedDays) {
    }

    /** One issue's moments and its current band, for the flow's per-week classification. */
    record FlowIssue(
            Instant createdAt, Instant statusEnteredAt, Instant doneAt, String statusKind) {
    }

    /**
     * The counted set, and it is <strong>byte-identical in meaning to
     * {@code ReadinessService.SCOPE_ADDED}'s</strong> (020 D-f).
     *
     * <p>Story-level issues whose type counts toward completion. An Epic is excluded by the flag —
     * every epic type carries {@code counts_toward_completion = false} — and a Task pointed at a
     * release is excluded because a Task is a breakdown <em>of</em> a story, so counting both counts
     * the same work twice and counting the task instead would make a release's total change with how
     * finely somebody had broken it down.
     *
     * <p>Two screens reporting different "scope added" figures for one release is the defect this
     * copy exists to prevent, and it is the exact shape {@code CLAUDE.md} records as having
     * typechecked and linted clean.
     */
    private static final String COUNTED = "f.type_level = 'STORY' AND f.counts_toward_completion";

    private final EntityManager entities;

    DeliveryFacts(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * One release's identity and baseline, or empty when the id names nothing in this project.
     *
     * <p>Scoped by {@code project_id} as well as by id: the caller has had the project gated and the
     * release id has not, so a release from another project the caller happens to be able to see
     * must not answer here.
     */
    Optional<ReleaseBaseline> release(UUID projectId, UUID releaseId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT r.id, r.version, r.created_at
                          FROM release r
                         WHERE r.id = :releaseId
                           AND r.project_id = :projectId
                        """)
                .setParameter("releaseId", releaseId)
                .setParameter("projectId", projectId)
                .getResultList();

        return rows.stream()
                .findFirst()
                .map(row -> new ReleaseBaseline(
                        (UUID) row[0], (String) row[1], instant(row[2])));
    }

    /** Every counted issue targeting one release. Tens of rows, so the series is folded in Java. */
    List<ReleaseIssue> releaseScope(UUID projectId, UUID releaseId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.release_assigned_at, f.done_at
                          FROM issue_fact f
                         WHERE f.project_id = :projectId
                           AND f.release_id = :releaseId
                           AND f.deleted_at IS NULL
                        """
                        // Concatenated rather than interpolated into the block above: a text block
                        // whose last line ended in `AND ` carries trailing whitespace, which javac
                        // warns about and -Werror turns into a build failure.
                        + "   AND " + COUNTED)
                .setParameter("projectId", projectId)
                .setParameter("releaseId", releaseId)
                .getResultList();

        List<ReleaseIssue> scope = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            scope.add(new ReleaseIssue(instant(row[0]), instant(row[1])));
        }
        return scope;
    }

    /**
     * Completed issues in the window, with their elapsed time in days.
     *
     * <p><strong>From {@code created_at}, and never from a "started" moment</strong> (020 D-c).
     * There is no status history in this product — {@code issue_transition} is the workflow
     * ALLOW-LIST, a table of legal moves and not a log of moves made — so {@code status_entered_at}
     * on a completed issue is when it entered the <em>Done</em> status. Subtracting that from
     * {@code done_at} yields approximately zero for every issue, under a label claiming it is how
     * long the work took.
     *
     * <p>The subtraction is done in SQL because {@code EXTRACT(EPOCH FROM …)} over
     * {@code timestamptz} is exact and does not depend on the JVM's default zone.
     */
    List<CompletedIssue> completedSince(UUID projectId, Instant since) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.type_name,
                               EXTRACT(EPOCH FROM (f.done_at - f.created_at)) / 86400.0
                          FROM issue_fact f
                         WHERE f.project_id = :projectId
                           AND f.deleted_at IS NULL
                           AND f.done_at IS NOT NULL
                           AND f.done_at >= :since
                         ORDER BY f.type_name
                        """)
                .setParameter("projectId", projectId)
                .setParameter("since", since)
                .getResultList();

        List<CompletedIssue> completed = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            completed.add(new CompletedIssue((String) row[0], number(row[1])));
        }
        return completed;
    }

    /**
     * Every non-deleted issue that existed by the end of the window.
     *
     * <p><strong>One query for the whole chart.</strong> The obvious shape — one count query per
     * weekly boundary — is 26 round trips per page load for a figure that fits in one, and the
     * population is a project's issues rather than a company's.
     */
    List<FlowIssue> flowPopulation(UUID projectId, Instant createdBefore) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entities.createNativeQuery("""
                        SELECT f.created_at, f.status_entered_at, f.done_at, f.status_kind
                          FROM issue_fact f
                         WHERE f.project_id = :projectId
                           AND f.deleted_at IS NULL
                           AND f.created_at < :createdBefore
                        """)
                .setParameter("projectId", projectId)
                .setParameter("createdBefore", createdBefore)
                .getResultList();

        List<FlowIssue> population = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            population.add(new FlowIssue(
                    instant(row[0]), instant(row[1]), instant(row[2]), (String) row[3]));
        }
        return population;
    }

    /**
     * A {@code timestamptz} column as an {@link Instant}, null-safe and driver-agnostic.
     *
     * <p><strong>Both branches are load-bearing.</strong> A native query's column arrives as a
     * {@link Timestamp} through plain JDBC and as an {@link Instant} through Hibernate 6's own type
     * mapping, and which one a given query hands back depends on how the result is materialised —
     * so a cast that assumes either one throws {@link ClassCastException} for the other, at runtime,
     * on a request rather than at compile time. That is exactly how this method was first written
     * and exactly how it failed.
     *
     * <p>Three of the four columns read here are nullable — {@code done_at} on anything open,
     * {@code release_assigned_at} on anything unassigned — so the null arm is the ordinary case
     * rather than a guard.
     */
    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant already) {
            return already;
        }
        return ((Timestamp) value).toInstant();
    }

    /**
     * A computed numeric column as a {@code double}.
     *
     * <p>Postgres answers an {@code EXTRACT … / numeric} division as {@code numeric}, which the
     * driver hands back as {@link BigDecimal} — but the same expression can arrive as a
     * {@link Double} depending on how the division is typed, and a cast that assumes one of them
     * fails at runtime for the other. Reading through {@link Number} costs nothing and cannot be
     * wrong either way.
     */
    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
