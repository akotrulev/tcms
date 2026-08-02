package com.tcms.project;

import java.time.Instant;
import java.util.UUID;

/**
 * One contribution to a review, as the contract's {@code ReviewParticipation}.
 *
 * @param user    who said it, or <strong>null</strong> for a row whose account no longer resolves.
 *                Nullable rather than omitted: dropping the row would silently shorten a review
 *                trail, and a verdict that was recorded stays recorded even when the person who
 *                gave it has been offboarded (§66's rule about an unattributable row — the absence
 *                is the honest answer, and the client renders it as "a former member")
 * @param comment optional free text; the only text on a review until {@code review_comment} has a
 *                writer
 */
public record ReviewParticipationView(
        UUID id,
        UserRefView user,
        ReviewParticipation.Kind kind,
        ReviewParticipation.Verdict verdict,
        String comment,
        Instant createdAt) {
}
