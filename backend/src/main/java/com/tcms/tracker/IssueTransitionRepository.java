package com.tcms.tracker;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The legal moves — an allow-list, so a pair with no row here is refused.
 *
 * <p>{@code @Transactional(readOnly = true)} for {@link IssueRepository}'s reason.
 */
@Transactional(readOnly = true)
public interface IssueTransitionRepository
        extends JpaRepository<IssueTransition, IssueTransitionId> {

    List<IssueTransition> findByProjectId(UUID projectId);

    /** Every move out of one status — what {@code allowedTransitions} on a card is built from. */
    List<IssueTransition> findByFromStatusId(UUID fromStatusId);
}
