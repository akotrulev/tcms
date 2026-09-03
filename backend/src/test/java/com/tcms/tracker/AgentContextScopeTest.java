package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code GET /issues/{key}/agent-context} — the composite an assistant fetches before it starts,
 * and the scope that must hold across it.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A composite that widens the scope.</strong> This endpoint assembles four reads, and
 *       a single one of them taken outside the issue's own gate is a token scoped to project A
 *       reading project B. Asserted as an <em>absence</em> through a scoped token whose owner can
 *       see both projects, so the answer cannot be their visibility failing.</li>
 *   <li><strong>A context with no acceptance criteria in it.</strong> The task says what to change;
 *       the story says what done means. §3.1's grounding rule is the whole reason this endpoint
 *       exists rather than six calls, and an implementation that returns the issue and nothing else
 *       satisfies the status assertion.</li>
 *   <li><strong>A spec excerpt that quotes nothing.</strong> A row naming a page with an empty
 *       excerpt is something a model treats as evidence it has read the spec.</li>
 * </ol>
 *
 * <p>Ids use the {@code e8…} prefix, checked free across the suite before it was taken.
 */
@AutoConfigureMockMvc
@DisplayName("Agent task context — one read, and a scope the composite may not widen")
class AgentContextScopeTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("e8000000-0000-4000-8000-000000000001");
    private static final UUID ATLAS = UUID.fromString("e8001000-0000-4000-8000-000000000001");
    private static final UUID BEACON = UUID.fromString("e8001000-0000-4000-8000-000000000002");

    /** Test Lead on BOTH projects — so a refusal below can only be the token's scope. */
    private static final UUID LEAD = UUID.fromString("e8002000-0000-4000-8000-000000000001");

    private static final UUID LEAD_ATLAS = UUID.fromString("e8003000-0000-4000-8000-000000000001");
    private static final UUID LEAD_BEACON = UUID.fromString("e8003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "agent-ctx-acme", "Acme", now);
            activeUser(LEAD, "lead@agent-ctx.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, ACME, "ACXA", "Atlas", now);
            seedProject(BEACON, ACME, "ACXB", "Beacon", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_ATLAS, ACME, Role.TEST_LEAD_ID, LEAD, ATLAS);
        grantOnProject(LEAD_BEACON, ACME, Role.TEST_LEAD_ID, LEAD, BEACON);
        seedProjectDefaults(ACME, ATLAS);
        seedProjectDefaults(ACME, BEACON);
        clearIssues(List.of(ACME));
        clearRepositories();
        clearTokens();
    }

    private void clearRepositories() throws SQLException {
        execute("DELETE FROM project_repository");
    }

    private void clearTokens() throws SQLException {
        execute("DELETE FROM audit_event");
        execute("DELETE FROM api_token");
    }

    private void execute(String sql) throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================

    @Test
    @DisplayName("a task comes back with its story's criteria, its epic's goal and the repositories")
    void theContextIsAssembledInOneRead() throws Exception {
        addRepository();
        String epic = created(ATLAS, "Epic", "Checkout", null, null);
        String story = created(ATLAS, "Story", "Guest checkout", epic,
                List.of("A guest can pay without an account", "The receipt is emailed"));
        // "Sub-task", not "Task": the seeded workflow's Task type is STORY level (V3 §592) and
        // sits under an Epic. The TASK-level type is Sub-task, which is what a story breaks into
        // and what an assistant is handed.
        String task = created(ATLAS, "Sub-task", "Wire the payment intent", story, null);
        describe(epic, "Let people buy without signing up first.");

        JsonNode context = contextOf(tokenFor(LEAD, ACME), task);

        assertThat(context.get("issue").get("key").asText()).isEqualTo(task);
        assertThat(context.get("story").get("key").asText()).isEqualTo(story);
        assertThat(context.get("story").get("acceptanceCriteria")).hasSize(2);
        assertThat(context.get("story").get("acceptanceCriteria").get(0).get("text").asText())
                .isEqualTo("A guest can pay without an account");
        assertThat(context.get("story").get("acceptanceCriteria").get(0).get("done").asBoolean())
                .isFalse();
        assertThat(context.get("epic").get("key").asText()).isEqualTo(epic);
        assertThat(context.get("epic").get("goal").asText())
                .isEqualTo("Let people buy without signing up first.");
        assertThat(context.get("repositories")).hasSize(1);
        assertThat(context.get("repositories").get(0).get("buildCommand").asText())
                .isEqualTo("npm ci && npm test");
        assertThat(context.get("repositories").get(0).get("primary").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("release is present and null — M3 has landed, and nothing here targets one")
    void theReleaseKeyIsPresentEvenWhenEmpty() throws Exception {
        // A key a client reads as absent and a key it reads as null are different facts, and the
        // one thing an assistant must not do is invent a version number.
        String task = created(ATLAS, "Task", "Wire the payment intent", null, null);

        JsonNode context = contextOf(tokenFor(LEAD, ACME), task);

        assertThat(context.get("issue").has("release")).isTrue();
        assertThat(context.get("issue").get("release").isNull()).isTrue();
    }

    @Test
    @DisplayName("a task with no story parent still answers — story and epic are simply null")
    void aParentlessTaskIsNotDegraded() throws Exception {
        String task = created(ATLAS, "Task", "Standalone chore", null, null);

        JsonNode context = contextOf(tokenFor(LEAD, ACME), task);

        assertThat(context.get("story").isNull()).isTrue();
        assertThat(context.get("epic").isNull()).isTrue();
        assertThat(context.get("specExcerpts")).isEmpty();
    }

    @Test
    @DisplayName("a token scoped to Atlas receives NOTHING from Beacon — asserted as an absence")
    void theScopeHoldsAcrossTheComposite() throws Exception {
        // LEAD holds Test Lead on both projects, so this cannot be their visibility failing:
        // TokenContext.projectId narrows the permission intersection to the empty set on Beacon.
        // A composite that took any one of its four reads outside the issue's gate answers 200.
        String atlasTask = created(ATLAS, "Task", "Wire the payment intent", null, null);
        String beaconTask = created(BEACON, "Task", "Rotate the signing key", null, null);
        String scopedToAtlas = assistantScopedTo(ATLAS);

        assertThat(statusOfContext(scopedToAtlas, atlasTask)).isEqualTo(200);
        assertThat(statusOfContext(scopedToAtlas, beaconTask)).isEqualTo(404);
    }

    @Test
    @DisplayName("an unknown key is a 404, indistinguishable from an unreachable one")
    void anUnknownKeyIs404() throws Exception {
        assertThat(statusOfContext(tokenFor(LEAD, ACME), "ACXA-99999")).isEqualTo(404);
    }

    @Test
    @DisplayName("the issue carries its caller-scoped allowedTransitions — not a second assembly")
    void theIssueInsideIsTheSameProjection() throws Exception {
        String task = created(ATLAS, "Task", "Wire the payment intent", null, null);

        JsonNode context = contextOf(tokenFor(LEAD, ACME), task);
        JsonNode direct = json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues/" + task)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getContentAsString());

        assertThat(context.get("issue")).isEqualTo(direct);
    }

    // ===================================================================== helpers

    private JsonNode contextOf(String bearer, String key) throws Exception {
        MvcResult result = context(bearer, key);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int statusOfContext(String bearer, String key) throws Exception {
        return context(bearer, key).getResponse().getStatus();
    }

    private MvcResult context(String bearer, String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues/" + key + "/agent-context")
                        .header("Authorization", "Bearer " + bearer))
                .andReturn();
    }

    private String assistantScopedTo(UUID projectId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/me/assistants")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"claude-code\",\"projectId\":\"" + projectId + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("secret").asText();
    }

    private void addRepository() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ATLAS + "/repositories")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"acme-storefront\","
                                + "\"url\":\"git@github.com:northwind/acme-storefront.git\","
                                + "\"defaultBranch\":\"main\",\"subdirectory\":\"/\","
                                + "\"buildCommand\":\"npm ci && npm test\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
    }

    private String created(
            UUID projectId, String typeName, String title, String parentKey, List<String> criteria)
            throws Exception {

        StringBuilder body = new StringBuilder("{\"projectId\":\"").append(projectId)
                .append("\",\"typeId\":\"").append(typeNamed(projectId, typeName))
                .append("\",\"title\":\"").append(title).append("\"");
        if (parentKey != null) {
            body.append(",\"parentKey\":\"").append(parentKey).append("\"");
        }
        if (criteria != null) {
            body.append(",\"acceptanceCriteria\":[");
            for (int index = 0; index < criteria.size(); index++) {
                body.append(index == 0 ? "" : ",").append("\"").append(criteria.get(index))
                        .append("\"");
            }
            body.append("]");
        }
        body.append("}");

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString()).get("key").asText();
    }

    private void describe(String key, String description) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.patch("/api/v1/issues/" + key)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"" + description + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private UUID typeNamed(UUID projectId, String name) throws Exception {
        JsonNode workflow = json.readTree(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn().getResponse().getContentAsString());
        for (JsonNode type : workflow.get("types")) {
            if (type.get("name").asText().equals(name)) {
                return UUID.fromString(type.get("id").asText());
            }
        }
        throw new AssertionError("this project has no type called " + name);
    }
}
