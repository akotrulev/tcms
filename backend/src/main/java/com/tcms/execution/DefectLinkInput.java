package com.tcms.execution;

import java.util.UUID;

/**
 * {@code DefectLinkInput} in {@code docs/specs/openapi.yaml} — the body of the link-a-defect
 * request.
 *
 * <p>~~Two fields, and the absences are the design.~~ → <strong>three since 004</strong>, and the
 * absences are still the design. There is <em>still</em> no {@code provider}, no
 * {@code statusCache}, no {@code syncedAt} ({@code V62}, confirmed by {@code V74}) and no
 * {@code caseId}.
 *
 * @param externalKey the tracker's own identifier — {@code PAY-991}. Free text: this product does
 *                    not know any tracker's key format, and refusing a shape it guessed at would
 *                    reject a real key from a real system.
 * @param url         where the defect lives. Validated as {@code http} or {@code https} by
 *                    {@link DefectLinkService}, which is a security bound rather than tidiness —
 *                    three screens render it as an {@code href}.
 * @param bindingId   which {@code tracker_binding} the issue was filed through, or null for a link
 *                    a person typed (004). <strong>A binding id and not a provider string, and that
 *                    is the whole point</strong>: the server resolves it and states the provider it
 *                    actually called, so {@code defect_link.provider} keeps recording something this
 *                    server established.
 *
 *                    <p><strong>It is also what stops one defect rendering twice.</strong>
 *                    {@code defect_link_execution_key_idx} is
 *                    {@code (company_id, execution_id, provider, external_key)}. The SPA files an
 *                    issue and then calls this endpoint; the outbox backstop writes the same row
 *                    from {@code integration.tracker_issue_created} if that second call never
 *                    arrives. Omit this field and the SPA's row says {@code EXTERNAL} while the
 *                    handler's says {@code JIRA_CLOUD}, the index does not collide, and the run
 *                    screen shows the same defect twice with both rows looking correct. There is a
 *                    test for the collision and a test for the twin, so the reason this field
 *                    exists is pinned rather than argued.
 */
public record DefectLinkInput(String externalKey, String url, UUID bindingId) {
}
