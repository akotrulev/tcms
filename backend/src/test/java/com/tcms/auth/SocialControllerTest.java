package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.InvitationService;
import com.tcms.identity.SocialIdentityService;
import com.tcms.identity.SocialProviderKey;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.identity.AppUser;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The two social controllers over MockMvc — the public browser legs answer 302 whatever
 * happens, the {@code /me} legs require the session {@code /auth/**} does not.
 */
@AutoConfigureMockMvc
@DisplayName("Social endpoints — public auth legs, authenticated /me legs")
class SocialControllerTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @DynamicPropertySource
    static void socialProviders(DynamicPropertyRegistry registry) {
        registry.add("tcms.auth.social.github.client-id", () -> "gh-client");
        registry.add("tcms.auth.social.github.client-secret", () -> "gh-secret");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private SocialIdentityService identities;

    @Autowired
    private com.tcms.identity.AppUserRepository users;

    @Autowired
    private SessionTokens sessions;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Test
    @DisplayName("GET /auth/social/providers answers the configured set to anyone")
    void providersIsPublic() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/social/providers")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("providers")).hasSize(1);
        assertThat(body.get("providers").get(0).asText()).isEqualTo("GITHUB");
    }

    @Test
    @DisplayName("GET /auth/social/GITHUB/authorize 302s to github.com when configured")
    void authorizeRedirectsToTheProvider() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/social/GITHUB/authorize")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location"))
                .startsWith("https://github.com/login/oauth/authorize?");
    }

    @Test
    @DisplayName("an unconfigured provider and a garbage provider both 302 to the one failure landing")
    void authorizeFailuresAreOneRedirect() throws Exception {
        MvcResult unconfigured = mvc.perform(get("/api/v1/auth/social/GITLAB/authorize"))
                .andReturn();
        MvcResult garbage = mvc.perform(get("/api/v1/auth/social/MYSPACE/authorize")).andReturn();

        assertThat(unconfigured.getResponse().getStatus()).isEqualTo(302);
        assertThat(garbage.getResponse().getStatus()).isEqualTo(302);
        assertThat(unconfigured.getResponse().getHeader("Location"))
                .contains("#error=sso_failed")
                .isEqualTo(garbage.getResponse().getHeader("Location"));
    }

    @Test
    @DisplayName("GET /auth/social/callback 302s to the failure landing on garbage")
    void callbackAlwaysRedirects() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/social/callback")
                .param("state", "never-issued").param("code", "x")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location")).contains("#error=sso_failed");
    }

    @Test
    @DisplayName("the /me surface requires a session")
    void meSurfaceIsAuthenticated() throws Exception {
        assertThat(mvc.perform(get("/api/v1/me/social-identities"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/me/social-identities/GITHUB"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(delete("/api/v1/me/social-identities/GITHUB"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("GET /me/social-identities lists the caller's linked identities")
    void listsIdentities() throws Exception {
        Fixture fixture = activated("soc-list");
        identities.link(fixture.userId(), SocialProviderKey.GITHUB,
                new SocialIdentityService.Claims("soc-gh-list", "gh@test.test", "Somebody"));

        MvcResult result = mvc.perform(get("/api/v1/me/social-identities")
                .header("Authorization", "Bearer " + tokenFor(fixture))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("identities")).hasSize(1);
        assertThat(body.get("identities").get(0).get("provider").asText()).isEqualTo("GITHUB");
        assertThat(body.get("identities").get(0).get("emailAtLink").asText())
                .isEqualTo("gh@test.test");
    }

    @Test
    @DisplayName("POST /me/social-identities/GITHUB answers a URL; an unconfigured or unknown provider is 404")
    void beginLinkAnswersAUrl() throws Exception {
        Fixture fixture = activated("soc-begin");
        String token = tokenFor(fixture);

        MvcResult ok = mvc.perform(post("/api/v1/me/social-identities/GITHUB")
                .header("Authorization", "Bearer " + token)).andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(ok.getResponse().getContentAsString()).get("url").asText())
                .startsWith("https://github.com/login/oauth/authorize?");

        assertThat(mvc.perform(post("/api/v1/me/social-identities/GITLAB")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(post("/api/v1/me/social-identities/MYSPACE")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("DELETE the last credential of a passwordless account is a 409 problem naming the remedy")
    void lastCredentialIsAConflict() throws Exception {
        UUID userId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> users.saveAndFlush(com.tcms.identity.AppUser
                .federate(userId, "soc-jit@test.test", "Soc JIT", java.time.Instant.now())));
        identities.link(userId, SocialProviderKey.GITHUB,
                new SocialIdentityService.Claims("soc-gh-jit", null, null));
        AppUser user = transactions.execute(status -> users.findById(userId).orElseThrow());
        String token = sessions.issue(userId, null, user.getPermissionVersion(), false).token();

        MvcResult refused = mvc.perform(delete("/api/v1/me/social-identities/GITHUB")
                .header("Authorization", "Bearer " + token)).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("only way to sign in");
    }

    @Test
    @DisplayName("DELETE unlinks with a 204, idempotently; an unknown provider is 404")
    void unlinkStatuses() throws Exception {
        Fixture fixture = activated("soc-unlink");
        String token = tokenFor(fixture);
        identities.link(fixture.userId(), SocialProviderKey.GITHUB,
                new SocialIdentityService.Claims("soc-gh-unlink", null, null));

        assertThat(mvc.perform(delete("/api/v1/me/social-identities/GITHUB")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        // Idempotent: already gone answers 204 again.
        assertThat(mvc.perform(delete("/api/v1/me/social-identities/GITHUB")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
        assertThat(mvc.perform(delete("/api/v1/me/social-identities/MYSPACE")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    // -------------------------------------------------------------------- fixtures ----

    private record Fixture(String email, UUID userId) {
    }

    private Fixture activated(String slug) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                slug, "Soc " + slug, "admin@" + slug + ".test", "Soc Admin"));
        String token = result.invitationLink().orElseThrow().replaceAll(".*\\?token=", "");
        assertThat(invitations.accept(token, PASSWORD))
                .isInstanceOf(InvitationService.Accepted.class);
        return new Fixture("admin@" + slug + ".test", result.adminUserId());
    }

    private String tokenFor(Fixture fixture) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthController.LoginRequest(
                                fixture.email(), PASSWORD, false))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
