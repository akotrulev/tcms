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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET} and {@code POST /projects/{projectId}/shared-steps} — the reusable step library.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>{@code usageCount} served from the stored column.</strong> The column exists, is
 *       {@code NOT NULL DEFAULT 0}, and nothing maintains it — so a mapping that reads it answers
 *       0 for every entry and the picker's ranking silently becomes alphabetical. The fixture seeds
 *       {@code usage_count} to values that <em>contradict</em> the real references for exactly this
 *       reason: reading the column and deriving the count give different answers here, so the two
 *       implementations cannot both pass.</li>
 *   <li><strong>Counting step rows instead of distinct cases.</strong> A case referencing the same
 *       entry twice would inflate the number, and the inflation looks like popularity.</li>
 *   <li><strong>Counting through soft-deleted cases.</strong> Cases are soft-deleted because
 *       executions reference them, so a count that ignores {@code deleted_at} ranks by history
 *       rather than by current practice, and never goes down.</li>
 *   <li><strong>An entry nothing references dropped from the list.</strong> A {@code GROUP BY}
 *       cannot emit a zero row, so an implementation that joins the counts in rather than
 *       defaulting them loses precisely the never-used entries — which is every entry a freshly
 *       seeded library has.</li>
 *   <li><strong>{@code library:manage} not actually gating the write.</strong> It had zero
 *       consumers in Java before this slice, so nothing had ever proved it was reachable — §62's
 *       shape with {@code case:approve}.</li>
 * </ol>
 *
 * <p>Company, project and user ids are unique to this class ({@code ab…}). Sharing them with
 * another class is how handoff §19's defect happened, five times: green alone, red in the full
 * suite.
 */
