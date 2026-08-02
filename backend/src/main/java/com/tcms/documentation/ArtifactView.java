package com.tcms.documentation;

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
 * @param url        the external link, for {@link ArtifactKind#EXTERNAL_URL} only
 * @param docPageId  the page, for {@link ArtifactKind#DOC_PAGE_REF} only
 * @param filename   always {@code null} today, and <strong>on the wire anyway</strong> because the
 *                   contract declares it and the register renders a size column for uploads. It is
 *                   the one place this slice serializes a null rather than omitting the field: the
 *                   value is genuinely unknown-because-absent (no row is a {@code FILE}), not
 *                   unknown-because-unasked, which is the distinction §57 drew for {@code coverage}
 * @param sizeBytes  likewise
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
     * @param people   resolved display names; a row whose {@code updated_by} names no account gets
     *                 {@code null} rather than an invented person, and the SPA renders the row
     *                 without an author. {@code updatedBy} is {@code required} in the contract, so
     *                 this is the one field where the schema is stricter than the column — see
     *                 {@link ArtifactPeople#resolve} for why the column can be dangling, and
     *                 {@link ArtifactService} for the deliberate choice not to hide such a row
     */
    static ArtifactView of(
            Artifact artifact, boolean inherited, Map<UUID, UserRefView> people) {

        return new ArtifactView(
                artifact.getId(),
                artifact.getScope(),
                artifact.getCategory(),
                artifact.getKind(),
                artifact.getTitle(),
                artifact.getDescription(),
                artifact.getStatus(),
                inherited,
                artifact.getExternalUrl(),
                artifact.getDocPageId(),
                null,
                null,
                people.get(artifact.getUpdatedBy()),
                artifact.getUpdatedAt());
    }
}
