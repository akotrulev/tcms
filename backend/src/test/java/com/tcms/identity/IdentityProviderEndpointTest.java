package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /identity-providers} — the CRUD §217 shipped gated, validated and audited, and which
 * nothing asked a question.
 *
 * <p>That is why §217's own handoff lists this class by name under "what is not done": every
 * property below was implemented and none of it was observable. Driven over MockMvc rather than
 * against the service, for {@code ApiTokenEndpointTest}'s reason — the sealed {@link
 * IdentityProviderService.Outcome} only becomes a status code in the controller, so a service-level
 * test would assert the mapping the product does not use.
 *
 * <p>The secret assertions are the point. {@code company:idp}'s first consumer is also the first
 * surface in this product that accepts a <em>recoverable</em> credential — {@code ProviderSecrets}
 * encrypts where {@code ApiTokenSecrets} hashes, because a token exchange needs the plaintext back
 * — so "never on a read path" is a property a test must hold rather than a sentence a header
 * states. Asserted against the raw response body, not against a field, because the failure being
 * prevented is a field nobody expected.
 *
 * <p>Ids use the {@code f3…} prefix, checked against the suite rather than assumed: handoff §19's
 * fixture collision has happened five times and the prefix space is hand-managed with no check
 * behind it. Lane B's flow test next door takes {@code f2}.
 */
