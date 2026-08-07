package com.tcms.repository;

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
import java.util.List;
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
 * {@code PUT /projects/{projectId}/cases/{caseId}/quarantine} — M3's quarantine flow over HTTP.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>Automation Service being able to quarantine.</strong>
 *       {@link Gates#automationServiceCannotQuarantineItsOwnRedTest()} is the one assertion here
 *       whose absence would be a security defect rather than a usability one. A leaked CI token
 *       that can post results <em>and</em> quarantine the tests those results fail can launder a
 *       red suite into a green one with nobody's involvement, and the whole reason Automation
 *       Service is the smallest role in the product is to make that impossible. Gating this on
 *       {@code result:submit} — which is the key the caller posting results obviously holds — is a
 *       one-word change that every other test in this class survives.</li>
 *   <li><strong>A quarantine with no reason.</strong> The board card would say a test is excluded
 *       and be unable to say why, which is debt nobody can clear.</li>
 *   <li><strong>An absent {@code quarantined} field defaulting to false.</strong> The one shape of
 *       this request that destroys a decision by omission — a client that sends
 *       {@code {"reason": "..."}} would silently release.</li>
 *   <li><strong>Repeating the request being an error.</strong> Releasing a case that was never
 *       quarantined must succeed: the caller asked for a state and the case is in it.</li>
 *   <li><strong>A write that reaches across the two path segments.</strong> The project gate is not
 *       the case gate.</li>
 *   <li><strong>A release that keeps the reason.</strong> A row saying "not quarantined" beside
 *       "quarantined by Ana — flaky on Firefox" is read in whichever order the screen puts them.</li>
 * </ol>
 *
 * <p>Company and user ids are {@code aa…}, checked against every prefix already in
 * {@code src/test/java} before writing — §19's first defect and §29's repeat of it.
 */
@AutoConfigureMockMvc
@DisplayName("Case quarantine — the decision, its reason, and the role that must not have it")
class CaseQuarantineEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("aa000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("aa000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("aa001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("aa001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("aa002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code case:edit}. */
    private static final UUID LEAD = UUID.fromString("aa003000-0000-4000-8000-000000000001");
    /** Test Engineer — also holds {@code case:edit}, unqualified. */
    private static final UUID ENGINEER = UUID.fromString("aa003000-0000-4000-8000-000000000002");
    /** Viewer — holds {@code project:read} and no write permission at all. */
    private static final UUID VIEWER = UUID.fromString("aa003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("aa003000-0000-4000-8000-000000000004");
    /**
     * <strong>Automation Service</strong> — the persona this class is really about. Holds
     * {@code result:submit} and {@code run:create} and deliberately not {@code case:edit}.
     */
    private static final UUID ROBOT = UUID.fromString("aa003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("aa004000-0000-4000-8000-000000000001");
    private static final UUID LOCKED_SUITE = UUID.fromString("aa004000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("aa004000-0000-4000-8000-000000000003");

    /** The flaky one. Most tests here act on it. */
    private static final UUID FLAKY = UUID.fromString("aa005000-0000-4000-8000-000000000001");
    /** A second, so a test that quarantines need not fight one asserting a clean row. */
    private static final UUID FLAKY_2 = UUID.fromString("aa005000-0000-4000-8000-000000000002");
    /** A third, for the release path. */
    private static final UUID FLAKY_3 = UUID.fromString("aa005000-0000-4000-8000-000000000003");
    /** In the sibling project nobody here is granted. */
    private static final UUID LOCKED_CASE = UUID.fromString("aa005000-0000-4000-8000-000000000004");
    /** In the other client, deliberately keyed like Acme's. */
    private static final UUID UMBRA_CASE = UUID.fromString("aa005000-0000-4000-8000-000000000005");

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

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "aq-acme", "Acme", now);
            company(UMBRA, "aq-umbra", "Umbra", now);

            activeUser(LEAD, "lead@aq-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@aq-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@aq-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@aq-acme.test", "N. Grant", now);
            activeUser(ROBOT, "ci@aq-acme.test", "CI Runner", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "AQOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "AQLCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ROBOT, "CI Runner", now);

            grantOnProject("aq-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("aq-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("aq-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
            grantOnProject("aq-robot", ACME, Role.AUTOMATION_SERVICE_ID, ROBOT, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "AQUOL", "Online Banking", now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedContent();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>{@code case_flake} first, because {@code test_case} is its parent — and every test here
     * writes one, so without this the second test to name a case finds it already quarantined and
     * asserts the wrong transition. Green alone, red in the suite: §19's defect in this class's
     * clothes.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM case_flake");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite");
            }
        }
    }

    // =====================================================================

    @Nested
    @DisplayName("The gates — case:edit, and the role that deliberately does not hold it")
    class Gates {

        @Test
        @DisplayName("AUTOMATION SERVICE cannot quarantine the test its own results just failed")
        void automationServiceCannotQuarantineItsOwnRedTest() throws Exception {
            // THE test of this slice, and the reason the gate is case:edit rather than the
            // result:submit a caller posting results obviously holds. Automation Service is
            // deliberately the smallest role in the product because a leaked CI token is the
            // realistic breach — and a token that can post results AND quarantine the tests those
            // results fail can turn a red suite green with nobody involved.
            //
            // §117 made the same call for automation_key_mapping's writer. Together they are what
            // makes "a leaked token cannot launder a red suite" a design rather than a hope: both
            // capabilities sit behind a key the role does not have.
            MvcResult refused = quarantine(ROBOT, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"it keeps failing\"}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(isQuarantined(ACME, FLAKY))
                    .as("a 403 that quarantined anyway is worse than either answer alone")
                    .isFalse();
        }

        @Test
        @DisplayName("and the robot's refusal is the permission, not a broken route or token")
        void theRobotReachesTheCase() throws Exception {
            // Without this, the test above passes equally well against a route that does not exist
            // or a token that is wrong — and would keep passing after the gate was removed.
            assertThat(get(ROBOT, ACME, ACME_BANKING, FLAKY).getResponse().getStatus())
                    .as("Automation Service holds case:read, so it can see the case it cannot quarantine")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Lead quarantines and gets the row back")
        void leadCanQuarantine() throws Exception {
            MvcResult result = quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"Flaky on the payment gateway stub\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("quarantined").asBoolean()).isTrue();
            assertThat(body.get("quarantineReason").asText())
                    .isEqualTo("Flaky on the payment gateway stub");
            assertThat(body.get("quarantinedBy").asText()).isEqualTo(LEAD.toString());
            assertThat(body.get("quarantinedAt").isNull())
                    .as("the database refuses a quarantine without a moment")
                    .isFalse();
        }

        @Test
        @DisplayName("a Test Engineer can too — case:edit carries no OWN qualifier here")
        void engineerCanQuarantine() throws Exception {
            // Roles spec §A5 qualifies case:DELETE as OWN, not case:edit. Applying the qualifier
            // here by analogy would refuse an engineer the ability to quarantine a colleague's
            // flaky test, which is most of the flaky tests they meet.
            assertThat(quarantine(ENGINEER, ACME, ACME_BANKING, FLAKY_2,
                    "{\"quarantined\": true, \"reason\": \"Times out on CI only\"}")
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Viewer is refused and the case is untouched")
        void viewerIsRefused() throws Exception {
            assertThat(quarantine(VIEWER, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"nope\"}")
                    .getResponse().getStatus())
                    .isEqualTo(403);
            assertThat(isQuarantined(ACME, FLAKY)).isFalse();
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = quarantine(VIEWER, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"nope\"}")
                    .getResponse().getContentAsString();

            assertThat(refused)
                    .as("a key is not actionable by someone who cannot grant it to themselves")
                    .doesNotContain("case:edit");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a member with no grant gets 404, never 403")
        void membershipConveysNothing() throws Exception {
            assertThat(quarantine(NO_GRANT, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"nope\"}")
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the project gate is not the case gate")
        void theSecondPathSegmentIsGatedToo() throws Exception {
            // /projects/{granted}/cases/{ungranted}. Without the second check this quarantines a
            // case in a project the caller was never given, through a project id they were.
            assertThat(quarantine(LEAD, ACME, ACME_BANKING, LOCKED_CASE,
                    "{\"quarantined\": true, \"reason\": \"nope\"}")
                    .getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(isQuarantined(ACME, LOCKED_CASE)).isFalse();
        }

        @Test
        @DisplayName("another company's case is a 404, not a 403")
        void anotherCompanysCaseIsInvisible() throws Exception {
            assertThat(quarantine(LEAD, ACME, UMBRA_BANKING, UMBRA_CASE,
                    "{\"quarantined\": true, \"reason\": \"nope\"}")
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("The body — the reason, and the field that must not default")
    class Body {

        @Test
        @DisplayName("a quarantine with no reason is a 400 naming the field")
        void theReasonIsRequired() throws Exception {
            MvcResult result = quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("reason");
            assertThat(isQuarantined(ACME, FLAKY)).isFalse();
        }

        @Test
        @DisplayName("a blank reason is the same refusal as no reason")
        void whitespaceIsNotAReason() throws Exception {
            // Otherwise the constraint is satisfied by a space bar, and the board card says a test
            // is excluded because " ".
            assertThat(quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"   \"}")
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an ABSENT quarantined field is a 400, never a silent release")
        void theStateFieldDoesNotDefault() throws Exception {
            // The one shape of this request that could destroy a decision by omission. A Boolean
            // rather than a boolean is what makes this reachable at all; with a primitive, a client
            // sending only a reason would release a quarantine it never mentioned and get a 200.
            quarantine(LEAD, ACME, ACME_BANKING, FLAKY_3,
                    "{\"quarantined\": true, \"reason\": \"Flaky under load\"}");

            MvcResult result = quarantine(LEAD, ACME, ACME_BANKING, FLAKY_3,
                    "{\"reason\": \"just updating the note\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(isQuarantined(ACME, FLAKY_3))
                    .as("and the decision is still recorded")
                    .isTrue();
        }

        @Test
        @DisplayName("releasing needs no reason")
        void releasingIsNotJustified() throws Exception {
            // The asymmetry is deliberate: releasing returns the test to the default, and the
            // exception is the thing that needs justifying.
            quarantine(LEAD, ACME, ACME_BANKING, FLAKY_3,
                    "{\"quarantined\": true, \"reason\": \"Flaky under load\"}");

            assertThat(quarantine(LEAD, ACME, ACME_BANKING, FLAKY_3, "{\"quarantined\": false}")
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("The state — an absolute target, so repeating it is not an error")
    class State {

        @Test
        @DisplayName("releasing a case that was never quarantined succeeds and writes no row")
        void releasingWhatWasNeverQuarantinedIsNotA404() throws Exception {
            // The caller asked for a state and the case is in it. A 404 here would be describing
            // the read model's SCHEDULE to somebody who did not ask about it — and the two-verb
            // shape (POST /quarantine, DELETE /quarantine) makes this the natural answer, which is
            // most of why this endpoint is a PUT.
            MvcResult result = quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": false}");

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("quarantined").asBoolean()).isFalse();
            assertThat(flakeRowExists(ACME, FLAKY))
                    .as("a row whose every column is a default is a claim the recompute has not made")
                    .isFalse();
        }

        @Test
        @DisplayName("quarantining twice is not a 409")
        void repeatingTheRequestSucceeds() throws Exception {
            quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"first\"}");

            MvcResult again = quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"second, more precise\"}");

            assertThat(again.getResponse().getStatus()).isEqualTo(200);
            assertThat(json.readTree(again.getResponse().getContentAsString())
                    .get("quarantineReason").asText())
                    .as("the newest reason wins — this is how somebody corrects a hasty one")
                    .isEqualTo("second, more precise");
        }

        @Test
        @DisplayName("releasing clears ALL FOUR authored columns, not just the flag")
        void aReleaseLeavesNoResidue() throws Exception {
            // A row saying "not quarantined" beside "quarantined by L. Lead — flaky on Firefox" is
            // read in whichever order the screen happens to put the two, and the reason field is
            // exactly the one a card renders without checking the flag first.
            quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"Flaky on Firefox\"}");

            MvcResult released = quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": false}");

            JsonNode body = json.readTree(released.getResponse().getContentAsString());
            assertThat(body.get("quarantined").asBoolean()).isFalse();
            assertThat(body.get("quarantineReason").isNull()).isTrue();
            assertThat(body.get("quarantinedBy").isNull()).isTrue();
            assertThat(body.get("quarantinedAt").isNull()).isTrue();
        }

        @Test
        @DisplayName("a quarantine on a case the recompute has never scored reports null, not zero")
        void anUnscoredCaseHasNoScore() throws Exception {
            // The ordinary sequence: a run goes red and is quarantined within the hour, while the
            // fact-log refresh is on a schedule. The row is created with V38's defaults, which
            // already mean "no evidence" — and flakeScore must be null rather than 0, because 0 is
            // a claim (evidently stable) about a test that was just quarantined for flapping.
            MvcResult result = quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"Flaky on Firefox\"}");

            JsonNode body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("flakeScore").isNull())
                    .as("null is 'we cannot say'; 0 would be 'evidently stable'")
                    .isTrue();
            assertThat(body.get("executions").asInt()).isZero();
        }

        @Test
        @DisplayName("the decision is written to the audit log with its reason")
        void theDecisionIsAudited() throws Exception {
            // case_flake keeps only the CURRENT reason — a release clears it — so without this the
            // record of why a test was excluded for three months is destroyed by the act of fixing
            // it.
            quarantine(LEAD, ACME, ACME_BANKING, FLAKY,
                    "{\"quarantined\": true, \"reason\": \"Flaky on Firefox\"}");
            quarantine(LEAD, ACME, ACME_BANKING, FLAKY, "{\"quarantined\": false}");

            assertThat(auditActions(ACME))
                    .containsExactly("case.quarantined", "case.quarantine_released");
        }
    }

    // =====================================================================
    // Fixture plumbing
    // =====================================================================

    private MvcResult quarantine(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/cases/" + caseId + "/quarantine")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private boolean isQuarantined(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT quarantined FROM case_flake WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private boolean flakeRowExists(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT 1 FROM case_flake WHERE case_id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private List<String> auditActions(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                var statement = connection.prepareStatement(
                        "SELECT action FROM audit_event ORDER BY occurred_at, action")) {
            try (ResultSet rows = statement.executeQuery()) {
                List<String> actions = new java.util.ArrayList<>();
                while (rows.next()) {
                    actions.add(rows.getString(1));
                }
                return actions;
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
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

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = assignmentId(marker);
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

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    private void seedContent() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, "TS-aq-1", 1, "Payments");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, "TS-aq-2", 2, "Internal");

            testCase(connection, FLAKY, ACME, ACME_BANKING, PAYMENTS, "TC-aq-1", 901,
                    "Checkout with a saved card");
            testCase(connection, FLAKY_2, ACME, ACME_BANKING, PAYMENTS, "TC-aq-2", 902,
                    "Checkout with a new card");
            testCase(connection, FLAKY_3, ACME, ACME_BANKING, PAYMENTS, "TC-aq-3", 903,
                    "Refund a settled payment");
            testCase(connection, LOCKED_CASE, ACME, ACME_LOCKED, LOCKED_SUITE, "TC-aq-4", 904,
                    "In the locked project");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // Deliberately keyed like Acme's: keys are numbered per company, so the collision is
            // the design working.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "TS-aq-1", 1, "Payments");
            testCase(connection, UMBRA_CASE, UMBRA, UMBRA_BANKING, UMBRA_SUITE, "TC-aq-1", 901,
                    "Checkout with a saved card");
        }
    }

    private void suite(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String name) throws SQLException {
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

    private void testCase(Connection connection, UUID id, UUID companyId, UUID projectId,
            UUID suiteId, String key, long keySeq, String title) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, automation_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        ?, ?, ?, 'AUTOMATED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.executeUpdate();
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("case-quarantine-endpoint:" + marker).getBytes());
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }
}
