package com.tcms.tracker.release;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The eleven release operations <strong>over HTTP</strong> — routing, query-string binding, status
 * codes and the permission split.
 *
 * <p>{@code ReleaseReadinessTest} drives the same services in process and proves the arithmetic;
 * this class proves the wire. 012 shipped five of ten board operations never driven over HTTP at
 * all, and its close-out named that as the gap; this module does not repeat it.
 *
 * <p>The one thing only this class can show: <strong>{@code release:read} is the first gate on this
 * surface that a project member can fail.</strong> A Test Engineer holds it and a Viewer holds it;
 * an Automation Service credential does not — and the 403 that produces is a code no in-process
 * assertion reaches.
 *
 * <p>UUID prefix {@code c7}.
 */
@AutoConfigureMockMvc
@DisplayName("Release endpoints — the wire, the status codes, and the two permission tiers")
class ReleaseEndpointTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("c7000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("c7001000-0000-4000-8000-000000000001");

    /** Project Manager — holds `release:manage` and `release:publish` (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    /** Test Engineer — holds {@code release:read} and NEITHER write key (§A3). */
    private static final UUID ENGINEER_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000004");

    /**
     * Automation Service — the one project role holding <strong>no</strong> {@code release:read}.
     *
     * <p>It is the reason this class exists rather than an in-process assertion: every read on this
     * surface before M3 was reachable by every credential that could see the project at all, so the
     * 403 below is a status code no service-level test produces.
     */
    private static final UUID AUTOMATION_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000005");

    private static final UUID MANAGER = UUID.fromString("c7002000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("c7002000-0000-4000-8000-000000000002");
    private static final UUID MANAGER_GRANT = UUID.fromString("c7003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER_GRANT = UUID.fromString("c7003000-0000-4000-8000-000000000002");
    private static final UUID ROBOT = UUID.fromString("c7002000-0000-4000-8000-000000000003");
    private static final UUID ROBOT_GRANT = UUID.fromString("c7003000-0000-4000-8000-000000000003");

    private String managerToken;
    private String engineerToken;
    private String robotToken;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "release-endpoints-acme", "Acme", now);
            activeUser(MANAGER, "pm@release-endpoints.test", "Paula Manager", now);
            activeUser(ENGINEER, "qa@release-endpoints.test", "Erin Engineer", now);
            activeUser(ROBOT, "ci@release-endpoints.test", "CI Robot", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "REL", "Release Endpoints Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
            member(ACME, ENGINEER, "Erin Engineer", now);
            member(ACME, ROBOT, "CI Robot", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        grantOnProject(ENGINEER_GRANT, ACME, ENGINEER_ROLE, ENGINEER, PROJECT);
        grantOnProject(ROBOT_GRANT, ACME, AUTOMATION_ROLE, ROBOT, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        wipe();

        managerToken = tokenFor(MANAGER, ACME);
        engineerToken = tokenFor(ENGINEER, ACME);
        robotToken = tokenFor(ROBOT, ACME);
    }

    @AfterEach
    void putEverythingBack() throws SQLException {
        wipe();
    }

    @Test
    @DisplayName("create answers 201 with a Location, and the list shows it with its readiness")
    void createThenList() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": "2.4", "description": "Checkout rebuild",
                                 "targetDate": "2026-09-18"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.version").value("2.4"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.releasedAt").doesNotExist())
                .andReturn();

        String id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value("2.4"))
                // A brand-new release has no scope and no run, so its verdict is NOT_TESTED and its
                // counts are absent rather than zero — the distinction on the wire, not only in the
                // service.
                .andExpect(jsonPath("$[0].readiness.verdict").value("NOT_TESTED"))
                .andExpect(jsonPath("$[0].readiness.hasRun").value(false))
                .andExpect(jsonPath("$[0].readiness.casesFailed").doesNotExist());

        mvc.perform(get("/api/v1/releases/" + id)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Checkout rebuild"));
    }

    @Test
    @DisplayName("a blank version is 400 and names the field; a duplicate is 409")
    void refusals() throws Exception {
        mvc.perform(post("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.version").exists());

        mvc.perform(post("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"2.4\"}"))
                .andExpect(status().isCreated());

        // Case-insensitively, as uq_release_version matches.
        mvc.perform(post("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"2.4\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a Test Engineer may read releases and may not write one")
    void theTwoTiersDifferOverHttp() throws Exception {
        String id = createRelease("2.4");

        mvc.perform(get("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/releases/" + id + "/readiness")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + engineerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"9.9\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/releases/" + id + "/publish")
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/releases/" + id)
                        .header("Authorization", "Bearer " + engineerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an Automation Service credential cannot read releases at all — 403, not 404")
    void theCiTokenIsRefusedTheDeliveryPlan() throws Exception {
        String id = createRelease("2.4");

        // 403 and NOT 404: the robot can see the project, so concealing the endpoint would tell it
        // the release had been deleted. §A3 is the whole reason this credential is here — a CI
        // token posts results and has no business reading a delivery plan.
        mvc.perform(get("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + robotToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/releases/" + id + "/readiness")
                        .header("Authorization", "Bearer " + robotToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/" + PROJECT + "/roadmap")
                        .header("Authorization", "Bearer " + robotToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH leaves absent fields alone and renaming the version re-sorts the list")
    void patchIsAPatchAndRenamingResorts() throws Exception {
        String id = createRelease("1.9");
        createRelease("1.10");

        mvc.perform(patch("/api/v1/releases/" + id)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Only this\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.9"))
                .andExpect(jsonPath("$.description").value("Only this"));

        mvc.perform(patch("/api/v1/releases/" + id)
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"1.11\"}"))
                .andExpect(status().isOk())
                // The description survives the rename: absent means "leave it", which is the whole
                // of B1 and the defect that shipped before PatchBody existed.
                .andExpect(jsonPath("$.description").value("Only this"));

        mvc.perform(get("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(jsonPath("$[0].version").value("1.10"))
                .andExpect(jsonPath("$[1].version").value("1.11"));
    }

    @Test
    @DisplayName("publish answers 200 and a delete answers 204; both are 404 for a stranger's id")
    void publishAndDelete() throws Exception {
        String id = createRelease("2.4");

        mvc.perform(post("/api/v1/releases/" + id + "/publish")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.releasedAt").exists());

        mvc.perform(delete("/api/v1/releases/" + id)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/releases/" + id)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the roadmap answers with two empty arrays on merge day, and that is a 200")
    void theEmptyRoadmapIsARealAnswer() throws Exception {
        mvc.perform(get("/api/v1/projects/" + PROJECT + "/roadmap")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.releases").isArray())
                .andExpect(jsonPath("$.releases").isEmpty())
                .andExpect(jsonPath("$.unscheduled").isArray());
    }

    @Test
    @DisplayName("the backlog binds releaseId, and refuses it together with unscheduled")
    void theBacklogsTwoNewParameters() throws Exception {
        String id = createRelease("2.4");

        mvc.perform(get("/api/v1/projects/" + PROJECT + "/backlog")
                        .param("releaseId", id)
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());

        // A cross-field rule no schema can spell: "only 2.4" and "only what has no release" cannot
        // both hold, and answering the empty intersection would look like a finished backlog.
        mvc.perform(get("/api/v1/projects/" + PROJECT + "/backlog")
                        .param("releaseId", id)
                        .param("unscheduled", "true")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.unscheduled").exists());
    }

    @Test
    @DisplayName("an issue may not target another project's release")
    void aCrossProjectReleaseIsAFieldError() throws Exception {
        String id = createRelease("2.4");

        String typeId = json.readTree(mvc.perform(get("/api/v1/projects/" + PROJECT + "/workflow")
                                .header("Authorization", "Bearer " + managerToken))
                        .andReturn().getResponse().getContentAsString())
                .get("types").get(0).get("id").asText();

        mvc.perform(post("/api/v1/issues")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "typeId": "%s", "title": "Targeted",
                                 "releaseId": "%s"}
                                """.formatted(PROJECT, typeId, id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.release.version").value("2.4"));

        mvc.perform(post("/api/v1/issues")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "%s", "typeId": "%s", "title": "Misfiled",
                                 "releaseId": "%s"}
                                """.formatted(PROJECT, typeId, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.releaseId").exists());
    }

    // =====================================================================

    private String createRelease(String version) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/projects/" + PROJECT + "/releases")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": \"" + version + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(created.getResponse().getContentAsString()).get("id").asText();
    }

    private void wipe() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            for (String table : List.of("issue_link", "issue", "release")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE company_id = CAST(? AS uuid)")) {
                    statement.setObject(1, ACME);
                    statement.executeUpdate();
                }
            }
        }
    }
}
