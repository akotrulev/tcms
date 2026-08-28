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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * {@code POST /projects/{projectId}/feature-imports} — the whole of blueprint area D.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A preview that writes.</strong> The entire value of {@code PREVIEW} is that a test
 *       engineer can point it at a repository they are not sure about. It is asserted by row counts
 *       on five tables, not by the absence of an id in the response.</li>
 *   <li><strong>A re-import that rewrites everything.</strong> Without {@code imported_source} and
 *       the condition hash, running the same folder twice mints a version per case and a second
 *       copy of every condition — noise on every client's history, produced by a button labelled
 *       "import".</li>
 *   <li><strong>An update that reassigns step ids.</strong> {@code CaseSteps.reconcile} deletes and
 *       re-inserts a step whose id it was not given, taking every {@code step_result} recorded
 *       against it. The case screen looks correct afterwards; the run history is gone.</li>
 *   <li><strong>An import that eats a human's work.</strong> A precondition somebody attached and a
 *       postcondition nobody imported must both survive an {@code UPDATE} — catalog addendum I.3.
 *       </li>
 *   <li><strong>A 403 for the request.</strong> A caller with {@code case:read} must get a complete
 *       plan in which every row is refused, because "which of my forty files could I import" is the
 *       question the page is for.</li>
 *   <li><strong>A key spelled by hand.</strong> The imported keys are compared against the ones
 *       {@code CucumberReportParser} builds out of the same fixture report — see
 *       {@code com.tcms.execution.AutomationKeysTest}, which makes the same comparison from the
 *       other module.</li>
 * </ol>
 *
 * <p>Company, user and project ids are unique to this class (prefix {@code 1d}) and so are its
 * company slugs ({@code fimp-}) — a slug is a second global namespace the id prefix does not cover,
 * because {@code company} is not under RLS and {@code uq_company_slug} spans the instance.
 */
