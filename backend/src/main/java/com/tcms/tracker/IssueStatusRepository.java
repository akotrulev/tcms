package com.tcms.tracker;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's workflow columns.
 *
 * <p>{@code @Transactional(readOnly = true)} for {@link IssueRepository}'s reason.
 */
@Transactional(readOnly = true)
public interface IssueStatusRepository extends JpaRepository<IssueStatus, UUID> {

    /**
     * The project's statuses in workflow order.
     *
     * <p><strong>The first element is what a new issue enters</strong> — by position, never by the
     * word "Backlog". A project that renamed its first column still has a first column, and a lookup
     * on the word would break exactly the projects that used the configurability B1 promises them.
     */
    List<IssueStatus> findByProjectIdOrderByPositionAsc(UUID projectId);
}
