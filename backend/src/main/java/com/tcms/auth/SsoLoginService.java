package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.FederatedIdentityService;
import com.tcms.identity.IdentityProvider;
import com.tcms.identity.IdentityProviderRepository;
import com.tcms.identity.InvitationProperties;
import com.tcms.identity.ProviderSecrets;
import com.tcms.tenancy.CompanyContext;
import jakarta.persistence.EntityManager;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The authorization-code + PKCE flow, written out explicitly.
 *
 * <h2>Why this is not {@code oauth2Login()}</h2>
 *
 * <p>Spring's client support is built for <em>static</em> registrations behind a
 * {@code ClientRegistrationRepository}; ours are per-company database rows an administrator edits.
 * The filter chain is {@code STATELESS} with a custom entry point, and TCMS mints its own session
 * at the end of this flow — so the {@code OAuth2AuthenticationToken} and authorized-client
 * machinery would be unused weight around a shape it does not fit.
 *
 * <p>The stronger reason is what must <em>not</em> happen: registering an external issuer on
 * {@code oauth2ResourceServer} would let an IdP-minted token authenticate against the whole API
 * directly, bypassing the session model, the {@code pv} epoch and the company claim. The IdP proves
 * who somebody is, once, here. It never becomes a credential for this API.
 *
 * <h2>Every refusal is the same refusal, and it says nothing</h2>
 *
 * <p>Unknown provider, disabled provider, unreachable issuer, bad code, wrong nonce, expired or
 * replayed {@code state}, no identity, no invitation, deactivated account — one empty result, and
 * the controller renders one message. {@code AuthenticationService}'s rule, on a surface that is
 * open to the internet by construction.
 *
 * <h2>Discovery answers identically for a domain that maps to nothing</h2>
 *
 * <p>{@link #providersFor} is always a 200 with a list, and the list is empty for an unmapped
 * domain, a domain no company claims, and a company whose providers are all disabled. §6r: anything
 * finer turns the login form into an oracle for which client organisations exist on this instance.
 */
@Service
public class SsoLoginService {

    private static final Logger log = LoggerFactory.getLogger(SsoLoginService.class);

    /** RFC 7636 allows 43–128 characters; 32 random bytes base64url-encoded is 43. */
    private static final int VERIFIER_BYTES = 32;

    private static final int STATE_BYTES = 32;

    /** One live sign-in route, as a button. */
    public record ProviderChoice(UUID id, String displayName) {
    }

    /** A session, exactly as local sign-in produces one. */
    public record SignedIn(UUID userId, UUID companyId, SessionTokens.Session session) {
    }

    private final SsoLoginAttemptRepository attempts;
    private final IdentityProviderRepository providers;
    private final AppUserRepository users;
    private final FederatedIdentityService identities;
    private final OidcDiscovery discovery;
    private final OidcClient oidc;
    private final OidcIdTokenVerifier verifier;
    private final ProviderSecrets secrets;
    private final SessionTokens tokens;
    private final OidcProperties properties;
    private final InvitationProperties spa;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SsoLoginService(
            SsoLoginAttemptRepository attempts,
            IdentityProviderRepository providers,
            AppUserRepository users,
            FederatedIdentityService identities,
            OidcDiscovery discovery,
            OidcClient oidc,
            OidcIdTokenVerifier verifier,
            ProviderSecrets secrets,
            SessionTokens tokens,
            OidcProperties properties,
            InvitationProperties spa,
            EntityManager entityManager,
            TransactionTemplate transactions,
            Clock clock) {

        this.attempts = attempts;
        this.providers = providers;
        this.users = users;
        this.identities = identities;
        this.discovery = discovery;
        this.oidc = oidc;
        this.verifier = verifier;
        this.secrets = secrets;
        this.tokens = tokens;
        this.properties = properties;
        this.spa = spa;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Which sign-in buttons to offer somebody who typed {@code email}.
     *
     * <p>Answered by {@code tcms_resolve_providers_for_domain} (V53) rather than by a repository,
     * because it is asked with no session and therefore no company context: under RLS,
     * {@code company_email_domain} and {@code identity_provider} both return zero rows here. The
     * function returns the union across every company claiming the domain, carrying no company id
     * and no company name.
     */
    public List<ProviderChoice> providersFor(String email) {
        String domain = domainOf(email);
        if (domain == null) {
            return List.of();
        }
        List<?> rows = transactions.execute(status -> entityManager
                .createNativeQuery("SELECT * FROM tcms_resolve_providers_for_domain(CAST(:domain AS text))")
                .setParameter("domain", domain)
                .getResultList());

        return rows == null ? List.of() : rows.stream()
                .map(row -> (Object[]) row)
                .map(row -> new ProviderChoice((UUID) row[0], (String) row[1]))
                .toList();
    }

    /**
     * Starts a sign-in: records the attempt and returns where to send the browser.
     *
     * <p>The provider is resolved by {@code tcms_resolve_identity_provider} (V53) — a primary-key
     * probe answered with no context open — and its {@code enabled} flag is <em>returned</em> rather
     * than filtered on, so a disabled provider refuses here, in Java, on the same line as every
     * other refusal.
     *
     * @return the issuer's authorization URL, or empty for any reason it could not be built
     */
    public Optional<String> authorize(UUID providerId) {
        Optional<ResolvedProvider> resolved = resolve(providerId);
        if (resolved.isEmpty() || !resolved.get().enabled()) {
            log.info("SSO authorize refused for provider {}", providerId);
            return Optional.empty();
        }
        ResolvedProvider provider = resolved.get();

        Optional<OidcDiscoveryDocument> document = discovery.forIssuer(provider.issuerUri());
        if (document.isEmpty()) {
            return Optional.empty();
        }

        String state = randomToken(STATE_BYTES);
        String nonce = randomToken(STATE_BYTES);
        String verifierValue = randomToken(VERIFIER_BYTES);
        String redirectUri = properties.redirectUri();
        Instant now = clock.instant();

        transactions.executeWithoutResult(status -> attempts.saveAndFlush(SsoLoginAttempt.begin(
                UUID.randomUUID(),
                provider.companyId(),
                providerId,
                digest(state),
                verifierValue,
                nonce,
                redirectUri,
                now,
                now.plus(properties.attemptValidity()))));

        return Optional.of(document.get().authorizationEndpoint()
                + "?response_type=code"
                + "&client_id=" + encoded(provider.clientId())
                + "&redirect_uri=" + encoded(redirectUri)
                + "&scope=" + encoded("openid email profile")
                + "&state=" + encoded(state)
                + "&nonce=" + encoded(nonce)
                + "&code_challenge=" + encoded(challengeFor(verifierValue))
                + "&code_challenge_method=S256");
    }

    /**
     * Completes a sign-in.
     *
     * <p>The ordering below is the security-relevant part and is not an implementation detail:
     *
     * <ol>
     *   <li>the attempt is claimed by a conditional {@code UPDATE} that <strong>commits before the
     *       token exchange</strong>, so two callbacks carrying one {@code state} cannot both
     *       proceed;</li>
     *   <li>the company context is opened from the attempt row — server-side data resolved from a
     *       presented secret, which is the same shape as reading it from a session and not the
     *       forbidden case of taking it from a parameter;</li>
     *   <li>the provider's own lifecycle is checked <em>inside</em> that context, against the
     *       RLS-protected row, so a provider disabled between the redirect and the callback
     *       refuses;</li>
     *   <li>the code is exchanged and the {@code id_token} verified before any identity question is
     *       asked at all.</li>
     * </ol>
     */
    public Optional<SignedIn> callback(String state, String code) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();

        SsoLoginAttempt attempt = transactions.execute(status ->
                attempts.findByStateHash(digest(state)).orElse(null));
        if (attempt == null || !attempt.isUsable(now)) {
            log.info("SSO callback refused: no usable attempt for the presented state");
            return Optional.empty();
        }

        Integer claimed = transactions.execute(status -> attempts.consume(attempt.getId(), now));
        if (claimed == null || claimed != 1) {
            log.info("SSO callback refused: attempt {} was already consumed", attempt.getId());
            return Optional.empty();
        }

        try (var ignored = CompanyContext.open(attempt.getCompanyId())) {
            return complete(attempt, code);
        }
    }

    private Optional<SignedIn> complete(SsoLoginAttempt attempt, String code) {
        IdentityProvider provider = transactions.execute(status ->
                providers.findByIdAndEnabledTrue(attempt.getProviderId()).orElse(null));
        if (provider == null) {
            log.info("SSO callback refused: provider {} is not a live route", attempt.getProviderId());
            return Optional.empty();
        }

        Optional<OidcDiscoveryDocument> document = discovery.forIssuer(provider.getIssuerUri());
        if (document.isEmpty()) {
            return Optional.empty();
        }

        Optional<OidcTokenResponse> exchanged = oidc.exchange(
                document.get().tokenEndpoint(),
                provider.getClientId(),
                secrets.decrypt(provider.getClientSecretEncrypted()),
                code,
                attempt.getRedirectUri(),
                attempt.getCodeVerifier());
        if (exchanged.isEmpty()) {
            return Optional.empty();
        }

        Optional<Jwt> verified = verifier.verify(
                new OidcIdTokenVerifier.Expected(
                        provider.getId(),
                        provider.getIssuerUri(),
                        provider.getClientId(),
                        document.get().jwksUri(),
                        attempt.getNonce()),
                exchanged.get().idToken());
        if (verified.isEmpty()) {
            return Optional.empty();
        }
        Jwt idToken = verified.get();

        FederatedIdentityService.Resolution resolution = identities.resolve(
                provider,
                new FederatedIdentityService.Claims(
                        idToken.getSubject(),
                        idToken.getClaimAsString("email"),
                        isTrue(idToken.getClaim("email_verified")),
                        idToken.getClaimAsString("name")));

        if (!(resolution instanceof FederatedIdentityService.Resolution.Resolved resolved)) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.execute(status -> issueFor(resolved.userId(),
                attempt.getCompanyId())));
    }

    /**
     * The session, minted by {@link SessionTokens#issue} exactly as local sign-in mints it — same
     * claim set, same TTL, same {@code pv} epoch. The IdP's own token stops here and never reaches
     * the SPA (§6r).
     */
    private SignedIn issueFor(UUID userId, UUID companyId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        log.info("Signed in user {} via SSO with active company {}", userId, companyId);
        return new SignedIn(userId, companyId,
                tokens.issue(userId, companyId, user.getPermissionVersion(), false));
    }

    /**
     * {@code email_verified} is a boolean in the specification and a string in more than one real
     * issuer's tokens. Anything that is not recognisably true is false, which is the safe direction:
     * the claim's only effect is to permit linking to an account that already exists.
     */
    private static boolean isTrue(Object claim) {
        return claim instanceof Boolean flag ? flag
                : claim instanceof String text && "true".equalsIgnoreCase(text);
    }

    private Optional<ResolvedProvider> resolve(UUID providerId) {
        List<?> rows = transactions.execute(status -> entityManager
                .createNativeQuery(
                        "SELECT * FROM tcms_resolve_identity_provider(CAST(:providerId AS uuid))")
                .setParameter("providerId", providerId.toString())
                .getResultList());
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new ResolvedProvider(
                (UUID) row[0], (String) row[1], (String) row[2], (Boolean) row[3]));
    }

    /** What {@code tcms_resolve_identity_provider} answers with, before any context is open. */
    private record ResolvedProvider(UUID companyId, String issuerUri, String clientId,
                                    boolean enabled) {
    }

    /**
     * Where the browser is sent when the sign-in did not happen.
     *
     * <p>One destination and one opaque marker for every cause, so the SPA has exactly one message
     * to render and the URL cannot be read as a reason.
     */
    public String failureUrl() {
        return spaOrigin() + properties.spaCallbackPath() + "#error=sso_failed";
    }

    /** Where the browser is sent once a session exists. */
    public String landingUrl(SignedIn signedIn) {
        String origin = spaOrigin();
        // The FRAGMENT, not the query string. A fragment is never sent to a server, so the session
        // does not land in this instance's access log, in Caddy's, or in the Referer header of the
        // first outbound link the SPA renders. The query string would put a live credential in all
        // three, which is the exposure InvitationProperties#acceptUrl accepts for a single-use
        // token and which is not acceptable for a session.
        return origin + properties.spaCallbackPath()
                + "#token=" + encoded(signedIn.session().token())
                + "&expiresAt=" + encoded(signedIn.session().expiresAt().toString());
    }

    private String spaOrigin() {
        return spa.baseUrl().toString().replaceAll("/+$", "");
    }

    private static String domainOf(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) {
            return null;
        }
        String domain = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
        return domain.isEmpty() ? null : domain;
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** S256, the only challenge method this sends — {@code plain} is PKCE with the proof removed. */
    private static String challengeFor(String verifier) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String digest(String value) {
        StringBuilder hex = new StringBuilder();
        for (byte b : sha256(value.getBytes(StandardCharsets.UTF_8))) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", impossible);
        }
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
