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
 * {@code POST /projects/{projectId}/runs/{runId}/clone} — plan §6v, decision round 3 #59, and the
 * clone the plan calls the highest-value of the three.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A clone that brings the results with it.</strong>
 *       {@link Copying#theClonesChecklistIsUntested()}. Nothing reachable from a create request
 *       names a result, so this is really an assertion that the clone did not grow its own write
 *       path — which is exactly what a "copy the run" implementation would do.</li>
 *   <li><strong>An inherited approval override.</strong>
 *       {@link Gating#theSourcesOverrideIsNotInherited()} is the assertion the design turns on: the
 *       override is a statement about a request, nothing stores it, and a clone that carried it
 *       forward would turn one documented 2am exception into a permanent exemption that no screen
 *       shows and no audit row explains.</li>
 *   <li><strong>A clone that re-resolves the scope.</strong>
 *       {@link Copying#theScopeIsTheResolvedSetAndNotTheArms()}. {@code test_run} stores no scope,
 *       so passing a suite arm as well as the resolved cases would silently widen the clone past
 *       what the source covered — and it would look like a feature.</li>
 *   <li><strong>An assignee invented out of a heterogeneous checklist.</strong>
 *       {@link Copying#anOmittedAssigneeMeansUnassigned()}.</li>
 * </ol>
 *
 * <p>Ids are {@code 3a…} and the slug is {@code rcl-…}, both checked against every prefix and slug
 * in {@code src/test/java}.
 */
@AutoConfigureMockMvc
@DisplayName("Cloning a test run — the same cases, none of the answers")
class RunCloneEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("3a000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("3a000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("3a001000-0000-4000-8000-000000000001");
    private static final UUID ACME_MOBILE = UUID.fromString("3a001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("3a002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("3a003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("3a003000-0000-4000-8000-000000000002");
    /** Viewer — the only role that does NOT hold {@code run:create}. */
    private static final UUID VIEWER = UUID.fromString("3a003000-0000-4000-8000-000000000003");
    private static final UUID NO_GRANT = UUID.fromString("3a003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("3a003000-0000-4000-8000-000000000005");
    /** Automation Service — holds {@code run:create} and {@code result:submit} and nothing else. */
    private static final UUID ROBOT = UUID.fromString("3a003000-0000-4000-8000-000000000006");

    private static final UUID PAYMENTS = UUID.fromString("3a004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("3a004000-0000-4000-8000-000000000002");
    private static final UUID SPARE = UUID.fromString("3a004000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_SUITE = UUID.fromString("3a004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("3a004000-0000-4000-8000-000000000005");

    private static final UUID ABSENT = UUID.fromString("3a00ffff-0000-4000-8000-00000000ffff");

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

    /** In REFUNDS, beneath PAYMENTS. Edited to v2 by one test, before its run is created. */
    private UUID caseA;
    /** In SPARE, outside the PAYMENTS subtree. */
    private UUID caseB;
    private UUID mobileCase;

    /** Scope: the PAYMENTS folder (covering caseA) and caseB directly — two distinct cases. */
    private UUID plan;
    /** In ACME_MOBILE. Named from ACME_BANKING's path it must be a field error, not a 404. */
    private UUID mobilePlan;
    /** A plan whose scope is empty — the run that would have nothing to execute. */
    private UUID emptyPlan;

    private UUID defaultConfiguration;
    private UUID mobileConfiguration;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "rcl-acme", "Acme", now);
            company(UMBRA, "rcl-umbra", "Umbra", now);

            activeUser(LEAD, "lead@rcl-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@rcl-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@rcl-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@rcl-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@rcl-umbra.test", "O. Outsider", now);
            activeUser(ROBOT, "ci@rcl-acme.test", "CI", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "RCLOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "RCLMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ROBOT, "CI", now);

            grant("rcl-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("rcl-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("rcl-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("rcl-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
            grant("rcl-robot", ACME, Role.AUTOMATION_SERVICE_ID, ROBOT, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "RCLUOL", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("rcl-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        caseA = createCase(LEAD, ACME, ACME_BANKING, "Refund the whole captured amount", REFUNDS);
        caseB = createCase(LEAD, ACME, ACME_BANKING, "Partial refund rounds correctly", SPARE);

        // What PAYMENTS' tag rule claims (§98) — caseA only. caseB stays in SPARE and untagged,
        // so the two scope arms answer different sets and a run scoped on the suite is not the
        // same list as a run scoped on the plan's cases.
        tagCase(caseA, "refunds");
        mobileCase = createCase(LEAD, ACME, ACME_MOBILE, "Mobile login", MOBILE_SUITE);

        // The plans are authored through the REAL POST /plans, so this class's fixture is checked
        // against the other half of the same slice rather than against SQL nobody else runs.
        plan = createPlan(ACME_BANKING, "Release 4.3",
                List.of(PAYMENTS), List.of(caseB));
        mobilePlan = createPlan(ACME_MOBILE, "Mobile 1.0", List.of(MOBILE_SUITE), List.of());
        emptyPlan = createPlan(ACME_BANKING, "Not scoped yet", List.of(), List.of());

        defaultConfiguration = configurationOf(ACME_BANKING);
        mobileConfiguration = configurationOf(ACME_MOBILE);

        resetRunCounter();

        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
            }
        }
    }

    // =====================================================================

    private UUID sourceRun(UUID actor, Map<String, Object> payload) throws Exception {
        MvcResult created = post(actor, ACME, ACME_BANKING, payload, null);
        assertThat(created.getResponse().getStatus())
                .as("the fixture run: %s", created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private MvcResult cloneRun(UUID userId, UUID companyId, UUID projectId, UUID runId,
            Map<String, Object> payload, String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/runs/" + runId + "/clone")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private static Map<String, Object> named(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        return payload;
    }

    private JsonNode clonedRun(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("expected a created run, got: %s", result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result);
    }

    private List<UUID> caseIdsOfRun(UUID runId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT case_id FROM test_execution WHERE run_id = CAST(? AS uuid) "
                                + "ORDER BY case_id")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }


    /** How many executions a run holds — the checklist's length (B7). */
    private long executionCountOf(UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM test_execution WHERE run_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
            }
        }
    }

    /**
     * Erases the recorded request, which is what a run created before {@code V61} carries.
     *
     * <p>Written in SQL because no endpoint can produce that state any more: the column is
     * {@code updatable = false} and every create fills it. {@code Company.suspend()}'s shape — a
     * rule enforced for rows the product can no longer author.
     */
    private void clearRecordedScope(UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "UPDATE test_run SET scope_request = NULL WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.executeUpdate();
        }
    }

    private long recordedExecutions(UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM test_execution WHERE run_id = CAST(? AS uuid) "
                                + "AND (status_id IS NOT NULL OR executed_at IS NOT NULL)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
            }
        }
    }

    private long assignedExecutions(UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM test_execution WHERE run_id = CAST(? AS uuid) "
                                + "AND assignee_id IS NOT NULL")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : -1;
            }
        }
    }

    /**
     * B7 — what the run was asked for, and re-asking it.
     *
     * <h2>The two questions a run could not answer</h2>
     *
     * <p>{@code test_run} kept {@code plan_id} and nothing else about the request, so "why is this
     * case in this run" had no answer at all — named directly, filed in a suite that was named, or
     * carrying a tag that was, are indistinguishable once the executions exist. And a clone could
     * only re-run the materialised list, so <em>run the smoke suite again</em> silently omitted
     * every case filed into that suite since.
     *
     * <p>Materialisation-once is untouched: a run's contents are still its executions, pinned, and
     * nothing re-resolves them. What {@code rescope} does is ask for a NEW run on the same terms.
     */
    @Nested
    @DisplayName("The recorded scope, and cloning by it (B7)")
    class RecordedScope {

        @Test
        @DisplayName("the run records the arms it was asked for, not the cases they resolved to")
        void theRequestIsRecorded() throws Exception {
            UUID source = sourceRun(LEAD, onScope("Smoke", List.of(), List.of(PAYMENTS),
                    List.of("refunds")));

            JsonNode scope = body(runDetail(source)).get("scopeRequest");

            assertThat(scope.isNull()).isFalse();
            assertThat(scope.get("suiteIds")).hasSize(1);
            assertThat(scope.get("tags")).hasSize(1);
            // The whole distinction: the checklist has cases, and the request named none. A
            // recording taken from `edit.caseIds()` would put the resolved set here and answer the
            // question with the same list it was asked about.
            assertThat(scope.get("caseIds")).isEmpty();
        }

        @Test
        @DisplayName("a plan-scoped run records the plan, and an ad-hoc one records null for it")
        void thePlanIsPartOfTheRequest() throws Exception {
            UUID fromPlan = sourceRun(LEAD, fromPlan("From the plan"));
            UUID adHoc = sourceRun(LEAD, onCases("Ad hoc", List.of(caseA)));

            assertThat(body(runDetail(fromPlan)).get("scopeRequest").get("planId").asText())
                    .isEqualTo(plan.toString());
            assertThat(body(runDetail(adHoc)).get("scopeRequest").get("planId").isNull()).isTrue();
        }

        @Test
        @DisplayName("rescope re-resolves the arms, so a case added since is on the new checklist")
        void rescopeReResolves() throws Exception {
            // A run over everything tagged `refunds` — caseA today.
            UUID source = sourceRun(LEAD, onScope("Smoke", List.of(), List.of(), List.of("refunds")));
            assertThat(executionCountOf(source)).isOne();

            // Somebody tags a second case afterwards. The ORDINARY clone must not pick it up (the
            // source's checklist is what a re-run of that pass means) and the rescoped one must.
            tagCase(caseB, "refunds");

            Map<String, Object> asIs = named("As it was");
            UUID plain = UUID.fromString(body(cloneRun(
                    LEAD, ACME, ACME_BANKING, source, asIs, null)).get("id").asText());
            assertThat(executionCountOf(plain)).isOne();

            Map<String, Object> again = named("The suite again");
            again.put("rescope", true);
            UUID rescoped = UUID.fromString(body(cloneRun(
                    LEAD, ACME, ACME_BANKING, source, again, null)).get("id").asText());

            assertThat(executionCountOf(rescoped)).isEqualTo(2);
        }

        @Test
        @DisplayName("rescope on a run with no recorded request is refused, never downgraded")
        void rescopeNeedsARequest() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA)));
            // What a run created before V61 looks like: the column is null.
            clearRecordedScope(source);

            Map<String, Object> again = named("Again");
            again.put("rescope", true);
            MvcResult refused = cloneRun(LEAD, ACME, ACME_BANKING, source, again, null);

            // A silent fallback to the ordinary clone would answer a different question and look
            // identical afterwards, which is the one outcome worth refusing over.
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("rescope")).isTrue();
        }

        @Test
        @DisplayName("a clone that does not ask for it still re-runs the materialised list")
        void theDefaultIsUnchanged() throws Exception {
            UUID source = sourceRun(LEAD, onScope("Smoke", List.of(), List.of(), List.of("refunds")));
            tagCase(caseB, "refunds");

            UUID clone = UUID.fromString(body(cloneRun(
                    LEAD, ACME, ACME_BANKING, source, named("Copy"), null)).get("id").asText());

            assertThat(executionCountOf(clone)).isOne();
        }
    }

    @Nested
    @DisplayName("The gate — run:create, and 404 before 403")
    class Gate {

        @Test
        @DisplayName("a Viewer cannot clone a run they can read")
        void aViewerCannotClone() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));

            assertThat(cloneRun(VIEWER, ACME, ACME_BANKING, source, named("Copy"), null)
                    .getResponse().getStatus())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("Automation Service CAN clone — it may open a run, and a clone is a run")
        void theRobotMayClone() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));

            // Deliberately not narrowed. A CI credential already holds run:create (§A6); inventing
            // a stricter key here would let the smallest role make a checklist from scratch and not
            // from the one it ran yesterday. Recording verdicts still costs run:execute (§124).
            assertThat(cloneRun(ROBOT, ACME, ACME_BANKING, source, named("Nightly again"), null)
                    .getResponse().getStatus())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a member with no grant gets 404, never 403")
        void membershipConveysNothing() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));

            assertThat(cloneRun(NO_GRANT, ACME, ACME_BANKING, source, named("Copy"), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a run in a sibling project is a 404 through a granted project id")
        void theSecondPathSegmentIsGatedToo() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));

            assertThat(cloneRun(LEAD, ACME, ACME_MOBILE, source, named("Copy"), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("What comes across, and what cannot")
    class Copying {

        @Test
        @DisplayName("THE CLONE'S CHECKLIST IS THE SAME CASES AND NONE OF THE ANSWERS")
        void theClonesChecklistIsUntested() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));

            JsonNode copy = clonedRun(
                    cloneRun(LEAD, ACME, ACME_BANKING, source, named("Regression, build 42"), null));
            UUID cloneId = UUID.fromString(copy.get("id").asText());

            assertThat(caseIdsOfRun(cloneId)).isEqualTo(caseIdsOfRun(source));
            assertThat(recordedExecutions(cloneId))
                    .as("a cloned run starts untested — every verdict, every timestamp, gone")
                    .isZero();
            assertThat(copy.get("key").asText())
                    .isNotEqualTo(body(get(LEAD, ACME,
                            "/projects/" + ACME_BANKING + "/runs/" + source)).get("key").asText());
        }

        @Test
        @DisplayName("the release version and build number are overridable, and fall back otherwise")
        void theBuildIsOverridable() throws Exception {
            Map<String, Object> payload = onCases("Regression", List.of(caseA, caseB));
            payload.put("releaseVersion", "4.3.0");
            payload.put("buildNumber", "41");
            UUID source = sourceRun(LEAD, payload);

            Map<String, Object> rebuild = named("Regression, build 42");
            rebuild.put("buildNumber", "42");
            JsonNode copy = clonedRun(cloneRun(LEAD, ACME, ACME_BANKING, source, rebuild, null));

            assertThat(copy.get("buildNumber").asText()).isEqualTo("42");
            assertThat(copy.get("releaseVersion").asText())
                    .as("omitted means the source's — a re-run is usually the same release")
                    .isEqualTo("4.3.0");
        }

        @Test
        @DisplayName("§177: the commit and branch are NOT inherited — the one fallback that isn't")
        void theGitCoordinateIsNotInherited() throws Exception {
            Map<String, Object> payload = onCases("Regression", List.of(caseA, caseB));
            payload.put("releaseVersion", "4.3.0");
            payload.put("commitSha", "4f9c2b1e8a7d");
            payload.put("branch", "release/4.3");
            UUID source = sourceRun(LEAD, payload);

            JsonNode copy = clonedRun(
                    cloneRun(LEAD, ACME, ACME_BANKING, source, named("Re-run"), null));

            // The three overrides above fall back because a re-run is against the same release until
            // the caller says otherwise. A git coordinate names the code that RAN, and a fresh clone
            // has run nothing — inheriting it would ship a run claiming a commit it never tested,
            // uncorrectably, since a run has no edit endpoint. `TestRunCloneInput` therefore declares
            // no override for either: there is nothing to override.
            assertThat(copy.get("commitSha").isNull()).isTrue();
            assertThat(copy.get("branch").isNull()).isTrue();
            assertThat(column(UUID.fromString(copy.get("id").asText()), "commit_sha"))
                    .as("in the row, not merely absent from the response")
                    .isNull();

            // And the source keeps its own — the clone is not a move.
            assertThat(column(source, "commit_sha")).isEqualTo("4f9c2b1e8a7d");
        }

        @Test
        @DisplayName("the scope is the RESOLVED case set, not the arms the source declared")
        void theScopeIsTheResolvedSetAndNotTheArms() throws Exception {
            // The source is scoped by a SUITE. test_run stores no scope, so the clone can only
            // carry what was materialised — and a clone that re-sent the suite arm would pick up
            // cases authored since, silently, which is a template and not a clone (§6v).
            UUID source = sourceRun(LEAD,
                    onScope("By folder", List.of(), List.of(REFUNDS), List.of()));
            List<UUID> covered = caseIdsOfRun(source);
            assertThat(covered).isNotEmpty();

            UUID extra = createCase(LEAD, ACME, ACME_BANKING, "Authored after the run", REFUNDS);

            JsonNode copy = clonedRun(
                    cloneRun(LEAD, ACME, ACME_BANKING, source, named("Same folder, later"), null));

            assertThat(caseIdsOfRun(UUID.fromString(copy.get("id").asText())))
                    .isEqualTo(covered)
                    .doesNotContain(extra);
        }

        @Test
        @DisplayName("an omitted assignee means an UNASSIGNED checklist — a run has none to copy")
        void anOmittedAssigneeMeansUnassigned() throws Exception {
            Map<String, Object> payload = onCases("Regression", List.of(caseA, caseB));
            payload.put("assigneeId", ENGINEER.toString());
            UUID source = sourceRun(LEAD, payload);
            assertThat(assignedExecutions(source)).isEqualTo(2);

            JsonNode copy = clonedRun(
                    cloneRun(LEAD, ACME, ACME_BANKING, source, named("Unowned copy"), null));

            // The assignee is a column on test_execution, one per case, so a checklist can name two
            // hundred different people. Picking one to be "the run's" would invent a fact.
            assertThat(assignedExecutions(UUID.fromString(copy.get("id").asText()))).isZero();
        }

        @Test
        @DisplayName("a blank name is refused by the create path's own rule")
        void theNameIsRequired() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));
            long before = runCount(ACME);

            assertThat(cloneRun(LEAD, ACME, ACME_BANKING, source, named("  "), null)
                    .getResponse().getStatus())
                    .isEqualTo(400);
            assertThat(runCount(ACME)).isEqualTo(before);
        }

        @Test
        @DisplayName("a retried intent clones once, and materialises no second checklist")
        void aRetriedIntentClonesOnce() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));
            long executionsBefore = executionCount(ACME);

            MvcResult first = cloneRun(
                    LEAD, ACME, ACME_BANKING, source, named("Build 42"), "ri-1");
            MvcResult second = cloneRun(
                    LEAD, ACME, ACME_BANKING, source, named("Build 42"), "ri-1");

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(executionCount(ACME))
                    .as("a second checklist would split one tester's results across two runs")
                    .isEqualTo(executionsBefore + 2);
        }

        @Test
        @DisplayName("the provenance row names both keys, beside the created row")
        void theTrailSaysWhereItCameFrom() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));
            String sourceKey = body(get(LEAD, ACME,
                    "/projects/" + ACME_BANKING + "/runs/" + source)).get("key").asText();

            JsonNode copy = clonedRun(
                    cloneRun(LEAD, ACME, ACME_BANKING, source, named("Build 42"), null));

            Map<String, String> actions = auditActions(ACME);
            assertThat(actions).containsKey("run.cloned");
            assertThat(actions.get("run.cloned"))
                    .contains(sourceKey)
                    .contains(copy.get("key").asText());
            assertThat(actions).containsKey("run.created");
        }
    }

    @Nested
    @DisplayName("§6g's gate is re-evaluated, and the override is not inherited")
    class Gating {

        @Test
        @DisplayName("THE SOURCE'S OVERRIDE IS NOT INHERITED — the clone is refused")
        void theSourcesOverrideIsNotInherited() throws Exception {
            gating(ACME_BANKING, true);

            Map<String, Object> payload = onCases("Hotfix at 2am", List.of(caseA, caseB));
            payload.put("overrideApprovalGate", true);
            UUID source = sourceRun(LEAD, payload);

            MvcResult refused = cloneRun(
                    LEAD, ACME, ACME_BANKING, source, named("Hotfix again"), null);

            // The whole design. Nothing on test_run stores the override, so it cannot be inherited
            // by accident — this pins that nobody adds a column or reads the source's audit row to
            // "be helpful", which would turn one documented exception into a standing one.
            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(refused)).contains("only runs approved test cases");
        }

        @Test
        @DisplayName("and the same clone succeeds when the caller overrides again, on the record")
        void theCallerMayOverrideAgain() throws Exception {
            gating(ACME_BANKING, true);

            Map<String, Object> payload = onCases("Hotfix at 2am", List.of(caseA, caseB));
            payload.put("overrideApprovalGate", true);
            UUID source = sourceRun(LEAD, payload);

            Map<String, Object> again = named("Hotfix again");
            again.put("overrideApprovalGate", true);

            assertThat(cloneRun(LEAD, ACME, ACME_BANKING, source, again, null)
                    .getResponse().getStatus())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a clone of an ungated run is refused once its project turns gating on")
        void theGateIsReEvaluated() throws Exception {
            UUID source = sourceRun(LEAD, onCases("Regression", List.of(caseA, caseB)));
            gating(ACME_BANKING, true);

            // The run was legal when it was made; the policy changed underneath it. A clone is a
            // new run and answers to today's policy, which is what makes it a create rather than a
            // copy of a row.
            assertThat(cloneRun(LEAD, ACME, ACME_BANKING, source, named("Copy"), null)
                    .getResponse().getStatus())
                    .isEqualTo(409);
        }
    }

    private Map<String, Object> fromPlan(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("planId", plan == null ? null : plan.toString());
        payload.put("configurationId", null);
        payload.put("releaseVersion", null);
        payload.put("buildNumber", null);
        payload.put("caseIds", List.of());
        payload.put("assigneeId", null);
        return payload;
    }

    private Map<String, Object> onCases(String name, List<UUID> caseIds) {
        return onScope(name, caseIds, List.of(), List.of());
    }

    /** §99's three arms, with no plan — the ad-hoc run. */
    private Map<String, Object> onScope(
            String name, List<UUID> caseIds, List<UUID> suiteIds, List<String> tags) {

        Map<String, Object> payload = fromPlan(name);
        payload.put("planId", null);
        payload.put("caseIds", caseIds.stream().map(UUID::toString).toList());
        payload.put("suiteIds", suiteIds.stream().map(UUID::toString).toList());
        payload.put("tags", tags);
        return payload;
    }

    /** The cases FILED in one suite — {@code Scope}'s non-vacuity check, and nothing else. */
    private List<UUID> filedIn(UUID suiteId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM test_case "
                             + "WHERE suite_id = CAST(? AS uuid) AND deleted_at IS NULL")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    private MvcResult post(
            UUID userId, UUID companyId, UUID projectId, Map<String, Object> payload,
            String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    /** The run's own detail, as the SPA reads it. */
    private MvcResult runDetail(UUID runId) throws Exception {
        return get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + runId);
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

    private JsonNode firstExecution(UUID runId) throws Exception {
        JsonNode detail = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + runId));
        String executionId = detail.get("executions").get(0).get("id").asText();
        return body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/runs/" + runId
                + "/executions/" + executionId));
    }

    private String detailOf(MvcResult result) throws Exception {
        return body(result).get("detail").asText();
    }

    /**
     * Turns §6g's case gating rule on, <strong>through the real settings endpoint</strong> — the
     * reason is on {@code PlanLifecycleEndpointTest#gating}, and so is the reason the review opt-ins
     * are deliberately left off.
     */
    private void gating(UUID projectId, boolean requireApprovedCaseForRun) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requiredPeerReviews", 0);
        payload.put("editAfterApproval", "RETAIN");
        payload.put("requireApprovedCaseForRun", requireApprovedCaseForRun);

        MvcResult saved = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(saved.getResponse().getStatus())
                .as("the fixture depends on the real settings endpoint; %s",
                        saved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /**
     * Walks cases to {@code APPROVED} <strong>through the real bulk endpoint</strong>, two moves at
     * a time because the ladder allows no short cut: {@code APPROVED} is reachable only from
     * {@code IN_REVIEW} (§62's call, re-derived in §75).
     *
     * <p>Not a SQL update, and the difference matters here more than usual: the state this fixture
     * has to produce is the one §6g's gate reads, so producing it the way the product does is what
     * makes the test evidence about the product rather than about the column. It also means these
     * tests would notice the day the two halves stop agreeing on what "approved" is.
     */
    private void approve(List<UUID> caseIds) throws Exception {
        for (String status : List.of("IN_REVIEW", "APPROVED")) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", "SET_STATUS");
            payload.put("caseIds", caseIds.stream().map(UUID::toString).toList());
            payload.put("dryRun", false);
            payload.put("payload", Map.of("lifecycleStatus", status));

            MvcResult applied = mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + ACME_BANKING + "/cases/bulk")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(payload)))
                    .andReturn();

            assertThat(applied.getResponse().getStatus())
                    .as("the fixture depends on the real bulk endpoint; %s",
                            applied.getResponse().getContentAsString())
                    .isEqualTo(200);
        }
        for (UUID caseId : caseIds) {
            assertThat(lifecycleOf(caseId))
                    .as("and the fixture must actually have got there")
                    .isEqualTo("APPROVED");
        }
    }

    private String keyOf(UUID caseId) throws SQLException {
        return caseColumn(caseId, "key");
    }

    private String lifecycleOf(UUID caseId) throws SQLException {
        return caseColumn(caseId, "lifecycle_status");
    }

    private String caseColumn(UUID caseId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_case WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
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

    // =====================================================================
    // helpers — fixture, authored through the REAL endpoints
    // =====================================================================

    private UUID umbraCase;

    private UUID createPlan(UUID projectId, String name, List<UUID> suiteIds, List<UUID> caseIds)
            throws Exception {

        UUID actor = projectId.equals(UMBRA_BANKING) ? OUTSIDER : LEAD;
        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", null);
        payload.put("milestoneName", null);
        payload.put("milestoneDate", null);
        payload.put("suiteIds", suiteIds.stream().map(UUID::toString).toList());
        payload.put("caseIds", caseIds.stream().map(UUID::toString).toList());

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(actor, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real plan endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(casePayload(title, suiteId, null))))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    /** Rewrites the case through the real PATCH, so a genuine v2 exists with different text. */
    private void editCaseToV2(UUID caseId) throws Exception {
        MvcResult edited = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                casePayload("Rewritten before the run", REFUNDS, "Rewritten"))))
                .andReturn();

        assertThat(edited.getResponse().getStatus())
                .as("%s", edited.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(edited).get("currentVersionNo").asInt()).isEqualTo(2);
    }

    private Map<String, Object> casePayload(String title, UUID suiteId, String changeComment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by RunCreateEndpointTest.");
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
        payload.put("changeComment", changeComment);
        return payload;
    }

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
        UUID id = UUID.nameUUIDFromBytes(("run-clone:" + marker).getBytes());
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
                // §82. Written by the Gating class's helper and by nothing else, so leaving it lets
                // one test turn the gate on for every test that runs after it. The identical
                // omission in PlanLifecycleEndpointTest reddened five tests on its first run, three
                // of them written before the slice existed.
                execute(connection, "DELETE FROM project_settings");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void resetRunCounter() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection,
                        "UPDATE key_sequence SET next_value = 1 WHERE entity_type = 'RUN'");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTrees() throws Exception {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1");
            suite(connection, REFUNDS, ACME, ACME_BANKING, null, "TS-2", 2, "Refunds", "s2");
            // §98: REFUNDS was a CHILD of PAYMENTS, and the plan below is scoped on PAYMENTS — so
            // every case in this fixture reached the plan through the subtree. Suites are flat, so
            // PAYMENTS claims them by TAG instead: the arm is different, the property is the same
            // one the fixture was built for (a suite arm reaching cases that are filed elsewhere),
            // and a tag rule is LIVE, which also preserves the later-authored case entering scope
            // with no edit to the plan.
            tagRule(connection, PAYMENTS, "refunds");
            suite(connection, SPARE, ACME, ACME_BANKING, null, "TS-3", 3, "Spare", "s3");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-4", 4, "Mobile", "s4");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1");
        }
        umbraCase = createCase(OUTSIDER, UMBRA, UMBRA_BANKING, "Umbra's own", UMBRA_SUITE);
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

    /** One tag rule on a suite — the membership arm that replaced this fixture's subtree (§98). */
    private void tagRule(Connection connection, UUID suiteId, String tag) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO suite_tag_rule (company_id, suite_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    /** One tag on a case, so a suite's rule can claim it. */
    private void tagCase(UUID caseId, String tag) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
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
    // helpers — reading rows back, past the API
    // =====================================================================

    private UUID configurationOf(UUID projectId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM configuration WHERE project_id = CAST(? AS uuid) "
                             + "AND is_default")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must seed a default configuration").isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private long runCount(UUID companyId) throws SQLException {
        return count(companyId, "SELECT COUNT(*) FROM test_run");
    }

    private long executionCount(UUID companyId) throws SQLException {
        return count(companyId, "SELECT COUNT(*) FROM test_execution");
    }

    private long count(UUID companyId, String sql) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private List<UUID> caseIdsOf(UUID runId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT case_id FROM test_execution WHERE run_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    private List<String> executionReleases(UUID runId) throws SQLException {
        List<String> releases = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT release_version || '|' || release_sort_key FROM test_execution "
                             + "WHERE run_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    releases.add(rows.getString(1));
                }
            }
        }
        return releases;
    }

    private String sortKeyOf(UUID runId) throws SQLException {
        return column(runId, "release_sort_key");
    }

    private UUID planIdOf(UUID runId) throws SQLException {
        String value = column(runId, "CAST(plan_id AS text)");
        return value == null ? null : UUID.fromString(value);
    }

    private String column(UUID runId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_run WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
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
