package com.tcms.documentation;

import com.tcms.api.UserRef;
import com.tcms.storage.Attachment;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code Attachment} in {@code docs/specs/openapi.yaml} — one file on a documentation page.
 *
 * <h2>One schema on the wire, one record per module</h2>
 *
 * <p>The contract has a <strong>single</strong> {@code Attachment} schema serving every owner that
 * is not evidence, and this record is its {@code DOC_PAGE} half. That split is not an oversight in
 * either direction.
 *
 * <p>The wire is one schema because the three shapes are identical field for field <em>and</em>
 * meaning for meaning — only the value of {@link #url} differs — and the SPA renders them with one
 * component. Three structurally-identical generated types would make that component generic over
 * nothing, and would be this product's own polymorphism-by-duplication re-created by hand on the
 * outside of the module that just removed it: {@code storage.Attachment} is one entity for five
 * owners.
 *
 * <p>The Java side is one record per module because a shared one has nowhere to live.
 * {@code documentation} may not import {@code repository}, and {@code storage} — the only module
 * both can reach — declares {@code tenancy} alone and holds no domain concept, so putting a view
 * there would teach it the API's route shapes and require it to know what a {@code UserRef} is.
 * ~~{@link UserRef} is already duplicated across four modules for exactly this reason; this is the
 * same trade, taken the same way.~~
 *
 * <p><strong>→ §147: the second sentence's example moved out from under it, and the first sentence
 * did not.</strong> {@code UserRef} lives in {@code com.tcms.api} now, where {@link #uploadedBy}
 * imports it from — so the trade this header cites as precedent is one this codebase no longer
 * takes for <em>that</em> shape. It still takes it for this one, and deliberately: {@code api}'s
 * charter admits a type only if the contract defines it and more than one module <em>serves</em> it.
 * The contract defines no shared attachment schema — it defines
 * {@code CaseAttachment} and {@code DocPageAttachment} as two schemas that happen to agree — so
 * moving this record there would not be an extraction but a contract change wearing one, and the
 * decision about whether the API has one attachment shape or three belongs in {@code openapi.yaml}
 * rather than in a Java package. If that decision is ever taken, this record and
 * {@code repository.CaseAttachmentView} are what it collapses.
 *
 * <p><strong>The mapping is what keeps the two honest, and it is deliberately not checked by
 * anything but the contract test.</strong> A field added here and not to
 * {@code repository.CaseAttachmentView} is a response that omits it on one owner — see the mutation
 * queue's {@code DA-9}.
 *
 * @param url where the bytes are, absolute from the API root and built here rather than by the SPA.
 *            A client that composes the path is a second place the route is spelled, and a route
 *            change then breaks the download with a 404 that reads as a missing file.
 * @param uploadedBy who attached it, as a {@code UserRef} and not a bare uuid — §100's convention and
 *            §121's finding. Null when {@code attachment.created_by} is null, which {@code V1}
 *            permits.
 */
public record DocPageAttachmentView(
        UUID id,
        String filename,
        String contentType,
        long sizeBytes,
        String url,
        Instant uploadedAt,
        UserRef uploadedBy) {

    static DocPageAttachmentView of(
            UUID projectId, UUID pageId, Attachment row, UserRef uploader) {

        return new DocPageAttachmentView(
                row.getId(),
                row.getFilename(),
                row.getContentType(),
                row.getSizeBytes(),
                "/api/v1/projects/" + projectId + "/doc-pages/" + pageId + "/attachments/"
                        + row.getId() + "/content",
                row.getCreatedAt(),
                uploader);
    }
}
