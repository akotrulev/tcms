package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.IdentityProvider;
import com.tcms.identity.IdentityProviderRepository;
import com.tcms.identity.ProviderSecrets;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /auth/ldap/{providerId}/login}, end to end — the directory is real, the TLS is real,
 * and the session is TCMS's own.
 *
 * <p>Fixture UUIDs take prefix {@code f3} — this plan's, shared with the provider CRUD next door in
 * {@code identity/}, so the values here continue that file's sequence rather than restarting it.
 * The directory server and its trust arrangement come from {@link TlsTestDirectory}.
 *
 * <p>The provider rows are written through {@link IdentityProviderRepository} inside a
 * {@code CompanyContext.open(...)} block, not through {@code IdentityProviderService} — the service
 * would be a second subject in a test about sign-in, and {@code register} forces
 * {@code enabled = false} (the same reasoning as {@code SsoFlowIntegrationTest#liveProvider}).
 */
@AutoConfigureMockMvc
@Import(LdapLoginEndpointTest.TrustingTheTestDirectory.class)
@DisplayName("Directory sign-in — one endpoint, one refusal, and the subject as the key")
class LdapLoginEndpointTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("f3000000-0000-4000-8000-00000000001a");
    /** An invited, ACTIVE member whose address the directory also holds. */
    private static final UUID DANA = UUID.fromString("f3003000-0000-4000-8000-000000000011");

    private static InMemoryDirectoryServer server;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private ProviderSecrets secrets;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Clock clock;

    @BeforeAll
    static void startDirectory() throws Exception {
        server = TlsTestDirectory.start();
    }

    @AfterAll
    static void stopDirectory() {
        if (server != null) {
            server.shutDown(true);
        }
    }

    /**
     * Trusts the test server's generated certificate through the same seam
     * {@code LdapDirectoryTest} uses, but on the bean the endpoint actually calls — the production
     * {@link LdapDirectory} in the Spring context would otherwise refuse the handshake against the
     * JVM default trust store, and every test here would pass on 401s that prove nothing.
     */
    @TestConfiguration
    static class TrustingTheTestDirectory {

        @Bean
        @Primary
        LdapDirectory trustingLdapDirectory(LdapProperties properties) {
            return new LdapDirectory(properties) {
                @Override
                protected java.util.Map<String, Object> environmentFor(Target target) {
                    java.util.Map<String, Object> environment = super.environmentFor(target);
                    environment.put("java.naming.ldap.factory.socket",
                            TlsTestDirectory.TrustAllSockets.class.getName());
                    return environment;
                }
            };
        }
    }

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            if (companies.findById(COMPANY).isEmpty()) {
                companies.save(Company.provision(COMPANY, "ldap-login", "Directoria", now));
            }
            if (users.findById(DANA).isEmpty()) {
                AppUser user = AppUser.invite(DANA, "dana@acme.test", "Dana Director", now);
                user.activate("hash", now);
                users.save(user);
            }
        });

        inCompany(() -> tx.executeWithoutResult(status -> {
            if (memberships.findByCompanyIdAndUserId(COMPANY, DANA).isEmpty()) {
                CompanyMembership membership =
                        CompanyMembership.invite(COMPANY, DANA, "Dana Director", null, now);
                membership.accept(now);
                memberships.save(membership);
            }
        }));

        // theSubjectIsTheKey renames dana's directory address; the server outlives each test.
        renameInDirectory("uid=dana,ou=people,dc=acme,dc=test", "dana@acme.test");

        try (var connection = appConnection(COMPANY)) {
            execute(connection, "DELETE FROM user_identity");
            execute(connection, "DELETE FROM identity_provider");
        }
    }

    // =====================================================================

    @Test
    @DisplayName("an invited person signs in with their directory password")
    void aLinkedDirectoryAccountSignsIn() throws Exception {
        UUID providerId = liveProvider(true);

        MvcResult response = login(providerId, "dana@acme.test", "directory-password");

        assertThat(statusOf(response)).isEqualTo(200);
        JsonNode body = json.readTree(response.getResponse().getContentAsString());
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("user").get("email").asText()).isEqualTo("dana@acme.test");
    }

    @Test
    @DisplayName("the second sign-in resolves the same account, and the address is never consulted")
    void theSubjectIsTheKey() throws Exception {
        UUID providerId = liveProvider(true);
        assertThat(statusOf(login(providerId, "dana@acme.test", "directory-password")))
                .isEqualTo(200);

        renameInDirectory("uid=dana,ou=people,dc=acme,dc=test", "dana.director@acme.test");
        MvcResult second = login(providerId, "dana.director@acme.test", "directory-password");

        assertThat(statusOf(second)).isEqualTo(200);
        assertThat(userIdentityCount())
                .describedAs("(provider, subject) is the key, so a rename at the directory must "
                        + "resolve the same row rather than forking a second identity — D3")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong password, a disabled provider and an unknown provider are one refusal")
    void everyRefusalIsTheSameRefusal() throws Exception {
        UUID providerId = liveProvider(true);

        assertThat(statusOf(login(providerId, "dana@acme.test", "wrong"))).isEqualTo(401);

        disable(providerId);
        assertThat(statusOf(login(providerId, "dana@acme.test", "directory-password")))
                .isEqualTo(401);

        assertThat(statusOf(login(UUID.fromString("f3000000-0000-4000-8000-0000000000ff"),
                "dana@acme.test", "directory-password")))
                .isEqualTo(401);
    }

    @Test
    @DisplayName("nobody is provisioned when the provider does not provision")
    void jitOffRefusesAStranger() throws Exception {
        UUID providerId = liveProvider(true);
        long usersBefore = appUserCount();

        // `stranger` proves who they are to the directory; the refusal is TCMS's own policy.
        MvcResult response = login(providerId, "stranger@acme.test", "directory-password");

        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(appUserCount()).isEqualTo(usersBefore);
    }

    @Test
    @DisplayName("no refusal names its cause, in the body or in the headers")
    void theRefusalCarriesNothing() throws Exception {
        UUID providerId = liveProvider(true);

        MvcResult response = login(providerId, "dana@acme.test", "wrong");

        assertThat(response.getResponse().getContentAsString()).isEmpty();
        assertThat(response.getResponse().getHeaderNames()).doesNotContain("X-Reason");
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private MvcResult login(UUID providerId, String email, String password) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/auth/ldap/" + providerId + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    /**
     * An enabled directory provider pointing at the in-memory server, written through the
     * repository. A fresh id per test, which also keys {@code BindThrottle} — the throttle is a
     * singleton in the shared context, so reusing one id would let one test's failures lock the
     * next test out.
     */
    private UUID liveProvider(boolean autoLinkVerifiedEmail) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        inCompany(() -> tx.executeWithoutResult(status -> {
            IdentityProvider provider = providers.saveAndFlush(IdentityProvider.registerDirectory(
                    id,
                    COMPANY,
                    "dir-" + id.toString().substring(0, 8),
                    "Acme Directory",
                    "ldaps://127.0.0.1:" + server.getListenPort("ldaps"),
                    "cn=service,dc=acme,dc=test",
                    secrets.encrypt("service-password"),
                    false,
                    "ou=people,dc=acme,dc=test",
                    "(mail={0})",
                    "entryUUID",
                    "mail",
                    "cn",
                    null,
                    now));
            applyLifecycle(provider, autoLinkVerifiedEmail, true, now);
            providers.saveAndFlush(provider);
        }));
        return id;
    }

    private void disable(UUID providerId) {
        Instant now = clock.instant();
        inCompany(() -> tx.executeWithoutResult(status -> {
            IdentityProvider provider = providers.findById(providerId).orElseThrow();
            applyLifecycle(provider, provider.isAutoLinkVerifiedEmail(), false, now);
            providers.saveAndFlush(provider);
        }));
    }

    /** The one mutator, with the directory fields carried through and the issuer half null. */
    private static void applyLifecycle(
            IdentityProvider provider, boolean autoLinkVerifiedEmail, boolean enabled, Instant now) {
        provider.applyEdit(
                provider.getDisplayName(),
                provider.getIssuerUri(),
                null,
                null,
                provider.getBindDn(),
                null,
                provider.isStartTls(),
                provider.getUserSearchBase(),
                provider.getUserSearchFilter(),
                provider.getUserIdAttribute(),
                provider.getEmailAttribute(),
                provider.getDisplayNameAttribute(),
                false,
                autoLinkVerifiedEmail,
                null,
                enabled,
                null,
                now);
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private void renameInDirectory(String dn, String newMail) throws Exception {
        server.modify(dn, new Modification(ModificationType.REPLACE, "mail", newMail));
    }

    private long userIdentityCount() throws SQLException {
        try (var connection = appConnection(COMPANY);
                var statement = connection.prepareStatement("SELECT count(*) FROM user_identity")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private long appUserCount() throws SQLException {
        try (var connection = superuserConnection();
                var statement = connection.prepareStatement("SELECT count(*) FROM app_user")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static void inCompany(Runnable action) {
        try (var ignored = CompanyContext.open(COMPANY)) {
            action.run();
        }
    }
}
