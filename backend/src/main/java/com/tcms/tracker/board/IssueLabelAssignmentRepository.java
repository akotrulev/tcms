package com.tcms.tracker.board;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which label is on which issue. {@code @Transactional(readOnly = true)} for
 * {@link BoardRepository}'s reason.
 */
@Transactional(readOnly = true)
public interface IssueLabelAssignmentRepository
        extends JpaRepository<IssueLabelAssignment, IssueLabelAssignmentId> {

    List<IssueLabelAssignment> findByIssueId(UUID issueId);

    /**
     * Every assignment for a set of issues, in one query.
     *
     * <p>The board and the backlog need labels for every row they render, and one query per issue is
     * the N+1 that makes a two-hundred-card board a two-hundred-query read.
     */
    List<IssueLabelAssignment> findByIssueIdIn(Collection<UUID> issueIds);
}
