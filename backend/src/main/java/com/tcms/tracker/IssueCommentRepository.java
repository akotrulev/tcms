package com.tcms.tracker;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * An issue's comments.
 *
 * <p>{@code @Transactional(readOnly = true)} on the interface for {@link IssueRepository}'s reason —
 * without it a derived method runs with no company set and RLS returns zero rows, fail-closed and
 * silent.
 */
@Transactional(readOnly = true)
public interface IssueCommentRepository extends JpaRepository<IssueComment, UUID> {

    /** Oldest first — a conversation, in the order it happened. */
    List<IssueComment> findByIssueIdOrderByCreatedAtAscIdAsc(UUID issueId);
}
