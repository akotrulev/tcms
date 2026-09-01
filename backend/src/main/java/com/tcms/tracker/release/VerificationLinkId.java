package com.tcms.tracker.release;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code issue_link}'s composite key — {@code (issue_id, target_type, target_id)}, exactly as
 * {@code V3} declared it.
 *
 * <p>An {@code @IdClass} rather than an {@code @EmbeddedId} for {@code BoardColumnStatusId}'s
 * reason: the fields stay flat on the entity, so a query reads {@code link.issueId} rather than
 * {@code link.id.issueId} and nothing has to remember which.
 */
public class VerificationLinkId implements Serializable {

    private UUID issueId;
    private String targetType;
    private UUID targetId;

    /** JPA only. */
    public VerificationLinkId() {
    }

    public VerificationLinkId(UUID issueId, String targetType, UUID targetId) {
        this.issueId = issueId;
        this.targetType = targetType;
        this.targetId = targetId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VerificationLinkId id
                && Objects.equals(issueId, id.issueId)
                && Objects.equals(targetType, id.targetType)
                && Objects.equals(targetId, id.targetId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(issueId, targetType, targetId);
    }
}
