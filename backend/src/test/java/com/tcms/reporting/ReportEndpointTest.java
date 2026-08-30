package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.auth.SessionTokens;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The three reporting operations over HTTP, and the two permission keys that decide them.
 *
 * <h2>Why this class exists at all: until it, both gates were decorative</h2>
 *
 * <p>{@code ReportJobPipelineTest} drives the same pipeline with no HTTP in the way and proves what
 * it produces. What it cannot prove is that anything is <strong>refused</strong>, and neither could
 * any endpoint test written before this one — because <strong>no persona the suite was able to build
 * lacked either report key</strong>. {@code V3} grants {@code report:view} and {@code report:export}
 * together to Instance Admin, Company Admin, Test Lead, Test Engineer and Viewer; the only built-in
 * withheld from them is Automation Service, which is attached to API tokens (M3, unbuilt) and which
 * also holds no {@code project:read}, so it 404s on the visibility gate one step earlier and never
 * reaches a permission check at all. Every gate assertion available from the built-in roles is
 * therefore satisfied by an implementation with <em>no gate</em>, and the mutation that deletes one
 * reddens nothing.
 *
 * <h2>The first custom roles in the suite, and there are two of them</h2>
 *
 * <p>{@code role} is instance-level with no {@code company_id} and a global unique name, so a test
 * may insert one. The ERD's <em>"there are no custom roles in v1"</em> is a statement about the
 * product — no endpoint creates one, nothing offers one, and this fixture does not change that.
 *
 * <p><strong>One custom role would not have been enough</strong>, and this is the whole design of
 * the fixture. A single persona holding neither key gets 403 from all three operations, which proves
 * that <em>a</em> gate runs and says nothing about <em>which key</em> it asks for: swapping
 * {@code report:export} for {@code report:view} on generate would keep every assertion green. So
 * there are two:
 *
 * <ul>
 *   <li><strong>{@link #SCREEN_ONLY}</strong> holds {@code report:view} and <em>not</em>
 *       {@code report:export} — the role §6e's two-key split exists to make expressible, "may read
 *       reports on screen, may not carry them off". They poll a job (200) and are refused the
 *       generate and the download (403). This persona is what makes the two keys distinguishable:
 *       each of the three operations answers them differently depending on which key it asks for.</li>
 *   <li><strong>{@link #REPORT_BLIND}</strong> holds neither, and is what keeps the <em>poll's</em>
 *       gate non-vacuous — with only the persona above, deleting the poll's permission check
 *       entirely leaves every assertion passing.</li>
 * </ul>
 *
 * <p>Both hold {@code project:read} (which <em>is</em> visibility — {@code ProjectAccess.canView})
 * and {@code case:read}, so their refusals are 403 and not the 404 a caller with no grant gets. That
 * distinction is the other half of §87's {@code readGranted}: a caller who is demonstrably looking
 * at the project is told they may not export it, rather than told their own request does not exist.
 *
 * <h2>The fixture rows another test class asserts the absence of</h2>
 *
 * <p>{@code SeedDataTest.nineBuiltInRoles} asserts that {@code SELECT name FROM role WHERE NOT
 * built_in} is <strong>empty</strong>. A leaked custom role therefore reddens a class that has
 * nothing to do with reporting, in the full suite only, at a position that moves — §19's
 * fixture-collision defect through a mechanism no UUID prefix protects against. Hence
 * {@link #dropTheCustomRoles()}: {@code @AfterAll} rather than {@code @AfterEach} because the rows
 * are seeded once and read by every method, and the assignments go first because
 * {@code role_assignment.role_id} has no {@code ON DELETE CASCADE} ({@code role_permission} does).
 *
 * <p>Ids are {@code ae…} — hex, because a non-hex prefix throws in the static initialiser and fails
 * every test in the class before one runs ({@code ExecutionFactRefreshTest}'s header records the run
 * that cost). Slugs are {@code re-…}.
 */
@AutoConfigureMockMvc
@DisplayName("Reporting endpoints — the three operations, and the two keys that decide them")
class ReportEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ae000000-0000-4000-8000-000000000001");
    private static final UUID UMBRA = UUID.fromString("ae000000-0000-4000-8000-000000000002");

    private static final UUID PROJECT = UUID.fromString("ae001000-0000-4000-8000-000000000001");
    /** A second Acme project LEAD also holds, so "the id resolves in the wrong path" is testable. */
    private static final UUID SIBLING = UUID.fromString("ae001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_PROJECT = UUID.fromString("ae001000-0000-4000-8000-000000000003");

    private static final UUID RUN = UUID.fromString("ae002000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_RUN = UUID.fromString("ae002000-0000-4000-8000-000000000002");

    /** Test Lead — holds both report keys, as most of the built-ins do. */
    private static final UUID LEAD = UUID.fromString("ae003000-0000-4000-8000-000000000001");
    /** Custom role: {@code report:view} and NOT {@code report:export}. */
    private static final UUID SCREEN_ONLY = UUID.fromString("ae003000-0000-4000-8000-000000000002");
    /** Custom role: neither report key. */
    private static final UUID REPORT_BLIND = UUID.fromString("ae003000-0000-4000-8000-000000000003");
    /** An ACTIVE member of Acme with no grant on any project — the 404 persona. */
    private static final UUID NO_GRANT = UUID.fromString("ae003000-0000-4000-8000-000000000004");

    private static final UUID SCREEN_ONLY_ROLE =
            UUID.fromString("ae004000-0000-4000-8000-000000000001");
    private static final UUID REPORT_BLIND_ROLE =
            UUID.fromString("ae004000-0000-4000-8000-000000000002");

    private static final String SCREEN_ONLY_ROLE_NAME = "Report Reader (§87 test fixture)";
    private static final String REPORT_BLIND_ROLE_NAME = "Report Blind (§87 test fixture)";

    private static final String RUN_SUMMARY = "run_summary";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private SessionTokens tokens;

    @Autowired
    private ReportJobRunner runner;

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
            company(ACME, "re-acme", "Acme", now);
            company(UMBRA, "re-umbra", "Umbra", now);

            activeUser(LEAD, "lead@re.test", "Leonora Lead", now);
            activeUser(SCREEN_ONLY, "screen@re.test", "S. Only", now);
            activeUser(REPORT_BLIND, "blind@re.test", "R. Blind", now);
            activeUser(NO_GRANT, "nogrant@re.test", "N. Grant", now);
        });

        customRole(SCREEN_ONLY_ROLE, SCREEN_ONLY_ROLE_NAME,
                List.of("project:read", "case:read", "report:view"));
        customRole(REPORT_BLIND_ROLE, REPORT_BLIND_ROLE_NAME,
                List.of("project:read", "case:read"));

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "REONE", "Online Banking", now);
            seedProject(SIBLING, ACME, "RETWO", "Mobile App", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, SCREEN_ONLY, "S. Only", now);
            member(ACME, REPORT_BLIND, "R. Blind", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("lead", ACME, Role.TEST_LEAD_ID, LEAD, PROJECT, now);
            grantOnProject("lead-sibling", ACME, Role.TEST_LEAD_ID, LEAD, SIBLING, now);
            grantOnProject("screen", ACME, SCREEN_ONLY_ROLE, SCREEN_ONLY, PROJECT, now);
            grantOnProject("blind", ACME, REPORT_BLIND_ROLE, REPORT_BLIND, PROJECT, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_PROJECT, UMBRA, "REUMB", "Umbra Product", now)));

        clearJobs();

        try (Connection acme = appConnection(ACME)) {
            seedProjectDefaults(acme, ACME, PROJECT);
            seedProjectDefaults(acme, ACME, SIBLING);
            // A run with no executions on purpose: this class is about the doors, and a run summary
            // over an empty run is a complete, renderable report that says nothing was recorded.
            // What the report CONTAINS is ReportJobPipelineTest's and RunSummaryReportTest's
            // subject, and duplicating their execution fixture here would be a second copy of it
            // maintained for assertions this class never makes.
            run(acme, RUN, ACME, PROJECT, "TR-1", 1, "Release 5.0 regression");
            // TR-2 rather than a second TR-1: uq_test_run_key is (company_id, key), so run keys are
            // unique per COMPANY and not per project — the two projects here share a company.
            run(acme, SIBLING_RUN, ACME, SIBLING, "TR-2", 2, "Mobile smoke");
        }
    }

    /**
     * Removes the two custom roles, and it is not housekeeping.
     *
     * <p>{@code SeedDataTest} asserts there are no non-built-in roles anywhere, so leaving these
     * behind reddens an unrelated class in the full suite only.
     */
    @AfterAll
    static void dropTheCustomRoles() throws SQLException {
        try (Connection acme = appConnection(ACME)) {
            delete(acme, """
                    DELETE FROM role_assignment
                     WHERE role_id IN (CAST(? AS uuid), CAST(? AS uuid))
                    """);
        }
        try (Connection any = appConnection()) {
            // role_permission is ON DELETE CASCADE; role_assignment above is not.
            delete(any, "DELETE FROM role WHERE id IN (CAST(? AS uuid), CAST(? AS uuid))");
        }
    }

    // =====================================================================
    // POST /reports/generate — report:export
    // =====================================================================

    @Nested
    @DisplayName("Generating — report:export")
    class Generating {

        @Test
        @DisplayName("A RETRY QUEUES NOTHING — same key, 200, and the job it already made (E2)")
        void aReplayedKeyQueuesNothing() throws Exception {
            // The last of the eleven creates in this API to take the header. What a duplicate costs
            // here is not a row: a job refreshes execution_fact for the whole company, computes,
            // renders and writes two artifacts, so a second one is a second full pass — and the
            // caller most likely to retry (CI, or an MCP client) is the one with no job list on
            // screen to notice it.
            String key = "generate-once-" + UUID.randomUUID();

            MvcResult first = generate(LEAD, PROJECT, body(RUN_SUMMARY, "MD"), key);
            MvcResult retry = generate(LEAD, PROJECT, body(RUN_SUMMARY, "MD"), key);

            assertThat(first.getResponse().getStatus()).isEqualTo(202);
            // 200, not 202: this request queued nothing, and the status is the only thing that
            // tells the caller so.
            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(parse(retry).get("jobId").asText())
                    .isEqualTo(parse(first).get("jobId").asText());
            assertThat(retry.getResponse().getHeader("Location"))
                    .as("the same poll either way — what the caller does next is identical")
                    .isEqualTo(first.getResponse().getHeader("Location"));
        }

        @Test
        @DisplayName("a DIFFERENT key is a different job, and no key at all is the old behaviour")
        void adifferentKeyIsADifferentJob() throws Exception {
            String first = parse(generate(LEAD, PROJECT, body(RUN_SUMMARY, "MD"),
                    "gen-a-" + UUID.randomUUID())).get("jobId").asText();
            String second = parse(generate(LEAD, PROJECT, body(RUN_SUMMARY, "MD"),
                    "gen-b-" + UUID.randomUUID())).get("jobId").asText();

            assertThat(first).isNotEqualTo(second);

            // The header is OPTIONAL, so a caller that sends none keeps getting a new job per
            // request — every caller that existed before V66.
            MvcResult keyless = generate(LEAD, PROJECT, body(RUN_SUMMARY, "MD"));
            assertThat(keyless.getResponse().getStatus()).isEqualTo(202);
            assertThat(parse(keyless).get("jobId").asText()).isNotIn(first, second);
        }

        @Test
        @DisplayName("a lead gets 202, the job's own shape, and a Location that resolves")
        void acceptedWithALocation() throws Exception {
            MvcResult result = generate(LEAD, PROJECT, body(RUN_SUMMARY, "MD"));

            assertThat(result.getResponse().getStatus()).isEqualTo(202);

            JsonNode job = parse(result);
            // The accept and the poll answer the same schema — ReportController's header — so every
            // field the contract marks required has to be here too, on the response a client parses
            // once and then never again if it follows Location.
            assertThat(List.of("jobId", "type", "status", "format", "downloadUrl", "modelUrl",
                    "error"))
                    .allSatisfy(field -> assertThat(job.has(field))
                            .as("ReportJob.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(job.get("status").asText()).isEqualTo("PENDING");
            assertThat(job.get("downloadUrl").isNull())
                    .as("nothing has been rendered yet, so there is nothing to link to")
                    .isTrue();

            String location = result.getResponse().getHeader("Location");
            assertThat(location).isEqualTo("/api/v1/projects/" + PROJECT + "/reports/jobs/"
                    + job.get("jobId").asText());
            assertThat(status(LEAD, location))
                    .as("a client that follows Location rather than the body must arrive somewhere")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("report:view is not enough — the screen-only role gets 403, not a job")
        void viewDoesNotGenerate() throws Exception {
            // THE assertion that makes this gate falsifiable. SCREEN_ONLY holds report:view and
            // nothing else in this area, so an implementation asking for the wrong one of the two
            // keys answers 202 here — with every other test in this class still green.
            MvcResult result = generate(SCREEN_ONLY, PROJECT, body(RUN_SUMMARY, "MD"));

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            // 403 and not 404: they can see the project, and §87's readGranted/writeGranted split
            // says what is withheld is a feature of it, not its existence.
            assertThat(parse(result).get("detail").asText()).contains("permission");
            assertThat(jobCount()).isZero();
        }

        @Test
        @DisplayName("a role holding neither key is refused the same way")
        void neitherKeyGenerates() throws Exception {
            assertThat(generate(REPORT_BLIND, PROJECT, body(RUN_SUMMARY, "MD"))
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(jobCount()).isZero();
        }

        @Test
        @DisplayName("a member with no grant on the project gets 404, never 403")
        void noGrantIsNotFound() throws Exception {
            // The visibility half of the gate, which still answers 404 — otherwise the endpoint is
            // an oracle for the ids of projects the caller was never granted (§6t).
            assertThat(generate(NO_GRANT, PROJECT, body(RUN_SUMMARY, "MD"))
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(jobCount()).isZero();
        }

        @Test
        @DisplayName("another client's project is 404 for a lead who holds every key here")
        void crossCompanyIsNotFound() throws Exception {
            assertThat(generate(LEAD, UMBRA_PROJECT, body(RUN_SUMMARY, "MD"))
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("PDF is a 400 at request time, naming the renderer as not installed")
        void pdfIsRefusedImmediately() throws Exception {
            // The contract's format enum offers PDF and this deployment cannot render it. A job
            // that goes FAILED half a minute later is indistinguishable, to a client, from a broken
            // server — so the refusal has to be on the request, in the field's own error.
            MvcResult result = generate(LEAD, PROJECT, body(RUN_SUMMARY, "PDF"));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode errors = parse(result).get("errors");
            assertThat(errors.has("format")).isTrue();
            assertThat(errors.get("format").get(0).asText()).contains("not installed");
            assertThat(jobCount()).isZero();
        }

        @Test
        @DisplayName("an unspellable format takes the same path as an unrenderable one")
        void anUnknownFormatIsTheSame400() throws Exception {
            // Bound as text rather than as ReportFormat precisely so this is the product's sentence
            // about formats and not Jackson's about deserialization.
            MvcResult result = generate(LEAD, PROJECT, body(RUN_SUMMARY, "DOCX"));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(parse(result).get("errors").has("format")).isTrue();
        }
    }

    // =====================================================================
    // GET /reports/jobs/{jobId} — report:view
    // =====================================================================

    @Nested
    @DisplayName("Polling — report:view")
    class Polling {

        @Test
        @DisplayName("the screen-only role may watch a job it could not have asked for")
        void viewIsEnoughToPoll() throws Exception {
            // The other direction of the same split, and the reason the catalogue has two keys: a
            // poll gated on report:export would refuse this caller, and nothing else in the suite
            // would notice.
            UUID jobId = requestAs(LEAD, PROJECT);

            MvcResult result = poll(SCREEN_ONLY, PROJECT, jobId);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(parse(result).get("jobId").asText()).isEqualTo(jobId.toString());
        }

        @Test
        @DisplayName("a role holding neither key gets 403 from the poll")
        void neitherKeyPolls() throws Exception {
            UUID jobId = requestAs(LEAD, PROJECT);

            // Without this persona the poll's gate can be deleted outright and every other
            // assertion in this class stays green.
            assertThat(poll(REPORT_BLIND, PROJECT, jobId).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a completed job answers 200 with the two URLs a client follows")
        void aCompletedJobCarriesItsUrls() throws Exception {
            UUID jobId = requestAs(LEAD, PROJECT);
            runWorker();

            JsonNode job = parse(poll(LEAD, PROJECT, jobId));

            assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(job.get("error").isNull()).isTrue();
            // Server-composed, never built by the client (§67 on Evidence.url) — so they must
            // actually point at this project's download route.
            assertThat(job.get("downloadUrl").asText())
                    .startsWith("/api/v1/projects/" + PROJECT + "/reports/artifacts/")
                    .endsWith("/content");
            // NOT the artifact route, and that is the §227 change: the on-screen model has a route
            // of its own so it can cost report:view. A modelUrl under /artifacts/ is the defect —
            // reading a report would cost exactly what carrying it away costs.
            assertThat(job.get("modelUrl").asText())
                    .isEqualTo("/api/v1/projects/" + PROJECT + "/reports/jobs/" + jobId + "/model");
            assertThat(job.get("requestedAt").isNull())
                    .as("a job exists by having been asked for, so this is never null")
                    .isFalse();
        }

        @Test
        @DisplayName("an unfinished job offers no model URL, so a client never asks for one")
        void anUnfinishedJobHasNoModelUrl() throws Exception {
            UUID jobId = requestAs(LEAD, PROJECT);

            // Keyed on the artifact id rather than on the status: the two agree in the designed
            // system and only the artifact id is false for a job completed by a deployment older
            // than §88's stored model, which is exactly the row that would 404 behind the link.
            assertThat(parse(poll(LEAD, PROJECT, jobId)).get("modelUrl").isNull()).isTrue();
            assertThat(model(LEAD, PROJECT, jobId).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("a job id that names nothing is 404")
        void anUnknownJobIsNotFound() throws Exception {
            assertThat(poll(LEAD, PROJECT, UUID.randomUUID()).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a sibling project's job is 404 even though the caller holds both projects")
        void aJobIsScopedToTheProjectInThePath() throws Exception {
            // "The access control ran, and ran on the wrong row" — the version of this defect that
            // survives a permission test. LEAD is Test Lead on both projects, so every gate passes
            // and only findByIdAndProjectId keeps the job out of the wrong path.
            UUID jobId = requestAs(LEAD, SIBLING);

            assertThat(poll(LEAD, PROJECT, jobId).getResponse().getStatus()).isEqualTo(404);
            assertThat(poll(LEAD, SIBLING, jobId).getResponse().getStatus())
                    .as("the same id resolves in its own project, so the 404 above is the scoping "
                            + "and not a job that failed to be written")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a member with no grant gets 404 rather than 403")
        void noGrantIsNotFound() throws Exception {
            UUID jobId = requestAs(LEAD, PROJECT);

            assertThat(poll(NO_GRANT, PROJECT, jobId).getResponse().getStatus()).isEqualTo(404);
        }
    }

    // =====================================================================
    // GET /reports/artifacts/{artifactId}/content — report:export
    // =====================================================================

    /**
     * The on-screen model — {@code report:view}, and the whole point of the route.
     *
     * <p>{@link #SCREEN_ONLY} is what makes this class falsifiable. Until §227 the model was served
     * through the download route, so that persona — the one §6e's two-key split exists for, and the
     * one this fixture invents a custom role to express — could watch a job succeed and then be
     * refused the report it had just watched being made. Every assertion below is green against the
     * old route if the persona is dropped.
     */
    @Nested
    @DisplayName("The on-screen model — report:view")
    class Model {

        @Test
        @DisplayName("the screen-only role reads the model it may not download")
        void screenOnlyReadsTheModel() throws Exception {
            UUID jobId = requestAs(LEAD, PROJECT);
            runWorker();

            MvcResult result = model(SCREEN_ONLY, PROJECT, jobId);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            // JSON and inline: this is a document to render, not a file to keep — which is the
            // difference the permission split describes, spelled in the response.
            assertThat(result.getResponse().getContentType())
                    .startsWith(MediaType.APPLICATION_JSON_VALUE);
            assertThat(result.getResponse().getHeader("Content-Disposition")).isNull();
            // The stored model, not a recomputation: §6e's "what you see is what exports" is a
            // property only while these bytes are the ones the export was rendered from.
            JsonNode document = json.readTree(result.getResponse().getContentAsString());
            assertThat(document.get("header")).isNotNull();
            assertThat(document.get("report").get("title").asText()).contains("Run summary");
        }

        @Test
        @DisplayName("the same persona is still refused the download — the split is real in both directions")
        void screenOnlyStillCannotDownload() throws Exception {
            // The pair. Without this half the model route could be gated on report:export and the
            // test above would still pass for a persona that happened to hold both.
            assertThat(download(SCREEN_ONLY, PROJECT, artifactOfACompletedJob(PROJECT))
                    .getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a role holding neither key is refused, with no body")
        void neitherKeyReadsTheModel() throws Exception {
            UUID jobId = requestAs(LEAD, PROJECT);
            runWorker();

            MvcResult result = model(REPORT_BLIND, PROJECT, jobId);

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }

        @Test
        @DisplayName("a sibling project's job is 404 through this project's path")
        void theModelIsScopedToTheProjectInThePath() throws Exception {
            UUID jobId = requestAs(LEAD, SIBLING);
            runWorker();

            assertThat(model(LEAD, PROJECT, jobId).getResponse().getStatus()).isEqualTo(404);
            assertThat(model(LEAD, SIBLING, jobId).getResponse().getStatus())
                    .as("the same job's model reads from its own project, so the 404 above is the "
                            + "scoping and not a job that never completed")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a member with no grant gets 404 rather than 403")
        void noGrantIsNotFound() throws Exception {
            UUID jobId = requestAs(LEAD, PROJECT);
            runWorker();

            assertThat(model(NO_GRANT, PROJECT, jobId).getResponse().getStatus()).isEqualTo(404);
        }
    }

    /**
     * The job list — {@code report:view}, and the project's rather than the caller's.
     *
     * <p>The opposite call to {@link Definitions}, one nested class down, and the two are asserted
     * against each other on purpose: a definition is a saved question owned by whoever wrote it,
     * and a job is a report produced about this project. Getting the list "mine" would hide every
     * report requested by somebody who has since left ({@code requested_by} is nullable since §66),
     * which is exactly the history a release wants.
     */
    @Nested
    @DisplayName("Listing jobs — report:view, and the rows are the project's")
    class Listing {

        @Test
        @DisplayName("a project with no jobs answers an empty array, not a 404")
        void anEmptyProjectAnswersEmpty() throws Exception {
            // SIBLING, because `clearJobs()` runs per test and nothing here has asked for one in
            // it. A 404 would be the answer to "this project does not exist", which is a different
            // fact and the one a client must not have to tell apart from "nothing generated yet".
            MvcResult result = jobs(LEAD, SIBLING);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(parse(result)).isEmpty();
        }

        @Test
        @DisplayName("every job the project has, newest first, whoever asked for it")
        void listsTheProjectsJobs() throws Exception {
            UUID first = requestAs(LEAD, PROJECT);
            UUID second = requestAs(LEAD, PROJECT);

            JsonNode rows = parse(jobs(SCREEN_ONLY, PROJECT));

            // SCREEN_ONLY asked for neither, which is the whole assertion: a "mine" reading answers
            // [] here and passes every other test in this class.
            assertThat(ids(rows)).contains(first.toString(), second.toString());
            // Newest first, which is the order the operation declares and the only thing making the
            // list readable without a second sort in every client. Asserted as the *property* —
            // `requestedAt` never rising down the list — rather than as a position: two requests a
            // few milliseconds apart can share a timestamp, and a positional assertion would then
            // be a coin toss rather than a check on the ORDER BY.
            // Parsed rather than compared as text: Jackson writes a variable number of fractional
            // digits, so `…:00Z` and `…:00.5Z` sort the wrong way round as strings.
            java.util.List<Instant> stamps = new java.util.ArrayList<>();
            rows.forEach(row -> stamps.add(Instant.parse(row.get("requestedAt").asText())));
            assertThat(stamps).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        }

        @Test
        @DisplayName("a sibling project's jobs are not in this project's list")
        void theListIsScopedToTheProjectInThePath() throws Exception {
            UUID sibling = requestAs(LEAD, SIBLING);

            assertThat(ids(parse(jobs(LEAD, PROJECT)))).doesNotContain(sibling.toString());
            assertThat(ids(parse(jobs(LEAD, SIBLING))))
                    .as("the same job is in its own project's list, so the absence above is the "
                            + "scoping and not a job that failed to be written")
                    .contains(sibling.toString());
        }

        @Test
        @DisplayName("a role holding neither key gets 403")
        void neitherKeyLists() throws Exception {
            assertThat(jobs(REPORT_BLIND, PROJECT).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a member with no grant gets 404 rather than 403")
        void noGrantIsNotFound() throws Exception {
            assertThat(jobs(NO_GRANT, PROJECT).getResponse().getStatus()).isEqualTo(404);
        }

        private java.util.List<String> ids(JsonNode rows) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            rows.forEach(row -> ids.add(row.get("jobId").asText()));
            return ids;
        }
    }

    @Nested
    @DisplayName("Capabilities — what this deployment can render (B5)")
    class Capabilities {

        @Test
        @DisplayName("the renderers on the classpath, and not the enum a client compiled against")
        void whatThisServerCanRender() throws Exception {
            JsonNode body = parse(capabilities(SCREEN_ONLY, PROJECT));

            // HTML and MD have renderers in the image; PDF does not, and its absence here is the
            // whole point of the operation — the generate enum offers it and every deployment
            // refuses it, so a picker built from the enum is wrong on every install.
            assertThat(formats(body)).containsExactly("HTML", "MD");
            // JSON is renderable on every deployment (`JsonReportRenderer` writes the model for
            // every job) and is not a format anybody asks for. A list derived from the renderer
            // registry alone would offer it.
            assertThat(formats(body)).doesNotContain("JSON");
        }

        @Test
        @DisplayName("what it lists is what generate accepts, both ways")
        void theListAgreesWithTheRefusal() throws Exception {
            // The pair that makes the answer trustworthy rather than merely present: a capability
            // list nothing compares to the refusal is a second opinion about the same fact, and it
            // is the one that goes stale the day a renderer is added.
            for (String format : formats(parse(capabilities(LEAD, PROJECT)))) {
                assertThat(generateWith(LEAD, PROJECT, format).getResponse().getStatus())
                        .as("%s is offered and must be accepted", format)
                        .isEqualTo(202);
            }
            assertThat(generateWith(LEAD, PROJECT, "PDF").getResponse().getStatus())
                    .as("PDF is not offered and must still be refused by name")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("report:view is enough, and no key at all is 403")
        void gatedOnTheReadKey() throws Exception {
            assertThat(capabilities(SCREEN_ONLY, PROJECT).getResponse().getStatus()).isEqualTo(200);
            assertThat(capabilities(REPORT_BLIND, PROJECT).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a member with no grant gets 404 rather than 403")
        void noGrantIsNotFound() throws Exception {
            assertThat(capabilities(NO_GRANT, PROJECT).getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("it names the report types too, so a caller need not guess one to be told")
        void whatThisServerCanCompute() throws Exception {
            /*
              `type` is an open string and the catalogue used to be reachable only through
              `ReportTypes.refusal` — which names it AFTER the caller has guessed wrong. A picker
              can live with that; an API caller with a `type` field and no list cannot, and
              `tcms_report` on the MCP surface was exactly that caller.
            */
            java.util.List<String> types = names(parse(capabilities(SCREEN_ONLY, PROJECT)), "types");

            assertThat(types).contains(RUN_SUMMARY);
            // Sorted, so a client rendering it is stable between reads.
            assertThat(types).isSorted();
        }

        @Test
        @DisplayName("what it lists as a type is what the refusal names")
        void theTypeListAgreesWithTheRefusal() throws Exception {
            /*
              `formats`' pairing, asked of the other field. It compares the catalogue to the
              REFUSAL rather than driving each type through `generate`, because whether one
              succeeds also depends on its own parameters — which the worker validates, not the
              request — and a check that conflated the two would fail for the wrong reason.
            */
            java.util.List<String> types = names(parse(capabilities(LEAD, PROJECT)), "types");

            String refusal = generate(LEAD, PROJECT, "{\"type\":\"no_such_report\",\"format\":\"MD\"}")
                    .getResponse().getContentAsString();

            for (String type : types) {
                assertThat(refusal)
                        .as("%s is offered by capabilities and must be in what the refusal lists", type)
                        .contains(type);
            }
        }

        private java.util.List<String> formats(JsonNode body) {
            return names(body, "formats");
        }

        private java.util.List<String> names(JsonNode body, String field) {
            java.util.List<String> values = new java.util.ArrayList<>();
            body.get(field).forEach(row -> values.add(row.asText()));
            return values;
        }
    }

    @Nested
    @DisplayName("Downloading — report:export")
    class Downloading {

        @Test
        @DisplayName("a lead gets the bytes, as an attachment named by the server")
        void theBytesAreServed() throws Exception {
            MvcResult result = download(LEAD, PROJECT, artifactOfACompletedJob(PROJECT));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getHeader("Content-Disposition"))
                    .startsWith("attachment; ")
                    .contains(RUN_SUMMARY)
                    .endsWith(".md\"");
            // The renderer's media type, not the generic one — a client that saves this by
            // Content-Type must not end up with a .bin.
            assertThat(result.getResponse().getContentType()).startsWith("text/markdown");
            assertThat(result.getResponse().getContentAsString())
                    .as("a COMPLETED job whose download is empty is the failure the bytes-before-row "
                            + "ordering exists to prevent")
                    .contains("Run summary");
        }

        @Test
        @DisplayName("HTML is served as HTML, named .html — the media type is the renderer's")
        void anHtmlArtifactIsServedAsHtml() throws Exception {
            // The one assertion that proves R4's renderer is reachable through the whole product
            // rather than only from a unit test: nothing in the controller, the service or the
            // validation was edited to admit HTML — a bean was added and the two registries
            // answered differently. A media type or an extension hard-coded anywhere in that path
            // would surface here as a .md called text/markdown holding a web page.
            MvcResult result = download(LEAD, PROJECT, artifactOfACompletedJob(PROJECT, "HTML"));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentType()).startsWith("text/html");
            assertThat(result.getResponse().getHeader("Content-Disposition")).endsWith(".html\"");
            assertThat(result.getResponse().getContentAsString())
                    .startsWith("<!DOCTYPE html>")
                    .contains("<h1>Run summary");
        }

        @Test
        @DisplayName("report:view is not enough — the screen-only role gets 403 with no body")
        void viewDoesNotDownload() throws Exception {
            UUID artifactId = artifactOfACompletedJob(PROJECT);

            MvcResult result = download(SCREEN_ONLY, PROJECT, artifactId);

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            // Deliberately bodiless, unlike the other two refusals: the response is typed to a
            // binary Resource, and a JSON problem document under a status the client can already
            // read tells it nothing it could act on (ReportController).
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }

        @Test
        @DisplayName("a role holding neither key is refused too")
        void neitherKeyDownloads() throws Exception {
            assertThat(download(REPORT_BLIND, PROJECT, artifactOfACompletedJob(PROJECT))
                    .getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("an artifact id that names nothing is 404")
        void anUnknownArtifactIsNotFound() throws Exception {
            assertThat(download(LEAD, PROJECT, UUID.randomUUID()).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a sibling project's artifact is 404 through this project's path")
        void anArtifactIsScopedToTheProjectInThePath() throws Exception {
            // report_artifact carries no project_id (V1), so the only thing scoping it is the job
            // that produced it. Resolved by id alone, this is a download served through a gate that
            // passed for a different project.
            UUID artifactId = artifactOfACompletedJob(SIBLING);

            assertThat(download(LEAD, PROJECT, artifactId).getResponse().getStatus()).isEqualTo(404);
            assertThat(download(LEAD, SIBLING, artifactId).getResponse().getStatus())
                    .as("the same artifact downloads from its own project")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a member with no grant gets 404 rather than 403")
        void noGrantIsNotFound() throws Exception {
            assertThat(download(NO_GRANT, PROJECT, artifactOfACompletedJob(PROJECT))
                    .getResponse().getStatus()).isEqualTo(404);
        }
    }

    // ---------------------------------------------------------------- driving

    // =====================================================================
    // GET / POST / DELETE /reports — saved definitions, report:view (§92)
    // =====================================================================

    @Nested
    @DisplayName("Saved definitions — report:view, and the row belongs to one person")
    class Definitions {

        @Test
        @DisplayName("saving answers 201 with the row, and the owner is the caller")
        void savedAndEchoed() throws Exception {
            MvcResult result = save(LEAD, PROJECT, definition("Release 5.0 regression", RUN));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);

            JsonNode saved = parse(result);
            assertThat(List.of("id", "type", "name", "parameters", "owner"))
                    .allSatisfy(field -> assertThat(saved.has(field))
                            .as("ReportDefinition.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(saved.get("type").asText()).isEqualTo(RUN_SUMMARY);
            assertThat(saved.get("name").asText()).isEqualTo("Release 5.0 regression");
            // An object, not a re-encoded string: a client that has to parse the field it was
            // handed is holding the shape §90 refused on the way in (openapi says `type: object`).
            assertThat(saved.get("parameters").isObject()).isTrue();
            assertThat(saved.get("parameters").get("runId").asText()).isEqualTo(RUN.toString());
            assertThat(saved.get("owner").get("id").asText()).isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("the list is the CALLER's, not the project's — asserted as two exact sets")
        void ownerScopedBothWays() throws Exception {
            save(LEAD, PROJECT, definition("Lead's cycle report", RUN));
            save(SCREEN_ONLY, PROJECT, definition("Screen-only's cycle report", RUN));

            // A pair, and it is the whole point of the fixture: "bounded by the caller" and
            // "everything in the project" answer identically as long as only one person has ever
            // saved anything, so one list is not evidence. Exact sets rather than `contains`,
            // because the defect this guards is an EXTRA row, which every contains() passes.
            assertThat(names(LEAD, PROJECT)).containsExactly("Lead's cycle report");
            assertThat(names(SCREEN_ONLY, PROJECT)).containsExactly("Screen-only's cycle report");
        }

        @Test
        @DisplayName("report:view is enough to SAVE — the persona §87 built could not otherwise")
        void viewSaves() throws Exception {
            // The other direction from Generating.viewDoesNotGenerate, and the reason the writes
            // here cost report:view: SCREEN_ONLY may read reports on screen and not carry them off,
            // and a role that has to retype its selection every release is not that role.
            assertThat(save(SCREEN_ONLY, PROJECT, definition("Mine", RUN))
                    .getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("a role holding neither key is refused all three operations")
        void neitherKeyIsRefused() throws Exception {
            UUID id = savedIdOf(LEAD, PROJECT, "Lead's");

            assertThat(list(REPORT_BLIND, PROJECT).getResponse().getStatus()).isEqualTo(403);
            assertThat(save(REPORT_BLIND, PROJECT, definition("Theirs", RUN))
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(delete(REPORT_BLIND, PROJECT, id).getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a member with no grant gets 404 rather than 403")
        void noGrantIsNotFound() throws Exception {
            assertThat(list(NO_GRANT, PROJECT).getResponse().getStatus()).isEqualTo(404);
            assertThat(save(NO_GRANT, PROJECT, definition("Theirs", RUN))
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("another client's project is 404 for a lead who holds every key here")
        void anotherCompanyIsNotFound() throws Exception {
            assertThat(list(LEAD, UMBRA_PROJECT).getResponse().getStatus()).isEqualTo(404);
            assertThat(save(LEAD, UMBRA_PROJECT, definition("Theirs", RUN))
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("an unknown type and a blank name are both field errors, in one answer")
        void bothRefusalsAtOnce() throws Exception {
            MvcResult result = save(LEAD, PROJECT,
                    "{\"type\":\"trend\",\"name\":\"   \",\"parameters\":{}}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);

            JsonNode errors = parse(result).get("errors");
            // Collected rather than short-circuited: a form that reveals its next objection only
            // after the first is fixed makes one mistake into two round trips.
            assertThat(errors.has("type")).isTrue();
            assertThat(errors.has("name")).isTrue();
            assertThat(errors.get("type").get(0).asText())
                    .as("the refusal must name what this server CAN produce")
                    .contains(RUN_SUMMARY);
        }

        @Test
        @DisplayName("parameters that are not a JSON object are refused on their own field")
        void parametersMustBeAnObject() throws Exception {
            MvcResult result = save(LEAD, PROJECT,
                    "{\"type\":\"" + RUN_SUMMARY + "\",\"name\":\"Odd\",\"parameters\":\"runId\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(parse(result).get("errors").has("parameters")).isTrue();
        }

        @Test
        @DisplayName("a definition with no parameters is saved with an empty object")
        void parametersDefaultToEmpty() throws Exception {
            MvcResult result = save(LEAD, PROJECT,
                    "{\"type\":\"" + RUN_SUMMARY + "\",\"name\":\"Everything\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(parse(result).get("parameters").isObject()).isTrue();
            assertThat(parse(result).get("parameters").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("deleting one answers 204 and it leaves the list")
        void deleted() throws Exception {
            UUID id = savedIdOf(LEAD, PROJECT, "Temporary");

            assertThat(delete(LEAD, PROJECT, id).getResponse().getStatus()).isEqualTo(204);
            assertThat(names(LEAD, PROJECT)).isEmpty();
        }

        @Test
        @DisplayName("somebody else's definition is 404, and survives the attempt")
        void anotherPersonsIsNotFound() throws Exception {
            UUID id = savedIdOf(LEAD, PROJECT, "Lead's");

            // Not 403: SCREEN_ONLY cannot list this row, so an answer that distinguishes "not
            // yours" from "no such thing" makes the route an oracle over other people's saved
            // reports (§6t at row level).
            assertThat(delete(SCREEN_ONLY, PROJECT, id).getResponse().getStatus()).isEqualTo(404);
            assertThat(names(LEAD, PROJECT)).containsExactly("Lead's");
        }

        @Test
        @DisplayName("a sibling project's definition is 404 through this project's path")
        void wrongProjectPathIsNotFound() throws Exception {
            UUID id = savedIdOf(LEAD, PROJECT, "Lead's");

            // LEAD holds both projects, so the gate passes — against the wrong project. The id
            // alone would resolve the row and delete it through a path that never named it.
            assertThat(delete(LEAD, SIBLING, id).getResponse().getStatus()).isEqualTo(404);
            assertThat(names(LEAD, PROJECT)).containsExactly("Lead's");
        }

        @Test
        @DisplayName("a definition id that names nothing is 404")
        void unknownIdIsNotFound() throws Exception {
            assertThat(delete(LEAD, PROJECT, UUID.randomUUID()).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // POST /reports/generate with definitionId — regenerating a saved report
    // =====================================================================

    @Nested
    @DisplayName("Regenerating a saved report — the definition supplies the type and the base")
    class Regenerating {

        @Test
        @DisplayName("definitionId alone is enough: no type, and the job carries both")
        void definitionSuppliesTheType() throws Exception {
            UUID definitionId = savedIdOf(LEAD, PROJECT, "Release 5.0 regression");

            MvcResult result = generate(LEAD, PROJECT,
                    "{\"definitionId\":\"" + definitionId + "\",\"format\":\"MD\"}");

            assertThat(result.getResponse().getStatus())
                    .as("one click, one field: %s", result.getResponse().getContentAsString())
                    .isEqualTo(202);
            assertThat(parse(result).get("type").asText()).isEqualTo(RUN_SUMMARY);

            UUID jobId = UUID.fromString(parse(result).get("jobId").asText());
            assertThat(definitionOfJob(jobId))
                    .as("the job must remember which saved report it came from")
                    .isEqualTo(definitionId);
            // The saved selection, not an empty one — the whole point of saving it.
            assertThat(parametersOfJob(jobId)).contains(RUN.toString());
        }

        @Test
        @DisplayName("the request overrides the definition key by key, and keeps the rest")
        void mergedShallowly() throws Exception {
            MvcResult saved = save(LEAD, PROJECT, "{\"type\":\"" + RUN_SUMMARY
                    + "\",\"name\":\"Cycle\",\"parameters\":{\"runId\":\"" + RUN
                    + "\",\"note\":\"as saved\"}}");
            UUID definitionId = UUID.fromString(parse(saved).get("id").asText());

            MvcResult result = generate(LEAD, PROJECT, "{\"definitionId\":\"" + definitionId
                    + "\",\"format\":\"MD\",\"parameters\":{\"note\":\"this run\"}}");

            assertThat(result.getResponse().getStatus()).isEqualTo(202);

            JsonNode stored = json.readTree(
                    parametersOfJob(UUID.fromString(parse(result).get("jobId").asText())));
            // Both halves, and the second is the one an override that REPLACES the object would
            // lose — a report computed over everything while the client believes it named a run.
            assertThat(stored.get("note").asText()).isEqualTo("this run");
            assertThat(stored.get("runId").asText()).isEqualTo(RUN.toString());
        }

        @Test
        @DisplayName("a type that contradicts the saved one is refused rather than resolved")
        void contradictoryTypeIsRefused() throws Exception {
            UUID definitionId = savedIdOf(LEAD, PROJECT, "Cycle");

            MvcResult result = generate(LEAD, PROJECT, "{\"definitionId\":\"" + definitionId
                    + "\",\"type\":\"trend\",\"format\":\"MD\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(parse(result).get("errors").has("type")).isTrue();
            assertThat(jobCount()).isZero();
        }

        @Test
        @DisplayName("somebody else's definition is a field error, and writes no job")
        void anotherPersonsDefinition() throws Exception {
            UUID definitionId = savedIdOf(SCREEN_ONLY, PROJECT, "Not the lead's");

            MvcResult result = generate(LEAD, PROJECT,
                    "{\"definitionId\":\"" + definitionId + "\",\"format\":\"MD\"}");

            // 400 and not 404: the caller can see the project and named something in it. Gone and
            // somebody-else's are deliberately the same answer, as on the delete.
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(parse(result).get("errors").has("definitionId")).isTrue();
            assertThat(jobCount()).isZero();
        }

        @Test
        @DisplayName("a definition of a sibling project is refused through this project's path")
        void wrongProjectIsRefused() throws Exception {
            UUID definitionId = savedIdOf(LEAD, SIBLING, "The other project's");

            MvcResult result = generate(LEAD, PROJECT,
                    "{\"definitionId\":\"" + definitionId + "\",\"format\":\"MD\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(parse(result).get("errors").has("definitionId")).isTrue();
        }

        @Test
        @DisplayName("report:view still does not generate, saved report or not")
        void savingIsNotGenerating() throws Exception {
            // SCREEN_ONLY may SAVE a definition (Definitions.viewSaves) and may not run it. The
            // two keys stay distinguishable on the path that reads a row the caller owns.
            UUID definitionId = savedIdOf(SCREEN_ONLY, PROJECT, "Mine to save, not to run");

            assertThat(generate(SCREEN_ONLY, PROJECT,
                    "{\"definitionId\":\"" + definitionId + "\",\"format\":\"MD\"}")
                    .getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("neither type nor definitionId is still a field error on type")
        void neitherIsRefused() throws Exception {
            MvcResult result = generate(LEAD, PROJECT, "{\"format\":\"MD\"}");

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(parse(result).get("errors").get("type").get(0).asText())
                    .contains(RUN_SUMMARY);
        }

        @Test
        @DisplayName("deleting the definition leaves the artifact it produced — V28's whole reason")
        void artifactSurvivesTheDefinition() throws Exception {
            UUID definitionId = savedIdOf(LEAD, PROJECT, "Release 5.0 regression");

            MvcResult accepted = generate(LEAD, PROJECT,
                    "{\"definitionId\":\"" + definitionId + "\",\"format\":\"MD\"}");
            assertThat(accepted.getResponse().getStatus()).isEqualTo(202);
            runWorker();

            UUID jobId = UUID.fromString(parse(accepted).get("jobId").asText());
            assertThat(parse(poll(LEAD, PROJECT, jobId)).get("status").asText())
                    .isEqualTo("COMPLETED");
            assertThat(artifactsOf(definitionId))
                    .as("the runner copies the job's definition onto every artifact it writes")
                    .isPositive();

            // Without V28 this is a foreign-key violation surfacing as a 500 — and only ever on a
            // saved report somebody actually used, which is the version of the defect that reaches
            // production.
            assertThat(delete(LEAD, PROJECT, definitionId).getResponse().getStatus())
                    .isEqualTo(204);

            assertThat(artifactCount())
                    .as("deleting a saved report must not delete the files it produced")
                    .isPositive();
            assertThat(artifactsOf(definitionId))
                    .as("they become ad-hoc artifacts, which is what they now are")
                    .isZero();
            // And the download still resolves: the artifact's project is proved through the job,
            // whose definition_id is now null too.
            String url = parse(poll(LEAD, PROJECT, jobId)).get("downloadUrl").asText();
            assertThat(status(LEAD, url)).isEqualTo(200);
        }
    }

    private UUID definitionOfJob(UUID jobId) throws SQLException {
        return uuidColumn("SELECT definition_id FROM report_job WHERE id = CAST(? AS uuid)", jobId);
    }

    private String parametersOfJob(UUID jobId) throws SQLException {
        try (Connection acme = appConnection(ACME);
                PreparedStatement statement = acme.prepareStatement(
                        "SELECT parameters::text FROM report_job WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, jobId.toString());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the job row must exist").isTrue();
                return rows.getString(1);
            }
        }
    }

    private UUID uuidColumn(String sql, UUID id) throws SQLException {
        try (Connection acme = appConnection(ACME);
                PreparedStatement statement = acme.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("the row must exist").isTrue();
                String value = rows.getString(1);
                return value == null ? null : UUID.fromString(value);
            }
        }
    }

    private long artifactsOf(UUID definitionId) throws SQLException {
        try (Connection acme = appConnection(ACME);
                PreparedStatement statement = acme.prepareStatement(
                        "SELECT count(*) FROM report_artifact "
                                + "WHERE definition_id = CAST(? AS uuid)")) {
            statement.setString(1, definitionId.toString());
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private long artifactCount() throws SQLException {
        try (Connection acme = appConnection(ACME);
                PreparedStatement statement =
                        acme.prepareStatement("SELECT count(*) FROM report_artifact");
                var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private MvcResult list(UUID userId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/reports")
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult save(UUID userId, UUID projectId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/reports")
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult delete(UUID userId, UUID projectId, UUID definitionId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/reports/" + definitionId)
                        .header("Authorization", "Bearer " + tokenFor(userId)))
                .andReturn();
    }

    private static String definition(String name, UUID runId) {
        return "{\"type\":\"" + RUN_SUMMARY + "\",\"name\":\"" + name
                + "\",\"parameters\":{\"runId\":\"" + runId + "\"}}";
    }

    /** The names in a caller's list, in the order the server returned them. */
    private List<String> names(UUID userId, UUID projectId) throws Exception {
        MvcResult result = list(userId, projectId);
        assertThat(result.getResponse().getStatus())
                .as("the fixture's own list must be served: %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(200);

        List<String> names = new java.util.ArrayList<>();
        parse(result).forEach(row -> names.add(row.get("name").asText()));
        return names;
    }

    /** Saves one and returns its id, asserting the fixture's own request was accepted. */
    private UUID savedIdOf(UUID userId, UUID projectId, String name) throws Exception {
        MvcResult result = save(userId, projectId, definition(name, RUN));
        assertThat(result.getResponse().getStatus())
                .as("the fixture's own save must be accepted: %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(parse(result).get("id").asText());
    }

    private MvcResult generate(UUID userId, UUID projectId, String body) throws Exception {
        return generate(userId, projectId, body, null);
    }

    /** With an {@code Idempotency-Key} when one is given — E2, {@code V66}. */
    private MvcResult generate(UUID userId, UUID projectId, String body, String idempotencyKey)
            throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/reports/generate")
                .header("Authorization", "Bearer " + tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult capabilities(UUID userId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/reports/capabilities")
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    /** A well-formed generate whose only variable is the format — the capability pair's driver. */
    private MvcResult generateWith(UUID userId, UUID projectId, String format) throws Exception {
        return generate(userId, projectId, "{\"type\":\"" + RUN_SUMMARY + "\",\"format\":\""
                + format + "\",\"parameters\":{\"runId\":\"" + RUN + "\"}}");
    }

    private MvcResult poll(UUID userId, UUID projectId, UUID jobId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/reports/jobs/" + jobId)
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult model(UUID userId, UUID projectId, UUID jobId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/reports/jobs/" + jobId + "/model")
                        .header("Authorization", "Bearer " + tokenFor(userId)))
                .andReturn();
    }

    private MvcResult jobs(UUID userId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/reports/jobs")
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult download(UUID userId, UUID projectId, UUID artifactId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/reports/artifacts/" + artifactId
                                + "/content")
                        .header("Authorization", "Bearer " + tokenFor(userId)))
                .andReturn();
    }

    private int status(UUID userId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path)
                        .header("Authorization", "Bearer " + tokenFor(userId)))
                .andReturn().getResponse().getStatus();
    }

    private static String body(String type, String format) {
        return "{\"type\":\"" + type + "\",\"format\":\"" + format
                + "\",\"parameters\":{\"runId\":\"" + RUN + "\"}}";
    }

    /** An accepted request, returning the job id the client would poll. */
    private UUID requestAs(UUID userId, UUID projectId) throws Exception {
        return requestAs(userId, projectId, "MD");
    }

    private UUID requestAs(UUID userId, UUID projectId, String format) throws Exception {
        String requestBody = "{\"type\":\"" + RUN_SUMMARY + "\",\"format\":\"" + format + "\","
                + "\"parameters\":{\"runId\":\"" + (projectId.equals(SIBLING) ? SIBLING_RUN : RUN)
                + "\"}}";

        MvcResult result = generate(userId, projectId, requestBody);
        assertThat(result.getResponse().getStatus())
                .as("the fixture's own request must be accepted: %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(202);
        return UUID.fromString(parse(result).get("jobId").asText());
    }

    /** Requests a report, runs the worker, and returns the id in the job's {@code downloadUrl}. */
    private UUID artifactOfACompletedJob(UUID projectId) throws Exception {
        return artifactOfACompletedJob(projectId, "MD");
    }

    private UUID artifactOfACompletedJob(UUID projectId, String format) throws Exception {
        UUID jobId = requestAs(LEAD, projectId, format);
        runWorker();

        JsonNode job = parse(poll(LEAD, projectId, jobId));
        assertThat(job.get("status").asText())
                .as("the download tests need bytes; error was: %s", job.get("error"))
                .isEqualTo("COMPLETED");

        String url = job.get("downloadUrl").asText();
        return UUID.fromString(url.substring(
                url.indexOf("/artifacts/") + "/artifacts/".length(), url.lastIndexOf("/content")));
    }

    /**
     * One tick of the worker with Acme open — the company loop's body.
     *
     * <p>The scheduler itself stays off ({@code tcms.reporting.enabled=false} in
     * {@link IntegrationTestBase}); a timer firing mid-class would claim jobs these tests have not
     * finished asserting about.
     */
    private void runWorker() {
        try (var ignored = CompanyContext.open(ACME)) {
            runner.runPendingJobs();
        }
    }

    private JsonNode parse(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body).as("expected a JSON body").isNotEmpty();
        return json.readTree(body);
    }

    private long jobCount() throws SQLException {
        try (Connection acme = appConnection(ACME);
                PreparedStatement statement =
                        acme.prepareStatement("SELECT count(*) FROM report_job");
                var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String tokenFor(UUID userId) {
        return tx.execute(status -> tokens.issue(userId, ACME, 1, false).token());
    }

    // ---------------------------------------------------------------- fixture

    private void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
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

    private void grantOnProject(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {

        UUID id = UUID.nameUUIDFromBytes(("report-endpoint:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    /**
     * A PROJECT-scope role holding exactly these keys, written with raw JDBC.
     *
     * <p>There is no entity route to this on purpose — {@code Role} has no factory method, because
     * one would be an invitation to write the seventh role from application code (its header). A
     * fixture inserting a row is not that; nothing in {@code src/main} gains the ability.
     */
    private void customRole(UUID id, String name, List<String> permissions) throws SQLException {
        try (Connection connection = appConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO role (id, scope, name, description, built_in)
                    VALUES (CAST(? AS uuid), 'PROJECT', ?, ?, false)
                        ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, id.toString());
                statement.setString(2, name);
                statement.setString(3, "Test fixture — see ReportEndpointTest.");
                statement.executeUpdate();
            }
            for (String permission : permissions) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO role_permission (role_id, permission_key)
                        VALUES (CAST(? AS uuid), ?)
                            ON CONFLICT DO NOTHING
                        """)) {
                    statement.setString(1, id.toString());
                    statement.setString(2, permission);
                    statement.executeUpdate();
                }
            }
        }
    }

    private static void delete(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SCREEN_ONLY_ROLE.toString());
            statement.setString(2, REPORT_BLIND_ROLE.toString());
            statement.executeUpdate();
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void run(Connection connection, UUID id, UUID companyId, UUID projectId, String key,
            long keySeq, String name) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_run
                    (id, company_id, project_id, key, key_seq, name, status, source,
                     configuration_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?,
                        'IN_PROGRESS', 'MANUAL',
                        (SELECT c.id FROM configuration c
                          WHERE c.project_id = CAST(? AS uuid) LIMIT 1),
                        now(), now())
                    ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, projectId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * Jobs, artifacts and saved definitions, before every method.
     *
     * <p>A leftover {@code PENDING} row is claimed by the next method's tick, which is how a test
     * asserting on "the completed job" ends up looking at a different one. A leftover definition is
     * worse in a quieter way: every assertion in {@link Definitions} is an <em>exact</em> list, so
     * one surviving row would redden whichever method happens to run second.
     *
     * <p>Order matters. {@code report_job.artifact_id} references the artifacts, and both jobs and
     * artifacts reference the definitions — the latter two are {@code ON DELETE SET NULL} ({@code
     * V27}, {@code V28}), so this order is what keeps the deletes from depending on that.
     */
    private void clearJobs() throws SQLException {
        try (Connection acme = appConnection(ACME)) {
            for (String table : List.of("report_job", "report_artifact", "report_definition")) {
                try (PreparedStatement statement =
                        acme.prepareStatement("DELETE FROM " + table)) {
                    statement.executeUpdate();
                }
            }
        }
    }
}
