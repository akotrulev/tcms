package com.tcms.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

/**
 * The body {@code /error} answers with — so a failure the <strong>container</strong> resolved is
 * shaped like one a controller refused.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Five failures never reach a handler: an unreadable body, malformed JSON, a wrong
 * {@code Content-Type}, a wrong method and an unmapped path. All are resolved by
 * {@code DefaultHandlerExceptionResolver} calling {@code response.sendError}, which begins the
 * container's ERROR dispatch against {@code /error} — and Boot's {@code BasicErrorController}
 * renders whatever {@link org.springframework.boot.web.servlet.error.ErrorAttributes} returns.
 * The default is {@code {timestamp, status, error, path}}: a shape this contract never declares, on
 * the paths a machine caller hits most.
 *
 * <p>§143 fixed the <em>status</em> on those five (they had all been 401 for an API token) and
 * §145's live pass found the <em>shape</em> still the container's. That residue is what this bean
 * removes: the caller now gets `Problem` whichever layer refused it, so {@code @tcms/client}'s
 * {@code describeRefusal} and the SPA's {@code describeError} have one shape to read rather than
 * two, and neither has to special-case the failures it is least able to interpret.
 *
 * <h2>The detail is curated per status, never the exception's message</h2>
 *
 * <p>The tempting implementation forwards {@code getError(webRequest).getMessage()}, which is how a
 * caller ends up holding a Jackson message naming an internal class and a JSON pointer, or a
 * database constraint name. The map below is a fixed sentence per status, in the vocabulary the rest
 * of this API uses, and it says what to <em>do</em>. A status this map does not know answers the
 * status' own reason phrase and nothing else — a new failure mode should read as terse, never as a
 * stack trace.
 *
 * <h2>What it deliberately does not touch</h2>
 *
 * <p><strong>An empty 404 stays empty.</strong> A controller answering
 * {@code ResponseEntity.notFound().build()} writes its status directly and never calls
 * {@code sendError}, so it does not pass through here at all — which is what keeps §6t's rule
 * intact: "ungranted", "another company's" and "does not exist" must be indistinguishable, and a
 * body explaining a 404 is an oracle even when the explanation is generic. The 404 this class does
 * shape is the other one: a path with no mapping, where there is no resource to be coy about.
 */
@Component
public class ProblemErrorAttributes extends DefaultErrorAttributes {

    /**
     * The five the container resolves, plus the two ends of the range.
     *
     * <p>Each pair is {@code {title, detail}}. The wording follows the same rule as
     * {@link Problem}'s factories — say what the caller should do next, and never name an internal.
     */
    private static final Map<Integer, String[]> KNOWN = Map.of(
            400, new String[] {
                "The request could not be read",
                "The body is not valid JSON, or a field holds a value of the wrong type. Check the "
                        + "request against the schema in the API contract." },
            404, new String[] {
                "No such endpoint",
                "There is no operation at this path. Check the method and the path against the API "
                        + "contract." },
            405, new String[] {
                "Method not allowed",
                "That HTTP method is not supported at this path. Check the API contract for the "
                        + "one this operation uses." },
            406, new String[] {
                "Not acceptable",
                "This API answers application/json. Adjust the Accept header." },
            415, new String[] {
                "Unsupported content type",
                "This operation reads application/json — except the upload endpoints, which read "
                        + "multipart/form-data. Set Content-Type accordingly." },
            500, new String[] {
                "Something went wrong",
                "The request failed on the server and nothing about it is actionable from here. If "
                        + "it repeats, the server log for this timestamp has the detail." });

    @Override
    public Map<String, Object> getErrorAttributes(
            WebRequest webRequest, ErrorAttributeOptions options) {

        // The superclass is still called: it is what resolves the status from the request
        // attributes, and reimplementing that lookup would be a second answer to the one question
        // this class is not trying to change.
        Map<String, Object> defaults = super.getErrorAttributes(webRequest, options);
        int status = defaults.get("status") instanceof Integer value ? value : 500;

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", titleOf(status));
        problem.put("status", status);
        problem.put("detail", detailOf(status));
        return problem;
    }

    private static String titleOf(int status) {
        String[] known = KNOWN.get(status);
        return known != null ? known[0] : reasonPhraseOf(status);
    }

    private static String detailOf(int status) {
        String[] known = KNOWN.get(status);
        return known != null ? known[1] : "The request was refused.";
    }

    private static String reasonPhraseOf(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved != null ? resolved.getReasonPhrase() : "Request failed";
    }
}
