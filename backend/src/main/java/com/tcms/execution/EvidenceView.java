package com.tcms.execution;

import com.tcms.api.UserRef;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code Evidence} in {@code docs/specs/openapi.yaml} — a screenshot or log attached to a result.
 *
 * <p>{@code url} is a <strong>relative</strong> API route, built in one place
 * ({@code EvidenceViews#contentUrl}) and served by {@code EvidenceController#content}. Relative
 * because the server does not know its own public origin: behind a reverse proxy it only ever sees
 * {@code localhost:8080}, and a browser resolves a relative url against the page it is already on.
 *
 * <p>{@code thumbnailUrl} is null for every row today — there is no image processing. Null rather
 * than a guessed route, which would 404 on every image.
 *
 * @param createdBy nullable, and that is the arm {@code evidence:delete}'s {@code OWN} qualifier
 *                  turns on: evidence nobody can attribute is not anybody's own work
 */
public record EvidenceView(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        String url,
        String thumbnailUrl,
        String caption,
        UserRef createdBy,
        Instant createdAt) {
}
