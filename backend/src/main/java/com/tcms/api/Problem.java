package com.tcms.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * {@code Problem} in {@code docs/specs/openapi.yaml} — the error envelope <strong>every</strong>
 * refusal in this API answers with, whether a controller wrote it or the container did.
 *
 * <h2>~~A second copy, and it is recorded as one rather than defended~~ → extracted in §146</h2>
 *
 * <p>This record lived in {@code repository} and was <strong>package-private</strong>, so the six
 * modules that needed it could not call it — they referred to it in <em>prose</em>
 * ({@code ProjectController}, {@code RunController}, {@code DocPageController},
 * {@code ReportController} each carry a paragraph about it) and wrote their own. Its old header
 * called the duplication <em>"deliberate, visible, and the extraction is the follow-up"</em>; this is
 * the follow-up, and what forced it was a <em>third</em> caller with no module to live in at all —
 * §146's container-error rendering, which serves failures Spring resolves before any controller runs.
 *
 * <p>Three spellings were collapsed into this one: this record, {@code ProblemBody} in
 * {@code auth.InvitationController} (the same shape minus {@code errors}), and a hand-written JSON
 * <em>string literal</em> in {@code auth.RateLimitFilter} — which is the form that shows what the
 * duplication costs, since a field renamed in the contract would have been found by no compiler.
 *
 * @param errors field name to messages, present only on a validation rejection. Serialized as
 *               {@code null} and omitted from the JSON when absent, so a 409 or a 404 does not carry
 *               an empty errors object that a client might read as "no fields were wrong" rather than
 *               "fields are not what this is about".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        String type,
        String title,
        int status,
        String detail,
        Map<String, List<String>> errors) {

    /** RFC 7807's "no further type information" URI, which is what every problem here is. */
    private static final String BLANK_TYPE = "about:blank";

    public static Problem validation(Map<String, List<String>> errors) {
        return new Problem(
                BLANK_TYPE,
                "Validation failed",
                400,
                // The SPA renders the per-field messages beside the inputs, so this line must not
                // repeat them — it says where to look, and the fields say what is wrong.
                "Check the highlighted fields.",
                errors);
    }

    /** A lost-update refusal on a case. Kept as the no-argument form because it has one caller. */
    public static Problem conflict() {
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
    public static Problem stale(String noun) {
        return new Problem(
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
    public static Problem conflict(String title, String detail) {
        return new Problem(BLANK_TYPE, title, 409, detail, null);
    }

    /**
     * The per-token rate limit refusing before dispatch (§141).
     *
     * <p>The wait belongs in {@code detail} as well as in {@code Retry-After}: {@code @tcms/cli}
     * prints {@code detail} verbatim, and a model handed a refusal with no number in it retries
     * immediately — which is the loop the limit exists to prevent, one refusal later.
     */
    public static Problem tooManyRequests(String detail) {
        return new Problem(BLANK_TYPE, "Too many requests", 429, detail, null);
    }

    /**
     * A body the server understood and will not act on — the shape {@code auth.InvitationController}
     * answers a rejected password with.
     *
     * <p>No {@code errors} map, deliberately, and it is the one refusal on this surface where that
     * is a security property rather than a shortcut: the detail describes the password the caller
     * just submitted and therefore leaks nothing, while a field map keyed on anything about the
     * <em>account</em> would say whether one exists.
     */
    public static Problem unprocessable(String title, String detail) {
        return new Problem(BLANK_TYPE, title, 422, detail, null);
    }

    /**
     * @param detail what the caller cannot do, in their words — <strong>never the permission
     *               key</strong>. A key is not actionable by someone who cannot grant it to
     *               themselves, and naming it publishes the catalogue to exactly the caller who is not
     *               entitled to it. Passed in rather than fixed because the action differs per
     *               endpoint and "you cannot do that" tells a user nothing about which of the three
     *               things on the screen was refused.
     */
    public static Problem forbidden(String detail) {
        return new Problem(BLANK_TYPE, "Not allowed", 403, detail, null);
    }
}
