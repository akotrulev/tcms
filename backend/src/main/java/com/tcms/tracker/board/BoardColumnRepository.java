package com.tcms.tracker.board;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** A board's columns. {@code @Transactional(readOnly = true)} for {@link BoardRepository}'s reason. */
@Transactional(readOnly = true)
public interface BoardColumnRepository extends JpaRepository<BoardColumn, UUID> {

    /** Left to right, and the order is direction of travel (§12.2). */
    List<BoardColumn> findByBoardIdOrderByPositionAsc(UUID boardId);
}
