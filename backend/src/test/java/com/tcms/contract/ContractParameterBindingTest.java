package com.tcms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The other half of the undeclared-parameter defect: a parameter the contract declares and no
 * handler binds.
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
 * <p>Consequently it only judges operations that are <strong>implemented</strong>. An endpoint
 * still served by MSW has no handler to compare against, so it is skipped rather than reported:
 * the check must not turn "not built yet" into a build failure, or the next unbuilt endpoint's
 * author will add it to an ignore list and the real finding will arrive inside one.
 */
@DisplayName("Contract bindings — every declared query parameter reaches a handler")
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
                continue; // not implemented — still MSW, and not this check's business
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
     * The query-parameter names the handler for this operation binds, or {@code null} when the
     * operation has no handler at all.
     *
     * <p>The name is read from {@code @RequestParam}'s own {@code name}/{@code value} when set
     * and from the Java parameter name otherwise — the majority spelling in this codebase, which
     * works because Spring Boot compiles with {@code -parameters}. Reading only the annotation
     * attribute would report all fourteen of the case list's filters as unbound.
     */
    private Set<String> boundParametersOf(OpenApiContract.Operation operation) {
        String contractPath = API_PREFIX + operation.path();
        String method = operation.method().toUpperCase(Locale.ROOT);

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            boolean methodMatches = mapping.getMethodsCondition().getMethods().stream()
                    .anyMatch(requestMethod -> requestMethod.name().equals(method));
            if (!methodMatches || !patternsOf(mapping).contains(contractPath)) {
                continue;
            }
            return namesOf(entry.getValue());
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
            if (!annotation.name().isEmpty()) {
                names.add(annotation.name());
            } else if (!annotation.value().isEmpty()) {
                names.add(annotation.value());
            } else {
                parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
                names.add(parameter.getParameterName());
            }
        }
        return names;
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
