package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.InvitationProperties;
import com.tcms.identity.SocialIdentityService;
import com.tcms.identity.SocialProviderKey;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The social authorization-code flow — {@link SsoLoginService}'s twin for the instance-level
 * providers, with two structural differences. First, two intents share the callback: LOGIN and
 * LINK, told apart by the attempt row the {@code state} resolves, never by the URL. Second,
 * GitHub is not OIDC — no discovery, no id_token, no nonce — so its leg exchanges the code and
 * asks its user API instead ({@link GithubUserClient}); GitLab and Google ride the §6r OIDC
 * machinery unchanged.
 *
 * <p>Every refusal is the same refusal, and the browser legs land on one opaque marker.
 *
 * <p>Unlike its twin this class opens <strong>no company context</strong>: both social tables
 * are instance-level ({@code V58}), and the session's company is resolved the way
 * {@code AuthenticationService} resolves it, from the memberships, server-side.
 */
@Service
public class SocialLoginService {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginService.class);

    private static final int STATE_BYTES = 32;

    /** RFC 7636 allows 43–128 characters; 32 random bytes base64url-encoded is 43. */
    private static final int VERIFIER_BYTES = 32;

    private static final String GOOGLE_ISSUER = "https://accounts.google.com";

    /** A session, exactly as local sign-in produces one. */
    public record SignedIn(UUID userId, UUID companyId, SessionTokens.Session session) {
    }

    /** The callback's answer — which landing to build. */
    public sealed interface Outcome {
        record LoggedIn(SignedIn signedIn) implements Outcome {
        }

        record Linked(SocialProviderKey provider) implements Outcome {
        }

        record Failed() implements Outcome {
        }
    }

    private final SocialProperties properties;
    private final SocialLoginAttemptRepository attempts;
    private final SocialIdentityService identities;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final OidcDiscovery discovery;
    private final OidcClient oidc;
    private final OidcIdTokenVerifier verifier;
    private final GithubUserClient github;
    private final SessionTokens tokens;
    private final OidcProperties oidcProperties;
    private final InvitationProperties spa;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SocialLoginService(
            SocialProperties properties,
            SocialLoginAttemptRepository attempts,
            SocialIdentityService identities,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            OidcDiscovery discovery,
            OidcClient oidc,
            OidcIdTokenVerifier verifier,
            GithubUserClient github,
            SessionTokens tokens,
            OidcProperties oidcProperties,
            InvitationProperties spa,
            TransactionTemplate transactions,
            Clock clock) {
        this.properties = properties;
        this.attempts = attempts;
        this.identities = identities;
        this.users = users;
        this.memberships = memberships;
        this.discovery = discovery;
        this.oidc = oidc;
        this.verifier = verifier;
        this.github = github;
        this.tokens = tokens;
        this.oidcProperties = oidcProperties;
        this.spa = spa;
        this.transactions = transactions;
        this.clock = clock;
    }

    public List<SocialProviderKey> configuredProviders() {
        return properties.configuredProviders();
    }

    /** The login leg — anyone on the internet may start one. */
    public Optional<String> authorize(SocialProviderKey provider) {
        return begin(provider, SocialLoginAttempt.Intent.LOGIN, null);
    }

    /** The link leg — the controller has authenticated {@code userId} already. */
    public Optional<String> beginLink(SocialProviderKey provider, UUID userId) {
        return begin(provider, SocialLoginAttempt.Intent.LINK, userId);
    }

    private Optional<String> begin(
            SocialProviderKey provider, SocialLoginAttempt.Intent intent, UUID userId) {

        if (!properties.configured(provider)) {
            log.info("Social authorize refused: {} is not configured", provider);
            return Optional.empty();
        }
        String state = randomToken(STATE_BYTES);
        String nonce = randomToken(STATE_BYTES);
        String verifierValue = randomToken(VERIFIER_BYTES);
        String redirectUri = oidcProperties.socialRedirectUri();
        Instant now = clock.instant();

        Optional<String> url = switch (provider) {
            case GITHUB -> githubAuthorizeUrl(state, redirectUri);
            case GITLAB, GOOGLE ->
                    oidcAuthorizeUrl(provider, state, nonce, verifierValue, redirectUri);
        };
        if (url.isEmpty()) {
            return Optional.empty();
        }
        transactions.executeWithoutResult(status -> attempts.saveAndFlush(SocialLoginAttempt.begin(
                UUID.randomUUID(),
                provider,
                intent,
                userId,
                digest(state),
                verifierValue,
                nonce,
                redirectUri,
                now,
                now.plus(oidcProperties.attemptValidity()))));
        return url;
    }

    private Optional<String> githubAuthorizeUrl(String state, String redirectUri) {
        SocialProperties.Client client = properties.clientFor(SocialProviderKey.GITHUB);
        return Optional.of(GithubUserClient.AUTHORIZE_URL
                + "?client_id=" + encoded(client.clientId())
                + "&redirect_uri=" + encoded(redirectUri)
                + "&scope=" + encoded("read:user user:email")
                + "&state=" + encoded(state));
    }

    private Optional<String> oidcAuthorizeUrl(
            SocialProviderKey provider, String state, String nonce, String verifierValue,
            String redirectUri) {

        return discovery.forIssuer(issuerOf(provider)).map(document ->
                document.authorizationEndpoint()
                        + "?response_type=code"
                        + "&client_id=" + encoded(properties.clientFor(provider).clientId())
                        + "&redirect_uri=" + encoded(redirectUri)
                        + "&scope=" + encoded("openid email profile")
                        + "&state=" + encoded(state)
                        + "&nonce=" + encoded(nonce)
                        + "&code_challenge=" + encoded(challengeFor(verifierValue))
                        + "&code_challenge_method=S256");
    }

    private String issuerOf(SocialProviderKey provider) {
        return switch (provider) {
            case GOOGLE -> GOOGLE_ISSUER;
            case GITLAB -> properties.gitlabBaseUrl();
            case GITHUB -> throw new IllegalArgumentException("GitHub is not an OIDC issuer");
        };
    }

    /** One callback for both intents — the attempt row decides which (the plan's decision 11). */
    public Outcome callback(String state, String code) {
        if (state == null || state.isBlank() || code == null || code.isBlank()) {
            return new Outcome.Failed();
        }
        Instant now = clock.instant();
        SocialLoginAttempt attempt = transactions.execute(status ->
                attempts.findByStateHash(digest(state)).orElse(null));
        if (attempt == null || !attempt.isUsable(now)) {
            log.info("Social callback refused: no usable attempt for the presented state");
            return new Outcome.Failed();
        }
        Integer claimed = transactions.execute(status -> attempts.consume(attempt.getId(), now));
        if (claimed == null || claimed != 1) {
            log.info("Social callback refused: attempt {} was already consumed", attempt.getId());
            return new Outcome.Failed();
        }

        Optional<SocialIdentityService.Claims> claims = assertIdentity(attempt, code);
        if (claims.isEmpty()) {
            return new Outcome.Failed();
        }
        return switch (attempt.getIntent()) {
            case LOGIN -> completeLogin(attempt.getProvider(), claims.get());
            case LINK -> completeLink(attempt, claims.get());
        };
    }

    /** The protocol step: code in, verified claims out. Provider-shaped, policy-free. */
    private Optional<SocialIdentityService.Claims> assertIdentity(
            SocialLoginAttempt attempt, String code) {

        SocialProperties.Client client = properties.clientFor(attempt.getProvider());
        if (client == null || !client.configured()) {
            return Optional.empty();
        }
        if (attempt.getProvider() == SocialProviderKey.GITHUB) {
            return github.identityFor(
                            client.clientId(), client.clientSecret(), code,
                            attempt.getRedirectUri())
                    .map(identity -> new SocialIdentityService.Claims(
                            identity.subject(), identity.email(), identity.name()));
        }
        String issuer = issuerOf(attempt.getProvider());
        Optional<OidcDiscoveryDocument> document = discovery.forIssuer(issuer);
        if (document.isEmpty()) {
            return Optional.empty();
        }
        Optional<OidcTokenResponse> exchanged = oidc.exchange(
                document.get().tokenEndpoint(),
                client.clientId(),
                client.clientSecret(),
                code,
                attempt.getRedirectUri(),
                attempt.getCodeVerifier());
        if (exchanged.isEmpty()) {
            return Optional.empty();
        }
        // providerId is only a log handle in Expected; a stable name-based UUID keeps the log
        // lines attributable without inventing a database row for an instance-level provider.
        Optional<Jwt> verified = verifier.verify(
                new OidcIdTokenVerifier.Expected(
                        UUID.nameUUIDFromBytes(("social:" + attempt.getProvider())
                                .getBytes(StandardCharsets.UTF_8)),
                        issuer,
                        client.clientId(),
                        document.get().jwksUri(),
                        attempt.getNonce()),
                exchanged.get().idToken());
        return verified.map(idToken -> new SocialIdentityService.Claims(
                idToken.getSubject(),
                idToken.getClaimAsString("email"),
                idToken.getClaimAsString("name")));
    }

    private Outcome completeLogin(SocialProviderKey provider, SocialIdentityService.Claims claims) {
        SocialIdentityService.LoginResolution resolution =
                identities.resolveLogin(provider, claims);
        if (!(resolution instanceof SocialIdentityService.LoginResolution.Resolved resolved)) {
            return new Outcome.Failed();
        }
        SignedIn signedIn = transactions.execute(status -> issueFor(resolved.userId()));
        return signedIn == null ? new Outcome.Failed() : new Outcome.LoggedIn(signedIn);
    }

    private Outcome completeLink(SocialLoginAttempt attempt, SocialIdentityService.Claims claims) {
        SocialIdentityService.LinkOutcome outcome =
                identities.link(attempt.getUserId(), attempt.getProvider(), claims);
        return outcome instanceof SocialIdentityService.LinkOutcome.Linked
                ? new Outcome.Linked(attempt.getProvider())
                : new Outcome.Failed();
    }

    /**
     * The session, minted by {@link SessionTokens#issue} exactly as local sign-in mints it. The
     * default company is resolved the way {@code AuthenticationService} resolves it —
     * {@code memberships.findCompanyIdsForUser}, the V7/V8 SECURITY DEFINER function —
     * server-side, never from anything the callback carried.
     */
    private SignedIn issueFor(UUID userId) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        UUID companyId =
                memberships.findCompanyIdsForUser(userId).stream().findFirst().orElse(null);
        log.info("Signed in user {} via social with active company {}", userId, companyId);
        return new SignedIn(userId, companyId,
                tokens.issue(userId, companyId, user.getPermissionVersion(), false));
    }

    /** One destination and one opaque marker for every cause — {@code SsoLoginService}'s rule. */
    public String failureUrl() {
        return spaOrigin() + oidcProperties.spaCallbackPath() + "#error=sso_failed";
    }

    /** The fragment, never the query string — {@link SsoLoginService#landingUrl}'s reasoning. */
    public String landingUrl(SignedIn signedIn) {
        return spaOrigin() + oidcProperties.spaCallbackPath()
                + "#token=" + encoded(signedIn.session().token())
                + "&expiresAt=" + encoded(signedIn.session().expiresAt().toString());
    }

    /** Where a completed LINK lands: the SPA callback routes the marker to the security page. */
    public String linkedUrl(SocialProviderKey provider) {
        return spaOrigin() + oidcProperties.spaCallbackPath()
                + "#linked=" + encoded(provider.name());
    }

    private String spaOrigin() {
        return spa.baseUrl().toString().replaceAll("/+$", "");
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
