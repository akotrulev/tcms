package com.tcms.repository;

import com.tcms.storage.Attachment;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code Attachment} in {@code docs/specs/openapi.yaml} — one file on a case's definition.
 *
 * <p>The schema was {@code CaseAttachment} until #49's phase 2 gave documentation pages a second
 * owner; it is one schema for all three authored-content owners now, and this record is its
 * {@code CASE} half. {@code documentation.DocPageAttachmentView} is the other one that exists today
 * — a second record rather than a shared one because {@code repository} and {@code documentation}
 * cannot import each other, and {@code storage} (which both may reach) declares {@code tenancy}
 * alone and has no business knowing what a route or a {@code UserRef} is. <strong>A field added to
 * one and not the other is a response that omits it on one owner</strong>, and nothing but the
 * contract test observes that.
 *
 * @param url where the bytes are. Absolute-from-root, built here rather than by the SPA, for the
 *            reason {@code EvidenceView} gives: a client that composes the path is a second place
 *            the route is spelled, and a route change then breaks a download with a 404 that looks
 *            like a missing file rather than like a missing endpoint.
 * @param sizeBytes what the row says the file is. Rendered by {@code lib/format.ts} and not
 *            formatted here — the server has no business deciding whether 1024 is "1 KB" or "1 KiB",
 *            and there is exactly one place in the SPA that already decides.
 * @param uploadedBy who attached it, as a {@code UserRef} rather than a bare uuid. §100's rule and
 *            §121's finding: {@code quarantinedBy} was the only bare-uuid actor field on this API
 *            against ~25 {@code UserRef}s, under a javadoc claiming it followed the convention it
 *            broke. Null when {@code created_by} is null, which {@code V1} permits and a row written
 *            by a future system actor would use.
 */
public record CaseAttachmentView(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        String url,
        Instant uploadedAt,
        UserRefView uploadedBy) {

    static CaseAttachmentView of(
            UUID projectId, UUID caseId, Attachment row, UserRefView uploader) {

        return new CaseAttachmentView(
                row.getId(),
                row.getFilename(),
                row.getContentType(),
                row.getSizeBytes(),
                "/api/v1/projects/" + projectId + "/cases/" + caseId + "/attachments/"
                        + row.getId() + "/content",
                row.getCreatedAt(),
                uploader);
    }
}
