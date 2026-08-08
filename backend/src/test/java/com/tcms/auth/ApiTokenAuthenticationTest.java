package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.ApiToken;
import com.tcms.identity.ApiTokenLookup;
import com.tcms.identity.ApiTokenLookupRepository;
import com.tcms.identity.ApiTokenRepository;
import com.tcms.identity.ApiTokenSecrets;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A CI job authenticating — <strong>the second way into this API, and the first that is not a
 * person.</strong>
 *
 * <p>Three things here could not be asserted anywhere else, and each one is a defect class that
 * fails silently:
 *
 * <ol>
 *   <li><strong>The credential resolves with NO company context open.</strong> {@code api_token} is
 *       under {@code FORCE ROW LEVEL SECURITY}, so a lookup on it before authentication returns
 *       zero rows — {@code V7}'s defect, which was invisible to a suite migrating as a superuser
 *       and answered every valid credential with a 401. {@code V36}'s instance-level
 *       {@code api_token_lookup} is what makes it work, and the only way to observe that is over
 *       HTTP, from outside.</li>
 *   <li><strong>The token is capped by its ROLE and by its OWNER, and the second is the
 *       escalation.</strong> A token whose owner is a Test Engineer and whose role says Company
 *       Admin must hold the Test Engineer's set. This class seeds exactly that row directly,
 *       because the issue endpoint cannot produce it — the owner is always the caller — and the
 *       evaluator must not depend on that being true.</li>
 *   <li><strong>Revocation and expiry are enforced on the POLICED row.</strong> The lookup row is
 *       deliberately left in place on revoke, so a revoked credential that still worked would be a
 *       401 nobody gets and a 200 everybody does.</li>
 * </ol>
 *
 * <p>Ids use the {@code 9e…} prefix, which no other class in the suite uses.
 */
@AutoConfigureMockMvc
@DisplayName("API-token authentication — resolving before there is a company, and the cap")
class ApiTokenAuthenticationTest extends IntegrationTestBase {

    private static final UUID NORTH = UUID.fromString("9e000000-0000-4000-8000-00000000000a");
    private static final UUID SOUTH = UUID.fromString("9e000000-0000-4000-8000-00000000000b");

    private static final UUID ATLAS = UUID.fromString("9e001000-0000-4000-8000-000000000001");
    private static final UUID BOREAS = UUID.fromString("9e001000-0000-4000-8000-000000000002");
    private static final UUID SOUTH_PROJECT =
            UUID.fromString("9e001000-0000-4000-8000-000000000003");

    /** Company Admin in NORTH — the ordinary issuer. */
    private static final UUID ADMIN = UUID.fromString("9e003000-0000-4000-8000-000000000001");
    /** Test Engineer on ATLAS — the weak owner in the escalation fixture. */
    private static final UUID ENGINEER = UUID.fromString("9e003000-0000-4000-8000-000000000002");
    private static final UUID SOUTH_ADMIN = UUID.fromString("9e003000-0000-4000-8000-000000000003");

    private static final UUID GRANT_ADMIN = UUID.fromString("9e005000-0000-4000-8000-000000000001");
    private static final UUID GRANT_ENGINEER =
            UUID.fromString("9e005000-0000-4000-8000-000000000002");
    private static final UUID GRANT_SOUTH = UUID.fromString("9e005000-0000-4000-8000-000000000003");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionTokens sessions;

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
    private ApiTokenRepository tokens;

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
            provisionCompany(NORTH, "north-auth", "Northwind", now);
            provisionCompany(SOUTH, "south-auth", "Southwind", now);

