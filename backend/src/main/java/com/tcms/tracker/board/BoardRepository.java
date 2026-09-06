package com.tcms.tracker.board;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * The project's boards in Work-strip order (021 D-f).
     *
     * <p>Position first and the name only as a tie-break: {@code board."position"} is deliberately
     * not unique, so two boards created in one instant share a number and something has to decide
     * between them. ~~{@code findByProjectIdOrderByNameAsc}~~ → superseded by V13, which is what
     * gave the table an order at all; alphabetical would have listed Dev before PM and QA last, so
     * the three seeded boards read their hand-offs right to left.
     */
    List<Board> findByProjectIdOrderByPositionAscNameAsc(UUID projectId);

    /** A query-based lookup, so the company filter applies in the application layer too. */
    Optional<Board> findScopedById(UUID id);

    long countByProjectId(UUID projectId);

    /**
     * The last position taken in this project, so a created board can go after it.
     *
     * <p>Answers {@code null} for a project with no board — which is a real state only while the
     * first one is being created, since {@code tcms_seed_project_defaults} gives every project
     * three and the service refuses to delete the last.
     */
    @Query("SELECT max(board.position) FROM Board board WHERE board.projectId = :projectId")
    Integer maxPositionOf(@Param("projectId") UUID projectId);
}
