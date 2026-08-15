package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code ArtifactInput} in {@code docs/specs/openapi.yaml} — the registration payload.
 *
 * <p>{@code status} is deliberately not a field, for the third time in this module and the same
 * §6g reason: a state an ordinary create could set is an approval its author granted themselves.
 * Every registration begins {@link ArtifactStatus#ACTIVE}.
 *
 * @param kind  narrower than {@link ArtifactKind} — only the two <em>reference</em> kinds are
 *              registrable. A {@code FILE} needs the storage port (open question #28) and a
 *              {@code GENERATED_REPORT} needs the {@code reporting} module; both are declared on
 *              the response because the register must render a row of any kind, and refused here
 *              because this endpoint cannot honour them. Typed as the full enum rather than a second
 *              two-value one so that Jackson's failure for an unknown string stays a 400 about
 *              {@code kind}, with {@link ArtifactWriteService} rejecting the two it will not write
 * @param planId    required when {@code scope} is {@link ArtifactScope#PLAN}, rejected otherwise
 * @param docPageId required when {@code kind} is {@link ArtifactKind#DOC_PAGE_REF}
 * @param url       required when {@code kind} is {@link ArtifactKind#EXTERNAL_URL}
 * @param supersedes the artifact this one replaces, or {@code null} for an ordinary registration.
 *                  <strong>The only writer of {@link ArtifactStatus#SUPERSEDED}</strong> (§209):
 *                  the replacement and the replacement's effect on the old row are one request, so
 *                  there is no window in which a newer document exists and the register still calls
 *                  the old one current. It must sit at the same scope and — at {@link
 *                  ArtifactScope#PLAN} — in the same plan, which is what makes the create's own
 *                  qualifier gate sufficient for both rows
 */
public record ArtifactInput(
        ArtifactScope scope,
        UUID planId,
        String category,
        ArtifactKind kind,
        String title,
        String description,
        UUID docPageId,
        String url,
        UUID supersedes) {
}
