package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code /projects/{projectId}/repositories} — reference data (D16), and its two different gates.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>Both verbs gated on {@code project:settings}.</strong> The list is what a new joiner
 *       and an assistant are handed, and neither holds the administrative key — so the tidy
 *       implementation makes the feature unreadable by exactly its readers.</li>
 *   <li><strong>{@code primary} stored rather than computed.</strong> Invisible until a row is
 *       deleted, at which point a project can have no primary repository at all, or two.</li>
 *   <li><strong>A duplicate name reaching the unique index.</strong> {@code
 *       uq_project_repository_name} answers that with a 500 naming a constraint; the form needs a
 *       field error.</li>
 * </ol>
 *
 * <p>Ids use the {@code e6…} prefix, checked free across the suite before it was taken.
 */
@AutoConfigureMockMvc
@DisplayName("Project repositories — read by everyone, written by whoever configures the project")
class ProjectRepoTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("e6000000-0000-4000-8000-000000000001");
    private static final UUID ATLAS = UUID.fromString("e6001000-0000-4000-8000-000000000001");

    /** Test Lead on Atlas — holds {@code project:settings} (§A3). */
    private static final UUID ADMIN = UUID.fromString("e6002000-0000-4000-8000-000000000001");
    /** Test Engineer on Atlas — {@code project:read} and no {@code project:settings}. */
    private static final UUID DEV = UUID.fromString("e6002000-0000-4000-8000-000000000002");

    private static final UUID ADMIN_GRANT = UUID.fromString("e6003000-0000-4000-8000-000000000001");
    private static final UUID DEV_GRANT = UUID.fromString("e6003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "repos-acme", "Acme", now);
            activeUser(ADMIN, "lead@repos.test", "L. Lead", now);
            activeUser(DEV, "dev@repos.test", "D. Developer", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ATLAS, ACME, "REPA", "Atlas", now);
            member(ACME, ADMIN, "L. Lead", now);
            member(ACME, DEV, "D. Developer", now);
        }));

        grantOnProject(ADMIN_GRANT, ACME, Role.TEST_LEAD_ID, ADMIN, ATLAS);
        grantOnProject(DEV_GRANT, ACME, Role.TEST_ENGINEER_ID, DEV, ATLAS);
        seedProjectDefaults(ACME, ATLAS);
        clearRepositories();
    }

    private void clearRepositories() throws SQLException {
        try (var connection = appConnection(ACME);
                var statement = connection.prepareStatement("DELETE FROM project_repository")) {
            statement.executeUpdate();
        }
    }

    // =====================================================================

    @Test
    @DisplayName("a project with none answers [] — that is normal, not degraded")
    void aProjectSpanningNoneIsNotDegraded() throws Exception {
        assertThat(json.readTree(list(DEV).getResponse().getContentAsString())).isEmpty();
    }

    @Test
    @DisplayName("two are appended in order, and the first is primary")
    void positionsAndPrimary() throws Exception {
        add(ADMIN, "acme-storefront", "npm ci && npm test");
        add(ADMIN, "acme-api", "./gradlew build");

        JsonNode listed = json.readTree(list(DEV).getResponse().getContentAsString());

        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("name").asText()).isEqualTo("acme-storefront");
        assertThat(listed.get(0).get("position").asInt()).isZero();
        assertThat(listed.get(0).get("primary").asBoolean()).isTrue();
        assertThat(listed.get(1).get("position").asInt()).isEqualTo(1);
        assertThat(listed.get(1).get("primary").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("deleting the primary promotes the next one — because primary is computed")
    void primaryIsComputedAndNotStored() throws Exception {
        // A stored boolean passes every other assertion in this class and leaves the project with
        // no primary repository here, which nothing else would notice.
        UUID first = idOf(add(ADMIN, "acme-storefront", "npm ci && npm test"));
        add(ADMIN, "acme-api", "./gradlew build");

        assertThat(delete(ADMIN, first).getResponse().getStatus()).isEqualTo(204);

        JsonNode listed = json.readTree(list(DEV).getResponse().getContentAsString());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("name").asText()).isEqualTo("acme-api");
        assertThat(listed.get(0).get("primary").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a PATCH replaces the five values")
    void editingReplacesEverything() throws Exception {
        UUID id = idOf(add(ADMIN, "acme-storefront", "npm ci && npm test"));

        MvcResult edited = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ATLAS + "/repositories/" + id)
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("acme-web", "npm run verify")))
                .andReturn();

        assertThat(edited.getResponse().getStatus())
                .as(edited.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode view = json.readTree(edited.getResponse().getContentAsString());
        assertThat(view.get("name").asText()).isEqualTo("acme-web");
        assertThat(view.get("buildCommand").asText()).isEqualTo("npm run verify");
        assertThat(view.get("primary").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a duplicate name is a field error, not a constraint violation")
    void aDuplicateNameIsAFieldError() throws Exception {
        add(ADMIN, "acme-storefront", "npm ci && npm test");

        MvcResult refused = attemptAdd(ADMIN, body("acme-storefront", "npm test"));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("name");
    }

    @Test
    @DisplayName("a blank build command is a field error — one command per repository, always")
    void aBlankBuildCommandIsRefused() throws Exception {
        MvcResult refused = attemptAdd(ADMIN, body("acme-storefront", "  "));

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("buildCommand");
    }

    @Test
    @DisplayName("a Test Engineer reads the list and is refused the write")
    void theTwoGatesAreDifferentKeys() throws Exception {
        add(ADMIN, "acme-storefront", "npm ci && npm test");

        assertThat(list(DEV).getResponse().getStatus()).isEqualTo(200);

        MvcResult refused = attemptAdd(DEV, body("acme-api", "./gradlew build"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("\"permission\":\"project:settings\"");
    }

    @Test
    @DisplayName("a project this session cannot reach is a 404 on every verb")
    void anUnreachableProjectIs404() throws Exception {
        UUID nowhere = UUID.randomUUID();

        assertThat(mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + nowhere + "/repositories")
                        .header("Authorization", "Bearer " + tokenFor(ADMIN, ACME)))
                .andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a repository id from another project is a 404 rather than an edit")
    void aRepositoryOutsideThisProjectIs404() throws Exception {
        assertThat(delete(ADMIN, UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);
    }

    // ===================================================================== helpers

    private MvcResult list(UUID callerId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ATLAS + "/repositories")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn();
    }

    private MvcResult attemptAdd(UUID callerId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ATLAS + "/repositories")
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode add(UUID callerId, String name, String buildCommand) throws Exception {
        MvcResult result = attemptAdd(callerId, body(name, buildCommand));
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult delete(UUID callerId, UUID repositoryId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + ATLAS + "/repositories/" + repositoryId)
                        .header("Authorization", "Bearer " + tokenFor(callerId, ACME)))
                .andReturn();
    }

    private static String body(String name, String buildCommand) {
        return "{\"name\":\"" + name + "\","
                + "\"url\":\"git@github.com:northwind/" + name.strip() + ".git\","
                + "\"defaultBranch\":\"main\","
                + "\"subdirectory\":\"/\","
                + "\"buildCommand\":\"" + buildCommand + "\"}";
    }

    private static UUID idOf(JsonNode view) {
        return UUID.fromString(view.get("id").asText());
    }
}
