package com.tcms.auth;

import com.tcms.identity.PermissionEvaluator;
import com.tcms.identity.PermissionSet;
import com.tcms.tenancy.CompanyContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who the caller is, and what they may do.
 *
 * <p>Both endpoints read the caller from the <strong>token</strong>, never from a parameter.
 * A {@code userId} query parameter on {@code /me/permissions} is the obvious convenience and
 * is an authorization bypass: it would answer "what may <em>that</em> user do", which is a
 * question this endpoint has no business answering to anyone.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final CurrentUserService currentUser;
    private final PermissionEvaluator permissions;

    public MeController(CurrentUserService currentUser, PermissionEvaluator permissions) {
        this.currentUser = currentUser;
        this.permissions = permissions;
    }

    public record PermissionsResponse(UUID projectId, List<String> permissions) {
    }

    @GetMapping
    public CurrentUserService.CurrentUser me(@AuthenticationPrincipal Jwt jwt) {
        return currentUser.describe(
                UUID.fromString(jwt.getSubject()), CompanyContext.current().orElse(null));
    }

    /**
     * Effective permissions, for hiding affordances.
     *
     * <p>Three questions, and they are genuinely different (plan §6b):
     * <ul>
     *   <li><strong>with a project</strong> — the SYSTEM, COMPANY and PROJECT terms. An
     *       unreachable or non-existent project both yield the empty set, identically, so
     *       the response cannot be used to probe for projects in other companies (§6t);</li>
     *   <li><strong>without a project, with a company open</strong> — SYSTEM and COMPANY
     *       only. Deliberately not "permissions on every project": a Test Lead on one
     *       project must not appear to hold {@code project:settings} company-wide;</li>
     *   <li><strong>without a company at all</strong> — SYSTEM only, which is the whole
     *       session of an operator who belongs to no company.</li>
     * </ul>
     */
    @GetMapping("/permissions")
    public PermissionsResponse permissions(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID projectId) {

        UUID userId = UUID.fromString(jwt.getSubject());
        Optional<UUID> company = CompanyContext.current();

        PermissionSet resolved;
        if (company.isEmpty()) {
            resolved = permissions.evaluateInstanceWide(userId);
        } else if (projectId == null) {
            resolved = permissions.evaluateCompanyWide(userId);
        } else {
            resolved = permissions.evaluate(userId, projectId);
        }
        return new PermissionsResponse(projectId, resolved.keys().stream().sorted().toList());
    }
}
