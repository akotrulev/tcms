package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.PasswordPolicy;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two self-service acts on the calling account (005 S9a): set or change the password, and end
 * every other session.
 *
 * <h2>Possession of a session is not possession of the credential</h2>
 *
 * <p>An account that has a password must present it to change it. A walked-away-from laptop is a
 * live session; it must not be enough to lock its owner out. An account with none — social- or
 * SSO-only — sets one with {@code currentPassword: null}, which closes the impossible half of the
 * {@code DELETE /me/social-identities/{provider}} refusal (005 §1.5).
 *
 * <h2>Why the wrong current password is a 400 and not a 401</h2>
 *
 * <p>The <em>session</em> is fine. A 401 would trip the SPA's session-recovery path for a typo, and
 * the form has a field to land the message on. The refusal is keyed on {@code currentPassword} for
 * that reason and says nothing about the account — the caller is already signed in to it.
 *
 * <h2>Sign-out-others is the {@code pv} epoch, finally reachable by its owner</h2>
 *
 * <p>Sessions are stateless and {@code /auth/logout} says so; the one revocation this product has is
 * bumping the account's epoch, which invalidates <strong>every</strong> session at once — including
 * the one asking. So the caller gets a fresh session in the same response, minted <em>after</em> the
 * bump, and the client swaps it in. There is deliberately no session list: nothing records
 * sessions, so all-or-nothing is what the mechanism is.
 */
@Service
public class AccountSecurityService {

    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy policy;
    private final SessionTokens tokens;
    private final Clock clock;

    public AccountSecurityService(
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            PasswordEncoder passwordEncoder,
            PasswordPolicy policy,
            SessionTokens tokens,
            Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.policy = policy;
        this.tokens = tokens;
        this.clock = clock;
    }

    public sealed interface PasswordChange {

        record Changed() implements PasswordChange {
        }

        /** {@code currentPassword} is wrong, or was null on an account that has one. 400. */
        record WrongCurrent() implements PasswordChange {
        }

        /** The new password fails the invitation-accept rules. 422, with the reason. */
        record Unacceptable(String detail) implements PasswordChange {
        }
    }

    /**
     * The policy is checked <em>after</em> the current password, so a caller who cannot prove
     * possession learns nothing about whether their candidate would have been accepted — and the
     * current-password check always costs one verification, against the stored hash when there is
     * one and a null-safe miss when there is not, so the two refusals are not told apart by a clock.
     */
    @Transactional
    public PasswordChange changePassword(UUID userId, String currentPassword, String newPassword) {
        AppUser user = requireAccount(userId);
        String hash = user.getPasswordHash();

        if (hash != null) {
            boolean matches = currentPassword != null
                    && passwordEncoder.matches(currentPassword, hash);
            if (!matches) {
                return new PasswordChange.WrongCurrent();
            }
        }

        Optional<String> rejection = policy.rejectionReason(newPassword);
        if (rejection.isPresent()) {
            return new PasswordChange.Unacceptable(rejection.get());
        }

        user.changePassword(passwordEncoder.encode(newPassword), clock.instant());
        return new PasswordChange.Changed();
    }

    /**
     * @param companyId the caller's active company, carried over into the replacement session so
     *                  signing everybody else out does not also move the caller to a different
     *                  company than the one they were working in
     */
    @Transactional
    public SessionTokens.Session signOutOthers(UUID userId, UUID companyId) {
        AppUser user = requireAccount(userId);
        user.revokeSessions();
        users.saveAndFlush(user);
        UUID company = companyId != null
                ? companyId
                : memberships.findCompanyIdsForUser(userId).stream().findFirst().orElse(null);
        return tokens.issue(userId, company, user.getPermissionVersion(), false);
    }

    private AppUser requireAccount(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new IllegalStateException(
                "Authenticated user " + userId + " does not exist. A session outliving its "
                        + "account means deletion happened somewhere that should only deactivate."));
    }
}