            activeUser(ADMIN, "admin@north-auth.test", now);
            activeUser(ENGINEER, "engineer@north-auth.test", now);
            activeUser(SOUTH_ADMIN, "admin@south-auth.test", now);
        });

        inCompany(NORTH, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, NORTH, "ATA", "Atlas", now);
            seedProject(BOREAS, NORTH, "BOR", "Boreas", now);

            member(NORTH, ADMIN, "A. Admin", now);
            member(NORTH, ENGINEER, "E. Engineer", now);

            grantOnCompany(GRANT_ADMIN, NORTH, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject(GRANT_ENGINEER, NORTH, Role.TEST_ENGINEER_ID, ENGINEER, ATLAS, now);
            grantCompanyReadBaseline(NORTH);
        }));

        inCompany(SOUTH, () -> tx.executeWithoutResult(status -> {
            seedProject(SOUTH_PROJECT, SOUTH, "STA", "Southwind Portal", now);
            member(SOUTH, SOUTH_ADMIN, "S. Admin", now);
            grantOnCompany(GRANT_SOUTH, SOUTH, Role.COMPANY_ADMIN_ID, SOUTH_ADMIN, now);
            grantCompanyReadBaseline(SOUTH);
        }));

        reactivate(ADMIN);
        reset(NORTH);
        reset(SOUTH);
    }

    private void reset(UUID companyId) throws SQLException {
        try (var connection = appConnection(companyId)) {
            try (var statement = connection.prepareStatement("DELETE FROM api_token")) {
                statement.executeUpdate();
            }
        }
    }

    /** Undoes {@link Deactivation}'s mutation, which outlives its own test. */
    private void reactivate(UUID userId) {
        tx.executeWithoutResult(status -> users.findById(userId).ifPresent(user -> {
            if (!user.isActive()) {
                user.activate("hash", Instant.now());
                users.save(user);
            }
        }));
    }

    // =====================================================================
    // Resolving before there is a company
    // =====================================================================

    @Nested
    @DisplayName("The credential resolves with no company context open")
    class Resolving {

        @Test
        @DisplayName("a live token reaches a project-scoped read — the whole point of V36")
        void aLiveTokenAuthenticates() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);

            // If api_token still carried the digest, this is a 401 in production and a green test
            // on any harness that opens a context first. It is the reason the assertion is made
            // over HTTP.
            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret))).isEqualTo(200);
        }

        @Test
        @DisplayName("the company comes from the token, so the sibling company's project is 404")
        void theCompanyIsTheTokensOwn() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, null, null);

            assertThat(statusOf(get("/api/v1/projects/" + SOUTH_PROJECT + "/cases", secret)))
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a string that is shaped like ours but is not one is 401, not a 500")
        void anUnknownSecretIs401() throws Exception {
            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases",
                    ApiTokenSecrets.PREFIX + "notarealtokenatall")))
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("a session token still works — the resolver skips ours and only ours")
        void sessionsAreUntouched() throws Exception {
            // The regression this kills: a BearerTokenResolver that returns null too eagerly, or a
            // filter that claims every Authorization header. Every other test in the suite would
            // fail with it, which is exactly why it is asserted HERE, next to the change.
            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", sessionFor(ADMIN))))
                    .isEqualTo(200);
        }
    }

    // =====================================================================
    // The cap
    // =====================================================================

    @Nested
    @DisplayName("A token is capped by its role AND by its owner")
    class Cap {

        @Test
        @DisplayName("a Company Admin's token carrying Automation Service is NOT a Company Admin")
        void theRoleCapsTheOwner() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);

            // ADMIN's own session lists the company's tokens. Their CI credential must not, and it
            // is the same person: the difference is the role the token carries.
            assertThat(statusOf(get("/api/v1/api-tokens", sessionFor(ADMIN)))).isEqualTo(200);
            assertThat(statusOf(get("/api/v1/api-tokens", secret))).isEqualTo(403);
        }

        @Test
        @DisplayName("and its permissions on the project are the role's, not the owner's")
        void theEffectiveSetIsTheIntersection() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);

            List<String> held = permissionsOn(secret, ATLAS);

            // Roles spec §A6, the exact grant. Asserted as a SET rather than by spot-checking one
            // key, because the failure being prevented is a cap that leaks a key nobody thought to
            // look for.
            assertThat(held).containsExactlyInAnyOrder(
                    "project:read", "case:read", "run:create", "result:submit",
                    "evidence:attach", "case:create");
            assertThat(permissionsOn(sessionFor(ADMIN), ATLAS)).contains("case:delete");
        }

        @Test
        @DisplayName("a WEAK owner caps a STRONG role — the escalation the endpoint cannot produce")
        void theOwnerCapsTheRole() throws Exception {
            // Seeded directly: ApiTokenService always sets owner = caller, so this row cannot be
            // created through the API today. The evaluator must not depend on that — a delegation
            // field, or a second issue path, would make it reachable, and the cap has to hold
            // because it is an intersection rather than because nothing produces the input.
            String secret = seedTokenDirectly(NORTH, ENGINEER, Role.COMPANY_ADMIN_ID, null);

            List<String> held = permissionsOn(secret, ATLAS);

            // Company Admin's keys, absent because the OWNER does not hold them.
            assertThat(held).doesNotContain("company:members", "token:issue", "project:create");
            // And what a Test Engineer does hold on their own project is still there, so the cap
            // narrowed rather than emptied — an intersection that returned nothing would pass the
            // assertion above for the wrong reason.
            assertThat(held).contains("case:read", "case:create");
        }

        @Test
        @DisplayName("a project-scoped token is inert on every other project in the same company")
        void theProjectBoundIsHard() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);

            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret))).isEqualTo(200);
            // Same company, same owner, same role. Only the project differs, and project:read
            // resolves to nothing — so it is the same 404 a project in another company gives.
            assertThat(statusOf(get("/api/v1/projects/" + BOREAS + "/cases", secret)))
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an unscoped token reaches every project the owner can")
        void anUnscopedTokenIsNotScoped() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, null, null);

            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret))).isEqualTo(200);
            assertThat(statusOf(get("/api/v1/projects/" + BOREAS + "/cases", secret)))
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("the company-wide answer administers nothing, though it is not empty")
        void theCompanyWideAnswerAdministersNothing() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, null, null);

            List<String> held = permissionsOn(secret, null);

            // Written this way after the first draft asserted `isEmpty()` and was wrong. Company
            // Admin is a COMPANY-scope role holding the CONTENT keys company-wide, so intersecting
            // it with Automation Service leaves those keys standing at company scope — correctly:
            // an unscoped token may read cases in any project its owner can. What must be gone is
            // the administration, and that is what this asserts.
            assertThat(held).doesNotContain(
                    "company:members", "company:teams", "company:settings",
                    "token:issue", "project:create", "audit:view");
            assertThat(permissionsOn(sessionFor(ADMIN), null)).contains("token:issue");
        }
    }

    // =====================================================================
    // Dying
    // =====================================================================

    @Nested
    @DisplayName("Revocation and expiry, enforced on the policed row")
    class Lifecycle {

        @Test
        @DisplayName("a revoked token is 401 on the very next request")
        void revocationIsImmediate() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);
            UUID tokenId = tokenIdFor(secret);

            assertThat(statusOf(revoke(tokenId))).isEqualTo(204);

            // The lookup row is deliberately still there — ApiTokenLookupRepository's header says
            // why one enforcement point beats two — so this passes only if isUsable() is asked.
            assertThat(lookupExists(secret)).isTrue();
            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret)))
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("an expired token is 401, and expiry is checked in the same place")
        void expiryIsEnforced() throws Exception {
            // Seeded past-dated: the endpoint refuses a past expiry, which is the correct refusal
            // and would make this state unreachable through the API. The state is still reachable
            // by time passing, which is the only way it ever happens in production.
            String secret = seedTokenDirectly(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID,
                    Instant.now().minus(1, ChronoUnit.HOURS));

            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret)))
                    .isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("last_used_at — coarse on purpose, and only for a credential that worked")
    class LastUsed {

        @Test
        @DisplayName("a fresh token is null until it is used, then carries a time")
        void theFirstUseRecordsATime() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);
            UUID tokenId = tokenIdFor(secret);

            // Null is not "unknown": it is the state a revocation candidate is in, and it is what
            // every row on every deployment read before this had a writer.
            assertThat(lastUsedOf(tokenId)).isNull();

            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret))).isEqualTo(200);

            assertThat(lastUsedOf(tokenId)).isNotNull();
        }

        @Test
        @DisplayName("a second request inside the resolution window does NOT write again")
        void theSecondUseIsFree() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);
            UUID tokenId = tokenIdFor(secret);

            get("/api/v1/projects/" + ATLAS + "/cases", secret);
            Instant first = lastUsedOf(tokenId);
            get("/api/v1/projects/" + ATLAS + "/cases", secret);

            // The whole point of the column being coarse. Without this the writer is one UPDATE
            // per authenticated request, every request, all contending on one row — and a busy CI
            // token is precisely the row that would carry it. The default resolution is an hour,
            // so the two requests above are inside one window by construction.
            assertThat(lastUsedOf(tokenId))
                    .as("a second request within the resolution window must not touch the row")
                    .isEqualTo(first);
        }

        @Test
        @DisplayName("a REFUSED credential does not move it — last used, not last attempted")
        void arefusedRequestRecordsNothing() throws Exception {
            String secret = seedTokenDirectly(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID,
                    Instant.now().minus(1, ChronoUnit.HOURS));
            UUID tokenId = tokenIdFor(secret);

            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret)))
                    .isEqualTo(401);

            // An expired or revoked credential still being presented is an access log's question.
            // Answering it here would make "last used" mean "last seen", and an administrator
            // deciding what to revoke would read a live date on a credential nothing accepts.
            assertThat(lastUsedOf(tokenId)).isNull();
        }
    }

    @Nested
    @DisplayName("The owner's account is still the off switch")
    class Deactivation {

        @Test
        @DisplayName("deactivating the owner kills the tokens they issued")
        void deactivatingTheOwnerKillsTheToken() throws Exception {
            String secret = issueToken(NORTH, ADMIN, Role.AUTOMATION_SERVICE_ID, ATLAS, null);
            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret))).isEqualTo(200);

            tx.executeWithoutResult(status -> {
                AppUser owner = users.findById(ADMIN).orElseThrow();
                owner.deactivate(Instant.now());
                users.save(owner);
            });

            // PermissionVersionFilter skips the epoch for a token and keeps the account gate, and
            // this is the assertion that keeps that half honest: without it, deactivating a person
            // would leave every credential they ever issued working.
            assertThat(statusOf(get("/api/v1/projects/" + ATLAS + "/cases", secret)))
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private MvcResult get(String path, String bearer) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + bearer))
                .andReturn();
    }

    private MvcResult revoke(UUID tokenId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1/api-tokens/" + tokenId)
                        .header("Authorization", "Bearer " + sessionFor(ADMIN)))
                .andReturn();
    }

    /** Issues through the API — the path a real operator takes — and returns the secret. */
    private String issueToken(UUID companyId, UUID caller, UUID roleId, UUID projectId,
            Instant expiresAt) throws Exception {

        String body = "{\"name\":\"CI " + UUID.randomUUID() + "\""
                + ",\"roleId\":\"" + roleId + "\""
                + ",\"projectId\":" + (projectId == null ? "null" : "\"" + projectId + "\"")
                + ",\"expiresAt\":" + (expiresAt == null ? "null" : "\"" + expiresAt + "\"")
                + "}";

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/api-tokens")
                        .header("Authorization", "Bearer " + sessionFor(caller, companyId))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(statusOf(result)).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("secret").asText();
    }

    /**
     * Writes the two rows straight through the repositories — for the states the endpoint
     * deliberately cannot produce: an owner who is not the caller, and an expiry in the past.
     */
    private String seedTokenDirectly(UUID companyId, UUID ownerId, UUID roleId, Instant expiresAt) {
        ApiTokenSecrets.NewSecret secret = secrets.generate();
        UUID id = UUID.randomUUID();
        inCompany(companyId, () -> tx.executeWithoutResult(status -> {
            tokens.save(ApiToken.issue(id, companyId, "seeded " + id, secret.prefix(),
                    ownerId, roleId, null, expiresAt, Instant.now()));
            lookups.save(ApiTokenLookup.of(secret.hash(), id, companyId));
        }));
        return secret.secret();
    }

    /**
     * The scope wraps the transaction, not the other way round — this class's own header explains
     * why, and a {@code tx.execute} that opened the company inside itself would read zero rows and
     * fail as "no such token".
     */
    private Instant lastUsedOf(UUID tokenId) {
        AtomicReference<Instant> value = new AtomicReference<>();
        inCompany(NORTH, () -> tx.executeWithoutResult(status ->
                value.set(tokens.findScopedById(tokenId).orElseThrow().getLastUsedAt())));
        return value.get();
    }

    private UUID tokenIdFor(String secret) {
        return tx.execute(status ->
                lookups.findByTokenHash(secrets.hash(secret)).orElseThrow().getTokenId());
    }

    private boolean lookupExists(String secret) {
        return Boolean.TRUE.equals(tx.execute(status ->
                lookups.findByTokenHash(secrets.hash(secret)).isPresent()));
    }

    /** The keys {@code /me/permissions} reports, for whatever credential is presented. */
    private List<String> permissionsOn(String bearer, UUID projectId) throws Exception {
        String path = "/api/v1/me/permissions"
                + (projectId == null ? "" : "?projectId=" + projectId);
        MvcResult result = get(path, bearer);
        assertThat(statusOf(result)).isEqualTo(200);

        List<String> keys = new ArrayList<>();
        for (JsonNode held : json.readTree(result.getResponse().getContentAsString())
                .get("permissions")) {
            keys.add(held.get("key").asText());
        }
        return keys;
    }

    private static int statusOf(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String sessionFor(UUID userId) {
        return sessionFor(userId, NORTH);
    }

    private String sessionFor(UUID userId, UUID companyId) {
        return tx.execute(status -> sessions.issue(
                userId, companyId,
                users.findById(userId).orElseThrow().getPermissionVersion(), false).token());
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
