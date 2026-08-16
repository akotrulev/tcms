package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code ArtifactInput} in {@code docs/specs/openapi.yaml} — the registration payload.
 *
 * <p>{@code status} is deliberately not a field, for the third time in this module and the same
 * §6g reason: a state an ordinary create could set is an approval its author granted themselves.
 * Every registration begins {@link ArtifactStatus#ACTIVE}.
 *
 * @param kind  narrower than {@link ArtifactKind}: <del>only the two <em>reference</em> kinds are
 *              registrable</del> → three of the four are, since audit C6 gave
 *              {@link ArtifactKind#GENERATED_REPORT} a writer. {@link ArtifactKind#FILE} is the one
 *              this endpoint refuses, and it is refused for a reason about the <em>endpoint</em>
 *              rather than the kind — the bytes have to travel with the registration. Typed as the
 *              full enum rather than a second three-value one so that Jackson's failure for an
 *              unknown string stays a 400 about {@code kind}, with {@link ArtifactWriteService}
 *              rejecting the one it will not write
 * @param planId    required when {@code scope} is {@link ArtifactScope#PLAN}, rejected otherwise
 * @param docPageId required when {@code kind} is {@link ArtifactKind#DOC_PAGE_REF}
 * @param url       required when {@code kind} is {@link ArtifactKind#EXTERNAL_URL}
 * @param reportJobId required when {@code kind} is {@link ArtifactKind#GENERATED_REPORT}, and
 *              rejected otherwise. <strong>The job, not the artifact</strong>: only the job carries
 *              a project, so it is the only id that can be proved to belong to the path's project —
 *              see {@link ReportFacts}. What is stored is the artifact it produced, which is
 *              immutable, so the register's entry answers the same bytes for ever
 * @param writeOnce a sign-off record: later edits and deletes are refused with a 409 and a
 *              correction supersedes instead (plan §6n). Absent is {@code false}. <strong>Decided
 *              here and never again</strong> — {@link ArtifactEditInput} has no such field and the
 *              column is not updatable, because a flag that can be set later can be set on somebody
 *              else's row, and one that can be taken back is not a promise. Audit C7: the column
 *              has been enforced by the delete since §126 and the edit since §229, with nothing in
 *              the product able to set it
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
        UUID reportJobId,
        boolean writeOnce,
        UUID supersedes) {
}
