package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
 * {@code /api-tokens} — <strong>the credential M3 runs on, and {@code token:issue}'s first
 * consumer.</strong>
 *
 * <p>{@code api_token} has been in the schema since {@code V1} with no writer, {@code V3} seeded
 * the permission and granted it, and {@code V16} repaired Automation Service so it would not 404 on
 * every project-scoped path. Nothing could issue a credential, so four things this class asserts had
 * never been observable in the product:
 *
 * <ol>
 *   <li><strong>{@code token:issue} decides, and it is not "administers something".</strong> A Test
 *       Lead runs a project and holds {@code project:members}; every "does this caller look
 *       powerful" heuristic says yes. Their 403 is the test that kills the gate a hurried
 *       implementation writes.</li>
 *   <li><strong>The secret exists exactly once.</strong> Asserted in three directions — it is in
 *       the creation response, it is <em>not</em> in the list, and it is not in the audit row, whose
 *       own contract forbids a credential in {@code changes} because the client's admins read it.
 *       A status-only assertion would pass against an implementation that stored the plaintext.</li>
 *   <li><strong>The digest is stored where the pre-authentication lookup can find it.</strong> The
 *       row lands in {@code api_token_lookup}, which carries no RLS — this is {@code V7}'s defect
 *       with the fix in place, and the reason the row is not on the policed table.</li>
 *   <li><strong>{@code V36}'s uniqueness is partial.</strong> A live name is taken and a revoked
 *       one is free, which is what makes rotation possible without renaming what is written into
 *       somebody's CI configuration.</li>
 * </ol>
 *
 * <p>Ids use the {@code 9d…} prefix, which no other class in the suite uses — checked rather than
 * assumed, because handoff §19's fixture collision has now happened five times and the prefix space
 * is hand-managed with no check behind it.
 */
