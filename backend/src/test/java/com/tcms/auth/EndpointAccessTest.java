package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * "Deny by default" is a claim until something walks the mappings and checks it.
 *
 * <p>The chain in {@link SecurityConfiguration} ends in {@code anyRequest().authenticated()},
 * which is the right shape — but the failure this guards against is not a wrong rule, it is
 * a <em>new endpoint</em>: a controller added under a path someone assumed was covered, or
 * added to the open {@code /auth} family for convenience. That mistake is invisible in
 * review and invisible in every test that sends a token.
 *
 * <p>So the assertion is derived from the live handler mapping rather than from a list
 * anyone has to remember to update — the same argument as
 * {@code RepositoryTransactionTest} deriving its repositories from the context and
 * {@code CompanyFilterTest} deriving its entities from {@code pg_class}.
 */
@AutoConfigureMockMvc
@DisplayName("Endpoint access — nothing is reachable that was not meant to be")
class EndpointAccessTest extends IntegrationTestBase {

    /**
     * The paths that are open, and the reason each one is.
     *
     * <p>Adding to this set is the deliberate act the test exists to force. Anything under
     * {@code /api/v1/auth} is reachable by anyone on the internet, so a new entry is a
     * decision about the attack surface, not a formality.
     */
    private static final Set<String> DELIBERATELY_PUBLIC = Set.of(
            // You cannot present a session in order to obtain one.
            "/api/v1/auth/login",
            // Discarding a token requires no proof of anything.
            "/api/v1/auth/logout",
            // Someone accepting an invitation has no account yet — securing these would
            // deadlock the only way into a new instance.
            "/api/v1/auth/invitations/{token}",
            "/api/v1/auth/invitations/{token}/accept");

    @Autowired
    private MockMvc mvc;

    // Qualified by name: the actuator contributes a second RequestMappingHandlerMapping
    // (controllerEndpointHandlerMapping), so an unqualified injection is ambiguous — and
    // picking the wrong one would sweep the actuator's mappings instead of the API's.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("every mapped endpoint outside the public set refuses an anonymous caller")
    void everyOtherEndpointRequiresAuthentication() throws Exception {
        List<String> reachable = new ArrayList<>();

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            for (String pattern : patternsOf(mapping)) {
                if (!pattern.startsWith("/api/v1/") || DELIBERATELY_PUBLIC.contains(pattern)) {
                    continue;
                }
                HttpMethod method = mapping.getMethodsCondition().getMethods().stream()
                        .findFirst()
                        .map(requestMethod -> HttpMethod.valueOf(requestMethod.name()))
                        .orElse(HttpMethod.GET);

                // A path variable substituted with something harmless. The status is what
                // matters and 401 is decided before any handler runs, so the value never
                // reaches one.
                String path = pattern.replaceAll("\\{[^}]+}", "x");
                int status = mvc.perform(MockMvcRequestBuilders.request(method, path))
                        .andReturn().getResponse().getStatus();

                if (status != 401) {
                    reachable.add(method + " " + pattern + " -> " + status);
                }
            }
        }

        assertThat(reachable)
                .as("""
                        These endpoints answered an anonymous caller with something other than 401. \
                        Either the endpoint belongs behind the session — in which case the chain in \
                        SecurityConfiguration needs a look — or it is genuinely public, in which \
                        case add it to DELIBERATELY_PUBLIC here with the reason it is open. Do not \
                        widen a matcher to make this pass.""")
                .isEmpty();
    }

    @Test
    @DisplayName("only the health probes are open on the actuator, not the whole actuator")
    void actuatorIsNotWideOpen() throws Exception {
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/actuator/health"))
                .andReturn().getResponse().getStatus())
                .as("the container's probe runs before anything can hold a credential")
                .isEqualTo(200);

        // /actuator/** would publish env, beans and configprops — i.e. the database
        // password and the JWT signing key — to whoever asked. management.endpoints
        // exposes only health and info today, so this is defence in depth rather than the
        // only thing standing there; both would have to be wrong at once.
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/actuator/env"))
                .andReturn().getResponse().getStatus())
                .isNotEqualTo(200);
    }

    private static List<String> patternsOf(RequestMappingInfo mapping) {
        if (mapping.getPathPatternsCondition() != null) {
            return mapping.getPathPatternsCondition().getPatternValues().stream().toList();
        }
        return List.of();
    }
}
