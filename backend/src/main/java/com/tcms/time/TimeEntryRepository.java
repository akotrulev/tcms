package com.tcms.time;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entries in a period.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface, for {@link TimesheetPeriodRepository}'s
 * reason.
 */
@Transactional(readOnly = true)
public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

    /** One period's rows, newest day first, which is the order the timesheet renders. */
    List<TimeEntry> findByPeriodIdOrderBySpentOnDesc(UUID periodId);

    /** Every row of several periods at once — the queue's per-project totals in one query. */
    List<TimeEntry> findByPeriodIdIn(Collection<UUID> periodIds);

    /** A query-based lookup, so the Hibernate company filter applies in the application layer too. */
    Optional<TimeEntry> findScopedById(UUID id);

    /**
     * Hours logged against one issue whose period is in a given state.
     *
     * <p>The join is written out rather than mapped as an association: an entry points at its period
     * by id, so an entity join keeps the two aggregates apart while letting one query answer what
     * {@link RollupService} would otherwise ask per row.
     */
    @Query("""
            SELECT COALESCE(SUM(e.hours), 0)
              FROM TimeEntry e, TimesheetPeriod p
             WHERE p.id = e.periodId
               AND e.subjectType = com.tcms.time.TimeSubjectType.ISSUE
               AND e.subjectId = :issueId
               AND p.state = :state
            """)
    BigDecimal hoursOnIssueIn(@Param("issueId") UUID issueId, @Param("state") TimesheetState state);

    /** The other side of {@link #hoursOnIssueIn} — everything not yet approved (016 D-d). */
    @Query("""
            SELECT COALESCE(SUM(e.hours), 0)
              FROM TimeEntry e, TimesheetPeriod p
             WHERE p.id = e.periodId
               AND e.subjectType = com.tcms.time.TimeSubjectType.ISSUE
               AND e.subjectId = :issueId
               AND p.state <> :state
            """)
    BigDecimal hoursOnIssueOutside(
            @Param("issueId") UUID issueId, @Param("state") TimesheetState state);

    /** Any surviving entry against this issue, for the project a rollup row has to carry. */
    List<TimeEntry> findBySubjectTypeAndSubjectId(TimeSubjectType subjectType, UUID subjectId);
}
