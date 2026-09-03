package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code /me/assistants} — <strong>the first credential in the product a caller issues for
 * themselves</strong> (D26).
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The surface gated on {@code token:issue}.</strong> That is the natural
 *       implementation — it reuses {@code ApiTokenService.issue} in one line — and it makes the
 *       feature unusable by everybody it is for, since no PROJECT-scope role holds that key. A
 *       Test Engineer's 201 is the assertion that kills it.</li>
 *   <li><strong>The token carrying its OWNER's role rather than Agent.</strong> Invisible while the
 *       owner is an ordinary member, because the two resolve to the same set. Asserted through a
 *       <em>Test Lead</em>: they hold {@code issue:delete} and the credential they issued must not,
 *       so a token resolving to its owner's grants passes every other test here and fails this
 *       one.</li>
 *   <li><strong>An assistant with no expiry.</strong> {@code ApiTokenService}'s per-role rule
 *       requires one and this surface does not ask for it, so a service that simply forwards the
 *       request would answer 400 to every connect — or, if the rule were "relaxed" for this path,
 *       would put an immortal credential on a laptop.</li>
 *   <li><strong>Somebody else's assistant revoked or listed.</strong> The rows live in one
 *       company-wide table, so an unfiltered read is the shape that works perfectly for one
 *       developer and shows the whole company's to the second.</li>
 * </ol>
 *
 * <p>Ids use the {@code e5…} prefix, checked free across the suite before it was taken.
 */
