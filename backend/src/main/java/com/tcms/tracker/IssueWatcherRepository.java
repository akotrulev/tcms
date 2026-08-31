package com.tcms.tracker;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who is following what.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link IssueRepository}'s reason —
 * without it a derived method runs with no company set and RLS returns zero rows, fail-closed and
 * silent.
 */
@Transactional(readOnly = true)
public interface IssueWatcherRepository extends JpaRepository<IssueWatcher, IssueWatcherId> {

    /** In the order people started following — the author of the first comment leads the list. */
    List<IssueWatcher> findByIssueIdOrderByCreatedAtAsc(UUID issueId);
}
