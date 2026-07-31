package com.tcms.execution;

import java.util.UUID;

/**
 * {@code DefectLink} in {@code docs/specs/openapi.yaml} — a link out to the defect tracker.
 *
 * <p>TCMS is not a defect tracker; it links out. So this carries the external key, the provider and
 * a URL, and no Jira-specific column — the shape behind the {@code IssueTracker} port (ERD §5), so
 * a GitHub or ADO adapter is additive.
 *
 * @param statusCache the tracker's status as of the last sync, nullable. Cached rather than fetched
 *                    on read, because a page that renders N executions must not make N calls to
 *                    somebody else's API.
 */
public record DefectLinkView(
        UUID id, String externalKey, String provider, String url, String statusCache) {
}
