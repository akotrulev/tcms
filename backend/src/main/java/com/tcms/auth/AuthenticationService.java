package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembershipRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email and password in, a session token out.
 *
 * <h2>Every failure is the same failure</h2>
 *
 * <p>Unknown address, wrong password, account still {@code INVITED}, account
 * {@code DEACTIVATED} — one empty result, and the caller renders one message. Anything
 * finer turns the login form into a directory of who has an account on this instance, and
 * email is unique <em>instance-wide</em> here (plan §6s), so that directory would span
 * every client.
 *
 * <h2>And every failure costs the same time</h2>
 *
 * <p>A miss verifies the submitted password against a <strong>dummy hash</strong> rather
 * than returning early. BCrypt at cost 12 takes a few hundred milliseconds, which is
 * enormous next to a failed row lookup — so without this, "no such account" answers in
 * ~2 ms and "wrong password" in ~250 ms, and the difference is readable over the network by
 * anyone with a stopwatch. That reinstates exactly the oracle the shared message removes.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /**
     * A real BCrypt hash of a value nobody knows, verified against when there is no account
     * to verify against. Its only job is to cost what a real verification costs.
     *
     * <p>Pre-computed rather than hashed at startup so the cost factor here cannot drift
     * from the configured one silently; the test asserts the two stay comparable.
     */
    private static final String DUMMY_HASH =
            "{bcrypt}$2a$12$C6UzMDM.H6dfI/f/IKcEe.LWnLKQXWXqW1LnHSLK7bTQ1EDNjJ6nO";

    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokens tokens;
    private final Clock clock;

    public AuthenticationService(
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            PasswordEncoder passwordEncoder,
            SessionTokens tokens,
            Clock clock) {
        this.users = users;
        this.memberships = memberships;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.clock = clock;
    }

    /** @param companyId the session's active company, absent for an operator with no membership */
    public record Authenticated(
            UUID userId, Optional<UUID> companyId, SessionTokens.Session session) {
    }

    /**
     * @return the session, or empty for <em>any</em> reason the sign-in did not succeed
     */
    @Transactional
    public Optional<Authenticated> authenticate(String email, String password, boolean rememberMe) {
        Optional<AppUser> found = email == null || email.isBlank()
                ? Optional.empty()
                : users.findByEmail(email.trim());

        // Note the order: the password is verified BEFORE the status is consulted, and
        // against the dummy hash when there is no row. An early return on either would
        // reintroduce the timing channel this exists to close.
        String hash = found.map(AppUser::getPasswordHash).orElse(null);
        boolean passwordMatches = passwordEncoder.matches(
                password == null ? "" : password, hash == null ? DUMMY_HASH : hash);

        if (found.isEmpty() || hash == null || !passwordMatches || !found.get().isActive()) {
            // The address is logged because failed sign-ins are exactly what an operator
            // needs to see; the password never is, not even its length.
            log.info("Sign-in refused for '{}'", email);
            return Optional.empty();
        }

        AppUser user = found.get();
        user.recordLogin(clock.instant());

        UUID companyId = defaultCompanyOf(user.getId()).orElse(null);
        SessionTokens.Session session = tokens.issue(
                user.getId(), companyId, user.getPermissionVersion(), rememberMe);

        log.info("Signed in user {} with active company {}", user.getId(), companyId);
        return Optional.of(new Authenticated(
                user.getId(), Optional.ofNullable(companyId), session));
    }

    /**
     * Which company the session starts in.
     *
     * <p>Resolved <strong>server-side, from the account's own memberships</strong> — never
     * offered as a choice on the sign-in form. A pre-authentication company picker would
     * publish the client roster to anyone who loaded the login page (plan §6s), which for
     * separate client organisations is a disclosure in its own right.
     *
     * <p>Empty is a legitimate answer, not a failure: an Instance Admin who has provisioned
     * nothing belongs to no company. Their session runs company-context-less and resolves
     * SYSTEM permissions only, which is precisely what {@code evaluateInstanceWide} exists
     * for.
     */
    private Optional<UUID> defaultCompanyOf(UUID userId) {
        // The one SECURITY DEFINER call in the codebase — see V7. Authentication precedes
        // company context, so an RLS-protected read of company_membership here correctly
        // returns nothing, and this is the narrow, audited way past that. The id passed is
        // the account that has just proved its password, never one from a request.
        return memberships.findCompanyIdsForUser(userId).stream().findFirst();
    }
}
