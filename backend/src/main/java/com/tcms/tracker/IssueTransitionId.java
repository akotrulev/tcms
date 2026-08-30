package com.tcms.tracker;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** {@code issue_transition}'s composite key — the pair itself is the identity of a legal move. */
public class IssueTransitionId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID fromStatusId;
    private UUID toStatusId;

    /** JPA only. */
    public IssueTransitionId() {
    }

    public IssueTransitionId(UUID fromStatusId, UUID toStatusId) {
        this.fromStatusId = fromStatusId;
        this.toStatusId = toStatusId;
    }

    public UUID getFromStatusId() {
        return fromStatusId;
    }

    public UUID getToStatusId() {
        return toStatusId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IssueTransitionId that)) {
            return false;
        }
        return Objects.equals(fromStatusId, that.fromStatusId)
                && Objects.equals(toStatusId, that.toStatusId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromStatusId, toStatusId);
    }
}
