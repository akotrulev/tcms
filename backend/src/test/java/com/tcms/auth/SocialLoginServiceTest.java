package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.InvitationService;
import com.tcms.identity.SocialIdentityService;
import com.tcms.identity.SocialProviderKey;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The social orchestration against the real attempt table, with the network stubbed out —
 * {@code OidcDiscovery}, {@code OidcClient}, {@code OidcIdTokenVerifier} and
 * {@code GithubUserClient} are the four seams that would otherwise call the internet, and
 * everything else (attempt persistence, single-use claiming, policy, session issuance) is the
 * production path.
 */
@DisplayName("SocialLoginService — two intents, one callback, one refusal")
class SocialLoginServiceTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void socialProviders(DynamicPropertyRegistry registry) {
        // GitHub and Google are configured; GitLab deliberately is not, so the unconfigured
        // refusal has a live subject.
        registry.add("tcms.auth.social.github.client-id", () -> "gh-client");
        registry.add("tcms.auth.social.github.client-secret", () -> "gh-secret");
        registry.add("tcms.auth.social.google.client-id", () -> "goog-client");
        registry.add("tcms.auth.social.google.client-secret", () -> "goog-secret");
    }

    @Autowired
    private SocialLoginService service;

    @Autowired
    private SocialIdentityService identities;

    @Autowired
    private SocialLoginAttemptRepository attempts;

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private OidcDiscovery discovery;

    @MockitoBean
    private OidcClient oidc;

    @MockitoBean
    private OidcIdTokenVerifier verifier;

    @MockitoBean
    private GithubUserClient github;

    // -------------------------------------------------------------------- authorize ----

    @Test
    @DisplayName("an unconfigured provider refuses to build an authorize URL")
    void unconfiguredRefuses() {
        assertThat(service.authorize(SocialProviderKey.GITLAB)).isEmpty();
    }

    @Test
    @DisplayName("the GitHub authorize URL carries client_id, redirect_uri, scope and state — and persists a LOGIN attempt with the state hashed")
    void githubAuthorize() {
        Optional<String> url = service.authorize(SocialProviderKey.GITHUB);

        assertThat(url).isPresent();
        assertThat(url.get()).startsWith("https://github.com/login/oauth/authorize?");
        Map<String, String> params = queryParams(url.get());
        assertThat(params).containsEntry("client_id", "gh-client");
        assertThat(params.get("redirect_uri")).endsWith("/api/v1/auth/social/callback");
        assertThat(params.get("scope")).isEqualTo("read:user user:email");

        SocialLoginAttempt attempt = attemptFor(params.get("state"));
        assertThat(attempt.getIntent()).isEqualTo(SocialLoginAttempt.Intent.LOGIN);
        assertThat(attempt.getUserId()).isNull();
        assertThat(attempt.getProvider()).isEqualTo(SocialProviderKey.GITHUB);
        assertThat(attempt.getStateHash()).isNotEqualTo(params.get("state"));
    }

    @Test
    @DisplayName("the Google authorize URL is a full OIDC request: nonce and an S256 PKCE challenge")
    void googleAuthorize() {
        when(discovery.forIssuer("https://accounts.google.com"))
                .thenReturn(Optional.of(googleDocument()));

        Optional<String> url = service.authorize(SocialProviderKey.GOOGLE);

        assertThat(url).isPresent();
        Map<String, String> params = queryParams(url.get());
        assertThat(url.get()).startsWith("https://google.test/authorize?");
        assertThat(params)
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "goog-client")
                .containsEntry("code_challenge_method", "S256")
                .containsKey("nonce")
                .containsKey("code_challenge");

        SocialLoginAttempt attempt = attemptFor(params.get("state"));
        assertThat(attempt.getIntent()).isEqualTo(SocialLoginAttempt.Intent.LOGIN);
        assertThat(attempt.getNonce()).isEqualTo(params.get("nonce"));
    }

    @Test
    @DisplayName("beginLink persists a LINK attempt bound to the caller")
    void beginLinkBindsTheUser() {
        UUID userId = activated("slt-linker", "slt-linker@test.test");

        Optional<String> url = service.beginLink(SocialProviderKey.GITHUB, userId);

        assertThat(url).isPresent();
        SocialLoginAttempt attempt = attemptFor(queryParams(url.get()).get("state"));
        assertThat(attempt.getIntent()).isEqualTo(SocialLoginAttempt.Intent.LINK);
        assertThat(attempt.getUserId()).isEqualTo(userId);
    }

    // --------------------------------------------------------------------- callback ----

    @Test
    @DisplayName("a callback with an unknown, blank or garbage state fails")
    void unknownStateFails() {
        assertThat(service.callback("never-issued", "code"))
                .isInstanceOf(SocialLoginService.Outcome.Failed.class);
        assertThat(service.callback(null, "code"))
                .isInstanceOf(SocialLoginService.Outcome.Failed.class);
        assertThat(service.callback("state", null))
                .isInstanceOf(SocialLoginService.Outcome.Failed.class);
    }

    @Test
    @DisplayName("a second presentation of the same state fails — consume claims the row exactly once")
    void replayFails() {
        UUID userId = activated("slt-replay", "slt-replay@test.test");
        identities.link(userId, SocialProviderKey.GITHUB, claims("gh-replay"));
        githubAsserts("gh-replay");
        String state = queryParams(service.authorize(SocialProviderKey.GITHUB).orElseThrow())
                .get("state");

        assertThat(service.callback(state, "code"))
                .isInstanceOf(SocialLoginService.Outcome.LoggedIn.class);
        assertThat(service.callback(state, "code"))
                .isInstanceOf(SocialLoginService.Outcome.Failed.class);
    }

    @Test
    @DisplayName("a LOGIN callback for a linked subject signs in with a session and the default company")
    void loginResolvesLinkedSubject() {
        ProvisionCompanyResult company = provisionActivated("slt-login", "slt-login@test.test");
        identities.link(company.adminUserId(), SocialProviderKey.GITHUB, claims("gh-login"));
        githubAsserts("gh-login");
        String state = queryParams(service.authorize(SocialProviderKey.GITHUB).orElseThrow())
                .get("state");

        SocialLoginService.Outcome outcome = service.callback(state, "code");

        assertThat(outcome).isInstanceOf(SocialLoginService.Outcome.LoggedIn.class);
        SocialLoginService.SignedIn signedIn =
                ((SocialLoginService.Outcome.LoggedIn) outcome).signedIn();
        assertThat(signedIn.userId()).isEqualTo(company.adminUserId());
        assertThat(signedIn.companyId()).isEqualTo(company.companyId());
        assertThat(signedIn.session().token()).isNotBlank();
    }

    @Test
    @DisplayName("a LOGIN callback for an unknown subject fails — never a provision")
    void loginNeverProvisions() {
        githubAsserts("gh-stranger");
        String state = queryParams(service.authorize(SocialProviderKey.GITHUB).orElseThrow())
                .get("state");

        assertThat(service.callback(state, "code"))
                .isInstanceOf(SocialLoginService.Outcome.Failed.class);
    }

    @Test
    @DisplayName("the OIDC leg (Google) exchanges, verifies against the attempt's nonce, and signs in")
    void googleLoginRidesTheOidcMachinery() {
        ProvisionCompanyResult company = provisionActivated("slt-goog", "slt-goog@test.test");
        identities.link(company.adminUserId(), SocialProviderKey.GOOGLE, claims("goog-login"));
        when(discovery.forIssuer("https://accounts.google.com"))
                .thenReturn(Optional.of(googleDocument()));
        when(oidc.exchange(eq("https://google.test/token"), eq("goog-client"), eq("goog-secret"),
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new OidcTokenResponse("an-id-token")));
        when(verifier.verify(any(), eq("an-id-token"))).thenReturn(Optional.of(
                Jwt.withTokenValue("an-id-token")
                        .header("alg", "RS256")
                        .subject("goog-login")
                        .claim("email", "slt-goog@test.test")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build()));
        String state = queryParams(service.authorize(SocialProviderKey.GOOGLE).orElseThrow())
                .get("state");

        SocialLoginService.Outcome outcome = service.callback(state, "code");

        assertThat(outcome).isInstanceOf(SocialLoginService.Outcome.LoggedIn.class);
    }

    @Test
    @DisplayName("a LINK callback links and answers Linked with the provider")
    void linkCallbackLinks() {
        UUID userId = activated("slt-link", "slt-link@test.test");
        githubAsserts("gh-new-link");
        String state = queryParams(service.beginLink(SocialProviderKey.GITHUB, userId)
                .orElseThrow()).get("state");

        SocialLoginService.Outcome outcome = service.callback(state, "code");

        assertThat(outcome)
                .isEqualTo(new SocialLoginService.Outcome.Linked(SocialProviderKey.GITHUB));
        assertThat(identities.identitiesOf(userId)).hasSize(1);
    }

    @Test
    @DisplayName("a LINK callback for a subject already on another account fails")
    void linkOfATakenSubjectFails() {
        UUID first = activated("slt-owner", "slt-owner@test.test");
        UUID second = activated("slt-thief", "slt-thief@test.test");
        identities.link(first, SocialProviderKey.GITHUB, claims("gh-owned"));
        githubAsserts("gh-owned");
        String state = queryParams(service.beginLink(SocialProviderKey.GITHUB, second)
                .orElseThrow()).get("state");

        assertThat(service.callback(state, "code"))
                .isInstanceOf(SocialLoginService.Outcome.Failed.class);
        assertThat(identities.identitiesOf(second)).isEmpty();
    }

    // ------------------------------------------------------------------------ URLs ----

    @Test
    @DisplayName("the three landings: session in the fragment, linked marker, one opaque failure")
    void landingUrlsUseTheFragment() {
        assertThat(service.failureUrl()).contains("#error=sso_failed").doesNotContain("?error");
        assertThat(service.linkedUrl(SocialProviderKey.GITHUB)).contains("#linked=GITHUB");
    }

    // -------------------------------------------------------------------- fixtures ----

    private void githubAsserts(String subject) {
        when(github.identityFor(eq("gh-client"), eq("gh-secret"), anyString(), anyString()))
                .thenReturn(Optional.of(new GithubUserClient.GithubIdentity(
                        subject, "Somebody", subject + "@github.test", true)));
    }

    private static OidcDiscoveryDocument googleDocument() {
        return new OidcDiscoveryDocument(
                "https://accounts.google.com",
                "https://google.test/authorize",
                "https://google.test/token",
                "https://google.test/jwks");
    }

    private static SocialIdentityService.Claims claims(String subject) {
        return new SocialIdentityService.Claims(subject, subject + "@social.test", "Somebody");
    }

    private SocialLoginAttempt attemptFor(String state) {
        return transactions.execute(status ->
                attempts.findByStateHash(sha256Hex(state)).orElseThrow());
    }

    private static Map<String, String> queryParams(String url) {
        var components = UriComponentsBuilder.fromUriString(url).build();
        return components.getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> java.net.URLDecoder.decode(
                                entry.getValue(), StandardCharsets.UTF_8)));
    }

    private static String sha256Hex(String value) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private UUID activated(String slug, String email) {
        return provisionActivated(slug, email).adminUserId();
    }

    private ProvisionCompanyResult provisionActivated(String slug, String email) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                slug, "SLT " + slug, email, "SLT Admin"));
        result.invitationLink().ifPresent(link -> {
            String token = link.replaceAll(".*\\?token=", "");
            boolean needsPassword = invitations.preview(token)
                    .map(InvitationService.InvitationPreview::passwordRequired)
                    .orElse(false);
            assertThat(invitations.accept(token, needsPassword ? "correct-horse-battery" : null))
                    .isInstanceOf(InvitationService.Accepted.class);
        });
        return result;
    }
}
