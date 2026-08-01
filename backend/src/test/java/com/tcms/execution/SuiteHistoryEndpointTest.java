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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * {@code GET /projects/{projectId}/suites/{suiteId}/history} over HTTP (plan §6p).
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The subtree not being a subtree.</strong> The contract scopes a folder's history to
 *       the folder <em>and its descendants</em>. An implementation reading only the suite's own
 *       {@code suite_id} rows makes every intermediate folder report an empty history — which renders
 *       as "never executed" for a subtree that runs nightly. The fixture puts one case directly in
 *       the parent and one in a child, so own-only and subtree give different totals, and a sibling
 *       folder's case is in the same project to catch the opposite error.</li>
 *   <li><strong>Unrecorded rows being dropped.</strong> This is the one place this endpoint
 *       deliberately disagrees with {@link CaseHistoryEndpointTest}: a timeline drops rows nobody
 *       recorded because an execution that has not happened is not history, while a suite's entry is
 *       a statement about <em>coverage</em> — so the same rows are its {@code notRun}. Both rules are
 *       asserted, here and there, because the pair is easy to make consistent in the wrong
 *       direction.</li>
 *   <li><strong>A run with nothing recorded vanishing.</strong> It still covered the suite, its
 *       {@code executedAt} falls back to the run's own creation, and the contract marks that field
 *       required — so a null there is a page that cannot sort.</li>
 *   <li><strong>Today's suite version stamped on an old run.</strong> {@code versionAt} resolves the
 *       mark in force at the time. A v2 authored between two runs must leave the earlier one on v1;
 *       reading {@code suite.current_version_no} passes every other assertion in this class.</li>
 *   <li><strong>A deleted run's entry vanishing.</strong> §6p, same rule and same pair of halves as
 *       the case timeline: the row survives AND is marked.</li>
 * </ol>
 *
 * <p>Ids are unique to this class — the {@code 3e…} prefix, checked against every other test
 * class rather than assumed: {@code 5a…} was taken by {@code SuiteEndpointTest}, and two classes
 * sharing a company and a user collide on {@code uq_role_assignment_user} in whichever one runs
 * second — a failure that names neither of them.
 */
