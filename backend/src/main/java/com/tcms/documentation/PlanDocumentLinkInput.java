package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code PlanDocumentLinkInput} in {@code docs/specs/openapi.yaml} — the body of the plan
 * document's {@code PUT}.
 *
 * <p>A {@link UUID} rather than a {@code String}, unlike {@link BlockLinkInput}'s two vocabulary
 * fields: there is nothing to parse tolerantly here and nothing a picker can mistype into a
 * different meaning. A malformed id is Jackson's 400 about a type, which is the right answer to a
 * client that sent something no picker in this product could produce.
 *
 * @param pageId a page <strong>of this project</strong>. A sibling project's page is a 404 rather
 *               than a 400 or a 409 — RLS reaches across the company, so the row is readable and
 *               only the project predicate refuses it, and a distinguishing error would confirm to
 *               a caller that a page they cannot see exists. Null is a field error, not a
 *               {@code NullPointerException} three statements later.
 */
public record PlanDocumentLinkInput(UUID pageId) {
}
