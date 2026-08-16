package com.tcms.execution;

/**
 * {@code DefectLinkInput} in {@code docs/specs/openapi.yaml} — the body of the link-a-defect
 * request.
 *
 * <p>Two fields, and the absences are the design. There is no {@code provider} because a caller
 * cannot establish one pre-M4 (see {@link DefectLink}), no {@code statusCache} or {@code syncedAt}
 * because those are a tracker's answers and no tracker has been asked ({@code V62}), and no
 * {@code caseId} because every reader of this table is execution-scoped and the execution is in the
 * path.
 *
 * @param externalKey the tracker's own identifier — {@code PAY-991}. Free text: this product does
 *                    not know any tracker's key format, and refusing a shape it guessed at would
 *                    reject a real key from a real system.
 * @param url         where the defect lives. Validated as {@code http} or {@code https} by
 *                    {@link DefectLinkService}, which is a security bound rather than tidiness —
 *                    three screens render it as an {@code href}.
 */
public record DefectLinkInput(String externalKey, String url) {
}
