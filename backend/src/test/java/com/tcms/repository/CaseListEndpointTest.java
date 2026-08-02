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
import java.net.URI;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{projectId}/cases} — the case list.
 *
 * <h2>The failures this class exists to catch, none of which throw</h2>
 *
 * <ol>
 *   <li><strong>Ordering by the text key.</strong> {@code TC-10} sorts before {@code TC-2}, the
 *       grid looks sorted, and the mistake is invisible until a company passes its tenth case —
 *       which is why the fixture has one.</li>
 *   <li><strong>Ordering an enum alphabetically.</strong> {@code priority} as text puts LOW before
 *       MEDIUM. Every row is in a plausible place and nothing fails.</li>
 *   <li><strong>A filter that is silently dropped.</strong> An ignored filter returns the project's
 *       entire case list under a heading claiming it is narrowed — worse than an error, because it
 *       is indistinguishable from a filter that matched everything.</li>
 *   <li><strong>A total that disagrees with the page.</strong> A count computed differently from
 *       the rows makes a last page that renders empty, and a facet chip that promises 12 and
 *       delivers 9.</li>
 *   <li><strong>An endpoint gated on the company only.</strong> Every cross-company test still
 *       passes — the rows genuinely are in the caller's company — while a member granted nothing
 *       receives every title, feature and owner in the client's repository. Persona 3.</li>
 * </ol>
 *
 * <h2>The fixture is deliberately hostile</h2>
 *
 * <p>Two companies whose cases carry <strong>the same display keys</strong>, so only ids prove the
 * scoping. Two projects in one company, so a sibling exists to leak in from. A soft-deleted case, a
 * live case inside a <em>soft-deleted suite</em>, an unowned case, and a case owned by someone who
 * is no longer a member — each is a branch that renders plausibly when it renders wrongly. A case
 * whose title contains a literal {@code %}, because an unescaped LIKE turns one keystroke into
 * "everything". And keys 1–6 plus 10, so text ordering and numeric ordering visibly disagree.
 *
 * <p>Company and user ids are unique to this class. Sharing them with another test class is how
 * handoff §19's first defect happened — green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Case list endpoint — filtered, sorted, paged and faceted behind the shared gate")
class CaseListEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("6a000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("6a000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD. Carries every case the assertions name. */
    private static final UUID ACME_BANKING = UUID.fromString("6a001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to LEAD. Its cases must never appear in BANKING's list. */
    private static final UUID ACME_MOBILE = UUID.fromString("6a001000-0000-4000-8000-000000000002");
    /** Granted to ADMIN through their COMPANY-scope role, deliberately left with no suites. */
    private static final UUID ACME_EMPTY = UUID.fromString("6a001000-0000-4000-8000-000000000003");
    /** The other client's project, with identically keyed cases. */
    private static final UUID UMBRA_BANKING = UUID.fromString("6a002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("6a003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("6a003000-0000-4000-8000-000000000002");
    /** Company Admin in Acme — reaches every project through a grant with no project id. */
    private static final UUID ADMIN = UUID.fromString("6a003000-0000-4000-8000-000000000003");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("6a003000-0000-4000-8000-000000000004");
    /**
     * An account that owns a case and holds <strong>no membership of Acme</strong> — offboarded,
     * or authored the case before being removed. Their name must still render, from the account
     * rather than from a membership that no longer exists.
     */
    private static final UUID FORMER = UUID.fromString("6a003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("6a004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("6a004000-0000-4000-8000-000000000002");
    private static final UUID PARTIAL = UUID.fromString("6a004000-0000-4000-8000-000000000003");
    private static final UUID CHECKOUT = UUID.fromString("6a004000-0000-4000-8000-000000000004");
    /** Soft-deleted, and holding a LIVE case. That case must leave the list with the folder. */
    private static final UUID RETIRED_SUITE = UUID.fromString("6a004000-0000-4000-8000-000000000005");

    /** Feature values in Acme banking: a parent, its child, and a retired sibling. */
    private static final UUID F_PAYMENTS = UUID.fromString("6a005000-0000-4000-8000-000000000001");
    private static final UUID F_REFUNDS = UUID.fromString("6a005000-0000-4000-8000-000000000002");
    private static final UUID F_RETIRED = UUID.fromString("6a005000-0000-4000-8000-000000000003");

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
            company(ACME, "cases-acme", "Acme", now);
            company(UMBRA, "cases-umbra", "Umbra", now);

            activeUser(LEAD, "lead@cases-acme.test", "Leonora Lead", now);
            activeUser(NO_GRANT, "nogrant@cases-acme.test", "N. Grant", now);
            activeUser(ADMIN, "admin@cases-acme.test", "A. Admin", now);
            activeUser(OUTSIDER, "engineer@cases-umbra.test", "O. Engineer", now);
            activeUser(FORMER, "former@cases-acme.test", "Fenwick Former", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "CAMOB", "Mobile App", now);
            seedProject(ACME_EMPTY, ACME, "CANEW", "Just Provisioned", now);

            member(ACME, LEAD, "L. Lead (Acme)", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("cases-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnCompany("cases-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("cases-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedVocabularies();
        seedTrees();
        seedCases();
        seedClassificationsAndTags();
    }

    // =====================================================================
    // The page
    // =====================================================================

    @Nested
    @DisplayName("The page — every row the project has, and nothing it does not")
    class Page {

        @Test
        @DisplayName("live cases in live suites come back, and only those")
        void liveCasesOnly() throws Exception {
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "")))
                    .containsExactlyInAnyOrder("TC-1", "TC-2", "TC-3", "TC-4", "TC-5", "TC-10");
        }

        @Test
        @DisplayName("a soft-deleted case is gone from the list, not marked in it")
        void deletedCasesAreExcluded() throws Exception {
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, ""))).doesNotContain("TC-6");
        }

        @Test
        @DisplayName("a LIVE case inside a soft-deleted suite leaves the list with its folder")
        void casesInDeletedSuitesAreExcluded() throws Exception {
            // TC-7 is not deleted. Its suite is. Soft-deleting a folder does not touch the cases
            // under it, so without the live-tree bound this row keeps appearing under a folder the
            // tree no longer shows — a Suite column naming a place the user cannot navigate to.
            // SuiteRepository's own comment states the intended semantics from the other side
            // ("its cases are already gone from the case list"); this is what makes that true.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, ""))).doesNotContain("TC-7");
        }

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode row = byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-1");

            assertThat(List.of("id", "key", "keySeq", "title", "suiteId", "suiteName", "priority",
                    "lifecycleStatus", "automationStatus", "stepFormat", "owner", "createdBy",
                    "features", "tags", "healthSignals", "updatedAt"))
                    .allSatisfy(field -> assertThat(row.has(field))
                            .as("TestCaseSummary.%s is required by openapi.yaml", field)
                            .isTrue());

            assertThat(row.get("suiteName").asText())
                    .as("resolved server-side, so the grid does not need the tree loaded")
                    .isEqualTo("Refunds");
            assertThat(row.get("keySeq").asLong()).isEqualTo(1);
            assertThat(row.get("stepFormat").asText()).isEqualTo("STRUCTURED");
            assertThat(row.get("lifecycleStatus").asText()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("healthSignals is [] for every case — derived, and nothing computes it yet")
        void healthIsEmptyAndPresent() throws Exception {
            // The honest answer rather than a stub: no signal HAS been computed, because
            // case_health is fed by execution and documentation and neither module exists. Never
            // null — the contract marks it required, so the SPA cannot null-check it.
            //
            // WHEN THIS TEST FAILS: something now writes case_health. That is the feature working,
            // and the fix is to assert the real signals rather than to delete the field.
            for (JsonNode row : itemsAs(LEAD, ACME, ACME_BANKING, "")) {
                assertThat(row.get("healthSignals").isArray()).isTrue();
                assertThat(row.get("healthSignals")).isEmpty();
            }
        }

        @Test
        @DisplayName("lastResult is absent rather than null — no execution has ever happened")
        void lastResultIsAbsent() throws Exception {
            // Not marked required by the contract, and the SPA renders a missing lastResult as
            // "Never run" — which is exactly true. A fabricated UNTESTED result would be the
            // response inventing an execution that never happened.
            assertThat(byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-1").has("lastResult"))
                    .isFalse();
        }

        @Test
        @DisplayName("companyId and projectId never appear")
        void tenancyFieldsAreNeverExposed() throws Exception {
            for (JsonNode row : itemsAs(LEAD, ACME, ACME_BANKING, "")) {
                assertThat(row.has("companyId")).isFalse();
                assertThat(row.has("projectId")).isFalse();
            }
        }

        @Test
        @DisplayName("the picker's caseCount agrees with this list's total")
        void theTwoScreensAgree() throws Exception {
            // FOUND BY RUNNING THE APP, not by a test. GET /projects reports a caseCount and this
            // endpoint reports a total; they are different queries on different screens, and they
            // disagreed by exactly the live case sitting in a soft-deleted suite — the picker said
            // 4 over a list showing 3. Both numbers were plausible, the build was green, and 306
            // tests passed.
            //
            // Asserted as an EQUALITY between the two endpoints rather than as two separate
            // expected numbers: expected numbers drift apart silently, and it is their agreement
            // that is the actual product requirement.
            long fromPicker = 0;
            for (JsonNode project : json.readTree(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects")
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andReturn().getResponse().getContentAsString())) {
                if (project.get("id").asText().equals(ACME_BANKING.toString())) {
                    fromPicker = project.get("caseCount").asLong();
                }
            }

            assertThat(fromPicker)
                    .as("the picker must not count TC-7, which is live inside a deleted folder "
                            + "and therefore unreachable from the list")
                    .isEqualTo(bodyAs(LEAD, ACME, ACME_BANKING, "").get("total").asLong())
                    .isEqualTo(6);
        }

        @Test
        @DisplayName("a project with no suites answers an empty page and 200, not 404")
        void anEmptyProjectIsNotAMissingOne() throws Exception {
            // The state of EVERY freshly provisioned project. Collapsing it into the gate's 404
            // would make the biggest screen unreachable for exactly the project a new client
            // starts with — and the facets must still be present, because an absent array is a
            // different response shape for the case a consumer is least likely to have exercised.
            JsonNode body = bodyAs(ADMIN, ACME, ACME_EMPTY, "");

            assertThat(statusOf(ADMIN, ACME, ACME_EMPTY, "")).isEqualTo(200);
            assertThat(body.get("items")).isEmpty();
            assertThat(body.get("total").asLong()).isZero();
            assertThat(body.get("facets")).hasSize(4);
        }
    }

    // =====================================================================
    // Ordering and paging
    // =====================================================================

    @Nested
    @DisplayName("Ordering — by meaning, and stable enough to page over")
    class Ordering {

        @Test
        @DisplayName("the default order is numeric, so TC-10 comes after TC-2 and not before it")
        void keysOrderNumerically() throws Exception {
            // §3 lesson 6. Ordering on the text key gives TC-1, TC-10, TC-2 … — sorted-looking and
            // wrong, and invisible until a company writes its tenth case. The fixture has one for
            // exactly this assertion.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "")))
                    .containsExactly("TC-1", "TC-2", "TC-3", "TC-4", "TC-5", "TC-10");
        }

        @Test
        @DisplayName("priority sorts by urgency, not alphabetically — LOW must come after MEDIUM")
        void enumsSortByDeclaredOrder() throws Exception {
            // The whole point of CaseSort.byDeclaredOrder. Sorted as text this reads CRITICAL,
            // HIGH, LOW, LOW, MEDIUM, MEDIUM: every value in a plausible place, nothing failing,
            // and the two least urgent cases at the top of the "most urgent first" column.
            assertThat(prioritiesOf(itemsAs(LEAD, ACME, ACME_BANKING, "&sort=priority,asc")))
                    .containsExactly("CRITICAL", "HIGH", "MEDIUM", "MEDIUM", "LOW", "LOW");
        }

        @Test
        @DisplayName("descending reverses the meaning, not the spelling")
        void enumsSortByDeclaredOrderDescending() throws Exception {
            assertThat(prioritiesOf(itemsAs(LEAD, ACME, ACME_BANKING, "&sort=priority,desc")))
                    .containsExactly("LOW", "LOW", "MEDIUM", "MEDIUM", "HIGH", "CRITICAL");
        }

        @Test
        @DisplayName("ties break on keySeq, so paging cannot drop or repeat a row")
        void tiesAreBrokenDeterministically() throws Exception {
            // Two LOW cases and two MEDIUM ones. Without a total order Postgres may return them in
            // either order per query, so a row can appear on page 1 and again on page 2 — or on
            // neither. The defect reads as "rows go missing" and only appears once the result set
            // outgrows a page.
            List<String> ascending = keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&sort=priority,asc"));

            assertThat(ascending.indexOf("TC-3"))
                    .as("TC-3 and TC-5 are both MEDIUM; keySeq puts TC-3 first, every time")
                    .isLessThan(ascending.indexOf("TC-5"));
            assertThat(ascending.indexOf("TC-2"))
                    .as("TC-2 and TC-10 are both LOW")
                    .isLessThan(ascending.indexOf("TC-10"));
        }

        @Test
        @DisplayName("suiteName sorts by the folder's name, through a subquery and no join")
        void sortingBySuiteName() throws Exception {
            // Checkout < Partial refunds < Refunds. A join would have multiplied nothing here, but
            // it would have required a mapped association — see TestCase's header for why there
            // isn't one.
            assertThat(suiteNamesOf(itemsAs(LEAD, ACME, ACME_BANKING, "&sort=suiteName,asc")))
                    .containsExactly("Checkout", "Partial refunds",
                            "Refunds", "Refunds", "Refunds", "Refunds");
        }

        @Test
        @DisplayName("an unsortable or unknown field falls back to keySeq rather than failing")
        void unknownSortFieldsFallBack() throws Exception {
            // `owner` is offered as sortable by the SPA's grid and cannot be served here — it
            // would need identity's table inside this module's ORDER BY. A 400 on a header click
            // is a dead end the user cannot act on; the mock falls back the same way.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&sort=owner,asc")))
                    .containsExactly("TC-1", "TC-2", "TC-3", "TC-4", "TC-5", "TC-10");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&sort=companyId,asc")))
                    .as("an attribute that exists on the entity is still not sortable — the "
                            + "allow-list is what stops user input naming a column")
                    .containsExactly("TC-1", "TC-2", "TC-3", "TC-4", "TC-5", "TC-10");
        }

        @Test
        @DisplayName("paging returns disjoint slices and a total that outlives the page")
        void pagingIsConsistent() throws Exception {
            JsonNode first = bodyAs(LEAD, ACME, ACME_BANKING, "&page=0&size=2");
            JsonNode second = bodyAs(LEAD, ACME, ACME_BANKING, "&page=1&size=2");

            assertThat(keysIn(first.get("items"))).containsExactly("TC-1", "TC-2");
            assertThat(keysIn(second.get("items"))).containsExactly("TC-3", "TC-4");
            assertThat(first.get("total").asLong())
                    .as("the total counts MATCHING cases, not returned ones — a count over the "
                            + "page would say 2 and the pager would offer exactly one page")
                    .isEqualTo(6);
            assertThat(first.get("page").asInt()).isZero();
            assertThat(first.get("size").asInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("size is clamped to the contract's maximum and echoed back honestly")
        void sizeIsClampedAndReported() throws Exception {
            // Clamped rather than refused, and reported rather than silently applied: a client
            // that asked for 1000 finds out it got 200 instead of concluding the project has 200
            // cases.
            assertThat(bodyAs(LEAD, ACME, ACME_BANKING, "&size=100000").get("size").asInt())
                    .isEqualTo(CaseQuery.MAX_SIZE);
            assertThat(bodyAs(LEAD, ACME, ACME_BANKING, "&page=-4").get("page").asInt()).isZero();
        }

        @Test
        @DisplayName("a page past the end is an empty page, not an error")
        void pagingPastTheEnd() throws Exception {
            JsonNode body = bodyAs(LEAD, ACME, ACME_BANKING, "&page=9&size=2");

            assertThat(body.get("items")).isEmpty();
            assertThat(body.get("total").asLong()).isEqualTo(6);
        }
    }

    // =====================================================================
    // Filters
    // =====================================================================

    @Nested
    @DisplayName("Filters — each one narrows, and none is silently dropped")
    class Filters {

        @Test
        @DisplayName("a case key short-circuits to an exact lookup, never a substring")
        void keyLookupIsExact() throws Exception {
            // Plan §6q. Searching TC-1 as text would also return TC-10 — and the exact match, of
            // which there is only ever one, is the row most likely to be buried.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&q=TC-1")))
                    .containsExactly("TC-1");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&q=tc_10")))
                    .as("case-insensitive, and the underscore spelling the SPA's mock accepts — "
                            + "a key arrives pasted from a ticket as often as typed")
                    .containsExactly("TC-10");
        }

        @Test
        @DisplayName("a key that cannot exist returns nothing rather than everything")
        void unmatchableKeysReturnNothing() throws Exception {
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING, "&q=TC-9999")).isEmpty();
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING,
                    "&q=TC-99999999999999999999999999"))
                    .as("larger than a bigint — a key that cannot name a row must return no rows, "
                            + "not raise")
                    .isEmpty();
        }

        @Test
        @DisplayName("free text searches title and description")
        void freeTextSearchesBothColumns() throws Exception {
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&q=refund")))
                    .as("TC-5's title says nothing about refunds; its DESCRIPTION does, and the "
                            + "contract asks for both")
                    .containsExactly("TC-1", "TC-2", "TC-3", "TC-4", "TC-5");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&q=REFUND")))
                    .as("case-insensitive")
                    .hasSize(5);
        }

        @Test
        @DisplayName("a literal % in the search box searches for a percent sign")
        void likeMetacharactersAreEscaped() throws Exception {
            // Unescaped, `100%` becomes the pattern %100%% and matches every row — the difference
            // between "one result" and "all results" on a single keystroke, which reads as the
            // search box being broken rather than as an escaping bug.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&q=100%25")))
                    .containsExactly("TC-10");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&q=%25")))
                    .as("a bare percent sign matches only the row that contains one")
                    .containsExactly("TC-10");
        }

        @Test
        @DisplayName("suiteId includes descendant suites, per the contract")
        void suiteFilterIncludesDescendants() throws Exception {
            // Payments holds no cases of its own; Refunds and Partial refunds beneath it do.
            // Without the subtree walk, picking a parent folder shows nothing and the tree reads
            // as broken.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&suiteId=" + PAYMENTS)))
                    .containsExactly("TC-1", "TC-2", "TC-3", "TC-4", "TC-10");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&suiteId=" + PARTIAL)))
                    .as("a leaf is its own subtree")
                    .containsExactly("TC-4");
        }

        @Test
        @DisplayName("a suiteId naming a deleted, foreign or absent folder returns nothing")
        void unreachableSuitesReturnNothing() throws Exception {
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING, "&suiteId=" + RETIRED_SUITE)).isEmpty();
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING, "&suiteId=" + UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("featureId includes descendant features, per plan §6q")
        void featureFilterIncludesDescendants() throws Exception {
            // TC-2 is classified on the parent, TC-1 and TC-3 on the child. Picking the parent
            // must return all three — cases are classified on leaves in practice, so a
            // non-recursive filter shows nothing and looks broken rather than precise.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&featureId=" + F_PAYMENTS)))
                    .containsExactly("TC-1", "TC-2", "TC-3");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&featureId=" + F_REFUNDS)))
                    .containsExactly("TC-1", "TC-3");
        }

        @Test
        @DisplayName("a case with three features appears once, not three times")
        void classificationDoesNotMultiplyRows() throws Exception {
            // The reason the predicate is an EXISTS and not a join. Joined, TC-1 would appear once
            // per matching classification row — in the page, in the total, and in every facet
            // count. The total is the assertion that would fail loudest.
            JsonNode body = bodyAs(LEAD, ACME, ACME_BANKING, "&featureId=" + F_PAYMENTS);

            assertThat(keysIn(body.get("items"))).doesNotHaveDuplicates();
            assertThat(body.get("total").asLong()).isEqualTo(3);
        }

        @Test
        @DisplayName("a retired feature value matches nothing rather than everything")
        void retiredValuesMatchNothing() throws Exception {
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING, "&featureId=" + F_RETIRED)).isEmpty();
        }

        @Test
        @DisplayName("a value from the wrong dimension matches nothing")
        void valuesAreLookedUpInTheirOwnDimension() throws Exception {
            // featureId naming a Feature value works; the same id passed as testTypeId must not.
            // The three parameters are separate because the dimensions are, and one that accepted
            // any value from any axis would make them interchangeable names for one filter.
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING, "&testTypeId=" + F_REFUNDS)).isEmpty();
        }

        @Test
        @DisplayName("the enum filters narrow, and several values within one filter are an OR")
        void enumFiltersOr() throws Exception {
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&lifecycleStatus=APPROVED")))
                    .containsExactly("TC-1", "TC-4");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING,
                    "&lifecycleStatus=APPROVED&lifecycleStatus=IN_REVIEW")))
                    .as("selecting a second chip must widen the result, never narrow it")
                    .containsExactly("TC-1", "TC-3", "TC-4");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&automationStatus=AUTOMATED")))
                    .containsExactly("TC-1");
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING, "&priority=LOW")))
                    .containsExactly("TC-2", "TC-10");
        }

        @Test
        @DisplayName("different filters are an AND")
        void differentFiltersAnd() throws Exception {
            // MANUAL alone gives TC-2, TC-4 and TC-5; the Refunds subtree alone gives TC-1,
            // TC-2, TC-3, TC-4 and TC-10. Only the intersection may come back — and note that
            // TC-4 is in Partial refunds, BENEATH Refunds, so the subtree rule is doing work here
            // rather than being incidental.
            assertThat(keysOf(itemsAs(LEAD, ACME, ACME_BANKING,
                    "&automationStatus=MANUAL&suiteId=" + REFUNDS)))
                    .containsExactly("TC-2", "TC-4");
        }

        @Test
        @DisplayName("an unparseable enum value is a 400, never a silently dropped filter")
        void badEnumValuesAreRefused() throws Exception {
            // The failure this prevents is not the 400 — it is the alternative. A dropped filter
            // returns the project's ENTIRE case list under a heading claiming it is narrowed,
            // which is indistinguishable from a filter that matched everything.
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, "&lifecycleStatus=NOT_A_STATUS"))
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a health filter matches nothing — no signal has been computed for any case")
        void healthFilterMatchesNothing() throws Exception {
            // Literally correct rather than a stub: case_health is empty because nothing writes
            // it, so no case raises any signal.
            //
            // WHEN THIS TEST FAILS: health computation has landed. Assert the real cases.
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING, "&healthSignal=NEVER_EXECUTED")).isEmpty();
            assertThat(bodyAs(LEAD, ACME, ACME_BANKING, "&healthSignal=ORPHANED")
                    .get("total").asLong()).isZero();
        }

        @Test
        @DisplayName("a release filter matches nothing — nothing has ever been executed")
        void releaseFilterMatchesNothing() throws Exception {
            // The parameter is defined over executions and the `execution` module does not exist,
            // so zero cases have been executed in any release and an empty result is true.
            //
            // The alternative is what makes this worth pinning: an IGNORED parameter returns the
            // entire project and labels it "executed in 4.3.0" — the response asserting something
            // the data never said, and worse than an error because it is indistinguishable from a
            // filter that found everything.
            //
            // WHEN THIS TEST FAILS: `execution` has landed and this filter now needs a real
            // predicate. Do not delete the disjunction without writing one.
            assertThat(itemsAs(LEAD, ACME, ACME_BANKING, "&releaseVersion=4.3.0")).isEmpty();
        }
    }

    // =====================================================================
    // Facets
    // =====================================================================

    @Nested
    @DisplayName("Facets — counts that agree with the rows beside them")
    class Facets {

        @Test
        @DisplayName("all four groups are present, in the order the filter bar renders them")
        void allFourGroupsArePresent() throws Exception {
            assertThat(facetKeysOf(bodyAs(LEAD, ACME, ACME_BANKING, "")))
                    .containsExactly("lifecycleStatus", "automationStatus", "priority",
                            "healthSignal");
        }

        @Test
        @DisplayName("counts match the unfiltered rows")
        void countsMatchTheRows() throws Exception {
            JsonNode body = bodyAs(LEAD, ACME, ACME_BANKING, "");

            assertThat(facetCount(body, "lifecycleStatus", "APPROVED")).isEqualTo(2);
            assertThat(facetCount(body, "lifecycleStatus", "DRAFT")).isEqualTo(2);
            assertThat(facetCount(body, "priority", "MEDIUM")).isEqualTo(2);
            assertThat(facetCount(body, "automationStatus", "AUTOMATED")).isEqualTo(1);
        }

        @Test
        @DisplayName("counts are computed over the FILTERED set, not the whole project")
        void countsFollowTheFilter() throws Exception {
            // A facet computed over the unfiltered project is the defect that makes a chip promise
            // 12 rows and deliver 9 — plausible on both sides, failing nothing.
            JsonNode body = bodyAs(LEAD, ACME, ACME_BANKING, "&suiteId=" + PARTIAL);

            assertThat(body.get("total").asLong()).isEqualTo(1);
            assertThat(facetCount(body, "priority", "HIGH")).isEqualTo(1);
            assertThat(facetCount(body, "priority", "CRITICAL"))
                    .as("CRITICAL exists in the project and not in this suite, so it must be "
                            + "absent — a chip offering 0 is a filter the user can select and "
                            + "get nothing from")
                    .isZero();
        }

        @Test
        @DisplayName("counts follow an ENUM filter too, not only a scope one")
        void countsFollowAnEnumFilter() throws Exception {
            // Distinct from the test above, and the distinction is what a mutation found. suiteId
            // is resolved into the scope before the predicate is built, so a facet query that
            // silently dropped the REQUEST's filters would still look scoped correctly and only
            // this assertion would notice.
            //
            // priority=LOW is TC-2 (DRAFT) and TC-10 (DEPRECATED). The lifecycle facet must
            // describe those two rows and no others.
            JsonNode body = bodyAs(LEAD, ACME, ACME_BANKING, "&priority=LOW");

            assertThat(body.get("total").asLong()).isEqualTo(2);
            assertThat(facetCount(body, "lifecycleStatus", "DRAFT")).isEqualTo(1);
            assertThat(facetCount(body, "lifecycleStatus", "DEPRECATED")).isEqualTo(1);
            assertThat(facetCount(body, "lifecycleStatus", "APPROVED"))
                    .as("two cases are APPROVED in this project and neither is LOW")
                    .isZero();
            assertThat(facetCount(body, "automationStatus", "AUTOMATED"))
                    .as("TC-1 is the only AUTOMATED case and it is CRITICAL")
                    .isZero();
        }

        @Test
        @DisplayName("the health facet is present and empty — nine zeros, rendered as no values")
        void healthFacetIsEmpty() throws Exception {
            JsonNode health = facet(bodyAs(LEAD, ACME, ACME_BANKING, ""), "healthSignal");

            assertThat(health.get("values").isArray()).isTrue();
            assertThat(health.get("values"))
                    .as("nothing writes case_health, so every tally is zero and every zero is "
                            + "dropped — the group stays so the filter bar has something to bind")
                    .isEmpty();
        }

        @Test
        @DisplayName("the facet sum equals the total, so the numbers cannot disagree")
        void facetsAndTotalAgree() throws Exception {
            // lifecycleStatus is NOT NULL and every case has exactly one, so its counts must sum
            // to the total. If the facet query and the page query ever drift apart, this is the
            // assertion that notices — and the drift is otherwise silent.
            JsonNode body = bodyAs(LEAD, ACME, ACME_BANKING, "");
            long summed = 0;
            for (JsonNode value : facet(body, "lifecycleStatus").get("values")) {
                summed += value.get("count").asLong();
            }

            assertThat(summed).isEqualTo(body.get("total").asLong());
        }
    }

    // =====================================================================
    // Owners
    // =====================================================================

    @Nested
    @DisplayName("Owners — named per company, and honestly absent when there is none")
    class Owners {

        @Test
        @DisplayName("the owner's name comes from the ACTIVE company's membership, not the account")
        void displayNameIsPerMembership() throws Exception {
            // Plan §6u. The account's canonical name is "Leonora Lead"; the Acme membership calls
            // them "L. Lead (Acme)". Resolving from app_user alone would show every client the
            // same string — whichever one was typed first.
            JsonNode owner = byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-1").get("owner");

            assertThat(owner.get("displayName").asText()).isEqualTo("L. Lead (Acme)");
            assertThat(owner.get("email").asText()).isEqualTo("lead@cases-acme.test");
            assertThat(owner.get("id").asText()).isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("an owner with no membership falls back to the account's canonical name")
        void offboardedOwnersStillRender() throws Exception {
            // Their authored cases stay when they leave the company, and a blank owner column
            // reads as data loss rather than as a person having left.
            assertThat(byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-4")
                    .get("owner").get("displayName").asText())
                    .isEqualTo("Fenwick Former");
        }

        @Test
        @DisplayName("an unowned case is listed with a null owner, not hidden and not invented")
        void unownedCasesAreListed() throws Exception {
            // owner_id is nullable in the schema while the contract used to mark `owner`
            // required; this slice resolved that toward the schema. Hiding the row loses a case
            // from its own project's list, and inventing an "Unassigned" user puts a person on the
            // wire whose id nothing can resolve.
            JsonNode row = byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-3");

            assertThat(row.has("owner"))
                    .as("present and null, so the SPA reads one shape either way")
                    .isTrue();
            assertThat(row.get("owner").isNull()).isTrue();
        }

        @Test
        @DisplayName("the owner's uuid is on the wire but nothing else about the account is")
        void nothingElseAboutTheAccountLeaks() throws Exception {
            JsonNode owner = byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-1").get("owner");

            assertThat(List.of("passwordHash", "status", "permissionVersion", "lastLoginAt"))
                    .allSatisfy(field -> assertThat(owner.has(field)).isFalse());
        }
    }

    // =====================================================================
    // Authorship
    // =====================================================================

    /**
     * {@code createdBy} — the field {@code case:delete}'s {@code OWN} qualifier is decided by.
     *
     * <p>Every assertion here would pass against {@code createdBy = owner} if the fixture seeded the
     * two columns equal, which is what they are on most real rows. {@link #seedCases} deliberately
     * disagrees them.
     */
    @Nested
    @DisplayName("Authorship — who WROTE the case, which is not who owns it")
    class Authorship {

        @Test
        @DisplayName("createdBy is the author, not the owner, when the two differ")
        void authorIsNotTheOwner() throws Exception {
            JsonNode row = byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-1");

            assertThat(row.get("createdBy").get("id").asText())
                    .as("test_case.created_by — the column CaseDeleteService compares")
                    .isEqualTo(ADMIN.toString());
            assertThat(row.get("owner").get("id").asText())
                    .as("and the owner is somebody else, or this test proves nothing")
                    .isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("the author's name comes from the ACTIVE company's membership too")
        void authorNameIsPerMembership() throws Exception {
            // Resolved through the same CaseOwners batch as the owner, so plan §6u's per-company
            // display name holds for both columns rather than for whichever one was written first.
            JsonNode author = byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-3").get("createdBy");

            assertThat(author.get("displayName").asText()).isEqualTo("L. Lead (Acme)");
            assertThat(author.get("id").asText()).isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("an unauthored case is listed with a null createdBy, not hidden")
        void unauthoredCasesAreListed() throws Exception {
            // created_by is nullable — a row written before authorship was recorded has none. The
            // SPA renders those with Delete DISABLED for an OWN-qualified caller, mirroring
            // canDoOnAuthored's `userId.equals(null)` → false: a grant covering "your own work"
            // must not stretch to work nobody can attribute.
            JsonNode row = byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-4");

            assertThat(row.has("createdBy"))
                    .as("present and null, so the SPA reads one shape either way")
                    .isTrue();
            assertThat(row.get("createdBy").isNull()).isTrue();
        }

        @Test
        @DisplayName("an owned-and-unauthored row proves the two columns are read separately")
        void ownerAndAuthorAreIndependent() throws Exception {
            // TC-4 has an owner and no author; TC-3 has an author and no owner. Together they
            // refuse both directions of the swap — a mapping that read one column for both fields
            // fails on one of these two rows whichever column it picked.
            List<JsonNode> items = itemsAs(LEAD, ACME, ACME_BANKING, "");

            assertThat(byKey(items, "TC-4").get("owner").isNull()).isFalse();
            assertThat(byKey(items, "TC-4").get("createdBy").isNull()).isTrue();
            assertThat(byKey(items, "TC-3").get("owner").isNull()).isTrue();
            assertThat(byKey(items, "TC-3").get("createdBy").isNull()).isFalse();
        }
    }

    // =====================================================================
    // Classification and tags
    // =====================================================================

    @Nested
    @DisplayName("Classification and tags — resolved to names, retired values dropped")
    class Classification {

        @Test
        @DisplayName("features are names, and a case can carry several")
        void featuresAreNamed() throws Exception {
            assertThat(namesIn(byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-1").get("features")))
                    .containsExactly("Payments", "Refunds");
        }

        @Test
        @DisplayName("testType and testLevel are scalars, and null when unclassified")
        void singleDimensionsAreScalars() throws Exception {
            List<JsonNode> rows = itemsAs(LEAD, ACME, ACME_BANKING, "");

            assertThat(byKey(rows, "TC-1").get("testType").asText()).isEqualTo("Functional");
            assertThat(byKey(rows, "TC-1").get("testLevel").asText()).isEqualTo("API");
            assertThat(byKey(rows, "TC-2").get("testType").isNull())
                    .as("a case need not be classified, and nothing forces it to be")
                    .isTrue();
        }

        @Test
        @DisplayName("a retired classification value is not rendered as a current one")
        void retiredValuesAreNotRendered() throws Exception {
            // TC-5 is classified under the retired feature and nothing else. Rendering it would
            // present a withdrawn classification as current — the same omission
            // SuiteConditionView makes for a retired condition.
            assertThat(byKey(itemsAs(LEAD, ACME, ACME_BANKING, ""), "TC-5").get("features"))
                    .isEmpty();
        }

        @Test
        @DisplayName("tags come back sorted, and an untagged case gets [] rather than null")
        void tagsAreOrderedAndAlwaysPresent() throws Exception {
            List<JsonNode> rows = itemsAs(LEAD, ACME, ACME_BANKING, "");

            assertThat(namesIn(byKey(rows, "TC-1").get("tags")))
                    .as("sorted, so a row's chips do not reshuffle between requests")
                    .containsExactly("regression", "smoke");
            assertThat(byKey(rows, "TC-2").get("tags").isArray()).isTrue();
            assertThat(byKey(rows, "TC-2").get("tags")).isEmpty();
        }
    }

    // =====================================================================
    // Isolation and the gate
    // =====================================================================

    @Nested
    @DisplayName("Both gates apply, and the lists do not mix")
    class Gates {

        @Test
        @DisplayName("a sibling project's cases do not appear, though the keys collide")
        void listsDoNotCrossProjects() throws Exception {
            List<String> mobile = idsIn(bodyAs(ADMIN, ACME, ACME_MOBILE, "").get("items"));

            assertThat(idsIn(bodyAs(ADMIN, ACME, ACME_BANKING, "").get("items")))
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(mobile);
            assertThat(mobile)
                    .as("the sibling really is populated — otherwise the assertion is vacuous")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("the other client's identically keyed cases are different rows")
        void listsDoNotCrossCompanies() throws Exception {
            List<JsonNode> umbra = itemsAs(OUTSIDER, UMBRA, UMBRA_BANKING, "");

            assertThat(keysOf(umbra))
                    .as("display keys are company-scoped, so TC-1 exists in both clients (§6t) — "
                            + "the fixture is only hostile if the other side really does collide")
                    .contains("TC-1");
            assertThat(idsOf(itemsAs(LEAD, ACME, ACME_BANKING, "")))
                    .doesNotContainAnyElementsOf(idsOf(umbra));
        }

        @Test
        @DisplayName("a member with no grant gets 404, not their client's entire repository")
        void membershipConveysNoCases() throws Exception {
            // The forbidden rows are in THIS caller's own company, so no cross-company assertion
            // can reach them. Only the grant gate stands between a member granted nothing and
            // every title, feature and owner in the client's test repository — which is a larger
            // disclosure than the suite tree's, because the tree sketches what they are building
            // and this list IS it.
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING, "")).isEqualTo(404);
        }

        @Test
        @DisplayName("the same caller who is refused DOES reach a project they hold a grant on")
        void theRefusalIsTheGateAndNotABrokenRoute() throws Exception {
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, "")).isEqualTo(200);
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent are indistinguishable, bodies included")
        void theThreeCasesAnswerIdentically() throws Exception {
            UUID absent = UUID.randomUUID();

            assertThat(List.of(
                    statusOf(LEAD, ACME, ACME_MOBILE, ""),
                    statusOf(LEAD, ACME, UMBRA_BANKING, ""),
                    statusOf(LEAD, ACME, absent, "")))
                    .as("told apart, the response becomes a probe for what exists in another "
                            + "client's tenancy (§6t)")
                    .containsExactly(404, 404, 404);

            assertThat(rawBodyOf(LEAD, ACME, UMBRA_BANKING, ""))
                    .as("a 404 that explains itself is still an oracle")
                    .isEmpty();
            assertThat(rawBodyOf(LEAD, ACME, absent, "")).isEmpty();
        }

        @Test
        @DisplayName("a Company Admin's COMPANY-scope grant reaches every project in that company")
        void companyScopeCoversEveryProject() throws Exception {
            assertThat(statusOf(ADMIN, ACME, ACME_BANKING, "")).isEqualTo(200);
            assertThat(statusOf(ADMIN, ACME, ACME_MOBILE, "")).isEqualTo(200);
            assertThat(statusOf(ADMIN, ACME, UMBRA_BANKING, ""))
                    .as("every project in the COMPANY the grant covers, and that company is the "
                            + "whole point")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/cases"))
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

    private JsonNode bodyAs(UUID userId, UUID companyId, UUID projectId, String query)
            throws Exception {

        String body = rawBodyOf(userId, companyId, projectId, query);
        JsonNode parsed = json.readTree(body);
        assertThat(parsed.isObject()).as("the endpoint must answer a JSON object: %s", body).isTrue();
        return parsed;
    }

    private List<JsonNode> itemsAs(UUID userId, UUID companyId, UUID projectId, String query)
            throws Exception {

        List<JsonNode> items = new ArrayList<>();
        bodyAs(userId, companyId, projectId, query).get("items").forEach(items::add);
        return List.copyOf(items);
    }

    private String rawBodyOf(UUID userId, UUID companyId, UUID projectId, String query)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get(uri(projectId, query))
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId, String query)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get(uri(projectId, query))
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    /**
     * The request URI, built as a {@link URI} rather than as a template string.
     *
     * <p>{@code MockMvcRequestBuilders.get(String)} treats its argument as a URI <em>template</em>
     * and encodes it, so a test sending {@code q=100%25} would have the percent re-encoded and the
     * endpoint would receive the literal text {@code %25}. The {@code URI} overload passes the
     * query through as written, which is what a real client sends — and without it the
     * LIKE-escaping test asserts against a string the product will never see.
     *
     * <p>{@code size=200} is appended only when the caller has not asked for a size of their own.
     * A second {@code size} parameter does not override the first; Spring binds the first
     * occurrence, so a paging test would silently be served the default page instead of the one it
     * asked for — which is the sort of harness bug that reads as a paging defect.
     */
    private static URI uri(UUID projectId, String query) {
        String base = "/api/v1/projects/" + projectId + "/cases?";
        return URI.create(base + (query.contains("size=") ? query.substring(1) : "size=200" + query));
    }

    // =====================================================================
    // helpers — reading the response
    // =====================================================================

    private static JsonNode byKey(List<JsonNode> rows, String key) {
        return rows.stream()
                .filter(node -> node.get("key").asText().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no case keyed '" + key + "' — the response carried " + keysOf(rows)));
    }

    private static List<String> keysOf(List<JsonNode> rows) {
        return rows.stream().map(node -> node.get("key").asText()).toList();
    }

    private static List<String> idsOf(List<JsonNode> rows) {
        return rows.stream().map(node -> node.get("id").asText()).toList();
    }

    private static List<String> prioritiesOf(List<JsonNode> rows) {
        return rows.stream().map(node -> node.get("priority").asText()).toList();
    }

    private static List<String> suiteNamesOf(List<JsonNode> rows) {
        return rows.stream().map(node -> node.get("suiteName").asText()).toList();
    }

    private static List<String> keysIn(JsonNode array) {
        List<String> keys = new ArrayList<>();
        array.forEach(node -> keys.add(node.get("key").asText()));
        return keys;
    }

    private static List<String> idsIn(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asText()));
        return ids;
    }

    private static List<String> namesIn(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private static List<String> facetKeysOf(JsonNode body) {
        List<String> keys = new ArrayList<>();
        body.get("facets").forEach(node -> keys.add(node.get("key").asText()));
        return keys;
    }

    private static JsonNode facet(JsonNode body, String key) {
        for (JsonNode node : body.get("facets")) {
            if (node.get("key").asText().equals(key)) {
                return node;
            }
        }
        throw new AssertionError("no facet '" + key + "' — carried " + facetKeysOf(body));
    }

    /** The count for one facet value, or 0 when the value is absent — absence means zero here. */
    private static long facetCount(JsonNode body, String facetKey, String valueId) {
        for (JsonNode value : facet(body, facetKey).get("values")) {
            if (value.get("id").asText().equals(valueId)) {
                return value.get("count").asLong();
            }
        }
        return 0;
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

    private void grantOnCompany(String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("case-list:" + marker).getBytes());
    }

    /**
     * The company and project vocabularies, through the V2 seed functions rather than by hand.
     *
     * <p>Both are idempotent, which is what makes running them per test method safe. The Feature
     * dimension arrives empty on purpose — inventing a client's taxonomy is how a controlled
     * vocabulary gets worked around — so its values are written below.
     */
    private void seedVocabularies() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            call(connection, "SELECT tcms_seed_company_defaults(CAST(? AS uuid))", ACME);
            for (UUID projectId : List.of(ACME_BANKING, ACME_MOBILE, ACME_EMPTY)) {
                seedProjectDefaults(connection, ACME, projectId);
            }
        }
        try (Connection connection = appConnection(UMBRA)) {
            call(connection, "SELECT tcms_seed_company_defaults(CAST(? AS uuid))", UMBRA);
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
        }

        try (Connection connection = appConnection(ACME)) {
            featureValue(connection, F_PAYMENTS, null, "Payments", "Payments", 0, false);
            featureValue(connection, F_REFUNDS, F_PAYMENTS, "Refunds", "Payments.Refunds", 1, false);
            // Retired, and therefore never rendered and never matchable — but still resolvable for
            // whatever ran classified under it, which is why it is a soft delete.
            featureValue(connection, F_RETIRED, null, "Legacy", "Legacy", 2, true);
        }
    }

    /**
     * The trees, written as raw SQL through the ordinary app role.
     *
     * <p>There is no suite-authoring endpoint, so a fixture is the only way to get rows here. The
     * <em>app</em> role rather than the superuser on purpose: it is the same policed path
     * production reads through, so a fixture that could not be written under RLS is one that is
     * lying about what the endpoint will see.
     */
    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1,
                    "Payments", "Payments", false);
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2,
                    "Refunds", "Payments.Refunds", false);
            suite(connection, PARTIAL, ACME, ACME_BANKING, REFUNDS, "TS-3", 3,
                    "Partial refunds", "Payments.Refunds.Partial", false);
            suite(connection, CHECKOUT, ACME, ACME_BANKING, null, "TS-4", 4,
                    "Checkout", "Checkout", false);
            suite(connection, RETIRED_SUITE, ACME, ACME_BANKING, null, "TS-5", 5,
                    "Retired folder", "Retired", true);

            suite(connection, mobileSuite(), ACME, ACME_MOBILE, null, "TS-6", 6,
                    "Payments", "Payments", false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            suite(connection, umbraSuite(), UMBRA, UMBRA_BANKING, null, "TS-1", 1,
                    "Payments", "Payments", false);
        }
    }

    /**
     * Every case the assertions name.
     *
     * <p>Keys run 1–6 and then <strong>10</strong>: with only single digits, a text ordering and a
     * numeric one agree and the {@code keySeq} assertion cannot fail. Priorities are chosen so an
     * alphabetical sort and a by-urgency sort disagree, and so two pairs tie — which is what the
     * tie-breaker has to be tested against.
     *
     * <p><strong>The insert order is deliberate and it is the second version of this fixture.</strong>
     * Rows that tie on priority are written in the <em>opposite</em> order to their {@code keySeq}
     * — TC-10 before TC-2, TC-5 before TC-3 — because Postgres returns a small table in physical
     * order when the sort does not separate two rows. Written in key order, a query with no
     * tie-breaker came back correctly ordered by luck and the mutation that deletes the tie-breaker
     * <em>survived</em>. A fixture that cannot distinguish the right query from the wrong one
     * proves nothing; §24's ordering survivor is the same lesson one level up.
     */
    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // `createdBy` DISAGREES with `owner_id` on almost every row, and that is the fixture's
            // job rather than an accident. The two columns answer different questions — who wrote
            // it, who is responsible for it now — and they are equal on most real rows, which is
            // precisely why seeding them equal would let `createdBy: owner` pass every assertion
            // below. §64's rule: seed the fixture so the wrong implementation is distinguishable.
            testCase(connection, caseId("tc1"), ACME_BANKING, REFUNDS, "TC-1", 1,
                    "Full refund to card", null, "CRITICAL", "APPROVED", "AUTOMATED",
                    "STRUCTURED", LEAD, ADMIN, false);
            // A literal percent sign in the title, and key 10.
            testCase(connection, caseId("tc10"), ACME_BANKING, REFUNDS, "TC-10", 10,
                    "100% discount path", null, "LOW", "DEPRECATED", "NOT_AUTOMATABLE",
                    "STRUCTURED", LEAD, ADMIN, false);

            testCase(connection, caseId("tc2"), ACME_BANKING, REFUNDS, "TC-2", 2,
                    "Partial refund rounding", null, "LOW", "DRAFT", "MANUAL",
                    "GHERKIN", LEAD, ADMIN, false);
            // Title says nothing about refunds; the DESCRIPTION does. That is what makes the
            // free-text assertion cover both columns rather than only the indexed one.
            testCase(connection, caseId("tc5"), ACME_BANKING, CHECKOUT, "TC-5", 5,
                    "Guest checkout", "Issue a refund at the till", "MEDIUM", "DRAFT", "MANUAL",
                    "STRUCTURED", LEAD, ADMIN, false);
            // Unowned: owner_id is nullable in the schema and this is the row that proves the
            // response says so rather than hiding the case or inventing a person. Its AUTHOR is
            // set, so it also separates the two nullabilities — a mapping that returned the owner
            // for both would answer null here and pass the unowned assertion.
            testCase(connection, caseId("tc3"), ACME_BANKING, REFUNDS, "TC-3", 3,
                    "Refund an expired card", null, "MEDIUM", "IN_REVIEW", "TO_BE_AUTOMATED",
                    "STRUCTURED", null, LEAD, false);
            // Owned by an account with no Acme membership — offboarded. UNAUTHORED: created_by is
            // nullable and this is the row the OWN grant's "must not stretch to work nobody can
            // attribute" arm is asserted against.
            testCase(connection, caseId("tc4"), ACME_BANKING, PARTIAL, "TC-4", 4,
                    "Split refund across two cards", null, "HIGH", "APPROVED", "MANUAL",
                    "STRUCTURED", FORMER, null, false);
            testCase(connection, caseId("tc6"), ACME_BANKING, CHECKOUT, "TC-6", 6,
                    "Deleted case", null, "LOW", "DRAFT", "MANUAL", "STRUCTURED", LEAD, LEAD, true);
            // LIVE, inside a soft-deleted suite. The row the live-tree bound exists for.
            testCase(connection, caseId("tc7"), ACME_BANKING, RETIRED_SUITE, "TC-7", 7,
                    "Case in a retired folder", null, "HIGH", "APPROVED", "MANUAL",
                    "STRUCTURED", LEAD, LEAD, false);
            testCase(connection, caseId("mob1"), ACME_MOBILE, mobileSuite(), "TC-20", 20,
                    "Mobile login", null, "HIGH", "APPROVED", "MANUAL", "STRUCTURED", LEAD,
                    LEAD, false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            // TC-1 again: keys are numbered per COMPANY, so the collision is the product working
            // as designed and not a fixture cheat.
            testCase(connection, caseId("umb1"), UMBRA_BANKING, umbraSuite(), "TC-1", 1,
                    "Full refund to card", null, "CRITICAL", "APPROVED", "AUTOMATED",
                    "STRUCTURED", null, null, false);
        }
    }

    private void seedClassificationsAndTags() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // TC-1 on both the parent and the child: a case with two features must still appear
            // once, which is what the EXISTS predicate is for.
            classify(connection, caseId("tc1"), F_PAYMENTS);
            classify(connection, caseId("tc1"), F_REFUNDS);
            classify(connection, caseId("tc2"), F_PAYMENTS);
            classify(connection, caseId("tc3"), F_REFUNDS);
            // Only the retired value, so the rendered list must come back empty rather than
            // naming a withdrawn classification.
            classify(connection, caseId("tc5"), F_RETIRED);

            classifyByName(connection, caseId("tc1"), "test_type", "Functional");
            classifyByName(connection, caseId("tc1"), "test_level", "API");

            tag(connection, caseId("tc1"), "smoke");
            tag(connection, caseId("tc1"), "regression");
        }
    }

    private static UUID caseId(String marker) {
        return UUID.nameUUIDFromBytes(("case-list-case:" + marker).getBytes());
    }

    private static UUID mobileSuite() {
        return UUID.fromString("6a004000-0000-4000-8000-000000000011");
    }

    private static UUID umbraSuite() {
        return UUID.fromString("6a004000-0000-4000-8000-000000000021");
    }

    // =====================================================================
    // helpers — SQL
    //
    // Every nullable parameter is bound through an explicit CAST: setObject(i, null) leaves pgjdbc
    // with no type to infer and fails with "could not determine data type of parameter", a
    // fixture-only failure that reads like a schema problem.
    // =====================================================================

    private void call(Connection connection, String sql, UUID argument) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, argument);
            statement.execute();
        }
    }

    private void seedProjectDefaults(Connection connection, UUID companyId, UUID projectId)
            throws SQLException {

        try (var statement = connection.prepareStatement(
                "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private void featureValue(
            Connection connection, UUID id, UUID parentId, String name, String path,
            int position, boolean retired) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO classification_value
                    (id, company_id, dimension_id, parent_id, path, name, position, deleted_at)
                SELECT CAST(? AS uuid), CAST(? AS uuid), d.id, CAST(? AS uuid), CAST(? AS ltree),
                       ?, ?, CAST(? AS timestamptz)
                  FROM classification_dimension d
                 WHERE d.company_id = CAST(? AS uuid)
                   AND d.project_id = CAST(? AS uuid)
                   AND d.key = 'feature'
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, parentId == null ? null : parentId.toString());
            statement.setString(4, path);
            statement.setString(5, name);
            statement.setInt(6, position);
            statement.setString(7, retired ? "2026-01-01T00:00:00Z" : null);
            statement.setString(8, ACME.toString());
            statement.setString(9, ACME_BANKING.toString());
            statement.executeUpdate();
        }
    }

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path, boolean deleted)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, parent_id, key, key_seq, path, name,
                     position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, 0, 'APPROVED', CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, parentId == null ? null : parentId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, path);
            statement.setString(8, name);
            statement.setString(9, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void testCase(
            Connection connection, UUID id, UUID projectId, UUID suiteId, String key, long keySeq,
            String title, String description, String priority, String lifecycle,
            String automation, String stepFormat, UUID ownerId, UUID createdBy, boolean deleted)
            throws SQLException {

        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, description,
                     step_format, priority, lifecycle_status, automation_status,
                     not_automatable_reason, owner_id, created_by, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, CAST(? AS text), ?, ?, ?, ?, CAST(? AS text), CAST(? AS uuid),
                        CAST(? AS uuid), CAST(? AS timestamptz), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, description);
            statement.setString(9, stepFormat);
            statement.setString(10, priority);
            statement.setString(11, lifecycle);
            statement.setString(12, automation);
            // ck_not_automatable_reason refuses the row without one — the constraint that keeps
            // automation coverage honestly denominated.
            statement.setString(13, "NOT_AUTOMATABLE".equals(automation) ? "Third-party captcha" : null);
            statement.setString(14, ownerId == null ? null : ownerId.toString());
            statement.setString(15, createdBy == null ? null : createdBy.toString());
            // A fixed instant rather than now(): deterministic, and updatedAt is asserted only for
            // presence.
            statement.setString(16, "2026-01-01T00:00:00Z");
            statement.setString(17, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void classify(Connection connection, UUID caseId, UUID valueId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, v.dimension_id
                  FROM classification_value v
                 WHERE v.id = CAST(? AS uuid)
                ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, valueId.toString());
            statement.executeUpdate();
        }
    }

    /** For the seeded vocabularies, whose value ids are generated and therefore unknown here. */
    private void classifyByName(
            Connection connection, UUID caseId, String dimensionKey, String valueName)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_classification (company_id, case_id, value_id, dimension_id)
                SELECT CAST(? AS uuid), CAST(? AS uuid), v.id, v.dimension_id
                  FROM classification_value v
                  JOIN classification_dimension d ON d.id = v.dimension_id
                 WHERE d.company_id = CAST(? AS uuid)
                   AND d.project_id IS NULL
                   AND d.key = ?
                   AND v.name = ?
                ON CONFLICT (case_id, value_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, ACME.toString());
            statement.setString(4, dimensionKey);
            statement.setString(5, valueName);
            statement.executeUpdate();
        }
    }

    private void tag(Connection connection, UUID caseId, String tag) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT (case_id, tag) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }
}
