package com.tcms.auth;

import com.tcms.tenancy.CompanyContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opens the request's company context from the authenticated session — and closes it again.
 *
 * <p><strong>This is the production path the whole tenancy layer was built for, and until
 * now it did not exist.</strong> Every company opened in this codebase so far was opened by
 * a test or by the provisioning CLI. {@code CompanyAwareTransactionManager} has been
 * reading {@link CompanyContext} in {@code doBegin} since M0 to set the
 * {@code app.current_company} GUC on every transaction; this filter is what finally puts
 * something there on a web request.
 *
 * <h2>The company comes from the token, and only from the token</h2>
 *
 * <p>The {@code co} claim is signed, was chosen server-side at login from the account's own
 * memberships, and cannot be edited by the client without breaking the signature. That is
 * the entire rule: no request parameter, no header, no path segment ever selects a company.
 * A client that wants a different one signs in again — or, from M6, switches, which
 * re-issues the token.
 *
 * <h2>Closing it matters as much as opening it</h2>
 *
 * <p>try-with-resources, so the scope closes even when the handler throws. Servlet threads
 * are pooled: a context left open would be inherited by the next request on that thread,
 * which is a cross-company read that looks completely ordinary in a log. This is the
 * request-scoped twin of what {@code CompanyContextTaskDecorator} does for async work — and
 * the reason {@link CompanyContext#open} is a scope guard rather than a setter.
 *
 * <h2>No company is a valid state</h2>
 *
 * <p>An Instance Admin who has provisioned nothing has no {@code co} claim. The request then
 * runs with no context at all, and every company-owned read returns zero rows because
 * {@code nullif(current_setting(...), '')} is NULL and {@code company_id = NULL} is never
 * true. Fail-closed, and the correct answer rather than an error: their SYSTEM permissions
 * resolve through {@code evaluateInstanceWide}, which needs no context by design.
 */
@Component
public class CompanyContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID companyId = companyFromToken();
        if (companyId == null) {
            chain.doFilter(request, response);
            return;
        }
        try (var ignored = CompanyContext.open(companyId)) {
            chain.doFilter(request, response);
        }
    }

    private static UUID companyFromToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        return PermissionVersionFilter.parseUuid(jwt.getClaimAsString(SessionTokens.CLAIM_COMPANY));
    }
}
