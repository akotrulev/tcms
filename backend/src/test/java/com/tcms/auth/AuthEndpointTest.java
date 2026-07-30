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
import com.tcms.tenancy.CompanyContext;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The authentication surface, end to end over HTTP.
 *
 * <p>This is the first web test in the codebase, and the first time the tenancy layer runs
 * the way production will run it: a company established from a signed claim by
 * {@link CompanyContextFilter}, rather than by a test opening a scope by hand.
 *
 * <p>Owns the {@code auth-} slug namespace (handoff §14 — the container is shared).
 */
@AutoConfigureMockMvc
@DisplayName("Authentication over HTTP")
class AuthEndpointTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

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

    @Nested
    @DisplayName("Signing in")
    class SigningIn {

        @Test
        @DisplayName("returns a token and the caller's per-membership display name")
        void signsIn() throws Exception {
            Fixture fixture = activated("auth-in", "In User");

            JsonNode body = json.readTree(login(fixture.email(), PASSWORD, 200)
                    .getResponse().getContentAsString());

            assertThat(body.get("token").asText()).isNotBlank();
            assertThat(Instant.parse(body.get("expiresAt").asText())).isAfter(Instant.now());
            assertThat(body.get("user").get("displayName").asText())
                    .as("the name is resolved through the ACTIVE company's membership, not "
                            + "from app_user.canonical_name — a contractor is named "
                            + "differently to each client and neither may see the other's (§6u)")
                    .isEqualTo("In User");
            assertThat(body.get("user").get("email").asText()).isEqualTo(fixture.email());
        }

        @Test
        @DisplayName("refuses an invited-but-not-activated account with no explanation")
        void refusesAnInvitedAccount() throws Exception {
            ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                    "auth-invited", "Auth Invited Ltd", "pending@auth-invited.test", "Pending"));
            assertThat(result.invitationLink()).isPresent();

            MvcResult refused = login("pending@auth-invited.test", PASSWORD, 401);

            assertThat(refused.getResponse().getContentAsString())
                    .as("a body distinguishing 'not activated' from 'no such account' turns "
                            + "the form into a directory of who exists — and email is unique "
                            + "instance-wide, so that directory spans every client")
                    .isEmpty();
        }

        @Test
        @DisplayName("answers a wrong password and an unknown address identically")
        void wrongPasswordAndUnknownAddressAreIndistinguishable() throws Exception {
            Fixture fixture = activated("auth-same", "Same Answer");

            MvcResult wrongPassword = login(fixture.email(), "wrong-password-entirely", 401);
            MvcResult unknownAddress = login("nobody@auth-same.test", PASSWORD, 401);

            assertThat(wrongPassword.getResponse().getContentAsString())
                    .isEqualTo(unknownAddress.getResponse().getContentAsString())
                    .isEmpty();
        }

        @Test
        @DisplayName("spends the same work on an unknown address as on a real one")
        void anUnknownAddressStillCostsAHash() throws Exception {
            // BCrypt at cost 12 is hundreds of milliseconds; a failed row lookup is
            // microseconds. Without the dummy-hash verification, "no such account" would
            // answer two orders of magnitude faster than "wrong password" and reinstate the
            // account oracle that the identical response body exists to remove.
            //
            // The threshold is deliberately far below one hash and far above a bare lookup,
            // so it measures the presence of the work rather than the speed of the machine.
            Instant start = Instant.now();
            login("definitely-nobody@auth-timing.test", PASSWORD, 401);
            Duration elapsed = Duration.between(start, Instant.now());

            assertThat(elapsed).isGreaterThan(Duration.ofMillis(50));
        }

        @Test
        @DisplayName("is case-insensitive about the address, as citext promises")
        void addressIsCaseInsensitive() throws Exception {
            Fixture fixture = activated("auth-case", "Case User");

            login(fixture.email().toUpperCase(java.util.Locale.ROOT), PASSWORD, 200);
        }
    }

    @Nested
    @DisplayName("Using the session")
    class UsingTheSession {

        @Test
        @DisplayName("/me needs a token, and /me/permissions answers from the company in it")
        void meRequiresATokenAndResolvesPermissions() throws Exception {
            Fixture fixture = activated("auth-me", "Me User");
            mvc.perform(get("/api/v1/me")).andReturn().getResponse().getStatus();

            assertThat(mvc.perform(get("/api/v1/me")).andReturn().getResponse().getStatus())
                    .isEqualTo(401);

            String token = tokenFor(fixture);
            JsonNode me = json.readTree(mvc.perform(get("/api/v1/me")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString());
            assertThat(me.get("displayName").asText()).isEqualTo("Me User");

            JsonNode permissions = json.readTree(mvc.perform(get("/api/v1/me/permissions")
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString());
            assertThat(permissions.get("permissions")).isNotEmpty();
            assertThat(permissions.get("permissions").toString())
                    .as("the first Company Admin holds company-wide capability, resolved "
                            + "through PermissionEvaluator against the company in the token")
                    .contains("company:settings");
        }

        @Test
        @DisplayName("leaves no company context behind on the thread")
        void closesTheCompanyContext() throws Exception {
            Fixture fixture = activated("auth-scope", "Scope User");

            mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokenFor(fixture)))
                    .andReturn();

            // MockMvc runs the filter chain on THIS thread, so the check is direct. Servlet
            // threads are pooled: a context left open is inherited by the next request on
            // that thread, and a cross-company read then looks entirely ordinary in a log.
            assertThat(CompanyContext.current())
                    .as("CompanyContextFilter opens the scope in try-with-resources for "
                            + "exactly this reason")
                    .isEmpty();
        }

        @Test
        @DisplayName("stops accepting a token the moment the account's authorization changes")
        void aBumpedEpochInvalidatesTheSession() throws Exception {
            Fixture fixture = activated("auth-epoch", "Epoch User");
            String token = tokenFor(fixture);

            assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);

            // Deactivation bumps pv. Without the epoch check the signed token would keep
            // working until it expired — which for an 8-hour session is most of a working
            // day after the account was switched off.
            AppUser user = users.findById(fixture.userId()).orElseThrow();
            user.deactivate(Instant.now());
            users.saveAndFlush(user);

            assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("refuses a token signed with someone else's key")
        void refusesAForeignSignature() throws Exception {
            // A token whose payload is plausible and whose signature is not. If this ever
            // returns 200, nothing else in this class means anything.
            String forged = "eyJhbGciOiJIUzI1NiJ9."
                    + "eyJpc3MiOiJ0Y21zIiwic3ViIjoiMDAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAw"
                    + "MDAxIiwicHYiOjAsImV4cCI6NDEwMjQ0NDgwMH0.this-signature-is-not-real";

            assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + forged))
                    .andReturn().getResponse().getStatus()).isEqualTo(401);
        }

        @Test
        @DisplayName("logout is a client-side discard and says so by doing nothing")
        void logoutIsStateless() throws Exception {
            Fixture fixture = activated("auth-logout", "Logout User");
            String token = tokenFor(fixture);

            assertThat(mvc.perform(post("/api/v1/auth/logout")
                    .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(204);

            // Deliberate, and documented on the endpoint: a stateless token has nothing
            // server-side to invalidate. Asserting the truth here rather than a comfortable
            // fiction, because a caller who believes logout revoked a leaked token will not
            // rotate anything. The immediate lever is pv, covered above.
            assertThat(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Redeeming an invitation over HTTP")
    class Redeeming {

        @Test
        @DisplayName("previews, accepts once, and then admits the new account")
        void theWholeWayIn() throws Exception {
            ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                    "auth-redeem", "Auth Redeem Ltd", "new@auth-redeem.test", "New Admin"));
            String token = tokenOf(result);

            JsonNode preview = json.readTree(mvc.perform(get("/api/v1/auth/invitations/" + token))
                    .andReturn().getResponse().getContentAsString());
            assertThat(preview.get("email").asText()).isEqualTo("new@auth-redeem.test");
            assertThat(preview.get("passwordRequired").asBoolean()).isTrue();

            assertThat(accept(token, PASSWORD).getResponse().getStatus()).isEqualTo(204);
            assertThat(accept(token, PASSWORD).getResponse().getStatus())
                    .as("single-use, and a spent token is indistinguishable from a made-up one")
                    .isEqualTo(404);

            login("new@auth-redeem.test", PASSWORD, 200);
        }

        @Test
        @DisplayName("answers 404 for a made-up token, with no body")
        void unknownTokenIsANotFound() throws Exception {
            MvcResult response = mvc.perform(get("/api/v1/auth/invitations/not-a-token"))
                    .andReturn();

            assertThat(response.getResponse().getStatus()).isEqualTo(404);
            assertThat(response.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("explains a rejected password, because that is about the submission")
        void aWeakPasswordIsExplained() throws Exception {
            ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                    "auth-weak", "Auth Weak Ltd", "weak@auth-weak.test", "Weak Pass"));

            MvcResult response = accept(tokenOf(result), "short");

            assertThat(response.getResponse().getStatus()).isEqualTo(422);
            assertThat(json.readTree(response.getResponse().getContentAsString())
                    .get("detail").asText())
                    .as("this describes the password they just typed, not the account or the "
                            + "instance, so it leaks nothing while saying nothing would "
                            + "leave them guessing")
                    .contains("at least");
        }

        @Test
        @DisplayName("is reachable with no credentials — the endpoints that must be open, are")
        void isReachableAnonymously() throws Exception {
            // The other side of deny-by-default: /auth/** has to be open, because someone
            // accepting an invitation has nothing to authenticate with. A chain that
            // secured it would deadlock the only way into a new instance.
            assertThat(mvc.perform(get("/api/v1/auth/invitations/anything"))
                    .andReturn().getResponse().getStatus()).isNotEqualTo(401);
        }
    }

    // ---------------------------------------------------------------- helpers ----

    private record Fixture(String email, java.util.UUID userId) {
    }

    /** Provisions a company and walks its administrator all the way to ACTIVE. */
    private Fixture activated(String slug, String displayName) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.of(
                slug, "Auth " + slug, "admin@" + slug + ".test", displayName));
        assertThat(invitations.accept(tokenOf(result), PASSWORD))
                .isInstanceOf(InvitationService.Accepted.class);
        return new Fixture("admin@" + slug + ".test", result.adminUserId());
    }

    private static String tokenOf(ProvisionCompanyResult result) {
        return result.invitationLink().orElseThrow().replaceAll(".*\\?token=", "");
    }

    private MvcResult login(String email, String password, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new AuthController.LoginRequest(
                                email, password, false))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return result;
    }

    private MvcResult accept(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new InvitationController.AcceptRequest(password))))
                .andReturn();
    }

    private String tokenFor(Fixture fixture) throws Exception {
        return json.readTree(login(fixture.email(), PASSWORD, 200)
                .getResponse().getContentAsString()).get("token").asText();
    }
}
