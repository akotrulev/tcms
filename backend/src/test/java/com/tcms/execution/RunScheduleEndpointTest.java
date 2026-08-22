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
 * {@code /projects/{projectId}/run-schedules} over HTTP — the CRUD surface (§247).
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A schedule stored with an expression the poller cannot read.</strong> Its symptom
 *       is not an error: it is a schedule that never fires and never says why, which is
 *       indistinguishable from one whose slot has not come round.</li>
 *   <li><strong>A schedule stored with no scope.</strong> It could only ever create empty runs, and
 *       an empty run renders as 0 of 0, 100%, complete — a run that looks finished and never
 *       started.</li>
 *   <li><strong>An assignee resolved with {@code resolve} rather than {@code isActiveMember}.</strong>
 *       {@code app_user} is instance-level (ERD §0.1's exception list), so the first accepts a user
 *       id belonging to another client entirely and hands them this client's nightly work.</li>
 *   <li><strong>A schedule id used as an existence oracle.</strong> An id from another company's
 *       project must answer 404 and never 403 — {@code writeGranted}'s ordering rule (§6t).</li>
 *   <li><strong>A delete that takes the runs with it.</strong> A schedule records no work; the runs
 *       it made are ordinary runs and outlive it.</li>
 * </ol>
 *
 * <p>Ids are unique to this class — {@code 3d…}, pre-assigned 2026-08-22 for area C of
 * {@code docs/plans/2026-08-18-unplanned-gaps.md} and verified unused across {@code backend/src},
 * {@code frontend/src} and {@code packages}.
 *
 * <p>⚠️ <strong>This class has never been run.</strong> It was written under the no-gates
 * directive of 2026-08-22; the batch's verification is one pass afterwards.
 */
@AutoConfigureMockMvc
@DisplayName("run schedules — created, read, replaced and deleted")
class RunScheduleEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("3d000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("3d000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("3d001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_BANKING = UUID.fromString("3d002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("3d003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("3d003000-0000-4000-8000-000000000002");
    /** Viewer — the only role that does NOT hold {@code run:create} (roles spec §A3). */
    private static final UUID VIEWER = UUID.fromString("3d003000-0000-4000-8000-000000000003");
    private static final UUID OUTSIDER = UUID.fromString("3d003000-0000-4000-8000-000000000005");

    private static final UUID SMOKE_SUITE = UUID.fromString("3d004000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_SUITE = UUID.fromString("3d004000-0000-4000-8000-000000000005");

    private static final UUID ABSENT = UUID.fromString("3d00ffff-0000-4000-8000-00000000ffff");

    /** Every weekday at 07:00, six-field Spring cron. */
    private static final String WEEKDAY_MORNING = "0 0 7 * * MON-FRI";

    private static final String SOFIA = "Europe/Sofia";

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

    private UUID smokeCase;
    private UUID umbraSchedule;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            // The slug is a SECOND uniqueness namespace beside the fixture's UUID prefix, and it
            // is a GLOBAL one: `company` is deliberately not under RLS (V1's comment says why) and
            // `uq_company_slug` therefore spans the whole instance. `rs-acme` had been
            // RunSummaryReportTest's since §188, so the initials this class first reached for made
            // both classes pass alone and fail together — §19's first defect, one namespace over,
            // and the reason the parallel-work rules' pre-assigned UUID prefix is not sufficient
            // on its own.
            company(ACME, "rsched-acme", "Acme", now);
            company(UMBRA, "rsched-umbra", "Umbra", now);

            activeUser(LEAD, "lead@rsched-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@rsched-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@rsched-acme.test", "V. Viewer", now);
            activeUser(OUTSIDER, "out@rsched-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "RSAOLB", "Online Banking", now);
            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            grant("rs-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("rs-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("rs-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "RSUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("rs-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        smokeCase = createCase(LEAD, ACME, ACME_BANKING, "Sign in with a valid card", SMOKE_SUITE);
        tagCase(ACME, smokeCase, "smoke");

        // Umbra's own schedule, so the isolation arms compare against a row that really exists
        // rather than against an id nothing ever wrote.
        umbraSchedule = createdId(post(OUTSIDER, UMBRA, UMBRA_BANKING,
                payload("Umbra nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(UMBRA_SUITE)))));
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — run:create, held by every role except Viewer")
    class Gate {

        @Test
        @DisplayName("a lead creates a schedule, and it comes back with a future nextFireAt")
        void aLeadCreatesASchedule() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly smoke", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))));

            assertThat(created.getResponse().getStatus())
                    .as("%s", created.getResponse().getContentAsString()).isEqualTo(201);
            assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                    .as("the create names the schedule's own URL")
                    .contains("/run-schedules/");

            JsonNode body = body(created);
            assertThat(body.get("name").asText()).isEqualTo("Nightly smoke");
            assertThat(body.get("enabled").asBoolean()).isTrue();
            assertThat(body.get("createdBy").asText()).isEqualTo(LEAD.toString());
            assertThat(body.get("createdByName").asText()).isEqualTo("L. Lead of Acme");
            assertThat(body.get("lastFiredAt").isNull()).isTrue();
            assertThat(body.get("lastFireError").isNull()).isTrue();

            assertThat(Instant.parse(body.get("nextFireAt").asText()))
                    .as("a schedule that is due in the past would fire on the very next tick, "
                            + "which is not what somebody creating one asked for")
                    .isAfter(Instant.now());
        }

        @Test
        @DisplayName("a Test Engineer creates one too — run:create is not a lead's key")
        void anEngineerCreatesOne() throws Exception {
            assertThat(post(ENGINEER, ACME, ACME_BANKING,
                    payload("Engineer's", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))))
                    .getResponse().getStatus()).isEqualTo(201);
        }

        /**
         * Viewer is the only role in the catalogue that does not hold {@code run:create}, which is
         * the same line {@code RunCreateEndpointTest} leans on. A fixture using a Test Engineer
         * would prove nothing — they hold the key.
         */
        @Test
        @DisplayName("a Viewer is refused with 403, and nothing is stored")
        void aViewerIsRefused() throws Exception {
            MvcResult refused = post(VIEWER, ACME, ACME_BANKING,
                    payload("Viewer's", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))));

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .as("§32 — the refusal names no permission key")
                    .doesNotContain("run:create");
            assertThat(scheduleCount(ACME, ACME_BANKING)).isZero();
        }

        @Test
        @DisplayName("a Viewer still READS the list — visibility is the whole read gate")
        void aViewerReadsTheList() throws Exception {
            post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly smoke", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))));

            MvcResult listed = get(VIEWER, ACME, ACME_BANKING, null);

            assertThat(listed.getResponse().getStatus())
                    .as("a schedule carries nothing a project member may not already see")
                    .isEqualTo(200);
            assertThat(body(listed)).hasSize(1);
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation — every refusal keyed to the field the caller can act on")
    class Validation {

        @Test
        @DisplayName("an unparseable cron expression is a 400 on cronExpression, and stores nothing")
        void anUnparseableExpressionIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", "every monday please", SOFIA, scope(List.of(SMOKE_SUITE))));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused)).containsKey("cronExpression");
            assertThat(scheduleCount(ACME, ACME_BANKING))
                    .as("the count, not just the status — a 400 that stored a row anyway is the "
                            + "failure this arm exists for")
                    .isZero();
        }

        @Test
        @DisplayName("five-field cron is refused — it is the other dialect, and would shift a field")
        void fiveFieldCronIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", "0 7 * * MON-FRI", SOFIA, scope(List.of(SMOKE_SUITE))));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused)).containsKey("cronExpression");
        }

        @Test
        @DisplayName("an unknown zone is a 400 on zoneId, not on cronExpression")
        void anUnknownZoneIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", WEEKDAY_MORNING, "Europe/Sofiaa",
                            scope(List.of(SMOKE_SUITE))));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused))
                    .as("keyed to the control the caller can act on — a typo in the zone must not "
                            + "send somebody to the cron box")
                    .containsKey("zoneId")
                    .doesNotContainKey("cronExpression");
        }

        @Test
        @DisplayName("a scope with all four arms empty is a 400 on scope")
        void anEmptyScopeIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of())));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused)).containsKey("scope");
            assertThat(scheduleCount(ACME, ACME_BANKING)).isZero();
        }

        @Test
        @DisplayName("a blank tag is not an arm — normalisation happens before the emptiness check")
        void aBlankTagIsNotAnArm() throws Exception {
            Map<String, Object> emptyish = new LinkedHashMap<>();
            emptyish.put("planId", null);
            emptyish.put("caseIds", List.of());
            emptyish.put("suiteIds", List.of());
            emptyish.put("tags", List.of("   "));

            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, emptyish));

            assertThat(refused.getResponse().getStatus())
                    .as("a schedule scoped on whitespace can only ever make empty runs, which is "
                            + "exactly what the emptiness check is for")
                    .isEqualTo(400);
            assertThat(fieldErrors(refused)).containsKey("scope");
        }

        /**
         * The {@code isActiveMember}-versus-{@code resolve} distinction, and it is worth its own
         * arm: {@code app_user} is instance-level, so {@code resolve} finds the outsider's account
         * and a schedule created that way would hand a client's nightly work to a stranger.
         */
        @Test
        @DisplayName("an assignee from the OTHER company is a 400, not a stored row")
        void anAssigneeFromAnotherCompanyIsRefused() throws Exception {
            Map<String, Object> body =
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE)));
            body.put("assigneeId", OUTSIDER.toString());

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, body);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused)).containsKey("assigneeId");
            assertThat(scheduleCount(ACME, ACME_BANKING)).isZero();
        }

        @Test
        @DisplayName("a configuration from another project is a 400 on configurationId")
        void aForeignConfigurationIsRefused() throws Exception {
            Map<String, Object> body =
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE)));
            body.put("configurationId", ABSENT.toString());

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, body);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused)).containsKey("configurationId");
        }

        /**
         * The one deliberate divergence from {@code RunEdit}, asserted so it does not later read
         * as an oversight. A schedule is evaluated over months; refusing the edit because a suite
         * it names was deleted would make the schedule uneditable — including the edit that would
         * have removed the dead arm.
         */
        @Test
        @DisplayName("a suite id naming nothing live is ACCEPTED — the emptiness is a fire error")
        void aDeadScopeArmIsNotAFieldError() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(ABSENT))));

            assertThat(created.getResponse().getStatus())
                    .as("%s", created.getResponse().getContentAsString())
                    .isEqualTo(201);
            assertThat(body(created).get("scope").get("suiteIds").get(0).asText())
                    .isEqualTo(ABSENT.toString());
        }

        @Test
        @DisplayName("a name over the cap is a 400 on name")
        void anOverlongNameIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    payload("N".repeat(RunScheduleEdit.MAX_NAME + 1), WEEKDAY_MORNING, SOFIA,
                            scope(List.of(SMOKE_SUITE))));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused)).containsKey("name");
        }

        @Test
        @DisplayName("several faults are COLLECTED, not short-circuited")
        void faultsAreCollected() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING,
                    payload("  ", "nonsense", "Nowhere/AtAll", scope(List.of())));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(fieldErrors(refused).keySet())
                    .as("somebody filling in a form must not be sent round the loop four times")
                    .contains("name", "cronExpression", "zoneId", "scope");
        }
    }

    // =====================================================================
    // Read, replace, delete
    // =====================================================================

    @Nested
    @DisplayName("Reading, replacing and deleting")
    class Lifecycle {

        @Test
        @DisplayName("the scope round-trips in all four arms")
        void theScopeRoundTrips() throws Exception {
            Map<String, Object> everything = new LinkedHashMap<>();
            everything.put("planId", null);
            everything.put("caseIds", List.of(smokeCase.toString()));
            everything.put("suiteIds", List.of(SMOKE_SUITE.toString()));
            everything.put("tags", List.of("smoke", "regression"));

            UUID id = createdId(post(LEAD, ACME, ACME_BANKING,
                    payload("Everything", WEEKDAY_MORNING, SOFIA, everything)));

            JsonNode scope = body(get(LEAD, ACME, ACME_BANKING, id)).get("scope");

            assertThat(scope.get("planId").isNull()).isTrue();
            assertThat(scope.get("caseIds").get(0).asText()).isEqualTo(smokeCase.toString());
            assertThat(scope.get("suiteIds").get(0).asText()).isEqualTo(SMOKE_SUITE.toString());
            assertThat(List.of(scope.get("tags").get(0).asText(),
                            scope.get("tags").get(1).asText()))
                    .containsExactly("smoke", "regression");
        }

        @Test
        @DisplayName("a PUT replaces TOTALLY — an omitted assignee clears it")
        void aPutReplacesTotally() throws Exception {
            Map<String, Object> withAssignee =
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE)));
            withAssignee.put("assigneeId", ENGINEER.toString());

            UUID id = createdId(post(LEAD, ACME, ACME_BANKING, withAssignee));
            assertThat(body(get(LEAD, ACME, ACME_BANKING, id)).get("assigneeId").asText())
                    .isEqualTo(ENGINEER.toString());

            MvcResult replaced = put(LEAD, ACME, ACME_BANKING, id,
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))));

            assertThat(replaced.getResponse().getStatus())
                    .as("%s", replaced.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(body(replaced).get("assigneeId").isNull())
                    .as("the body states the whole schedule; an omission is a clear, not a "
                            + "leave-alone")
                    .isTrue();
        }

        @Test
        @DisplayName("changing the expression recomputes nextFireAt, not only the stored text")
        void changingTheExpressionRecomputesTheNextFire() throws Exception {
            UUID id = createdId(post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", "0 0 3 1 1 *", "UTC", scope(List.of(SMOKE_SUITE)))));
            Instant before = Instant.parse(
                    body(get(LEAD, ACME, ACME_BANKING, id)).get("nextFireAt").asText());

            MvcResult replaced = put(LEAD, ACME, ACME_BANKING, id,
                    payload("Nightly", "0 * * * * *", "UTC", scope(List.of(SMOKE_SUITE))));

            Instant after = Instant.parse(body(replaced).get("nextFireAt").asText());
            assertThat(after)
                    .as("a corrected cadence that only took effect at the OLD cadence's next slot "
                            + "is a correction that looks ignored")
                    .isBefore(before);
        }

        @Test
        @DisplayName("a disabled schedule keeps its next fire time")
        void disablingKeepsTheNextFireTime() throws Exception {
            UUID id = createdId(post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE)))));

            Map<String, Object> off =
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE)));
            off.put("enabled", false);

            JsonNode body = body(put(LEAD, ACME, ACME_BANKING, id, off));

            assertThat(body.get("enabled").asBoolean()).isFalse();
            assertThat(body.get("nextFireAt").isNull())
                    .as("pausing a cadence and deleting it are different acts; a paused schedule "
                            + "resumes at its own next slot")
                    .isFalse();
        }

        @Test
        @DisplayName("an expression with nothing left answers a NULL nextFireAt, not an error")
        void anExhaustedExpressionAnswersNull() throws Exception {
            // The 30th of February, not a year that has passed: a six-field cron has no year
            // field at all, so "0 0 3 1 1 2020" is SEVEN fields and the server refuses it with a
            // 400 — correctly. An impossible day-of-month is the only way to express a cadence
            // with nothing left, and CronScheduleTest asserts the same thing one layer down.
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    payload("Never again", "0 0 3 30 2 *", "UTC",
                            scope(List.of(SMOKE_SUITE))));

            assertThat(created.getResponse().getStatus())
                    .as("%s", created.getResponse().getContentAsString()).isEqualTo(201);
            assertThat(body(created).get("nextFireAt").isNull())
                    .as("a real answer — the screen says \"will not fire again\"")
                    .isTrue();
        }

        @Test
        @DisplayName("the list is ordered by name, so it does not flicker between reads")
        void theListIsOrderedByName() throws Exception {
            post(LEAD, ACME, ACME_BANKING,
                    payload("Zulu", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))));
            post(LEAD, ACME, ACME_BANKING,
                    payload("Alpha", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))));

            JsonNode listed = body(get(LEAD, ACME, ACME_BANKING, null));

            assertThat(List.of(listed.get(0).get("name").asText(),
                            listed.get(1).get("name").asText()))
                    .containsExactly("Alpha", "Zulu");
        }

        /**
         * A schedule records no work, so deleting one destroys nothing that happened. The runs it
         * created are ordinary runs and must be untouched.
         */
        @Test
        @DisplayName("DELETE removes the schedule and leaves the run it created alone")
        void deleteLeavesTheRunsAlone() throws Exception {
            UUID id = createdId(post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE)))));
            UUID runId = createRunManually();
            pointLastRunAt(id, runId);

            assertThat(delete(LEAD, ACME, ACME_BANKING, id).getResponse().getStatus())
                    .isEqualTo(204);

            assertThat(scheduleCount(ACME, ACME_BANKING)).isZero();
            assertThat(liveRunCount(ACME))
                    .as("the run outlives the schedule — there is nothing here to cascade")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the delete is audited, so the act survives the row")
        void theDeleteIsAudited() throws Exception {
            UUID id = createdId(post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE)))));
            delete(LEAD, ACME, ACME_BANKING, id);

            assertThat(auditActions(ACME))
                    .contains("run_schedule.created", "run_schedule.deleted");
        }
    }

    // =====================================================================
    // Tenancy
    // =====================================================================

    @Nested
    @DisplayName("Tenancy — an id is never an existence oracle")
    class Tenancy {

        @Test
        @DisplayName("GET of another company's schedule is 404, not 403")
        void anotherCompanysScheduleIs404() throws Exception {
            MvcResult refused = mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/run-schedules/"
                                    + umbraSchedule)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn();

            assertThat(refused.getResponse().getStatus())
                    .as("§6t — 403 here would confirm the id names something real")
                    .isEqualTo(404);
            assertThat(refused.getResponse().getContentAsString())
                    .as("bodiless, per the contract's NotFound response").isEmpty();
        }

        @Test
        @DisplayName("a caller in the other company cannot reach this project at all")
        void anOutsiderIs404() throws Exception {
            assertThat(get(OUTSIDER, UMBRA, ACME_BANKING, null).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the list is scoped to the company — Umbra's schedule is invisible here")
        void theListIsScopedToTheCompany() throws Exception {
            post(LEAD, ACME, ACME_BANKING,
                    payload("Nightly smoke", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))));

            JsonNode listed = body(get(LEAD, ACME, ACME_BANKING, null));

            assertThat(listed).hasSize(1);
            assertThat(listed.get(0).get("name").asText()).isEqualTo("Nightly smoke");
        }

        @Test
        @DisplayName("a PUT on a schedule id from another company is 404")
        void aPutOnAForeignScheduleIs404() throws Exception {
            assertThat(put(LEAD, ACME, ACME_BANKING, umbraSchedule,
                    payload("Stolen", WEEKDAY_MORNING, SOFIA, scope(List.of(SMOKE_SUITE))))
                    .getResponse().getStatus()).isEqualTo(404);
        }
    }

    // =====================================================================
    // HTTP helpers
    // =====================================================================

    private MvcResult post(UUID userId, UUID companyId, UUID projectId, Map<String, Object> body)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/run-schedules")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult put(
            UUID userId, UUID companyId, UUID projectId, UUID scheduleId, Map<String, Object> body)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/run-schedules/" + scheduleId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, UUID projectId, UUID scheduleId)
            throws Exception {

        String path = "/api/v1/projects/" + projectId + "/run-schedules"
                + (scheduleId == null ? "" : "/" + scheduleId);
        return mvc.perform(MockMvcRequestBuilders.get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult delete(UUID userId, UUID companyId, UUID projectId, UUID scheduleId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/run-schedules/" + scheduleId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private static Map<String, Object> payload(
            String name, String cron, String zone, Map<String, Object> scope) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("cronExpression", cron);
        body.put("zoneId", zone);
        body.put("scope", scope);
        body.put("configurationId", null);
        body.put("assigneeId", null);
        body.put("enabled", true);
        return body;
    }

    /** A scope naming suites and nothing else — the shape most of these arms need. */
    private static Map<String, Object> scope(List<UUID> suiteIds) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("planId", null);
        scope.put("caseIds", List.of());
        scope.put("suiteIds", suiteIds.stream().map(UUID::toString).toList());
        scope.put("tags", List.of());
        return scope;
    }

    private UUID createdId(MvcResult created) throws Exception {
        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private Map<String, List<String>> fieldErrors(MvcResult result) throws Exception {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        JsonNode node = body(result).get("errors");
        if (node != null) {
            node.fieldNames().forEachRemaining(field -> {
                List<String> messages = new java.util.ArrayList<>();
                node.get(field).forEach(message -> messages.add(message.asText()));
                errors.put(field, messages);
            });
        }
        return errors;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    // =====================================================================
    // Fixture
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
        UUID id = UUID.nameUUIDFromBytes(("run-schedule:" + marker).getBytes());
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
                execute(connection, "DELETE FROM run_schedule");
                execute(connection, "DELETE FROM step_result");
                execute(connection, "DELETE FROM test_execution");
                execute(connection, "DELETE FROM test_run");
                execute(connection, "DELETE FROM test_case_version");
                execute(connection, "DELETE FROM test_step");
                execute(connection, "DELETE FROM case_tag");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite_version");
                execute(connection, "DELETE FROM suite_tag_rule");
                execute(connection, "DELETE FROM suite");
                execute(connection, "DELETE FROM project_settings");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
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
            suite(connection, SMOKE_SUITE, ACME, ACME_BANKING, "TS-1", 1, "Smoke");
            tagRule(connection, ACME, SMOKE_SUITE, "smoke");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "TS-1", 1, "Smoke");
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
            Connection connection, UUID id, UUID companyId, UUID projectId,
            String key, long keySeq, String name) throws SQLException {

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

    private void tagRule(Connection connection, UUID companyId, UUID suiteId, String tag)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO suite_tag_rule (company_id, suite_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private void tagCase(UUID companyId, UUID caseId, String tag) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    /** Authored through the real {@code POST /cases}, so the fixture is checked by the product. */
    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by RunScheduleEndpointTest.");
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

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("%s", created.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    /** A run made by hand, so the delete arm can prove one outlives the schedule. */
    private UUID createRunManually() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Made by a person");
        body.put("planId", null);
        body.put("configurationId", null);
        body.put("releaseVersion", null);
        body.put("buildNumber", null);
        body.put("commitSha", null);
        body.put("branch", null);
        body.put("caseIds", List.of(smokeCase.toString()));
        body.put("suiteIds", List.of());
        body.put("tags", List.of());
        body.put("assigneeId", null);
        body.put("overrideApprovalGate", null);

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + ACME_BANKING + "/runs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("%s", created.getResponse().getContentAsString()).isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    // =====================================================================
    // Reading rows back, past the API
    // =====================================================================

    private void pointLastRunAt(UUID scheduleId, UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE run_schedule SET last_run_id = CAST(? AS uuid) "
                             + "WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.setString(2, scheduleId.toString());
            statement.executeUpdate();
        }
    }

    private long scheduleCount(UUID companyId, UUID projectId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM run_schedule WHERE project_id = CAST(? AS uuid)")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private long liveRunCount(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM test_run WHERE deleted_at IS NULL");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private List<String> auditActions(UUID companyId) throws SQLException {
        List<String> actions = new java.util.ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT action FROM audit_event ORDER BY occurred_at, id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                actions.add(rows.getString(1));
            }
        }
        return actions;
    }
}