@AutoConfigureMockMvc
@DisplayName("Feature import — plan, apply, re-import, and what it must not touch")
class FeatureImportTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("1d000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("1d000000-0000-4000-8000-00000000000b");

    private static final UUID SHOP = UUID.fromString("1d001000-0000-4000-8000-000000000001");
    /** Same company, granted to LEAD only — where the sibling-project key lives. */
    private static final UUID PAY = UUID.fromString("1d001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SHOP = UUID.fromString("1d002000-0000-4000-8000-000000000001");

    /** Test Lead on SHOP and PAY. Holds all four keys the import asks about. */
    private static final UUID LEAD = UUID.fromString("1d003000-0000-4000-8000-000000000001");
    /** Viewer on SHOP — {@code case:read} and nothing this endpoint can act on. */
    private static final UUID VIEWER = UUID.fromString("1d003000-0000-4000-8000-000000000002");
    /** Every key the import needs EXCEPT {@code library:manage} — the custom role below. */
    private static final UUID LIBRARYLESS = UUID.fromString("1d003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("1d003000-0000-4000-8000-000000000004");

    private static final UUID NO_LIBRARY_ROLE =
            UUID.fromString("1d005000-0000-4000-8000-000000000001");

    /** Named "Payments", so {@code payments.feature} REUSEs it rather than creating one. */
    private static final UUID PAYMENTS_SUITE = UUID.fromString("1d004000-0000-4000-8000-000000000001");
    /** Two suites, one name — the ambiguity {@code twins.feature} runs into. */
    private static final UUID TWIN_A = UUID.fromString("1d004000-0000-4000-8000-000000000002");
    private static final UUID TWIN_B = UUID.fromString("1d004000-0000-4000-8000-000000000003");
    /** The explicit target for the {@code suiteId} arm. */
    private static final UUID TARGET_SUITE = UUID.fromString("1d004000-0000-4000-8000-000000000004");
    private static final UUID PAY_SUITE = UUID.fromString("1d004000-0000-4000-8000-000000000005");

    /** Filed in PAYMENTS_SUITE with a key no fixture file names — the orphan. */
    private static final UUID ORPHAN_CASE = UUID.fromString("1d006000-0000-4000-8000-000000000001");
    /** In PAY, holding the key {@code sibling.feature} wants. */
    private static final UUID SIBLING_CASE = UUID.fromString("1d006000-0000-4000-8000-000000000002");
    /** A human's library entry, attached by hand and never owned by the import. */
    private static final UUID HUMAN_PRE = UUID.fromString("1d007000-0000-4000-8000-000000000001");
    private static final UUID HUMAN_POST = UUID.fromString("1d007000-0000-4000-8000-000000000002");

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
            company(ACME, "fimp-acme", "Acme", now);
            company(UMBRA, "fimp-umbra", "Umbra", now);
            activeUser(LEAD, "lead@fimp-acme.test", "L. Lead", now);
            activeUser(VIEWER, "viewer@fimp-acme.test", "V. Viewer", now);
            activeUser(LIBRARYLESS, "nolib@fimp-acme.test", "N. Olib", now);
            activeUser(NO_GRANT, "nogrant@fimp-acme.test", "N. Grant", now);
        });

        customRole(NO_LIBRARY_ROLE, "Fixture — import without library:manage", List.of(
                "project:read", "case:read", "case:create", "case:edit", "suite:manage"));

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(SHOP, ACME, "FSHOP", "Shop", now);
            seedProject(PAY, ACME, "FPAY", "Payments platform", now);
            member(ACME, LEAD, "L. Lead", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, LIBRARYLESS, "N. Olib", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            grant("lead-shop", ACME, Role.TEST_LEAD_ID, LEAD, SHOP, now);
            grant("lead-pay", ACME, Role.TEST_LEAD_ID, LEAD, PAY, now);
            grant("viewer-shop", ACME, Role.VIEWER_ID, VIEWER, SHOP, now);
            grant("nolib-shop", ACME, NO_LIBRARY_ROLE, LIBRARYLESS, SHOP, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status ->
                seedProject(UMBRA_SHOP, UMBRA, "FUSHP", "Shop", now)));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedTrees();
        seedExistingRows();
    }

    /**
     * Everything a test can write, deleted, and the key counters put back to 1.
     *
     * <p>The counters matter here more than anywhere: half the assertions below are about
     * {@code TC-1} and {@code TS-5} being the keys the import allocated, and
     * {@code tcms_seed_company_defaults} is {@code ON CONFLICT DO NOTHING} — so without this every
     * one of them would be green alone and red in the suite, which is handoff §19's defect.
     *
     * <p>Suites are deleted too, unlike in most sibling classes: this endpoint CREATES suites, and
     * a suite left behind by a previous method would turn the next method's {@code CREATE} into a
     * {@code REUSE} — a wrong answer that looks like a correct one.
     */
    private void resetMutableRows() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            execute(connection, "DELETE FROM audit_event");
            execute(connection, "DELETE FROM case_condition");
            execute(connection, "DELETE FROM suite_condition");
            execute(connection, "DELETE FROM condition");
            execute(connection, "DELETE FROM test_case_version");
            execute(connection, "DELETE FROM test_step");
            execute(connection, "DELETE FROM suite_case");
            execute(connection, "DELETE FROM test_case");
            execute(connection, "DELETE FROM suite");
            execute(connection, "UPDATE key_sequence SET next_value = 1");
        }
    }

    // =====================================================================
    // PREVIEW
    // =====================================================================

    @Nested
    @DisplayName("PREVIEW — the plan, and nothing on disk")
    class Preview {

        @Test
        @DisplayName("three features plan four cases, three suites and four conditions")
        void theWholeFolderIsPlanned() throws Exception {
            MvcResult result = importing(LEAD, SHOP, preview("orders", "payments", "rules"));

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode plan = body(result);
            assertThat(plan.get("applied").asBoolean()).isFalse();
            assertThat(plan.get("mode").asText()).isEqualTo("PREVIEW");

            assertThat(actions(plan)).containsExactly("CREATE", "CREATE", "CREATE", "CREATE");
            assertThat(keysOf(plan)).containsExactly(
                    "Orders#Cancel an order",
                    "Orders#Refund an order",
                    "Payments#Pay by card",
                    "Shipping#A large order ships free");

            assertThat(texts(plan.get("suites"), "name"))
                    .containsExactly("Orders", "Payments", "Shipping");
            assertThat(texts(plan.get("suites"), "action"))
                    // Payments already exists in the tree, so it is matched rather than created —
                    // an import that created a second "Payments" would fork the tree on every run.
                    .containsExactly("CREATE", "REUSE", "CREATE");

            // FOUR conditions from three features, and that is the whole of amendment AM1: one per
            // distinct Background STEP, deduplicated across the request. "I am signed in as a
            // customer" appears in all three files and is one library entry.
            assertThat(texts(plan.get("conditions"), "title")).containsExactly(
                    "Given I am signed in as a customer",
                    "And the catalogue has been loaded",
                    "And my basket is empty",
                    "Given my address is in the EU");
            assertThat(texts(plan.get("conditions"), "action"))
                    .containsOnly("CREATE");

            JsonNode totals = plan.get("totals");
            assertThat(totals.get("create").asInt()).isEqualTo(4);
            assertThat(totals.get("update").asInt()).isZero();
            assertThat(totals.get("skip").asInt()).isZero();
            assertThat(totals.get("refuse").asInt()).isZero();
        }

        @Test
        @DisplayName("a preview writes nothing — five tables, counted")
        void nothingIsWritten() throws Exception {
            importing(LEAD, SHOP, preview("orders", "payments", "rules"));

            // Counted rather than inferred from the absence of ids in the response: a service that
            // wrote and then failed to report would answer an identical body.
            // Two, not zero: the orphan and the sibling-project case the fixture seeds. The
            // number is the fixture's, and a preview that wrote would move it.
            assertThat(count("test_case")).isEqualTo(2);
            assertThat(count("test_step")).isZero();
            assertThat(count("condition")).isZero();
            assertThat(count("case_condition")).isZero();
            assertThat(count("test_case_version")).isZero();
            // The suite fixture is the four seeded ones and nothing more.
            assertThat(count("suite")).isEqualTo(5);
        }

        @Test
        @DisplayName("each row lists the preconditions it will carry, in Background order")
        void preconditionsAreListedInOrder() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, preview("orders", "rules")));

            assertThat(preconditionsOf(plan, "Orders#Cancel an order")).containsExactly(
                    "Given I am signed in as a customer",
                    "And the catalogue has been loaded",
                    "And my basket is empty");
            // The Rule's own Background follows the feature's — api-surface.md §5.4.
            assertThat(preconditionsOf(plan, "Shipping#A large order ships free")).containsExactly(
                    "Given I am signed in as a customer",
                    "Given my address is in the EU");
        }

        @Test
        @DisplayName("a file that does not parse is ONE row, with the parser's line")
        void aParserErrorIsOneRow() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, preview("broken")));

            assertThat(plan.get("scenarios")).hasSize(1);
            JsonNode row = plan.get("scenarios").get(0);
            assertThat(row.get("action").asText()).isEqualTo("REFUSE");
            assertThat(row.get("scenario").isNull()).isTrue();
            assertThat(row.get("line").isNull()).isTrue();
            // The line is the whole value of the sentence: without it the caller has a file name.
            assertThat(row.get("reason").asText()).matches("Line \\d+.*");
        }

        @Test
        @DisplayName("an Outline refuses its own row and its sibling scenario still imports")
        void anOutlineRefusesOneRow() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, preview("outline")));

            assertThat(actions(plan)).containsExactly("CREATE", "REFUSE");
            assertThat(reasonFor(plan, "Discounts#Tiered discounts"))
                    .isEqualTo("Scenario Outline is not supported yet");
            assertThat(plan.get("totals").get("create").asInt()).isOne();
        }

        @Test
        @DisplayName("a file with no Feature: name is a whole-file refusal")
        void aNamelessFeatureIsRefused() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, request(
                    "PREVIEW", "SKIP", null, file("empty.feature", "# nothing here\n"))));

            assertThat(plan.get("scenarios")).hasSize(1);
            assertThat(plan.get("scenarios").get(0).get("reason").asText())
                    .isEqualTo("Feature has no name");
        }

        @Test
        @DisplayName("two suites with one name refuse the rows and name both keys")
        void ambiguousSuitesRefuse() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, preview("twins")));

            String expected = "two suites are named 'Twins' (TS-2, TS-3) — pass suiteId";
            assertThat(texts(plan.get("suites"), "reason")).containsExactly(expected);
            assertThat(reasonFor(plan, "Twins#Two suites answer to this name")).isEqualTo(expected);
        }

        @Test
        @DisplayName("a key held in a sibling project is refused NAMING the case and the project")
        void aSiblingProjectsKeyIsRefused() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, preview("sibling")));

            // RLS scopes to the COMPANY, so this key is visible to the session and the insert would
            // fail at test_case_automation_key_idx. Reporting it as a row is what turns a 409 that
            // rolls back forty files into one refusal the caller can act on.
            assertThat(reasonFor(plan, "Sibling#Taken"))
                    .isEqualTo("automationKey already used by TC-901 in project FPAY");
        }

        @Test
        @DisplayName("a suiteId files everything and matches no suite at all")
        void anExplicitSuiteMatchesNothing() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, request(
                    "PREVIEW", "SKIP", TARGET_SUITE, feature("orders"), feature("payments"))));

            assertThat(plan.get("suites")).isEmpty();
            assertThat(actions(plan)).containsOnly("CREATE");
        }

        @Test
        @DisplayName("orphans in the target suite are listed and nothing else is")
        void orphansAreListed() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, request(
                    "PREVIEW", "SKIP", PAYMENTS_SUITE, feature("payments"))));

            assertThat(plan.get("orphans")).hasSize(1);
            assertThat(plan.get("orphans").get(0).get("automationKey").asText())
                    .isEqualTo("Payments#Retired long ago");
            assertThat(plan.get("orphans").get(0).get("caseKey").asText()).isEqualTo("TC-900");
        }
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — case:read to ask, and every other key is a ROW")
    class TheGate {

        @Test
        @DisplayName("a case:read-only caller gets a plan, not a 403, and every row is refused")
        void aReadOnlyCallerGetsAPlan() throws Exception {
            MvcResult result = importing(VIEWER, SHOP, preview("catalogue"));

            assertThat(result.getResponse().getStatus())
                    .as("a 403 tells a test engineer nothing about which files they could import")
                    .isEqualTo(200);
            assertThat(reasonFor(body(result), "Catalogue#Browse the catalogue"))
                    .isEqualTo("needs case:create");
            assertThat(body(result).get("totals").get("refuse").asInt()).isOne();
        }

        @Test
        @DisplayName("a caller without library:manage is refused per scenario, Background named")
        void theBackgroundNeedsTheLibraryKey() throws Exception {
            JsonNode plan = body(importing(LIBRARYLESS, SHOP, preview("orders", "catalogue")));

            // Both of Orders' scenarios, because the whole scenario is refused rather than attached
            // without its setup — a case imported without its Background is one a tester runs and
            // fails for a reason the file already stated.
            assertThat(reasonFor(plan, "Orders#Cancel an order"))
                    .isEqualTo("needs library:manage for the Background");
            assertThat(reasonFor(plan, "Orders#Refund an order"))
                    .isEqualTo("needs library:manage for the Background");
            // And a feature with no Background is untouched by the missing key.
            assertThat(actionFor(plan, "Catalogue#Browse the catalogue")).isEqualTo("CREATE");
        }

        @Test
        @DisplayName("a member with no grant gets 404 with no body — persona 3, not 403")
        void noGrantIsNotFound() throws Exception {
            MvcResult result = importing(NO_GRANT, SHOP, preview("catalogue"));

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }

        @Test
        @DisplayName("another client's project answers 404 and nothing is written into it")
        void crossCompanyIsNotFound() throws Exception {
            assertThat(importing(LEAD, ACME, UMBRA_SHOP, preview("catalogue"))
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("unauthenticated is 401")
        void unauthenticatedIsRejected() throws Exception {
            int status = mvc.perform(MockMvcRequestBuilders.post(path(SHOP))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(preview("catalogue"))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(401);
        }

        @Test
        @DisplayName("an empty file list and an unknown suiteId are field errors, not 500s")
        void theRequestShapeIsValidated() throws Exception {
            MvcResult empty = importing(LEAD, SHOP, request("PREVIEW", "SKIP", null));
            assertThat(empty.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(empty).get("errors").has("files")).isTrue();

            MvcResult unknownSuite = importing(LEAD, SHOP, request(
                    "PREVIEW", "SKIP", UUID.randomUUID(), feature("catalogue")));
            assertThat(unknownSuite.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(unknownSuite).get("errors").has("suiteId")).isTrue();
        }
    }

    // =====================================================================
    // APPLY
    // =====================================================================

    @Nested
    @DisplayName("APPLY — one transaction, and the provenance that makes a replay free")
    class Apply {

        @Test
        @DisplayName("suites, conditions and cases are written and every row names its case")
        void theFolderIsImported() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP, apply("orders", "payments", "rules")));

            assertThat(plan.get("applied").asBoolean()).isTrue();
            assertThat(actions(plan)).containsOnly("CREATE");
            for (JsonNode row : plan.get("scenarios")) {
                assertThat(row.get("caseKey").asText()).matches("TC-\\d+");
            }

            assertThat(count("test_case")).isEqualTo(2 + 4);
            assertThat(count("condition")).isEqualTo(4);
            // One PRE attachment per Background step: 3 + 3 for Orders' two scenarios, 1 for
            // Payments, 2 for Shipping.
            assertThat(count("case_condition")).isEqualTo(9);
            // v1 for each — a created case with no version is a case whose history starts at 2.
            assertThat(count("test_case_version")).isEqualTo(4);
            // Orders and Shipping were created; Payments was reused.
            assertThat(count("suite")).isEqualTo(7);
        }

        @Test
        @DisplayName("the provenance triple is written and served back as importedFrom")
        void provenanceIsRecorded() throws Exception {
            importing(LEAD, SHOP, apply("orders"));

            Map<String, String> stored = importedOf("Orders#Cancel an order");
            assertThat(stored.get("imported_path")).isEqualTo("features/orders.feature");
            assertThat(stored.get("imported_line")).isEqualTo("12");
            assertThat(stored.get("imported_source")).startsWith("  Scenario: Cancel an order");
            assertThat(stored.get("imported_source")).endsWith("# expected: the order status reads Cancelled");

            JsonNode served = body(get(LEAD, "/projects/" + SHOP + "/cases/"
                    + caseIdOf("Orders#Cancel an order")));
            assertThat(served.get("importedFrom").get("path").asText())
                    .isEqualTo("features/orders.feature");
            assertThat(served.get("importedFrom").get("line").asInt()).isEqualTo(12);
        }

        @Test
        @DisplayName("a doc string and a data table survive the import as step rows")
        void theBlockArgumentsSurvive() throws Exception {
            importing(LEAD, SHOP, apply("orders"));

            JsonNode refund = body(get(LEAD, "/projects/" + SHOP + "/cases/"
                    + caseIdOf("Orders#Refund an order")));
            JsonNode steps = refund.get("steps");

            assertThat(steps).hasSize(3);
            assertThat(steps.get(1).get("docString").asText())
                    .isEqualTo("The parcel arrived damaged on both corners.");
            assertThat(steps.get(2).get("dataTable").get(1).get(0).asText()).isEqualTo("amount");
            assertThat(steps.get(2).get("dataTable").get(1).get(1).asText()).isEqualTo("42.00");
            assertThat(refund.get("stepFormat").asText()).isEqualTo("GHERKIN");
            assertThat(refund.get("automationStatus").asText()).isEqualTo("AUTOMATED");
        }

        @Test
        @DisplayName("a Rule's name becomes the objective's prefix and its Background attaches second")
        void aRuleIsCarried() throws Exception {
            importing(LEAD, SHOP, apply("rules"));

            JsonNode shipped = body(get(LEAD, "/projects/" + SHOP + "/cases/"
                    + caseIdOf("Shipping#A large order ships free")));

            assertThat(shipped.get("objective").asText()).startsWith("Rule: Free over fifty");
            assertThat(titlesOf(shipped.get("preconditions"))).containsExactly(
                    "Given I am signed in as a customer",
                    "Given my address is in the EU");
        }

        @Test
        @DisplayName("the imported keys are the ones a Cucumber report resolves to")
        void theKeysMatchTheReport() throws Exception {
            importing(LEAD, SHOP, apply("orders", "payments", "rules"));

            // The same four strings com.tcms.execution.AutomationKeysTest reads out of
            // gherkin/import/report.json with CucumberReportParser. This is the repository end of
            // that comparison; the parser is package-private in `execution` and cannot be reached
            // from here, which is why the check is made twice against one fixture.
            assertThat(storedAutomationKeys()).containsExactly(
                    "Orders#Cancel an order",
                    "Orders#Refund an order",
                    "Payments#Pay by card",
                    "Shipping#A large order ships free");
        }

        @Test
        @DisplayName("a replay writes nothing and says so row by row")
        void aReplayIsANoOp() throws Exception {
            importing(LEAD, SHOP, apply("orders", "payments", "rules"));
            long versions = count("test_case_version");
            long conditions = count("condition");

            JsonNode replay = body(importing(LEAD, SHOP, apply("orders", "payments", "rules")));

            assertThat(actions(replay)).containsOnly("SKIP");
            assertThat(texts(replay.get("scenarios"), "reason")).containsOnly("exists (SKIP)");
            assertThat(texts(replay.get("conditions"), "action")).containsOnly("UNCHANGED");
            assertThat(count("test_case_version")).isEqualTo(versions);
            assertThat(count("condition")).isEqualTo(conditions);
            assertThat(count("test_case")).isEqualTo(2 + 4);
        }

        @Test
        @DisplayName("under UPDATE, an unchanged file is still SKIP — the imported_source read")
        void anUnchangedFileMintsNoVersion() throws Exception {
            importing(LEAD, SHOP, apply("orders"));
            long versions = count("test_case_version");

            JsonNode replay = body(importing(LEAD, SHOP,
                    request("APPLY", "UPDATE", null, feature("orders"))));

            assertThat(actions(replay)).containsOnly("SKIP");
            assertThat(texts(replay.get("scenarios"), "reason"))
                    .containsOnly("unchanged since import");
            assertThat(count("test_case_version")).isEqualTo(versions);
        }

        @Test
        @DisplayName("a local edit SURVIVES a re-import of an unchanged file — the import is not a mirror")
        void aLocalEditSurvivesAnUnchangedFile() throws Exception {
            importing(LEAD, SHOP, apply("orders"));
            UUID caseId = caseIdOf("Orders#Cancel an order");
            rename(caseId, "Renamed by a human");

            JsonNode replay = body(importing(LEAD, SHOP,
                    request("APPLY", "UPDATE", null, feature("orders"))));

            /*
              SKIP, and this is the design rather than a gap. R6 compares the FILE against
              `imported_source` — what this import last wrote — and NOT against the case as it
              stands now, so an unchanged file is unchanged work whatever anybody did to the case
              in between (`api-surface.md` §5.4: "identical -> SKIP 'unchanged since import'",
              nothing written, no version).

              The consequence, stated because it is the surprising half and a reader will otherwise
              expect the opposite: the import does NOT continuously mirror the repository. A human's
              rename stands until the SCENARIO's own text changes, and then it is overwritten
              wholesale. The alternative — comparing against the case — would re-write and re-version
              every locally-edited case on every nightly re-import of a folder nobody touched, which
              is what makes a replay free rather than a write of the whole suite.
            */
            assertThat(actionFor(replay, "Orders#Cancel an order")).isEqualTo("SKIP");
            assertThat(reasonFor(replay, "Orders#Cancel an order"))
                    .isEqualTo("unchanged since import");
            assertThat(body(get(LEAD, "/projects/" + SHOP + "/cases/" + caseId))
                    .get("title").asText())
                    .isEqualTo("Renamed by a human");
        }

        @Test
        @DisplayName("a local edit IS overwritten once the scenario itself changes")
        void aLocalEditIsOverwrittenWhenTheFileMoves() throws Exception {
            importing(LEAD, SHOP, apply("orders"));
            UUID caseId = caseIdOf("Orders#Cancel an order");
            rename(caseId, "Renamed by a human");

            // The scenario's own text moves, so R6's short-circuit does not fire and the file wins.
            body(importing(LEAD, SHOP, request("APPLY", "UPDATE", null,
                    file("features/orders.feature",
                            resource("orders").replace(
                                    "The customer changes their mind before dispatch.",
                                    "The customer changes their mind before it ships.")))));

            assertThat(body(get(LEAD, "/projects/" + SHOP + "/cases/" + caseId))
                    .get("title").asText())
                    .isEqualTo("Cancel an order");
        }

        @Test
        @DisplayName("an UPDATE keeps the ids of the steps it did not change")
        void stepIdsSurviveAnUpdate() throws Exception {
            importing(LEAD, SHOP, apply("orders"));
            UUID caseId = caseIdOf("Orders#Cancel an order");
            List<UUID> before = stepIdsOf(caseId);

            JsonNode replay = body(importing(LEAD, SHOP, request("APPLY", "UPDATE", null,
                    file("features/orders.feature",
                            resource("orders").replace("When I cancel it",
                                    "When I cancel it from the order page")))));

            assertThat(actionFor(replay, "Orders#Cancel an order")).isEqualTo("UPDATE");
            List<UUID> after = stepIdsOf(caseId);
            assertThat(after).hasSize(3);
            // The Given and the Then keep their ids — and with them every step_result recorded
            // against them. Only the edited When is a new row.
            assertThat(after.get(0)).isEqualTo(before.get(0));
            assertThat(after.get(2)).isEqualTo(before.get(2));
            assertThat(after.get(1)).isNotEqualTo(before.get(1));
        }

        @Test
        @DisplayName("an APPROVED case is not touched by an UPDATE")
        void approvedIsUntouched() throws Exception {
            importing(LEAD, SHOP, apply("orders"));
            setLifecycle(caseIdOf("Orders#Cancel an order"), "APPROVED");
            setLifecycle(caseIdOf("Orders#Refund an order"), "DEPRECATED");

            JsonNode replay = body(importing(LEAD, SHOP, request("APPLY", "UPDATE", null,
                    file("features/orders.feature",
                            resource("orders").replace("Then the order is cancelled",
                                    "Then the order is void")))));

            assertThat(reasonFor(replay, "Orders#Cancel an order"))
                    .isEqualTo("approved — not touched by UPDATE");
            assertThat(reasonFor(replay, "Orders#Refund an order"))
                    .isEqualTo("deprecated — not touched by UPDATE");
            assertThat(actions(replay)).containsOnly("SKIP");
        }

        @Test
        @DisplayName("a human's precondition and postcondition survive a re-import")
        void humanConditionsSurvive() throws Exception {
            importing(LEAD, SHOP, apply("orders"));
            UUID caseId = caseIdOf("Orders#Cancel an order");
            humanCondition(HUMAN_PRE, "PRE", "A human attached this");
            humanCondition(HUMAN_POST, "POST", "And this one too");
            attachByHand(caseId, HUMAN_PRE, "PRE");
            attachByHand(caseId, HUMAN_POST, "POST");

            JsonNode replay = body(importing(LEAD, SHOP, request("APPLY", "UPDATE", null,
                    file("features/orders.feature",
                            resource("orders").replace("Then the order is cancelled",
                                    "Then the order is void")))));

            assertThat(actionFor(replay, "Orders#Cancel an order")).isEqualTo("UPDATE");
            JsonNode served = body(get(LEAD, "/projects/" + SHOP + "/cases/" + caseId));
            // The import-owned three, in Background order, THEN the human's — catalog addendum I.3.
            assertThat(titlesOf(served.get("preconditions"))).containsExactly(
                    "Given I am signed in as a customer",
                    "And the catalogue has been loaded",
                    "And my basket is empty",
                    "A human attached this");
            // POST is never sent by the import, so it cannot have been reordered or dropped.
            assertThat(titlesOf(served.get("postconditions")))
                    .containsExactly("And this one too");
            assertThat(preconditionsOf(replay, "Orders#Cancel an order")).containsExactly(
                    "Given I am signed in as a customer",
                    "And the catalogue has been loaded",
                    "And my basket is empty",
                    "A human attached this");
        }

        @Test
        @DisplayName("a Background step removed from the file detaches, and the library keeps it")
        void aRemovedBackgroundStepDetaches() throws Exception {
            importing(LEAD, SHOP, apply("orders"));

            body(importing(LEAD, SHOP, request("APPLY", "UPDATE", null,
                    file("features/orders.feature",
                            resource("orders").replace("    And my basket is empty\n", "")))));

            JsonNode served = body(get(LEAD, "/projects/" + SHOP + "/cases/"
                    + caseIdOf("Orders#Cancel an order")));
            assertThat(titlesOf(served.get("preconditions"))).containsExactly(
                    "Given I am signed in as a customer",
                    "And the catalogue has been loaded");
            // The entry itself stays: a human retires a library condition, never an import.
            assertThat(count("condition")).isEqualTo(3);
        }

        @Test
        @DisplayName("re-ordering a Background re-mints nothing — the key is a text hash")
        void aReorderedBackgroundMintsNothing() throws Exception {
            importing(LEAD, SHOP, apply("orders"));

            // The two `And` steps swap places and nothing else changes, so both normalised texts —
            // and therefore both hashes — are exactly what they were. A POSITIONAL key would mint
            // two new conditions here and leave two orphans in the library on every reformat of an
            // automation repository.
            JsonNode replay = body(importing(LEAD, SHOP, request("APPLY", "UPDATE", null,
                    file("features/orders.feature", resource("orders")
                            .replace("    And the catalogue has been loaded\n"
                                            + "    And my basket is empty\n",
                                    "    And my basket is empty\n"
                                            + "    And the catalogue has been loaded\n")))));

            assertThat(texts(replay.get("conditions"), "action")).containsOnly("UNCHANGED");
            assertThat(count("condition")).isEqualTo(3);
            // The ATTACHMENTS are re-ordered, which is the half that does change.
            assertThat(preconditionsOf(replay, "Orders#Cancel an order")).containsExactly(
                    "Given I am signed in as a customer",
                    "And my basket is empty",
                    "And the catalogue has been loaded");
        }

        @Test
        @DisplayName("a refused row does not stop the rows beside it")
        void refusalsAreNotFatal() throws Exception {
            JsonNode plan = body(importing(LEAD, SHOP,
                    apply("catalogue", "broken", "outline", "twins")));

            assertThat(plan.get("totals").get("create").asInt()).isEqualTo(2);
            assertThat(plan.get("totals").get("refuse").asInt()).isEqualTo(3);
            assertThat(count("test_case")).isEqualTo(2 + 2);
        }
    }

    // =====================================================================
    // fixture
    // =====================================================================

    private static String path(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/feature-imports";
    }

    private Map<String, Object> preview(String... names) {
        return request("PREVIEW", "SKIP", null, filesOf(names));
    }

    private Map<String, Object> apply(String... names) {
        return request("APPLY", "SKIP", null, filesOf(names));
    }

    /**
     * The named fixtures as request files.
     *
     * <p>A {@code List} rather than an array on purpose: {@code new Map[n]} is a RAW type and there
     * is no way to spell {@code new Map<String, Object>[n]}, so the array version needs a
     * {@code rawtypes} warning this module refuses under {@code -Werror}.
     */
    private List<Map<String, Object>> filesOf(String[] names) {
        List<Map<String, Object>> files = new ArrayList<>(names.length);
        for (String name : names) {
            files.add(feature(name));
        }
        return files;
    }

    /**
     * The varargs door, for the arms that name their files inline.
     *
     * <p>The array is copied in a loop rather than handed to {@code Arrays.asList} or
     * {@code Arrays.stream}: {@code @SafeVarargs} promises the array does not ESCAPE, so javac
     * warns at any call that takes it — and this module compiles with {@code -Werror}.
     */
    @SafeVarargs
    private Map<String, Object> request(
            String mode, String onExisting, UUID suiteId, Map<String, Object>... files) {
        List<Map<String, Object>> named = new ArrayList<>(files.length);
        for (Map<String, Object> file : files) {
            named.add(file);
        }
        return request(mode, onExisting, suiteId, named);
    }

    private Map<String, Object> request(
            String mode, String onExisting, UUID suiteId, List<Map<String, Object>> files) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("files", files);
        body.put("suiteId", suiteId == null ? null : suiteId.toString());
        body.put("onExisting", onExisting);
        body.put("mode", mode);
        return body;
    }

    private Map<String, Object> feature(String name) {
        try {
            return file("features/" + name + ".feature", resource(name));
        } catch (IOException unreadable) {
            throw new IllegalStateException("fixture " + name + ".feature is missing", unreadable);
        }
    }

    private static Map<String, Object> file(String path, String content) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("path", path);
        file.put("content", content);
        return file;
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = FeatureImportTest.class
                .getResourceAsStream("/gherkin/import/" + name + ".feature")) {
            assertThat(stream).as("fixture " + name + ".feature").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ HTTP ----

    private MvcResult importing(UUID userId, UUID projectId, Map<String, Object> payload)
            throws Exception {
        return importing(userId, ACME, projectId, payload);
    }

    private MvcResult importing(
            UUID userId, UUID companyId, UUID projectId, Map<String, Object> payload)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders.post(path(projectId))
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult get(UUID userId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1" + path)
                        .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("the endpoint answered with no body").isNotEmpty();
        return json.readTree(content);
    }

    // ------------------------------------------------------------------ reading the plan ----

    private static List<String> actions(JsonNode plan) {
        return texts(plan.get("scenarios"), "action");
    }

    private static List<String> keysOf(JsonNode plan) {
        return texts(plan.get("scenarios"), "automationKey");
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(
                node.get(field) == null || node.get(field).isNull()
                        ? null
                        : node.get(field).asText()));
        return values;
    }

    private static JsonNode rowFor(JsonNode plan, String automationKey) {
        for (JsonNode row : plan.get("scenarios")) {
            if (!row.get("automationKey").isNull()
                    && automationKey.equals(row.get("automationKey").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + automationKey);
    }

    private static String reasonFor(JsonNode plan, String automationKey) {
        return rowFor(plan, automationKey).get("reason").asText();
    }

    private static String actionFor(JsonNode plan, String automationKey) {
        return rowFor(plan, automationKey).get("action").asText();
    }

    private static List<String> preconditionsOf(JsonNode plan, String automationKey) {
        List<String> titles = new ArrayList<>();
        rowFor(plan, automationKey).get("preconditions").forEach(node -> titles.add(node.asText()));
        return titles;
    }

    private static List<String> titlesOf(JsonNode conditions) {
        List<String> titles = new ArrayList<>();
        conditions.forEach(node -> titles.add(node.get("title").asText()));
        return titles;
    }

    // ------------------------------------------------------------------ reading the database ----

    private long count(String table) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement =
                     connection.prepareStatement("SELECT count(*) FROM " + table);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private UUID caseIdOf(String automationKey) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM test_case WHERE automation_key = ? AND deleted_at IS NULL")) {
            statement.setString(1, automationKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no case keyed " + automationKey).isTrue();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private Map<String, String> importedOf(String automationKey) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT imported_source, imported_path, imported_line
                       FROM test_case WHERE automation_key = ?
                     """)) {
            statement.setString(1, automationKey);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Map<String, String> stored = new LinkedHashMap<>();
                stored.put("imported_source", rows.getString(1));
                stored.put("imported_path", rows.getString(2));
                stored.put("imported_line", rows.getString(3));
                return stored;
            }
        }
    }

    private List<String> storedAutomationKeys() throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT automation_key FROM test_case
                      WHERE automation_key IS NOT NULL AND deleted_at IS NULL
                        AND key_seq < 900
                      ORDER BY key_seq
                     """);
             ResultSet rows = statement.executeQuery()) {
            List<String> keys = new ArrayList<>();
            while (rows.next()) {
                keys.add(rows.getString(1));
            }
            return keys;
        }
    }

    private List<UUID> stepIdsOf(UUID caseId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM test_step WHERE case_id = CAST(? AS uuid) ORDER BY position")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
                return ids;
            }
        }
    }

    /** An edit made IN TCMS, written straight to the row — the endpoint's own headers are not the subject. */
    private void rename(UUID caseId, String title) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE test_case SET title = ? WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, title);
            statement.setString(2, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void setLifecycle(UUID caseId, String status) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE test_case SET lifecycle_status = ? WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, status);
            statement.setString(2, caseId.toString());
            statement.executeUpdate();
        }
    }

    private void humanCondition(UUID id, String type, String title) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO condition
                         (id, company_id, project_id, title, type, body, created_at, updated_at)
                     VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                             CAST(? AS jsonb), now(), now())
                     ON CONFLICT (id) DO NOTHING
                     """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, SHOP.toString());
            statement.setString(4, title);
            statement.setString(5, type);
            statement.setString(6, "[\"" + title + "\"]");
            statement.executeUpdate();
        }
    }

    /**
     * Attaches a condition the way a person does — at the END of its kind's list.
     *
     * <p>{@code imported_key} is null on these rows, which is the whole of the ownership rule: the
     * import may replace the {@code PRE} attachments whose condition has one and may never touch
     * these.
     */
    private void attachByHand(UUID caseId, UUID conditionId, String kind) throws SQLException {
        try (Connection connection = appConnection(ACME);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO case_condition (id, company_id, case_id, condition_id, kind, position)
                     SELECT gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?,
                            coalesce(max(position) + 1, 0)
                       FROM case_condition WHERE case_id = CAST(? AS uuid) AND kind = ?
                     """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, conditionId.toString());
            statement.setString(4, kind);
            statement.setString(5, caseId.toString());
            statement.setString(6, kind);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ seeding ----

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

        UUID id = UUID.nameUUIDFromBytes(("feature-import:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    /**
     * A PROJECT-scope role holding exactly these keys, written with raw JDBC.
     *
     * <p>There is no role in the catalogue that holds {@code case:create} and not
     * {@code library:manage} — the roles spec §A3 grants the two together to every role that has
     * either — so the per-row {@code library:manage} refusal has no seeded persona to drive it. The
     * shape is {@code ReportEndpointTest}'s: a fixture inserting a row, with no entity route, so
     * nothing in {@code src/main} gains the ability to write a seventh role.
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
                statement.setString(3, "Test fixture — see FeatureImportTest.");
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

    /**
     * Removes the fixture role, and it is not housekeeping.
     *
     * <p>{@code SeedDataTest} asserts, instance-wide, that {@code SELECT name FROM role WHERE NOT
     * built_in} is <strong>empty</strong> and that the assignable roles are exactly the built-in
     * set. Leaving {@link #NO_LIBRARY_ROLE} behind therefore reddens a class that has nothing to do
     * with importing, <strong>in the full suite only</strong>, at a position that moves — §19's
     * fixture-collision defect through a mechanism no UUID prefix protects against.
     *
     * <p>{@code @AfterAll} rather than {@code @AfterEach} because the row is seeded once and read by
     * every method, and the assignment goes first: {@code role_assignment.role_id} has no
     * {@code ON DELETE CASCADE} where {@code role_permission} does. This is the half of
     * {@code ReportEndpointTest}'s shape that D15 cited and the first cut of this class omitted.
     */
    @AfterAll
    static void dropTheFixtureRole() throws SQLException {
        try (Connection acme = appConnection(ACME);
             PreparedStatement assignments = acme.prepareStatement(
                     "DELETE FROM role_assignment WHERE role_id = CAST(? AS uuid)")) {
            assignments.setString(1, NO_LIBRARY_ROLE.toString());
            assignments.executeUpdate();
        }
        try (Connection any = appConnection();
             PreparedStatement role = any.prepareStatement(
                     "DELETE FROM role WHERE id = CAST(? AS uuid)")) {
            role.setString(1, NO_LIBRARY_ROLE.toString());
            role.executeUpdate();
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, SHOP);
            seedProjectDefaults(connection, ACME, PAY);
            suite(connection, PAYMENTS_SUITE, SHOP, "TS-1", 1, "Payments", "p");
            suite(connection, TWIN_A, SHOP, "TS-2", 2, "Twins", "ta");
            suite(connection, TWIN_B, SHOP, "TS-3", 3, "Twins", "tb");
            suite(connection, TARGET_SUITE, SHOP, "TS-4", 4, "Everything", "e");
            suite(connection, PAY_SUITE, PAY, "TS-5", 5, "Platform", "pl");

            /*
              Past the five just seeded. `resetMutableRows` puts EVERY counter back to 1, and these
              rows are written with their keys spelled out rather than allocated — so without this
              the first suite the import creates asks for `TS-1`, which is sitting right there, and
              the whole APPLY dies on `uq_suite_key`. The cases dodge it by living at TC-900/901;
              the suites cannot, because two of them are the `Twins` pair whose ORDER matters.
              `SuiteWriteEndpointTest` moves the same counter for the same reason.
            */
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE key_sequence SET next_value = 6 WHERE entity_type = 'SUITE'")) {
                statement.executeUpdate();
            }
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    private void suite(
            Connection connection, UUID id, UUID projectId, String key, long keySeq, String name,
            String path) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED', NULL)
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

    /**
     * The two rows no test writes and every test needs.
     *
     * <p>{@code TC-900} is filed in the Payments suite with a key none of the fixture files names —
     * the orphan. {@code TC-901} sits in a SIBLING project holding the key {@code sibling.feature}
     * wants, which is only reachable because {@code test_case_automation_key_idx} is unique per
     * company rather than per project. Their key sequences sit far above the counter so they cannot
     * collide with an allocated one.
     */
    private void seedExistingRows() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            existingCase(connection, ORPHAN_CASE, SHOP, PAYMENTS_SUITE, "TC-900", 900,
                    "Retired runner", "Payments#Retired long ago");
            existingCase(connection, SIBLING_CASE, PAY, PAY_SUITE, "TC-901", 901,
                    "Owns the key", "Sibling#Taken");
        }
    }

    private void existingCase(
            Connection connection, UUID id, UUID projectId, UUID suiteId, String key, long keySeq,
            String title, String automationKey) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, automation_key,
                     current_version_no, lock_version, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'AUTOMATED', ?, 1, 0, now())
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, automationKey);
            statement.executeUpdate();
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
