package com.tcms.repository;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * {@code Problem} in {@code docs/specs/openapi.yaml} — the error envelope this module answers with.
 *
 * <h2>A second copy, and it is recorded as one rather than defended</h2>
 *
 * <p>{@code InvitationController.ProblemBody} is the same shape without {@code errors}. Two copies of
 * an error envelope is the beginning of the failure the working agreement names about display logic:
 * a hundred consistent responses becoming a hundred slightly different ones. The right home is a
 * {@code shared} module, which the architecture lists and which does not exist yet — creating it
 * means a package, a Modulith declaration, and adding it to every module's
 * {@code allowedDependencies}. That is a bigger change than this slice should carry quietly, so the
 * duplication is deliberate, visible, and the extraction is the follow-up.
 *
 * @param errors field name to messages, present only on a validation rejection. Serialized as
 *               {@code null} and omitted from the JSON when absent, so a 409 or a 404 does not carry
 *               an empty errors object that a client might read as "no fields were wrong" rather than
 *               "fields are not what this is about".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ApiProblem(
        String type,
        String title,
        int status,
        String detail,
        Map<String, List<String>> errors) {

    /** RFC 7807's "no further type information" URI, which is what every problem here is. */
    private static final String BLANK_TYPE = "about:blank";

    static ApiProblem validation(Map<String, List<String>> errors) {
        return new ApiProblem(
                BLANK_TYPE,
                "Validation failed",
                400,
                // The SPA renders the per-field messages beside the inputs, so this line must not
                // repeat them — it says where to look, and the fields say what is wrong.
                "Check the highlighted fields.",
                errors);
    }

    /** A lost-update refusal on a case. Kept as the no-argument form because it has one caller. */
    static ApiProblem conflict() {
        return stale("case");
    }

    /**
     * A lost-update refusal — the {@code If-Match} the client sent is not the stored version.
     *
     * @param noun what to reload, in the user's vocabulary. Parameterized because the copy tells the
     *             reader what to do next, and "reload the case" on a suite rename sends them to the
     *             wrong screen — a small wrongness that costs a support conversation, since the
     *             advice is otherwise correct enough to be followed.
     */
    static ApiProblem stale(String noun) {
        return new ApiProblem(
                BLANK_TYPE,
                "Someone else saved first",
                409,
                "Reload the " + noun + " to see the newer version, then reapply your change.",
                null);
    }

    /**
     * A refusal that is not a lost update — the request was well-formed, current, and still cannot
     * be performed in the target's present state.
     *
     * <p>Separate from {@link #stale} because the two 409s want opposite advice: a stale write says
     * "reload and reapply", and reloading does nothing at all for a delete refused because the
     * folder has cases in it. One wording covering both would be the one that fits neither.
     */
    static ApiProblem conflict(String title, String detail) {
        return new ApiProblem(BLANK_TYPE, title, 409, detail, null);
    }

    /**
     * @param detail what the caller cannot do, in their words — <strong>never the permission
     *               key</strong>. A key is not actionable by someone who cannot grant it to
     *               themselves, and naming it publishes the catalogue to exactly the caller who is not
     *               entitled to it. Passed in rather than fixed because the action differs per
     *               endpoint and "you cannot do that" tells a user nothing about which of the three
     *               things on the screen was refused.
     */
    static ApiProblem forbidden(String detail) {
        return new ApiProblem(BLANK_TYPE, "Not allowed", 403, detail, null);
    }
}
