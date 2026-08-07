package com.tcms.documentation;

import com.tcms.storage.Attachment;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code Artifact} in {@code docs/specs/openapi.yaml} — one row of the register as the SPA reads it.
 *
 * @param inherited  whether this row belongs to the project rather than the plan being viewed. The
 *                   field plan §6n insists on: a plan's register shows the project's documents
 *                   alongside its own, and a reader who cannot tell them apart duplicates them.
 *                   Always {@code false} in a project-scope request — nothing is inherited when the
 *                   project itself is what you asked for — so it is a property of <em>this
 *                   response</em>, not of the row, which is why it is computed at projection time
 *                   rather than stored.
 * @param url        <strong>where this artifact points, whatever its kind</strong> — the external
 *                   link for {@link ArtifactKind#EXTERNAL_URL}, and since §126 phase 3 the download
 *                   path for {@link ArtifactKind#FILE}. {@code null} for the two kinds the SPA
 *                   reaches by id instead. Widening the field rather than adding a second one is
 *                   deliberate: the alternative is the client composing
 *                   {@code /projects/…/artifacts/…/content} itself, which is a second place the
 *                   route is spelled and turns a route change into a 404 that reads as a missing
 *                   file — the rule {@code DocPageAttachmentView.url} states one owner over. What
 *                   the reader must not lose is *which* of the two it is, and that is {@code kind}'s
 *                   job: an external link opens somebody else's site and a download does not
 * @param docPageId  the page, for {@link ArtifactKind#DOC_PAGE_REF} only
 * @param filename   ~~always {@code null} today~~ → <strong>the uploaded name, since a {@code FILE}
 *                   became registrable.</strong> It was on the wire through three slices with no
 *                   value behind it, on the argument that the null was
 *                   unknown-because-absent rather than unknown-because-unasked (§57's distinction
 *                   for {@code coverage}) — which is why nothing on either side had to change shape
 *                   when the writer arrived. Still {@code null} for every non-{@code FILE} row,
 *                   where it means what it always meant
 * @param sizeBytes  likewise, and the column the register renders for uploads
 */
public record ArtifactView(
        UUID id,
        ArtifactScope scope,
        String category,
        ArtifactKind kind,
        String title,
        String description,
        ArtifactStatus status,
        boolean inherited,
        String url,
        UUID docPageId,
        String filename,
        Long sizeBytes,
        UserRefView updatedBy,
        Instant updatedAt) {

    /**
     * Projects one row.
     *
     * @param file     the blob row for a {@link ArtifactKind#FILE}, or {@code null} for every other
     *                 kind and for a {@code FILE} whose row cannot be found. The projection does not
     *                 consult {@code kind} to decide whether to read it: a row either has an
     *                 attachment or does not, and keying the render on the enum would produce a
     *                 {@code FILE} with bytes it declined to describe the day the two disagreed
     * @param people   resolved display names; a row whose {@code updated_by} names no account gets
     *                 {@code null} rather than an invented person, and the SPA renders the row
     *                 without an author. {@code updatedBy} is {@code required} in the contract, so
     *                 this is the one field where the schema is stricter than the column — see
     *                 {@link ArtifactPeople#resolve} for why the column can be dangling, and
     *                 {@link ArtifactService} for the deliberate choice not to hide such a row
     */
    static ArtifactView of(
            UUID projectId,
            Artifact artifact,
            Attachment file,
            boolean inherited,
            Map<UUID, UserRefView> people) {

        return new ArtifactView(
                artifact.getId(),
                artifact.getScope(),
                artifact.getCategory(),
                artifact.getKind(),
                artifact.getTitle(),
                artifact.getDescription(),
                artifact.getStatus(),
                inherited,
                file == null ? artifact.getExternalUrl() : contentPath(projectId, artifact.getId()),
                artifact.getDocPageId(),
                file == null ? null : file.getFilename(),
                file == null ? null : file.getSizeBytes(),
                people.get(artifact.getUpdatedBy()),
                artifact.getUpdatedAt());
    }

    /**
     * Where the bytes are, absolute from the API root and built here rather than by the SPA.
     *
     * <p>The artifact's own id addresses the download, not the attachment's — the attachment is an
     * implementation of the artifact rather than a thing the register has a route to, and a URL
     * naming it would put a second identifier on screen for one document.
     */
    private static String contentPath(UUID projectId, UUID artifactId) {
        return "/api/v1/projects/" + projectId + "/artifacts/" + artifactId + "/content";
    }
}
