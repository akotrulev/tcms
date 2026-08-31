package com.tcms.tracker.board;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's labels.
 *
 * <p>{@code @Transactional(readOnly = true)} for {@link BoardRepository}'s reason: Spring Data gives
 * a transaction only to the inherited CRUD methods, so a derived method declared here would run with
 * no company set and RLS would answer zero rows, fail-closed and silent.
 */
@Transactional(readOnly = true)
public interface IssueLabelRepository extends JpaRepository<IssueLabel, UUID> {

    /** In name order, which is what the label picker and the filter chips render. */
    List<IssueLabel> findByProjectIdOrderByNameAsc(UUID projectId);

    /** A query-based lookup, so the company filter applies in the application layer too. */
    Optional<IssueLabel> findScopedById(UUID id);
}
