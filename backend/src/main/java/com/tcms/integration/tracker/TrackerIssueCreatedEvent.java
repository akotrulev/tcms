package com.tcms.integration.tracker;

import java.util.UUID;

/**
 * {@code integration.tracker_issue_created} — the payload, as this module writes it.
 *
 * <h2>The producer half of a pair, and the pair is forced</h2>
 *
 * <p>{@code execution.TrackerIssueCreatedPayload} is the consumer's declaration of the same shape.
 * They are two records on purpose and cannot be one: {@code execution} may not import
 * {@code integration} and {@code integration} may not import {@code execution}, so the payload
 * crosses as a JSON document and the compiler is structurally unable to check that they agree.
 * {@code Outbox.record}'s standing debt is paid the way {@code RunCompletedPayload} pays it — a test
 * on each side pinning the shape, and one that drives producer to handler through the real outbox,
 * which is the one that still fails when somebody updates both pins together.
 *
 * <h2>It is a backstop, not the path</h2>
 *
 * <p>The SPA files the issue and then writes the {@code defect_link} itself, in two calls, because
 * {@code execution} may not import {@code integration} and the create therefore cannot write the
 * link. This event exists for the crash between those two calls: the handler writes the same row
 * from this payload, and the two writes collide on
 * {@code defect_link_execution_key_idx} rather than producing a twin.
 *
 * <p><strong>{@code provider} is in the payload because it is in that index.</strong> If the SPA's
 * follow-up omits its {@code bindingId}, its row says {@code EXTERNAL} while this handler's says
 * {@code JIRA_CLOUD}, the index does not collide, and the run screen shows one defect twice with
 * both rows looking correct. That is the reason {@code DefectLinkInput.bindingId} exists, and it is
 * pinned by a test rather than by this paragraph.
 *
 * @param eventId     the occurrence's own id, minted here. The handler's idempotency key, since
 *                    outbox delivery is at least once.
 * @param executionId null when the issue was filed from nowhere in particular — the handler then
 *                    has no row to write and settles the event, which is a delivery and not a
 *                    failure.
 */
record TrackerIssueCreatedEvent(
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
