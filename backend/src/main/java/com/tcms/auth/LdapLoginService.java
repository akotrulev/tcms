package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.FederatedIdentityService;
import com.tcms.identity.IdentityProvider;
import com.tcms.identity.IdentityProviderKind;
import com.tcms.identity.IdentityProviderRepository;
import com.tcms.identity.ProviderSecrets;
import com.tcms.tenancy.CompanyContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signing in against a directory — the credential half of external identity.
 *
 * <h2>What is different from {@link SsoLoginService}, and what is deliberately identical</h2>
 *
 * <p>Different: there is no third party to redirect to, so there is no {@code state}, no
 * {@code nonce}, no PKCE and no {@code sso_login_attempt} row. TCMS collects the person's directory
 * password and binds with it, which is a credential this product does not own and cannot rotate.
 *
 * <p>Identical, and not by accident: the ordering, the refusal, and the session.
 *
 * <ol>
 *   <li>the provider is resolved with <strong>no context open</strong>, through V53's function,
 *       which answers a company and a lifecycle flag and nothing else;</li>
 *   <li>the company context is opened from that answer — server-side data resolved from a presented
 *       id, not a company taken from a request parameter;</li>
 *   <li>the full configuration is read <em>inside</em> that context, against the RLS-protected row,
 *       so a provider disabled a moment ago refuses;</li>
 *   <li>the directory proves who they are, and only then is any account question asked;</li>
 *   <li>{@link FederatedIdentityService} answers whose account it is — the same class, with the
 *       same policy, that the OIDC path uses. It is unmodified by this slice, which is the test
 *       that the seam between protocol and policy was drawn in the right place.</li>
 * </ol>
 *
 * <p>{@code emailVerified} is passed as <strong>true</strong>: a directory is authoritative for the
 * addresses it publishes in its own tree. The gate on taking over an existing TCMS account is
 * {@code autoLinkVerifiedEmail}, which is off by default and is an explicit act by the company's
 * own administrator — that was always the gate, and this claim was never it.
 */
@Service
public class LdapLoginService {

    private static final Logger log = LoggerFactory.getLogger(LdapLoginService.class);

    private final ProviderResolution resolution;
    private final IdentityProviderRepository providers;
    private final AppUserRepository users;
    private final FederatedIdentityService identities;
    private final LdapDirectory directory;
    private final BindThrottle throttle;
    private final ProviderSecrets secrets;
    private final SessionTokens tokens;
    private final TransactionTemplate transactions;

    public LdapLoginService(
            ProviderResolution resolution,
            IdentityProviderRepository providers,
            AppUserRepository users,
            FederatedIdentityService identities,
            LdapDirectory directory,
            BindThrottle throttle,
            ProviderSecrets secrets,
            SessionTokens tokens,
            TransactionTemplate transactions) {

        this.resolution = resolution;
        this.providers = providers;
        this.users = users;
        this.identities = identities;
        this.directory = directory;
        this.throttle = throttle;
        this.secrets = secrets;
        this.tokens = tokens;
        this.transactions = transactions;
    }

    /** A session, exactly as local sign-in produces one. */
    public record SignedIn(UUID userId, UUID companyId, SessionTokens.Session session) {
    }

    /**
     * @return the session, or empty for every failure. The caller must not tell them apart (§6r)
     */
    public Optional<SignedIn> signIn(UUID providerId, String email, String password,
                                     boolean rememberMe) {

        if (providerId == null || email == null || email.isBlank()
                || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        if (!throttle.mayBind(providerId, email)) {
            // Refused WITHOUT contacting the directory: every failed bind increments the customer's
            // own lockout counter, and this endpoint is reachable by anyone.
            log.info("Directory sign-in refused for provider {} before binding: too many failures",
                    providerId);
            return Optional.empty();
        }

        Optional<ProviderResolution.Resolved> resolved = resolution.forProvider(providerId);
        if (resolved.isEmpty() || !resolved.get().enabled()) {
            log.info("Directory sign-in refused for provider {}", providerId);
            return Optional.empty();
        }

        try (var ignored = CompanyContext.open(resolved.get().companyId())) {
            return complete(providerId, resolved.get().companyId(), email, password, rememberMe);
        }
    }

    private Optional<SignedIn> complete(UUID providerId, UUID companyId, String email,
                                        String password, boolean rememberMe) {

        IdentityProvider provider = transactions.execute(status ->
                providers.findByIdAndEnabledTrue(providerId).orElse(null));
        if (provider == null || provider.getKind() != IdentityProviderKind.LDAP) {
            return Optional.empty();
        }

        Optional<LdapDirectory.Entry> entry = directory.authenticate(
                new LdapDirectory.Target(
                        provider.getIssuerUri(),
                        provider.isStartTls(),
                        provider.getBindDn(),
                        secrets.decrypt(provider.getBindPasswordEncrypted()),
                        provider.getUserSearchBase(),
                        provider.getUserSearchFilter(),
                        provider.getUserIdAttribute(),
                        provider.getEmailAttribute(),
                        provider.getDisplayNameAttribute()),
                email,
                password);

        if (entry.isEmpty()) {
            throttle.recordFailure(providerId, email);
            return Optional.empty();
        }
        throttle.recordSuccess(providerId, email);

        FederatedIdentityService.Resolution resolution = identities.resolve(
                provider,
                new FederatedIdentityService.Claims(
                        entry.get().subject(),
                        entry.get().email(),
                        // D5: a directory is authoritative for the addresses in its own tree, and
                        // the gate on reaching an existing account is autoLinkVerifiedEmail.
                        true,
                        entry.get().displayName()));

        if (!(resolution instanceof FederatedIdentityService.Resolution.Resolved signedIn)) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.execute(status ->
                issueFor(signedIn.userId(), companyId, rememberMe)));
    }

    private SignedIn issueFor(UUID userId, UUID companyId, boolean rememberMe) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        log.info("Signed in user {} via directory with active company {}", userId, companyId);
        return new SignedIn(userId, companyId,
                tokens.issue(userId, companyId, user.getPermissionVersion(), rememberMe));
    }
}
