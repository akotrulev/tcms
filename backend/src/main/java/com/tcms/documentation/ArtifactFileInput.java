package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code ArtifactFileInput} in {@code docs/specs/openapi.yaml} — the JSON part beside the bytes when
 * a {@link ArtifactKind#FILE} is registered.
 *
 * <h2>Why this is not {@link ArtifactInput} with a file bolted on</h2>
 *
 * <p>It carries no {@code kind}, no {@code url} and no {@code docPageId}, and each omission removes
 * a request that could otherwise be sent and would have to be refused. The kind is {@code FILE} by
 * construction — there is no field to disagree with the endpoint — which is
 * {@link ArtifactInput#kind()}'s own argument applied one level further: that field is typed as the
 * full enum so an unknown string is a 400 rather than a silent {@code null}, and the two kinds the
 * JSON endpoint will not write are refused in the service. Here there is nothing to refuse, because
 * a client generating types from the contract cannot express the disagreement.
 *
 * <p>The cost is five fields spelled twice, and it is worth naming rather than hiding. The
 * alternative — one input record and a runtime rule that {@code kind} must be {@code FILE} on this
 * path and must not be on the other — is a rule that lives in prose and is checked in one place,
 * against a shape that invites the mistake. Everything the two records <em>share</em> is validated
 * by one method: {@link #asRegistration()} widens this into an {@link ArtifactInput}, so title,
 * category, description and scope rules are the register's, not this endpoint's second opinion.
 *
 * @param scope  {@code PROJECT} or {@code PLAN}, gated exactly as the reference create is — a Test
 *               Engineer may upload a cycle's sign-off and not the project's test strategy
 * @param planId required when {@code scope} is {@link ArtifactScope#PLAN}, rejected otherwise
 * @param supersedes as {@link ArtifactInput#supersedes()}, and the field this endpoint's own help
 *               text has been promising since §126 phase 3: "the bytes are fixed at registration —
 *               replacing the document means registering a new artifact that supersedes this one"
 *               described an act nothing could perform until §209. A replaced upload does not have
 *               to be replaced by another upload; kinds are free to differ, because what is being
 *               recorded is that one document took over from another
 * @param writeOnce as {@link ArtifactInput#writeOnce()} — and <strong>this is the path a sign-off
 *               usually arrives by</strong>, a countersigned PDF being bytes rather than a link.
 *               Both creates take it because the promise is about the register's entry, not about
 *               where the document is kept
 */
public record ArtifactFileInput(
        ArtifactScope scope,
        UUID planId,
        String category,
        String title,
        String description,
        boolean writeOnce,
        UUID supersedes) {

    /**
     * The same registration, in the shape the shared validation and the entity factory already
     * take.
     *
     * <p>{@code kind} is {@link ArtifactKind#FILE} and both reference payloads are {@code null},
     * which is what makes the cross-field rules answer correctly without knowing which endpoint they
     * were reached from.
     */
    ArtifactInput asRegistration() {
        return new ArtifactInput(
                scope, planId, category, ArtifactKind.FILE, title, description, null, null, null,
                writeOnce, supersedes);
    }
}
