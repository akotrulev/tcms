package com.tcms.time;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * This module's projection of hours per issue.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface, for {@link TimesheetPeriodRepository}'s
 * reason.
 */
@Transactional(readOnly = true)
public interface IssueTimeRollupRepository extends JpaRepository<IssueTimeRollup, UUID> {

    /** A query-based lookup, so the Hibernate company filter applies in the application layer too. */
    Optional<IssueTimeRollup> findScopedByIssueId(UUID issueId);

    /** The rows behind an epic panel, fetched once for every child rather than once per child. */
    List<IssueTimeRollup> findByIssueIdIn(Collection<UUID> issueIds);
}
