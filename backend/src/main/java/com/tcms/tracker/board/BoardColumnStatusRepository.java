package com.tcms.tracker.board;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which column claims which status. {@code @Transactional(readOnly = true)} for
 * {@link BoardRepository}'s reason.
 */
@Transactional(readOnly = true)
public interface BoardColumnStatusRepository
        extends JpaRepository<BoardColumnStatus, BoardColumnStatusId> {

    List<BoardColumnStatus> findByBoardId(UUID boardId);
}
