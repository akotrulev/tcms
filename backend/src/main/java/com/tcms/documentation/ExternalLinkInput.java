package com.tcms.documentation;

/**
 * {@code ExternalLinkInput} in {@code docs/specs/openapi.yaml} — the body of an external link
 * create.
 *
 * <p>{@link BlockLinkInput}'s counterpart at the other end of {@code doc_link}, and Strings for the
 * same reason: an unbound enum produces Jackson's own 400 describing a Java type, where this
 * endpoint's job is to answer a form with the field that is wrong.
 *
 * <p><strong>One record for both external kinds, unlike {@link DocLink}'s two factories.</strong>
 * A request body is what a client sends and a client sends one JSON object; splitting it into
 * {@code GitLinkInput} and {@code UrlLinkInput} would need either two endpoints or a discriminated
 * union the generated TypeScript would surface as an untagged either — and the field that decides
 * is on the wire regardless, because {@code targetKind} is what the row stores. The invalid
 * combinations are refused by {@link ExternalLinkService} with a message per field, which is where
 * a form can act on them; the type is the wire shape and not the rule.
 *
 * @param targetKind    {@code EXTERNAL_GIT} or {@code EXTERNAL_URL}. Required — there is no default,
 *                      because guessing from which fields are populated is how a request with both
 *                      a URL and a path gets silently interpreted rather than refused.
 * @param relationship  null means {@code VERIFIES}, the contract's default and the column's, exactly
 *                      as on a block link.
 */
public record ExternalLinkInput(
        String targetKind,
        String relationship,
        String repo,
        String path,
        Integer lineStart,
        Integer lineEnd,
        String commitSha,
        String url) {
}