@AutoConfigureMockMvc
@DisplayName("GET /suites/{id}/history — one folder's pass rate, run by run")
class SuiteHistoryEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("3e000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("3e000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("3e001000-0000-4000-8000-000000000001");
    private static final UUID ACME_MOBILE = UUID.fromString("3e001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("3e002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("3e003000-0000-4000-8000-000000000001");
    private static final UUID ENGINEER = UUID.fromString("3e003000-0000-4000-8000-000000000002");
    private static final UUID NO_GRANT = UUID.fromString("3e003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("3e003000-0000-4000-8000-000000000005");

    private static final UUID ABSENT = UUID.fromString("3e00ffff-0000-4000-8000-00000000ffff");

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

    /** The folder under test — holds one case of its own and one child folder. */
    private UUID payments;

    /** Beneath {@link #payments}. Its case must count toward the parent and not the sibling. */
    private UUID refunds;

    /** A root folder beside {@link #payments}, in the same project. The leak this catches. */
    private UUID login;

    /** A folder with no cases at all — the 200-with-nothing branch, which must not be a 404. */
    private UUID empty;

    /** In the sibling project. Reachable to LEAD, and still a 404 through this project's path. */
    private UUID mobileSuite;

    private UUID paymentsCase;
    private UUID refundsCase;
    private UUID loginCase;

    private UUID defaultConfiguration;

    /** Every run this class creates, so the fixture can be inspected without re-deriving ids. */
    private final Map<String, UUID> runs = new LinkedHashMap<>();

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "sh-acme", "Acme", now);
            company(UMBRA, "sh-umbra", "Umbra", now);

            activeUser(LEAD, "lead@sh-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@sh-acme.test", "E. Engineer", now);
            activeUser(NO_GRANT, "nogrant@sh-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@sh-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "SHAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "SHAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("sh-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("sh-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("sh-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "SHUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("sh-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedProjectDefaults();

        // Through the real POST /suites, so the ltree paths this endpoint's subtree query depends on
        // are built by the writer that builds them in production — and so each folder gets the v1
        // suite_version row the marker rail reads.
        payments = createSuite("Payments", null);
        refunds = createSuite("Refunds", payments);
        login = createSuite("Login", null);
        empty = createSuite("Reporting", null);
        mobileSuite = createSuite(ACME_MOBILE, "Mobile", null);

        paymentsCase = createCase(ACME_BANKING, "Capture a payment", payments);
        refundsCase = createCase(ACME_BANKING, "Refund the whole captured amount", refunds);
        loginCase = createCase(ACME_BANKING, "Sign in with a password", login);

        defaultConfiguration = configurationOf(ACME_BANKING);

        buildTheHistory();
    }

    /**
     * The fixture, in order.
     *
     * <pre>
     *   day -3  R1  every case          payments PASSED, refunds PASSED, login FAILED
     *   day -2  R2  every case          payments FAILED, refunds nothing recorded
     *           ─ suite_version v2 for Payments ─
     *   day -1  R3  every case          payments PASSED, refunds PASSED
     *           R4  every case          nothing recorded at all
     *           R5  refunds only        PASSED, then withdrawn
     * </pre>
     *
     * <p>So Payments' subtree sees five runs; Login sees the three that recorded it; and the counts
     * differ from every own-cases-only or whole-project reading.
     */
    private void buildTheHistory() throws Exception {
        // The suites were created moments ago and every run below is displaced into the past, so
        // without this every v1 mark lands AFTER the results it precedes — the same inversion
        // CaseHistoryEndpointTest.moveVersionInTime exists for, and the shape that would make a
        // reader think the folder was created after it was executed.
        for (UUID suiteId : List.of(payments, refunds, login, empty)) {
            moveSuiteVersionInTime(suiteId, 1, 96);
        }

        UUID r1 = createRun("Nightly 1", "4.2", paymentsCase, refundsCase, loginCase);
        record(r1, paymentsCase, passedStatus());
        record(r1, refundsCase, passedStatus());
        record(r1, loginCase, failedStatus());
        moveRunInTime(r1, 3);

        UUID r2 = createRun("Nightly 2", "4.2", paymentsCase, refundsCase, loginCase);
        record(r2, paymentsCase, failedStatus());
        // refundsCase and loginCase left unrecorded on purpose — the notRun assertions read them.
        moveRunInTime(r2, 2);

        // A second composition of Payments, 36 hours ago: after R2 and before R3. Written by SQL
        // because nothing writes a v2 yet — SuiteVersions writes v1 at creation and the moves that
        // would change a composition are not built. The row shape is what the endpoint reads, which
        // is the same licence CaseHistoryEndpointTest takes for a soft-deleted run.
        writeSuiteVersion(payments, 2, "Moved the disputed-charge cases in", 36);

        UUID r3 = createRun("Nightly 3", "4.3", paymentsCase, refundsCase, loginCase);
        record(r3, paymentsCase, passedStatus());
        record(r3, refundsCase, passedStatus());
        record(r3, loginCase, passedStatus());
        moveRunInTime(r3, 1);

        // Opened and never touched. Every row unrecorded, so the entry exists only if a run that
        // covered the suite counts as history.
        createRun("Opened, not started", null, paymentsCase, refundsCase, loginCase);

        UUID r5 = createRun("Withdrawn run", "4.3", refundsCase);
        record(r5, refundsCase, passedStatus());
        moveRunInTime(r5, 1);
        softDeleteRun(r5);
    }

    // =====================================================================
    // The subtree — the scope decision the contract states
    // =====================================================================

    @Nested
    @DisplayName("Scope — a folder's history is the folder AND everything beneath it")
    class Subtree {

        /**
         * <strong>The assertion this endpoint was written for.</strong> Payments holds one case; its
         * child holds another. Own-cases-only answers 1 per run and whole-project answers 3.
         */
        @Test
        @DisplayName("the parent counts its own cases and its child's, and no others")
        void theParentCoversItsSubtree() throws Exception {
            JsonNode entry = entryFor(payments, "Nightly 1");

            assertThat(entry.get("counts").get("total").asInt())
                    .as("Payments' own case plus Refunds' — and NOT Login's, which is a root "
                            + "folder beside it in the same project")
                    .isEqualTo(2);
            assertThat(entry.get("counts").get("passed").asInt()).isEqualTo(2);
            assertThat(entry.get("counts").get("failed").asInt())
                    .as("Login failed in this run; counting it here is the leak")
                    .isZero();
        }

        @Test
        @DisplayName("the child covers only itself — the subtree does not run upwards")
        void theChildCoversOnlyItself() throws Exception {
            assertThat(entryFor(refunds, "Nightly 1").get("counts").get("total").asInt())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a folder with no cases anywhere beneath it is a 200 of nothing, not a 404")
        void anEmptyFolder() throws Exception {
            JsonNode body = history(LEAD, empty);

            assertThat(body.get("entries"))
                    .as("an empty folder exists and has a history of nothing; 404 would tell a "
                            + "reader their suite is gone")
                    .isEmpty();
            assertThat(body.get("versionMarkers"))
                    .as("created through POST /suites, so it has a v1 (§30)")
                    .hasSize(1);
        }
    }

    // =====================================================================
    // Counts — where this endpoint deliberately disagrees with the case timeline
    // =====================================================================

    @Nested
    @DisplayName("Counts — an unrecorded row is coverage here, and not history there")
    class Counts {

        /**
         * The contrast that makes the two rules a decision rather than an inconsistency. The same
         * three rows are {@code notRun} on this screen and absent from the case timeline.
         */
        @Test
        @DisplayName("rows nobody recorded are notRun, not dropped")
        void unrecordedRowsAreNotRun() throws Exception {
            JsonNode counts = entryFor(payments, "Nightly 2").get("counts");

            assertThat(counts.get("total").asInt()).isEqualTo(2);
            assertThat(counts.get("failed").asInt()).isEqualTo(1);
            assertThat(counts.get("notRun").asInt())
                    .as("Refunds' case was materialised into this run and never recorded — the "
                            + "run covered it, so it is outstanding rather than nonexistent")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the buckets partition the total, which is what the denominator toggle needs")
        void thePartitionHolds() throws Exception {
            for (JsonNode entry : history(LEAD, payments).get("entries")) {
                JsonNode counts = entry.get("counts");
                int sum = counts.get("passed").asInt()
                        + counts.get("failed").asInt()
                        + counts.get("blocked").asInt()
                        + counts.get("skipped").asInt()
                        + counts.get("notRun").asInt();

                assertThat(sum)
                        .as("lib/results.ts derives every percentage from this arithmetic; a total "
                                + "that is not the sum makes both denominators wrong at once")
                        .isEqualTo(counts.get("total").asInt());
            }
        }

        @Test
        @DisplayName("a run that recorded nothing still has an entry, timestamped by the run")
        void anUntouchedRunIsStillHistory() throws Exception {
            JsonNode entry = entryFor(payments, "Opened, not started");

            assertThat(entry.get("counts").get("notRun").asInt()).isEqualTo(2);
            assertThat(entry.get("counts").get("passed").asInt()).isZero();
            assertThat(entry.get("executedAt").isNull())
                    .as("the contract marks executedAt required and the page sorts by it, so the "
                            + "run's own start is the fallback rather than null")
                    .isFalse();
        }
    }

    // =====================================================================
    // The suite version in force — not the one in force now
    // =====================================================================

    @Nested
    @DisplayName("Suite version — the composition at the time of the run")
    class Versions {

        /**
         * Reading {@code suite.current_version_no} — the tempting shortcut — passes every other
         * assertion in this class and fails this one, which is why it is here.
         */
        @Test
        @DisplayName("runs before the v2 edit stay on v1; runs after it read v2")
        void theVersionIsResolvedAtTheMoment() throws Exception {
            assertThat(entryFor(payments, "Nightly 1").get("suiteVersionNo").asInt())
                    .as("three days ago, before the composition changed")
                    .isEqualTo(1);
            assertThat(entryFor(payments, "Nightly 2").get("suiteVersionNo").asInt())
                    .isEqualTo(1);
            assertThat(entryFor(payments, "Nightly 3").get("suiteVersionNo").asInt())
                    .as("one day ago, after the v2 authored 36 hours ago — today's number on an "
                            + "old run puts this year's membership under last year's pass rate")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("the markers name both versions, oldest first, with the comment")
        void theMarkers() throws Exception {
            JsonNode markers = history(LEAD, payments).get("versionMarkers");

            assertThat(markers).hasSize(2);
            assertThat(markers.get(0).get("versionNo").asInt()).isEqualTo(1);
            assertThat(markers.get(1).get("versionNo").asInt()).isEqualTo(2);
            assertThat(markers.get(1).get("changeComment").asText())
                    .isEqualTo("Moved the disputed-charge cases in");
            assertThat(markers.get(0).get("author").get("displayName").asText())
                    .as("resolved through the ACTIVE COMPANY's membership (§6u)")
                    .isEqualTo("L. Lead of Acme");
        }

        @Test
        @DisplayName("a suite whose composition never changed reports v1 everywhere")
        void theUnchangedSuite() throws Exception {
            for (JsonNode entry : history(LEAD, refunds).get("entries")) {
                assertThat(entry.get("suiteVersionNo").asInt()).isEqualTo(1);
            }
        }
    }

    // =====================================================================
    // Deleted runs — §6p's "marked rather than vanishing"
    // =====================================================================

    @Nested
    @DisplayName("A withdrawn run — the entry survives it, and says so")
    class WithdrawnRuns {

        @Test
        @DisplayName("the entry is still there AND it is marked")
        void theEntrySurvivesAndIsMarked() throws Exception {
            JsonNode entry = entryFor(refunds, "Withdrawn run");

            assertThat(entry.get("runDeleted").asBoolean())
                    .as("unmarked, a withdrawn result reads as a live one and the client offers "
                            + "navigation into a run that answers 404")
                    .isTrue();
        }

        @Test
        @DisplayName("the live runs are not marked, so the flag discriminates")
        void liveRunsAreNotMarked() throws Exception {
            assertThat(entryFor(refunds, "Nightly 1").get("runDeleted").asBoolean())
                    .as("a blanket true would pass the assertion above while marking everything")
                    .isFalse();
        }
    }

    // =====================================================================
    // The ordinary states, and the two gates
    // =====================================================================

    @Nested
    @DisplayName("The states a real installation is in")
    class OrdinaryStates {

        /**
         * One row per run, never one per execution — and the two suites answer different sets,
         * which is what shows the entry list is derived from the subtree rather than from the
         * project's runs.
         */
        @Test
        @DisplayName("one entry per run, and only the runs that covered the suite")
        void oneEntryPerRun() throws Exception {
            assertThat(runNames(payments))
                    .as("the withdrawn run covered Refunds, which is BENEATH Payments, so it "
                            + "counts here too")
                    .containsExactlyInAnyOrder(
                            "Nightly 1", "Nightly 2", "Nightly 3", "Opened, not started",
                            "Withdrawn run");

            assertThat(runNames(login))
                    .as("Login is a root folder beside Payments; the run that covered only "
                            + "Refunds never touched it")
                    .containsExactlyInAnyOrder("Nightly 1", "Nightly 2", "Nightly 3",
                            "Opened, not started");
        }

        private List<String> runNames(UUID suiteId) throws Exception {
            List<String> names = new ArrayList<>();
            for (JsonNode entry : history(LEAD, suiteId).get("entries")) {
                names.add(entry.get("runName").asText());
            }
            return names;
        }

        @Test
        @DisplayName("entries are oldest first, which is the order they happened in")
        void oldestFirst() throws Exception {
            Instant previous = null;
            for (JsonNode entry : history(LEAD, payments).get("entries")) {
                Instant at = Instant.parse(entry.get("executedAt").asText());
                if (previous != null) {
                    assertThat(at).isAfterOrEqualTo(previous);
                }
                previous = at;
            }
        }

        @Test
        @DisplayName("an entry names its run, its configuration and its release")
        void theEntryIsReadable() throws Exception {
            JsonNode entry = entryFor(payments, "Nightly 3");

            assertThat(entry.get("runKey").asText()).startsWith("TR-");
            assertThat(entry.get("configuration").asText()).isEqualTo("Default");
            assertThat(entry.get("releaseVersion").asText()).isEqualTo("4.3");
            assertThat(entry.get("runId").asText()).isEqualTo(runs.get("Nightly 3").toString());
        }
    }

    @Nested
    @DisplayName("The two gates — 404, never 403")
    class Gates {

        @Test
        @DisplayName("a suite in a sibling project is a 404 through this project's path")
        void aSiblingProjectsSuite() throws Exception {
            assertThat(rawResult(LEAD, ACME, path(mobileSuite)).getResponse().getStatus())
                    .as("LEAD holds a grant on BOTH projects, so only the project predicate on "
                            + "the suite lookup can refuse this")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a member with no project grant gets 404, not 403")
        void noGrant() throws Exception {
            assertThat(rawResult(NO_GRANT, ACME, path(payments)).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("another company's user cannot reach it")
        void anotherCompany() throws Exception {
            assertThat(rawResult(OUTSIDER, UMBRA, path(payments)).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an absent suite id is a 404, not a 200 of nothing")
        void absentSuite() throws Exception {
            assertThat(rawResult(LEAD, ACME, path(ABSENT)).getResponse().getStatus())
                    .as("200-with-nothing for a suite that does not exist is how a typo in a URL "
                            + "reads as a folder nobody has ever run")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("no token is a 401")
        void anonymous() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1" + path(payments))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }

        /**
         * The parameter §50's binding check would otherwise fail the build over. Deleted from the
         * contract rather than implemented, because nothing sends it — so the server must ignore it
         * exactly as it ignores any other unknown key, rather than narrowing the answer.
         */
        @Test
        @DisplayName("an undeclared versionNo is ignored, not honoured")
        void theDeletedFilterIsInert() throws Exception {
            assertThat(raw(LEAD, ACME, path(payments) + "?versionNo=1").toString())
                    .isEqualTo(history(LEAD, payments).toString());
        }
    }

    // =====================================================================
    // helpers — the request
    // =====================================================================

    private static String path(UUID suiteId) {
        return "/projects/" + ACME_BANKING + "/suites/" + suiteId + "/history";
    }

    private JsonNode history(UUID userId, UUID suiteId) throws Exception {
        return raw(userId, ACME, path(suiteId));
    }

    /** The one entry for a named run, asserted present — every count assertion reads through this. */
    private JsonNode entryFor(UUID suiteId, String runName) throws Exception {
        for (JsonNode entry : history(LEAD, suiteId).get("entries")) {
            if (entry.get("runName").asText().equals(runName)) {
                return entry;
            }
        }
        throw new AssertionError("no entry for run '" + runName + "' on suite " + suiteId);
    }

    private JsonNode raw(UUID userId, UUID companyId, String path) throws Exception {
        MvcResult result = rawResult(userId, companyId, path);
        assertThat(result.getResponse().getStatus())
                .as("%s", result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result);
    }

    private MvcResult rawResult(UUID userId, UUID companyId, String path) throws Exception {
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

    // =====================================================================
    // helpers — the fixture
    // =====================================================================

    private UUID createSuite(String name, UUID parentId) throws Exception {
        return createSuite(ACME_BANKING, name, parentId);
    }

    private UUID createSuite(UUID projectId, String name, UUID parentId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("parentId", parentId == null ? null : parentId.toString());

        return UUID.fromString(created(
                "/api/v1/projects/" + projectId + "/suites", LEAD, ACME, payload)
                .get("id").asText());
    }

    private UUID createCase(UUID projectId, String title, UUID suiteId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by SuiteHistoryEndpointTest.");
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

        return UUID.fromString(created(
                "/api/v1/projects/" + projectId + "/cases", LEAD, ACME, payload)
                .get("id").asText());
    }

    private UUID createRun(String name, String releaseVersion, UUID... caseIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("planId", null);
        payload.put("configurationId", defaultConfiguration.toString());
        payload.put("releaseVersion", releaseVersion);
        payload.put("buildNumber", null);
        payload.put("caseIds", java.util.Arrays.stream(caseIds).map(UUID::toString).toList());
        payload.put("assigneeId", null);

        UUID id = UUID.fromString(created(
                "/api/v1/projects/" + ACME_BANKING + "/runs", LEAD, ACME, payload)
                .get("id").asText());
        runs.put(name, id);
        return id;
    }

    /** Records a verdict through the real PATCH, so the fixture exercises the writer. */
    private void record(UUID runId, UUID caseId, UUID statusId) throws Exception {
        UUID executionId = executionOf(runId, caseId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("statusId", statusId.toString());
        payload.put("comment", null);

        MvcResult recorded = mvc.perform(MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + ACME_BANKING + "/runs/" + runId
                                + "/executions/" + executionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(ENGINEER, ACME))
                        .header(HttpHeaders.IF_MATCH, "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(recorded.getResponse().getStatus())
                .as("the fixture depends on the real result endpoint; %s",
                        recorded.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode created(String path, UUID userId, UUID companyId, Map<String, Object> payload)
            throws Exception {

        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
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
        UUID id = UUID.nameUUIDFromBytes(("suite-history:" + marker).getBytes());
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
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedProjectDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
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

    /**
     * Moves a run's recorded results {@code days} into the past.
     *
     * <p>{@code CaseHistoryEndpointTest}'s discipline: recorded through the API for realism, then
     * displaced through SQL for determinism. Runs recorded inside one method land microseconds
     * apart, which would make every ordering assertion here a coin flip.
     *
     * <p>{@code created_at} moves too, unlike on the case timeline — this endpoint falls back to it
     * for a run with nothing recorded, and leaving it at wall-clock now would put an untouched run
     * ahead of results from tomorrow's perspective.
     */
    private void moveRunInTime(UUID runId, int days) throws SQLException {
        Instant when = Instant.now().minus(days, ChronoUnit.DAYS);
        try (Connection connection = appConnection(ACME)) {
            try (var statement = connection.prepareStatement(
                    "UPDATE test_execution SET executed_at = ? "
                            + "WHERE run_id = CAST(? AS uuid) AND executed_at IS NOT NULL")) {
                statement.setObject(1, when.atOffset(ZoneOffset.UTC));
                statement.setString(2, runId.toString());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "UPDATE test_run SET created_at = ? WHERE id = CAST(? AS uuid)")) {
                statement.setObject(1, when.atOffset(ZoneOffset.UTC));
                statement.setString(2, runId.toString());
                statement.executeUpdate();
            }
        }
    }

    /** Backdates an authored suite version, so the marks precede the results they explain. */
    private void moveSuiteVersionInTime(UUID suiteId, int versionNo, int hours) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE suite_version SET created_at = ? "
                             + "WHERE suite_id = CAST(? AS uuid) AND version_no = ?")) {
            statement.setObject(1, Instant.now().minus(hours, ChronoUnit.HOURS)
                    .atOffset(ZoneOffset.UTC));
            statement.setString(2, suiteId.toString());
            statement.setInt(3, versionNo);
            statement.executeUpdate();
        }
    }

    /** Withdraws a run the way the product will — soft delete only (§2), so the executions stay. */
    private void softDeleteRun(UUID runId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "UPDATE test_run SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, runId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Writes a suite version {@code hours} into the past.
     *
     * <p>By SQL because nothing writes a v2 yet — {@code SuiteVersions} writes v1 at creation and
     * the composition-changing moves are not built. Asserting the resolution rule now is what stops
     * {@code suite.current_version_no} from creeping into the service on the day one is.
     */
    private void writeSuiteVersion(UUID suiteId, int versionNo, String comment, int hours)
            throws SQLException {

        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     INSERT INTO suite_version
                         (id, company_id, suite_id, version_no, snapshot, content_hash,
                          change_comment, created_at, created_by)
                     VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), ?,
                             CAST('{}' AS jsonb), 'sh-fixture', ?, ?, CAST(? AS uuid))
                     """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, suiteId.toString());
            statement.setInt(3, versionNo);
            statement.setString(4, comment);
            statement.setObject(5, Instant.now().minus(hours, ChronoUnit.HOURS)
                    .atOffset(ZoneOffset.UTC));
            statement.setString(6, LEAD.toString());
            statement.executeUpdate();
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
