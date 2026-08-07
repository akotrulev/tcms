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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /projects/{projectId}/cases/{caseId}} — one whole case.
 *
 * <h2>The failures this class exists to catch, and only one of them is loud</h2>
 *
 * <ol>
 *   <li><strong>A case served through the wrong project's path.</strong> The caller holds a grant
 *       on the project in the path and none on the project the case is actually in, and a lookup by
 *       case id alone hands over its full text — title, steps, conditions, owner. It needs no
 *       cross-company step, so <em>every</em> cross-company test in this suite still passes while
 *       it is open. Asserted directly rather than inferred from the shared gate.</li>
 *   <li><strong>An inherited condition rendered as the case's own.</strong> The SPA marks inherited
 *       entries and warns that editing one reaches every case beneath the suite. Unmarked, it reads
 *       as local, gets "fixed" locally, and the fix lands on two hundred cases nobody was
 *       looking at (plan §6f).</li>
 *   <li><strong>An opted-out or retired condition still in the checklist.</strong> Both are
 *       silent: the response is a plausible list with one entry too many, and the entry is a
 *       procedure somebody deliberately withdrew.</li>
 *   <li><strong>Preconditions and postconditions deduped together.</strong> "Reset the test payment
 *       data" attached before the steps and after them is two actions at two times — exactly what a
 *       {@code BOTH} library entry is for — and a single dedupe silently drops the second.</li>
 * </ol>
 *
 * <h2>The fixture is deliberately hostile</h2>
 *
 * <p>The main case sits at the <strong>bottom</strong> of a three-deep chain, so the ancestry walk
 * has somewhere to go wrong; every folder in the chain attaches something, and the expected order
 * root-downward is the reverse of the order nearest-first, so a walk in the wrong direction cannot
 * come back right. One condition is attached twice at two depths (dedupe), one is opted out, one is
 * retired, one is inline, one is attached as {@code PRE} above and as {@code POST} on the case.
 * Steps are inserted in an order that disagrees with their positions, and automation tags in an
 * order that disagrees with the alphabet, because Postgres returns a small table in physical order
 * and a fixture written in the answer's order proves nothing (§25, §26).
 *
 * <p>A second case carries none of it — no steps, no conditions, no owner, no severity — because
 * "the empty ones serialize as arrays and the absent ones as null" is a claim about the shape that
 * a fully populated fixture cannot make. A third is live inside a soft-deleted folder, which is the
 * one reachable case whose ancestry chain is empty.
 *
 * <p>Company and user ids are unique to this class. Sharing them with another test class is how
 * handoff §19's first defect happened — green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Case detail endpoint — the whole case, behind two path gates")
class CaseDetailEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d0000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("d0000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD. Holds every case the assertions name. */
    private static final UUID ACME_BANKING = UUID.fromString("d0001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to LEAD — the sibling its cases must not be readable through. */
    private static final UUID ACME_MOBILE = UUID.fromString("d0001000-0000-4000-8000-000000000002");
    /** The other client's project. */
    private static final UUID UMBRA_BANKING = UUID.fromString("d0002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("d0003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("d0003000-0000-4000-8000-000000000002");
    /** Company Admin in Acme — reaches every project through a grant with no project id. */
    private static final UUID ADMIN = UUID.fromString("d0003000-0000-4000-8000-000000000003");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("d0003000-0000-4000-8000-000000000004");

    // The chain: Payments › Refunds › Partial refunds. Every level attaches something.
    private static final UUID PAYMENTS = UUID.fromString("d0004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("d0004000-0000-4000-8000-000000000002");
    private static final UUID PARTIAL = UUID.fromString("d0004000-0000-4000-8000-000000000003");
    private static final UUID CHECKOUT = UUID.fromString("d0004000-0000-4000-8000-000000000004");
    private static final UUID RETIRED_SUITE = UUID.fromString("d0004000-0000-4000-8000-000000000005");
    private static final UUID MOBILE_SUITE = UUID.fromString("d0004000-0000-4000-8000-000000000006");
    /** Attaches nothing, so the unauthored case really is unauthored all the way down. */
    private static final UUID REPORTING = UUID.fromString("d0004000-0000-4000-8000-000000000008");
    private static final UUID UMBRA_SUITE = UUID.fromString("d0004000-0000-4000-8000-000000000007");

    // The library.
    private static final UUID BANNER = UUID.fromString("d0005000-0000-4000-8000-000000000001");
    private static final UUID LOGIN = UUID.fromString("d0005000-0000-4000-8000-000000000002");
    private static final UUID DECLINED = UUID.fromString("d0005000-0000-4000-8000-000000000003");
    private static final UUID SETTLED = UUID.fromString("d0005000-0000-4000-8000-000000000004");
    private static final UUID RESET = UUID.fromString("d0005000-0000-4000-8000-000000000005");
    private static final UUID CLEANUP = UUID.fromString("d0005000-0000-4000-8000-000000000006");
    private static final UUID RETIRED_CONDITION =
            UUID.fromString("d0005000-0000-4000-8000-000000000007");
    private static final UUID OWN_ONLY = UUID.fromString("d0005000-0000-4000-8000-000000000008");

    /** The {@code suite_condition} row FULL declines — the opt-out is keyed on the attachment. */
    private static final UUID DECLINED_ATTACHMENT =
            UUID.fromString("d0006000-0000-4000-8000-000000000002");

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
    private CaseConditionRepository caseConditions;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "detail-acme", "Acme", now);
            company(UMBRA, "detail-umbra", "Umbra", now);

            activeUser(LEAD, "lead@detail-acme.test", "L. Lead", now);
            activeUser(NO_GRANT, "nogrant@detail-acme.test", "N. Grant", now);
            activeUser(ADMIN, "admin@detail-acme.test", "A. Admin", now);
            activeUser(OUTSIDER, "engineer@detail-umbra.test", "O. Engineer", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "DAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "DAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("detail-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnCompany("detail-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "DUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("detail-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedVocabularies();
        seedTrees();
        seedCases();
        seedFlake();
        seedLibrary();
        seedAttachments();
        seedStepsTagsAndClassification();
    }

    // =====================================================================
    // The case itself
    // =====================================================================

    @Nested
    @DisplayName("The case — every field the contract's two halves declare")
    class TheCase {

        @Test
        @DisplayName("the summary half is present and is the grid's, field for field")
        void summaryFieldsArePresent() throws Exception {
            // TestCase is allOf: [TestCaseSummary, {...}], so the detail owes every field the grid
            // owes. Rendered through CaseSummaries — the same component the list uses — which is
            // what stops a row and its own page disagreeing about one case.
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("full"));

            assertThat(List.of("id", "key", "keySeq", "title", "suiteId", "suiteName", "priority",
                            "lifecycleStatus", "automationStatus", "stepFormat", "owner",
                            "features", "tags", "healthSignals", "updatedAt"))
                    .allSatisfy(field -> assertThat(body.has(field))
                            .as("TestCaseSummary.%s is required by openapi.yaml", field)
                            .isTrue());

            assertThat(body.get("key").asText()).isEqualTo("TC-1");
            assertThat(body.get("keySeq").asLong()).isEqualTo(1L);
            assertThat(body.get("title").asText()).isEqualTo("Full refund to card");
            assertThat(body.get("suiteId").asText()).isEqualTo(PARTIAL.toString());
            assertThat(body.get("suiteName").asText()).isEqualTo("Partial refunds");
            assertThat(body.get("priority").asText()).isEqualTo("CRITICAL");
            assertThat(body.get("stepFormat").asText()).isEqualTo("STRUCTURED");
        }

        @Test
        @DisplayName("the owner is named through THIS company's membership, not the account")
        void ownerUsesTheMembershipDisplayName() throws Exception {
            // Plan §6u: the same person is labelled separately per client, and neither may see the
            // other's label. The account's canonical name is the fallback for someone offboarded,
            // not the value.
            JsonNode owner = detailAs(LEAD, ACME, ACME_BANKING, caseId("full")).get("owner");

            assertThat(owner.get("displayName").asText()).isEqualTo("L. Lead of Acme");
            assertThat(owner.get("id").asText()).isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("the detail half carries every authored field")
        void detailFieldsArePresent() throws Exception {
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("full"));

            assertThat(body.get("description").asText())
                    .isEqualTo("Refunds the whole captured amount to the original card.");
            assertThat(body.get("objective").asText())
                    .isEqualTo("Prove a merchant can recover funds without manual intervention.");
            assertThat(body.get("expectedOutcome").asText())
                    .isEqualTo("The payment reaches REFUNDED and the refundable balance is zero.");
            assertThat(body.get("severity").asText()).isEqualTo("HIGH");
            assertThat(body.get("estimatedDurationSec").asInt()).isEqualTo(240);
            assertThat(body.get("automationKey").asText())
                    .isEqualTo("com.example.payments.RefundIT#fullRefund");
            assertThat(body.get("currentVersionNo").asInt()).isEqualTo(7);
            assertThat(body.get("lockVersion").asLong()).isEqualTo(3L);
        }

        @Test
        @DisplayName("severity is null where nobody set one, never defaulted to the priority")
        void severityIsNullWhenUnset() throws Exception {
            // priority is NOT NULL DEFAULT 'MEDIUM' and severity has no default at all — the
            // schema saying they are different fields. Copying priority across would put a
            // judgement on the wire that nobody made, and it would be indistinguishable from one
            // that was.
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("bare"));

            assertThat(body.get("priority").asText()).isEqualTo("MEDIUM");
            assertThat(body.get("severity").isNull()).isTrue();
        }

        @Test
        @DisplayName("an unauthored case answers 200 with nulls and empty arrays, never 404")
        void anEmptyCaseIsStillACase() throws Exception {
            // The state of every case created from a title and filled in later. The arrays must be
            // arrays: the SPA renders `steps ?? []` today but the empty states it has are written
            // for an empty list, not for a missing key.
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("bare"));

            assertThat(body.get("objective").isNull()).isTrue();
            assertThat(body.get("automationKey").isNull()).isTrue();
            assertThat(body.get("estimatedDurationSec").isNull()).isTrue();
            assertThat(body.get("owner").isNull()).isTrue();
            assertThat(body.get("steps").isArray()).isTrue();
            assertThat(body.get("steps")).isEmpty();
            assertThat(body.get("automationTags").isArray()).isTrue();
            assertThat(body.get("automationTags")).isEmpty();
            assertThat(body.get("preconditions")).isEmpty();
            assertThat(body.get("postconditions")).isEmpty();
        }

        @Test
        @DisplayName("currentVersionNo and lockVersion are different numbers, both served")
        void theTwoVersionsAreNotTheSameNumber() throws Exception {
            // v7 beside the title is the authored version a run pins (§3 lesson 1); lockVersion is
            // the write counter the SPA sends back so a concurrent edit is a 409. Serving one for
            // the other puts a number beside the title that no history entry matches.
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("full"));

            assertThat(body.get("currentVersionNo").asInt()).isEqualTo(7);
            assertThat(body.get("lockVersion").asLong()).isEqualTo(3L);
        }

        @Test
        @DisplayName("companyId and projectId never appear")
        void tenancyFieldsAreNeverExposed() throws Exception {
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("full"));

            assertThat(body.has("companyId")).isFalse();
            assertThat(body.has("projectId")).isFalse();
        }

        @Test
        @DisplayName("a live case in a soft-deleted folder resolves, with no folder name")
        void aCaseInARetiredFolderStillResolves() throws Exception {
            // Reachable by a bookmarked link and by nothing else — the case list is bounded to the
            // live tree, so this row is absent from every grid. The name is empty rather than
            // invented: a folder this endpoint could not resolve is not one it should name.
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("orphan"));

            assertThat(body.get("suiteId").asText()).isEqualTo(RETIRED_SUITE.toString());
            assertThat(body.get("suiteName").asText()).isEmpty();
        }
    }

    // =====================================================================
    // Steps
    // =====================================================================

    @Nested
    @DisplayName("Steps — the dual format, in the author's order")
    class Steps {

        @Test
        @DisplayName("steps come back in position order, not in the order they were written")
        void stepsAreOrderedByPosition() throws Exception {
            // The fixture inserts them 3, 1, 2 on purpose. Postgres returns a small table in
            // physical order, so a query with no ORDER BY comes back in insert order and a fixture
            // written in the answer's order would not notice (§25's tie-breaker survivor).
            assertThat(textsOf(detailAs(LEAD, ACME, ACME_BANKING, caseId("full")).get("steps"),
                    "action"))
                    .containsExactly(
                            "Open the settled payment",
                            "Request a full refund",
                            "Confirm the balance is zero");
        }

        @Test
        @DisplayName("a step's id is its own, and stable — evidence anchors to it")
        void stepIdsAreTheStepsOwn() throws Exception {
            // step_result FKs the step id, so a client keying on anything derived from the
            // position reattributes every screenshot the first time someone reorders (plan §6m).
            JsonNode first = detailAs(LEAD, ACME, ACME_BANKING, caseId("full")).get("steps").get(0);

            assertThat(first.get("id").asText()).isEqualTo(stepId("full-1").toString());
            assertThat(first.get("position").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("every field the contract marks required is present on a step")
        void requiredStepFieldsArePresent() throws Exception {
            JsonNode first = detailAs(LEAD, ACME, ACME_BANKING, caseId("full")).get("steps").get(0);

            assertThat(List.of("id", "position", "action"))
                    .allSatisfy(field -> assertThat(first.has(field))
                            .as("TestStep.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(first.get("testData").asText()).isEqualTo("card ending 4242");
            assertThat(first.get("expectedResult").asText())
                    .isEqualTo("The payment is listed as settled");
        }

        @Test
        @DisplayName("a structured step has no keyword — the absence IS the format")
        void structuredStepsCarryNoKeyword() throws Exception {
            // V1: "non-null identifies a Gherkin step". Defaulting it to GIVEN would turn every
            // structured case into a malformed Gherkin one.
            for (JsonNode step : detailAs(LEAD, ACME, ACME_BANKING, caseId("full")).get("steps")) {
                assertThat(step.get("keyword").isNull())
                        .as("step '%s' must have no keyword", step.get("action").asText())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a Gherkin case carries its source and its keyworded steps")
        void gherkinCasesCarryBoth() throws Exception {
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("gherkin"));

            assertThat(body.get("stepFormat").asText()).isEqualTo("GHERKIN");
            assertThat(body.get("gherkinSource").asText()).startsWith("Feature: Refunds");
            assertThat(textsOf(body.get("steps"), "keyword")).containsExactly("GIVEN", "WHEN", "THEN");
        }

        @Test
        @DisplayName("a structured case's gherkinSource is null, not an empty string")
        void structuredCasesHaveNoGherkinSource() throws Exception {
            // The two formats are alternatives (plan §6a) and the SPA branches on stepFormat. An
            // empty string would render as a Gherkin block containing nothing.
            assertThat(detailAs(LEAD, ACME, ACME_BANKING, caseId("full")).get("gherkinSource")
                    .isNull()).isTrue();
        }
    }

    // =====================================================================
    // Conditions — the §6f resolution order
    // =====================================================================

    @Nested
    @DisplayName("Conditions — inherited first, outermost folder first, deduped")
    class Conditions {

        @Test
        @DisplayName("THE assertion: the whole precondition list, in order, with its markers")
        void preconditionsResolveInTheDocumentedOrder() throws Exception {
            // Every rule in one list. Reading it top to bottom — all inherited from the case's
            // own suite since §98, in `position` order:
            //   pos 0 — "Accept the cookie banner"; inserted SECOND, so a dropped ORDER BY shows.
            //   pos 1 — "Log in as standard user".
            //   pos 2 — "Confirm the terms": OPTED OUT, absent.
            //   pos 3 — an INLINE attachment: absent, no title to serve.
            //   pos 4 — "Seed a settled payment".
            //   pos 5 — "Log in" AGAIN: deduped against pos 1, which wins by being first.
            //   pos 6 — a RETIRED library entry: absent.
            //   pos 7 — "Reset the test payment data".
            //   the case's own — "Seed a settled payment" again: deduped against the inherited
            //                    copy, which wins because it carries the warning.
            //                  — "Check the merchant is verified": the only own entry left.
            List<JsonNode> pre = arrayOf(
                    detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions");

            assertThat(textsOf(pre, "title")).containsExactly(
                    "Accept the cookie banner",
                    "Log in as standard user",
                    "Seed a settled payment",
                    "Reset the test payment data",
                    "Check the merchant is verified");
            assertThat(pre.stream().map(node -> node.get("inheritedFrom").asText("")).toList())
                    .as("one suite now names them all — the case's own")
                    .containsExactly("Partial refunds", "Partial refunds", "Partial refunds",
                            "Partial refunds", "");
            assertThat(pre.stream().map(node -> node.get("inherited").asBoolean()).toList())
                    .containsExactly(true, true, true, true, false);
        }

        @Test
        @DisplayName("the case's own condition is marked local and names no suite")
        void ownConditionsAreNotMarkedInherited() throws Exception {
            // inherited and inheritedFrom are set together and neither is meaningful alone.
            JsonNode own = byTitle(
                    arrayOf(detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions"),
                    "Check the merchant is verified");

            assertThat(own.get("inherited").asBoolean()).isFalse();
            assertThat(own.get("inheritedFrom").isNull()).isTrue();
        }

        @Test
        @DisplayName("a condition on the case's OWN suite is inherited, not local")
        void theCasesOwnFolderStillInherits() throws Exception {
            // The case did not attach it, and editing it still reaches every sibling filed in that
            // suite — which is the whole point of the marker. Marking it local removes the
            // warning from the commonest case there is, and since §98 that is the ONLY case.
            JsonNode nearest = byTitle(
                    arrayOf(detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions"),
                    "Reset the test payment data");

            assertThat(nearest.get("inherited").asBoolean()).isTrue();
            assertThat(nearest.get("inheritedFrom").asText()).isEqualTo("Partial refunds");
        }

        @Test
        @DisplayName("an opted-out inherited condition is gone from the list, not flagged in it")
        void optOutsRemoveTheRow() throws Exception {
            // The toggle beside the inherited marker (plan §6f). The case that tests the login
            // screen cannot require being logged in, and the alternatives without this are moving
            // the case out of the folder it belongs in or deleting the rule for 200 siblings.
            assertThat(titlesOf(arrayOf(
                    detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions")))
                    .doesNotContain("Confirm the terms and conditions");
        }

        @Test
        @DisplayName("an opt-out binds to ONE attachment, not to the condition everywhere")
        void optOutsAreKeyedOnTheAttachment() throws Exception {
            // The same library entry is attached at PARTIAL (opted out by this case) and again at
            // Checkout, where a different case inherits it and must keep it. Keying the opt-out on
            // condition_id would make one toggle mean "never inherit this from anywhere".
            assertThat(titlesOf(arrayOf(
                    detailAs(LEAD, ACME, ACME_BANKING, caseId("bystander")), "preconditions")))
                    .contains("Confirm the terms and conditions");
        }

        @Test
        @DisplayName("a retired library entry drops out of the checklist entirely")
        void retiredConditionsAreExcluded() throws Exception {
            // Retirement is a soft delete because attachments survive it. Serving one presents a
            // withdrawn procedure as current — the omission SuiteConditionView records.
            assertThat(titlesOf(arrayOf(
                    detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions")))
                    .doesNotContain("Log in as the old admin");
        }

        @Test
        @DisplayName("an inline attachment is absent — it has no title the contract could carry")
        void inlineAttachmentsAreExcluded() throws Exception {
            // Recorded rather than worked around: inventing a title would be the response making a
            // claim the data never made. Nothing can create one today either. The assertion is on
            // the count, because an inline row has no title to look for.
            assertThat(arrayOf(detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions"))
                    .hasSize(5);
        }

        @Test
        @DisplayName("the two kinds dedupe SEPARATELY — the same entry may be setup and cleanup")
        void kindsAreDedupedIndependently() throws Exception {
            // "Reset the test payment data" is inherited as a PRE from Partial refunds and
            // attached by the case as its own POST. Two actions, two times, and one shared dedupe
            // silently drops the second — which is exactly what a BOTH library entry exists for.
            List<JsonNode> post = arrayOf(
                    detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "postconditions");

            assertThat(textsOf(post, "title"))
                    .containsExactly("Tidy up the merchant account", "Reset the test payment data");
            assertThat(post.get(1).get("inherited").asBoolean())
                    .as("the case's own POST attachment, not the inherited PRE one")
                    .isFalse();
        }

        @Test
        @DisplayName("a PRE attachment never appears among the postconditions")
        void kindsDoNotLeakIntoEachOther() throws Exception {
            assertThat(titlesOf(arrayOf(
                    detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "postconditions")))
                    .doesNotContain("Log in as standard user", "Accept the cookie banner");
        }

        @Test
        @DisplayName("every field the contract marks required is present on a condition")
        void requiredConditionFieldsArePresent() throws Exception {
            JsonNode entry = byTitle(
                    arrayOf(detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions"),
                    "Log in as standard user");

            assertThat(List.of("id", "position", "title", "body", "inline", "inherited"))
                    .allSatisfy(field -> assertThat(entry.has(field))
                            .as("CaseConditionRef.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(entry.get("conditionId").asText()).isEqualTo(LOGIN.toString());
            assertThat(entry.get("inline").asBoolean()).isFalse();
            assertThat(textsIn(entry.get("body")))
                    .containsExactly("Open the application", "Enter valid credentials");
        }

        @Test
        @DisplayName("the id is the ATTACHMENT's, which is also what an opt-out is keyed on")
        void theIdIsTheAttachmentNotTheCondition() throws Exception {
            // Two cases referencing one condition are two rows, and the SPA keys list items on
            // this. For an inherited entry it is the suite_condition id — so the client already
            // holds the id it would need to decline the row.
            JsonNode entry = byTitle(
                    arrayOf(detailAs(LEAD, ACME, ACME_BANKING, caseId("full")), "preconditions"),
                    "Log in as standard user");

            assertThat(entry.get("id").asText())
                    .isNotEqualTo(LOGIN.toString())
                    .isEqualTo(attachmentId("payments-login").toString());
        }

        @Test
        @DisplayName("the query itself excludes inline rows, not just the consumer downstream")
        void theExclusionLivesInThePredicate() {
            // Asserted HERE, at the repository, and not only through the endpoint — because
            // through the endpoint it cannot fail. An inline row has a null conditionId, the
            // resolver looks it up with resolved.get(null), and HashMap answers null, so the row
            // drops out whether or not the query ever excluded it. Removing the predicate leaves
            // every endpoint assertion in this class green, and this one red.
            //
            // Verified by mutation, not assumed: the exclusion on the SUITE side is caught by
            // exactly this test in SuiteEndpointTest and by nothing else. Two guards is defence in
            // depth and is fine; a guard nothing can tell apart from its neighbour is how the
            // remaining one gets deleted as redundant.
            //
            // The context is opened AROUND the transaction, not inside it: the transaction manager
            // reads CompanyContext in doBegin to set app.current_company, so a context opened
            // after the transaction started arrives too late and RLS answers zero rows.
            // Fail-closed and silent — the same shape as handoff §15's missing @Transactional.
            List<UUID> attached;
            try (var ignored = CompanyContext.open(ACME)) {
                attached = tx.execute(status ->
                        caseConditions.findForCase(caseId("full")).stream()
                                .map(CaseCondition::getId)
                                .toList());
            }

            assertThat(attached)
                    .as("the three reference-backed attachments, and not the inline one")
                    .containsExactlyInAnyOrder(
                            attachmentId("full-settled"),
                            attachmentId("full-own"),
                            attachmentId("full-reset"));
        }

        @Test
        @DisplayName("a case whose folder is soft-deleted inherits nothing at all")
        void aBrokenChainInheritsNothing() throws Exception {
            // Half a chain is not a rule anyone wrote: the folder that would have carried the
            // inheritance is gone, so nothing above the gap applies either.
            assertThat(arrayOf(detailAs(LEAD, ACME, ACME_BANKING, caseId("orphan")),
                    "preconditions")).isEmpty();
        }
    }

    // =====================================================================
    // Automation
    // =====================================================================

    @Nested
    @DisplayName("Automation — selection tags, and the match key that is not one")
    class Automation {

        @Test
        @DisplayName("automation tags come back sorted, not in insert order")
        void automationTagsAreSorted() throws Exception {
            // Inserted @smoke then @regression. There is no authored order — the table has no
            // position column, because selection tags are a set — and a fixed order is what stops
            // a case's chips reshuffling between two reads.
            assertThat(textsIn(detailAs(LEAD, ACME, ACME_BANKING, caseId("full"))
                    .get("automationTags")))
                    .containsExactly("@regression", "@smoke");
        }

        @Test
        @DisplayName("automation tags are not the organisational tags, and both are served")
        void automationTagsAreSeparateFromCaseTags() throws Exception {
            // Plan §6c keeps them in two tables so "run the smoke suite" and "cases the release
            // manager flagged" are not the same query. Merging them makes the first sprint label
            // someone adds change what CI executes.
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("full"));

            assertThat(textsIn(body.get("automationTags"))).containsExactly("@regression", "@smoke");
            assertThat(textsIn(body.get("tags"))).containsExactly("release-4.2");
        }

        @Test
        @DisplayName("a NOT_AUTOMATABLE case carries the reason the constraint forced")
        void notAutomatableCasesCarryTheirReason() throws Exception {
            // ck_not_automatable_reason refuses the row without one — what keeps automation
            // coverage honestly denominated rather than letting anything inconvenient be filed as
            // impossible.
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("gherkin"));

            assertThat(body.get("automationStatus").asText()).isEqualTo("NOT_AUTOMATABLE");
            assertThat(body.get("notAutomatableReason").asText())
                    .isEqualTo("Requires a bank sandbox submission that cannot be triggered.");
        }
    }

    // =====================================================================
    // The gates
    // =====================================================================

    @Nested
    @DisplayName("Access — two path segments, three failures, one answer")
    class Access {

        @Test
        @DisplayName("THE assertion: a case in an ungranted project is not readable through a granted one")
        void aCaseIsNotReadableThroughAnotherProjectsPath() throws Exception {
            // /projects/{granted}/cases/{ungranted-case}. A lookup by case id alone answers 200
            // with the whole case, and every cross-company test in this suite still passes while
            // it does: the row genuinely is in the caller's company.
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, caseId("mobile"))).isEqualTo(404);
        }

        @Test
        @DisplayName("...and the case IS real, reachable by someone who holds the project")
        void thatCaseIsOtherwiseReachable() throws Exception {
            // Without this the assertion above passes against an endpoint that 404s on everything.
            assertThat(statusOf(ADMIN, ACME, ACME_MOBILE, caseId("mobile"))).isEqualTo(200);
        }

        @Test
        @DisplayName("a company member with no grant sees nothing — persona 3")
        void aMemberWithNoGrantIsRefused() throws Exception {
            // The rows ARE in this caller's company, so no cross-company assertion can catch a
            // gate that checks only tenancy.
            assertThat(statusOf(NO_GRANT, ACME, ACME_BANKING, caseId("full"))).isEqualTo(404);
        }

        @Test
        @DisplayName("a Company Admin reaches it through a grant with no project id")
        void aCompanyAdminIsAllowed() throws Exception {
            assertThat(statusOf(ADMIN, ACME, ACME_BANKING, caseId("full"))).isEqualTo(200);
        }

        @Test
        @DisplayName("another client's user gets 404, and nothing of this client's leaks")
        void anotherClientIsRefused() throws Exception {
            assertThat(statusOf(OUTSIDER, UMBRA, ACME_BANKING, caseId("full"))).isEqualTo(404);
            assertThat(bodyOf(OUTSIDER, UMBRA, ACME_BANKING, caseId("full"))).isEmpty();
        }

        @Test
        @DisplayName("this client cannot read the other client's case through its own project")
        void theOtherDirectionIsRefusedToo() throws Exception {
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, caseId("umbra"))).isEqualTo(404);
        }

        @Test
        @DisplayName("a soft-deleted case is 404, not a tombstone")
        void deletedCasesAreGone() throws Exception {
            // The row survives because past runs pin the version they executed and must keep
            // resolving what they covered. That is a reason to keep the row, not to keep offering
            // a screen that edits, executes and links it.
            assertThat(statusOf(LEAD, ACME, ACME_BANKING, caseId("deleted"))).isEqualTo(404);
        }

        @Test
        @DisplayName("all four failures answer identically — 404, no body")
        void everyRefusalLooksTheSame() throws Exception {
            // Anything that told them apart would make the endpoint an oracle for case ids in
            // projects the caller cannot open (§6t, §3 lesson 13). Same status AND same empty
            // body: a different content length is a distinguishable answer too.
            UUID missing = UUID.fromString("d0009000-0000-4000-8000-0000000000ff");

            for (UUID target : List.of(
                    missing, caseId("mobile"), caseId("umbra"), caseId("deleted"))) {
                assertThat(statusOf(LEAD, ACME, ACME_BANKING, target))
                        .as("case %s", target)
                        .isEqualTo(404);
                assertThat(bodyOf(LEAD, ACME, ACME_BANKING, target))
                        .as("case %s", target)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("an unreachable project 404s before the case is looked at")
        void anUnreachableProjectIsRefusedFirst() throws Exception {
            assertThat(statusOf(LEAD, ACME, ACME_MOBILE, caseId("mobile"))).isEqualTo(404);
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private JsonNode detailAs(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {

        String body = bodyOf(userId, companyId, projectId, caseId);
        assertThat(body).as("the endpoint answered with no body").isNotEmpty();
        return json.readTree(body);
    }

    private String bodyOf(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private static List<JsonNode> arrayOf(JsonNode body, String field) {
        JsonNode array = body.get(field);
        assertThat(array.isArray()).as("%s must be a JSON array", field).isTrue();
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private static JsonNode byTitle(List<JsonNode> entries, String title) {
        return entries.stream()
                .filter(node -> node.get("title").asText().equals(title))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no entry titled '" + title + "' — the response carried "
                                + titlesOf(entries)));
    }

    private static List<String> titlesOf(List<JsonNode> nodes) {
        return textsOf(nodes, "title");
    }

    private static List<String> textsOf(Iterable<JsonNode> nodes, String field) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.get(field).asText()));
        return values;
    }

    private static List<String> textsIn(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    // =====================================================================
    // helpers — fixture, Spring Data side
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

    private void grantOnCompany(
            String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    // =====================================================================
    // The flake half
    // =====================================================================

    @Nested
    @DisplayName("The flake half — three ways to have no number, and they are not the same")
    class Flake {

        @Test
        @DisplayName("a case the recompute never reached serves flake: null, and the KEY is present")
        void noRowIsAnExplicitNull() throws Exception {
            JsonNode body = detailAs(LEAD, ACME, ACME_BANKING, caseId("bare"));

            assertThat(body.has("flake"))
                    .as("`flake` is required by openapi.yaml — an absent key reads as a field the "
                            + "server forgot, which is TestCaseSummary.suiteId's rule one field over")
                    .isTrue();
            assertThat(body.get("flake").isNull()).isTrue();
        }

        @Test
        @DisplayName("a scored row carries the counts, the score and the cells")
        void aScoredRowIsServed() throws Exception {
            JsonNode flake = detailAs(LEAD, ACME, ACME_BANKING, caseId("full")).get("flake");

            assertThat(flake.get("caseId").asText()).isEqualTo(caseId("full").toString());
            assertThat(flake.get("executions").asInt()).isEqualTo(12);
            assertThat(flake.get("transitions").asInt()).isEqualTo(9);
            assertThat(flake.get("alternations").asInt()).isEqualTo(4);
            assertThat(flake.get("cells").asInt()).isEqualTo(2);
            assertThat(flake.get("flakeScore").asDouble()).isEqualTo(0.4444);
            assertThat(flake.get("computedAt").isNull())
                    .as("what tells 'not flaky' from 'nobody has looked at it'")
                    .isFalse();
        }

        @Test
        @DisplayName("NULL SCORE IS NOT ZERO — the counts ship so the panel can say which")
        void theNullScoreIsNotAZero() throws Exception {
            // The one assertion this whole field exists for. `flakeScore: null` (a row below the
            // evidence floor) and `flakeScore: 0` (a test measured 38 times and evidently stable)
            // are opposite claims, and `score ?? 0` type-checks in every language on both sides.
            JsonNode unscored = detailAs(LEAD, ACME, ACME_BANKING, caseId("gherkin")).get("flake");
            JsonNode stable = detailAs(LEAD, ACME, ACME_BANKING, caseId("bystander")).get("flake");

            assertThat(unscored.isNull())
                    .as("a row EXISTS — this is not the never-scored state")
                    .isFalse();
            assertThat(unscored.get("flakeScore").isNull()).isTrue();
            assertThat(unscored.get("executions").asInt())
                    .as("the counts are what let a client render 'not enough to score' rather "
                            + "than an unexplained blank")
                    .isEqualTo(3);
            assertThat(unscored.get("transitions").asInt()).isEqualTo(2);

            assertThat(stable.get("flakeScore").isNull()).isFalse();
            assertThat(stable.get("flakeScore").asDouble()).isZero();
            assertThat(stable.get("transitions").asInt()).isEqualTo(38);
        }

        @Test
        @DisplayName("the decider is a UserRef and is named through THIS company's membership")
        void theDeciderIsNamed() throws Exception {
            // A bare uuid until this slice, and the only actor field on the API that was one. The
            // panel's sentence is "quarantined by <somebody>", so an id makes the record present
            // and unreviewable — see CaseFlakeView.
            JsonNode by = detailAs(LEAD, ACME, ACME_BANKING, caseId("full"))
                    .get("flake").get("quarantinedBy");

            assertThat(by.get("id").asText()).isEqualTo(LEAD.toString());
            assertThat(by.get("displayName").asText())
                    .as("plan §6u — the membership label, not the account's canonical name")
                    .isEqualTo("L. Lead of Acme");
        }

        @Test
        @DisplayName("a released row names nobody and keeps no reason")
        void releasedRowsCarryNoDecision() throws Exception {
            JsonNode flake = detailAs(LEAD, ACME, ACME_BANKING, caseId("bystander")).get("flake");

            assertThat(flake.get("quarantined").asBoolean()).isFalse();
            assertThat(flake.get("quarantinedBy").isNull()).isTrue();
            assertThat(flake.get("quarantinedAt").isNull()).isTrue();
            assertThat(flake.get("quarantineReason").isNull()).isTrue();
        }

        @Test
        @DisplayName("the GRID does not carry it — detail only, deliberately")
        void theListIsNotWidened() throws Exception {
            // `classificationValueIds`' rule one field over: a per-row join on the hottest read in
            // the product, for a column no grid draws. If a flake column is ever wanted on the
            // list, that is a decision and this test is where it gets made.
            String body = mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/cases")
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                            .accept(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString();

            JsonNode rows = json.readTree(body).get("items");
            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(row -> assertThat(row.has("flake")).isFalse());
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("case-detail:" + marker).getBytes());
    }

    private static UUID caseId(String marker) {
        return UUID.nameUUIDFromBytes(("case-detail-case:" + marker).getBytes());
    }

    private static UUID stepId(String marker) {
        return UUID.nameUUIDFromBytes(("case-detail-step:" + marker).getBytes());
    }

    /**
     * Three cases with a {@code case_flake} row and one deliberately without.
     *
     * <p>The four states this fixture holds apart are the whole point of the field, and three of
     * them render as "no number" if anything collapses them: <strong>no row</strong> (nobody has
     * looked at this test), <strong>a row with a null score</strong> (looked at, not enough evidence
     * to say), <strong>a score of zero</strong> (looked at, evidently stable), and a real score.
     */
    private void seedFlake() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // Scored AND quarantined — the only row here that names a decider.
            flake(connection, caseId("full"), ACME_BANKING, 12, 9, 4, "0.4444", true, LEAD,
                    "Flaky on the payment gateway stub");

            // Below the evidence floor: a row exists, the recompute ran, and the score is NULL
            // because two transitions cannot tell a flaky test from an unlucky one. The counts are
            // what let a panel say so instead of rendering a blank.
            flake(connection, caseId("gherkin"), ACME_BANKING, 3, 2, 1, null, false, null, null);

            // Evidently stable — the opposite claim from the row above, and the one a `score ?? 0`
            // would make them both.
            flake(connection, caseId("bystander"), ACME_BANKING, 40, 38, 0, "0.0000", false, null,
                    null);

            // caseId("bare") deliberately gets NO ROW.
        }
    }

    private void flake(Connection connection, UUID caseId, UUID projectId, int executions,
            int transitions, int alternations, String score, boolean quarantined, UUID decider,
            String reason) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_flake (case_id, company_id, project_id, executions, transitions,
                                        alternations, cells, flake_score, computed_at,
                                        quarantined, quarantined_at, quarantined_by,
                                        quarantine_reason)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, 2, ?::numeric, now(),
                        ?, CASE WHEN ? THEN now() END, ?::uuid, ?)
                ON CONFLICT (case_id) DO NOTHING
                """)) {
            statement.setString(1, caseId.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setInt(4, executions);
            statement.setInt(5, transitions);
            statement.setInt(6, alternations);
            statement.setString(7, score);
            statement.setBoolean(8, quarantined);
            statement.setBoolean(9, quarantined);
            statement.setString(10, decider == null ? null : decider.toString());
            statement.setString(11, reason);
            statement.executeUpdate();
        }
    }

    private static UUID attachmentId(String marker) {
        return UUID.nameUUIDFromBytes(("case-detail-attachment:" + marker).getBytes());
    }

    // =====================================================================
    // helpers — fixture, raw SQL through the ORDINARY APP ROLE
    //
    // There is no authoring endpoint for any of this, so a fixture is the only way to get rows
    // here. The app role rather than the superuser on purpose: it is the same policed path
    // production reads through, so a fixture that could not be written under RLS is one that is
    // lying about what the endpoint will see.
    //
    // Every nullable parameter is bound through an explicit CAST: setObject(i, null) leaves pgjdbc
    // with no type to infer and fails with "could not determine data type of parameter", a
    // fixture-only failure that reads like a schema problem.
    // =====================================================================

    private void seedVocabularies() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            call(connection, "SELECT tcms_seed_company_defaults(CAST(? AS uuid))", ACME);
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
        }
        try (Connection connection = appConnection(UMBRA)) {
            call(connection, "SELECT tcms_seed_company_defaults(CAST(? AS uuid))", UMBRA);
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
        }
    }

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
            suite(connection, REPORTING, ACME, ACME_BANKING, null, "TS-7", 7,
                    "Reporting", "Reporting", false);
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-6", 6,
                    "Payments", "Payments", false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1,
                    "Payments", "Payments", false);
        }
    }

    /**
     * The cases.
     *
     * <p>{@code full} sits at the <em>bottom</em> of the three-deep chain, which is what gives the
     * ancestry walk somewhere to go wrong. {@code bare} is the opposite fixture and is the only one
     * that can prove the empty shapes. {@code orphan} is live inside a soft-deleted folder — the
     * one reachable case whose chain is empty.
     */
    private void seedCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            testCase(connection, caseId("full"), ACME_BANKING, PARTIAL, "TC-1", 1,
                    "Full refund to card",
                    "Refunds the whole captured amount to the original card.",
                    "Prove a merchant can recover funds without manual intervention.",
                    "The payment reaches REFUNDED and the refundable balance is zero.",
                    "STRUCTURED", null, "CRITICAL", "HIGH", 240, "APPROVED", "AUTOMATED",
                    "com.example.payments.RefundIT#fullRefund", LEAD, 7, 3, false);

            testCase(connection, caseId("gherkin"), ACME_BANKING, REFUNDS, "TC-2", 2,
                    "Partial refund rounding", null, null, null,
                    "GHERKIN",
                    "Feature: Refunds\n\n  Scenario: Partial refund rounding\n"
                            + "    Given a settled payment of 10.01 EUR\n"
                            + "    When the merchant refunds half of it\n"
                            + "    Then the refunded amount rounds to 5.01 EUR",
                    "MEDIUM", null, null, "DRAFT", "NOT_AUTOMATABLE", null, LEAD, 1, 0, false);

            // Nothing authored beyond the title: no owner, no severity, no objective, no steps,
            // no conditions, no tags. The state of every case created and filled in later — and it
            // lives in a folder that attaches nothing, because a case whose FOLDER carries a
            // standing rule is not unauthored no matter how empty the case itself is.
            testCase(connection, caseId("bare"), ACME_BANKING, REPORTING, "TC-3", 3,
                    "Guest checkout", null, null, null,
                    "STRUCTURED", null, "MEDIUM", null, null, "DRAFT", "MANUAL", null, null,
                    1, 0, false);

            // Inherits from Checkout, where "Confirm the terms" is attached a second time — the
            // case that proves an opt-out binds to one attachment rather than to the condition.
            testCase(connection, caseId("bystander"), ACME_BANKING, CHECKOUT, "TC-4", 4,
                    "Checkout with saved card", null, null, null,
                    "STRUCTURED", null, "LOW", null, null, "DRAFT", "MANUAL", null, null,
                    1, 0, false);

            testCase(connection, caseId("deleted"), ACME_BANKING, REFUNDS, "TC-5", 5,
                    "Withdrawn case", null, null, null,
                    "STRUCTURED", null, "LOW", null, null, "DRAFT", "MANUAL", null, null,
                    1, 0, true);

            testCase(connection, caseId("orphan"), ACME_BANKING, RETIRED_SUITE, "TC-6", 6,
                    "Case in a retired folder", null, null, null,
                    "STRUCTURED", null, "HIGH", null, null, "APPROVED", "MANUAL", null, null,
                    1, 0, false);

            testCase(connection, caseId("mobile"), ACME_MOBILE, MOBILE_SUITE, "TC-20", 20,
                    "Mobile login", null, null, null,
                    "STRUCTURED", null, "HIGH", null, null, "APPROVED", "MANUAL", null, null,
                    1, 0, false);
        }
        try (Connection connection = appConnection(UMBRA)) {
            // TC-1 again: keys are numbered per COMPANY, so the collision is the product working as
            // designed rather than a fixture cheat.
            testCase(connection, caseId("umbra"), UMBRA_BANKING, UMBRA_SUITE, "TC-1", 1,
                    "Full refund to card", null, null, null,
                    "STRUCTURED", null, "CRITICAL", null, null, "APPROVED", "MANUAL", null, null,
                    1, 0, false);
        }
    }

    private void seedLibrary() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            condition(connection, BANNER, ACME_BANKING, "Accept the cookie banner", "PRE",
                    "[\"Dismiss the consent dialog\"]", false);
            condition(connection, LOGIN, ACME_BANKING, "Log in as standard user", "PRE",
                    "[\"Open the application\", \"Enter valid credentials\"]", false);
            condition(connection, DECLINED, ACME_BANKING, "Confirm the terms and conditions", "PRE",
                    "[\"Tick the acceptance box\"]", false);
            condition(connection, SETTLED, ACME_BANKING, "Seed a settled payment", "PRE",
                    "[\"Authorise 120.00 EUR\", \"Capture it\"]", false);
            // BOTH: attached as a PRE up the tree and as this case's own POST. The entry the
            // separate-dedupe assertion turns on.
            condition(connection, RESET, ACME_BANKING, "Reset the test payment data", "BOTH",
                    "[\"Void any pending authorisations\"]", false);
            condition(connection, CLEANUP, ACME_BANKING, "Tidy up the merchant account", "POST",
                    "[\"Remove the test merchant\"]", false);
            condition(connection, RETIRED_CONDITION, ACME_BANKING, "Log in as the old admin", "PRE",
                    "[\"Open the legacy console\"]", true);
            condition(connection, OWN_ONLY, ACME_BANKING, "Check the merchant is verified", "PRE",
                    "[\"Open the merchant record\"]", false);
        }
    }

    /**
     * The attachments — the fixture the resolution order is actually tested against.
     *
     * <p><strong>Positions disagree with insert order on purpose.</strong> At {@code Payments},
     * "Log in" is written first and sits at position 1 while "Accept the cookie banner" is written
     * second and sits at position 0, so a query that dropped {@code ORDER BY position} would come
     * back in the other order rather than in the right one by luck.
     */
    private void seedAttachments() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            /*
              §98 COLLAPSED THREE SUITES INTO ONE HERE, AND ALMOST NOTHING WAS LOST.

              These attachments used to hang off a three-level chain — Payments › Refunds ›
              Partial refunds — because inheritance walked `suite.path` upward and the list was
              asserted OUTERMOST-FIRST. There is no chain now: a suite's conditions reach the cases
              FILED in it and nothing else, so every attachment moves onto PARTIAL, the case's own
              suite.

              What that costs is exactly one rule — the between-suite ordering. Everything else the
              class was written for is a rule WITHIN one suite or between the suite and the case,
              and all of it still bites: `position` order (the entries are inserted in the wrong
              order on purpose, so a query dropping ORDER BY comes back wrong rather than right by
              luck), dedupe against the case's own attachments with the inherited copy winning,
              opt-outs keyed on the attachment, retired entries dropping out, inline attachments
              excluded, and the two kinds deduping separately.
            */
            suiteCondition(connection, attachmentId("payments-login"), PARTIAL, "PRE", 1, LOGIN);
            suiteCondition(connection, attachmentId("payments-banner"), PARTIAL, "PRE", 0, BANNER);
            suiteCondition(connection, DECLINED_ATTACHMENT, PARTIAL, "PRE", 2, DECLINED);
            suiteCondition(connection, attachmentId("payments-cleanup"), PARTIAL, "POST", 0,
                    CLEANUP);
            // Inline: legal in the schema, unrepresentable in the contract, excluded at the query.
            inlineSuiteCondition(connection, attachmentId("payments-inline"), PARTIAL, "PRE", 3);

            suiteCondition(connection, attachmentId("refunds-settled"), PARTIAL, "PRE", 4, SETTLED);
            // "Log in" a second time on the SAME suite — still deduped away, and the surviving row
            // is the earlier position, which is the one the assertions name.
            suiteCondition(connection, attachmentId("refunds-login"), PARTIAL, "PRE", 5, LOGIN);

            suiteCondition(connection, attachmentId("partial-retired"), PARTIAL, "PRE", 6,
                    RETIRED_CONDITION);
            suiteCondition(connection, attachmentId("partial-reset"), PARTIAL, "PRE", 7, RESET);

            // Checkout — the SECOND attachment of the condition `full` opted out of, inherited by
            // `bystander`, which must keep it.
            suiteCondition(connection, attachmentId("checkout-declined"), CHECKOUT, "PRE", 0,
                    DECLINED);

            // The case's own attachments.
            caseCondition(connection, attachmentId("full-settled"), caseId("full"), "PRE", 0,
                    SETTLED);
            caseCondition(connection, attachmentId("full-own"), caseId("full"), "PRE", 1, OWN_ONLY);
            caseCondition(connection, attachmentId("full-reset"), caseId("full"), "POST", 0, RESET);
            inlineCaseCondition(connection, attachmentId("full-inline"), caseId("full"), "PRE", 2);

            optOut(connection, caseId("full"), DECLINED_ATTACHMENT);
        }
    }

    private void seedStepsTagsAndClassification() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            // Written 3, 1, 2. Postgres returns a small table in physical order, so a fixture
            // written in position order cannot fail when the ORDER BY is dropped.
            step(connection, stepId("full-3"), caseId("full"), 3, null,
                    "Confirm the balance is zero", null, "The refundable balance reads 0.00 EUR");
            step(connection, stepId("full-1"), caseId("full"), 1, null,
                    "Open the settled payment", "card ending 4242",
                    "The payment is listed as settled");
            step(connection, stepId("full-2"), caseId("full"), 2, null,
                    "Request a full refund", "120.00 EUR", "The refund is accepted");

            step(connection, stepId("gherkin-1"), caseId("gherkin"), 1, "GIVEN",
                    "a settled payment of 10.01 EUR", null, null);
            step(connection, stepId("gherkin-2"), caseId("gherkin"), 2, "WHEN",
                    "the merchant refunds half of it", null, null);
            step(connection, stepId("gherkin-3"), caseId("gherkin"), 3, "THEN",
                    "the refunded amount rounds to 5.01 EUR", null, null);

            // @smoke first, so the alphabetical order is not the insert order.
            automationTag(connection, caseId("full"), "@smoke");
            automationTag(connection, caseId("full"), "@regression");
            tag(connection, caseId("full"), "release-4.2");
        }
    }

    private void call(Connection connection, String sql, UUID argument) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, argument.toString());
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

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path, boolean deleted)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED', CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setString(7, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void testCase(
            Connection connection, UUID id, UUID projectId, UUID suiteId, String key, long keySeq,
            String title, String description, String objective, String expectedOutcome,
            String stepFormat, String gherkinSource, String priority, String severity,
            Integer estimatedDurationSec, String lifecycle, String automation, String automationKey,
            UUID ownerId, int currentVersionNo, long lockVersion, boolean deleted)
            throws SQLException {

        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, description,
                     objective, expected_outcome, step_format, gherkin_source, priority, severity,
                     estimated_duration_sec, lifecycle_status, automation_status, automation_key,
                     not_automatable_reason, owner_id, current_version_no, lock_version,
                     updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, CAST(? AS text), CAST(? AS text), CAST(? AS text), ?, CAST(? AS text),
                        ?, CAST(? AS text), CAST(? AS int), ?, ?, CAST(? AS text), CAST(? AS text),
                        CAST(? AS uuid), ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz))
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
            statement.setString(9, objective);
            statement.setString(10, expectedOutcome);
            statement.setString(11, stepFormat);
            statement.setString(12, gherkinSource);
            statement.setString(13, priority);
            statement.setString(14, severity);
            statement.setObject(15, estimatedDurationSec, java.sql.Types.INTEGER);
            statement.setString(16, lifecycle);
            statement.setString(17, automation);
            statement.setString(18, automationKey);
            // ck_not_automatable_reason refuses the row without one.
            statement.setString(19, "NOT_AUTOMATABLE".equals(automation)
                    ? "Requires a bank sandbox submission that cannot be triggered." : null);
            statement.setString(20, ownerId == null ? null : ownerId.toString());
            statement.setInt(21, currentVersionNo);
            statement.setLong(22, lockVersion);
            // A fixed instant rather than now(): deterministic, and updatedAt is asserted only for
            // presence.
            statement.setString(23, "2026-01-01T00:00:00Z");
            statement.setString(24, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void condition(
            Connection connection, UUID id, UUID projectId, String title, String type, String body,
            boolean retired) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, type, body, usage_count, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS jsonb), 0, CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, title);
            statement.setString(5, type);
            statement.setString(6, body);
            statement.setString(7, retired ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void suiteCondition(
            Connection connection, UUID id, UUID suiteId, String kind, int position,
            UUID conditionId) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite_condition
                    (id, company_id, suite_id, kind, position, condition_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS uuid))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, suiteId.toString());
            statement.setString(4, kind);
            statement.setInt(5, position);
            statement.setString(6, conditionId.toString());
            statement.executeUpdate();
        }
    }

    /** {@code ck_suite_condition_source}: an inline row has a body and no condition id. */
    private void inlineSuiteCondition(
            Connection connection, UUID id, UUID suiteId, String kind, int position)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite_condition
                    (id, company_id, suite_id, kind, position, inline_body)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, suiteId.toString());
            statement.setString(4, kind);
            statement.setInt(5, position);
            statement.setString(6, "[\"An ad-hoc step nobody gave a title\"]");
            statement.executeUpdate();
        }
    }

    private void caseCondition(
            Connection connection, UUID id, UUID caseId, String kind, int position,
            UUID conditionId) throws SQLException {

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

    private void inlineCaseCondition(
            Connection connection, UUID id, UUID caseId, String kind, int position)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_condition
                    (id, company_id, case_id, kind, position, inline_body)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, CAST(? AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.setString(4, kind);
            statement.setInt(5, position);
            statement.setString(6, "[\"Another untitled ad-hoc step\"]");
            statement.executeUpdate();
        }
    }

    private void optOut(Connection connection, UUID caseId, UUID suiteConditionId)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO case_condition_optout (company_id, case_id, suite_condition_id)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                ON CONFLICT (case_id, suite_condition_id) DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, suiteConditionId.toString());
            statement.executeUpdate();
        }
    }

    private void step(
            Connection connection, UUID id, UUID caseId, int position, String keyword,
            String action, String testData, String expectedResult) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_step
                    (id, company_id, case_id, position, keyword, action, test_data, expected_result)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS text), ?,
                        CAST(? AS text), CAST(? AS text))
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
