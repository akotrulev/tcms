package com.tcms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The other half of the undeclared-parameter defect: a parameter the contract declares and no
 * handler binds — <strong>and, since §176, the half after that: a parameter the handler binds and
 * the contract never declared.</strong>
 *
 * <p>{@link ContractConventionTest} and {@code DashboardEndpointTest} both check the SPA's side
 * of this — that a key something sends is a key the contract declares. That leaves the far end
 * unchecked, and it fails in exactly the same silence: <strong>Spring ignores a query parameter
 * no handler method binds.</strong> So a contract can promise a filter, {@code schema.d.ts} can
 * generate it, a caller can send it, and the server answers the unfiltered question with a 200.
 * Nothing throws at any point in that chain.
 *
 * <p>It is the more dangerous half, because the contract is the artifact everyone trusts. A key
 * the SPA invents is at least absent from the spec someone can read; a key the spec declares has
 * been reviewed, generated into the client's types, and blessed. §49 shipped two of them on the
 * endpoint whose own slice named this check as the next thing to build.
 *
 * <h2>Why the live handler mapping rather than a source scan</h2>
 *
 * <p>Same argument as {@code EndpointAccessTest}, {@code RepositoryTransactionTest} and
 * {@code CompanyFilterTest}: the mapping is what the running server does. A scan for
 * {@code @RequestParam} in the source would agree with it today and diverge the first time a
 * parameter arrives some other way, and a hand-maintained list of endpoints would go stale
 * exactly when a new one is added — which is the moment this check exists for.
 *
 * <p>~~Consequently it only judges operations that are <strong>implemented</strong>. An endpoint
 * still served by MSW has no handler to compare against, so it is skipped rather than reported:
 * the check must not turn "not built yet" into a build failure, or the next unbuilt endpoint's
 * author will add it to an ignore list and the real finding will arrive inside one.~~
 * → <strong>superseded 2026-08-09 (§145): the premise was deleted with the mock layer.</strong>
 *
 * <p>The skip was correct while an unimplemented endpoint was a normal state — the SPA was built
 * against MSW ahead of the API, so "no handler" meant "not yet" and failing the build on it would
 * have been noise. <strong>§107 deleted {@code src/mocks/} on 2026-08-06, and with it the only
 * reason a declared operation could legitimately have no handler.</strong> The skip stayed, and it
 * was covering one: {@code GET /projects/&#123;projectId&#125;/suites/&#123;suiteId&#125;} had been in
 * the contract since M0, in both copies of {@code schema.d.ts}, and mapped by nothing. The SPA never
 * called it; §140 and §144 then wrote <em>three</em> consumers against it — the MCP
 * {@code tcms_list_suites} "open one" arm and the read half of both clients' {@code suite edit} —
 * and every one of them answered <strong>405 in production</strong> while 2308 tests, oxlint and two
 * green CI lanes said nothing. Found by driving a real server (§145's live pass, row {@code LP-3}).
 *
 * <p>So the skip is now a <em>finding</em>: {@link #everyDeclaredOperationHasAHandler} fails on a
 * declared operation nothing serves. That is the ninth time in this repo a note recording why
 * something was permitted outlived the thing that permitted it, and the first where the note was
 * inside the check that would otherwise have reported the defect.
 *
 * <h2>The multipart half (§171)</h2>
 *
 * <p>Everything above is about the query string, and a form field fails in exactly the same
 * silence for exactly the same reason — Spring drops a part nothing binds. It went unchecked
 * because the four {@code multipart/form-data} bodies are the one position in this contract with
 * no record behind them, so {@code ContractConventionTest}'s pairing rules exempt them and
 * <em>recorded</em> the gap rather than closing it, naming this class as where it would go. It is
 * here now: {@link #everyDeclaredFormPartIsBound}, {@link #noHandlerBindsAnUndeclaredFormPart} and
 * {@link #formPartRequirednessAgrees}, the same three questions the named lane asks of a record's
 * components.
 *
 * <h2>The four rules are two questions asked of two populations (§176)</h2>
 *
 * <p>§171 closed the multipart pair and <em>recorded</em> the one position left open — the query
 * string's undeclared direction — because {@code @RequestParam} cannot tell a form field from a
 * query key, so the rule needed the media type in hand rather than the signature alone.
 * {@link #noHandlerBindsAnUndeclaredQueryParameter} is that rule, and the grid it completes is the
 * argument for reading all four together:
 *
 * <table border="1">
 *   <caption>Which rule owns which direction</caption>
 *   <tr><th></th><th>query string</th><th>{@code multipart/form-data}</th></tr>
 *   <tr><td>declared, unbound</td>
 *       <td>{@link #everyDeclaredQueryParameterIsBound} — a 200 answering a broader question</td>
 *       <td>{@link #everyDeclaredFormPartIsBound} — a 201 describing a dropped part</td></tr>
 *   <tr><td>bound, undeclared</td>
 *       <td>{@link #noHandlerBindsAnUndeclaredQueryParameter} — unreachable code</td>
 *       <td>{@link #noHandlerBindsAnUndeclaredFormPart} — unreachable code</td></tr>
 * </table>
 *
 * <p><strong>Each name has exactly one owner and the media type is what assigns it.</strong> The
 * query rule skips an operation with a multipart body outright; the form rule subtracts that
 * operation's declared query parameters. Those are the same decision written from both ends, and
 * without it the two rules report the same {@code caption} under two different diagnoses.
 */
@DisplayName("Contract bindings — every declared query parameter and form part reaches a handler")
class ContractParameterBindingTest extends IntegrationTestBase {

    /** Path prefix the contract omits and the mappings carry. */
    private static final String API_PREFIX = "/api/v1";

    /**
     * Declared parameters no handler binds, and the reason each is not a defect.
     *
     * <p><strong>Every entry is a parameter the server deliberately answers the same way with
     * or without.</strong> "Not implemented yet" is not a reason to be here — that is the
     * finding. Delete the declaration instead: an unimplemented filter is a promise the
     * generated types make on the server's behalf, and the contract is cheap to re-add from
     * when a consumer actually needs it.
     *
     * <p>Empty today, and {@link #everyUnboundExemptionStillExemptsSomething} is what keeps it
     * honest once it is not — the same one-directional rot {@code ContractConventionTest}'s two
     * lists had, pinned before the first entry rather than after the first stale one.
     */
    private static final Set<String> DELIBERATELY_UNBOUND = Set.of();

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("no implemented operation ignores a query parameter the contract declares")
    void everyDeclaredQueryParameterIsBound() {
        OpenApiContract contract = OpenApiContract.load();
        List<String> offenders = new ArrayList<>();
        int compared = 0;

        for (OpenApiContract.Operation operation : contract.operations()) {
            Set<String> declared = contract.queryParametersOf(operation);
            if (declared.isEmpty()) {
                continue;
            }
            Set<String> bound = boundParametersOf(operation);
            if (bound == null) {
                // Unmapped, which everyDeclaredOperationHasAHandler below reports as a finding —
                // so this arm is reached only on a run that is already red for it, and skipping
                // is what keeps the failure list about the missing handler rather than about
                // every parameter that handler would have bound. ~~"not implemented — still MSW,
                // and not this check's business"~~ → the MSW premise was deleted with the mock
                // layer in §107 and struck in this class's header in §145; the inline copy
                // survived both and was still giving the retired reason (§162).
                continue;
            }
            compared++;

            Set<String> ignored = new TreeSet<>(declared);
            ignored.removeAll(bound);
            ignored.removeIf(name -> DELIBERATELY_UNBOUND.contains(operation.describe() + " " + name));
            if (!ignored.isEmpty()) {
                offenders.add(operation.describe() + "  ignores " + ignored + "  (binds " + bound + ")");
            }
        }

        assertThat(compared)
                .as("no implemented operation declares a query parameter, so this asserted nothing "
                        + "— the mapping lookup has probably stopped matching the contract's paths")
                .isGreaterThan(5);

        assertThat(offenders)
                .as("""
                        These operations declare a query parameter their handler does not bind.

                        Spring does not reject an unbound parameter — it IGNORES it. The caller gets a
                        200 carrying the answer to a broader question than it asked, under whatever
                        heading the client had already written. `schema.d.ts` generates the parameter
                        from this contract, so the SPA's types actively invite sending it.

                        Two honest repairs, and picking between them is the point:
                          * bind it — add the @RequestParam and the filtering it implies; or
                          * delete it from openapi.yaml, and re-add it contract-first when a consumer
                            exists. An unimplemented filter in the contract is worse than an absent
                            one, because absent is discoverable and unimplemented is not.

                        Adding a row to DELIBERATELY_UNBOUND is neither, and is only correct when the
                        server genuinely answers identically with and without the parameter.

                        Operations ignoring a declared parameter:""")
                .isEmpty();
    }

    /**
     * The reverse: a query parameter the handler binds and the contract never declared.
     *
     * <p>{@link #everyDeclaredQueryParameterIsBound} is the half that fails at runtime — the server
     * silently answers a broader question than the caller asked. This half never fails at runtime
     * at all, and that is what makes it the harder one to find: the parameter is absent from both
     * copies of {@code schema.d.ts}, so <strong>no generated client can send it</strong>, the
     * handler's argument is its default on every request that has ever been made, and the branch
     * behind it has never executed. A filter nobody can reach reads, from inside the code, exactly
     * like a filter nobody happens to use.
     *
     * <p>It is the writerless shape this repo keeps finding — §116's tables, §121's seam, §152's
     * repository methods, §172's hook — arriving at a request parameter. And it is §171's own
     * recorded gap, quoted from the section that could not close it: <em>"a bound-and-undeclared
     * query rule is a real check and it needs the media type in hand; it is a slice, not a
     * line"</em>.
     *
     * <h2>The media type is the whole of why this could not be one line</h2>
     *
     * <p>{@code @RequestParam("caption")} binds the query string key {@code caption} and the text
     * form field {@code caption} identically — the annotation carries nothing that tells them
     * apart, and neither does the handler. So on the four {@code multipart/form-data} operations
     * every bound name is a <em>part</em>, and a rule that read the signature alone would report
     * {@code caption} and {@code stepId} as undeclared query parameters while
     * {@link #noHandlerBindsAnUndeclaredFormPart} was reporting them correctly as parts, or not at
     * all because the contract declares them.
     *
     * <p>Hence the skip rather than a subtraction: <strong>a multipart operation's bound names
     * belong to the form rule entirely</strong>, declared or not, so each name has exactly one
     * owner and a failure names the side a human has to edit. The mirror of this decision is
     * already in that rule, which subtracts the declared query parameters for the same reason
     * pointing the other way.
     */
    @Test
    @DisplayName("no handler binds a query parameter the contract does not declare")
    void noHandlerBindsAnUndeclaredQueryParameter() {
        OpenApiContract contract = OpenApiContract.load();
        List<String> offenders = new ArrayList<>();
        int compared = 0;

        for (OpenApiContract.Operation operation : contract.operations()) {
            if (!contract.formPartsOf(operation).isEmpty()) {
                // A multipart operation's @RequestParams are form fields, and
                // noHandlerBindsAnUndeclaredFormPart owns every one of them — see above.
                continue;
            }
            Set<String> bound = boundParametersOf(operation);
            if (bound == null || bound.isEmpty()) {
                // Unmapped is everyDeclaredOperationHasAHandler's finding, not this one; binding
                // nothing is the normal state of most operations here.
                continue;
            }
            compared++;

            Set<String> undeclared = new TreeSet<>(bound);
            undeclared.removeAll(contract.queryParametersOf(operation));
            if (!undeclared.isEmpty()) {
                offenders.add(operation.describe() + "  binds " + undeclared
                        + "  (contract declares " + contract.queryParametersOf(operation) + ")");
            }
        }

        assertThat(compared)
                .as("no operation outside the multipart four binds a query parameter at all, which "
                        + "cannot be true of this contract — the mapping lookup has probably stopped "
                        + "matching the contract's paths")
                .isGreaterThan(5);

        assertThat(offenders)
                .as("""
                        These handlers bind a query parameter openapi.yaml does not declare.

                        Nothing fails at runtime, which is the problem: the key is absent from both
                        copies of schema.d.ts, so no generated client can send it, the argument is its
                        default on every request ever made, and the code behind it has never run. The
                        SPA, the CLI and the MCP server are all generated from this document.

                        Two honest repairs, and picking between them is the point:
                          * declare it in openapi.yaml — regenerate BOTH copies of schema.d.ts — if a
                            caller should be able to send it; or
                          * delete the parameter, along with the filtering it implies. A parameter no
                            declared client can send is dead code with a plausible name.

                        Handlers binding an undeclared parameter:""")
                .isEmpty();
    }

    /**
     * Each {@code DELIBERATELY_UNBOUND} entry still names a parameter that is declared and unbound.
     *
     * <p>{@code ContractConventionTest.everyExemptionStillExemptsSomething} is this rule for that
     * file's two lists, and the argument is there. The short form: an entry here excuses a
     * parameter the handler ignores, and if the handler starts binding it — or the contract stops
     * declaring it — nothing fails, because a skip that was never going to fire looks exactly like
     * a rule being obeyed.
     */
    @Test
    @DisplayName("every unbound-parameter exemption is still exempting something")
    void everyUnboundExemptionStillExemptsSomething() {
        OpenApiContract contract = OpenApiContract.load();
        Set<String> live = new TreeSet<>();

        for (OpenApiContract.Operation operation : contract.operations()) {
            Set<String> bound = boundParametersOf(operation);
            if (bound == null) {
                continue;
            }
            for (String declared : contract.queryParametersOf(operation)) {
                if (!bound.contains(declared)) {
                    live.add(operation.describe() + " " + declared);
                }
            }
        }

        assertThat(DELIBERATELY_UNBOUND)
                .as("""
                        A DELIBERATELY_UNBOUND entry no longer names a declared-and-unbound \
                        parameter — the handler binds it now, or the contract stopped declaring \
                        it. Either way the argument has outlived what it was about and the row is \
                        excusing nothing. Delete it.""")
                .isSubsetOf(live);
    }

    @Test
    @DisplayName("every operation the contract declares is served by a handler")
    void everyDeclaredOperationHasAHandler() {
        OpenApiContract contract = OpenApiContract.load();
        List<String> unserved = new ArrayList<>();
        int compared = 0;

        for (OpenApiContract.Operation operation : contract.operations()) {
            compared++;
            if (!isMapped(operation)) {
                unserved.add(operation.describe());
            }
        }

        assertThat(compared)
                .as("the contract parsed to no operations at all — the loader has stopped finding it")
                .isGreaterThan(100);

        assertThat(unserved)
                .as("""
                        These operations are declared in openapi.yaml and NO handler serves them.

                        Both copies of schema.d.ts generate them, so every client types the call, \
                        compiles it and ships it; Spring answers 405 (or 404) at runtime. Nothing \
                        upstream can see that: a stubbed fetch answers whatever the contract says, \
                        MockMvc is never asked, and the SPA is not necessarily a consumer.

                        Two honest repairs, and picking between them is the point:
                          * implement it — the contract already says what it must answer; or
                          * delete it from openapi.yaml, and re-add it contract-first when a \
                        consumer exists.

                        There is deliberately no ignore list. An operation that is 'not built yet' \
                        is exactly the finding: this check used to skip it, on the grounds that MSW \
                        served the SPA ahead of the API, and that premise was deleted with the mock \
                        layer in §107 while the skip remained — hiding a 405 that three clients \
                        shipped against.

                        Declared and unserved:""")
                .isEmpty();
    }

    /**
     * Every {@code multipart/form-data} part the contract declares is bound by the handler.
     *
     * <p>The half that fails silently, and it fails the same way the query-parameter rule above
     * exists for: <strong>Spring ignores a form part no parameter binds.</strong> The upload
     * succeeds, the part is dropped, and the caller gets a 201 describing a thing it did not ask
     * for — an evidence file attached with no caption, against a contract that promised one.
     *
     * <p>{@code ContractConventionTest} exempts all four of these forms from its pairing rules,
     * because no record serves a form, and it named this as the check they forgo: <em>"nothing
     * compares a form's declared parts to the parameters the handler binds"</em>. That note is what
     * this test closes, so the exemption there now points here instead of recording a gap.
     */
    @Test
    @DisplayName("no multipart form declares a part its handler does not bind")
    void everyDeclaredFormPartIsBound() {
        OpenApiContract contract = OpenApiContract.load();
        List<String> offenders = new ArrayList<>();
        int compared = 0;

        for (OpenApiContract.Operation operation : contract.operations()) {
            Map<String, Boolean> declared = contract.formPartsOf(operation);
            if (declared.isEmpty()) {
                continue;
            }
            HandlerMethod handler = handlerFor(operation);
            if (handler == null) {
                // everyDeclaredOperationHasAHandler reports this, and the run is already red for
                // it — same argument as the query rule's skip above.
                continue;
            }
            compared++;

            Set<String> bound = formPartsBoundBy(handler).keySet();
            Set<String> ignored = new TreeSet<>(declared.keySet());
            ignored.removeAll(bound);
            if (!ignored.isEmpty()) {
                offenders.add(operation.describe() + "  ignores " + ignored + "  (binds " + bound + ")");
            }
        }

        assertThat(compared)
                .as("no multipart form was compared at all — the contract has four, so either the "
                        + "media type moved or the mapping lookup stopped matching these paths")
                .isEqualTo(4);

        assertThat(offenders)
                .as("""
                        These multipart forms declare a part their handler does not bind.

                        Spring does not reject an unbound part — it IGNORES it, exactly as it does an
                        unbound query parameter, and an upload is the worst place for that: the write
                        succeeds and the 201 body describes a record missing whatever the dropped part
                        carried. Both copies of schema.d.ts generate the field, so every client fills
                        it in.

                        Bind it (@RequestPart for a file or a JSON part, @RequestParam for a text one),
                        or delete it from openapi.yaml and re-add it contract-first when a handler
                        reads it.

                        Forms ignoring a declared part:""")
                .isEmpty();
    }

    /**
     * The reverse: a part the handler binds and the contract never declared.
     *
     * <p>Harmless to the server and not to anybody reading the spec — the field is invisible in
     * both copies of {@code schema.d.ts}, so no generated client can send it and the handler's
     * argument is permanently null. That is the writerless shape this repo keeps finding, arriving
     * at a request instead of at a column.
     *
     * <p><strong>The declared query parameters are subtracted, and that is not tidiness.</strong>
     * {@code @RequestParam} binds a query string key and a text form field <em>identically</em> —
     * the annotation cannot tell you which was meant, so on a multipart operation the only thing
     * separating the two populations is which side of the contract declares the name. All four
     * forms declare no query parameter today, so the subtraction removes nothing; it is here so
     * that the day one does, this rule reports it as a query parameter rather than as an
     * undeclared part.
     *
     * <p>~~The mirror of this — bound and undeclared on the <em>query</em> side — stays unwritten
     * on purpose: on these four operations it would report {@code caption} and {@code stepId},
     * which are form fields.~~ → <strong>written in §176</strong> as
     * {@link #noHandlerBindsAnUndeclaredQueryParameter}, which resolves that by skipping any
     * operation with a multipart body: on a form, <em>every</em> bound name is this rule's,
     * declared or not. Verified in both directions — mutation {@code §176 QP-2} binds an undeclared
     * name on this rule's own {@code POST …/evidence} and reds <em>this</em> rule while the query
     * rule stays green.
     */
    @Test
    @DisplayName("no multipart handler binds a part the contract does not declare")
    void noHandlerBindsAnUndeclaredFormPart() {
        OpenApiContract contract = OpenApiContract.load();
        List<String> offenders = new ArrayList<>();

        for (OpenApiContract.Operation operation : contract.operations()) {
            Map<String, Boolean> declared = contract.formPartsOf(operation);
            if (declared.isEmpty()) {
                continue;
            }
            HandlerMethod handler = handlerFor(operation);
            if (handler == null) {
                continue;
            }

            Set<String> undeclared = new TreeSet<>(formPartsBoundBy(handler).keySet());
            undeclared.removeAll(declared.keySet());
            undeclared.removeAll(contract.queryParametersOf(operation));
            if (!undeclared.isEmpty()) {
                offenders.add(operation.describe() + "  binds " + undeclared
                        + "  (contract declares " + declared.keySet() + ")");
            }
        }

        assertThat(offenders)
                .as("""
                        These handlers bind a multipart part openapi.yaml does not declare.

                        No generated client can send it — the field is absent from both copies of
                        schema.d.ts — so the parameter is null on every request that has ever been
                        made, and the code downstream of it has never run. Declare the part, or
                        delete the parameter.

                        Handlers binding an undeclared part:""")
                .isEmpty();
    }

    /**
     * A part's {@code required} flag says the same thing on both sides.
     *
     * <p>The two directions fail differently and both are real. A part the contract calls optional
     * and the handler demands is a <strong>400 on a request the spec says is legal</strong>, which
     * the caller has no way to diagnose from the document. The reverse — required in the contract,
     * {@code required = false} on the parameter — hands the handler a null on a path written for a
     * value, and every existing test passes because every existing test sends the part.
     */
    @Test
    @DisplayName("a form part is required on both sides or optional on both")
    void formPartRequirednessAgrees() {
        OpenApiContract contract = OpenApiContract.load();
        List<String> offenders = new ArrayList<>();

        for (OpenApiContract.Operation operation : contract.operations()) {
            Map<String, Boolean> declared = contract.formPartsOf(operation);
            if (declared.isEmpty()) {
                continue;
            }
            HandlerMethod handler = handlerFor(operation);
            if (handler == null) {
                continue;
            }

            Map<String, Boolean> bound = formPartsBoundBy(handler);
            declared.forEach((name, contractRequires) -> {
                Boolean handlerRequires = bound.get(name);
                if (handlerRequires != null && handlerRequires != contractRequires) {
                    offenders.add(operation.describe() + " " + name + "  contract says "
                            + (contractRequires ? "required" : "optional") + ", handler says "
                            + (handlerRequires ? "required" : "optional"));
                }
            });
        }

        assertThat(offenders)
                .as("""
                        These form parts disagree about whether they are required.

                        Contract optional / handler required is a 400 on a request the document says
                        is legal, and the document is the only thing the caller can read. Contract
                        required / handler optional is a null on a path written for a value, and no
                        test can see it because every test sends the part.

                        Parts whose two sides disagree:""")
                .isEmpty();
    }

    /** Whether any mapping answers this operation's path and method. */
    private boolean isMapped(OpenApiContract.Operation operation) {
        return handlerFor(operation) != null;
    }

    /**
     * The query-parameter names the handler for this operation binds, or {@code null} when the
     * operation has no handler at all.
     *
     * <p>The name is read from {@code @RequestParam}'s own {@code name}/{@code value} when set
     * and from the Java parameter name otherwise — the majority spelling in this codebase, which
     * works because Spring Boot compiles with {@code -parameters}. Reading only the annotation
     * attribute would report all fourteen of the case list's filters as unbound.
     */
    private Set<String> boundParametersOf(OpenApiContract.Operation operation) {
        HandlerMethod handler = handlerFor(operation);
        return handler == null ? null : namesOf(handler);
    }

    /** The handler serving this operation's path and method, or {@code null} if nothing does. */
    private HandlerMethod handlerFor(OpenApiContract.Operation operation) {
        String contractPath = API_PREFIX + operation.path();
        String method = operation.method().toUpperCase(Locale.ROOT);

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            boolean methodMatches = mapping.getMethodsCondition().getMethods().stream()
                    .anyMatch(requestMethod -> requestMethod.name().equals(method));
            if (!methodMatches || !patternsOf(mapping).contains(contractPath)) {
                continue;
            }
            return entry.getValue();
        }
        return null;
    }

    private static Set<String> namesOf(HandlerMethod handler) {
        Set<String> names = new LinkedHashSet<>();
        for (MethodParameter parameter : handler.getMethodParameters()) {
            RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
            if (annotation == null) {
                continue;
            }
            names.add(nameOf(parameter, annotation.name(), annotation.value()));
        }
        return names;
    }

    /**
     * The form parts a handler binds, each mapped to whether it will refuse the request without
     * one.
     *
     * <p>Three spellings, because Spring accepts three and this codebase writes two of them:
     *
     * <ul>
     *   <li>{@code @RequestPart} — the file parts and the one JSON part
     *       ({@code ArtifactFileInput}, deserialized from its own {@code Content-Type});</li>
     *   <li>{@code @RequestParam} — the text parts, indistinguishable from a query binding, which
     *       is why {@link #noHandlerBindsAnUndeclaredFormPart} subtracts the declared query
     *       parameters;</li>
     *   <li>a bare {@code MultipartFile} parameter with no annotation at all, which Spring binds by
     *       parameter name. Nothing here is written that way. It is read anyway because the point
     *       of this test is what the running server binds — a handler switched to the bare form
     *       would otherwise read as binding <em>nothing</em>, and the rule would go quiet on the
     *       one endpoint somebody had just rewritten.</li>
     * </ul>
     *
     * <p>A {@code defaultValue} makes a parameter optional whatever {@code required} says — Spring
     * overrides the flag when one is present — so it is folded in here rather than left to trip the
     * requiredness comparison with a disagreement that is not one.
     */
    private static Map<String, Boolean> formPartsBoundBy(HandlerMethod handler) {
        Map<String, Boolean> parts = new LinkedHashMap<>();
        for (MethodParameter parameter : handler.getMethodParameters()) {
            RequestPart part = parameter.getParameterAnnotation(RequestPart.class);
            if (part != null) {
                parts.put(nameOf(parameter, part.name(), part.value()), part.required());
                continue;
            }
            RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
            if (param != null) {
                boolean hasDefault = !ValueConstants.DEFAULT_NONE.equals(param.defaultValue());
                parts.put(nameOf(parameter, param.name(), param.value()),
                        param.required() && !hasDefault);
                continue;
            }
            if (MultipartFile.class.isAssignableFrom(parameter.getParameterType())) {
                parts.put(nameOf(parameter, "", ""), true);
            }
        }
        return parts;
    }

    /** An annotation's own name, its {@code value} alias, or the Java parameter name. */
    private static String nameOf(MethodParameter parameter, String name, String alias) {
        if (!name.isEmpty()) {
            return name;
        }
        if (!alias.isEmpty()) {
            return alias;
        }
        parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        return parameter.getParameterName();
    }

    /**
     * The URL patterns a mapping answers on. Spring Boot 3 uses {@code PathPatternsCondition}
     * by default and leaves the legacy {@code PatternsCondition} null, so reading only the
     * latter would silently find no patterns at all and skip every operation — which this
     * test's own {@code compared} floor is there to catch.
     */
    private static Set<String> patternsOf(RequestMappingInfo mapping) {
        if (mapping.getPathPatternsCondition() != null) {
            return mapping.getPathPatternsCondition().getPatternValues();
        }
        return mapping.getPatternsCondition() == null
                ? Set.of()
                : mapping.getPatternsCondition().getPatterns();
    }
}
