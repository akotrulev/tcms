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
 * {@code GET /projects/{projectId}/suites} — the suite tree.
 *
 * <h2>The three failures this class exists to catch, none of them dramatic</h2>
 *
 * <ol>
 *   <li><strong>A rolled-up {@code caseCount}.</strong> Counting a suite's whole subtree instead of
 *       its own cases answers 200 with plausible numbers, and the SPA — which adds each node's
 *       count to its children's — then reports roughly double at every level. Nothing fails, and
 *       the number is wrong on the first screen after the picker.</li>
 *   <li><strong>An endpoint gated on the company only.</strong> Every cross-company test in the
 *       suite still passes, because the rows genuinely are in the caller's own company, and a
 *       member granted nothing gets the client's entire test tree — a folder-by-folder sketch of
 *       what they are building. Persona 3, asserted here rather than assumed from the shared
 *       gate.</li>
 *   <li><strong>A flat list ordered so a child precedes its parent.</strong> Ordering by
 *       {@code position} — the obvious column — interleaves depths, because position is sibling
 *       order within one parent. A consumer building the tree in one pass then drops nodes.</li>
 * </ol>
 *
 * <h2>The fixture is deliberately hostile</h2>
 *
 * <p>Two companies whose suites carry <strong>the same names and the same display keys</strong>, so
 * only ids can prove the scoping. Two projects in one company, so a sibling tree exists to leak in
 * from. Three suites that were a three-level tree until §98 flattened them — the nesting is gone
 * about. A soft-deleted suite, a suite whose cases are all soft-deleted, and an empty parent whose
 * children hold the cases. Plus three condition attachments — one live reference, one inline, one
 * pointing at a retired library entry — because the two exclusions are decisions and an
 * undocumented decision is indistinguishable from a bug.
 *
 * <p>Company and user ids are unique to this class. Sharing them with another test class is how
 * handoff §19's first defect happened — green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Suite tree endpoint — behind the shared project gate")
class SuiteEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("5a000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("5a000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD. Carries the three-level tree and every condition attachment. */
    private static final UUID ACME_BANKING = UUID.fromString("5a001000-0000-4000-8000-000000000001");
    /** Same company, NOT granted to LEAD. Its suites must never appear in BANKING's tree. */
    private static final UUID ACME_MOBILE = UUID.fromString("5a001000-0000-4000-8000-000000000002");
    /** The other client's project, with identically named and identically keyed suites. */
    private static final UUID UMBRA_BANKING = UUID.fromString("5a002000-0000-4000-8000-000000000001");
    /** Granted to ADMIN through their COMPANY-scope role, and deliberately left with no suites. */
    private static final UUID ACME_EMPTY = UUID.fromString("5a001000-0000-4000-8000-000000000003");

    private static final UUID LEAD = UUID.fromString("5a003000-0000-4000-8000-000000000001");
    /** ACTIVE member of Acme holding nothing at all. Persona 3. */
    private static final UUID NO_GRANT = UUID.fromString("5a003000-0000-4000-8000-000000000002");
    /** Company Admin in Acme — reaches every project through a grant with no project id. */
    private static final UUID ADMIN = UUID.fromString("5a003000-0000-4000-8000-000000000003");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("5a003000-0000-4000-8000-000000000004");

    // The Acme banking tree. Payments is an empty parent on purpose: its cases live in its
    // children, which is exactly the arrangement a subtree count gets wrong.
    private static final UUID PAYMENTS = UUID.fromString("5a004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("5a004000-0000-4000-8000-000000000002");
    private static final UUID PARTIAL = UUID.fromString("5a004000-0000-4000-8000-000000000003");
    private static final UUID CHECKOUT = UUID.fromString("5a004000-0000-4000-8000-000000000004");
    /** Soft-deleted. Its cases are already gone from the case list; the folder must be too. */
    private static final UUID RETIRED = UUID.fromString("5a004000-0000-4000-8000-000000000005");
    /** The sibling project's identically named suite — only {@code project_id} separates it. */
    private static final UUID MOBILE_PAYMENTS =
            UUID.fromString("5a004000-0000-4000-8000-000000000011");

    private static final UUID LIVE_CONDITION = UUID.fromString("5a005000-0000-4000-8000-00000000000a");
    private static final UUID RETIRED_CONDITION =
            UUID.fromString("5a005000-0000-4000-8000-00000000000b");

    /** Refunds' cleanup attachment — the same library entry as its setup one, in the other list. */
    private static final UUID POST_ATTACHMENT =
            UUID.fromString("5a006000-0000-4000-8000-000000000004");

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
    private SuiteConditionRepository attachments;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "suite-acme", "Acme", now);
            company(UMBRA, "suite-umbra", "Umbra", now);

            activeUser(LEAD, "lead@suite-acme.test", now);
            activeUser(NO_GRANT, "nogrant@suite-acme.test", now);
            activeUser(ADMIN, "admin@suite-acme.test", now);
            activeUser(OUTSIDER, "engineer@suite-umbra.test", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "SAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "SAMOB", "Mobile App", now);
            seedProject(ACME_EMPTY, ACME, "SANEW", "Just Provisioned", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("suite-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnCompany("suite-admin", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "SUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Engineer", now);
            grantOnProject("suite-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedAcmeBankingTree();
        seedSiblingAndForeignTrees();
        seedConditions();
    }

    // =====================================================================
    // The tree itself
    // =====================================================================

    @Nested
    @DisplayName("The tree — flat, ordered so it can be built in one pass")
    class Tree {

        @Test
        @DisplayName("every live suite in the project comes back, and only those")
        void liveSuitesOnly() throws Exception {
            assertThat(namesOf(treeAs(LEAD, ACME, ACME_BANKING)))
                    .containsExactlyInAnyOrder(
                            "Payments", "Refunds", "Partial refunds", "Checkout")
                    .doesNotContain("Retired folder");
        }

        @Test
        @DisplayName("a soft-deleted suite is gone from the response, not marked in it")
        void deletedSuitesAreExcluded() throws Exception {
            // Soft-deleted because past runs reference the cases beneath it. Included, the tree
            // would show a folder whose cases have already vanished from the grid — which reads
            // as data loss rather than as a stale row.
            assertThat(idsOf(treeAs(LEAD, ACME, ACME_BANKING)))
                    .doesNotContain(RETIRED.toString());
        }

        @Test
        @DisplayName("the order is total and stable, so the rail cannot reshuffle between loads")
        void orderingIsTotalAndStable() throws Exception {
            /*
              §98 replaced what this asserted, and the replacement is a weaker claim about a
              stronger guarantee.

              It used to be "a parent always precedes its own descendants", which is what made a
              flat payload CONSTRUCTIBLE into a tree — the query ordered by `path`, an ltree that
              compares element-wise, where ordering by `position` (sibling order WITHIN one parent)
              interleaves depths and lets a child arrive before the parent it hangs off. Every node
              in this fixture has position 0, so a position-ordered query returned an arbitrary
              order and that assertion was what noticed.

              There is no tree to construct now. What an order still has to be is total and stable,
              because the client sorts by NAME for display and two suites may share a name — so the
              server's order is the tiebreaker underneath it. `key_seq` is unique per company and
              monotonic, which is why the query orders on it; asserting the ids come back in
              key_seq order is what fails if it goes back to `position`, where every row is 0 and
              Postgres may return them in any order at all.
            */
            List<JsonNode> suites = treeAs(LEAD, ACME, ACME_BANKING);

            List<String> keys = suites.stream().map(node -> node.get("key").asText()).toList();
            assertThat(keys)
                    .as("ordered by key_seq — a total order, where `position` is 0 on every row")
                    .isSorted();
            assertThat(keys).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("no suite carries a parent or a path any more")
        void theTreeIsGone() throws Exception {
            /*
              The inverse of two tests §98 deleted: `theTreeIsReconstructible` (a root's `parentId`
              was null EXPLICITLY rather than by omission, because the SPA bucketed on
              `parentId ?? null`) and `pathIsCarriedAndIsNotTheName` (the path was the materialized
              ltree and legitimately disagreed with the display name, since labels admit only
              [A-Za-z0-9_]).

              Asserting their absence is not bookkeeping. Both fields were dropped from the wire
              while `V33` dropped the columns, and a serializer that re-added either — a leftover
              getter, a @JsonProperty, a mapper that reached for the entity — would be serving a
              field no client reads and no column backs.
            */
            for (JsonNode suite : treeAs(LEAD, ACME, ACME_BANKING)) {
                assertThat(suite.has("parentId"))
                        .as("%s still carries parentId", suite.get("key").asText())
                        .isFalse();
                assertThat(suite.has("path"))
                        .as("%s still carries path", suite.get("key").asText())
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a project with no suites answers [] and 200, not 404")
        void anEmptyProjectIsNotAMissingOne() throws Exception {
            // The state of EVERY freshly provisioned project: tcms_seed_project_defaults seeds a
            // configuration and the statuses, deliberately not a folder. Collapsing "no suites"
            // into the 404 the gate uses would make the first screen after the picker unreachable
            // for exactly the project a new client starts with.
            assertThat(statusOf(ADMIN, ACME, ACME_EMPTY)).isEqualTo(200);
            assertThat(treeAs(ADMIN, ACME, ACME_EMPTY)).isEmpty();
        }

        @Test
        @DisplayName("every field the contract marks required is present")
        void requiredFieldsArePresent() throws Exception {
            JsonNode suite = byName(treeAs(LEAD, ACME, ACME_BANKING), "Refunds");

            assertThat(List.of("id", "key", "name", "caseCount", "filedCount", "addedCount",
                    "taggedCount", "lifecycleStatus",
                    "preconditions", "postconditions", "lockVersion"))
                    .allSatisfy(field -> assertThat(suite.has(field))
                            .as("Suite.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(suite.get("lifecycleStatus").asText()).isEqualTo("APPROVED");
            assertThat(suite.get("key").asText()).isEqualTo("TS-2");
        }

        @Test
        @DisplayName("companyId and projectId never appear")
        void tenancyFieldsAreNeverExposed() throws Exception {
            // companyId is the field a client has no business seeing (ERD §0.1 point 2), and
            // projectId is the one a future author would try to SEND in order to pick a scope.
            for (JsonNode suite : treeAs(LEAD, ACME, ACME_BANKING)) {
                assertThat(suite.has("companyId")).isFalse();
                assertThat(suite.has("projectId")).isFalse();
            }
        }
    }

    // =====================================================================
    // caseCount — the number that is wrong in a way nothing fails over
    // =====================================================================

    @Nested
    @DisplayName("caseCount — the suite's own live cases")
    class CaseCounts {

        @Test
        @DisplayName("a parent counts only its own cases, never its subtree's")
        void countsAreDirectNotRolledUp() throws Exception {
            // Payments holds no cases FILED in it; its children hold 3 and 2. A subtree count
            // would report 5 more than it does here, the SPA would add the children's counts to
            // it, and the tree would claim ten-odd cases in a project that has five. Both numbers
            // are plausible, which is why this is asserted rather than eyeballed.
            //
            // The 2 is §96's other two arms — one case added individually, one matched by the
            // folder's tag rule — and it is deliberately NOT zero any more: a rollup would make it
            // 7, which is what this test discriminates. The Membership section asserts what the 2
            // is made of.
            List<JsonNode> tree = treeAs(LEAD, ACME, ACME_BANKING);

            assertThat(byName(tree, "Payments").get("caseCount").asLong()).isEqualTo(2);
            assertThat(byName(tree, "Refunds").get("caseCount").asLong()).isEqualTo(3);
            assertThat(byName(tree, "Partial refunds").get("caseCount").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("a soft-deleted case is not counted")
        void deletedCasesAreExcluded() throws Exception {
            // Checkout has three rows and all three are deleted, so it must report 0 rather than
            // 3 — and the GROUP BY returns no row for it at all, which is the "absent means zero"
            // path the default exists for.
            JsonNode checkout = byName(treeAs(LEAD, ACME, ACME_BANKING), "Checkout");

            assertThat(checkout.get("caseCount").isNull())
                    .as("absent from a GROUP BY must render as 0, not as null — the contract "
                            + "marks caseCount required")
                    .isFalse();
            assertThat(checkout.get("caseCount").asLong()).isZero();
        }

        @Test
        @DisplayName("cases in another project's suites do not leak into the count")
        void countsDoNotCrossProjects() throws Exception {
            // ACME_MOBILE's suite carries cases too. Both projects are in the caller's own
            // company, so the company predicate admits both sets and only project_id separates
            // them.
            assertThat(byName(treeAs(ADMIN, ACME, ACME_MOBILE), "Payments").get("caseCount")
                    .asLong())
                    .as("the sibling really is populated — otherwise the assertion above is "
                            + "vacuous")
                    .isEqualTo(4);
        }
    }


    // =====================================================================
    // Membership — three arms, unioned and deduped (§96)
    // =====================================================================

    @Nested
    @DisplayName("Membership — a suite resolves rather than merely holds")
    class Membership {

        @Test
        @DisplayName("the count is filed PLUS added PLUS tagged, deduped")
        void theCountIsTheWholeUnion() throws Exception {
            // Payments has NOTHING filed in it. Its two members are one case added individually
            // and one matched by its `smoke` rule — and the added one is tagged too, so a
            // COUNT(*) over the union answers 3 where the deduped count answers 2. That pair is
            // what separates "unioned" from "unioned and deduped"; every other assertion here
            // passes under both.
            List<JsonNode> tree = treeAs(LEAD, ACME, ACME_BANKING);

            assertThat(byName(tree, "Payments").get("caseCount").asLong()).isEqualTo(2);
            assertThat(byName(tree, "Refunds").get("caseCount").asLong())
                    .as("the filed arm is unchanged — being claimed by another suite does not "
                            + "remove a case from the folder it is filed in")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("the three arms are counted separately, and they do NOT sum to caseCount")
        void theArmsAreCountedSeparately() throws Exception {
            // 005 S1. `caseCount` cannot explain itself: a suite reading "2 cases" with no way to
            // see which arm they arrive through is a collection nobody can reason about.
            List<JsonNode> tree = treeAs(LEAD, ACME, ACME_BANKING);
            JsonNode payments = byName(tree, "Payments");

            assertThat(payments.get("filedCount").asLong())
                    .as("Payments has nothing FILED in it — that is the fixture's whole point")
                    .isZero();
            assertThat(payments.get("addedCount").asLong())
                    .as("addedCount IS the length of caseIds, by definition — deriving it any "
                            + "other way would be two answers to one question on one screen")
                    .isEqualTo(payments.get("caseIds").size())
                    .isEqualTo(1);
            assertThat(payments.get("taggedCount").asLong()).isEqualTo(2);

            assertThat(payments.get("filedCount").asLong()
                            + payments.get("addedCount").asLong()
                            + payments.get("taggedCount").asLong())
                    .as("THE assertion: the added case is tagged too, so the arms overlap and "
                            + "3 != 2. A client that renders them as a total renders a number that "
                            + "is wrong for every suite whose arms overlap.")
                    .isEqualTo(3)
                    .isNotEqualTo(payments.get("caseCount").asLong());
        }

        @Test
        @DisplayName("a suite with only filed cases reports them once, in the filed arm alone")
        void theFiledArmStandsAlone() throws Exception {
            JsonNode refunds = byName(treeAs(LEAD, ACME, ACME_BANKING), "Refunds");

            assertThat(refunds.get("filedCount").asLong()).isEqualTo(3);
            assertThat(refunds.get("addedCount").asLong()).isZero();
            assertThat(refunds.get("taggedCount").asLong()).isZero();
        }

        @Test
        @DisplayName("a tag rule does not resurrect a soft-deleted case in the TAGGED arm either")
        void deletedCasesAreNotCountedInTheTaggedArm() throws Exception {
            // The same predicate as the union's, because it IS the union's — the arms are FILTER
            // clauses over one join rather than three queries that would each carry their own copy
            // of "live case in this project".
            assertThat(byName(treeAs(LEAD, ACME, ACME_BANKING), "Checkout").get("taggedCount")
                    .asLong())
                    .isZero();
        }

        @Test
        @DisplayName("a tag rule does not resurrect a soft-deleted case")
        void deletedCasesAreNotMatched() throws Exception {
            // Checkout's rule matches exactly one case and that case is deleted. Without the
            // deleted_at predicate on the tag arm the folder would report 1, and a case the grid
            // refuses to show would be counted on the tree beside it — the contradiction this
            // class's caseCount section exists for, arriving through a new arm.
            assertThat(byName(treeAs(LEAD, ACME, ACME_BANKING), "Checkout").get("caseCount")
                    .asLong())
                    .isZero();
        }

        @Test
        @DisplayName("the rules and the added cases are served, and never null")
        void theArmsAreOnTheWire() throws Exception {
            List<JsonNode> tree = treeAs(LEAD, ACME, ACME_BANKING);
            JsonNode payments = byName(tree, "Payments");

            assertThat(payments.get("tagRules").isArray()).isTrue();
            assertThat(namesIn(payments.get("tagRules"))).containsExactly("smoke");
            assertThat(payments.get("caseIds")).hasSize(1);

            JsonNode refunds = byName(tree, "Refunds");
            assertThat(refunds.get("tagRules").isArray())
                    .as("[] and never null — the contract marks both required, so the SPA cannot "
                            + "null-check them")
                    .isTrue();
            assertThat(refunds.get("tagRules")).isEmpty();
            assertThat(refunds.get("caseIds")).isEmpty();
        }

        @Test
        @DisplayName("a sibling project's case is never resolved into this project's suite")
        void membershipDoesNotCrossProjects() throws Exception {
            // Both projects belong to the caller's company and the sibling's cases are untagged,
            // so this asserts the project predicate on the tag arm rather than the company one —
            // the boundary RLS does NOT enforce.
            assertThat(byName(treeAs(ADMIN, ACME, ACME_MOBILE), "Payments").get("caseCount")
                    .asLong())
                    .isEqualTo(4);
        }
    }

    // =====================================================================
    // Conditions — and the two exclusions that are decisions
    // =====================================================================

    @Nested
    @DisplayName("Conditions — the suite's own, resolved against the live library")
    class Conditions {

        @Test
        @DisplayName("a reference attachment resolves to the library entry's title and body")
        void referencesResolve() throws Exception {
            JsonNode refunds = byName(treeAs(LEAD, ACME, ACME_BANKING), "Refunds");
            JsonNode condition = refunds.get("preconditions").get(0);

            assertThat(refunds.get("preconditions")).hasSize(1);
            assertThat(condition.get("title").asText()).isEqualTo("Log in as standard user");
            assertThat(namesIn(condition.get("body")))
                    .as("the body is an ordered list of actions, not a paragraph — 'log in' is "
                            + "realistically three steps (ERD §4)")
                    .containsExactly("Open the application", "Enter valid credentials");
            assertThat(condition.get("conditionId").asText()).isEqualTo(LIVE_CONDITION.toString());
        }

        @Test
        @DisplayName("the attachment's own id is exposed, not the library condition's")
        void theIdIsTheAttachmentsOwn() throws Exception {
            // Two suites referencing one library condition are two rows, and the SPA keys list
            // items on this id. Exposing the condition id here would produce duplicate React keys
            // the moment the same precondition is attached twice.
            JsonNode condition =
                    byName(treeAs(LEAD, ACME, ACME_BANKING), "Refunds").get("preconditions").get(0);

            assertThat(condition.get("id").asText()).isNotEqualTo(LIVE_CONDITION.toString());
        }

        @Test
        @DisplayName("inherited is false on a suite's own conditions, and inheritedFrom is null")
        void aSuitesOwnConditionsAreNotInherited() throws Exception {
            // The field distinguishes a CASE's own condition from one it picked up from a suite
            // above it. Read on a suite, the inheritance runs downward — so `true` here would be
            // the response claiming this suite got the condition from somewhere else.
            JsonNode condition =
                    byName(treeAs(LEAD, ACME, ACME_BANKING), "Refunds").get("preconditions").get(0);

            assertThat(condition.get("inherited").asBoolean()).isFalse();
            assertThat(condition.get("inheritedFrom").isNull()).isTrue();
            assertThat(condition.get("inline").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("the query itself excludes inline rows, not just the consumer downstream")
        void theExclusionLivesInThePredicate() {
            // Asserted HERE, at the repository, and not only through the endpoint — because
            // through the endpoint it cannot fail. An inline row has a null conditionId, the
            // service resolves it with library.get(null), and HashMap answers null, so the row
            // drops out whether or not the query ever excluded it. Removing the predicate leaves
            // every endpoint assertion green.
            //
            // That is handoff §22's surviving mutation in a new costume: a response that happens
            // to be right cannot distinguish "excluded on purpose" from "silently dropped". The
            // decision is the predicate's, so the test belongs against the predicate.
            // The context is opened AROUND the transaction, not inside it: the transaction
            // manager reads CompanyContext in doBegin to set app.current_company, so a context
            // opened after the transaction started arrives too late and RLS answers zero rows.
            // Fail-closed and silent — the same shape as handoff §15's missing @Transactional.
            List<UUID> attached;
            try (var ignored = CompanyContext.open(ACME)) {
                attached = tx.execute(status ->
                        attachments.findForSuites(List.of(REFUNDS, PARTIAL, CHECKOUT)).stream()
                                .map(SuiteCondition::getId)
                                .toList());
            }

            assertThat(attached)
                    .as("the three reference-backed attachments, and none of them the inline one")
                    .containsExactlyInAnyOrder(
                            UUID.fromString("5a006000-0000-4000-8000-000000000001"),
                            UUID.fromString("5a006000-0000-4000-8000-000000000002"),
                            POST_ATTACHMENT)
                    .doesNotContain(UUID.fromString("5a006000-0000-4000-8000-000000000003"));
        }

        @Test
        @DisplayName("an inline attachment is excluded — it has no title the contract requires")
        void inlineAttachmentsAreExcluded() throws Exception {
            // A DECISION, not an oversight, and this test is where it is visible. suite_condition
            // stores either a condition_id or an inline_body and nothing else, so an inline row
            // has no column a title could come from and CaseConditionInput has no field a writer
            // could send one in — the API cannot describe the row. Inventing a title would be the
            // response making a claim the data never made (§22, §23). Nothing can create one
            // today; this fixture writes it by hand precisely because nothing else can.
            //
            // WHEN THIS TEST FAILS: an inline authoring surface has landed. The fix is not to
            // delete the predicate — it is to decide, in openapi.yaml, whether an inline
            // condition carries a title or whether `title` becomes nullable.
            assertThat(byName(treeAs(LEAD, ACME, ACME_BANKING), "Checkout").get("preconditions"))
                    .isEmpty();
        }

        @Test
        @DisplayName("an attachment to a retired library entry drops out rather than being served")
        void retiredLibraryEntriesAreExcluded() throws Exception {
            // Retiring a library condition is a soft delete because attachments to it survive.
            // Serving it would offer a withdrawn precondition as current — and Partial refunds
            // has exactly one attachment, to exactly that entry.
            JsonNode partial = byName(treeAs(LEAD, ACME, ACME_BANKING), "Partial refunds");

            assertThat(partial.has("preconditions")).isTrue();
            assertThat(partial.get("preconditions").isArray()).isTrue();
            assertThat(partial.get("preconditions")).isEmpty();
            assertThat(partial.get("postconditions")).isEmpty();
        }

        @Test
        @DisplayName("PRE and POST attachments land in different arrays, not one merged list")
        void theTwoKindsAreSeparated() throws Exception {
            // §186's whole contract change, asserted at the only place it is observable. Refunds
            // attaches the SAME library entry twice — once as setup, once as cleanup — which is
            // exactly what a BOTH entry is for and exactly what the single `conditions` array
            // could not express: both rows arrived in one list, in an order decided by `position`,
            // with nothing on the wire saying which was which.
            //
            // WHEN THIS TEST FAILS by both lists holding both rows: the grouping stopped keying on
            // `SuiteCondition.kind` and is bucketing by suite alone.
            JsonNode refunds = byName(treeAs(LEAD, ACME, ACME_BANKING), "Refunds");

            assertThat(refunds.get("preconditions")).hasSize(1);
            assertThat(refunds.get("postconditions")).hasSize(1);
            assertThat(refunds.get("preconditions").get(0).get("id").asText())
                    .isNotEqualTo(POST_ATTACHMENT.toString());
            assertThat(refunds.get("postconditions").get(0).get("id").asText())
                    .isEqualTo(POST_ATTACHMENT.toString());
            assertThat(refunds.get("postconditions").get(0).get("conditionId").asText())
                    .as("the same library entry as the precondition — one procedure, two moments")
                    .isEqualTo(LIVE_CONDITION.toString());
        }

        @Test
        @DisplayName("a suite with neither kind serializes both as [], never as null or absent")
        void anEmptySuiteCarriesAnEmptyArray() throws Exception {
            // The contract marks both required, so the SPA cannot null-check either — an absent
            // key is a crash on the tree rather than an empty list. Both arms matter now that
            // there are two: a suite with setup and no cleanup is the ordinary state, so the
            // empty side is the normal path rather than an edge case.
            JsonNode payments = byName(treeAs(LEAD, ACME, ACME_BANKING), "Payments");

            assertThat(payments.has("preconditions")).isTrue();
            assertThat(payments.get("preconditions").isArray()).isTrue();
            assertThat(payments.get("preconditions")).isEmpty();
            assertThat(payments.has("postconditions")).isTrue();
            assertThat(payments.get("postconditions").isArray()).isTrue();
            assertThat(payments.get("postconditions")).isEmpty();
        }
    }

    // =====================================================================
    // Isolation and the gate
    // =====================================================================

    @Nested
    @DisplayName("Both gates apply, and the trees do not mix")
    class Gates {

        @Test
        @DisplayName("a sibling project's suites do not appear, though the names collide")
        void treesDoNotCrossProjects() throws Exception {
            // ACME_MOBILE owns a suite named "Payments" in the SAME company, so the company
            // filter admits both and only the project predicate separates them. Asserting on the
            // name would pass with either row; the id is what distinguishes them.
            List<String> mobile = idsOf(treeAs(ADMIN, ACME, ACME_MOBILE));

            assertThat(idsOf(treeAs(ADMIN, ACME, ACME_BANKING)))
                    .isNotEmpty()
                    .doesNotContainAnyElementsOf(mobile);
            assertThat(namesOf(treeAs(ADMIN, ACME, ACME_MOBILE)))
                    .as("the sibling really is populated — otherwise 'does not contain' is vacuous")
                    .containsExactly("Payments");
        }

        @Test
        @DisplayName("the other client's identically named and keyed suites are different rows")
        void treesDoNotCrossCompanies() throws Exception {
            List<JsonNode> umbra = treeAs(OUTSIDER, UMBRA, UMBRA_BANKING);

            assertThat(namesOf(umbra))
                    .as("the fixture is only hostile if the other side really does collide")
                    .containsExactly("Payments");
            assertThat(keysOf(umbra))
                    .as("display keys are company-scoped, so TS-1 exists in both companies (§6t)")
                    .containsExactly("TS-1");
            assertThat(idsOf(treeAs(LEAD, ACME, ACME_BANKING)))
                    .doesNotContainAnyElementsOf(idsOf(umbra));
        }

        @Test
        @DisplayName("a member with no grant gets 404, not their client's test tree")
        void membershipConveysNoTree() throws Exception {
            // The forbidden rows are in THIS caller's own company, so no cross-company assertion
            // can reach them. Only the grant gate stands between a member who was granted nothing
            // and a folder-by-folder sketch of what the client is building.
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
                            .get("/api/v1/projects/" + ACME_BANKING + "/suites"))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    /**
     * {@code GET /projects/{projectId}/suites/{suiteId}} — declared since M0, served since §145.
     *
     * <p>The defect it closes was not a wrong answer but <strong>no answer</strong>: the contract
     * declared the operation, both copies of {@code schema.d.ts} generated it, three clients called
     * it, and Spring answered 405 because nothing was mapped. The SPA builds every suite screen from
     * the list, so the one consumer that could have noticed never called it.
     *
     * <p>The detail must answer with the same body the list serves for that suite — that is the
     * whole reason it renders and picks rather than assembling its own view — so the first test here
     * compares the two rather than restating field expectations a third time.
     */
    @Nested
    @DisplayName("One suite by id — the operation the contract declared and nothing served")
    class Detail {

        @Test
        @DisplayName("the detail is byte-for-byte the row the list serves")
        void detailAgreesWithTheList() throws Exception {
            JsonNode fromList = byName(treeAs(LEAD, ACME, ACME_BANKING), "Refunds");

            assertThat(json.readTree(detailBodyOf(LEAD, ACME, ACME_BANKING, REFUNDS)))
                    .as("a second definition of a suite's wire shape is the defect viewOf exists "
                            + "to prevent — the detail and the list must not be able to disagree")
                    .isEqualTo(fromList);
        }

        @Test
        @DisplayName("a suite in a sibling project is 404, though the caller may see this one")
        void aSiblingProjectsSuiteIsNotReachableThroughThisProject() throws Exception {
            // ACME_MOBILE's suite is in the SAME company, so the company filter and the FK both
            // admit it. Only the project predicate separates them, and a detail endpoint that
            // looked the suite up by id alone would serve it under a project the caller reads.
            assertThat(detailStatusOf(ADMIN, ACME, ACME_BANKING, MOBILE_PAYMENTS)).isEqualTo(404);
            assertThat(detailStatusOf(ADMIN, ACME, ACME_MOBILE, MOBILE_PAYMENTS))
                    .as("the row really is reachable through its own project — otherwise the "
                            + "assertion above passes for the wrong reason")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a member with no grant gets 404, exactly as on the list")
        void membershipConveysNoSuite() throws Exception {
            assertThat(detailStatusOf(NO_GRANT, ACME, ACME_BANKING, REFUNDS)).isEqualTo(404);
        }

        @Test
        @DisplayName("no such suite, another client's suite, and a soft-deleted one all answer alike")
        void theFourCasesAnswerIdentically() throws Exception {
            assertThat(List.of(
                    detailStatusOf(LEAD, ACME, ACME_BANKING, UUID.randomUUID()),
                    detailStatusOf(LEAD, ACME, ACME_BANKING, RETIRED),
                    detailStatusOf(LEAD, ACME, UMBRA_BANKING, REFUNDS)))
                    .as("told apart, the id becomes a probe for what exists elsewhere (§6t)")
                    .containsExactly(404, 404, 404);

            assertThat(detailBodyOf(LEAD, ACME, ACME_BANKING, UUID.randomUUID()))
                    .as("a 404 that explains itself is still an oracle")
                    .isEmpty();
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/suites/" + REFUNDS))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private String detailBodyOf(UUID userId, UUID companyId, UUID projectId, UUID suiteId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/suites/" + suiteId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private int detailStatusOf(UUID userId, UUID companyId, UUID projectId, UUID suiteId)
            throws Exception {

        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/suites/" + suiteId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private List<JsonNode> treeAs(UUID userId, UUID companyId, UUID projectId) throws Exception {
        String body = bodyOf(userId, companyId, projectId);
        JsonNode array = json.readTree(body);
        assertThat(array.isArray()).as("the endpoint must answer a JSON array: %s", body).isTrue();
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        return List.copyOf(elements);
    }

    private String bodyOf(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/suites")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();
    }

    private int statusOf(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/suites")
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private static JsonNode byName(List<JsonNode> suites, String name) {
        return suites.stream()
                .filter(node -> node.get("name").asText().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no suite named '" + name + "' — the response carried " + namesOf(suites)));
    }

    private static List<String> idsOf(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.get("id").asText()).toList();
    }

    private static List<String> keysOf(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.get("key").asText()).toList();
    }

    private static List<String> namesOf(List<JsonNode> nodes) {
        return nodes.stream().map(node -> node.get("name").asText()).toList();
    }

    private static List<String> namesIn(JsonNode array) {
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
        return UUID.nameUUIDFromBytes(("suite-endpoint:" + marker).getBytes());
    }

    /**
     * The tree, its cases and its deleted rows, written as raw SQL through the ordinary app role.
     *
     * <p>There is no suite-authoring endpoint and no {@code TestCase} entity, so a fixture is the
     * only way to get rows here at all. The <em>app</em> role rather than the superuser on
     * purpose: it is the same policed path production reads through, so a fixture that could not
     * be written under RLS is a fixture that is lying about what the endpoint will see.
     *
     * <p><strong>Sibling positions descend as the tree deepens</strong> — Payments 5, Refunds 3,
     * Partial refunds 1. That is deliberate and it is the second version of this fixture: with
     * every position at 0 an {@code ORDER BY position} query returned parents first anyway, by
     * luck, and the mutation that swaps {@code path} for {@code position} <em>survived</em>. A
     * fixture that cannot distinguish the right query from the wrong one is a fixture that proves
     * nothing, which is §22's surviving-mutation lesson in a new costume.
     */
    private void seedAcmeBankingTree() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1,
                    "Payments", "Payments", "APPROVED", 5, false);
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2,
                    "Refunds", "Payments.Refunds", "APPROVED", 3, false);
            suite(connection, PARTIAL, ACME, ACME_BANKING, REFUNDS, "TS-3", 3,
                    "Partial refunds", "Payments.Refunds.Partial", "IN_REVIEW", 1, false);
            suite(connection, CHECKOUT, ACME, ACME_BANKING, null, "TS-4", 4,
                    "Checkout", "Checkout", "DRAFT", 4, false);
            suite(connection, RETIRED, ACME, ACME_BANKING, null, "TS-5", 5,
                    "Retired folder", "Retired", "DEPRECATED", 9, true);

            // Refunds: 3 live. Partial: 2 live + 1 deleted. Checkout: 3, all deleted — so the
            // GROUP BY returns no row for it and "absent means zero" is exercised. Payments holds
            // none of its own, which is what a rolled-up count would get wrong.
            cases(connection, ACME, ACME_BANKING, REFUNDS, "acme-ref", 3, 0);
            cases(connection, ACME, ACME_BANKING, PARTIAL, "acme-par", 2, 1);
            cases(connection, ACME, ACME_BANKING, CHECKOUT, "acme-che", 0, 3);

            // §96's three arms, on ONE folder, so the union is distinguishable from any single arm
            // taken alone. Payments holds nothing of its own — that is what makes it the right
            // subject: every member it reports comes from an arm that did not exist before V31.
            //
            //   added:  the FIRST of Refunds' three live cases, individually
            //   tagged: the SECOND of them, via the rule below
            //   both:   the same first case is tagged too, so a COUNT(*) over the union answers 3
            //           where the deduped COUNT(DISTINCT) answers 2. That is the assertion that
            //           separates "unioned" from "unioned and deduped", and nothing else does.
            tag(connection, ACME, caseOf("acme-ref", 0), "smoke");
            tag(connection, ACME, caseOf("acme-ref", 1), "smoke");
            addedCase(connection, ACME, PAYMENTS, caseOf("acme-ref", 0));
            tagRule(connection, ACME, PAYMENTS, "smoke");

            // A rule matching a case that is DELETED, on a folder that has nothing else: the arm
            // must not resurrect a case the grid has dropped, and with a live match beside it the
            // difference would be invisible.
            tag(connection, ACME, caseOf("acme-par", 2), "gone");
            tagRule(connection, ACME, CHECKOUT, "gone");
        }
    }

    /** The id {@link #cases} assigns to the {@code index}-th row it writes under one marker. */
    private static UUID caseOf(String marker, int index) {
        return UUID.nameUUIDFromBytes(("suite-case:" + marker + ":" + index).getBytes());
    }

    private void tag(Connection connection, UUID companyId, UUID caseId, String tag)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO case_tag (company_id, case_id, tag) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private void tagRule(Connection connection, UUID companyId, UUID suiteId, String tag)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO suite_tag_rule (company_id, suite_id, tag) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), ?) ON CONFLICT DO NOTHING")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    private void addedCase(Connection connection, UUID companyId, UUID suiteId, UUID caseId)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO suite_case (company_id, suite_id, case_id) "
                        + "VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid)) "
                        + "ON CONFLICT DO NOTHING")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, suiteId.toString());
            statement.setString(3, caseId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * A same-named suite in the sibling project and in the other client, both populated.
     *
     * <p>Both collisions matter and they fail differently: the sibling shares the caller's company
     * so only {@code project_id} separates it, and the other client shares the name AND the
     * display key so only {@code company_id} does.
     */
    private void seedSiblingAndForeignTrees() throws SQLException {
        UUID umbraPayments = UUID.fromString("5a004000-0000-4000-8000-000000000021");

        try (Connection connection = appConnection(ACME)) {
            suite(connection, MOBILE_PAYMENTS, ACME, ACME_MOBILE, null, "TS-6", 6,
                    "Payments", "Payments", "APPROVED", 0, false);
            cases(connection, ACME, ACME_MOBILE, MOBILE_PAYMENTS, "acme-mob", 4, 0);
        }
        try (Connection connection = appConnection(UMBRA)) {
            // TS-1 again: display keys are numbered per COMPANY, so the collision is the product
            // working as designed and not a fixture cheat.
            suite(connection, umbraPayments, UMBRA, UMBRA_BANKING, null, "TS-1", 1,
                    "Payments", "Payments", "APPROVED", 0, false);
            cases(connection, UMBRA, UMBRA_BANKING, umbraPayments, "umbra-pay", 5, 0);
        }
    }

    /**
     * One live library condition, one retired one, and the four attachment shapes.
     *
     * <p>Refunds gets the live reference as setup <em>and</em> as cleanup, Partial refunds the
     * retired one, Checkout an inline body. The retired and inline rows are the exclusions
     * {@code SuiteConditionView} documents and they have no other coverage — those two the product
     * still cannot create.
     *
     * <p>~~nothing in the product can create any of these rows yet~~ → <strong>§186 gave
     * {@code suite_condition} a writer</strong>, so the two <em>reference</em> rows here could now
     * be seeded through {@code POST /suites}. They are deliberately not: this class tests the read,
     * and seeding a read fixture through the write under test makes a broken pair agree with
     * itself — which is the argument §107 deleted the whole mock layer over. {@code
     * SuiteWriteEndpointTest} owns the write, and it seeds nothing by hand.</p>
     */
    private void seedConditions() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            condition(connection, LIVE_CONDITION, "Log in as standard user",
                    "[\"Open the application\", \"Enter valid credentials\"]", false);
            condition(connection, RETIRED_CONDITION, "Log in as the old admin",
                    "[\"Open the legacy console\"]", true);

            attachment(connection, UUID.fromString("5a006000-0000-4000-8000-000000000001"),
                    REFUNDS, "PRE", 0, LIVE_CONDITION, null);
            attachment(connection, UUID.fromString("5a006000-0000-4000-8000-000000000002"),
                    PARTIAL, "PRE", 0, RETIRED_CONDITION, null);
            attachment(connection, UUID.fromString("5a006000-0000-4000-8000-000000000003"),
                    CHECKOUT, "PRE", 0, null, "[\"Have a cart with two items\"]");
            // The SAME library entry, on the SAME suite, as cleanup — which is the arrangement the
            // one-array response could not describe (§186) and the one a BOTH entry exists for.
            // Position 0 again: the two lists number independently, so a POST row sharing a
            // position with a PRE row is correct rather than a collision.
            attachment(connection, POST_ATTACHMENT, REFUNDS, "POST", 0, LIVE_CONDITION, null);
        }
    }

    /** Every nullable parameter is bound through an explicit {@code CAST} — see below. */
    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID parentId,
            String key, long keySeq, String name, String path, String lifecycle, int position,
            boolean deleted) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, ?, ?, CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.setInt(7, position);
            statement.setString(8, lifecycle);
            // A fixed instant rather than now(): nothing asserts the value, and a deterministic
            // fixture is one less thing that can differ between runs.
            statement.setString(9, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /**
     * Cases in one suite — {@code live} of them countable, {@code deleted} of them not.
     *
     * <p>{@code key} is unique per company, so the marker prefix keeps two suites' fixtures from
     * colliding on a key and failing in a way that reads like a tenancy bug.
     */
    private void cases(
            Connection connection, UUID companyId, UUID projectId, UUID suiteId, String marker,
            int live, int deleted) throws SQLException {

        for (int i = 0; i < live + deleted; i++) {
            boolean isDeleted = i >= live;
            try (var statement = connection.prepareStatement("""
                    INSERT INTO test_case
                        (id, company_id, project_id, suite_id, key, key_seq, title, deleted_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                            ?, ?, ?, CAST(? AS timestamptz))
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1,
                        UUID.nameUUIDFromBytes(("suite-case:" + marker + ":" + i).getBytes())
                                .toString());
                statement.setString(2, companyId.toString());
                statement.setString(3, projectId.toString());
                statement.setString(4, suiteId.toString());
                statement.setString(5, marker + "-TC-" + i);
                statement.setLong(6, i);
                statement.setString(7, "Case " + i);
                statement.setString(8, isDeleted ? "2026-01-01T00:00:00Z" : null);
                statement.executeUpdate();
            }
        }
    }

    private void condition(
            Connection connection, UUID id, String title, String body, boolean retired)
            throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO condition
                    (id, company_id, project_id, title, type, body, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, 'PRE',
                        CAST(? AS jsonb), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_BANKING.toString());
            statement.setString(4, title);
            statement.setString(5, body);
            statement.setString(6, retired ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    /**
     * One attachment — a reference or an inline body, never both.
     *
     * <p>{@code setObject(i, null)} leaves pgjdbc with no type to infer and fails with "could not
     * determine data type of parameter", a fixture-only failure that reads like a schema problem.
     * Hence the explicit casts on every nullable parameter here and above.
     */
    private void attachment(
            Connection connection, UUID id, UUID suiteId, String kind, int position,
            UUID conditionId, String inlineBody) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite_condition
                    (id, company_id, suite_id, kind, position, condition_id, inline_body)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS uuid), CAST(? AS jsonb))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, suiteId.toString());
            statement.setString(4, kind);
            statement.setInt(5, position);
            statement.setString(6, conditionId == null ? null : conditionId.toString());
            statement.setString(7, inlineBody);
            statement.executeUpdate();
        }
    }
}