@AutoConfigureMockMvc
@DisplayName("Connected assistants — a credential you issue for yourself, capped at Agent")
class MeAssistantsTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("e5000000-0000-4000-8000-000000000001");

    private static final UUID ATLAS = UUID.fromString("e5001000-0000-4000-8000-000000000001");
    private static final UUID BEACON = UUID.fromString("e5001000-0000-4000-8000-000000000002");

    /** Test Lead on both projects — holds {@code issue:delete}, and holds no {@code token:issue}. */
    private static final UUID LEAD = UUID.fromString("e5002000-0000-4000-8000-000000000001");
    /** Test Engineer on Atlas — the developer this feature exists for. */
    private static final UUID DEV = UUID.fromString("e5002000-0000-4000-8000-000000000002");

    private static final UUID LEAD_ATLAS = UUID.fromString("e5003000-0000-4000-8000-000000000001");
    private static final UUID LEAD_BEACON = UUID.fromString("e5003000-0000-4000-8000-000000000002");
    private static final UUID DEV_ATLAS = UUID.fromString("e5003000-0000-4000-8000-000000000003");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "assistants-acme", "Acme", now);
            activeUser(LEAD, "lead@assistants.test", "Leonora Lead", now);
            activeUser(DEV, "dev@assistants.test", "D. Developer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, ACME, "ASTA", "Atlas", now);
            seedProject(BEACON, ACME, "ASTB", "Beacon", now);
            member(ACME, LEAD, "Leonora Lead", now);
            member(ACME, DEV, "D. Developer", now);
        }));

        grantOnProject(LEAD_ATLAS, ACME, Role.TEST_LEAD_ID, LEAD, ATLAS);
        grantOnProject(LEAD_BEACON, ACME, Role.TEST_LEAD_ID, LEAD, BEACON);
        grantOnProject(DEV_ATLAS, ACME, Role.TEST_ENGINEER_ID, DEV, ATLAS);
        seedProjectDefaults(ACME, ATLAS);
        seedProjectDefaults(ACME, BEACON);
        clearIssues(List.of(ACME));
        clearTokens();
    }

    /**
     * Empties the tokens and the trail this class reads its assertions from.
     *
     * <p>Per method, for {@code ApiTokenEndpointTest}'s reason: the name is unique per company
     * among live rows, so a class that let its tokens accumulate would pass or fail on JUnit's
     * method order.
     */
    private void clearTokens() throws SQLException {
        try (var connection = appConnection(ACME)) {
            for (String sql : List.of("DELETE FROM audit_event", "DELETE FROM api_token")) {
                try (var statement = connection.prepareStatement(sql)) {
                    statement.executeUpdate();
                }
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("Connecting one costs no permission, which is the whole point")
    class Connecting {

        @Test
        @DisplayName("a Test Engineer connects one — 201, with the secret and a Location")
        void aDeveloperMayConnect() throws Exception {
            MvcResult result = connect(DEV, "{\"name\":\"claude-code\",\"projectId\":\""
                    + ATLAS + "\"}");

            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(201);
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("secret").asText()).startsWith(ApiTokenSecrets.PREFIX);
            assertThat(body.get("assistant").get("name").asText()).isEqualTo("claude-code");
            assertThat(body.get("assistant").get("projectKey").asText()).isEqualTo("ASTA");
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/me/assistants/"
                            + body.get("assistant").get("id").asText());
        }

        @Test
        @DisplayName("and the same caller is refused by /api-tokens — the two gates differ")
        void theAdministrativeSurfaceStillRefusesThem() throws Exception {
            // The pair that says this is a decision rather than an oversight: one endpoint mints a
            // credential the caller names the role of, and the other mints one that can reach
            // nothing the caller cannot.
            MvcResult refused = mvc.perform(MockMvcRequestBuilders.post("/api/v1/api-tokens")
                            .header("Authorization", "Bearer " + tokenFor(DEV, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"claude-code\",\"roleId\":\"" + Role.AGENT_ID
                                    + "\"}"))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("no projectId means every project the owner can reach")
        void anUnscopedAssistantHasNoProjectKey() throws Exception {
            JsonNode body = connected(DEV, "everywhere", null);

            assertThat(body.get("assistant").get("projectId").isNull()).isTrue();
            assertThat(body.get("assistant").get("projectKey").isNull()).isTrue();
        }

        @Test
        @DisplayName("the server sets an expiry — this surface never asks for one and never omits it")
        void everyAssistantExpires() throws Exception {
            // ApiTokenService's per-role rule requires an Agent token to expire, and this form has
            // no field for it. A service that simply forwarded the request would 400 here.
            UUID id = idOf(connected(DEV, "claude-code", ATLAS));

            assertThat(expiryOf(id)).isNotNull();
        }

        @Test
        @DisplayName("a blank name is a field error")
        void aBlankNameIsRefused() throws Exception {
            MvcResult result = connect(DEV, "{\"name\":\"  \"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("name");
        }

        @Test
        @DisplayName("a project outside this company is a 404, not a 403")
        void anUnreachableProjectIs404() throws Exception {
            MvcResult result = connect(DEV,
                    "{\"name\":\"claude-code\",\"projectId\":\"" + UUID.randomUUID() + "\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("The credential is capped at Agent, never at its owner")
    class TheCap {

        @Test
        @DisplayName("it authenticates and can read the project it is scoped to")
        void theSecretWorks() throws Exception {
            String secret = connected(DEV, "claude-code", ATLAS).get("secret").asText();

            assertThat(statusOfIssueList(secret, ATLAS)).isEqualTo(200);
        }

        @Test
        @DisplayName("a scoped assistant reaches nothing in another project — asserted as absence")
        void theScopeIsHard() throws Exception {
            // LEAD holds Test Lead on BOTH projects, so this cannot be their visibility failing:
            // TokenContext.projectId narrows the permission intersection to the empty set on
            // Beacon, and an empty set is indistinguishable from no such project (§6t).
            String secret = connected(LEAD, "claude-code", ATLAS).get("secret").asText();

            assertThat(statusOfIssueList(secret, ATLAS)).isEqualTo(200);
            assertThat(statusOfIssueList(secret, BEACON)).isEqualTo(404);
        }

        @Test
        @DisplayName("a Test Lead's assistant cannot delete an issue, though its owner can")
        void theTokenCarriesAgentAndNotItsOwnersRole() throws Exception {
            // The mutation this kills: resolving the token as its owner rather than as the
            // intersection of Agent and its owner. Every other assertion in this class passes
            // under that implementation.
            String key = createIssue(LEAD, ATLAS, "Something to delete");
            String secret = connected(LEAD, "claude-code", ATLAS).get("secret").asText();

            int throughTheToken = mvc.perform(MockMvcRequestBuilders
                            .delete("/api/v1/issues/" + key)
                            .header("Authorization", "Bearer " + secret))
                    .andReturn().getResponse().getStatus();
            int throughTheOwner = mvc.perform(MockMvcRequestBuilders
                            .delete("/api/v1/issues/" + key)
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andReturn().getResponse().getStatus();

            assertThat(throughTheToken).isEqualTo(403);
            assertThat(throughTheOwner).isEqualTo(204);
        }

        @Test
        @DisplayName("every act through it is attributed to the person, via the assistant")
        void attributionNamesBoth() throws Exception {
            // "<person>, via <assistant>" is assembled from these two columns and nothing else.
            // A token that recorded only actor_user_id renders as the person having done it by
            // hand, which is the one thing the history must not say.
            JsonNode connected = connected(DEV, "claude-code", ATLAS);
            UUID assistantId = idOf(connected);

            createIssueThroughToken(connected.get("secret").asText(), ATLAS, "Filed by the agent");

            assertThat(actorsOf("issue.created")).containsExactly(DEV + "/" + assistantId);
        }
    }

    @Nested
    @DisplayName("The list and the revoke are the caller's own rows and nobody else's")
    class OwnRowsOnly {

        @Test
        @DisplayName("the list carries the caller's own, and never the secret")
        void theListIsScopedAndCarriesNoSecret() throws Exception {
            String secret = connected(DEV, "claude-code", ATLAS).get("secret").asText();
            connected(LEAD, "leads-own", ATLAS);

            String listed = list(DEV).getResponse().getContentAsString();

            assertThat(json.readTree(listed)).hasSize(1);
            assertThat(json.readTree(listed).get(0).get("name").asText()).isEqualTo("claude-code");
            // On the raw body rather than on a field, because the failure being prevented is a key
            // nobody expected: a nullable `secret` on the read type that one path fills.
            assertThat(listed).doesNotContain(secret);
            assertThat(listed).doesNotContain("\"secret\"");
        }

        @Test
        @DisplayName("revoking is 204, idempotent, and the secret stops working")
        void revoking() throws Exception {
            JsonNode connected = connected(DEV, "claude-code", ATLAS);
            String secret = connected.get("secret").asText();
            UUID id = idOf(connected);

            assertThat(revoke(DEV, id).getResponse().getStatus()).isEqualTo(204);
            assertThat(revoke(DEV, id).getResponse().getStatus()).isEqualTo(204);

            assertThat(statusOfIssueList(secret, ATLAS)).isEqualTo(401);
        }

        @Test
        @DisplayName("a revoked assistant stays in the list, carrying when it stopped working")
        void aRevokedAssistantIsEvidence() throws Exception {
            // Its acts stay on each issue's history attributed to its owner via it, so a row that
            // vanished would leave those lines naming something the screen says never existed.
            UUID id = idOf(connected(DEV, "claude-code", ATLAS));
            revoke(DEV, id);

            JsonNode listed = json.readTree(list(DEV).getResponse().getContentAsString());

            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).get("revokedAt").isNull()).isFalse();
        }

        @Test
        @DisplayName("somebody else's is a 404 and stays live")
        void anotherPersonsAssistantIsNotTheCallersToRevoke() throws Exception {
            JsonNode leads = connected(LEAD, "leads-own", ATLAS);
            UUID id = idOf(leads);

            assertThat(revoke(DEV, id).getResponse().getStatus()).isEqualTo(404);
            assertThat(statusOfIssueList(leads.get("secret").asText(), ATLAS)).isEqualTo(200);
        }
    }

    // ===================================================================== helpers

    private MvcResult connect(UUID callerId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/me/assistants")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode connected(UUID callerId, String name, UUID projectId) throws Exception {
        MvcResult result = connect(callerId, "{\"name\":\"" + name + "\",\"projectId\":"
                + (projectId == null ? "null" : "\"" + projectId + "\"") + "}");
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult list(UUID callerId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/me/assistants")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn();
    }

    private MvcResult revoke(UUID callerId, UUID assistantId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1/me/assistants/" + assistantId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn();
    }

    private int statusOfIssueList(String bearer, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1/issues")
                        .param("projectId", projectId.toString())
                        .header("Authorization", "Bearer " + bearer))
                .andReturn().getResponse().getStatus();
    }

    private String createIssue(UUID callerId, UUID projectId, String title) throws Exception {
        return createdKey(tokenFor(callerId, ACME), projectId, title);
    }

    private void createIssueThroughToken(String secret, UUID projectId, String title)
            throws Exception {

        createdKey(secret, projectId, title);
    }

    private String createdKey(String bearer, UUID projectId, String title) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":\"" + projectId + "\",\"typeId\":\""
                                + taskTypeOf(projectId) + "\",\"title\":\"" + title + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private UUID taskTypeOf(UUID projectId) throws Exception {
        JsonNode workflow = json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode type : workflow.get("types")) {
            if ("Task".equals(type.get("name").asText())) {
                return UUID.fromString(type.get("id").asText());
            }
        }
        throw new AssertionError("this project has no Task type");
    }

    private static UUID idOf(JsonNode connected) {
        return UUID.fromString(connected.get("assistant").get("id").asText());
    }

    private String expiryOf(UUID tokenId) throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT expires_at FROM api_token WHERE id = ?::uuid")) {
            statement.setString(1, tokenId.toString());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /** {@code actor_user_id/actor_token_id} straight off the trail, as one string per row. */
    private List<String> actorsOf(String action) throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT actor_user_id, actor_token_id FROM audit_event WHERE action = ?")) {
            statement.setString(1, action);
            try (var rows = statement.executeQuery()) {
                List<String> actors = new java.util.ArrayList<>();
                while (rows.next()) {
                    actors.add(rows.getString(1) + "/" + rows.getString(2));
                }
                return actors;
            }
        }
    }
}
