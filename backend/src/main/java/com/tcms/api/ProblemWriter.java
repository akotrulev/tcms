package com.tcms.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Writes a {@link Problem} onto a raw {@link HttpServletResponse} — for the refusals that never
 * reach Spring MVC's view resolution.
 *
 * <p>Two callers, and neither has a controller: a <strong>filter</strong> refusing before dispatch
 * ({@code auth.RateLimitFilter}'s 429), and the container-error path (§146), which runs after the
 * dispatcher has already given up. Everything with a handler returns a {@code Problem} from the
 * method and lets the message converters do this.
 *
 * <p>It exists because the alternative was observed: {@code RateLimitFilter} composed the JSON as a
 * <strong>string literal</strong>, which is the one spelling of the envelope where renaming a field
 * in the contract is caught by no compiler, no test that reads the record, and no reader who is not
 * looking directly at it. Four constants and a number is exactly the size at which hand-composing
 * looks cheaper than it is.
 */
@Component
public class ProblemWriter {

    private final ObjectMapper json;

    public ProblemWriter(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Sets the status, the content type and the body.
     *
     * <p>Deliberately does <strong>not</strong> touch any other header: the 429 carries
     * {@code Retry-After}, which is the caller's to set before calling this, and a writer that
     * decided headers on a caller's behalf would be a second place the refusal is composed.
     */
    public void write(HttpServletResponse response, Problem problem) throws IOException {
        response.setStatus(problem.status());
        // `application/problem+json` since §162, matching every other refusal in this API. It was
        // `application/json` here, and Spring's ProblemDetail sites were already sending the other
        // one — two media types for one envelope, which the contract described as neither.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getWriter(), problem);
    }
}
