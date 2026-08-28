package com.tcms.auth;

import com.tcms.api.Problem;
import com.tcms.identity.PermissionEvaluator;
import com.tcms.identity.PermissionQualifier;
import com.tcms.identity.PermissionSet;
import com.tcms.tenancy.CompanyContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AccountSecurityService security;

    public MeController(
            CurrentUserService currentUser,
            PermissionEvaluator permissions,
            AccountSecurityService security) {
        this.currentUser = currentUser;
        this.permissions = permissions;
        this.security = security;
    }

    /**
     * {@code PasswordChangeInput}. Both members required, one nullable: {@code currentPassword: null}
     * is a statement ("this account has no password, I am setting one") that the server checks
     * against the account, where an absent key would be a form that forgot the field.
     */
    public record PasswordChangeRequest(
            @Size(max = 400) String currentPassword, @NotNull String newPassword) {
    }

    /**
     * {@code POST /me/password} — set or change the calling account's password (005 S9a). A POST
     * rather than a PUT because it is not idempotent the way a PUT promises: the second call with
     * the same body is refused, the {@code currentPassword} it names having just stopped being
     * current. See {@link AccountSecurityService} for why the wrong current is 400 and not 401.
     */
    @PostMapping("/password")
    public ResponseEntity<Object> changePassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeRequest request) {

        return switch (security.changePassword(
                UUID.fromString(jwt.getSubject()), request.currentPassword(), request.newPassword())) {
            case AccountSecurityService.PasswordChange.Changed ignored ->
                    ResponseEntity.noContent().build();
            case AccountSecurityService.PasswordChange.WrongCurrent ignored ->
                    ResponseEntity.badRequest().body(Problem.validation(Map.of(
                            "currentPassword", List.of("That is not this account's current password."))));
            case AccountSecurityService.PasswordChange.Unacceptable unacceptable ->
                    ResponseEntity.unprocessableEntity().body(new Problem(
                            "about:blank",
                            "The password was not accepted",
                            HttpStatus.UNPROCESSABLE_ENTITY.value(),
                            unacceptable.detail(),
                            Map.of("newPassword", List.of(unacceptable.detail()))));
        };
    }

    /**
     * {@code POST /me/sign-out-others} — bumps the {@code pv} epoch and answers <strong>200 with a
     * fresh {@code AuthResult}</strong>, because the caller's own token is dead the moment the epoch
     * moves and a 204 would sign them out too, one request after they pressed "sign out everywhere
     * else". Every other device gets a 401 on its next request and falls into ordinary session
     * recovery.
     */
    @PostMapping("/sign-out-others")
    public AuthController.LoginResponse signOutOthers(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID companyId = CompanyContext.current().orElse(null);
        SessionTokens.Session session = security.signOutOthers(userId, companyId);
        return new AuthController.LoginResponse(
                session.token(), session.expiresAt(), currentUser.describe(userId, companyId));
    }

    public record PermissionsResponse(UUID projectId, List<HeldPermission> permissions) {
    }

    /**
     * One held permission and <strong>which rows it covers</strong>.
     *
     * <p>The qualifier is on the wire since §65 because without it this endpoint cannot express the
     * answer the SPA needs. A Test Engineer held {@code artifact:edit} at
     * {@link PermissionQualifier#PLAN_SCOPE}: a keys-only response said "held", the project-scope
     * control enabled, and the server refused the submit — so handoff §31's disable-don't-hide
     * became unfollowable on exactly the control where it mattered, and §61 shipped a refusal
     * message in the response body as the interim.
     *
     * <p><strong>{@code V52} (§210) de-qualified that grant and no grant carries a qualifier now</strong>,
     * so this field currently answers {@code ALL} for every key held. It is not removed: the
     * argument above is about what a keys-only response <em>cannot say</em>, which is a property of
     * the shape rather than of any grant, and a client written against a keys-only answer would be
     * wrong on the day a qualified grant returns. Roles spec §A5.
     *
     * <p>{@link PermissionQualifier#NONE} is never serialized: a permission the caller does not hold
     * is an <em>absent</em> entry, never a present one qualified to nothing.
     * {@link com.tcms.identity.PermissionSet} refuses to hold such an entry, so this cannot emit one.
     */
    public record HeldPermission(String key, PermissionQualifier qualifier) {
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
        // Sorted by key, as the contract states. Not for the server's benefit — a stable order makes
        // two responses diffable when somebody is working out why a control is disabled.
        return new PermissionsResponse(
                projectId,
                resolved.qualifiers().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> new HeldPermission(e.getKey(), e.getValue()))
                        .toList());
    }
}
