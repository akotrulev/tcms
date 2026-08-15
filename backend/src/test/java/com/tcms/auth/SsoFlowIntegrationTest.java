package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.IdentityProvider;
import com.tcms.identity.IdentityProviderKind;
import com.tcms.identity.IdentityProviderRepository;
import com.tcms.identity.ProviderSecrets;
import com.tcms.identity.UserIdentity;
import com.tcms.identity.UserIdentityRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.net.URI;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * The flow, end to end, against an issuer this test stands up.
 *
 * <p>Closes §217 FD-10, FD-11, FD-12 and FD-14 — all four recorded as expected-green for one
 * reason: the HTTP path had no observer. {@code OidcIdTokenVerifierTest} proves the verifier refuses
 * a wrong nonce and nothing proved {@code SsoLoginService} hands it the attempt's own; two green
 * tests either side of nothing.
 *
 * <p>Fixture UUIDs take prefix {@code f2} — hand-managed, no check behind it, collided five times
 * (§19…§62). The provider CRUD next door takes {@code f3}.
 *
 * <h2>What is real here, and the one thing that is not</h2>
 *
 * <p>Real: the authorize redirect and its {@code state}/{@code nonce}/PKCE parameters, the attempt
 * row and its conditional claim, the company context opened from it, the provider's lifecycle check
 * inside that context, the token exchange over a socket, the JWKS fetch, the signature check, the
 * {@code iss}/{@code aud}/{@code nonce} comparisons, the identity resolution and the session.
 *
 * <p>Not real: {@link LoopbackDiscovery} permits a plain-HTTP issuer for {@code 127.0.0.1} alone,
 * because {@link OidcDiscovery#forIssuer} refuses one at runtime and the stub cannot serve TLS
 * without a certificate this project has no dependency to generate. The gate that exemption steps
 * around is observed directly by {@code OidcDiscoveryTest}, written in this slice because nothing
 * observed it at all.
 */
@Import(SsoFlowIntegrationTest.LoopbackDiscovery.class)
@DisplayName("SSO — the wire nothing crossed, and the nonce nothing carried")
class SsoFlowIntegrationTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("f2000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("f2000000-0000-4000-8000-00000000000b");

    /** The person NORTH's issuer authenticates. */
    private static final UUID KIM = UUID.fromString("f2003000-0000-4000-8000-000000000001");

    private static final String CLIENT_ID = "tcms-client";
    private static final String SUBJECT = "sub-1";
    private static final String EMAIL = "kim@acme.test";

    @Autowired
    private SsoLoginService sso;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserIdentityRepository identities;

    @Autowired
    private ProviderSecrets secrets;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-sso", "Northwind", now);
            provisionCompany(SOUTH, "south-sso", "Southwind", now);
            activeUser(KIM, EMAIL, now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> member(NORTH, KIM, "K. Kim", now)));

        resetMutableRows();
    }

    /**
     * Removes every attempt, identity and provider.
     *
     * <p>A replay test that inherited a previous test's consumed attempt would pass on JUnit's
     * method order rather than on the conditional UPDATE — §19's defect. Through each company's own
     * app-role connection, so RLS is in force while the cleanup runs.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : new UUID[] {NORTH, SOUTH}) {
            try (var connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM sso_login_attempt");
                execute(connection, "DELETE FROM user_identity");
                execute(connection, "DELETE FROM identity_provider");
            }
        }
    }

    // =====================================================================

    @Test
    @DisplayName("a signed-in person gets a TCMS session, and the IdP's token stops here")
    void aSignedInPersonGetsATcmsSession() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);

            Map<String, String> query = parseQuery(authorize(provider.getId()));
            issuer.willIssue(CLIENT_ID, SUBJECT, EMAIL, true, query.get("nonce"));

            SsoLoginService.SignedIn signedIn =
                    sso.callback(query.get("state"), "any-code").orElseThrow();

            assertThat(signedIn.userId()).isEqualTo(KIM);
            assertThat(signedIn.companyId()).isEqualTo(NORTH);
            assertThat(signedIn.session().token())
                    .describedAs("a TCMS session, not the issuer's — the id_token never leaves "
                            + "SsoLoginService.complete")
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("§217 FD-11: the nonce from the attempt is the one required of the token")
    void theNonceFromTheAttemptIsTheOneRequiredOfTheToken() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);

            Map<String, String> query = parseQuery(authorize(provider.getId()));
            issuer.willIssue(CLIENT_ID, SUBJECT, EMAIL, true, "a-different-nonce");

            assertThat(sso.callback(query.get("state"), "any-code"))
                    .describedAs("the verifier is proved to refuse a wrong nonce, and until this "
                            + "test nothing proved the service handed it the right one")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a token from another issuer is refused even when the subject is linked")
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        try (StubIssuer issuer = new StubIssuer(); StubIssuer other = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);

            Map<String, String> query = parseQuery(authorize(provider.getId()));
            issuer.willIssue(CLIENT_ID, SUBJECT, EMAIL, true, query.get("nonce"));
            issuer.override(Map.of("iss", other.issuer()));

            assertThat(sso.callback(query.get("state"), "any-code")).isEmpty();
        }
    }

    @Test
    @DisplayName("a token for another audience is refused")
    void aTokenForAnotherAudienceIsRefused() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);

            Map<String, String> query = parseQuery(authorize(provider.getId()));
            issuer.willIssue("somebody-elses-client", SUBJECT, EMAIL, true, query.get("nonce"));

            assertThat(sso.callback(query.get("state"), "any-code")).isEmpty();
        }
    }

    @Test
    @DisplayName("§217 FD-10: a replayed state is refused")
    void aReplayedStateIsRefused() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);

            Map<String, String> query = parseQuery(authorize(provider.getId()));
            issuer.willIssue(CLIENT_ID, SUBJECT, EMAIL, true, query.get("nonce"));

            assertThat(sso.callback(query.get("state"), "any-code")).isPresent();
            assertThat(sso.callback(query.get("state"), "any-code"))
                    .describedAs("single-use, claimed by a conditional UPDATE that commits before "
                            + "the token exchange")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a provider disabled between the redirect and the callback refuses")
    void aProviderDisabledMidFlowRefuses() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);

            Map<String, String> query = parseQuery(authorize(provider.getId()));
            issuer.willIssue(CLIENT_ID, SUBJECT, EMAIL, true, query.get("nonce"));
            disable(NORTH, provider.getId());

            assertThat(sso.callback(query.get("state"), "any-code"))
                    .describedAs("the lifecycle check happens inside the company context, against "
                            + "the RLS-protected row — not against the pre-authentication lookup")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("a disabled provider cannot even start a sign-in")
    void aDisabledProviderCannotStart() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            disable(NORTH, provider.getId());

            assertThat(sso.authorize(provider.getId())).isEmpty();
        }
    }

    @Test
    @DisplayName("THE takeover vector: another company's issuer asserting the same subject")
    void anotherCompanysIssuerAssertingTheSameSubjectDoesNotReachThisUser() throws Exception {
        try (StubIssuer ours = new StubIssuer(); StubIssuer theirs = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, ours.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);
            IdentityProvider otherCompanys = liveProvider(SOUTH, theirs.issuer());

            Map<String, String> query = parseQuery(authorize(otherCompanys.getId()));
            theirs.willIssue(CLIENT_ID, SUBJECT, EMAIL, true, query.get("nonce"));

            Optional<SsoLoginService.SignedIn> signedIn = sso.callback(query.get("state"), "any-code");

            assertThat(signedIn.map(SsoLoginService.SignedIn::userId))
                    .describedAs("identity is (company, issuer, subject), so a different client's "
                            + "IdP asserting sub-1 must never resolve to KIM")
                    .isNotEqualTo(Optional.of(KIM));
        }
    }

    @Test
    @DisplayName("§217 FD-14: the landing URL carries the session in the FRAGMENT")
    void theLandingUrlPutsTheSessionInTheFragment() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            IdentityProvider provider = liveProvider(NORTH, issuer.issuer());
            linkIdentity(NORTH, provider, SUBJECT, KIM);

            Map<String, String> query = parseQuery(authorize(provider.getId()));
            issuer.willIssue(CLIENT_ID, SUBJECT, EMAIL, true, query.get("nonce"));
            SsoLoginService.SignedIn signedIn =
                    sso.callback(query.get("state"), "any-code").orElseThrow();

            String landing = sso.landingUrl(signedIn);

            // A fragment is never sent to a server, so the session does not reach this instance's
            // access log, the reverse proxy's, or the Referer of the first outbound link the SPA
            // renders. The query string would put a live credential in all three.
            assertThat(landing).contains("#token=");
            assertThat(landing.substring(0, landing.indexOf('#')))
                    .describedAs("nothing before the fragment may carry the session")
                    .doesNotContain(signedIn.session().token());
            assertThat(sso.failureUrl())
                    .describedAs("one opaque marker for every cause (§6r)")
                    .endsWith("#error=sso_failed");
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /**
     * A live provider, written through the repository.
     *
     * <p>Through the repository and not through {@code IdentityProviderService} for two reasons at
     * once: the service refuses a plain-HTTP issuer, and {@code register} forces
     * {@code enabled = false}. The same fact as {@code deploy/keycloak/README.md}'s SQL recipe,
     * arriving from the test side — see {@link StubIssuer}'s header for the decision.
     */
    private IdentityProvider liveProvider(UUID companyId, String issuerUri) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        return inCompany(companyId, () -> tx.execute(status -> {
            IdentityProvider provider = providers.saveAndFlush(IdentityProvider.register(
                    id,
                    companyId,
                    "stub-" + id.toString().substring(0, 8),
                    IdentityProviderKind.OIDC,
                    "Stub issuer",
                    issuerUri,
                    CLIENT_ID,
                    secrets.encrypt("stub-secret"),
                    null,
                    now));
            provider.applyEdit(
                    provider.getDisplayName(),
                    issuerUri,
                    CLIENT_ID,
                    provider.getClientSecretEncrypted(),
                    false,
                    false,
                    null,
                    true,
                    null,
                    now);
            return providers.saveAndFlush(provider);
        }));
    }

    private void linkIdentity(UUID companyId, IdentityProvider provider, String subject, UUID userId) {
        Instant now = clock.instant();
        inCompany(companyId, () -> tx.executeWithoutResult(status -> identities.saveAndFlush(
                UserIdentity.link(UUID.randomUUID(), companyId, userId, provider.getId(), subject,
                        EMAIL, now))));
    }

    private void disable(UUID companyId, UUID providerId) {
        Instant now = clock.instant();
        inCompany(companyId, () -> tx.executeWithoutResult(status -> {
            IdentityProvider provider = providers.findById(providerId).orElseThrow();
            provider.applyEdit(
                    provider.getDisplayName(),
                    provider.getIssuerUri(),
                    provider.getClientId(),
                    provider.getClientSecretEncrypted(),
                    false,
                    false,
                    null,
                    false,
                    null,
                    now);
            providers.saveAndFlush(provider);
        }));
    }

    private String authorize(UUID providerId) {
        return sso.authorize(providerId)
                .orElseThrow(() -> new AssertionError("authorize() refused a live provider"));
    }

    /** The authorize redirect's query, which is where the attempt's own nonce and state are read. */
    private static Map<String, String> parseQuery(String url) {
        Map<String, String> values = new HashMap<>();
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            values.put(
                    java.net.URLDecoder.decode(pair.substring(0, equals), java.nio.charset.StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(equals + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return values;
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private static <T> T inCompany(UUID companyId, java.util.function.Supplier<T> action) {
        try (var ignored = CompanyContext.open(companyId)) {
            return action.get();
        }
    }

    private void provisionCompany(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    /**
     * Permits a plain-HTTP issuer on the loopback interface, and nowhere else.
     *
     * <p>The narrowest exemption that lets {@link StubIssuer} be reachable: any issuer that is not
     * {@code http://127.0.0.1:} goes to the real {@link OidcDiscovery#forIssuer}, https gate
     * included. Every check downstream of the document — the self-declared-issuer comparison aside
     * — is untouched, because the document this returns is the one the stub actually served.
     */
    @TestConfiguration
    static class LoopbackDiscovery {

        @Bean
        @Primary
        OidcDiscovery loopbackOidcDiscovery(RestClient http, OidcProperties properties, Clock clock) {
            return new OidcDiscovery(http, properties, clock) {
                @Override
                public Optional<OidcDiscoveryDocument> forIssuer(String issuerUri) {
                    if (issuerUri == null || !issuerUri.startsWith("http://127.0.0.1:")) {
                        return super.forIssuer(issuerUri);
                    }
                    OidcDiscoveryDocument document = http.get()
                            .uri(URI.create(issuerUri + "/.well-known/openid-configuration"))
                            .retrieve()
                            .body(OidcDiscoveryDocument.class);
                    return Optional.ofNullable(document).filter(OidcDiscoveryDocument::isComplete);
                }
            };
        }
    }
}
