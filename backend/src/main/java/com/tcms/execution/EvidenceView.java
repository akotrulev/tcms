package com.tcms.execution;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code Evidence} in {@code docs/specs/openapi.yaml} — a screenshot or log attached to a result.
 *
 * <p><strong>Always an empty list in this slice.</strong> {@code evidence} rows reference
 * {@code attachment}, and nothing uploads one: {@code evidence:attach} has no endpoint. The shape is
 * declared so the response's required {@code evidence} array has a type rather than being
 * {@code List<Object>} — a list whose element type is unknown is a list nothing can ever be added
 * to correctly.
 */
public record EvidenceView(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        String url,
        String thumbnailUrl,
        String caption,
        UserRefView createdBy,
        Instant createdAt) {
}