@AutoConfigureMockMvc
@DisplayName("Identity providers — the CRUD that was gated, validated, audited and undriven")
class IdentityProviderEndpointTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("f3000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("f3000000-0000-4000-8000-00000000000b");

    /** Company Admin in NORTH — holds {@code company:idp}. */
    private static final UUID ADMIN = UUID.fromString("f3003000-0000-4000-8000-000000000001");
    /** An ACTIVE member of NORTH holding nothing beyond the V15 baseline. */
    private static final UUID MEMBER = UUID.fromString("f3003000-0000-4000-8000-000000000002");
    /** Company Admin in SOUTH — the cross-company caller. */
    private static final UUID OUTSIDER = UUID.fromString("f3003000-0000-4000-8000-000000000003");

    private static final UUID GRANT_ADMIN = UUID.fromString("f3005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_OUTSIDER =
            UUID.fromString("f3005000-0000-4000-8000-000000000002");

    private static final String SECRET = "s3cret-nothing-may-echo";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private com.tcms.auth.SessionTokens tokens;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-idp", "Northwind", now);
            provisionCompany(SOUTH, "south-idp", "Southwind", now);

            activeUser(ADMIN, "admin@north-idp.test", now);
            activeUser(MEMBER, "member@north-idp.test", now);
            activeUser(OUTSIDER, "outsider@south-idp.test", now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> {
            member(NORTH, ADMIN, "A. Admin", now);
            member(NORTH, MEMBER, "M. Member", now);
            grantOnCompany(GRANT_ADMIN, NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantCompanyReadBaseline(NORTH);
        }));

        inCompany(SOUTH, () -> tx.executeWithoutResult(status -> {
            member(SOUTH, OUTSIDER, "O. Outsider", now);
            grantOnCompany(GRANT_OUTSIDER, SOUTH, Role.COMPANY_ADMIN_ID, OUTSIDER, now);
            grantCompanyReadBaseline(SOUTH);
        }));

        resetMutableRows();
    }

    /**
     * Removes every provider, claim and audit row.
     *
     * <p>This class creates the rows it reads its assertions from, and a key-uniqueness or
     * list-contents test that inherited a previous test's provider would pass or fail on JUnit's
     * method order — §19's defect, cheaper to prevent than to diagnose. Through each company's own
     * app-role connection, so RLS is in force while the cleanup runs.
     */
    private void resetMutableRows() throws SQLException {
        reset(NORTH);
        reset(SOUTH);
    }

    private void reset(UUID companyId) throws SQLException {
        try (var connection = appConnection(companyId)) {
            execute(connection, "DELETE FROM audit_event");
            execute(connection, "DELETE FROM company_email_domain");
            execute(connection, "DELETE FROM identity_provider");
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gate — company:idp, and nothing that merely resembles it
    // =====================================================================

    @Nested
    @DisplayName("company:idp decides, on every operation")
    class Gate {

        @Test
        @DisplayName("a plain member is refused on all five operations")
        void aPlainMemberIsRefusedEverywhere() throws Exception {
            assertThat(statusOf(list(MEMBER))).isEqualTo(403);
            assertThat(statusOf(register(MEMBER, body()))).isEqualTo(403);
            assertThat(statusOf(listDomains(MEMBER))).isEqualTo(403);
            assertThat(statusOf(putDomains(MEMBER, "[\"acme.test\"]"))).isEqualTo(403);

            UUID providerId = idOf(registered(ADMIN, body()));
            assertThat(statusOf(update(MEMBER, providerId, body()))).isEqualTo(403);
        }

        @Test
        @DisplayName("the LIST is refused, not emptied — an empty array would be a false claim")
        void theListIsRefusedRatherThanFiltered() throws Exception {
            // ApiTokensPage's argument, and the reason the SPA gates the fetch on the verdict
            // rather than firing it: 200-with-[] would tell a caller this company has no providers,
            // which is a different sentence from "you may not ask".
            assertThat(statusOf(list(MEMBER))).isEqualTo(403);
            assertThat(statusOf(list(ADMIN))).isEqualTo(200);
        }

        @Test
        @DisplayName("the refusal comes before the payload is judged — an invalid body still 403s")
        void theGateRunsFirstOnRegister() throws Exception {
            // Otherwise the endpoint reports which provider keys are taken in this company to a
            // caller who may not have one — RoleGrantService's directory-probe argument.
            assertThat(statusOf(register(MEMBER, body("", null, "not-a-url", "")))).isEqualTo(403);
        }
    }

    // =====================================================================
    // Registering and enabling are two acts
    // =====================================================================

    @Nested
    @DisplayName("Registering always produces a disabled provider")
    class Registration {

        @Test
        @DisplayName("enabled=true on the way in is ignored — 201, and disabled")
        void registeringAlwaysProducesADisabledProvider() throws Exception {
            JsonNode created = registered(ADMIN, bodyWith("\"enabled\":true"));

            assertThat(created.get("enabled").asBoolean())
                    .describedAs("register() forces enabled=false whatever the caller sends: an "
                            + "issuer typed wrong is a sign-in route that refuses everybody")
                    .isFalse();
        }

        @Test
        @DisplayName("a disabled provider is in the list and stays there")
        void aDisabledProviderIsListed() throws Exception {
            registered(ADMIN, body());

            JsonNode listed = json.readTree(list(ADMIN).getResponse().getContentAsString());

            // A list that hid them could not be used to enable one.
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).get("enabled").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("enabling is a second, deliberate act on the edit")
        void enablingIsAnEdit() throws Exception {
            UUID providerId = idOf(registered(ADMIN, body()));

            MvcResult result = update(providerId, bodyWith("\"enabled\":true"));

            assertThat(statusOf(result)).isEqualTo(200);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                            .get("enabled").asBoolean())
                    .isTrue();
        }
    }

    // =====================================================================
    // The client secret leaves by exactly one door, and comes back never
    // =====================================================================

    @Nested
    @DisplayName("The client secret is on no read path")
    class Secret {

        @Test
        @DisplayName("not in the registration response, not in the list, not on an edit")
        void theClientSecretIsOnNoReadPath() throws Exception {
            MvcResult created = register(ADMIN, body());
            UUID providerId = idOf(json.readTree(created.getResponse().getContentAsString()));

            String creation = created.getResponse().getContentAsString();
            String listed = list(ADMIN).getResponse().getContentAsString();
            String edited = update(providerId, body()).getResponse().getContentAsString();

            assertThat(creation).doesNotContain(SECRET);
            assertThat(listed).doesNotContain(SECRET);
            assertThat(edited).doesNotContain(SECRET);
            // On the raw body rather than a field: the failure being prevented is a nullable
            // `clientSecret` on the read type that one path fills — §217 FD-15's mutation.
            assertThat(creation).doesNotContain("\"clientSecret\"");
            assertThat(listed).doesNotContain("\"clientSecret\"");
            assertThat(edited).doesNotContain("\"clientSecret\"");
        }

        @Test
        @DisplayName("clientSecretSet is the boolean that replaces it")
        void clientSecretSetStandsInForIt() throws Exception {
            JsonNode withSecret = registered(ADMIN, body());
            assertThat(withSecret.get("clientSecretSet").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("nor in the audit row, which the client's own admins can read")
        void theAuditTrailNeverCarriesIt() throws Exception {
            registered(ADMIN, body());

            String changes = auditChanges(NORTH, "identity_provider.registered");

            assertThat(changes).doesNotContain(SECRET);
            assertThat(changes).doesNotContain("clientSecret");
        }

        @Test
        @DisplayName("omitting it on an edit keeps the stored one")
        void omittingTheSecretOnAnEditKeepsTheStoredOne() throws Exception {
            UUID providerId = idOf(registered(ADMIN, body()));

            MvcResult result = update(providerId, body("Renamed", "acme-sso", ISSUER, null));

            assertThat(statusOf(result)).isEqualTo(200);
            JsonNode edited = json.readTree(result.getResponse().getContentAsString());
            assertThat(edited.get("displayName").asText()).isEqualTo("Renamed");
            assertThat(edited.get("clientSecretSet").asBoolean())
                    .describedAs("no read path can echo the secret, so an edit form has nothing to "
                            + "resend — omission must be a keep, not a clear")
                    .isTrue();
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("a plain-HTTP issuer is refused — 400, on the issuerUri field")
        void aPlainHttpIssuerIsRefused() throws Exception {
            MvcResult result = register(
                    ADMIN, body("Keycloak", "keycloak", "http://keycloak.localtest.me:8081", SECRET));

            assertThat(statusOf(result))
                    .describedAs("the jwks_uri is read from this URL, so plain HTTP lets anyone on "
                            + "the path nominate the keys that decide who signs in — the decision "
                            + "recorded in the M4 plan's self-review, 2026-08-15")
                    .isEqualTo(400);
            assertThat(errorsOf(result).has("issuerUri")).isTrue();
        }

        @Test
        @DisplayName("key and kind are not read on an edit — an edit omitting them still saves")
        void keyAndKindAreImmutableAndUnreadOnEdit() throws Exception {
            UUID providerId = idOf(registered(ADMIN, body()));

            MvcResult result = update(providerId, "{"
                    + "\"displayName\":\"Renamed\","
                    + "\"issuerUri\":\"" + ISSUER + "\","
                    + "\"clientId\":\"tcms\""
                    + "}");

            assertThat(statusOf(result)).isEqualTo(200);
            JsonNode edited = json.readTree(result.getResponse().getContentAsString());
            assertThat(edited.get("key").asText())
                    .describedAs("set once at registration; the server ignores what an edit sends")
                    .isEqualTo("acme-sso");
        }

        @Test
        @DisplayName("a bad key is refused at registration")
        void aBadKeyIsRefused() throws Exception {
            MvcResult result = register(ADMIN, body("Acme", "Not A Key", ISSUER, SECRET));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("key")).isTrue();
        }
    }

    // =====================================================================
    // Tenancy
    // =====================================================================

    @Nested
    @DisplayName("Another company's provider is not visible and not editable")
    class Tenancy {

        @Test
        @DisplayName("it is absent from the list and its id 404s on an edit")
        void anotherCompanysProviderIsNotVisibleAndNotEditable() throws Exception {
            UUID theirs = idOf(registeredIn(SOUTH, OUTSIDER, body()));

            JsonNode ourList = json.readTree(list(ADMIN).getResponse().getContentAsString());
            assertThat(ourList).isEmpty();

            // 404 and not 403: under RLS the row is not there to be refused, which is the fail-closed
            // direction — the id itself must not become an oracle for another client's configuration.
            assertThat(statusOf(update(theirs, body()))).isEqualTo(404);
        }
    }

    // =====================================================================
    // Email domains
    // =====================================================================

    @Nested
    @DisplayName("Email domains are claimed wholesale and lower-cased")
    class EmailDomains {

        @Test
        @DisplayName("a PUT lower-cases and replaces the whole set")
        void emailDomainsAreLowercasedAndReplacedWholesale() throws Exception {
            MvcResult first = putDomains(ADMIN, "[\"ACME.test\",\"b.test\"]");
            assertThat(statusOf(first)).isEqualTo(200);
            assertThat(domainsOf(first)).containsExactly("acme.test", "b.test");

            MvcResult second = putDomains(ADMIN, "[\"b.test\"]");

            assertThat(domainsOf(second))
                    .describedAs("a replace, not a merge: the set is the unit an administrator "
                            + "thinks in and a claim has no lifecycle to preserve")
                    .containsExactly("b.test");
            assertThat(domainsOf(listDomains(ADMIN))).containsExactly("b.test");
        }

        @Test
        @DisplayName("an address where a domain belongs is refused — 400")
        void anAddressIsNotADomain() throws Exception {
            MvcResult result = putDomains(ADMIN, "[\"kim@acme.test\"]");

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("domains")).isTrue();
        }

        @Test
        @DisplayName("another company may claim the same domain — a claim is not ownership")
        void aDomainMayBeClaimedTwice() throws Exception {
            assertThat(statusOf(putDomains(ADMIN, "[\"shared.test\"]"))).isEqualTo(200);

            // Deliberately not unique across companies (§6r): a refusal here would tell the second
            // company that somebody else already claimed it.
            MvcResult theirs = mvc.perform(MockMvcRequestBuilders.put(
                            "/api/v1/identity-providers/email-domains")
                            .header("Authorization", "Bearer " + tokenFor(OUTSIDER, SOUTH))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"domains\":[\"shared.test\"]}"))
                    .andReturn();

            assertThat(statusOf(theirs)).isEqualTo(200);
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private static final String ISSUER = "https://issuer.acme.test/realms/acme";

    private MvcResult list(UUID callerId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/identity-providers")
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH)))
                .andReturn();
    }

    private MvcResult register(UUID callerId, String body) throws Exception {
        return registerIn(NORTH, callerId, body);
    }

    private MvcResult registerIn(UUID companyId, UUID callerId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/identity-providers")
                        .header("Authorization", "Bearer " + tokenFor(callerId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult update(UUID providerId, String body) throws Exception {
        return update(ADMIN, providerId, body);
    }

    private MvcResult update(UUID callerId, UUID providerId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/identity-providers/" + providerId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult listDomains(UUID callerId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/identity-providers/email-domains")
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH)))
                .andReturn();
    }

    private MvcResult putDomains(UUID callerId, String jsonArray) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/identity-providers/email-domains")
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"domains\":" + jsonArray + "}"))
                .andReturn();
    }

    /** Registers through the API and returns the parsed view — the fixture builder for the rest. */
    private JsonNode registered(UUID callerId, String body) throws Exception {
        MvcResult result = register(callerId, body);
        assertThat(statusOf(result))
                .as("registration failed: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode registeredIn(UUID companyId, UUID callerId, String body) throws Exception {
        MvcResult result = registerIn(companyId, callerId, body);
        assertThat(statusOf(result)).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static UUID idOf(JsonNode view) {
        return UUID.fromString(view.get("id").asText());
    }

    private java.util.List<String> domainsOf(MvcResult result) throws Exception {
        JsonNode domains = json.readTree(result.getResponse().getContentAsString()).get("domains");
        java.util.List<String> values = new java.util.ArrayList<>();
        domains.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static String body() {
        return body("Acme Corporate SSO", "acme-sso", ISSUER, SECRET);
    }

    private static String body(String displayName, String key, String issuerUri, String secret) {
        return "{"
                + "\"key\":" + quoted(key)
                + ",\"kind\":\"OIDC\""
                + ",\"displayName\":" + quoted(displayName)
                + ",\"issuerUri\":" + quoted(issuerUri)
                + ",\"clientId\":\"tcms\""
                + ",\"clientSecret\":" + quoted(secret)
                + "}";
    }

    /** The default body plus one extra member — for the fields only one test cares about. */
    private static String bodyWith(String extra) {
        String base = body();
        return base.substring(0, base.length() - 1) + "," + extra + "}";
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private JsonNode errorsOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("errors");
    }

    private String auditChanges(UUID companyId, String action) throws SQLException {
        try (var connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT changes::text FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no %s audit row was written", action).isTrue();
                return rows.getString(1);
            }
        }
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
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

    private void grantOnCompany(UUID id, UUID companyId, UUID roleId, UUID userId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private void grantCompanyReadBaseline(UUID companyId) {
        entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
    }
}
