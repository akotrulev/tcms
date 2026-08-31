package com.tcms.tracker.board;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** {@code issue_label_assignment}'s key — an assignment is identified by the pair it is. */
public class IssueLabelAssignmentId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID issueId;
    private UUID labelId;

    /** JPA only. */
    public IssueLabelAssignmentId() {
    }

    public IssueLabelAssignmentId(UUID issueId, UUID labelId) {
        this.issueId = issueId;
        this.labelId = labelId;
    }

    public UUID getIssueId() {
        return issueId;
    }

    public UUID getLabelId() {
        return labelId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IssueLabelAssignmentId that)) {
            return false;
        }
        return Objects.equals(issueId, that.issueId) && Objects.equals(labelId, that.labelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issueId, labelId);
    }
}
