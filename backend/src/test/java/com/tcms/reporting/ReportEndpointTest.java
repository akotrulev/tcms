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
 * reaches a permission check at all. Every gate assertion available from the six built-ins is
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
 * <p>{@code SeedDataTest.sixBuiltInRoles} asserts that {@code SELECT name FROM role WHERE NOT
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

    /** Test Lead — holds both report keys, as four of the six built-ins do. */
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
            assertThat(job.get("modelUrl").asText())
                    .startsWith("/api/v1/projects/" + PROJECT + "/reports/artifacts/");
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

    private MvcResult generate(UUID userId, UUID projectId, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/reports/generate")
                        .header("Authorization", "Bearer " + tokenFor(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult poll(UUID userId, UUID projectId, UUID jobId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/reports/jobs/" + jobId)
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
     * Jobs and artifacts, before every method.
     *
     * <p>A leftover {@code PENDING} row is claimed by the next method's tick, which is how a test
     * asserting on "the completed job" ends up looking at a different one.
     */
    private void clearJobs() throws SQLException {
        try (Connection acme = appConnection(ACME)) {
            for (String table : List.of("report_job", "report_artifact")) {
                try (PreparedStatement statement =
                        acme.prepareStatement("DELETE FROM " + table)) {
                    statement.executeUpdate();
                }
            }
        }
    }
}
