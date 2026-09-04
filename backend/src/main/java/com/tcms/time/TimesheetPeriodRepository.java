package com.tcms.time;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's half-months.
 *
 * <p>{@code @Transactional(readOnly = true)} <strong>on the interface</strong>, for the reason every
 * repository in this codebase carries it: Spring Data gives a transaction only to the inherited CRUD
 * methods, so a derived method declared here would run with no company set and every RLS-protected
 * read would return <strong>zero rows</strong> — fail-closed and silent, with no error to attribute
 * it to.
 */
@Transactional(readOnly = true)
public interface TimesheetPeriodRepository extends JpaRepository<TimesheetPeriod, UUID> {

    /** The uniqueness {@code uq_timesheet_period} enforces, asked before a row is created. */
    Optional<TimesheetPeriod> findByPersonIdAndStartsOn(UUID personId, LocalDate startsOn);

    /** Newest first — the "Previous period" panel, and the route back to a rejected fortnight. */
    List<TimesheetPeriod> findByPersonIdOrderByStartsOnDesc(UUID personId);

    /** Everybody's period for one fortnight — the approvals queue before D-i narrows it. */
    List<TimesheetPeriod> findByStartsOn(LocalDate startsOn);

    /** A query-based lookup, so the Hibernate company filter applies in the application layer too. */
    Optional<TimesheetPeriod> findScopedById(UUID id);
}