@AutoConfigureMockMvc
@DisplayName("API tokens — the gate nobody had asked, and the secret that exists exactly once")
class ApiTokenEndpointTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("9d000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("9d000000-0000-4000-8000-00000000000b");

    private static final UUID ATLAS = UUID.fromString("9d001000-0000-4000-8000-000000000001");
    private static final UUID SOUTH_PROJECT =
            UUID.fromString("9d001000-0000-4000-8000-000000000002");

    /** Company Admin in NORTH — holds {@code token:issue}. */
    private static final UUID ADMIN = UUID.fromString("9d003000-0000-4000-8000-000000000001");
    /** Test Lead on ATLAS. Administers a project and holds no {@code token:issue}. */
    private static final UUID LEAD = UUID.fromString("9d003000-0000-4000-8000-000000000002");
    /** An ACTIVE member of NORTH holding nothing. */
    private static final UUID MEMBER = UUID.fromString("9d003000-0000-4000-8000-000000000003");
    /** Company Admin in SOUTH — the cross-company caller. */
    private static final UUID OUTSIDER = UUID.fromString("9d003000-0000-4000-8000-000000000006");

    private static final UUID GRANT_ADMIN = UUID.fromString("9d005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_LEAD = UUID.fromString("9d005000-0000-4000-8000-000000000002");
    private static final UUID GRANT_OUTSIDER =
            UUID.fromString("9d005000-0000-4000-8000-000000000003");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private com.tcms.auth.SessionTokens tokens;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private ApiTokenLookupRepository lookups;

    @Autowired
    private ApiTokenSecrets secrets;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            provisionCompany(NORTH, "north-token", "Northwind", now);
            provisionCompany(SOUTH, "south-token", "Southwind", now);

            activeUser(ADMIN, "admin@north-token.test", now);
            activeUser(LEAD, "lead@north-token.test", now);
            activeUser(MEMBER, "member@north-token.test", now);
            activeUser(OUTSIDER, "outsider@south-token.test", now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, NORTH, "ATK", "Atlas", now);

            member(NORTH, ADMIN, "A. Admin", now);
            member(NORTH, LEAD, "L. Lead", now);
            member(NORTH, MEMBER, "M. Member", now);

            grantOnCompany(GRANT_ADMIN, NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject(GRANT_LEAD, NORTH, Role.TEST_LEAD_ID, LEAD, ATLAS, now);
            grantCompanyReadBaseline(NORTH);
        }));

        inCompany(SOUTH, () -> tx.executeWithoutResult(status -> {
            seedProject(SOUTH_PROJECT, SOUTH, "STK", "Southwind Portal", now);
            member(SOUTH, OUTSIDER, "O. Outsider", now);
            grantOnCompany(GRANT_OUTSIDER, SOUTH, Role.COMPANY_ADMIN_ID, OUTSIDER, now);
            grantCompanyReadBaseline(SOUTH);
        }));

        resetMutableRows();
    }

    /**
     * Removes every token and audit row.
     *
     * <p>This class creates the rows it reads its assertions from, and a name-uniqueness test that
     * inherits a previous test's token passes or fails on JUnit's method order — §19's defect,
     * cheaper to prevent than to diagnose. Through each company's own app-role connection, so RLS
     * is in force while the cleanup runs; {@code api_token_lookup} goes with the token by
     * {@code V36}'s {@code ON DELETE CASCADE}, which is itself worth exercising every run.
     */
    private void resetMutableRows() throws SQLException {
        reset(NORTH);
        reset(SOUTH);
    }

    private void reset(UUID companyId) throws SQLException {
        try (var connection = appConnection(companyId)) {
            execute(connection, "DELETE FROM audit_event");
            execute(connection, "DELETE FROM api_token");
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gate — token:issue, and nothing that merely resembles it
    // =====================================================================

    @Nested
    @DisplayName("token:issue decides, and administering a project is not it")
    class Gate {

        @Test
        @DisplayName("a Company Admin issues a token — 201, with Location")
        void aCompanyAdminMayIssue() throws Exception {
            MvcResult result = issue(ADMIN, body("CI", Role.AUTOMATION_SERVICE_ID, null, null));

            assertThat(statusOf(result)).isEqualTo(201);
            JsonNode created = json.readTree(result.getResponse().getContentAsString());
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/api-tokens/" + created.get("token").get("id").asText());
            assertThat(created.get("token").get("name").asText()).isEqualTo("CI");
            assertThat(created.get("token").get("roleName").asText())
                    .isEqualTo("Automation Service");
        }

        @Test
        @DisplayName("a Test Lead may NOT — leading a project is not issuing credentials")
        void aLeadMayNotIssue() throws Exception {
            // The mutation this kills: gating on AdminReach.companyWide(), on manageTeams(), or on
            // any "this caller administers something" reading. LEAD passes the project-shaped ones
            // and holds no token:issue.
            assertThat(statusOf(issue(LEAD, body("CI", Role.AUTOMATION_SERVICE_ID, null, null))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("a plain member may not")
        void aPlainMemberMayNotIssue() throws Exception {
            assertThat(statusOf(issue(MEMBER, body("CI", Role.AUTOMATION_SERVICE_ID, null, null))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("the refusal comes before the payload is judged — an invalid body still 403s")
        void theGateRunsFirst() throws Exception {
            // Otherwise the endpoint reports whether a token name is taken in this company to a
            // caller who may not use one — RoleGrantService's directory-probe argument.
            assertThat(statusOf(issue(MEMBER, body("", Role.AUTOMATION_SERVICE_ID, null, null))))
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("the LIST is refused, not emptied — an empty array would be a false claim")
        void theListIsRefusedRatherThanFiltered() throws Exception {
            // GET /role-assignments filters down to what the caller may see. A credential has no
            // partial-visibility story, so 200-with-[] would tell a Test Lead this company has no
            // tokens. Deliberately the opposite choice from GET /teams, and stated in the contract.
            assertThat(statusOf(list(LEAD))).isEqualTo(403);
            assertThat(statusOf(list(MEMBER))).isEqualTo(403);
            assertThat(statusOf(list(ADMIN))).isEqualTo(200);
        }
    }

    // =====================================================================
    // The secret
    // =====================================================================

    @Nested
    @DisplayName("The secret exists exactly once")
    class Secret {

        @Test
        @DisplayName("it is in the creation response, prefixed, and long enough to be a secret")
        void theSecretIsReturnedOnce() throws Exception {
            JsonNode created = issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null);

            String secret = created.get("secret").asText();
            assertThat(secret).startsWith(ApiTokenSecrets.PREFIX);
            // 32 bytes base64url-encode to 43 characters, plus the marker. A shorter secret means
            // somebody reduced the entropy, which is the one property nothing else would notice.
            assertThat(secret).hasSize(ApiTokenSecrets.PREFIX.length() + 43);
            assertThat(created.get("token").get("tokenPrefix").asText())
                    .isEqualTo(secret.substring(0, ApiToken.PREFIX_LENGTH));
        }

        @Test
        @DisplayName("and it is NOT in the list — only the prefix survives")
        void theListNeverCarriesIt() throws Exception {
            JsonNode created = issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null);
            String secret = created.get("secret").asText();

            String listed = list(ADMIN).getResponse().getContentAsString();

            assertThat(listed).doesNotContain(secret);
            // Asserted on the raw body rather than on a field, because the failure being prevented
            // is a field nobody expected: a nullable `secret` on the read type that one path fills.
            assertThat(listed).doesNotContain("\"secret\"");
            assertThat(json.readTree(listed).get(0).get("tokenPrefix").asText())
                    .isEqualTo(secret.substring(0, ApiToken.PREFIX_LENGTH));
        }

        @Test
        @DisplayName("nor in the audit row, which the client's own admins can read")
        void theAuditTrailNeverCarriesIt() throws Exception {
            JsonNode created = issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null);
            String secret = created.get("secret").asText();

            String changes = auditChanges(NORTH, "api_token.issued");

            assertThat(changes).doesNotContain(secret);
            // The prefix IS there, and that is the point of having one: without it the event names
            // a uuid and a reader cannot match it to the row in front of them.
            assertThat(changes).contains(secret.substring(0, ApiToken.PREFIX_LENGTH));
        }

        @Test
        @DisplayName("its digest lands in api_token_lookup — the table with no RLS on it")
        void theDigestIsWhereThePreAuthLookupCanFindIt() throws Exception {
            JsonNode created = issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null);
            UUID tokenId = UUID.fromString(created.get("token").get("id").asText());

            // Read with NO company context open — which is the whole reason this table exists, and
            // exactly the arrangement V7 shipped broken: under RLS this returns nothing, the
            // instance answers 401 to a valid token for ever, and no test that opens a context
            // first can see it.
            ApiTokenLookup lookup = tx.execute(status ->
                    lookups.findByTokenHash(secrets.hash(created.get("secret").asText()))
                            .orElseThrow());

            assertThat(lookup.getTokenId()).isEqualTo(tokenId);
            assertThat(lookup.getCompanyId()).isEqualTo(NORTH);
        }

        @Test
        @DisplayName("two tokens get two different secrets")
        void secretsAreNotReused() throws Exception {
            String first = issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null)
                    .get("secret").asText();
            String second = issued(ADMIN, "Nightly", Role.AUTOMATION_SERVICE_ID, null, null)
                    .get("secret").asText();

            assertThat(first).isNotEqualTo(second);
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("What a token may say it is")
    class Validation {

        @Test
        @DisplayName("a blank name is a field error")
        void aBlankNameIsRefused() throws Exception {
            MvcResult result = issue(ADMIN, body("   ", Role.AUTOMATION_SERVICE_ID, null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("name")).isTrue();
        }

        @Test
        @DisplayName("a second LIVE token cannot take the name — the pair is untellable apart")
        void aDuplicateLiveNameIsRefused() throws Exception {
            issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null);

            MvcResult result = issue(ADMIN, body("CI", Role.AUTOMATION_SERVICE_ID, null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("name")).isTrue();
        }

        @Test
        @DisplayName("but a REVOKED token's name is free again — V36's index is partial for this")
        void aRevokedNameIsReusable() throws Exception {
            UUID first = idOf(issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null));
            assertThat(statusOf(revoke(ADMIN, first))).isEqualTo(204);

            // Rotation is issue-then-revoke or revoke-then-issue, and the name is written into
            // somebody's CI configuration. A plain unique index makes this a duplicate-key 500
            // naming a dead row — §93's argument on doc_page, one table over.
            assertThat(statusOf(issue(ADMIN, body("CI", Role.AUTOMATION_SERVICE_ID, null, null))))
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a SYSTEM-scope role is refused — those grants are not a token's to carry")
        void aSystemRoleIsRefused() throws Exception {
            MvcResult result = issue(ADMIN, body("CI", Role.INSTANCE_ADMIN_ID, null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("roleId")).isTrue();
        }

        @Test
        @DisplayName("so is the membership baseline, which is a consequence rather than a choice")
        void theCompanyMemberBaselineIsRefused() throws Exception {
            MvcResult result = issue(ADMIN, body("CI", Role.COMPANY_MEMBER_ID, null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("roleId")).isTrue();
        }

        @Test
        @DisplayName("Agent is NOT refused — non-assignable and issuable are different questions")
        void theAgentCeilingIsIssuable() throws Exception {
            // The asymmetry V40 turns on, asserted from the endpoint because it is invisible in the
            // data: Agent and Company Member are both `assignable = false`, and exactly one of them
            // may be carried by a token. If this check is ever "cleaned up" to read the flag —
            // which is the tidy-looking edit, one line, in a method that already has two role
            // checks — the role that exists ONLY to be carried by a token becomes unissuable, and
            // nothing else in the suite changes colour.
            MvcResult result = issue(ADMIN, body("Agent", Role.AGENT_ID, null,
                    Instant.now().plus(30, ChronoUnit.DAYS)));

            assertThat(statusOf(result)).isEqualTo(201);
        }

        @Test
        @DisplayName("an Agent token with no expiry is refused — the one role that must have one")
        void anAgentTokenMustExpire() throws Exception {
            // The inversion, asserted in both directions in one class: the test above issues an
            // Agent token WITH an expiry and gets a 201, and an Automation Service token with none
            // is a 201 two tests up. The rule is per-role and lives in the service, not the form —
            // the bearer works with curl, so a client-side rule is not a rule.
            MvcResult result = issue(ADMIN, body("Agent", Role.AGENT_ID, null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("expiresAt")).isTrue();
        }

        @Test
        @DisplayName("an unknown role is a field error, not a 500")
        void anUnknownRoleIsRefused() throws Exception {
            MvcResult result = issue(ADMIN, body("CI", UUID.randomUUID(), null, null));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("roleId")).isTrue();
        }

        @Test
        @DisplayName("an expiry in the past is refused — it would be dead on arrival")
        void aPastExpiryIsRefused() throws Exception {
            MvcResult result = issue(ADMIN, body("CI", Role.AUTOMATION_SERVICE_ID, null,
                    Instant.now().minus(1, ChronoUnit.DAYS)));

            assertThat(statusOf(result)).isEqualTo(400);
            assertThat(errorsOf(result).has("expiresAt")).isTrue();
        }

        @Test
        @DisplayName("no expiry at all is allowed — an outage is worse than a live credential")
        void anAbsentExpiryIsAllowed() throws Exception {
            JsonNode created = issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null);

            assertThat(created.get("token").get("expiresAt").isNull()).isTrue();
        }

        @Test
        @DisplayName("a sibling company's project is 404, not 403 — the status is not an oracle")
        void aForeignProjectIs404() throws Exception {
            MvcResult result = issue(ADMIN,
                    body("CI", Role.AUTOMATION_SERVICE_ID, SOUTH_PROJECT, null));

            assertThat(statusOf(result)).isEqualTo(404);
        }

        @Test
        @DisplayName("this company's project is accepted and comes back on the row")
        void aVisibleProjectIsAccepted() throws Exception {
            JsonNode created = issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, ATLAS, null);

            assertThat(UUID.fromString(created.get("token").get("projectId").asText()))
                    .isEqualTo(ATLAS);
        }
    }

    // =====================================================================
    // Revocation
    // =====================================================================

    @Nested
    @DisplayName("Revoking")
    class Revoking {

        @Test
        @DisplayName("a revoked token stays in the list, carrying when it stopped working")
        void aRevokedTokenIsEvidence() throws Exception {
            UUID id = idOf(issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null));

            assertThat(statusOf(revoke(ADMIN, id))).isEqualTo(204);

            JsonNode listed = json.readTree(list(ADMIN).getResponse().getContentAsString());
            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).get("revokedAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("re-revoking is 204 and does NOT move the timestamp")
        void revocationIsIdempotentAndTheFirstOneCounts() throws Exception {
            UUID id = idOf(issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null));
            revoke(ADMIN, id);
            String first = revokedAtOf(id);

            assertThat(statusOf(revoke(ADMIN, id))).isEqualTo(204);

            // The trail must report when the credential stopped working, not when somebody
            // clicked a second time. A blind `revoke(now)` passes the status assertion and fails
            // this one.
            assertThat(revokedAtOf(id)).isEqualTo(first);
        }

        @Test
        @DisplayName("an unknown token is 404")
        void anUnknownTokenIs404() throws Exception {
            assertThat(statusOf(revoke(ADMIN, UUID.randomUUID()))).isEqualTo(404);
        }

        @Test
        @DisplayName("a Test Lead may not revoke")
        void revokingIsGatedToo() throws Exception {
            UUID id = idOf(issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null));

            assertThat(statusOf(revoke(LEAD, id))).isEqualTo(403);
        }
    }

    // =====================================================================
    // The boundary
    // =====================================================================

    @Nested
    @DisplayName("A token belongs to one company")
    class Isolation {

        @Test
        @DisplayName("a sibling company's admin sees none of these tokens")
        void tokensDoNotCrossTheBoundary() throws Exception {
            issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null);

            MvcResult result = mvc.perform(MockMvcRequestBuilders.get("/api/v1/api-tokens")
                            .header("Authorization", "Bearer " + tokenFor(OUTSIDER, SOUTH)))
                    .andReturn();

            assertThat(statusOf(result)).isEqualTo(200);
            assertThat(json.readTree(result.getResponse().getContentAsString())).isEmpty();
        }

        @Test
        @DisplayName("and cannot revoke one — 404, indistinguishable from no such token")
        void aSiblingCompanyCannotRevoke() throws Exception {
            UUID id = idOf(issued(ADMIN, "CI", Role.AUTOMATION_SERVICE_ID, null, null));

            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .delete("/api/v1/api-tokens/" + id)
                            .header("Authorization", "Bearer " + tokenFor(OUTSIDER, SOUTH)))
                    .andReturn();

            assertThat(statusOf(result)).isEqualTo(404);
            assertThat(revokedAtOf(id)).isNull();
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private MvcResult issue(UUID callerId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/api-tokens")
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult list(UUID callerId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/api-tokens")
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH)))
                .andReturn();
    }

    private MvcResult revoke(UUID callerId, UUID tokenId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1/api-tokens/" + tokenId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, NORTH)))
                .andReturn();
    }

    /** Issues through the API and returns the parsed body — the fixture builder for everything else. */
    private JsonNode issued(UUID callerId, String name, UUID roleId, UUID projectId, Instant expiry)
            throws Exception {

        MvcResult result = issue(callerId, body(name, roleId, projectId, expiry));
        assertThat(statusOf(result)).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static UUID idOf(JsonNode issued) {
        return UUID.fromString(issued.get("token").get("id").asText());
    }

    private static String body(String name, UUID roleId, UUID projectId, Instant expiresAt) {
        return "{\"name\":" + quoted(name)
                + ",\"roleId\":" + (roleId == null ? "null" : "\"" + roleId + "\"")
                + ",\"projectId\":" + (projectId == null ? "null" : "\"" + projectId + "\"")
                + ",\"expiresAt\":" + (expiresAt == null ? "null" : "\"" + expiresAt + "\"")
                + "}";
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

    /** {@code revoked_at} straight from the row, so the assertion does not go through the view. */
    private String revokedAtOf(UUID tokenId) throws SQLException {
        try (var connection = appConnection(NORTH);
                var statement = connection.prepareStatement(
                        "SELECT revoked_at FROM api_token WHERE id = ?::uuid")) {
            statement.setString(1, tokenId.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
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

    private void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
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

    private void grantOnProject(
            UUID id, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void grantCompanyReadBaseline(UUID companyId) {
        entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
    }
}
