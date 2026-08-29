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
 * {@code POST .../cases/parse-gherkin} and {@code GET .../cases/{caseId}/feature} — §255 T3.
 *
 * <h2>What this class exists to catch, and only the last one is loud</h2>
 *
 * <ol>
 *   <li><strong>A parser failure answered as a 500.</strong> The text comes from a person and being
 *       unparseable is the ordinary case; a stack trace where a line number belongs is a paste
 *       dialog that reads as broken. Each of the four refusals is asserted on its status, its
 *       {@code type} and its {@code errors.text}, because a stub answering 400 to everything passes
 *       a test that only checks the status.</li>
 *   <li><strong>A render that drops what Gherkin has no syntax for.</strong> {@code expectedResult},
 *       {@code testData} and a case's {@code POST} conditions have no construct, so they leave as
 *       comments — and the assertions name those comment lines rather than merely checking the
 *       steps are present.</li>
 *   <li><strong>A {@code STRUCTURED} case refused.</strong> Numbered steps are a scenario whose
 *       steps have no keyword and {@code *} is Gherkin's spelling for that, so "view as .feature"
 *       must not be an action that errors on half the repository.</li>
 *   <li><strong>A zero-row legacy case rendering an empty scenario.</strong> That population is
 *       what {@code migrate-gherkin-source} has not reached yet, and the product is still showing
 *       those users a procedure. An empty {@code Scenario:} would tell them it is gone.</li>
 *   <li><strong>The paste endpoint gated on more than project visibility.</strong> It writes
 *       nothing, so a write permission here would refuse an author who may read the project — and
 *       gating it on nothing would let a member with no grant use it, which is only a leak in the
 *       sense that it costs the server CPU, and is still a permission this endpoint never had.</li>
 * </ol>
 *
 * <p>Company, project, user and case ids are unique to this class ({@code e1…}). Sharing them with
 * another class is how handoff §19's defect happened five times: green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Gherkin paste and render — text in, rows out, and back again")
class CaseGherkinEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("e3000000-0000-4000-8000-00000000000a");

    /** Granted to ENGINEER. */
    private static final UUID ACME_BANKING = UUID.fromString("e3100000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to ENGINEER — the sibling nothing may be read through. */
    private static final UUID ACME_MOBILE = UUID.fromString("e3100000-0000-4000-8000-000000000002");

    private static final UUID ENGINEER = UUID.fromString("e3200000-0000-4000-8000-000000000001");
    /** An ACTIVE member of Acme holding no project grant at all. */
    private static final UUID NO_GRANT = UUID.fromString("e3200000-0000-4000-8000-000000000002");

    private static final UUID SUITE = UUID.fromString("e3300000-0000-4000-8000-000000000001");
    private static final UUID MOBILE_SUITE = UUID.fromString("e3300000-0000-4000-8000-000000000002");

    /** The whole shape: keywords, all three data spellings, a PRE and a POST, two tags. */
    private static final UUID GHERKIN_CASE = UUID.fromString("e3400000-0000-4000-8000-000000000001");
    /** Numbered steps, no keyword on any of them — the `*` render. */
    private static final UUID STRUCTURED_CASE =
            UUID.fromString("e3400000-0000-4000-8000-000000000002");
    /** Zero rows and a stored scenario — the population the backfill has not reached. */
    private static final UUID LEGACY_CASE = UUID.fromString("e3400000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_CASE = UUID.fromString("e3400000-0000-4000-8000-000000000004");

    private static final UUID PRECONDITION = UUID.fromString("e3500000-0000-4000-8000-000000000001");
    private static final UUID POSTCONDITION =
            UUID.fromString("e3500000-0000-4000-8000-000000000002");

    private static final String LEGACY_BLOB =
            "Feature: The ledger\n  Scenario: Not yet migrated\n    Given nothing has run yet\n";

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
            company(ACME, "gher-acme", "Acme", now);
            activeUser(ENGINEER, "engineer@gher-acme.test", now);
            activeUser(NO_GRANT, "nogrant@gher-acme.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "E3OLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "E3MOB", "Mobile App", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            grantOnProject("eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
        }));

        seedCases();
    }

    // =====================================================================
    // GET .../{caseId}/feature
    // =====================================================================

    @Nested
    @DisplayName("rendering a case as .feature")
    class Rendering {

        @Test
        @DisplayName("every construct the case carries has a line in the text")
        void theWholeCaseRenders() throws Exception {
            String feature = featureOf(ENGINEER, ACME_BANKING, GHERKIN_CASE);

            assertThat(feature.lines().toList())
                    .as("the rendered case was:%n%s", feature)
                    .containsSubsequence(
                            "@refunds @smoke",
                            "Feature: Refunds",
                            "  Background:",
                            "    Given the operator is signed in",
                            "    And the ledger is open",
                            "  Scenario: A refund lands on the ledger",
                            "    the ledger is the record of what happened",
                            "    Given a captured payment of 100 EUR",
                            "    # data: order-42",
                            "    When I refund it",
                            "      \"\"\"",
                            "      {\"amount\": 100}",
                            "      \"\"\"",
                            "    Then the ledger shows a credit",
                            "      | line | amount |",
                            "      | credit | 100 |",
                            "    # expected: the balance falls by 100",
                            "    # postconditions:",
                            "    #   Reset the test payment data");
        }

        @Test
        @DisplayName("what a Cucumber runner needs to save it — the media type and the filename")
        void theResponseIsAFile() throws Exception {
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .get(featurePath(ACME_BANKING, GHERKIN_CASE))
                            .header("Authorization", "Bearer " + tokenFor(ENGINEER, ACME)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentType())
                    .as("a JSON string of escaped newlines is one nobody can paste into a suite")
                    .startsWith(MediaType.TEXT_PLAIN_VALUE);
            assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                    .as("named after the case, not after the path segment")
                    .isEqualTo("inline; filename=\"E3OLB-1.feature\"");
        }

        @Test
        @DisplayName("a STRUCTURED case renders with * — there is no 409")
        void structuredCasesRenderToo() throws Exception {
            String feature = featureOf(ENGINEER, ACME_BANKING, STRUCTURED_CASE);

            assertThat(feature.lines().toList())
                    .as("`*` is Gherkin's format-neutral keyword; refusing would make the action "
                            + "present on some cases and an error on others for a reason the "
                            + "author never chose. Rendered:%n%s", feature)
                    .containsSubsequence(
                            "    * Open the ledger",
                            "    * Read the closing balance");
            assertThat(feature).doesNotContain("Given ", "When ", "Then ");
        }

        @Test
        @DisplayName("a zero-row legacy case renders its stored scenario verbatim")
        void aLegacyCaseRendersItsBlob() throws Exception {
            // The population migrate-gherkin-source has not reached. An empty `Scenario:` here
            // would tell a reader the case has no procedure while the detail screen is still
            // showing them one.
            assertThat(featureOf(ENGINEER, ACME_BANKING, LEGACY_CASE)).isEqualTo(LEGACY_BLOB);
        }

        @Test
        @DisplayName("a case in a project the caller was not granted is a bodiless 404")
        void aSiblingProjectsCaseIsNotReadable() throws Exception {
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .get(featurePath(ACME_MOBILE, MOBILE_CASE))
                            .header("Authorization", "Bearer " + tokenFor(ENGINEER, ACME)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString())
                    .as("§6t — a body explaining a 404 is an oracle even when it is generic")
                    .isEmpty();
        }
    }

    // =====================================================================
    // POST .../cases/parse-gherkin
    // =====================================================================

    @Nested
    @DisplayName("parsing pasted text")
    class Parsing {

        @Test
        @DisplayName("the 200 carries the title, objective, tags, background and rows")
        void theWholeResultIsServed() throws Exception {
            JsonNode body = json.readTree(parse(ENGINEER, ACME_BANKING, """
                    @smoke
                    Feature: Refunds

                      Background:
                        Given the operator is signed in
                        And the ledger is open

                      @ledger
                      Scenario: A refund lands on the ledger
                        the ledger is the record

                        Given a captured payment
                        # data: order-42
                        When I refund it
                        Then the balance falls
                    """));

            assertThat(body.get("title").asText()).isEqualTo("A refund lands on the ledger");
            assertThat(body.get("objective").asText()).isEqualTo("the ledger is the record");
            assertThat(fieldValues(body.get("tags"))).containsExactly("smoke", "ledger");

            assertThat(body.get("background")).hasSize(2);
            assertThat(body.get("background").get(0).get("title").asText())
                    .as("AM1 — one entry per Background STEP, not one blob for the block")
                    .isEqualTo("Given the operator is signed in");
            assertThat(fieldValues(body.get("background").get(1).get("body")))
                    .containsExactly("And the ledger is open");

            assertThat(body.get("steps")).hasSize(3);
            assertThat(body.get("steps").get(0).get("keyword").asText()).isEqualTo("GIVEN");
            assertThat(body.get("steps").get(0).get("action").asText())
                    .isEqualTo("a captured payment");
            assertThat(body.get("steps").get(0).get("testData").asText())
                    .as("`# data:` is how a field Gherkin cannot spell survives the text")
                    .isEqualTo("order-42");
            assertThat(body.get("steps").get(0).get("id").isNull())
                    .as("nothing was created, so nothing has an id")
                    .isTrue();
        }

        @Test
        @DisplayName("a syntax error is a 400 naming the line and the column, never a 500")
        void aSyntaxErrorIsA400() throws Exception {
            JsonNode problem = json.readTree(parse(ENGINEER, ACME_BANKING, """
                    Feature: F
                      Scenario: S
                        Given a captured payment
                      this line is not anything
                    """));

            assertThat(problem.get("status").asInt()).isEqualTo(400);
            assertThat(problem.get("type").asText()).isEqualTo("gherkin-syntax");
            assertThat(problem.get("errors").get("text").get(0).asText())
                    .as("`text` is what the paste dialog's textarea is bound to, so the SPA lands "
                            + "the sentence beside the box")
                    .startsWith("Line 4, column 3: ");
        }

        @Test
        @DisplayName("a Scenario Outline is a 400 of its own type, naming its line")
        void anOutlineIsRefused() throws Exception {
            JsonNode problem = json.readTree(parse(ENGINEER, ACME_BANKING, """
                    Feature: F

                      Scenario Outline: a template
                        Given a balance of <amount>

                        Examples:
                          | amount |
                          | 10     |
                    """));

            assertThat(problem.get("type").asText()).isEqualTo("gherkin-outline");
            assertThat(problem.get("errors").get("text").get(0).asText())
                    .isEqualTo("Line 3: Scenario Outline is not supported yet — expand the "
                            + "examples into separate scenarios");
        }

        @Test
        @DisplayName("two scenarios and an empty paste are two different 400s")
        void theOtherTwoRefusalsAreDistinct() throws Exception {
            // Four types rather than one, because the remedies differ: paste one scenario, versus
            // write something. One sentence covering both fits neither.
            JsonNode many = json.readTree(parse(ENGINEER, ACME_BANKING, """
                    Feature: F
                      Scenario: A
                        Given a captured payment
                      Scenario: B
                        Given a second payment
                    """));

            assertThat(many.get("type").asText()).isEqualTo("gherkin-many-scenarios");
            assertThat(many.get("errors").get("text").get(0).asText())
                    .isEqualTo("This text holds 2 scenarios; paste one, or import the file");

            JsonNode empty =
                    json.readTree(parse(ENGINEER, ACME_BANKING, "# nothing\n"));

            assertThat(empty.get("type").asText()).isEqualTo("gherkin-empty");
            assertThat(empty.get("errors").get("text").get(0).asText()).isEqualTo("No steps found");
        }

        @Test
        @DisplayName("text over the cap is a 413 before anything is parsed")
        void anOversizedPasteIsA413() throws Exception {
            // Checked by the handler, not by the container: the servlet limit bounds FORM bodies
            // only, so without this a 300 KiB paste is read in full and parsed.
            String oversized = "Given a step\n".repeat(30_000);

            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post(parsePath(ACME_BANKING))
                            .header("Authorization", "Bearer " + tokenFor(ENGINEER, ACME))
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(oversized))
                    .andReturn();

            assertThat(oversized.length())
                    .as("the fixture must stay over the cap or this arm cannot fail")
                    .isGreaterThan(256 * 1024);
            assertThat(result.getResponse().getStatus()).isEqualTo(413);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("type").asText())
                    .isEqualTo("payload-too-large");
        }

        @Test
        @DisplayName("a JSON body is a 415 — this operation reads text")
        void aJsonBodyIsA415() throws Exception {
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post(parsePath(ACME_BANKING))
                            .header("Authorization", "Bearer " + tokenFor(ENGINEER, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"Given a step\"}"))
                    .andReturn();

            // The STATUS only. The body is written on the container's ERROR dispatch to /error
            // (ProblemErrorAttributes), which MockMvc does not perform — so asserting it here
            // would assert MockMvc's behaviour rather than the server's. The shape of that body is
            // ErrorBodyPolicyTest's, and this arm's subject is that the operation refuses JSON at
            // all: without `consumes`, Spring would bind the JSON document as the scenario text.
            assertThat(result.getResponse().getStatus()).isEqualTo(415);
        }

        @Test
        @DisplayName("a member with no grant on the project gets 404, not a parse")
        void theGateIsProjectVisibility() throws Exception {
            // It writes nothing, so it takes the READ gate and no write permission — and it is
            // still gated, because an endpoint gated on nothing is one any member of the company
            // can drive against any project id.
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post(parsePath(ACME_BANKING))
                            .header("Authorization", "Bearer " + tokenFor(NO_GRANT, ACME))
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("Given a captured payment\n"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the refusal names its line, structured")
    class RefusalLines {

        // 003 C4: under D1 the author has no row editor to fall back to, so the sentence has to
        // land ON the line it names — and a client regexing the number out of `errors.text` would
        // be parsing prose this server is free to reword. `Problem.line` is what makes that
        // structural, and these arms are what stop it being null for want of a throw site.

        @Test
        @DisplayName("a syntax error carries the parser's own line")
        void aSyntaxErrorCarriesItsLine() throws Exception {
            JsonNode problem = json.readTree(parse(ENGINEER, ACME_BANKING, """
                    Feature: F
                      Scenario: S
                        Given a captured payment
                      this line is not anything
                    """));

            assertThat(problem.get("line").asInt())
                    .as("the same 4 the sentence names — one Location read twice, never a number "
                            + "parsed back out of the prose")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("an Outline carries the Outline's line")
        void anOutlineCarriesItsLine() throws Exception {
            JsonNode problem = json.readTree(parse(ENGINEER, ACME_BANKING, """
                    Feature: F

                      Scenario Outline: a template
                        Given a balance of <amount>

                        Examples:
                          | amount |
                          | 10     |
                    """));

            assertThat(problem.get("line").asInt()).isEqualTo(3);
        }

        @Test
        @DisplayName("two scenarios point at the SECOND one — the line to cut from")
        void manyScenariosPointAtTheSurplus() throws Exception {
            // Not the first. The first scenario is the one the author is keeping, and marking it
            // would say "this is the problem" about the half of the paste that is fine.
            JsonNode problem = json.readTree(parse(ENGINEER, ACME_BANKING, """
                    Feature: F
                      Scenario: A
                        Given a captured payment
                      Scenario: B
                        Given a second payment
                    """));

            assertThat(problem.get("line").asInt()).isEqualTo(4);
        }

        @Test
        @DisplayName("an empty paste sends no line at all")
        void anEmptyPasteSendsNoLine() throws Exception {
            // Null, and omitted by @JsonInclude(NON_NULL). A paste with nothing that parses as a
            // step has no line to point at — the remedy is to write something, anywhere — and a
            // fabricated 1 would put a marker on a line the author has no reason to look at.
            JsonNode problem = json.readTree(parse(ENGINEER, ACME_BANKING, "# nothing\n"));

            assertThat(problem.get("type").asText()).isEqualTo("gherkin-empty");
            assertThat(problem.has("line")).isFalse();
        }

        @Test
        @DisplayName("the 413 sends no line either")
        void theSizeRefusalSendsNoLine() throws Exception {
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post(parsePath(ACME_BANKING))
                            .header("Authorization", "Bearer " + tokenFor(ENGINEER, ACME))
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("Given a step\n".repeat(30_000)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(413);
            assertThat(json.readTree(result.getResponse().getContentAsString()).has("line"))
                    .as("the refusal is about the whole body; no line in it is the problem")
                    .isFalse();
        }
    }

    // =====================================================================
    // POST .../cases/render-gherkin  — 003 Phase 2
    // =====================================================================

    @Nested
    @DisplayName("rendering a case the editor has not saved")
    class RenderingADraft {

        @Test
        @DisplayName("the Feature line is the suite's name, resolved server-side")
        void theFeatureNameComesFromTheSuite() throws Exception {
            // 003 C3's trap: the `Feature:` line renders the SUITE NAME, which the editor does not
            // hold and must not be asked for — a client that sent it could make the render disagree
            // with the export by editing one string.
            String text = render(ENGINEER, ACME_BANKING, """
                    {"title": "A refund lands", "suiteId": "%s", "stepFormat": "GHERKIN",
                     "priority": "MEDIUM", "automationStatus": "MANUAL",
                     "steps": [{"keyword": "GIVEN", "action": "a captured payment"}]}
                    """.formatted(SUITE));

            assertThat(text).startsWith("Feature: Refunds\n");
            assertThat(text).contains("  Scenario: A refund lands\n");
            assertThat(text).contains("    Given a captured payment\n");
        }

        @Test
        @DisplayName("an unfiled case still renders — the header is bare, not an error")
        void anUnfiledCaseRenders() throws Exception {
            String text = render(ENGINEER, ACME_BANKING, """
                    {"title": "Nowhere in particular", "stepFormat": "GHERKIN",
                     "priority": "MEDIUM", "automationStatus": "MANUAL",
                     "steps": [{"keyword": "GIVEN", "action": "a captured payment"}]}
                    """);

            assertThat(text).startsWith("Feature: \n");
        }

        @Test
        @DisplayName("preconditions render as Background, resolved from the library")
        void preconditionsBecomeBackground() throws Exception {
            // D6: the text carries the case's OWN preconditions, and the editor sends ids. The
            // bodies are the library's, which is the second thing this endpoint resolves.
            String text = render(ENGINEER, ACME_BANKING, """
                    {"title": "A refund lands", "suiteId": "%s", "stepFormat": "GHERKIN",
                     "priority": "MEDIUM", "automationStatus": "MANUAL",
                     "preconditions": [{"conditionId": "%s"}],
                     "steps": [{"keyword": "GIVEN", "action": "a captured payment"}]}
                    """.formatted(SUITE, PRECONDITION));

            assertThat(text).contains("  Background:\n");
            assertThat(text).contains("    Given the operator is signed in\n");
        }

        @Test
        @DisplayName("render → parse → render is stable")
        void theRenderSurvivesTheRoundTrip() throws Exception {
            // The contract's own claim for this path, and the reason it is asserted here rather
            // than trusted: `everyConstructSurvivesTheRoundTrip` pins the SAVED render, and this
            // endpoint is a second entry into the same renderer.
            String once = render(ENGINEER, ACME_BANKING, """
                    {"title": "A refund lands", "suiteId": "%s", "stepFormat": "GHERKIN",
                     "priority": "MEDIUM", "automationStatus": "MANUAL",
                     "steps": [{"keyword": "GIVEN", "action": "a captured payment"},
                               {"keyword": "THEN", "action": "the balance falls",
                                "expectedResult": "the balance falls by 100"}]}
                    """.formatted(SUITE));

            JsonNode parsed = json.readTree(parse(ENGINEER, ACME_BANKING, once));
            assertThat(parsed.get("title").asText()).isEqualTo("A refund lands");
            assertThat(parsed.get("steps")).hasSize(2);
            assertThat(parsed.get("steps").get(1).get("expectedResult").asText())
                    .isEqualTo("the balance falls by 100");
        }

        @Test
        @DisplayName("a member with no grant on the project gets 404, not a render")
        void theGateIsProjectVisibility() throws Exception {
            // The READ gate and no write permission, exactly as the paste has: it stores nothing.
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post(renderPath(ACME_BANKING))
                            .header("Authorization", "Bearer " + tokenFor(NO_GRANT, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title": "T", "stepFormat": "GHERKIN", "priority": "MEDIUM",
                                     "automationStatus": "MANUAL", "steps": []}
                                    """))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("a rendered document over the cap is a 413, same slug as the paste")
        void anOversizedRenderIsA413() throws Exception {
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < 20_000; i++) {
                steps.append(i == 0 ? "" : ",")
                        .append("{\"keyword\": \"GIVEN\", \"action\": \"a captured payment ")
                        .append(i).append("\"}");
            }

            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post(renderPath(ACME_BANKING))
                            .header("Authorization", "Bearer " + tokenFor(ENGINEER, ACME))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title": "Large", "stepFormat": "GHERKIN",
                                     "priority": "MEDIUM", "automationStatus": "MANUAL",
                                     "steps": [%s]}
                                    """.formatted(steps)))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(413);
            assertThat(json.readTree(result.getResponse().getContentAsString())
                    .get("type").asText())
                    .isEqualTo("payload-too-large");
        }

        @Test
        @DisplayName("a text/plain body is a 415 — this operation reads JSON")
        void aTextBodyIsA415() throws Exception {
            // The mirror of the paste's arm one nested class up, and the same reasoning: without
            // `consumes`, Spring would bind something.
            MvcResult result = mvc.perform(MockMvcRequestBuilders
                            .post(renderPath(ACME_BANKING))
                            .header("Authorization", "Bearer " + tokenFor(ENGINEER, ACME))
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("Given a captured payment"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(415);
        }
    }

    @Nested
    @DisplayName("the two halves agree")
    class RoundTrip {

        @Test
        @DisplayName("the rendered case parses back to the rows it was rendered from")
        void whatIsRenderedIsWhatIsParsed() throws Exception {
            // The endpoint-level statement of GherkinScenariosTest's property: this is the whole
            // point of "view as .feature", since a text a Cucumber runner accepts and this product
            // then cannot read back is an export rather than a view.
            String feature = featureOf(ENGINEER, ACME_BANKING, GHERKIN_CASE);
            JsonNode parsed =
                    json.readTree(parse(ENGINEER, ACME_BANKING, feature));

            assertThat(parsed.get("title").asText()).isEqualTo("A refund lands on the ledger");
            assertThat(parsed.get("steps")).hasSize(3);
            assertThat(parsed.get("steps").get(0).get("testData").asText()).isEqualTo("order-42");
            assertThat(parsed.get("steps").get(1).get("docString").asText())
                    .isEqualTo("{\"amount\": 100}");
            assertThat(parsed.get("steps").get(2).get("expectedResult").asText())
                    .isEqualTo("the balance falls by 100");
            assertThat(parsed.get("background")).hasSize(2);
            assertThat(fieldValues(parsed.get("tags"))).containsExactly("refunds", "smoke");
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private String featureOf(UUID userId, UUID projectId, UUID caseId) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get(featurePath(projectId, caseId))
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the render answered %s", result.getResponse().getStatus())
                .isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    /** The parse endpoint's body, whichever status it came with. */
    private String parse(UUID userId, UUID projectId, String text) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post(parsePath(projectId))
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(text))
                .andReturn().getResponse().getContentAsString();
    }

    /** The render endpoint's body, whichever status it came with. */
    private String render(UUID userId, UUID projectId, String body) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .post(renderPath(projectId))
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the render answered %s: %s",
                        result.getResponse().getStatus(),
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private static String renderPath(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/cases/render-gherkin";
    }

    private static String parsePath(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/cases/parse-gherkin";
    }

    private static String featurePath(UUID projectId, UUID caseId) {
        return "/api/v1/projects/" + projectId + "/cases/" + caseId + "/feature";
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private static List<String> fieldValues(JsonNode array) {
        assertThat(array.isArray()).as("expected a JSON array, got %s", array).isTrue();
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
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

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
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

        UUID id = UUID.nameUUIDFromBytes(("case-gherkin-endpoint:" + marker).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    /**
     * The cases, written through the ordinary app role.
     *
     * <p>The app role rather than the superuser on purpose: it is the same policed path production
     * reads through, so a fixture that could not be written under RLS is one that lies about what
     * the endpoint will see.
     */
    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, SUITE, ACME_BANKING, "E3OLB-S1", 1, "Refunds");
            suite(connection, MOBILE_SUITE, ACME_MOBILE, "E3MOB-S1", 2, "Payments");

            testCase(connection, GHERKIN_CASE, ACME_BANKING, SUITE, "E3OLB-1", 1,
                    "A refund lands on the ledger", "the ledger is the record of what happened",
                    "GHERKIN", null);
            // The three data spellings, one per step — ck_test_step_one_data forbids two on one.
            step(connection, 1, GHERKIN_CASE, 0, "GIVEN", "a captured payment of 100 EUR",
                    "order-42", null, null, null);
            step(connection, 2, GHERKIN_CASE, 1, "WHEN", "I refund it",
                    null, null, "{\"amount\": 100}", null);
            step(connection, 3, GHERKIN_CASE, 2, "THEN", "the ledger shows a credit",
                    null, "the balance falls by 100", null,
                    "[[\"line\",\"amount\"],[\"credit\",\"100\"]]");
            // Written out of alphabetical order, because Postgres returns a small table in
            // physical order and a fixture in the answer's order proves nothing (§25, §26).
            automationTag(connection, GHERKIN_CASE, "smoke");
            automationTag(connection, GHERKIN_CASE, "refunds");

            // A body whose lines ARE Gherkin steps, which is what the import (T6) writes and what
            // makes the rendered case re-parseable. A human-authored condition may legitimately
            // hold prose; its rendered Background is then text for a reader rather than a document
            // the parser accepts back, and api-surface §5.2 says so.
            condition(connection, PRECONDITION, ACME_BANKING, "The operator is signed in", "PRE",
                    "[\"Given the operator is signed in\",\"And the ledger is open\"]");
            condition(connection, POSTCONDITION, ACME_BANKING, "Reset the test payment data",
                    "POST", "[\"Reset the test payment data\"]");
            caseCondition(connection, 1, GHERKIN_CASE, "PRE", 0, PRECONDITION);
            caseCondition(connection, 2, GHERKIN_CASE, "POST", 0, POSTCONDITION);

            testCase(connection, STRUCTURED_CASE, ACME_BANKING, SUITE, "E3OLB-2", 2,
                    "Reading the closing balance", null, "STRUCTURED", null);
            step(connection, 11, STRUCTURED_CASE, 0, null, "Open the ledger", null, null,
                    null, null);
            step(connection, 12, STRUCTURED_CASE, 1, null, "Read the closing balance", null,
                    "The balance is shown", null, null);

            // Zero step rows and a stored scenario: the pre-§255 population.
            testCase(connection, LEGACY_CASE, ACME_BANKING, SUITE, "E3OLB-3", 3,
                    "Not yet migrated", null, "GHERKIN", LEGACY_BLOB);

            testCase(connection, MOBILE_CASE, ACME_MOBILE, MOBILE_SUITE, "E3MOB-1", 4,
                    "A case the caller may not reach", null, "GHERKIN", null);
            step(connection, 21, MOBILE_CASE, 0, "GIVEN", "the mobile app is open", null, null,
                    null, null);
        }
    }

    private void suite(Connection connection, UUID id, UUID projectId, String key, long keySeq,
            String name) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void testCase(Connection connection, UUID id, UUID projectId, UUID suiteId, String key,
            long keySeq, String title, String objective, String stepFormat, String gherkinSource)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, objective,
                     step_format, gherkin_source, priority, lifecycle_status, automation_status,
                     current_version_no, lock_version, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, ?, ?, ?, 'MEDIUM', 'DRAFT', 'MANUAL', 1, 0,
                        CAST('2026-01-01T00:00:00Z' AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, objective);
            statement.setString(9, stepFormat);
            statement.setString(10, gherkinSource);
            statement.executeUpdate();
        }
    }

    /**
     * One step. Nullable parameters bound through an explicit {@code CAST}.
     *
     * <p>{@code setObject(i, null)} leaves pgjdbc with no type to infer and fails with "could not
     * determine data type of parameter" — a fixture-only failure that reads like a schema problem.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private void step(Connection connection, int marker, UUID caseId, int position, String keyword,
            String action, String testData, String expectedResult, String docString,
            String dataTable) throws SQLException {

        UUID id = UUID.fromString(String.format("e3600000-0000-4000-8000-%012d", marker));
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_step
                    (id, company_id, case_id, position, keyword, action, test_data,
                     expected_result, docstring, datatable)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, ?,
                        CAST(? AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setInt(4, position);
            statement.setString(5, keyword);
            statement.setString(6, action);
            statement.setString(7, testData);
            statement.setString(8, expectedResult);
            statement.setString(9, docString);
            statement.setString(10, dataTable);
            statement.executeUpdate();
        }
    }

    private void automationTag(Connection connection, UUID caseId, String tag) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO case_automation_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT (case_id, tag) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private void condition(Connection connection, UUID id, UUID projectId, String title,
            String type, String body) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, type, body, usage_count)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS jsonb), 0)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, title);
            statement.setString(5, type);
            statement.setString(6, body);
            statement.executeUpdate();
        }
    }

    private void caseCondition(Connection connection, int marker, UUID caseId, String kind,
            int position, UUID conditionId) throws SQLException {

        UUID id = UUID.fromString(String.format("e3700000-0000-4000-8000-%012d", marker));
        try (var statement = connection.prepareStatement("""
                INSERT INTO case_condition
                    (id, company_id, case_id, kind, position, condition_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setString(4, kind);
            statement.setInt(5, position);
            statement.setString(6, conditionId.toString());
            statement.executeUpdate();
        }
    }
}
