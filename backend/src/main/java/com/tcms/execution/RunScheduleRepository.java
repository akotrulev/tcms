package com.tcms.execution;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Standing run requests, by project and by dueness ({@code V68}, §247).
 *
 * <p>{@code @Transactional(readOnly = true)} per {@link TestPlanRepository}, and it is not a style
 * rule: Spring Data gives a transaction only to the CRUD methods inherited from
 * {@code SimpleJpaRepository}, never to the ones declared here — so without it every query below
 * runs with no company context and {@code FORCE ROW LEVEL SECURITY} answers <strong>zero rows,
 * silently</strong>. {@code RepositoryTransactionTest} derives its list from the live context and
 * fails the build for a repository that forgets.
 */
@Transactional(readOnly = true)
interface RunScheduleRepository extends JpaRepository<RunSchedule, UUID> {

    /**
     * A project's schedules, by name.
     *
     * <p>The secondary sort key is not decoration: two schedules sharing a name would otherwise
     * swap places between reads and the list flickers under the reader.
     */
    @Query("SELECT s FROM RunSchedule s WHERE s.projectId = :projectId "
            + "ORDER BY s.name ASC, s.id ASC")
    List<RunSchedule> findInProject(@Param("projectId") UUID projectId);

    /** One schedule that is in this project — the second gate, per {@link TestRunRepository}. */
    @Query("SELECT s FROM RunSchedule s WHERE s.projectId = :projectId AND s.id = :scheduleId")
    Optional<RunSchedule> findInProject(
            @Param("projectId") UUID projectId, @Param("scheduleId") UUID scheduleId);

    /**
     * Every due schedule in the company the context is open on.
     *
     * <p>Matches {@code run_schedule_due_idx} exactly, including the {@code enabled} predicate the
     * index is partial on. <strong>There is deliberately no cross-company variant.</strong>
     * {@code run_schedule} is under FORCE RLS, so a query for every due row in the instance returns
     * zero rows and lets the poller log a successful tick having fired nothing — see
     * {@code RunScheduleScheduler}'s header, and {@code AsyncCompanyContextTest}, which drives that
     * shape rather than describing it.
     *
     * <p>Ordered by dueness so the oldest slot is served first, tie-broken by id so two application
     * instances polling one tick walk the same list in the same order.
     */
    @Query("SELECT s FROM RunSchedule s WHERE s.enabled = TRUE AND s.nextFireAt <= :now "
            + "ORDER BY s.nextFireAt ASC, s.id ASC")
    List<RunSchedule> findDue(@Param("now") Instant now);
}
