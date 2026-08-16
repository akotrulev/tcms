package com.tcms.documentation;

import com.tcms.api.UserRef;
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
 *                   link for {@link ArtifactKind#EXTERNAL_URL}, since §126 phase 3 the download
 *                   path for {@link ArtifactKind#FILE}, and since audit C6 the <em>report</em>
 *                   download for {@link ArtifactKind#GENERATED_REPORT}. {@code null} only for
 *                   {@link ArtifactKind#DOC_PAGE_REF}, which the SPA reaches by id instead.
 *                   <strong>The report's route is gated as a report</strong> ({@code report:export}),
 *                   not as an artifact: pinning one records that it was produced and does not
 *                   re-license carrying it off, so a reader with {@code artifact:read} alone sees
 *                   the entry and is refused the bytes. Widening the field rather than adding a second one is
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
 * @param supersededById the artifact that replaced this one, and {@code null} for every row that is
 *                   not {@link ArtifactStatus#SUPERSEDED}. On the wire since §209 because a status
 *                   the reader cannot follow is a dead end: "superseded" without *by what* sends
 *                   somebody scanning the register for a title they have to guess. The replacement
 *                   is in the same register and at the same scope by construction, so the client
 *                   resolves it from the rows it already holds rather than fetching
 * @param writeOnce  a sign-off record, which the register keeps as it was signed: every edit and
 *                   every delete on this row is a 409, and correcting it means superseding it. On
 *                   the wire since audit C7 and the reason is §31 — the column has been enforced by
 *                   two refusals the client had <strong>no way to predict</strong>, so Correct… and
 *                   Remove… enabled on every sign-off row and the refusal arrived after the user
 *                   had written the correction
 */
public record ArtifactView(
        UUID id,
        ArtifactScope scope,
        String category,
        ArtifactKind kind,
        String title,
        String description,
        ArtifactStatus status,
        UUID supersededById,
        boolean inherited,
        String url,
        UUID docPageId,
        String filename,
        Long sizeBytes,
        boolean writeOnce,
        UserRef updatedBy,
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
            Map<UUID, UserRef> people) {

        return new ArtifactView(
                artifact.getId(),
                artifact.getScope(),
                artifact.getCategory(),
                artifact.getKind(),
                artifact.getTitle(),
                artifact.getDescription(),
                artifact.getStatus(),
                artifact.getSupersededById(),
                inherited,
                url(projectId, artifact, file),
                artifact.getDocPageId(),
                file == null ? null : file.getFilename(),
                file == null ? null : file.getSizeBytes(),
                artifact.isWriteOnce(),
                people.get(artifact.getUpdatedBy()),
                artifact.getUpdatedAt());
    }

    /**
     * Where this row's document is, over three kinds and three routes.
     *
     * <p>Ordered by what is <em>present on the row</em> rather than by {@code kind}, which is the
     * projection's existing rule stated once now that there are three arms: a row either has an
     * attachment or does not, and keying the render on the enum produces a {@code FILE} with bytes
     * it declined to describe the day the two disagree.
     */
    private static String url(UUID projectId, Artifact artifact, Attachment file) {
        if (file != null) {
            return contentPath(projectId, artifact.getId());
        }
        if (artifact.getReportArtifactId() != null) {
            return reportContentPath(projectId, artifact.getReportArtifactId());
        }
        return artifact.getExternalUrl();
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

    /**
     * A pinned report's bytes — {@code reporting}'s download route, addressed by the
     * <em>report</em> artifact rather than by this register row.
     *
     * <p>The register does not re-serve the report and must not: those bytes are immutable
     * ({@code ReportArtifact}), they are already addressable, and a second route to them would be a
     * second gate to keep in step with the first. What this composes is therefore a route another
     * module owns — the only such string in this module — which is a coupling worth stating rather
     * than an accident. The alternative was to serve the id and let the client build the path, and
     * that is exactly what this method's siblings exist to prevent (§67, {@code Evidence.url}): a
     * composed path is a second copy of the server's routing, and moving the route becomes a
     * breaking change nothing detects.
     */
    private static String reportContentPath(UUID projectId, UUID reportArtifactId) {
        return "/api/v1/projects/" + projectId + "/reports/artifacts/" + reportArtifactId
                + "/content";
    }
}
