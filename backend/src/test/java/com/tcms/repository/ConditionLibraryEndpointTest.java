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
 * {@code GET /projects/{projectId}/conditions} — the reusable condition library.
 *
 * <h2>The failures this class exists to catch, none of them loud</h2>
 *
 * <ol>
 *   <li><strong>A {@code BOTH} entry excluded from {@code type=PRE}.</strong> The exact-match
 *       reading returns 200 with a plausible list, and the one row it silently withholds is the
 *       one an author marked reusable in <em>both</em> positions — so the way to make a condition
 *       unreachable in every picker would be to declare it usable in all of them. Nothing fails
 *       and the picker just looks short.</li>
 *   <li><strong>A retired entry served as current.</strong> Retirement is a soft delete because
 *       attachments survive it. Offering a withdrawn precondition in the picker is how it gets
 *       attached to a new case after being withdrawn from the old ones.</li>
 *   <li><strong>An endpoint gated on the company only.</strong> Every cross-company test still
 *       passes — the rows genuinely are in the caller's company — and a member granted nothing
 *       reads the client's setup procedures. Persona 3, asserted rather than assumed from the
 *       shared gate.</li>
 * </ol>
 *
 * <h2>The fixture is deliberately hostile</h2>
 *
 * <p>Two companies whose libraries carry <strong>identical titles</strong>, so only ids can prove
 * the scoping. Two projects in one company, so a sibling library exists to leak in from. One entry
 * of each type including {@code BOTH}; one retired; and — for each of the two {@code LIKE}
 * metacharacters — a pair of titles that <em>only</em> an escaped search tells apart:
 * "A 100% refund is available" beside "Refund 1000 EUR in full", and "Seed a user_account row"
 * beside "Seed a user account row". A single row carrying the metacharacter is not enough, and the
 * first draft of this fixture had exactly that: the unescaped query returned the same right answer
 * and the mutation survived.
 *
 * <p>Company and user ids are unique to this class. Sharing them with another test class is how
 * handoff §19's first defect happened — green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Condition library endpoint — behind the shared project gate")
class ConditionLibraryEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("c0000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("c0000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD. Carries the whole library. */
    private static final UUID ACME_BANKING = UUID.fromString("c0001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to LEAD. Its conditions must never appear in BANKING's library. */
    private static final UUID ACME_MOBILE = UUID.fromString("c0001000-0000-4000-8000-000000000002");
    /** The other client's project, with identically titled conditions. */
    private static final UUID UMBRA_BANKING = UUID.fromString("c0002000-0000-4000-8000-000000000001");
    /** Granted to ADMIN through their COMPANY-scope role, deliberately left with no conditions. */
    private static final UUID ACME_EMPTY = UUID.fromString("c0001000-0000-4000-8000-000000000003");

    private static final UUID LEAD = UUID.fromString("c0003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("c0003000-0000-4000-8000-000000000002");
    /** Company Admin in Acme — reaches every project through a grant with no project id. */
    private static final UUID ADMIN = UUID.fromString("c0003000-0000-4000-8000-000000000003");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("c0003000-0000-4000-8000-000000000004");

    private static final UUID LOG_IN = UUID.fromString("c0004000-0000-4000-8000-000000000001");
    private static final UUID NAVIGATE = UUID.fromString("c0004000-0000-4000-8000-000000000002");
    private static final UUID LOG_OUT = UUID.fromString("c0004000-0000-4000-8000-000000000003");
    private static final UUID RESET_DATA = UUID.fromString("c0004000-0000-4000-8000-000000000004");
    private static final UUID RETIRED = UUID.fromString("c0004000-0000-4000-8000-000000000005");
    private static final UUID FULL_REFUND = UUID.fromString("c0004000-0000-4000-8000-000000000006");
    private static final UUID SNAKE_CASE = UUID.fromString("c0004000-0000-4000-8000-000000000007");
    /** Contains "100" but not "100%" — the row an unescaped {@code %} would wrongly sweep in. */
    private static final UUID THOUSAND = UUID.fromString("c0004000-0000-4000-8000-000000000008");
    /** "user account" with a space — the row an unescaped {@code _} would wrongly sweep in. */
    private static final UUID SPACED = UUID.fromString("c0004000-0000-4000-8000-000000000009");

    /** Holds the cases the attachments hang off, and attaches NAVIGATE itself. */
    private static final UUID BANKING_SUITE =
            UUID.fromString("c0005000-0000-4000-8000-000000000001");

    /**
     * Attaches LOG_IN <strong>twice</strong> — as a pre- and as a postcondition, which is legal and
     * is what makes {@code COUNT(DISTINCT case_id)} distinguishable from {@code COUNT(*)}.
     */
    private static final UUID CASE_TWICE = UUID.fromString("c0006000-0000-4000-8000-000000000001");
    /** Attaches LOG_IN once and LOG_OUT once. */
    private static final UUID CASE_ONCE = UUID.fromString("c0006000-0000-4000-8000-000000000002");
    /** Soft-deleted, and attaches LOG_IN — so a missing {@code deleted_at} filter counts 3, not 2. */
    private static final UUID CASE_RETIRED = UUID.fromString("c0006000-0000-4000-8000-000000000003");

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
    private ConditionRepository conditions;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "cond-acme", "Acme", now);
            company(UMBRA, "cond-umbra", "Umbra", now);

            activeUser(LEAD, "lead@cond-acme.test", now);
            activeUser(NO_GRANT, "nogrant@cond-acme.test", now);
            activeUser(ADMIN, "admin@cond-acme.test", now);
            activeUser(OUTSIDER, "engineer@cond-umbra.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "CAMOB", "Mobile App", now);
            seedProject(ACME_EMPTY, ACME, "CANEW", "Just Provisioned", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("cond-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnCompany("cond-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("cond-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedLibraries();
    }

    // =====================================================================
    // The library itself
    // =====================================================================

    @Nested
    @DisplayName("The library — live entries, most-used first")
    class Library {

        @Test
        @DisplayName("every live entry in the project comes back, and only those")
        void liveEntriesOnly() throws Exception {
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING)))
                    .containsExactlyInAnyOrder(
                            "Log in as standard user",
                            "Navigate to Billing",
                            "Log out",
                            "Reset test payment data",
                            "A 100% refund is available",
                            "Seed a user_account row",
                            "Refund 1000 EUR in full",
                            "Seed a user account row")
                    .doesNotContain("Log in as the old admin");
        }

        @Test
        @DisplayName("a retired entry is gone from the response, not marked in it")
        void retiredEntriesAreExcluded() throws Exception {
            // Retiring is a soft delete because attachments to it survive. Serving it here would
            // offer a withdrawn precondition for attachment to a NEW case, which is the one place
            // the withdrawal was supposed to bite.
            assertThat(idsOf(libraryAs(LEAD, ACME, ACME_BANKING)))
                    .doesNotContain(RETIRED.toString());
        }

        @Test
        @DisplayName("most-used first — and the STORED count would order these two the other way")
        void orderedByUsageThenTitle() throws Exception {
            // The fixture's stored usage_count ranks Log out (412) above Log in (203); the real
            // attachments rank Log in (2 live cases) above Log out (1). The orders are opposites,
            // so this assertion fails loudly if anything goes back to serving the column — which
            // is the regression worth catching, since the column reads 0 in production and the
            // resulting order would look merely alphabetical rather than wrong.
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "q", "log")))
                    .containsExactly("Log in as standard user", "Log out");
        }

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode entry = byTitle(
                    libraryAs(LEAD, ACME, ACME_BANKING), "Log in as standard user");

            assertThat(List.of(
                            "id", "title", "type", "body", "usageCount", "suiteUsageCount",
                            "lockVersion"))
                    .allSatisfy(field -> assertThat(entry.has(field))
                            .as("Condition.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(entry.get("id").asText()).isEqualTo(LOG_IN.toString());
            assertThat(entry.get("type").asText()).isEqualTo("PRE");
        }

        @Test
        @DisplayName("usageCount counts distinct live CASES — not attachment rows, not the column")
        void usageCountIsDistinctLiveCases() throws Exception {
            JsonNode entry = byTitle(
                    libraryAs(LEAD, ACME, ACME_BANKING), "Log in as standard user");

            // Four attachment rows exist against LOG_IN: two on CASE_TWICE (pre and post), one on
            // CASE_ONCE, one on the soft-deleted CASE_RETIRED. Each wrong reading lands on a
            // different number, so this single assertion separates all four:
            //   203 → the stored column is being served again
            //     4 → neither DISTINCT nor the deleted filter
            //     3 → COUNT(*) rather than COUNT(DISTINCT case_id)
            //     3 → DISTINCT but counting the retired case
            //     2 → correct
            assertThat(entry.get("usageCount").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("a suite attachment is a SECOND number and never part of the case count")
        void suiteUsageIsCountedSeparately() throws Exception {
            // NAVIGATE is attached by one suite and by no case. Folding the two counts together —
            // the tempting simplification, and the one the contract argues against on
            // Condition.suiteUsageCount — makes this row report one case, which is a figure nothing
            // in the database supports and which the client would render as an impact warning.
            JsonNode entry = byTitle(libraryAs(LEAD, ACME, ACME_BANKING), "Navigate to Billing");

            assertThat(entry.get("usageCount").asInt())
                    .as("no case attaches this entry")
                    .isZero();
            assertThat(entry.get("suiteUsageCount").asInt())
                    .as("one suite does, and every case beneath it inherits")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an entry nothing attaches reports zero on both counts, not an absent field")
        void anUnattachedEntryIsZeroAndNotAbsent() throws Exception {
            // A GROUP BY cannot emit a row for something nothing references, so both counts come
            // from the caller's default rather than from the query. This is the state of every
            // entry the moment it is created, which makes it the common case rather than an edge.
            JsonNode entry = byTitle(libraryAs(LEAD, ACME, ACME_BANKING), "Reset test payment data");

            assertThat(entry.get("usageCount").asInt()).isZero();
            assertThat(entry.get("suiteUsageCount").asInt()).isZero();
        }

        @Test
        @DisplayName("body is the ordered list of actions, not a paragraph")
        void bodyIsAnOrderedList() throws Exception {
            // The order is the content: "open the application" before "enter credentials" is the
            // procedure. Read out of jsonb through Hibernate's JSON format mapper.
            JsonNode entry = byTitle(
                    libraryAs(LEAD, ACME, ACME_BANKING), "Log in as standard user");

            assertThat(stringsIn(entry.get("body")))
                    .containsExactly("Open the application", "Enter valid credentials");
        }

        @Test
        @DisplayName("an entry with an empty body serializes as [], never as null")
        void anEmptyBodyIsStillAnArray() throws Exception {
            // The contract marks `body` required, so the SPA cannot null-check it.
            JsonNode entry = byTitle(libraryAs(LEAD, ACME, ACME_BANKING), "Log out");

            assertThat(entry.get("body").isArray()).isTrue();
            assertThat(entry.get("body")).isEmpty();
        }

        @Test
        @DisplayName("a project with no conditions answers [] and 200, not 404")
        void anEmptyLibraryIsNotAMissingProject() throws Exception {
            // The state of EVERY freshly provisioned project: tcms_seed_project_defaults seeds the
            // statuses and a configuration, deliberately not a client's setup procedures.
            assertThat(statusOf(ADMIN, ACME, ACME_EMPTY)).isEqualTo(200);
            assertThat(libraryAs(ADMIN, ACME, ACME_EMPTY)).isEmpty();
        }

        @Test
        @DisplayName("companyId and projectId never appear")
        void tenancyFieldsAreNeverExposed() throws Exception {
            // companyId is the field a client has no business seeing (ERD §0.1 point 2), and
            // projectId is the one a future author would try to SEND in order to pick a scope.
            for (JsonNode entry : libraryAs(LEAD, ACME, ACME_BANKING)) {
                assertThat(entry.has("companyId")).isFalse();
                assertThat(entry.has("projectId")).isFalse();
            }
        }
    }

    // =====================================================================
    // type — where BOTH is folded in
    // =====================================================================

    @Nested
    @DisplayName("type — what may be attached in this position")
    class TypeFilter {

        @Test
        @DisplayName("type=PRE includes BOTH, because BOTH means 'usable here too'")
        void preIncludesBoth() throws Exception {
            // THE assertion of this class. Exact matching answers 200 with a plausible list and
            // silently withholds the entry an author declared reusable in either position — so
            // declaring a condition usable everywhere would be the way to hide it everywhere.
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "type", "PRE")))
                    .containsExactlyInAnyOrder(
                            "Log in as standard user",
                            "Navigate to Billing",
                            "A 100% refund is available",
                            "Seed a user_account row",
                            "Refund 1000 EUR in full",
                            "Seed a user account row",
                            "Reset test payment data");
        }

        @Test
        @DisplayName("type=POST includes BOTH and excludes every PRE")
        void postIncludesBoth() throws Exception {
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "type", "POST")))
                    .containsExactlyInAnyOrder("Log out", "Reset test payment data");
        }

        @Test
        @DisplayName("the BOTH entry is the same row in both lists, not two entries")
        void bothIsOneRow() throws Exception {
            // If it were duplicated per role the picker would offer it twice and the SPA would
            // render two identical items with the same React key.
            assertThat(idsOf(libraryAs(LEAD, ACME, ACME_BANKING, "type", "PRE")))
                    .contains(RESET_DATA.toString());
            assertThat(idsOf(libraryAs(LEAD, ACME, ACME_BANKING, "type", "POST")))
                    .contains(RESET_DATA.toString());
            assertThat(byTitle(libraryAs(LEAD, ACME, ACME_BANKING, "type", "PRE"),
                    "Reset test payment data").get("type").asText())
                    .as("the response reports what the row IS, never what the filter asked for")
                    .isEqualTo("BOTH");
        }

        @Test
        @DisplayName("type=BOTH asks for the entries declared usable either way, exactly")
        void bothAsksForBoth() throws Exception {
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "type", "BOTH")))
                    .containsExactly("Reset test payment data");
        }

        @Test
        @DisplayName("an omitted type is no filter, not an empty one")
        void omittedTypeMatchesEverything() throws Exception {
            // An empty collection bound into SQL `IN ()` is a syntax error, so "no opinion" has to
            // resolve to all three values rather than to none.
            assertThat(libraryAs(LEAD, ACME, ACME_BANKING)).hasSize(8);
        }

        @Test
        @DisplayName("an unparseable type is a 400, never a silently dropped filter")
        void anUnknownTypeIsRejected() throws Exception {
            // A dropped filter here offers postconditions in a precondition picker, and every one
            // of them looks like a plausible answer. Same failure as the case list's enums,
            // arriving through the same door.
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, "type", "NOPE")).isEqualTo(400);
        }
    }

    // =====================================================================
    // q — the typeahead, and the metacharacters
    // =====================================================================

    @Nested
    @DisplayName("q — the typeahead over the title")
    class Search {

        @Test
        @DisplayName("matches a substring of the title, case-insensitively")
        void matchesCaseInsensitively() throws Exception {
            // The string arrives one keystroke at a time; refusing "billing" for "Billing" is
            // refusing the same word in different clothes.
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "q", "BILLING")))
                    .containsExactly("Navigate to Billing");
        }

        @Test
        @DisplayName("a blank q is no filter, not a search for nothing")
        void blankIsNotAFilter() throws Exception {
            // ?q= arrives whenever the SPA's search box is cleared.
            assertThat(libraryAs(LEAD, ACME, ACME_BANKING, "q", "")).hasSize(8);
            assertThat(libraryAs(LEAD, ACME, ACME_BANKING, "q", "  ")).hasSize(8);
        }

        @Test
        @DisplayName("a literal % is searched for, not treated as a wildcard")
        void percentIsEscaped() throws Exception {
            // Unescaped, `%` is a wildcard — "100%" becomes "contains 100", which quietly widens
            // the search instead of narrowing it. "Refund 1000 EUR in full" is in the library
            // precisely so the two readings return different answers; without it the wrong query
            // returns the right row and the assertion proves nothing. Same check as the case
            // list's q=100%.
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "q", "100%")))
                    .containsExactly("A 100% refund is available");
        }

        @Test
        @DisplayName("a literal _ is searched for, not treated as a single-character wildcard")
        void underscoreIsEscaped() throws Exception {
            // The quieter of the two metacharacters: `_` matches ONE character, so "user_account"
            // unescaped also matches "user account". The fixture carries both spellings for that
            // reason — with only the underscored row present, escaping and not escaping return
            // the same single row and the test is decoration.
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "q", "user_account")))
                    .containsExactly("Seed a user_account row");
        }

        @Test
        @DisplayName("q and type are ANDed, never ORed")
        void filtersCombine() throws Exception {
            assertThat(titlesOf(libraryAs(LEAD, ACME, ACME_BANKING, "q", "log", "type", "POST")))
                    .containsExactly("Log out");
        }

        @Test
        @DisplayName("a query matching nothing is an empty 200, not a 404")
        void noMatchesIsStillTwoHundred() throws Exception {
            // A user who cannot load the dialog cannot clear the query that emptied it.
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, "q", "zzzz")).isEqualTo(200);
            assertThat(libraryAs(LEAD, ACME, ACME_BANKING, "q", "zzzz")).isEmpty();
        }

        @Test
        @DisplayName("the search does not reach the body — recorded, not accidental")
        void searchIsTitleOnly() throws Exception {
            // "Open the account menu" is in Navigate to Billing's BODY and in no title. HQL cannot
            // express `@@` without a custom function and a native query would drop the Hibernate
            // company filter, so this is a LIKE over the title, matching the SPA's mock.
            //
            // WHEN THIS TEST FAILS: the search_vector path has landed and the corpus now includes
            // the body — which is the intended §6h behaviour. Update the expectation; do not
            // reinstate the narrower query.
            assertThat(libraryAs(LEAD, ACME, ACME_BANKING, "q", "account menu")).isEmpty();
        }
    }

    // =====================================================================
    // Isolation and the gate
    // =====================================================================

    @Nested
    @DisplayName("Both gates apply, and the libraries do not mix")
    class Gates {

        @Test
        @DisplayName("a sibling project's conditions do not appear, though the titles collide")
        void librariesDoNotCrossProjects() throws Exception {
            // ACME_MOBILE owns an identically titled entry in the SAME company, so the company
            // filter admits both and only the project predicate separates them. Asserting on the
            // title would pass with either row; the id is what distinguishes them.
            List<String> mobile = idsOf(libraryAs(ADMIN, ACME, ACME_MOBILE));

            assertThat(titlesOf(libraryAs(ADMIN, ACME, ACME_MOBILE)))
                    .as("the sibling really is populated — otherwise 'does not contain' is vacuous")
                    .containsExactly("Log in as standard user");
            assertThat(idsOf(libraryAs(LEAD, ACME, ACME_BANKING)))
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(mobile);
        }

        @Test
        @DisplayName("the other client's identically titled conditions are different rows")
        void librariesDoNotCrossCompanies() throws Exception {
            List<JsonNode> umbra = libraryAs(OUTSIDER, UMBRA, UMBRA_BANKING);

            assertThat(titlesOf(umbra))
                    .as("the fixture is only hostile if the other side really does collide")
                    .containsExactly("Log in as standard user");
            assertThat(idsOf(libraryAs(LEAD, ACME, ACME_BANKING)))
                    .doesNotContainAnyElementsOf(idsOf(umbra));
        }

        @Test
        @DisplayName("a member with no grant gets 404, not the client's setup procedures")
        void membershipConveysNoLibrary() throws Exception {
            // The forbidden rows are in THIS caller's own company, so no cross-company assertion
            // can reach them. A condition library describes how the client's system is operated.
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING)).isEqualTo(404);
        }

        @Test
        @DisplayName("the same caller who is refused DOES reach a project they hold a grant on")
        void theRefusalIsTheGateAndNotABrokenRoute() throws Exception {
            assertThat(statusOf(LEAD, ACME, ACME_BANKING)).isEqualTo(200);
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent are indistinguishable, bodies included")
        void theThreeCasesAnswerIdentically() throws Exception {
            UUID absent = UUID.randomUUID();

            assertThat(List.of(
                    statusOf(LEAD, ACME, ACME_MOBILE),
                    statusOf(LEAD, ACME, UMBRA_BANKING),
                    statusOf(LEAD, ACME, absent)))
                    .as("told apart, the response becomes a probe for what exists in another "
                            + "client's tenancy (§6t)")
                    .containsExactly(404, 404, 404);

            assertThat(bodyOf(LEAD, ACME, UMBRA_BANKING))
                    .as("a 404 that explains itself is still an oracle")
                    .isEmpty();
            assertThat(bodyOf(LEAD, ACME, absent)).isEmpty();
        }

        @Test
        @DisplayName("the gate is applied before the filters, so a query cannot soften it")
        void filtersDoNotBypassTheGate() throws Exception {
            // A 404 that becomes an empty 200 once a filter is added would tell the caller the
            // project exists — the oracle above, reached through a different parameter.
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING, "q", "log", "type", "PRE")).isEqualTo(404);
        }

        @Test
        @DisplayName("a Company Admin's COMPANY-scope grant reaches every project in that company")
        void companyScopeCoversEveryProject() throws Exception {
            assertThat(statusOf(ADMIN, ACME, ACME_BANKING)).isEqualTo(200);
            assertThat(statusOf(ADMIN, ACME, ACME_MOBILE)).isEqualTo(200);
            assertThat(statusOf(ADMIN, ACME, UMBRA_BANKING))
                    .as("every project in the COMPANY the grant covers — and that company is "
                            + "the whole point")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/conditions"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // The predicate itself, where the endpoint cannot see it
    // =====================================================================

    @Nested
    @DisplayName("The query, asserted where the response cannot distinguish it")
    class Predicate {

        @Test
        @DisplayName("the project bound is the predicate's, not RLS's — two projects, one company")
        void theProjectPredicateIsRealAndNotTheCompanyFilter() {
            // Asserted at the repository because through the endpoint the two are hard to tell
            // apart: both ACME projects pass the company filter and pass RLS, so only project_id
            // separates them, and the gate would have refused the request either way.
            //
            // The context is opened AROUND the transaction, not inside it: the transaction manager
            // reads CompanyContext in doBegin to set app.current_company, so a context opened
            // after the transaction started arrives too late and RLS answers zero rows.
            // Fail-closed and silent — the shape of handoff §15's missing @Transactional.
            List<String> banking;
            List<String> mobile;
            try (var ignored = CompanyContext.open(ACME)) {
                banking = tx.execute(status -> titles(ACME_BANKING));
                mobile = tx.execute(status -> titles(ACME_MOBILE));
            }

            assertThat(banking).hasSize(8);
            assertThat(mobile)
                    .as("the sibling holds a row with the SAME title, so a dropped project "
                            + "predicate would have returned nine above rather than nothing here")
                    .containsExactly("Log in as standard user");
        }

        private List<String> titles(UUID projectId) {
            return conditions
                    .findLibrary(projectId, List.of(ConditionType.values()), "%").stream()
                    .map(Condition::getTitle)
                    .toList();
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
     * Builds the request with each parameter bound as a <em>value</em>, never appended to the URL.
     *
     * <p>This is not a style choice and the first draft got it wrong: {@code MockMvc} takes the
     * string it is handed as a URI template and does <strong>not</strong> percent-decode the query
     * string, so {@code ?q=100%25} arrives at the controller as the six characters
     * {@code 100%25} — which then matched nothing, and the escaping test failed for a reason that
     * had nothing to do with escaping. Binding through {@code .param} is also what a real client
     * produces after the container has decoded the request, so the value under test is the value
     * the endpoint will actually see.
     *
     * @param params alternating name and value; a value is bound verbatim, blanks included
     */
    private MockHttpServletRequestBuilder request(
            UUID userId, UUID companyId, UUID projectId, String... params) {

        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders
                .get("/api/v1/projects/" + projectId + "/conditions")
                .header("Authorization", "Bearer " + tokenFor(userId, companyId));

        for (int i = 0; i < params.length; i += 2) {
            builder = builder.param(params[i], params[i + 1]);
        }
        return builder;
    }

    private static JsonNode byTitle(List<JsonNode> entries, String title) {
        return entries.stream()
                .filter(node -> node.get("title").asText().equals(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no condition titled '" + title + "' — the response carried "
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

    private void grantOnCompany(String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("condition-endpoint:" + marker).getBytes());
    }

    /**
     * The three libraries, written as raw SQL through the ordinary app role.
     *
     * <p>There is no condition-authoring endpoint, so a fixture is the only way to get rows here
     * at all. The <em>app</em> role rather than the superuser on purpose: it is the same policed
     * path production reads through, so a fixture that could not be written under RLS is a fixture
     * that is lying about what the endpoint will see.
     *
     * <p><strong>The stored {@code usage_count} values are no longer served, and they stay here on
     * purpose</strong> — §95 unmapped the column and derives both counts from the referencing
     * tables ({@link #seedAttachments}). Keeping the hand-set numbers, and keeping them ranking the
     * two "log" rows in the <em>opposite</em> order to the real attachments, turns a silent
     * regression into a failing assertion: anything that goes back to reading the column reverses
     * the list rather than merely making it look alphabetical.
     *
     * <p>They were originally seeded at odds with the alphabet for the same family of reason, and
     * the first draft was not: "Log in" at 412 and "Log out" at 203 order the pair the way the title
     * already does, so the mutation dropping {@code usageCount} from the ORDER BY <em>survived</em>.
     * Exactly §25's tie-breaker survivor one level up.
     */
    private void seedLibraries() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            condition(connection, LOG_IN, ACME_BANKING, "Log in as standard user", "PRE",
                    "[\"Open the application\", \"Enter valid credentials\"]", 203, false);
            condition(connection, NAVIGATE, ACME_BANKING, "Navigate to Billing", "PRE",
                    "[\"Open the account menu\", \"Select Billing\"]", 156, false);
            // An empty body: legal (the column is NOT NULL, the array may be empty) and it must
            // still serialize as [].
            condition(connection, LOG_OUT, ACME_BANKING, "Log out", "POST", "[]", 412, false);
            condition(connection, RESET_DATA, ACME_BANKING, "Reset test payment data", "BOTH",
                    "[\"Void any pending authorisations\"]", 71, false);
            condition(connection, RETIRED, ACME_BANKING, "Log in as the old admin", "PRE",
                    "[\"Open the legacy console\"]", 9, true);
            // The two LIKE metacharacters, each in a title that must be findable literally.
            condition(connection, FULL_REFUND, ACME_BANKING, "A 100% refund is available", "PRE",
                    "[\"Seed a settled payment\"]", 12, false);
            condition(connection, SNAKE_CASE, ACME_BANKING, "Seed a user_account row", "PRE",
                    "[\"Insert the row\"]", 3, false);
            // The two near-misses that make the escaping assertions able to fail. Without them an
            // unescaped `%` still matches only the "100" row and an unescaped `_` still matches
            // only the underscored one, so both searches return the right answer by luck.
            condition(connection, THOUSAND, ACME_BANKING, "Refund 1000 EUR in full", "PRE",
                    "[\"Open the refund form\"]", 5, false);
            condition(connection, SPACED, ACME_BANKING, "Seed a user account row", "PRE",
                    "[\"Insert the row\"]", 2, false);

            // The sibling project, same company, same title — only project_id separates it.
            condition(connection, UUID.fromString("c0004000-0000-4000-8000-000000000011"),
                    ACME_MOBILE, "Log in as standard user", "PRE", "[\"Open the app\"]", 999, false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            condition(connection, UUID.fromString("c0004000-0000-4000-8000-000000000021"),
                    UMBRA_BANKING, "Log in as standard user", "PRE", "[\"Open the portal\"]",
                    888, false);
        }
        seedAttachments();
    }

    /**
     * The rows the two usage counts are derived from — §95.
     *
     * <h2>The stored counts and the real ones deliberately DISAGREE, and they disagree in order</h2>
     *
     * <p>Every {@code condition} above carries a hand-set {@code usage_count} that this fixture
     * leaves in place. It is no longer served — §95 unmapped the column, because it reads its
     * {@code DEFAULT 0} in production for ever and an impact warning computed from a permanent zero
     * is a confident wrong answer rather than a cautious one. Leaving the stored values here, and
     * making them rank the two "log" rows in the <strong>opposite</strong> order to the real
     * attachments, is what makes the derivation provable: stored says Log out (412) then Log in
     * (203), the attachments say Log in (2 cases) then Log out (1). A regression that re-maps the
     * column does not merely weaken the ordering assertion — it reverses it.
     *
     * <h2>Three properties, each with a row that can fail it</h2>
     *
     * <ul>
     *   <li><strong>Distinct cases, not attachment rows.</strong> {@code CASE_TWICE} attaches
     *       LOG_IN as both a pre- and a postcondition, so {@code COUNT(*)} answers 3 where
     *       {@code COUNT(DISTINCT case_id)} answers 2. Both are plausible on sight, which is why
     *       the fixture makes them differ rather than trusting a reading of the query.</li>
     *   <li><strong>Soft-deleted cases do not count.</strong> {@code CASE_RETIRED} attaches LOG_IN
     *       too, so a missing {@code deleted_at} filter answers 3 — the library would rank by
     *       history rather than by current practice.</li>
     *   <li><strong>Suites are a second number.</strong> NAVIGATE is attached by a suite and by no
     *       case at all, so it is the row that fails if the two counts are folded together: its
     *       honest answer is 0 cases and 1 suite, and any blend reports a case count nothing
     *       supports.</li>
     * </ul>
     */
    private void seedAttachments() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, BANKING_SUITE, ACME_BANKING, "CAOLB-S1", "Payments");

            testCase(connection, CASE_TWICE, ACME_BANKING, "CAOLB-1", 1, "Refund a settled payment",
                    false);
            testCase(connection, CASE_ONCE, ACME_BANKING, "CAOLB-2", 2, "Sign in and sign out",
                    false);
            testCase(connection, CASE_RETIRED, ACME_BANKING, "CAOLB-3", 3, "An abandoned case",
                    true);

            // LOG_IN: two distinct LIVE cases, three live attachment rows, four rows in total.
            caseCondition(connection, "c0007000-0000-4000-8000-000000000001", CASE_TWICE, LOG_IN,
                    "PRE", 0);
            caseCondition(connection, "c0007000-0000-4000-8000-000000000002", CASE_TWICE, LOG_IN,
                    "POST", 0);
            caseCondition(connection, "c0007000-0000-4000-8000-000000000003", CASE_ONCE, LOG_IN,
                    "PRE", 0);
            caseCondition(connection, "c0007000-0000-4000-8000-000000000004", CASE_RETIRED, LOG_IN,
                    "PRE", 0);

            // LOG_OUT: one case. Stored 412 — the higher number, and the lower real one.
            caseCondition(connection, "c0007000-0000-4000-8000-000000000005", CASE_ONCE, LOG_OUT,
                    "POST", 1);

            // NAVIGATE: a suite and no case.
            suiteCondition(connection, "c0008000-0000-4000-8000-000000000001", BANKING_SUITE,
                    NAVIGATE, "PRE", 0);
        }
    }

    /**
     * One library entry. Every nullable parameter is bound through an explicit {@code CAST}.
     *
     * <p>{@code setObject(i, null)} leaves pgjdbc with no type to infer and fails with "could not
     * determine data type of parameter", a fixture-only failure that reads like a schema problem.
     */
    private void condition(
            Connection connection, UUID id, UUID projectId, String title, String type, String body,
            int usageCount, boolean retired) throws SQLException {

        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;
        try (var statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, type, body, usage_count, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS jsonb), ?, CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, title);
            statement.setString(5, type);
            statement.setString(6, body);
            statement.setInt(7, usageCount);
            // A fixed instant rather than now(): nothing asserts the value, and a deterministic
            // fixture is one less thing that can differ between runs.
            statement.setString(8, retired ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /** One folder, only ever as somewhere for the fixture's cases to live. */
    private void suite(Connection connection, UUID id, UUID projectId, String key, String name)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, path, name)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, 1L);
            // The path is an ltree label, so it may carry only [A-Za-z0-9_] — a raw UUID with its
            // hyphens is rejected at insert with a syntax error that reads like a schema fault.
            statement.setString(6, id.toString().replace("-", ""));
            statement.setString(7, name);
            statement.executeUpdate();
        }
    }

    /** One case, live or soft-deleted — the two states the usage counts must tell apart. */
    private void testCase(
            Connection connection, UUID id, UUID projectId, String key, long keySeq, String title,
            boolean retired) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?,
                        CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, BANKING_SUITE.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, retired ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /**
     * One case→condition attachment.
     *
     * <p>{@code inline_body} stays null because {@code ck_case_condition_source} is an XOR: a row
     * carrying both a {@code condition_id} and an inline body is refused, and one carrying neither
     * is too. Only the reference form counts toward a library entry's usage, which is the whole
     * distinction the check constraint exists to keep.
     */
    private void caseCondition(
            Connection connection, String id, UUID caseId, UUID conditionId, String kind,
            int position) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_condition
                    (id, company_id, case_id, kind, position, condition_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id);
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setString(4, kind);
            statement.setInt(5, position);
            statement.setString(6, conditionId.toString());
            statement.executeUpdate();
        }
    }

    /** One suite→condition attachment — the second count, and never part of the first. */
    private void suiteCondition(
            Connection connection, String id, UUID suiteId, UUID conditionId, String kind,
            int position) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite_condition
                    (id, company_id, suite_id, kind, position, condition_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id);
            statement.setString(2, ACME.toString());
            statement.setString(3, suiteId.toString());
            statement.setString(4, kind);
            statement.setInt(5, position);
            statement.setString(6, conditionId.toString());
            statement.executeUpdate();
        }
    }
}
