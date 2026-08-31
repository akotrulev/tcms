package com.tcms.tracker.board;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's boards.
 *
 * <p><strong>{@code @Transactional(readOnly = true)} is on the interface and must stay there.</strong>
 * Spring Data gives a transaction only to the inherited CRUD methods, so a derived method declared
 * here would otherwise run with no company set: the RLS policy sees an unset
 * {@code app.current_company} and filters every row out — zero rows, fail-closed and silent, with
 * no exception to find it by.
 */
@Transactional(readOnly = true)
public interface BoardRepository extends JpaRepository<Board, UUID> {

    List<Board> findByProjectIdOrderByNameAsc(UUID projectId);

    /** A query-based lookup, so the company filter applies in the application layer too. */
    Optional<Board> findScopedById(UUID id);

    long countByProjectId(UUID projectId);
}
