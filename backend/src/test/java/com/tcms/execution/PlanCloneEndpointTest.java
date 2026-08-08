package com.tcms.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code POST /projects/{projectId}/plans/{planId}/clone} — plan §6v, decision round 3 #59.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A clone routed through the create endpoint.</strong>
 *       {@link Copying#theWholePlanComesAcross()} is the assertion the whole slice turns on.
 *       {@code TestPlanInput} carries six fields where a plan has twelve, so a clone built on it
 *       loses the tag arm, every §97 section, both prose fields and both criteria lists — and
 *       answers {@code 201} with a plan that reads as a faithful copy of something that never had
 *       any of it.</li>
 *   <li><strong>A clone born at version 2.</strong> {@link Copying#theCloneHasExactlyOneVersion()}.
 *       The cheap way to keep the sections is create-then-edit, and it gives every cloned plan a
 *       history saying somebody rewrote it a second after it was made — §6v rule 3 asks for its
 *       <em>own</em> version 1, not a v1 that never described anything real.</li>
 *   <li><strong>A clone of an APPROVED plan that is itself APPROVED.</strong>
 *       {@link Copying#theCloneIsADraft()} — §6g, the laundered sign-off.</li>
 *   <li><strong>A criteria list that makes a plan uncloneable.</strong>
 *       {@link Copying#theCriteriaAreCopiedVerbatim()}: they are copied as stored rather than
 *       re-validated, so a metric retired since the plan was written does not turn into a 400 about
 *       a field the caller never sent.</li>
 *   <li><strong>The gate widening by accident.</strong> {@link Gate#everyPlanCreatorAlsoHoldsPlanEdit()}
 *       derives from the <em>seeded catalogue</em> the assumption that lets one key authorise a
 *       write that normally costs two — so a future role separating them fails the build rather
 *       than silently acquiring an edit.</li>
 * </ol>
 *
 * <p>Ids are {@code 2a…} and the slug is {@code pcl-…}, both checked against every prefix and every
 * slug in {@code src/test/java} — §128 lost a class to a duplicate slug with unique UUIDs.
 */
@AutoConfigureMockMvc
@DisplayName("Cloning a test plan — the whole plan, its own version 1, and a DRAFT")
class PlanCloneEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("2a000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("2a000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("2a001000-0000-4000-8000-000000000001");
    /** Same company, LEAD only — the source of the sibling-project ids the scope must refuse. */
    private static final UUID ACME_MOBILE = UUID.fromString("2a001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("2a002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code plan:create}. */
    private static final UUID LEAD = UUID.fromString("2a003000-0000-4000-8000-000000000001");
    /** Test Engineer — also holds {@code plan:create} (roles spec §A4). */
    private static final UUID ENGINEER = UUID.fromString("2a003000-0000-4000-8000-000000000002");
    /** Viewer — reads the plans list and must be refused the create. THE persona of this class. */
    private static final UUID VIEWER = UUID.fromString("2a003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("2a003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("2a003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("2a004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("2a004000-0000-4000-8000-000000000002");
    private static final UUID SPARE = UUID.fromString("2a004000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_SUITE = UUID.fromString("2a004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("2a004000-0000-4000-8000-000000000005");

    private static final UUID ABSENT = UUID.fromString("2a00ffff-0000-4000-8000-00000000ffff");

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
    private TransactionTemplate tx;

    /** In REFUNDS, beneath PAYMENTS — so the folder's subtree covers it. */
    private UUID caseA;
    /** In SPARE, outside the PAYMENTS subtree. */
    private UUID caseB;
    /** In ACME_MOBILE — a live case in the caller's own company and the wrong project. */
    private UUID mobileCase;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "pcl-acme", "Acme", now);
            company(UMBRA, "pcl-umbra", "Umbra", now);

            activeUser(LEAD, "lead@pcl-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@pcl-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@pcl-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@pcl-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@pcl-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "PCLOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "PCLMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("pcl-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("pcl-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("pcl-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("pcl-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "PCLUOL", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("pcl-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        caseA = createCase(LEAD, ACME, ACME_BANKING, "Refund the whole captured amount", REFUNDS);
        caseB = createCase(LEAD, ACME, ACME_BANKING, "Partial refund rounds correctly", SPARE);
        mobileCase = createCase(LEAD, ACME, ACME_MOBILE, "Mobile login", MOBILE_SUITE);

        // The counter has been moved by the fixture's own case creates and by the suites; reset so
        // every TP-n assertion below is about this endpoint rather than about fixture order.
        resetPlanCounter();

        // Cleared LAST, so "a refusal writes no audit row" is about the request under test.
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
            }
        }
    }

    // =====================================================================

    /** The plan every test clones — full: sections, tags, prose and both criteria lists. */
    private UUID sourcePlan;

    private UUID seedRichPlan() throws Exception {
        MvcResult created = post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), null);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        UUID planId = UUID.fromString(body(created).get("id").asText());

        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("name", "Release 4.3");
        edit.put("description", "Everything shipping in 4.3.");
        edit.put("milestoneName", "4.3");
        edit.put("milestoneDate", "2026-09-01");
        edit.put("suiteIds", List.of(REFUNDS.toString()));
        edit.put("caseIds", List.of(caseA.toString()));
        edit.put("tags", List.of("regression"));
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("name", "Payments");
        section.put("caseIds", List.of(caseB.toString()));
        section.put("suiteIds", List.of());
        section.put("tags", List.of("smoke"));
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("name", "Not started");
        empty.put("caseIds", List.of());
        empty.put("suiteIds", List.of());
        empty.put("tags", List.of());
        edit.put("sections", List.of(section, empty));
        edit.put("scopeText", "The refund paths.");
        edit.put("outOfScopeText", "Anything behind the flag.");
        edit.put("entryCriteria", List.of(
                Map.of("metric", "cases_approved", "op", ">=", "value", 0.9)));
        edit.put("exitCriteria", List.of(
                Map.of("metric", "pass_rate", "op", ">=", "value", 0.95)));

        MvcResult updated = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/plans/" + planId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(edit)))
                .andReturn();
        assertThat(updated.getResponse().getStatus())
                .as("the fixture plan: %s", updated.getResponse().getContentAsString())
                .isEqualTo(200);
        return planId;
    }

    private MvcResult clonePlan(UUID userId, UUID companyId, UUID projectId, UUID planId,
            String bodyJson, String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/plans/" + planId + "/clone")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyJson);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode cloned(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("expected a created plan, got: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result);
    }

    /** A bare array of uuid strings — {@code ids} above reads objects with an {@code id}. */
    private static List<UUID> uuidsOf(JsonNode array) {
        List<UUID> values = new ArrayList<>();
        array.forEach(node -> values.add(UUID.fromString(node.asText())));
        return values;
    }

    private JsonNode planDetail(UUID planId) throws Exception {
        return body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + planId));
    }

    private int versionRowsOf(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT count(*) FROM test_plan_version WHERE plan_id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private void approvePlan(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "UPDATE test_plan SET status = 'APPROVED' WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            statement.executeUpdate();
        }
    }

    @Nested
    @DisplayName("The gate — plan:create, and 404 before 403")
    class Gate {

        @Test
        @DisplayName("a Viewer cannot clone a plan they can read")
        void aViewerCannotClone() throws Exception {
            sourcePlan = seedRichPlan();
            assertThat(clonePlan(VIEWER, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Copy\"}", null)
                    .getResponse().getStatus())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("a member with no grant gets 404, never 403")
        void membershipConveysNothing() throws Exception {
            sourcePlan = seedRichPlan();
            assertThat(clonePlan(NO_GRANT, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Copy\"}", null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a plan in a sibling project is a 404 through a granted project id")
        void theSecondPathSegmentIsGatedToo() throws Exception {
            sourcePlan = seedRichPlan();
            // The plan exists and the caller holds BOTH projects, so this is not a permission
            // question: it is whether the id in the second segment is checked against the first.
            assertThat(clonePlan(LEAD, ACME, ACME_MOBILE, sourcePlan, "{\"name\": \"Copy\"}", null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("EVERY role holding plan:create also holds plan:edit — derived, not assumed")
        void everyPlanCreatorAlsoHoldsPlanEdit() throws Exception {
            // The clone writes criteria and scope prose under plan:create alone. That is only
            // defensible while the two keys are held by the same roles, and this reads the SEEDED
            // CATALOGUE rather than restating V3 in a comment — a schema-wide invariant needs a
            // computed check, not a careful reading.
            try (Connection connection = appConnection(ACME);
                    var statement = connection.prepareStatement("""
                            SELECT rp.role_id FROM role_permission rp
                            WHERE rp.permission_key = 'plan:create'
                              AND NOT EXISTS (
                                SELECT 1 FROM role_permission rp2
                                WHERE rp2.role_id = rp.role_id
                                  AND rp2.permission_key = 'plan:edit')
                            """);
                    ResultSet rows = statement.executeQuery()) {

                assertThat(rows.next())
                        .as("a role that may create a plan and not edit one would gain, through "
                                + "clone, a write it was never granted — see PlanCloneService")
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("What comes across")
    class Copying {

        @Test
        @DisplayName("THE WHOLE PLAN COMES ACROSS — sections, tags, prose and both criteria lists")
        void theWholePlanComesAcross() throws Exception {
            sourcePlan = seedRichPlan();

            JsonNode copy = cloned(clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", null));
            JsonNode detail = planDetail(UUID.fromString(copy.get("id").asText()));

            assertThat(detail.get("name").asText()).isEqualTo("Release 4.4");
            assertThat(detail.get("description").asText()).isEqualTo("Everything shipping in 4.3.");
            assertThat(detail.get("milestoneName").asText()).isEqualTo("4.3");

            // The six a create-shaped clone drops silently.
            assertThat(uuidsOf(detail.get("suiteIds"))).containsExactly(REFUNDS);
            assertThat(uuidsOf(detail.get("caseIds"))).containsExactly(caseA);
            assertThat(detail.get("tags").get(0).asText()).isEqualTo("regression");
            assertThat(detail.get("sections")).hasSize(2);
            assertThat(detail.get("sections").get(0).get("name").asText()).isEqualTo("Payments");
            assertThat(detail.get("sections").get(1).get("name").asText())
                    .as("an EMPTY section survives too — it is a row that names itself and targets "
                            + "nothing, and dropping it deletes a heading somebody typed")
                    .isEqualTo("Not started");
            assertThat(detail.get("scopeText").asText()).isEqualTo("The refund paths.");
            assertThat(detail.get("outOfScopeText").asText())
                    .isEqualTo("Anything behind the flag.");
            assertThat(detail.get("entryCriteria")).hasSize(1);
            assertThat(detail.get("exitCriteria")).hasSize(1);
        }

        @Test
        @DisplayName("the criteria are copied verbatim rather than re-validated")
        void theCriteriaAreCopiedVerbatim() throws Exception {
            sourcePlan = seedRichPlan();

            JsonNode copy = cloned(clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", null));

            assertThat(criteriaColumnsOf(UUID.fromString(copy.get("id").asText())))
                    .as("the stored columns, byte for byte — a round trip through the request "
                            + "validator makes a plan uncloneable the day a metric is retired")
                    .isEqualTo(criteriaColumnsOf(sourcePlan));
        }

        @Test
        @DisplayName("THE CLONE HAS EXACTLY ONE VERSION, and it describes the clone")
        void theCloneHasExactlyOneVersion() throws Exception {
            sourcePlan = seedRichPlan();

            JsonNode copy = cloned(clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", null));
            UUID cloneId = UUID.fromString(copy.get("id").asText());

            // Two rows here is the create-then-edit shortcut, and it gives every cloned plan a
            // history saying somebody rewrote it a second after it was made.
            assertThat(versionRowsOf(cloneId)).isEqualTo(1);
            assertThat(copy.get("currentVersionNo").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("the clone is a DRAFT even when the source is APPROVED")
        void theCloneIsADraft() throws Exception {
            sourcePlan = seedRichPlan();
            approvePlan(sourcePlan);

            JsonNode copy = cloned(clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", null));

            assertThat(copy.get("status").asText()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("the clone gets its own key, and the source is untouched")
        void theSourceIsUntouched() throws Exception {
            sourcePlan = seedRichPlan();
            JsonNode before = planDetail(sourcePlan);

            JsonNode copy = cloned(clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", null));

            assertThat(copy.get("key").asText()).isNotEqualTo(before.get("key").asText());
            assertThat(planDetail(sourcePlan)).isEqualTo(before);
        }

        @Test
        @DisplayName("a blank name is refused by the edit validator's own rule")
        void theNameIsRequired() throws Exception {
            sourcePlan = seedRichPlan();
            long before = planCount(ACME);

            MvcResult refused = clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"  \"}", null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("name");
            assertThat(planCount(ACME)).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("Idempotency and provenance")
    class Trail {

        @Test
        @DisplayName("a retried intent clones once")
        void aRetriedIntentClonesOnce() throws Exception {
            sourcePlan = seedRichPlan();
            long before = planCount(ACME);

            MvcResult first = clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", "pi-1");
            MvcResult second = clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", "pi-1");

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(second).get("id").asText()).isEqualTo(body(first).get("id").asText());
            assertThat(planCount(ACME)).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("the provenance row names both keys, beside the created row")
        void theTrailSaysWhereItCameFrom() throws Exception {
            sourcePlan = seedRichPlan();
            JsonNode source = planDetail(sourcePlan);

            JsonNode copy = cloned(clonePlan(
                    LEAD, ACME, ACME_BANKING, sourcePlan, "{\"name\": \"Release 4.4\"}", null));

            Map<String, String> actions = auditActions(ACME);
            assertThat(actions).containsKey("plan.cloned");
            assertThat(actions.get("plan.cloned"))
                    .contains(source.get("key").asText())
                    .contains(copy.get("key").asText());
            assertThat(actions)
                    .as("a second row, not a rename — every existing audit reader knows plan.created")
                    .containsKey("plan.created");
        }
    }

    private Map<String, Object> plan(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", null);
        payload.put("milestoneName", null);
        payload.put("milestoneDate", null);
        payload.put("suiteIds", List.of());
        payload.put("caseIds", List.of());
        return payload;
    }

    private MvcResult post(
            UUID userId, UUID companyId, UUID projectId, Map<String, Object> payload,
            String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1" + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<UUID> ids(JsonNode array) {
        List<UUID> ids = new ArrayList<>();
        array.forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }

    private Map<String, List<String>> errorsOf(MvcResult result) throws Exception {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        JsonNode node = body(result).get("errors");
        if (node == null) {
            return errors;
        }
        node.properties().forEach(field -> {
            List<String> messages = new ArrayList<>();
            field.getValue().forEach(message -> messages.add(message.asText()));
            errors.put(field.getKey(), messages);
        });
        return errors;
    }

    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by PlanCreateEndpointTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", "Do the thing");
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("sharedStepId", null);
        payload.put("steps", List.of(step));
        payload.put("changeComment", null);

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    // =====================================================================
    // helpers — fixture
    // =====================================================================

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void activeUser(UUID id, String email, String canonicalName, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, canonicalName, now);
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

    private void grant(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("plan-create:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    /** Everything a test can write, in foreign-key order, through each company's own context. */
    private void clearMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM defect_link");
                execute(connection, "DELETE FROM step_result");
                execute(connection, "DELETE FROM test_execution");
                execute(connection, "DELETE FROM test_run");
                execute(connection, "DELETE FROM plan_scope_item");
                execute(connection, "DELETE FROM test_plan_version");
                execute(connection, "DELETE FROM test_plan");
                execute(connection, "DELETE FROM test_case_version");
                execute(connection, "DELETE FROM test_step");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite_version");
                execute(connection, "DELETE FROM suite");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void resetPlanCounter() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection,
                        "UPDATE key_sequence SET next_value = 1 WHERE entity_type = 'PLAN'");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1");
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2, "Refunds", "s1.s2");
            suite(connection, SPARE, ACME, ACME_BANKING, null, "TS-3", 3, "Spare", "s3");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-4", 4, "Mobile", "s4");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1");
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back through the app role, past the API
    // =====================================================================

    private long planCount(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM test_plan");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private long scopeItemCount(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM plan_scope_item WHERE plan_id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private List<String> criteriaColumnsOf(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT CAST(entry_criteria AS text), CAST(exit_criteria AS text) "
                             + "FROM test_plan WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                List<String> columns = new ArrayList<>();
                columns.add(rows.getString(1));
                columns.add(rows.getString(2));
                return columns;
            }
        }
    }

    private UUID companyOfPlan(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT company_id FROM test_plan WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private Map<String, String> auditActions(UUID companyId) throws SQLException {
        Map<String, String> events = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT action, CAST(changes AS text) FROM audit_event ORDER BY occurred_at");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.put(rows.getString(1), rows.getString(2));
            }
        }
        return events;
    }

    private void softDeleteCase(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            statement.executeUpdate();
        }
    }
}