@AutoConfigureMockMvc
@DisplayName("Shared step library — the read, the write, and the count that is derived")
class SharedStepEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ab100000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("ab100000-0000-4000-8000-00000000000b");

    /** Granted to ENGINEER and VIEWER. Carries the library and the cases that reference it. */
    private static final UUID ACME_BANKING = UUID.fromString("ab200000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to ENGINEER. Its entries must never appear in BANKING's library. */
    private static final UUID ACME_MOBILE = UUID.fromString("ab200000-0000-4000-8000-000000000002");
    /** The other client's project, with an identically titled entry. */
    private static final UUID UMBRA_BANKING = UUID.fromString("ab200000-0000-4000-8000-000000000003");
    /** Granted to ADMIN through their COMPANY-scope role, deliberately left with no entries. */
    private static final UUID ACME_EMPTY = UUID.fromString("ab200000-0000-4000-8000-000000000004");
    /**
     * Where the successful creates land, and the reason it is a fourth project.
     *
     * <p>{@code @BeforeEach} re-seeds and never cleans up — the Testcontainers database is shared
     * across the whole run — so an entry created by one test method is still there for the next.
     * Writing into BANKING made its exhaustive read assertions depend on JUnit's method order,
     * which is the kind of green that turns red when an unrelated test is added. Refusals still go
     * to BANKING: a refused write leaves nothing behind, which is precisely what they assert.
     */
    private static final UUID ACME_WRITES = UUID.fromString("ab200000-0000-4000-8000-000000000005");

    /** Test Engineer — holds `library:manage`, the lowest role that does. */
    private static final UUID ENGINEER = UUID.fromString("ab300000-0000-4000-8000-000000000001");
    /** Viewer on BANKING — reads the library and may not write to it. */
    private static final UUID VIEWER = UUID.fromString("ab300000-0000-4000-8000-000000000002");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("ab300000-0000-4000-8000-000000000003");
    /** Company Admin in Acme. */
    private static final UUID ADMIN = UUID.fromString("ab300000-0000-4000-8000-000000000004");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("ab300000-0000-4000-8000-000000000005");

    private static final UUID SUITE = UUID.fromString("ab400000-0000-4000-8000-000000000001");

    /** Referenced by three live cases — one of them twice. Distinct-case count: 3. */
    private static final UUID SUBMIT_REFUND = UUID.fromString("ab500000-0000-4000-8000-000000000001");
    /** Referenced by one live case and one SOFT-DELETED case. Distinct-case count: 1. */
    private static final UUID VERIFY_AUDIT = UUID.fromString("ab500000-0000-4000-8000-000000000002");
    /** Referenced by nothing. Must still appear, at 0. */
    private static final UUID NEVER_USED = UUID.fromString("ab500000-0000-4000-8000-000000000003");
    /** Retired. Must not appear at all. */
    private static final UUID RETIRED = UUID.fromString("ab500000-0000-4000-8000-000000000004");
    /** The `%` metacharacter, and the near-miss that makes escaping observable. */
    private static final UUID FULL_REFUND = UUID.fromString("ab500000-0000-4000-8000-000000000005");
    private static final UUID THOUSAND = UUID.fromString("ab500000-0000-4000-8000-000000000006");
    /** The sibling project's entry — the one a case in BANKING must be refused. */
    private static final UUID MOBILE_ENTRY = UUID.fromString("ab500000-0000-4000-8000-000000000011");

    private static final UUID CASE_ONE = UUID.fromString("ab600000-0000-4000-8000-000000000001");
    private static final UUID CASE_TWO = UUID.fromString("ab600000-0000-4000-8000-000000000002");
    private static final UUID CASE_THREE = UUID.fromString("ab600000-0000-4000-8000-000000000003");
    /** Soft-deleted, and references VERIFY_AUDIT. Must not be counted. */
    private static final UUID CASE_DELETED = UUID.fromString("ab600000-0000-4000-8000-000000000004");

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
            company(ACME, "shst-acme", "Acme", now);
            company(UMBRA, "shst-umbra", "Umbra", now);

            activeUser(ENGINEER, "engineer@shst-acme.test", now);
            activeUser(VIEWER, "viewer@shst-acme.test", now);
            activeUser(NO_GRANT, "nogrant@shst-acme.test", now);
            activeUser(ADMIN, "admin@shst-acme.test", now);
            activeUser(OUTSIDER, "engineer@shst-umbra.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "ABOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "ABMOB", "Mobile App", now);
            seedProject(ACME_EMPTY, ACME, "ABNEW", "Just Provisioned", now);
            seedProject(ACME_WRITES, ACME, "ABWRT", "Library Writes", now);

            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("shst-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("shst-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
            grantOnProject("shst-eng-w", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_WRITES, now);
            grantOnCompany("shst-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "AUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("shst-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedLibraryAndCases();
    }

    // =====================================================================
    // usageCount — the decision this slice turns on
    // =====================================================================

    @Nested
    @DisplayName("usageCount is derived from the references, never read from the column")
    class DerivedUsage {

        @Test
        @DisplayName("the served count is the reference count, not the stored usage_count")
        void theStoredColumnIsIgnored() throws Exception {
            // THE assertion of this class. The fixture stores 777 on this row and three live cases
            // reference it, so reading the column and counting the references disagree by
            // construction: an implementation that maps usage_count answers 777 here.
            assertThat(byTitle(libraryAs(ENGINEER, ACME, ACME_BANKING), "Submit the refund form")
                    .get("usageCount").asInt())
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a case referencing the same entry twice counts once")
        void distinctCasesNotStepRows() throws Exception {
            // CASE_ONE carries two steps pointing at SUBMIT_REFUND. Counting rows answers 4 where
            // counting cases answers 3, and 4 reads as "one more team uses this" rather than as an
            // artefact of how one case happens to be written.
            assertThat(byTitle(libraryAs(ENGINEER, ACME, ACME_BANKING), "Submit the refund form")
                    .get("usageCount").asInt())
                    .as("three cases reference it, one of them from two steps")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("a soft-deleted case stops counting")
        void deletedCasesDoNotCount() throws Exception {
            // VERIFY_AUDIT is referenced by one live case and one deleted one. Without the
            // deleted_at predicate this is 2 — a number that can never go down, so the library
            // would rank by everything ever written rather than by what is in use.
            assertThat(byTitle(libraryAs(ENGINEER, ACME, ACME_BANKING), "Verify the audit entry")
                    .get("usageCount").asInt())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an entry nothing references is present at 0, not missing")
        void neverUsedEntriesSurvive() throws Exception {
            // A GROUP BY over the referencing table cannot emit a row for something nothing
            // references. Joining the counts in rather than defaulting them drops exactly the
            // entries a freshly seeded library consists of — including every entry the POST below
            // creates.
            List<JsonNode> library = libraryAs(ENGINEER, ACME, ACME_BANKING);

            assertThat(titlesOf(library)).contains("Open the settings drawer");
            assertThat(byTitle(library, "Open the settings drawer").get("usageCount").asInt())
                    .isZero();
        }

        @Test
        @DisplayName("a sibling project's cases do not count toward this project's entry")
        void usageDoesNotCrossProjects() throws Exception {
            // MOBILE_ENTRY lives in ACME_MOBILE and is referenced by nothing there, while BANKING's
            // identically titled entry is referenced three times. If the count query lost its join
            // to the entry id the two would blend.
            assertThat(byTitle(libraryAs(ADMIN, ACME, ACME_MOBILE), "Submit the refund form")
                    .get("usageCount").asInt())
                    .isZero();
        }

        @Test
        @DisplayName("most-used first — and the fixture makes that disagree with the alphabet")
        void orderedByUsage() throws Exception {
            // "Submit the refund form" (3) sorts AFTER "Open the settings drawer" (0) and
            // "Verify the audit entry" (1) alphabetically, so a comparator that lost its usage
            // term would return these in a different order rather than the same one — §25's
            // tie-breaker-survivor lesson, applied to the fixture rather than to the assertion.
            assertThat(titlesOf(libraryAs(ENGINEER, ACME, ACME_BANKING, "q", "e")))
                    .containsExactly(
                            "Submit the refund form",
                            "Verify the audit entry",
                            "A 100% refund is available",
                            "Open the settings drawer",
                            "Refund 1000 EUR in full");
        }
    }

    // =====================================================================
    // The library read
    // =====================================================================

    @Nested
    @DisplayName("The library — live entries only")
    class Library {

        @Test
        @DisplayName("every live entry comes back, and the retired one does not")
        void liveEntriesOnly() throws Exception {
            assertThat(titlesOf(libraryAs(ENGINEER, ACME, ACME_BANKING)))
                    .containsExactlyInAnyOrder(
                            "Submit the refund form",
                            "Verify the audit entry",
                            "Open the settings drawer",
                            "A 100% refund is available",
                            "Refund 1000 EUR in full")
                    .doesNotContain("Sign in to the legacy console");
        }

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode entry =
                    byTitle(libraryAs(ENGINEER, ACME, ACME_BANKING), "Submit the refund form");

            assertThat(List.of("id", "title", "body", "usageCount"))
                    .allSatisfy(field -> assertThat(entry.has(field))
                            .as("SharedStep.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(entry.get("id").asText()).isEqualTo(SUBMIT_REFUND.toString());
        }

        @Test
        @DisplayName("body is the ordered list of actions, not a paragraph")
        void bodyIsAnOrderedList() throws Exception {
            // The order is the content — "enter the amount" before "confirm" is the procedure.
            assertThat(stringsIn(byTitle(
                    libraryAs(ENGINEER, ACME, ACME_BANKING), "Submit the refund form").get("body")))
                    .containsExactly("Enter the refund amount", "Select a reason", "Confirm");
        }

        @Test
        @DisplayName("a project with no entries answers [] and 200, not 404")
        void anEmptyLibraryIsNotAMissingProject() throws Exception {
            // The state of every freshly provisioned project.
            assertThat(statusOf(ADMIN, ACME, ACME_EMPTY)).isEqualTo(200);
            assertThat(libraryAs(ADMIN, ACME, ACME_EMPTY)).isEmpty();
        }

        @Test
        @DisplayName("companyId and projectId never appear")
        void tenancyFieldsAreNeverExposed() throws Exception {
            for (JsonNode entry : libraryAs(ENGINEER, ACME, ACME_BANKING)) {
                assertThat(entry.has("companyId")).isFalse();
                assertThat(entry.has("projectId")).isFalse();
            }
        }

        @Test
        @DisplayName("a literal % is searched for, not treated as a wildcard")
        void percentIsEscaped() throws Exception {
            // Unescaped, "100%" becomes "contains 100", which widens the search instead of
            // narrowing it. "Refund 1000 EUR in full" exists so the two readings differ; without
            // it the wrong query returns the right row and the assertion proves nothing.
            assertThat(titlesOf(libraryAs(ENGINEER, ACME, ACME_BANKING, "q", "100%")))
                    .containsExactly("A 100% refund is available");
        }

        @Test
        @DisplayName("a blank q is no filter, not a search for nothing")
        void blankIsNotAFilter() throws Exception {
            assertThat(libraryAs(ENGINEER, ACME, ACME_BANKING, "q", "")).hasSize(5);
            assertThat(libraryAs(ENGINEER, ACME, ACME_BANKING, "q", "  ")).hasSize(5);
        }

        @Test
        @DisplayName("a query matching nothing is an empty 200, not a 404")
        void noMatchesIsStillTwoHundred() throws Exception {
            assertThat(statusOf(ENGINEER, ACME, ACME_BANKING, "q", "zzzz")).isEqualTo(200);
            assertThat(libraryAs(ENGINEER, ACME, ACME_BANKING, "q", "zzzz")).isEmpty();
        }
    }

    // =====================================================================
    // The write
    // =====================================================================

    @Nested
    @DisplayName("Adding an entry — library:manage")
    class Create {

        @Test
        @DisplayName("a Test Engineer creates one, and it comes back at usageCount 0")
        void engineerCreates() throws Exception {
            // library:manage's first consumer in Java. It has been seeded by V3 and granted since
            // M0 with nothing ever asking for it — so before this, "Test Engineer maintains the
            // shared library" was true in the catalogue and unreachable in the product.
            var response = post(ENGINEER, ACME, ACME_WRITES, """
                    {"title": "Approve the payment", "body": ["Open the queue", "Approve"]}
                    """);

            assertThat(response.getStatus()).isEqualTo(201);

            JsonNode created = json.readTree(response.getContentAsString());
            assertThat(created.get("title").asText()).isEqualTo("Approve the payment");
            assertThat(stringsIn(created.get("body")))
                    .containsExactly("Open the queue", "Approve");
            assertThat(created.get("usageCount").asInt())
                    .as("nothing can reference an entry created in this transaction")
                    .isZero();
            assertThat(created.get("id").asText()).isNotBlank();
        }

        @Test
        @DisplayName("the created entry is in the library on the next read")
        void createdEntriesAreReadable() throws Exception {
            post(ENGINEER, ACME, ACME_WRITES, """
                    {"title": "Reconcile the ledger", "body": ["Open the ledger"]}
                    """);

            // The round trip is the point: a create that answers 201 from its own in-memory object
            // and never lands is the failure a response-only assertion cannot see.
            assertThat(titlesOf(libraryAs(ENGINEER, ACME, ACME_WRITES)))
                    .contains("Reconcile the ledger");
        }

        @Test
        @DisplayName("a Viewer is refused — they hold case:read and not library:manage")
        void viewerIsRefused() throws Exception {
            // The Viewer can READ this library, so the refusal is the permission and not the gate.
            assertThat(statusOf(VIEWER, ACME, ACME_BANKING))
                    .as("the same caller reads it successfully, so 403 below is about the write")
                    .isEqualTo(200);
            assertThat(post(VIEWER, ACME, ACME_BANKING, """
                    {"title": "Nope", "body": ["Nope"]}
                    """).getStatus())
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("a member with no grant gets 404 on the write, not 403")
        void noGrantIsNotFound() throws Exception {
            // 403 would confirm the project id names something real — the oracle §6t refuses.
            assertThat(post(NO_GRANT, ACME, ACME_BANKING, """
                    {"title": "Nope", "body": ["Nope"]}
                    """).getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("another client's project answers 404, identically")
        void crossCompanyIsNotFound() throws Exception {
            assertThat(post(ENGINEER, ACME, UMBRA_BANKING, """
                    {"title": "Nope", "body": ["Nope"]}
                    """).getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a blank title is a field error naming title")
        void titleIsRequired() throws Exception {
            assertThat(problemFields(post(ENGINEER, ACME, ACME_BANKING, """
                    {"title": "   ", "body": ["Something"]}
                    """)))
                    .containsKey("title");
        }

        @Test
        @DisplayName("a body of only blank lines is refused, naming body")
        void anAllBlankBodyIsRefused() throws Exception {
            // Dropping blanks silently is right for the trailing row the editor appends, and wrong
            // for a form nobody filled in: it would store an empty block, and an empty library
            // entry is a picker row that inserts nothing.
            assertThat(problemFields(post(ENGINEER, ACME, ACME_BANKING, """
                    {"title": "Has a title", "body": ["  ", ""]}
                    """)))
                    .containsKey("body");
        }

        @Test
        @DisplayName("an absent body is refused rather than stored as an empty block")
        void bodyIsRequired() throws Exception {
            assertThat(problemFields(post(ENGINEER, ACME, ACME_BANKING, """
                    {"title": "Has a title"}
                    """)))
                    .containsKey("body");
        }

        @Test
        @DisplayName("blank lines around real ones are dropped, not stored")
        void blankLinesAreDropped() throws Exception {
            // The editor appends an empty row on "Add action", so a trailing blank arrives on
            // routine input. Storing it puts an empty line in every case referencing the entry,
            // in a list whose positions are the whole structure.
            var response = post(ENGINEER, ACME, ACME_WRITES, """
                    {"title": "Trimmed", "body": ["  ", "Do this", "", "Then this", "   "]}
                    """);

            assertThat(stringsIn(json.readTree(response.getContentAsString()).get("body")))
                    .containsExactly("Do this", "Then this");
        }

        @Test
        @DisplayName("an over-long title is refused with the limit in the message")
        void titleIsCapped() throws Exception {
            String tooLong = "x".repeat(SharedStepEdit.MAX_TITLE + 1);

            assertThat(problemFields(post(ENGINEER, ACME, ACME_BANKING,
                    "{\"title\": \"" + tooLong + "\", \"body\": [\"Something\"]}")))
                    .containsKey("title");
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + ACME_BANKING + "/shared-steps")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"x\", \"body\": [\"y\"]}"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // Isolation and the read gate
    // =====================================================================

    @Nested
    @DisplayName("Both gates apply, and the libraries do not mix")
    class Gates {

        @Test
        @DisplayName("a sibling project's entries do not appear, though the titles collide")
        void librariesDoNotCrossProjects() throws Exception {
            // ACME_MOBILE owns an identically titled entry in the SAME company, so the company
            // filter admits both and only the project predicate separates them. Asserting on the
            // title would pass either way; the id is what distinguishes them.
            List<String> mobile = idsOf(libraryAs(ADMIN, ACME, ACME_MOBILE));

            assertThat(titlesOf(libraryAs(ADMIN, ACME, ACME_MOBILE)))
                    .as("the sibling really is populated — otherwise the exclusion is vacuous")
                    .containsExactly("Submit the refund form");
            assertThat(idsOf(libraryAs(ENGINEER, ACME, ACME_BANKING)))
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(mobile);
        }

        @Test
        @DisplayName("the other client's identically titled entry is a different row")
        void librariesDoNotCrossCompanies() throws Exception {
            List<JsonNode> umbra = libraryAs(OUTSIDER, UMBRA, UMBRA_BANKING);

            assertThat(titlesOf(umbra))
                    .as("the fixture is only hostile if the other side really does collide")
                    .containsExactly("Submit the refund form");
            assertThat(idsOf(libraryAs(ENGINEER, ACME, ACME_BANKING)))
                    .doesNotContainAnyElementsOf(idsOf(umbra));
        }

        @Test
        @DisplayName("a member with no grant gets 404, not the client's procedures")
        void membershipConveysNoLibrary() throws Exception {
            // The forbidden rows are in THIS caller's own company, so no cross-company assertion
            // can reach them. A step library describes how the client's system is operated.
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING)).isEqualTo(404);
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent are indistinguishable, bodies included")
        void theThreeCasesAnswerIdentically() throws Exception {
            UUID absent = UUID.randomUUID();

            assertThat(List.of(
                    statusOf(ENGINEER, ACME, ACME_MOBILE),
                    statusOf(ENGINEER, ACME, UMBRA_BANKING),
                    statusOf(ENGINEER, ACME, absent)))
                    .as("told apart, the response becomes a probe for another client's tenancy")
                    .containsExactly(404, 404, 404);

            assertThat(bodyOf(ENGINEER, ACME, UMBRA_BANKING)).isEmpty();
            assertThat(bodyOf(ENGINEER, ACME, absent)).isEmpty();
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/shared-steps"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private List<JsonNode> libraryAs(
            UUID userId, UUID companyId, UUID projectId, String... params) throws Exception {

        String body = bodyOf(userId, companyId, projectId, params);
        JsonNode array = json.readTree(body);
        assertThat(array.isArray()).as("the endpoint must answer a JSON array: %s", body).isTrue();
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private String bodyOf(UUID userId, UUID companyId, UUID projectId, String... params)
            throws Exception {

        return mvc.perform(request(userId, companyId, projectId, params)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId, String... params)
            throws Exception {

        return mvc.perform(request(userId, companyId, projectId, params))
                .andReturn().getResponse().getStatus();
    }

    /**
     * Binds each parameter as a <em>value</em> rather than appending it to the URL.
     *
     * <p>{@code MockMvc} takes the string it is handed as a URI template and does not
     * percent-decode the query string, so {@code ?q=100%25} would arrive as six literal
     * characters — and the escaping test would fail for a reason unrelated to escaping. The long
     * version of this is on {@code ConditionLibraryEndpointTest#request}.
     */
    private MockHttpServletRequestBuilder request(
            UUID userId, UUID companyId, UUID projectId, String... params) {

        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
                .get("/api/v1/projects/" + projectId + "/shared-steps")
                .header("Authorization", "Bearer " + tokenFor(userId, companyId));

        for (int i = 0; i < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private org.springframework.mock.web.MockHttpServletResponse post(
            UUID userId, UUID companyId, UUID projectId, String body) throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/shared-steps")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    /** The {@code errors} map of an RFC 7807 validation problem, keyed by field name. */
    private java.util.Map<String, JsonNode> problemFields(
            org.springframework.mock.web.MockHttpServletResponse response) throws Exception {

        assertThat(response.getStatus())
                .as("expected a validation refusal: %s", response.getContentAsString())
                .isEqualTo(400);

        JsonNode errors = json.readTree(response.getContentAsString()).get("errors");
        assertThat(errors).as("a 400 must name its fields, or the form has nowhere to land the "
                + "message").isNotNull();

        java.util.Map<String, JsonNode> fields = new java.util.LinkedHashMap<>();
        errors.fieldNames().forEachRemaining(name -> fields.put(name, errors.get(name)));
        return fields;
    }

    private static JsonNode byTitle(List<JsonNode> entries, String title) {
        return entries.stream()
                .filter(node -> node.get("title").asText().equals(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no shared step titled '" + title + "' — the response carried "
                                + titlesOf(entries)));
    }

    private static List<String> idsOf(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.get("id").asText()).toList();
    }

    private static List<String> titlesOf(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.get("title").asText()).toList();
    }

    private static List<String> stringsIn(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
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
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    private void grantOnCompany(
            String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("shared-step-endpoint:" + marker).getBytes());
    }

    /**
     * The libraries and the cases that reference them, written through the ordinary app role.
     *
     * <p>The app role rather than the superuser on purpose: it is the same policed path production
     * reads through, so a fixture that could not be written under RLS is one that lies about what
     * the endpoint will see.
     *
     * <p><strong>{@code usage_count} is seeded to values that contradict the real references</strong>
     * — 777 on an entry three cases use, 5 on one nothing uses. That is the whole mutation guard for
     * this slice: with the column seeded to the derived answer, mapping it and deriving it would
     * both pass, and the test would prove nothing about which one shipped.
     */
    private void seedLibraryAndCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            sharedStep(connection, SUBMIT_REFUND, ACME_BANKING, "Submit the refund form",
                    "[\"Enter the refund amount\", \"Select a reason\", \"Confirm\"]", 777, false);
            sharedStep(connection, VERIFY_AUDIT, ACME_BANKING, "Verify the audit entry",
                    "[\"Open the audit log\"]", 0, false);
            sharedStep(connection, NEVER_USED, ACME_BANKING, "Open the settings drawer",
                    "[\"Click the gear\"]", 5, false);
            sharedStep(connection, RETIRED, ACME_BANKING, "Sign in to the legacy console",
                    "[\"Open the console\"]", 3, true);
            sharedStep(connection, FULL_REFUND, ACME_BANKING, "A 100% refund is available",
                    "[\"Seed a settled payment\"]", 0, false);
            // The near-miss that makes the % escaping assertion able to fail: without it an
            // unescaped `%` still matches only the "100" row and the search is right by luck.
            sharedStep(connection, THOUSAND, ACME_BANKING, "Refund 1000 EUR in full",
                    "[\"Open the refund form\"]", 0, false);

            // The sibling project, same company, same title — only project_id separates them.
            sharedStep(connection, MOBILE_ENTRY, ACME_MOBILE, "Submit the refund form",
                    "[\"Open the app\"]", 999, false);

            suite(connection);

            // CASE_ONE references SUBMIT_REFUND TWICE — the distinct-case assertion's whole basis.
            testCase(connection, CASE_ONE, "ABOLB-1", 1, false);
            step(connection, uuid("ab700000-0000-4000-8000-000000000001"), CASE_ONE, 0,
                    "Submit the refund form", SUBMIT_REFUND);
            step(connection, uuid("ab700000-0000-4000-8000-000000000002"), CASE_ONE, 1,
                    "Check the total", null);
            step(connection, uuid("ab700000-0000-4000-8000-000000000003"), CASE_ONE, 2,
                    "Submit the refund form again", SUBMIT_REFUND);

            testCase(connection, CASE_TWO, "ABOLB-2", 2, false);
            step(connection, uuid("ab700000-0000-4000-8000-000000000004"), CASE_TWO, 0,
                    "Submit the refund form", SUBMIT_REFUND);
            step(connection, uuid("ab700000-0000-4000-8000-000000000005"), CASE_TWO, 1,
                    "Verify the audit entry", VERIFY_AUDIT);

            testCase(connection, CASE_THREE, "ABOLB-3", 3, false);
            step(connection, uuid("ab700000-0000-4000-8000-000000000006"), CASE_THREE, 0,
                    "Submit the refund form", SUBMIT_REFUND);

            // Soft-deleted, and it references VERIFY_AUDIT — so a count that ignores deleted_at
            // reports 2 where the live answer is 1.
            testCase(connection, CASE_DELETED, "ABOLB-4", 4, true);
            step(connection, uuid("ab700000-0000-4000-8000-000000000007"), CASE_DELETED, 0,
                    "Verify the audit entry", VERIFY_AUDIT);
        }
        try (Connection connection = appConnection(UMBRA)) {
            sharedStep(connection, uuid("ab500000-0000-4000-8000-000000000021"), UMBRA_BANKING,
                    "Submit the refund form", "[\"Open the portal\"]", 888, false);
        }
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }

    /**
     * One library entry. Nullable parameters bound through an explicit {@code CAST}.
     *
     * <p>{@code setObject(i, null)} leaves pgjdbc with no type to infer and fails with "could not
     * determine data type of parameter" — a fixture-only failure that reads like a schema problem.
     */
    private void sharedStep(
            Connection connection, UUID id, UUID projectId, String title, String body,
            int usageCount, boolean retired) throws SQLException {

        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;
        try (var statement = connection.prepareStatement("""
                INSERT INTO shared_step
                    (id, company_id, project_id, title, body, usage_count, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb), ?,
                        CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, title);
            statement.setString(5, body);
            statement.setInt(6, usageCount);
            statement.setString(7, retired ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void suite(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, SUITE.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_BANKING.toString());
            statement.setString(4, "ABOLB-S1");
            statement.setLong(5, 1);
            statement.setString(6, "Refunds");
            statement.setInt(7, 0);
            statement.executeUpdate();
        }
    }

    private void testCase(Connection connection, UUID id, String key, long keySeq, boolean deleted)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, current_version_no,
                     lock_version, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'DRAFT', 'MANUAL', 1, 0,
                        CAST(? AS timestamptz), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_BANKING.toString());
            statement.setString(4, SUITE.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, "Refund case " + keySeq);
            statement.setString(8, "2026-01-01T00:00:00Z");
            statement.setString(9, deleted ? "2026-01-02T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void step(
            Connection connection, UUID id, UUID caseId, int position, String action,
            UUID sharedStepId) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_step
                    (id, company_id, case_id, position, action, shared_step_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setInt(4, position);
            statement.setString(5, action);
            statement.setString(6, sharedStepId == null ? null : sharedStepId.toString());
            statement.executeUpdate();
        }
    }
}
