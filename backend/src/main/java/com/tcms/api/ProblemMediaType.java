package com.tcms.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Sends every {@link Problem} under {@code application/problem+json} — the one place the record's
 * media type is decided.
 *
 * <h2>What §162 measured</h2>
 *
 * <p>This API had <strong>two</strong> error envelopes and therefore two media types, and neither
 * fact was written anywhere true. Spring's {@code ProblemDetail} — twenty-one controllers — carries
 * {@code application/problem+json} by construction: {@code AbstractJackson2HttpMessageConverter}
 * returns it as the default content type for that class, and it wins even against an explicit
 * {@code Accept: application/json}, which is what {@code @tcms/client} sends. This record — eight
 * files — is an ordinary bean, so it went out as {@code application/json}.
 *
 * <p>The contract knew about neither. <strong>132 of its 176 declared error bodies named the wrong
 * media type</strong>: 123 said {@code application/json} for a refusal that arrives as
 * {@code problem+json}, and one said {@code problem+json} for the case-attachment 413, which is the
 * single {@code ProblemDetail}-shaped upload handler that was written with this record instead. Both
 * directions wrong in one document, which is §148's finding one property over — there the contract
 * promised a body nothing wrote, here it promised the right body under a type nothing sends.
 *
 * <p>Nothing could see it. There was not one occurrence of the string {@code problem+json} anywhere
 * in {@code backend/src}, main or test, before §162: the media type of a refusal is not asserted by
 * any endpoint test, and both TypeScript consumers call {@code .json()} without consulting
 * {@code Content-Type}, so the divergence cost nothing at runtime and reported nothing either. It is
 * the generated types that were false — and a third-party client generated from this contract, which
 * is the whole reason the contract is the artifact everyone trusts, would parse by the media type it
 * was given.
 *
 * <h2>Why an advice, and not a content type at each site</h2>
 *
 * <p>The record reaches a response at roughly fifty {@code .body(Problem.…)} call sites across seven
 * controllers. Setting {@code .contentType(…)} at each is fifty chances to forget one and, worse,
 * makes the refusal composed in two places — which is the argument {@link ProblemWriter} already
 * makes for itself in the paths that have no controller. One advice keys the decision to the
 * <em>type</em>, so a ninth file returning a {@code Problem} gets it without knowing this class
 * exists.
 *
 * <p>It deliberately does not touch {@code ProblemDetail}: Spring already decides that one, and a
 * second decider for the same value is how the two spellings diverge again.
 */
@ControllerAdvice
public class ProblemMediaType implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // The handlers return ResponseEntity<Object>, so the declared type says nothing about
        // whether the body is a Problem — that is decided per response, in beforeBodyWrite.
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof Problem || isContainerError(returnType)) {
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        return body;
    }

    /**
     * The {@code /error} dispatch, which is a {@code Problem} in every respect except its Java type.
     *
     * <p>{@link ProblemErrorAttributes} already reshapes the container's
     * {@code {timestamp,status,error,path}} into this contract's envelope (§146), but Boot's
     * {@code BasicErrorController} returns it as a {@code Map} under a mapping hard-coded to
     * {@code produces = application/json} — so the one refusal a machine caller is most likely to
     * meet was the last one still arriving under the other media type.
     *
     * <p>Matched on the declaring class rather than replaced: defining an {@code ErrorController}
     * bean would switch off Boot's, which is a bigger change than a header and would put §143's and
     * §146's ERROR-dispatch work behind a reimplementation. The <em>body</em> is already right; only
     * the type it was announced under was not.
     */
    private static boolean isContainerError(MethodParameter returnType) {
        Class<?> declaring = returnType.getContainingClass();
        return declaring != null
                && declaring.getName().equals(
                        "org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController");
    }
}
