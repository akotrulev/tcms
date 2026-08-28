package com.tcms.execution;

import java.util.UUID;

/**
 * {@code integration.tracker_issue_created}, as this module reads it.
 *
 * <h2>The consumer half of a pair, and the pair is forced</h2>
 *
 * <p>{@code integration.tracker.TrackerIssueCreatedEvent} is the producer's declaration of the same
 * shape. <strong>They are two records on purpose and cannot be one:</strong> neither module may
 * import the other, so the payload crosses as a JSON document and the compiler is structurally
 * unable to check that they agree.
 *
 * <p>{@code Outbox.record}'s standing debt is paid the way {@link RunCompletedEvent} pays it — a
 * test on each side pinning the shape, plus one that drives producer to handler through the real
 * outbox, which is the one that still fails when somebody updates both pins together.
 *
 * @param eventId     the occurrence's own id, minted by the producer. Not the outbox row's id — a
 *                    handler is deliberately given no row identity.
 * @param executionId which execution the issue was filed against, or null when it was filed from
 *                    nowhere in particular. {@link TrackerDefectLinkBackstop} settles a null one as
 *                    delivered and writes nothing: there is no row to write, and that is a delivery
 *                    rather than a failure.
 * @param provider    the tracker's own name. <strong>In the payload because it is in the unique
 *                    index</strong> the handler's write has to collide on — see
 *                    {@link DefectLinkInput#bindingId}.
 * @param externalKey the key at creation, which is what {@code defect_link.external_key} holds.
 *                    <em>Not</em> the stable id: this table's index predates 004 and is keyed on the
 *                    key, which is a different decision from {@code doc_link}'s and is left alone
 *                    rather than changed underneath rows that already exist.
 */
record TrackerIssueCreatedPayload(
        UUID eventId,
        UUID companyId,
        UUID projectId,
        UUID executionId,
        UUID bindingId,
        String provider,
        String externalId,
        String externalKey,
        String url,
        UUID createdBy) {
}
