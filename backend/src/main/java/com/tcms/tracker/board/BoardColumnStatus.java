package com.tcms.tracker.board;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One column's claim on one status, and the half of "exactly one column per status" the database
 * can hold.
 *
 * <p>{@code uq_board_column_status (company_id, board_id, status_id)} refuses a status claimed
 * twice. "Claimed at least once" is the absence of a row and therefore a cross-table assertion no
 * CHECK can express — it is {@link BoardConfigService}'s rule instead.
 *
 * <p>{@link #boardId} is denormalised onto the row so that unique index can exist at all: without
 * it the constraint would have to span {@code board_column}, which a unique index cannot do.
 */
@Entity
@Table(name = "board_column_status")
@IdClass(BoardColumnStatusId.class)
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class BoardColumnStatus {

    @Id
    @Column(name = "column_id", nullable = false, updatable = false)
    private UUID columnId;

    @Id
    @Column(name = "status_id", nullable = false, updatable = false)
    private UUID statusId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "board_id", nullable = false, updatable = false)
    private UUID boardId;

    /** JPA only. */
    protected BoardColumnStatus() {
    }

    static BoardColumnStatus claim(UUID companyId, UUID boardId, UUID columnId, UUID statusId) {
        BoardColumnStatus claim = new BoardColumnStatus();
        claim.companyId = companyId;
        claim.boardId = boardId;
        claim.columnId = columnId;
        claim.statusId = statusId;
        return claim;
    }

    public UUID getColumnId() {
        return columnId;
    }

    public UUID getStatusId() {
        return statusId;
    }
}
