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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * The hand-recorded write of {@code test_execution.duration_ms}.
 *
 * <h2>The column is old and had no writer at all</h2>
 *
 * <p>{@code duration_ms BIGINT} has been on {@code test_execution} since
 * {@code V1__initial_schema.sql}, is mapped on {@link TestExecution}, is served on
 * {@code TestExecutionView}, {@code TestExecutionSummaryView} and {@code CaseHistoryView}, is
 * copied into {@code execution_fact} by {@code ExecutionSource} and is summed into the run
 * summary report's "recorded duration". <strong>Nothing wrote it.</strong> The ingestion parsers
 * do recover a duration from a JUnit/TestNG/Cucumber report, and
 * {@code IngestionService#payload} puts it in the ingestion job's payload JSON — never in the
 * column. So every consumer above has answered null since 2026-07-30, and this endpoint is the
 * column's first writer.
 *
 * <p>That matters to a reader of the plan this class comes from, which said the field was
 * "written by ingestion since V1" and asked for a fourth arm proving that an ingested duration
 * survives a hand amendment that omits it. <del>There is no such duration to survive.</del> That
 * arm is replaced by {@link #zeroIsADurationAndNotAnAbsence}, which pins the defect the substitution
 * actually exposes — see its own header.
 *
 * <h2>The three states the field has, and why the middle one is the one to get wrong</h2>
 *
 * <p>This body states desired state rather than patching it, exactly as {@code comment} has since
 * Phase 1, so an <em>omitted</em> duration is a <strong>clear</strong> and not a keep. A service
 * that assigned it conditionally would make the field un-clearable and diverge from the field
 * sitting beside it on the same form — and it would pass an assertion written only about the
 * setting arm.
 *
 * <p>Company, user and project ids are unique to this class — {@code 66…}, checked against every
 * prefix already in {@code src/test/java} before writing.
 */
@AutoConfigureMockMvc
@DisplayName("Execution duration — the hand-recorded write of a column that had no writer")
class ExecutionDurationTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("66000000-0000-4000-8000-00000000000a");
    private static final UUID ACME_BANKING = UUID.fromString("66001000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code run:execute}, {@code result:submit} and {@code result:amend}. */
    private static final UUID LEAD = UUID.fromString("66003000-0000-4000-8000-000000000001");

    private static final UUID PAYMENTS = UUID.fromString("66004000-0000-4000-8000-000000000001");

    /** A status id no project uses, for the arm that needs two faults in one payload. */
    private static final UUID ABSENT_STATUS =
            UUID.fromString("6600ffff-0000-4000-8000-00000000ffff");

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

    private UUID theCase;
    private UUID theRun;
    private UUID theExecution;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "ed-acme", "Acme", now);
            activeUser(LEAD, "lead@ed-acme.test", "L. Lead", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "EDAOLB", "Online Banking", now);
            member(ACME, LEAD, "L. Lead of Acme", now);
            grant("lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        clearMutableRows();
        seedTree();

        theCase = createCase("Refund the whole captured amount");
        theRun = createRun("Nightly");
        theExecution = executionOf(theRun, theCase);
    }

    @Test
    @DisplayName("a recorded result keeps the duration it was given")
    void aRecordedResultKeepsTheDurationItWasGiven() throws Exception {
        JsonNode recorded = record(0, payload(passedStatus(), "Took a while.", 4500L));

        assertThat(recorded.get("durationMs").asLong())
                .as("the write's own response is the read that follows it")
                .isEqualTo(4500L);
        assertThat(readBack().get("durationMs").asLong())
                .as("and the view a second reader gets says the same")
                .isEqualTo(4500L);
        assertThat(onTheChecklist().get("durationMs").asLong())
                .as("the run's checklist carries it too — TestExecutionSummaryView declares the "
                        + "field, and a projection that dropped it would leave the grid column "
                        + "blank beside a detail screen that shows a number")
                .isEqualTo(4500L);
    }

    /**
     * The arm a reader is most likely to get wrong, and the one that separates this field from a
     * PATCH-shaped one: an amendment that does not mention the duration clears it.
     */
    @Test
    @DisplayName("omitting the duration clears it, on the same terms as the comment")
    void omittingTheDurationClearsIt() throws Exception {
        record(0, payload(passedStatus(), "Took a while.", 4500L));

        Map<String, Object> amendment = new LinkedHashMap<>();
        amendment.put("statusId", failedStatus().toString());
        amendment.put("comment", "Actually it failed.");
        JsonNode amended = record(1, amendment);

        assertThat(amended.get("durationMs").isNull())
                .as("a service that treated null as 'leave alone' would make the field "
                        + "un-clearable and diverge from `comment`, which is on the same form")
                .isTrue();
        assertThat(readBack().get("durationMs").isNull()).isTrue();
    }

    /**
     * <strong>Zero is a duration.</strong> This replaces the plan's fourth arm, which asked about
     * an ingested duration that does not exist (see the class header).
     *
     * <p>The defect it pins is the one the substitution walks into: every layer between the tester
     * and the column has a falsy check in it that would swallow a zero — {@code present()} in both
     * write clients drops {@code undefined}, the SPA converts seconds to milliseconds and could
     * read {@code 0} as "nothing typed", and a service guarding the assignment with
     * {@code > 0} rather than {@code != null} would turn a fast test into an untimed one. A run of
     * sub-second automated checks recorded by hand is the case where every one of those is wrong.
     */
    @Test
    @DisplayName("zero is a duration and not an absence")
    void zeroIsADurationAndNotAnAbsence() throws Exception {
        JsonNode recorded = record(0, payload(passedStatus(), null, 0L));

        assertThat(recorded.get("durationMs").isNull())
                .as("0 must not collapse into 'not timed' — the two are different statements")
                .isFalse();
        assertThat(recorded.get("durationMs").asLong()).isZero();
        assertThat(readBack().get("durationMs").asLong()).isZero();
    }

    /**
     * The refusal, and that it is <em>collected</em> rather than reported on its own.
     *
     * <p>The caller here is a tester at the end of a test, which is the worst moment to be sent
     * round a loop — so the payload carries a second fault as well and both must come back.
     */
    @Test
    @DisplayName("a negative duration is a field error, gathered with every other fault")
    void aNegativeDurationIsAFieldError() throws Exception {
        MvcResult result = attempt(0, payload(ABSENT_STATUS, null, -1L));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode errors = body(result).get("errors");
        assertThat(errors.has("durationMs"))
                .as("the fault is keyed to the field the tester can fix; %s", errors)
                .isTrue();
        assertThat(errors.get("durationMs").get(0).asText())
                .isEqualTo("A duration cannot be negative.");
        assertThat(errors.has("statusId"))
                .as("and it is collected alongside the other fault rather than short-circuiting it")
                .isTrue();

        assertThat(readBack().get("durationMs").isNull())
                .as("a refused payload writes nothing").isTrue();
    }

    // =====================================================================
    // helpers — the request
    // =====================================================================

    private Map<String, Object> payload(UUID statusId, String comment, Long durationMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("statusId", statusId.toString());
        body.put("comment", comment);
        body.put("durationMs", durationMs);
        return body;
    }

    private JsonNode record(int lockVersion, Map<String, Object> body) throws Exception {
        MvcResult result = attempt(lockVersion, body);
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        return body(result);
    }

    private MvcResult attempt(int lockVersion, Map<String, Object> body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/runs/" + theRun
                                + "/executions/" + theExecution)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .header(HttpHeaders.IF_MATCH, String.valueOf(lockVersion))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private JsonNode readBack() throws Exception {
        return get("/projects/" + ACME_BANKING + "/runs/" + theRun + "/executions/" + theExecution);
    }

    /** The one row of the run's checklist this class is about. */
    private JsonNode onTheChecklist() throws Exception {
        JsonNode rows = get("/projects/" + ACME_BANKING + "/runs/" + theRun).get("executions");
        for (JsonNode row : rows) {
            if (theExecution.toString().equals(row.get("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("the run's checklist does not hold the execution under test");
    }

    private JsonNode get(String path) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1" + path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    // =====================================================================
    // helpers — the fixture
    // =====================================================================

    private UUID createCase(String title) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", PAYMENTS.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by ExecutionDurationTest.");
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
        payload.put("steps", List.of(step));
        payload.put("changeComment", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + ACME_BANKING + "/cases", payload).get("id").asText());
    }

    private UUID createRun(String name) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("planId", null);
        payload.put("configurationId", configurationOf(ACME_BANKING).toString());
        payload.put("releaseVersion", "4.3");
        payload.put("buildNumber", null);
        payload.put("caseIds", List.of(theCase.toString()));
        payload.put("assigneeId", null);

        return UUID.fromString(created(
                "/api/v1/projects/" + ACME_BANKING + "/runs", payload).get("id").asText());
    }

    private JsonNode created(String path, Map<String, Object> payload) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the fixture depends on the real endpoint at %s; %s",
                        path, result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result);
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
        UUID id = UUID.nameUUIDFromBytes(("execution-duration:" + marker).getBytes());
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
        try (Connection connection = appConnection(ACME)) {
            execute(connection, "DELETE FROM audit_event");
            execute(connection, "DELETE FROM defect_link");
            execute(connection, "DELETE FROM evidence");
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
            execute(connection, "DELETE FROM configuration WHERE NOT is_default");
            execute(connection, "UPDATE key_sequence SET next_value = 1");
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTree() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (var statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setString(1, ACME.toString());
                statement.setString(2, ACME_BANKING.toString());
                statement.execute();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO suite
                        (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 'TS-1', 1,
                            'Payments', 0, 'APPROVED')
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, PAYMENTS.toString());
                statement.setString(2, ACME.toString());
                statement.setString(3, ACME_BANKING.toString());
                statement.executeUpdate();
            }
        }
    }

    private UUID executionOf(UUID runId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM test_execution WHERE run_id = CAST(? AS uuid) "
                             + "AND case_id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.setString(2, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the run's checklist must hold this case, or the fixture is wrong "
                                + "before any assertion runs")
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

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

    private UUID statusIdOf(String kind) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT id FROM case_status WHERE project_id = CAST(? AS uuid) AND kind = ?")) {
            statement.setString(1, ACME_BANKING.toString());
            statement.setString(2, kind);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("tcms_seed_project_defaults must have seeded a %s status", kind)
                        .isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private UUID passedStatus() throws SQLException {
        return statusIdOf("PASSED");
    }

    private UUID failedStatus() throws SQLException {
        return statusIdOf("FAILED");
    }
}
