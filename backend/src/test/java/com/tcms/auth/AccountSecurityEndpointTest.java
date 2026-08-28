package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.InvitationService;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/** 005 S9a — {@code POST /me/password}, {@code POST /me/sign-out-others}, {@code CurrentUser.hasPassword}. */
@AutoConfigureMockMvc
@DisplayName("Account security — the two self-service acts on the calling account")
class AccountSecurityEndpointTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "battery-horse-correct-2";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private InvitationService invitations;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private TransactionTemplate tx;

    @Nested
    @DisplayName("hasPassword")
    class HasPassword {

        @Test
        @DisplayName("true for a password account — a boolean and nothing more")
        void trueWhenAHashIsStored() throws Exception {
            Fixture fixture = activated("acct-haspw", "Has Password");
            JsonNode me = meAs(tokenFor(fixture));

            assertThat(me.get("hasPassword").asBoolean()).isTrue();
            assertThat(me.fieldNames()).toIterable()
                    .as("no hint, no age, no strength")
                    .doesNotContain("passwordHash", "passwordSetAt", "passwordStrength");
        }
    }

    @Nested
    @DisplayName("POST /me/password")
    class ChangePassword {

        @Test
        @DisplayName("changes it when the current one is right — 204, and the session is unaffected")
        void changesWithTheCurrentPassword() throws Exception {
            Fixture fixture = activated("acct-change", "Changer");
            String token = tokenFor(fixture);

            assertThat(changePassword(token, PASSWORD, NEW_PASSWORD).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(meAs(token).get("email").asText())
                    .as("changing a password bumps nothing — the session that asked keeps working")
                    .isEqualTo(fixture.email());
            assertThat(login(fixture.email(), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
            assertThat(login(fixture.email(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("a wrong current password is 400 on errors.currentPassword — never a 401")
        void wrongCurrentIs400NotA401() throws Exception {
            Fixture fixture = activated("acct-wrong", "Wrong Current");
            MvcResult refused = changePassword(tokenFor(fixture), "not-the-current-one", NEW_PASSWORD);

            assertThat(refused.getResponse().getStatus())
                    .as("the session is fine; a 401 would trip the SPA's recovery path for a typo")
                    .isEqualTo(400);
            JsonNode body = json.readTree(refused.getResponse().getContentAsString());
            assertThat(body.get("errors").get("currentPassword").get(0).asText()).endsWith(".");
            assertThat(login(fixture.email(), NEW_PASSWORD).getResponse().getStatus())
                    .as("nothing was changed")
                    .isEqualTo(401);
        }

        @Test
        @DisplayName("a null current password on an account that has one is the same 400")
        void nullCurrentOnAPasswordAccountIs400() throws Exception {
            Fixture fixture = activated("acct-null", "Null Current");
            MvcResult refused = changePassword(tokenFor(fixture), null, NEW_PASSWORD);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("currentPassword");
        }

        @Test
        @DisplayName("a policy failure is 422 on errors.newPassword with the invitation-accept rules")
        void policyFailureIs422() throws Exception {
            Fixture fixture = activated("acct-policy", "Policy");

            MvcResult tooShort = changePassword(tokenFor(fixture), PASSWORD, "short");
            assertThat(tooShort.getResponse().getStatus()).isEqualTo(422);
            JsonNode body = json.readTree(tooShort.getResponse().getContentAsString());
            assertThat(body.get("errors").get("newPassword").get(0).asText()).contains("12");

            MvcResult tooLong = changePassword(tokenFor(fixture), PASSWORD, "x".repeat(73));
            assertThat(tooLong.getResponse().getStatus()).isEqualTo(422);
            assertThat(tooLong.getResponse().getContentAsString()).contains("72");
        }

        @Test
        @DisplayName("the current password is checked BEFORE the policy — a stranger learns nothing about the candidate")
        void currentIsCheckedFirst() throws Exception {
            Fixture fixture = activated("acct-order", "Order");
            MvcResult refused = changePassword(tokenFor(fixture), "not-the-current-one", "short");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).doesNotContain("newPassword");
        }

        @Test
        @DisplayName("an account with no password SETS one with currentPassword: null")
        void setsOneOnAPasswordlessAccount() throws Exception {
            Fixture fixture = activated("acct-set", "Setter");
            String token = tokenFor(fixture);
            tx.executeWithoutResult(status -> users.findById(fixture.userId()).ifPresent(account -> {
                account.changePassword(null, java.time.Instant.now());
                users.save(account);
            }));
            assertThat(meAs(token).get("hasPassword").asBoolean()).isFalse();

            assertThat(changePassword(token, null, NEW_PASSWORD).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(meAs(token).get("hasPassword").asBoolean()).isTrue();
            assertThat(login(fixture.email(), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("the second identical call is refused — the reason it is a POST and not a PUT")
        void notIdempotent() throws Exception {
            Fixture fixture = activated("acct-twice", "Twice");
            String token = tokenFor(fixture);

            assertThat(changePassword(token, PASSWORD, NEW_PASSWORD).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(changePassword(token, PASSWORD, NEW_PASSWORD).getResponse().getStatus())
                    .as("the currentPassword it names has just stopped being current")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("needs a session")
        void needsASession() throws Exception {
            assertThat(mvc.perform(post("/api/v1/me/password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":null,\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("POST /me/sign-out-others")
    class SignOutOthers {

        @Test
        @DisplayName("bumps pv and answers 200 with a fresh AuthResult — the other session dies, the caller's replacement lives")
        void bumpsTheEpochAndHandsBackANewSession() throws Exception {
            Fixture fixture = activated("acct-signout", "Sign Out");
            String phone = tokenFor(fixture);
            String laptop = tokenFor(fixture);
            int epochBefore = epochOf(fixture.userId());

            MvcResult result = mvc.perform(post("/api/v1/me/sign-out-others")
                            .header("Authorization", "Bearer " + laptop))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("a 204 would sign the caller out too, one request later")
                    .isEqualTo(200);
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("token").asText()).isNotBlank().isNotEqualTo(laptop);
            assertThat(body.get("expiresAt").asText()).isNotBlank();
            assertThat(body.get("user").get("email").asText()).isEqualTo(fixture.email());
            assertThat(epochOf(fixture.userId())).isEqualTo(epochBefore + 1);

            assertThat(meStatus(phone)).as("every other session gets a 401").isEqualTo(401);
            assertThat(meStatus(laptop)).as("including the one that asked").isEqualTo(401);
            assertThat(meStatus(body.get("token").asText()))
                    .as("the replacement carries the new epoch")
                    .isEqualTo(200);
        }
    }

    // --------------------------------------------------------------- plumbing ----

    private record Fixture(String email, UUID userId) {
    }

    private Fixture activated(String slug, String displayName) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                slug, "Account " + slug, "admin@" + slug + ".test", displayName));
        String token = result.invitationLink().orElseThrow().replaceAll(".*\\?token=", "");
        assertThat(invitations.accept(token, PASSWORD))
                .isInstanceOf(InvitationService.Accepted.class);
        return new Fixture("admin@" + slug + ".test", result.adminUserId());
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new AuthController.LoginRequest(email, password, false))))
                .andReturn();
    }

    private String tokenFor(Fixture fixture) throws Exception {
        MvcResult result = login(fixture.email(), PASSWORD);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private MvcResult changePassword(String token, String current, String replacement) throws Exception {
        return mvc.perform(post("/api/v1/me/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new MeController.PasswordChangeRequest(current, replacement))))
                .andReturn();
    }

    private JsonNode meAs(String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int meStatus(String token) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    private int epochOf(UUID userId) {
        return tx.execute(status -> users.findById(userId).map(AppUser::getPermissionVersion).orElseThrow());
    }
}
