package com.tcms.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * The filter chain. Deny by default; three exceptions, each named.
 *
 * <h2>Why the exceptions are enumerated rather than pattern-matched</h2>
 *
 * <p>{@code /api/v1/auth/**} is the only open family, and it is open because every endpoint
 * under it exists to be reached without a session: signing in, and redeeming an invitation.
 * A wildcard higher up the tree — {@code /api/v1/**} with per-method annotations — is the
 * arrangement where one missing annotation silently publishes an endpoint, and
 * {@code EndpointAccessTest} exists because "deny by default" is a claim until something
 * walks the mappings and checks it.
 *
 * <h2>Stateless, and therefore no CSRF token</h2>
 *
 * <p>The session travels in an {@code Authorization} header the client must set explicitly.
 * A browser does not attach it to a cross-site form post the way it attaches a cookie, so
 * the confused-deputy shape CSRF protects against does not arise. <strong>This stops being
 * true the moment a cookie carries the token</strong> — if a future session mechanism moves
 * it there, CSRF protection comes back with it, and that is written here rather than left
 * as tribal knowledge.
 */
@Configuration
// The same jar is the server AND the operator CLI, and the CLI runs with
// WebApplicationType.NONE — where HttpSecurity does not exist. Without this condition the
// filterChain bean cannot be satisfied and the whole context fails to start, so
// `provision-company` dies before it reaches a database.
//
// Found by running the CLI against the live compose stack, not by the suite: every test
// here boots a web context, so the one arrangement that breaks is the one nothing
// exercised. ProvisionCompanyCommandTest now covers it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            PermissionVersionFilter permissionVersionFilter,
            CompanyContextFilter companyContextFilter) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        // Signing in and redeeming an invitation are the two things you do
                        // when you have no session; requiring one would be a deadlock.
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // The container's own probes, which run before anything can hold a
                        // credential. Only the probes — /actuator/** would publish env,
                        // beans and configprops to the internet.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                // Plain 401 rather than a WWW-Authenticate challenge: this API is consumed
                // by an SPA that handles re-authentication itself, and a Basic challenge
                // would make the browser render its own credential dialog over it.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // Order is load-bearing: pv first, so a token that predates a revocation is
                // rejected before its company is ever opened.
                .addFilterAfter(permissionVersionFilter, BasicAuthenticationFilter.class)
                .addFilterAfter(companyContextFilter, PermissionVersionFilter.class)
                .build();
    }

}
