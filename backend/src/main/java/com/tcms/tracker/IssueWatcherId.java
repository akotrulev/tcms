package com.tcms.tracker;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** {@code issue_watcher}'s composite key — one person following one issue, at most once. */
public class IssueWatcherId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID issueId;
    private UUID userId;

    /** JPA only. */
    public IssueWatcherId() {
    }

    public IssueWatcherId(UUID issueId, UUID userId) {
        this.issueId = issueId;
        this.userId = userId;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IssueWatcherId that)) {
            return false;
        }
        return Objects.equals(issueId, that.issueId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issueId, userId);
    }
}
