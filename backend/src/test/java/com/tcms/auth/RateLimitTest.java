package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
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
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The rate limit as a caller meets it — the MCP plan's phase 9, wired.
 *
 * <p>{@code TokenRateLimiterTest} owns the arithmetic. What can only be asserted from outside is
 * the part that is a <em>choice about the API</em>: which callers are counted, where the filter
 * sits in the chain, and what the refusal actually contains. Three of those are silent failures —
 * a limiter placed after {@code CompanyContextFilter} would refuse requests that had already
 * opened a transaction; one that read the JWT subject instead of {@link
 * com.tcms.tenancy.TokenContext} would count sessions too and nothing would fail until a person
 * clicked quickly; and a 429 with no {@code Retry-After} is the loop this slice exists to
 * interrupt, arriving with a green test behind it.
 *
 * <p>The limits here are deliberately tiny. Production's are generous by design (600 a minute,
 * burst 120), and a test that spent them would be four hundred requests long and would prove the
 * same thing.
 *
 * <p>Ids use the {@code d2…} prefix, which no other class in the suite uses.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "tcms.rate-limit.enabled=true",
        // Three at once, six a minute — so the fourth request in a burst is refused and a permit
        // is worth ten seconds, which is long enough for the wait to be asserted without the
        // assertion depending on how fast MockMvc answered the first three.
        "tcms.rate-limit.requests-per-minute=6",
        "tcms.rate-limit.burst=3",
})
@DisplayName("Rate limiting — per token, and only a token")
class RateLimitTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d2100000-0000-4000-8000-00000000000a");
    private static final UUID PROJECT = UUID.fromString("d2101000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("d2103000-0000-4000-8000-000000000001");
    private static final UUID GRANT = UUID.fromString("d2105000-0000-4000-8000-000000000001");

    private static final String CASES = "/api/v1/projects/" + PROJECT + "/cases";

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
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "acme-rate", "Acme", now));
            }
            if (users.findById(ADMIN).isEmpty()) {
                AppUser user = AppUser.invite(ADMIN, "admin@acme-rate.test", "admin@acme-rate.test", now);
                user.activate("hash", now);
                users.save(user);
            }
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, ACME, "ACR", "Acme Rate", null, now));
            }
            if (memberships.findByCompanyIdAndUserId(ACME, ADMIN).isEmpty()) {
                CompanyMembership membership =
                        CompanyMembership.invite(ACME, ADMIN, "A. Admin", null, now);
                membership.accept(now);
                memberships.save(membership);
            }
            if (roleAssignments.findById(GRANT).isEmpty()) {
                roleAssignments.save(RoleAssignment.forUserOnCompany(
                        GRANT, ACME, Role.COMPANY_ADMIN_ID, ADMIN, null, now));
            }
        }));
    }

    @Test
    @DisplayName("a token is refused at its ceiling, and told how long to wait")
    void aTokenIsRefusedAtItsCeiling() throws Exception {
        String secret = issueToken();

        for (int i = 0; i < 3; i++) {
            assertThat(get(CASES, secret).getResponse().getStatus())
                    .as("request %d of the burst", i + 1)
                    .isEqualTo(200);
        }

        MvcResult refused = get(CASES, secret);

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);

        // Six a minute is a permit every ten seconds, so an exhausted bucket's honest wait is ten
        // — minus however long the three requests above actually took, which is why this is a band
        // and not an equality. A CONSTANT would satisfy an equality just as well; what the band
        // rules out is a header derived from nothing, and TokenRateLimiterTest pins the
        // arithmetic exactly against a clock that does not move.
        String header = refused.getResponse().getHeader("Retry-After");
        assertThat(header).isNotNull();
        assertThat(Integer.parseInt(header)).isBetween(5, 10);

        var body = json.readTree(refused.getResponse().getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(429);
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        // The same number in the prose as in the header — @tcms/cli prints `detail` verbatim and a
        // model reads the sentence rather than the headers, so the two disagreeing would send the
        // two clients to sleep for different lengths of time.
        assertThat(body.get("detail").asText()).contains("Wait " + header + " second");
    }

    @Test
    @DisplayName("a second token is untouched — the bucket is per credential, not per company")
    void asecondTokenIsUntouched() throws Exception {
        String noisy = issueToken();
        String quiet = issueToken();

        for (int i = 0; i < 4; i++) {
            get(CASES, noisy);
        }
        assertThat(get(CASES, noisy).getResponse().getStatus()).isEqualTo(429);

        // Both tokens are the same company, the same owner and the same role. If this is a 429 the
        // key is wrong, and one agent's accident has taken down every CI job in a client
        // organisation.
        assertThat(get(CASES, quiet).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a session is never counted, however fast the person clicks")
    void sessionsAreNotCounted() throws Exception {
        String session = sessionFor(ADMIN);

        // Well past the token ceiling. A limiter keyed on the authenticated subject rather than on
        // TokenContext would refuse here, and nothing in the product would fail until somebody
        // used the SPA quickly — which is a 429 the SPA has no copy for.
        for (int i = 0; i < 10; i++) {
            assertThat(get(CASES, session).getResponse().getStatus())
                    .as("session request %d", i + 1)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a refused request never reaches a handler, so a write is safe to retry")
    void arefusalIsANoOp() throws Exception {
        String secret = issueToken();
        for (int i = 0; i < 3; i++) {
            get(CASES, secret);
        }

        // A POST past the ceiling. It is refused with the same 429 as a read — the filter runs
        // before dispatch, so there is no handler that half-ran and no row that may or may not
        // exist. That is what lets the client tell a model "nothing was changed", which is the
        // opposite of the copy a timed-out write gets (§140's result.ts).
        MvcResult refused = mvc.perform(MockMvcRequestBuilders.post(CASES)
                        .header("Authorization", "Bearer " + secret)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"never created\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getContentAsString()).contains("Nothing was changed");
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private MvcResult get(String path, String bearer) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + bearer))
                .andReturn();
    }

    /** Issues through the API, as an operator does. Each call is a distinct credential. */
    private String issueToken() throws Exception {
        String body = "{\"name\":\"CI " + UUID.randomUUID() + "\""
                + ",\"roleId\":\"" + Role.AUTOMATION_SERVICE_ID + "\""
                + ",\"projectId\":\"" + PROJECT + "\""
                + ",\"expiresAt\":null}";

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/api-tokens")
                        .header("Authorization", "Bearer " + sessionFor(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("secret").asText();
    }

    private String sessionFor(UUID userId) {
        return tx.execute(status -> sessions.issue(
                userId, ACME,
                users.findById(userId).orElseThrow().getPermissionVersion(), false).token());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
