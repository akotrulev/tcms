package com.tcms.tracker.board;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** {@code board_column_status}'s composite key — a claim is identified by the pair it is. */
public class BoardColumnStatusId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID columnId;
    private UUID statusId;

    /** JPA only. */
    public BoardColumnStatusId() {
    }

    public BoardColumnStatusId(UUID columnId, UUID statusId) {
        this.columnId = columnId;
        this.statusId = statusId;
    }

    public UUID getColumnId() {
        return columnId;
    }

    public UUID getStatusId() {
        return statusId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BoardColumnStatusId that)) {
            return false;
        }
        return Objects.equals(columnId, that.columnId) && Objects.equals(statusId, that.statusId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnId, statusId);
    }
}
