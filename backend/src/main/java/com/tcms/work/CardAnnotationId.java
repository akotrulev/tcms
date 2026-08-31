package com.tcms.work;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code derived_item_annotation}'s composite key.
 *
 * <p>The viewer leads it, which is the shape story B5 asks for: one person's annotations on
 * everything, never one card's annotations across everybody.
 */
public class CardAnnotationId implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID userId;
    private String sourceType;
    private UUID sourceId;

    /** JPA only. */
    public CardAnnotationId() {
    }

    public CardAnnotationId(UUID userId, String sourceType, UUID sourceId) {
        this.userId = userId;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardAnnotationId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId)
                && Objects.equals(sourceType, that.sourceType)
                && Objects.equals(sourceId, that.sourceId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, sourceType, sourceId);
    }
}
