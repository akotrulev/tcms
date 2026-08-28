package com.tcms.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * {@code Problem} in {@code docs/specs/openapi.yaml} — <s>the error envelope <strong>every</strong>
 * refusal in this API answers with, whether a controller wrote it or the container did.</s>
 *
 * <p><strong>→ corrected §162: it is one of two, and the smaller one.</strong> Eight production
 * files return this record; <strong>twenty-one</strong> return Spring's {@code ProblemDetail}, each
 * arguing for it in a header of its own ({@code RunController}: <em>"serializes to exactly the
 * {@code Problem} the contract declares"</em>). Both claims were written in good faith and neither
 * was checked, and they are not merely two spellings: Jackson gives {@code ProblemDetail} the
 * default content type {@code application/problem+json} and this record {@code application/json},
 * and {@code ProblemDetail} carries an {@code instance} property this record has no component for.
 * <strong>132 of the contract's 176 declared error bodies named the wrong media type as a result</strong>,
 * in both directions at once. {@link ProblemEnvelope} closes the media-type half; <s>{@code instance}
 * is declared optional in the contract and its reconciliation is recorded there as a decision.</s>
 *
 * <p><strong>→ §163 took that decision, and this record won the argument.</strong> {@code instance}
 * is suppressed rather than added, so the two envelopes now differ in Java type and in nothing a
 * caller can observe. It matters which way round that went: the missing component here was not a gap
 * to be filled — a record cannot know the request, and the three ways to give it one are the
 * hand-written composition §146 deleted. See {@link ProblemEnvelope} for the argument in full.
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
 * <h2>2026-08-28 (005 C1a) — three members, and the §162–§164 argument reopened once</h2>
 *
 * <p>~~{@code errors} is the one optional member.~~ → four are: {@code errors}, {@code permission},
 * {@code scope} and {@code line}. §141's rule still decides what a refusal carries — a member is
 * earned by what the caller can do with it — and two things a caller can now do earned these. A 403
 * whose {@code permission} and {@code scope} are structured lets every screen draw one Refused state
 * (<em>"needs case:approve on this project — ask a Company Admin"</em>) without parsing prose that no
 * two controllers word alike; a gherkin refusal whose {@code line} is structured lets the editor
 * anchor the sentence to the line it is about (003 C4). Both envelopes carry them:
 * {@link #forbidden(String, String, Scope)} for this record and {@link #forbiddenDetail} for the
 * {@code ProblemDetail} writers, so the property does not depend on which writer produced it —
 * §162's whole point. {@code ErrorBodyPolicyTest} pins the seven names in both directions.
 *
 * @param permission <strong>on a 403 only</strong>: the catalogue key the caller lacks, exactly as the
 *                   roles spec spells it. Null, and therefore omitted, on every other status. See
 *                   {@link #forbidden(String, String, Scope)} for why naming the key stopped being
 *                   the disclosure the {@link #forbidden(String)} javadoc once said it was.
 * @param scope      beside {@code permission}: the scope the key is evaluated at, so the copy can say
 *                   "on this project" rather than guessing from the URL. Null wherever
 *                   {@code permission} is.
 * @param line       <strong>on the four gherkin refusals only</strong>: the 1-based line of the pasted
 *                   text the refusal is about. Every refusal this module's factories write sends
 *                   null; the repository module's parse and render endpoints fill it from
 *                   {@code GherkinSyntaxException}.
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
        String permission,
        Scope scope,
        Integer line,
        Map<String, List<String>> errors) {

    /**
     * The scope a missing permission is evaluated at — {@code Problem.scope} in the contract.
     *
     * <p>Declared here rather than as a reference to {@code identity.RoleScope} because {@code api} is
     * the module every other one imports and may import nothing itself; the two enums spell the same
     * three names and {@code ContractEnumTest} pins this one to the contract.
     */
    public enum Scope { SYSTEM, COMPANY, PROJECT }

    /**
     * The shape every writer used before 2026-08-28 — no permission, no scope, no line. Kept so the
     * repository module's six hand-built refusals (the gherkin parse family and the import
     * conflicts) did not have to change in the slice that added the members; the one that earns
     * {@code line} switches to the canonical constructor when it fills it.
     */
    public Problem(String type, String title, int status, String detail, Map<String, List<String>> errors) {
        this(type, title, status, detail, null, null, null, errors);
    }

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
                null,
                null,
                null,
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
                null,
                null,
                null,
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
        return new Problem(BLANK_TYPE, title, 409, detail, null, null, null, null);
    }

    /**
     * The per-token rate limit refusing before dispatch (§141).
     *
     * <p>The wait belongs in {@code detail} as well as in {@code Retry-After}: {@code @tcms/cli}
     * prints {@code detail} verbatim, and a model handed a refusal with no number in it retries
     * immediately — which is the loop the limit exists to prevent, one refusal later.
     */
    public static Problem tooManyRequests(String detail) {
        return new Problem(BLANK_TYPE, "Too many requests", 429, detail, null, null, null, null);
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
        return new Problem(BLANK_TYPE, title, 422, detail, null, null, null, null);
    }

    /**
     * A 403 that does not name the key — the shape for a refusal that is not about a catalogue
     * permission at all (an authorship rule, a state the target is in), where {@code permission}
     * has nothing true to say. A refusal that <em>is</em> about a key uses the three-argument form.
     *
     * @param detail what the caller cannot do, in their words. ~~<strong>never the permission
     *               key</strong>. A key is not actionable by someone who cannot grant it to
     *               themselves, and naming it publishes the catalogue to exactly the caller who is not
     *               entitled to it.~~ → superseded 2026-08-28 (005 C1a): the key travels in
     *               {@code permission}, structured, and the catalogue was never a secret — the same
     *               caller reads every key it holds from {@code GET /me/permissions} and every key
     *               that exists from the roles screen. What stays true is the first half: the key
     *               is not the <em>sentence</em>, so {@code detail} still says what the caller
     *               cannot do and the client says who to ask.
     */
    public static Problem forbidden(String detail) {
        return new Problem(BLANK_TYPE, "Not allowed", 403, detail, null, null, null, null);
    }

    /**
     * The ordinary 403: the caller lacks {@code permission} at {@code scope}.
     *
     * @param permission the catalogue key as the roles spec spells it — {@code case:approve}, never a
     *                   role name; the client labels it from the same table it labels
     *                   {@code GET /me/permissions} from
     */
    public static Problem forbidden(String detail, String permission, Scope scope) {
        return new Problem(BLANK_TYPE, "Not allowed", 403, detail, permission, scope, null, null);
    }

    /**
     * The same 403 for the {@code ProblemDetail} writers, so both envelopes serialize the same
     * members — the property lands through {@code setProperty}, the way {@code errors} already does
     * on their validation shape.
     */
    public static ProblemDetail forbiddenDetail(String detail, String permission, Scope scope) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Not allowed");
        problem.setDetail(detail);
        problem.setProperty("permission", permission);
        problem.setProperty("scope", scope);
        return problem;
    }
}
