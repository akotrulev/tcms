package com.tcms.documentation;

/**
 * {@code ArtifactStatusInput} in {@code docs/specs/openapi.yaml}.
 *
 * <p>A {@code String} rather than an {@link ArtifactStatus}, for {@link DocPageStatusInput}'s reason
 * and one more of its own. The shared reason: an unknown value becomes a field error naming the
 * values this endpoint takes, instead of Jackson's own 400 about a JSON parse, which reaches the SPA
 * as "check your input" with nothing highlighted.
 *
 * <p>The one of its own: the endpoint accepts a <em>proper subset</em> of the enum, so typing this
 * as {@link ArtifactStatus} would declare {@code SUPERSEDED} acceptable and then refuse it — a
 * contract that offers a value the server will not take. That state is a fact about another row (see
 * {@link ArtifactStatus#SUPERSEDED}); nothing chooses it and nothing leaves it.
 */
public record ArtifactStatusInput(String status) {
}
