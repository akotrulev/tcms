package com.tcms.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * The one place a refusal's envelope is finished before it goes out: every {@link Problem} is
 * announced as {@code application/problem+json}, and no refusal carries the path it was sent to.
 *
 * <p><s>Named {@code ProblemMediaType} (§162).</s> → renamed §163, when it acquired the second rule
 * below. A class that strips a property under a name that says it decides a header is the shape §152
 * spent a slice on — a javadoc, or here a class name, revised into being wrong by the commit that
 * widened what it does.
 *
 * <h2>Rule one — one media type (§162)</h2>
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
 * <h2>Rule two — no refusal echoes the request back (§163)</h2>
 *
 * <p><strong>Nothing in this codebase ever called {@code setInstance}, and every one of the
 * twenty-one {@code ProblemDetail} controllers was sending {@code instance} anyway.</strong> Spring
 * Framework's return-value handlers write it: {@code HttpEntityMethodProcessor} and
 * {@code RequestResponseBodyMethodProcessor} each do
 * {@code if (detail.getInstance() == null) detail.setInstance(URI.create(request.getRequestURI()))}
 * before handing the body to a converter. So the property arrives on a refusal by way of a default
 * nobody in this repository chose, on the majority of the refusals this API can send.
 *
 * <p>§162 declared it optional in the contract and named the reconciliation as a decision rather than
 * taking it, because {@code instance} <strong>is {@code path} under another name</strong> and §146
 * had deliberately removed {@code path} from the container's error body —
 * {@code ErrorDispatchStatusTest} asserts its absence in as many words while twenty-one controllers
 * shipped the identical string one layer over. §163 takes the decision: <strong>suppress it</strong>,
 * so §146's rule holds on every refusal rather than on the one writer somebody happened to look at.
 * The argument is this repository's own, three ways:
 *
 * <ul>
 *   <li><strong>One envelope, decided in one place.</strong> §162's whole repair was that a property
 *       of a refusal must not depend on which of four writers produced it. {@code instance} was
 *       exactly that: present on the twenty-one, absent on the record's eight (~50 sites), absent on
 *       {@link ProblemWriter}'s 429, absent on the container's {@code /error}. Uniform is the
 *       requirement; which way it goes uniform is the decision.</li>
 *   <li><strong>Making it uniform the other way costs three new echoes to publish nothing.</strong>
 *       {@link Problem} would gain a component no factory can fill (a record does not know the
 *       request), {@code ProblemWriter} would compose the path by hand in a filter — the hand-written
 *       JSON §146 deleted, reintroduced — and {@code ProblemErrorAttributes} would have to re-add the
 *       exact key §146 removed. Three writers, for a value the caller already has: it is the URL it
 *       just sent.</li>
 *   <li><strong>§141's test for what belongs in a refusal.</strong> A refusal that does not say what
 *       to do next is the one a model retries unchanged. {@code detail} says what to do, {@code
 *       errors} says which field; {@code instance} says where the caller just was. It is the one
 *       member of this envelope carrying no information the caller lacks.</li>
 * </ul>
 *
 * <p>This is not a second decider for a value Spring decides. It is the removal of a property Spring
 * adds to <em>one</em> of the two envelopes — the asymmetry itself. The media type below is left to
 * Spring for {@code ProblemDetail} for exactly the opposite reason: there Spring is consistent, and a
 * second decider is how two spellings diverge again.
 *
 * <h2>Why an advice, and not a line at each site</h2>
 *
 * <p>The record reaches a response at roughly fifty {@code .body(Problem.…)} call sites across seven
 * controllers, and {@code ProblemDetail} at more than that across twenty-one. Doing either rule at
 * the site is a hundred chances to forget one and, worse, makes the refusal composed in two places —
 * which is the argument {@link ProblemWriter} already makes for itself in the paths that have no
 * controller. One advice keys both decisions to the <em>type</em>, so a ninth {@code Problem} file
 * and a twenty-second {@code ProblemDetail} file get them without knowing this class exists.
 *
 * <p>It has to run <em>after</em> the return-value handler for rule two to work at all, and it does:
 * {@code HttpEntityMethodProcessor} sets {@code instance} and then calls
 * {@code writeWithMessageConverters}, which is what invokes this. That ordering is asserted rather
 * than reasoned — {@code ErrorBodyPolicyTest.noRefusalBodyEchoesTheRequest} drives the real
 * serialization, because the reasoning is the kind that is correct until Spring reorders two calls.
 */
@ControllerAdvice
public class ProblemEnvelope implements ResponseBodyAdvice<Object> {

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
        if (body instanceof ProblemDetail detail) {
            // Unconditional, and it can be: `setInstance` occurs nowhere in src/main, so anything
            // found here was put there by the framework's own default. `ProblemDetailJacksonMixin`
            // is NON_EMPTY, so nulling it removes the key rather than sending it null — which is
            // the difference between "this API does not echo the request" and "it echoes nothing",
            // and only the first is what the contract now says.
            detail.setInstance(null);
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
