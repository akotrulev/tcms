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
 * {@code PATCH /plans/{planId}} over HTTP, against real Postgres with RLS in force.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A version row on every save.</strong> Plan §6j defines a plan version as its
 *       <em>scope</em>, so a rename must write none — {@link Versioning#renamingWritesNoVersion} is
 *       the test an implementation that bumps unconditionally fails, and it is the same call
 *       {@code suite_version}'s schema comment makes. Nothing else in the suite would notice: the
 *       response is identical either way and the extra rows only show up as a history nobody can
 *       read, months later.</li>
 *   <li><strong>A version row on <em>no</em> save.</strong> The mirror, and worse: a scope that
 *       moved with no row recorded is the one thing {@code test_plan_version} exists to prevent,
 *       and the scope is replaced wholesale, so the previous membership is gone the moment the
 *       delete commits.</li>
 *   <li><strong>An edit that silently discards a scope it did not mean to touch.</strong> An
 *       omitted list means "leave it alone" and {@code []} means "clear it" — the split §68 settled
 *       for a case's classification. Read the other way, changing a plan's <em>name</em> would drop
 *       every folder in it.</li>
 *   <li><strong>A criterion nobody can ever satisfy.</strong> A metric outside the closed
 *       vocabulary renders exactly like one the product cannot evaluate yet — both show a gate with
 *       no verdict — so a typo would sit in a release meeting looking like an unimplemented check.
 *       The validator refuses it at authoring time, which is the only moment the difference is
 *       visible.</li>
 *   <li><strong>An editable closed plan.</strong> Its scope and criteria are the record of what a
 *       finished release was tested against, so a save afterwards shows it passing gates nobody
 *       applied. This is {@link PlanLifecycle#isClosed}'s first consumer, and the placement matters
 *       as much as the rule — see {@link Frozen}.</li>
 *   <li><strong>A sibling project's suite in the scope.</strong> It satisfies the foreign key, the
 *       company column and RLS, so every cross-company test in the suite passes while a plan
 *       silently spans two products.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code ed…}, checked against every prefix
 * already in {@code src/test/java} before writing (handoff §19, and it has collided five times
 * since).
 */
@AutoConfigureMockMvc
@DisplayName("PATCH /plans/{id} — the content, the scope, and the version that is not always written")
class PlanUpdateEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ed000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("ed000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("ed001000-0000-4000-8000-000000000001");
    /** Same company, LEAD only — the source of the sibling-project ids the scope must refuse. */
    private static final UUID ACME_MOBILE = UUID.fromString("ed001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("ed002000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("ed003000-0000-4000-8000-000000000001");
    /** Test Engineer — holds {@code plan:edit} and not {@code plan:approve}. */
    private static final UUID ENGINEER = UUID.fromString("ed003000-0000-4000-8000-000000000002");
    private static final UUID VIEWER = UUID.fromString("ed003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding no grant on the project — 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("ed003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("ed003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("ed004000-0000-4000-8000-000000000001");
    private static final UUID LOGIN = UUID.fromString("ed004000-0000-4000-8000-000000000002");
    /** In ACME_MOBILE — real, in the caller's company, and not in this project. */
    private static final UUID MOBILE_SUITE = UUID.fromString("ed004000-0000-4000-8000-000000000003");

    private static final UUID PAYMENTS_CASE = UUID.fromString("ed005000-0000-4000-8000-000000000001");
    private static final UUID MOBILE_CASE = UUID.fromString("ed005000-0000-4000-8000-000000000002");
    /**
     * Filed under LOGIN and tagged {@code checkout} — reachable by the tag arm and by no suite the
     * section tests name, which is what lets a tag-armed section's count be told apart from a
     * suite-armed one's.
     */
    private static final UUID TAGGED_CASE = UUID.fromString("ed005000-0000-4000-8000-000000000003");

    private static final UUID ABSENT = UUID.fromString("ed00ffff-0000-4000-8000-00000000ffff");

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
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "pu-acme", "Acme", now);
            company(UMBRA, "pu-umbra", "Umbra", now);

            activeUser(LEAD, "lead@pu-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@pu-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@pu-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@pu-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@pu-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "PUAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "PUAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("pu-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("pu-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("pu-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("pu-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "PUUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("pu-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedProjectDefaults();

        try (Connection connection = appConnection(ACME)) {
            suite(connection, PAYMENTS, ACME, ACME_BANKING, "PUS-1", 1, "Payments", "payments");
            suite(connection, LOGIN, ACME, ACME_BANKING, "PUS-2", 2, "Login", "login");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, "PUS-3", 3, "Android", "android");
            testCase(connection, PAYMENTS_CASE, ACME, ACME_BANKING, PAYMENTS, "PUC-1", 1, "Refund");
            testCase(connection, MOBILE_CASE, ACME, ACME_MOBILE, MOBILE_SUITE, "PUC-2", 2, "Push");
            testCase(connection, TAGGED_CASE, ACME, ACME_BANKING, LOGIN, "PUC-3", 3, "Checkout");
            caseTag(connection, TAGGED_CASE, "checkout");
        }

        // Cleared LAST, so "a refusal writes no audit row" is about the request under test.
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
            }
        }
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — plan:edit, and the two paths that must answer 404 rather than 403")
    class Gate {

        @Test
        @DisplayName("a Test Engineer edits a plan a lead created — plan:edit is not OWN-qualified")
        void engineerEdits() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            MvcResult saved = patch(ENGINEER, ACME, ACME_BANKING, plan, edit("Release 4.4"), null);

            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(saved).get("name").asText()).isEqualTo("Release 4.4");
            assertThat(planColumn(plan, "name")).isEqualTo("Release 4.4");
        }

        @Test
        @DisplayName("a Viewer READS the plan and is refused the edit — 403, nothing written")
        void viewerIsRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            clearAudit(ACME);

            // Without the read this proves nothing: a 403 would pass just as well against a route
            // that does not exist or a token that is wrong.
            assertThat(get(VIEWER, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan)
                    .getResponse().getStatus()).isEqualTo(200);

            MvcResult refused = patch(VIEWER, ACME, ACME_BANKING, plan, edit("Renamed"), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .as("a permission key is not actionable by somebody who cannot grant it (§32)")
                    .doesNotContain("plan:edit");
            assertThat(planColumn(plan, "name")).isEqualTo("Release 4.3");
            assertThat(auditActions(ACME)).isEmpty();
        }

        @Test
        @DisplayName("a company member with no grant gets 404, never 403")
        void noGrantIs404() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // The project IS in this caller's company, so every cross-company assertion in the
            // suite would still pass while this leaked the project's existence.
            assertThat(patch(NO_GRANT, ACME, ACME_BANKING, plan, edit("Renamed"), null)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the other client, an absent plan and a SIBLING project's plan are all 404")
        void unreachablePlansAre404() throws Exception {
            UUID mine = plan(LEAD, ACME_BANKING, "Release 4.3");
            UUID theirs = plan(LEAD, ACME_MOBILE, "Mobile 1.0");

            assertThat(patch(OUTSIDER, UMBRA, ACME_BANKING, mine, edit("Renamed"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(patch(LEAD, ACME, ACME_BANKING, ABSENT, edit("Renamed"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            // The plan is real, the project is real, the caller holds BOTH projects — only the
            // second predicate in findLiveInProject refuses this one.
            assertThat(patch(LEAD, ACME, ACME_BANKING, theirs, edit("Renamed"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(planColumn(theirs, "name")).isEqualTo("Mobile 1.0");
        }
    }

    // =====================================================================
    // The criteria
    // =====================================================================

    @Nested
    @DisplayName("The criteria — the columns nothing had ever written")
    class Criteria {

        @Test
        @DisplayName("exit criteria are stored, served back, and land in the column as JSON")
        void exitCriteriaAreWritten() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("exitCriteria", List.of(
                    criterion("pass_rate", ">=", 95, "95% of executed cases pass"),
                    criterion("coverage", ">=", 90, null)));

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            JsonNode exit = body(saved).get("exitCriteria");
            assertThat(exit).hasSize(2);
            assertThat(exit.get(0).get("metric").asText()).isEqualTo("pass_rate");
            assertThat(exit.get(0).get("value").asDouble()).isEqualTo(95d);
            assertThat(exit.get(0).get("label").asText()).isEqualTo("95% of executed cases pass");
            // Null and non-null exactly together: neither is evaluated on a plan detail, which is
            // what CriterionView#met documents and what the panel renders as "not evaluated".
            assertThat(exit.get(0).get("met").isNull()).isTrue();
            assertThat(exit.get(0).get("actual").isNull()).isTrue();
            // The label is DERIVED when absent, so no criterion is ever rendered blank.
            assertThat(exit.get(1).get("label").asText()).isEqualTo("coverage >= 90.0");

            assertThat(planColumn(plan, "CAST(exit_criteria AS text)"))
                    .contains("pass_rate").contains("coverage");
        }

        @Test
        @DisplayName("a metric outside the vocabulary is a 400 that NAMES the valid set")
        void unknownMetricIsRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("exitCriteria", List.of(criterion("pass_rte", ">=", 95, null)));

            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            String body = refused.getResponse().getContentAsString();
            assertThat(body).contains("exitCriteria").contains("pass_rte");
            assertThat(body)
                    .as("a closed product vocabulary is not a secret, unlike a permission key — "
                            + "withholding it leaves the author guessing at a list the server knows")
                    .contains("pass_rate").contains("open_critical");
            assertThat(planColumn(plan, "CAST(exit_criteria AS text)")).isNull();
        }

        @Test
        @DisplayName("an unusable operator and a criterion with no threshold are both 400")
        void badPredicatesAreRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> badOp = edit("Release 4.3");
            badOp.put("exitCriteria", List.of(criterion("pass_rate", "=>", 95, null)));
            assertThat(patch(LEAD, ACME, ACME_BANKING, plan, badOp, null)
                    .getResponse().getStatus()).isEqualTo(400);

            // Boxed and checked rather than defaulted: 0 would become "pass_rate >= 0", a gate met
            // by every release that ever runs and indistinguishable on screen from a real one.
            Map<String, Object> noValue = edit("Release 4.3");
            noValue.put("exitCriteria", List.of(criterion("pass_rate", ">=", null, null)));
            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, plan, noValue, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("pass_rate");

            assertThat(planColumn(plan, "CAST(exit_criteria AS text)")).isNull();
        }

        @Test
        @DisplayName("an omitted list leaves the column alone; [] clears it — three states, not two")
        void absentIsNotEmpty() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> withCriteria = edit("Release 4.3");
            withCriteria.put("exitCriteria", List.of(criterion("pass_rate", ">=", 95, null)));
            patch(LEAD, ACME, ACME_BANKING, plan, withCriteria, null);

            // Omitted: the whole point of the split, because the SPA's rename dialog does not
            // render criteria and must not silently delete them by not sending them.
            MvcResult renamed = patch(LEAD, ACME, ACME_BANKING, plan, edit("Release 4.4"), null);
            assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(renamed).get("exitCriteria")).hasSize(1);

            Map<String, Object> cleared = edit("Release 4.4");
            cleared.put("exitCriteria", List.of());
            MvcResult emptied = patch(LEAD, ACME, ACME_BANKING, plan, cleared, null);
            assertThat(body(emptied).get("exitCriteria")).isEmpty();
            // "[]" and not NULL: the author decided there are none, which TestPlan.create's comment
            // insisted stay distinguishable from "nobody has decided".
            assertThat(planColumn(plan, "CAST(exit_criteria AS text)")).isEqualTo("[]");
        }

        @Test
        @DisplayName("a created plan's columns are NULL, which is not the same as []")
        void aCreatedPlanHasNeitherList() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            assertThat(planColumn(plan, "CAST(exit_criteria AS text)")).isNull();
            assertThat(planColumn(plan, "CAST(entry_criteria AS text)")).isNull();
            // Both still render as [] — the difference is authoring history, not something a plan
            // page can act on.
            JsonNode plan1 = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan));
            assertThat(plan1.get("exitCriteria")).isEmpty();
            assertThat(plan1.get("entryCriteria")).isEmpty();
        }
    }

    // =====================================================================
    // The scope
    // =====================================================================

    @Nested
    @DisplayName("The scope — served so it can be written back, and validated against this project")
    class Scope {

        @Test
        @DisplayName("the detail serves the scope ids, so the editor can write back what it read")
        void theScopeIsServed() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            JsonNode served = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan));

            // Before this slice the response carried caseCount and nothing else about the scope —
            // an expansion, derived, and unusable as the value a PATCH sends back.
            assertThat(ids(served.get("suiteIds"))).containsExactly(PAYMENTS);
            assertThat(served.get("caseIds")).isEmpty();
        }

        @Test
        @DisplayName("a folder is added and one removed, and the rows follow")
        void theScopeIsReplaced() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("suiteIds", List.of(LOGIN));
            payload.put("caseIds", List.of(PAYMENTS_CASE));

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            assertThat(ids(body(saved).get("suiteIds"))).containsExactly(LOGIN);
            assertThat(ids(body(saved).get("caseIds"))).containsExactly(PAYMENTS_CASE);
            assertThat(scopeRows(plan)).containsExactlyInAnyOrder(LOGIN, PAYMENTS_CASE);
        }

        @Test
        @DisplayName("renaming a plan does NOT drop its scope — an omitted list means leave it alone")
        void renamingKeepsTheScope() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            MvcResult renamed = patch(LEAD, ACME, ACME_BANKING, plan, edit("Release 4.4"), null);

            assertThat(ids(body(renamed).get("suiteIds"))).containsExactly(PAYMENTS);
            assertThat(scopeRows(plan)).containsExactly(PAYMENTS);
        }

        @Test
        @DisplayName("and [] genuinely empties it")
        void anEmptyListClearsTheScope() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("suiteIds", List.of());

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(body(saved).get("suiteIds")).isEmpty();
            assertThat(scopeRows(plan)).isEmpty();
        }

        /**
         * The id is real, live, in the caller's own company, and the caller holds a grant on the
         * project it belongs to — so the foreign key, the company column, RLS and every
         * cross-company assertion in this suite all accept it. Only {@code liveSuiteIds} refuses.
         */
        @Test
        @DisplayName("a SIBLING project's suite and case are refused — the check nothing else makes")
        void siblingProjectScopeIsRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> suitePayload = edit("Release 4.3");
            suitePayload.put("suiteIds", List.of(MOBILE_SUITE));
            MvcResult refusedSuite = patch(LEAD, ACME, ACME_BANKING, plan, suitePayload, null);
            assertThat(refusedSuite.getResponse().getStatus()).isEqualTo(400);
            assertThat(refusedSuite.getResponse().getContentAsString())
                    .contains("suiteIds")
                    .as("the id is not echoed — it names a folder the reader may not be able to see")
                    .doesNotContain(MOBILE_SUITE.toString());

            Map<String, Object> casePayload = edit("Release 4.3");
            casePayload.put("caseIds", List.of(MOBILE_CASE));
            assertThat(patch(LEAD, ACME, ACME_BANKING, plan, casePayload, null)
                    .getResponse().getStatus()).isEqualTo(400);

            assertThat(scopeRows(plan)).isEmpty();
        }

        @Test
        @DisplayName("duplicates collapse rather than writing two rows saying one thing")
        void duplicatesAreCollapsed() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("suiteIds", List.of(PAYMENTS, PAYMENTS, LOGIN));

            patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(scopeRows(plan)).containsExactlyInAnyOrder(PAYMENTS, LOGIN);
        }
    }

    // =====================================================================
    // §97 — the tag arm, the sections, and the prose
    // =====================================================================

    @Nested
    @DisplayName("The tag arm — a scope line whose membership is a query (§97 D8)")
    class TagArm {

        @Test
        @DisplayName("a tag in scope resolves to the cases carrying it, and is served back")
        void aTagResolves() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("tags", List.of("checkout"));

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            assertThat(strings(body(saved).get("tags"))).containsExactly("checkout");
            // The point of the arm: the plan covers a case it never names. A tag stored and not
            // resolved would serve the tag back and report a caseCount of 0, which reads exactly
            // like a tag nobody has used.
            assertThat(body(saved).get("caseCount").asLong())
                    .as("TAGGED_CASE carries `checkout` and is named nowhere in this plan")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the plan gains a case when one is TAGGED, with nothing written to the plan")
        void theSelectorIsLive() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            Map<String, Object> payload = edit("Release 4.3");
            payload.put("tags", List.of("checkout"));
            patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            // Taken AFTER the edit, because adding the tag IS a scope change and legitimately
            // writes a version. The claim under test is about what happens with no edit at all.
            String versionBeforeTagging = planColumn(plan, "CAST(current_version_no AS text)");

            // D7′ in one assertion, and the only way to observe it is from the case side.
            try (Connection connection = appConnection(ACME)) {
                caseTag(connection, PAYMENTS_CASE, "checkout");
            }

            JsonNode reread = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan));

            assertThat(reread.get("caseCount").asLong())
                    .as("no PATCH happened between the two reads — a snapshot would still say 1")
                    .isEqualTo(2);
            assertThat(planColumn(plan, "CAST(current_version_no AS text)"))
                    .as("and the plan's own version does NOT move, because the plan did not change "
                            + "— that is the accepted cost of a live selector, and it is why the "
                            + "approval goes stale silently while the case gate still fires at "
                            + "run-create time")
                    .isEqualTo(versionBeforeTagging);
        }

        @Test
        @DisplayName("a tag matching nothing is stored and resolves to nothing — never an error")
        void anUnusedTagIsLegal() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("tags", List.of("not-a-tag-anyone-uses"));

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            // A tag is free text and the endpoint that lists them is a picker's list, never a
            // validator's — refusing this would close the vocabulary, and the first case to carry
            // a new tag could never be planned for.
            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(saved).get("caseCount").asLong()).isZero();
        }
    }

    @Nested
    @DisplayName("Sections — named sub-features whose membership is resolved on every read (D7′)")
    class Sections {

        @Test
        @DisplayName("a section round-trips with all three of its arms and its resolved count")
        void aSectionRoundTrips() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, withSections(List.of(
                    section("Create an object", List.of(PAYMENTS_CASE), List.of(), List.of()),
                    section("Navigation", List.of(), List.of(), List.of("checkout")))), null);

            assertThat(saved.getResponse().getStatus())
                    .as("%s", saved.getResponse().getContentAsString())
                    .isEqualTo(200);

            JsonNode sections = body(saved).get("sections");
            assertThat(names(sections))
                    .as("author order, as submitted — not alphabetical, which would rearrange "
                            + "somebody's document on save")
                    .containsExactly("Create an object", "Navigation");
            assertThat(sections.get(0).get("position").asInt()).isZero();
            assertThat(sections.get(1).get("position").asInt()).isEqualTo(1);
            assertThat(ids(sections.get(0).get("caseIds"))).containsExactly(PAYMENTS_CASE);
            assertThat(strings(sections.get(1).get("tags"))).containsExactly("checkout");

            // The count is the RESOLUTION and not the number of references: the second section
            // names one tag and holds one case, and it is not the same case.
            assertThat(sections.get(0).get("caseCount").asLong()).isEqualTo(1);
            assertThat(sections.get(1).get("caseCount").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("the sections' counts EXCEED the plan's, because a case reached twice is one case")
        void theCountsDoNotSum() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // PAYMENTS_CASE is named directly in one section and arrives through its own folder in
            // the other. Both sections hold it; the plan holds it once.
            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, withSections(List.of(
                    section("Named directly", List.of(PAYMENTS_CASE), List.of(), List.of()),
                    section("Via the folder", List.of(), List.of(PAYMENTS), List.of()))), null);

            JsonNode served = body(saved);
            assertThat(served.get("sections").get(0).get("caseCount").asLong()).isEqualTo(1);
            assertThat(served.get("sections").get(1).get("caseCount").asLong()).isEqualTo(1);
            assertThat(served.get("caseCount").asLong())
                    .as("the parts sum to 2 and the whole is 1 — stated on the contract and on the "
                            + "screen, because a reader who subtracts reports a bug in a correct "
                            + "number")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an EMPTY section survives a save and a reload")
        void anEmptySectionIsKept() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // The state the whole shape of ck_plan_scope_target exists for: an author types the
            // sub-feature heading before filling it. Dropping it on save deletes what they just
            // typed, mid-edit, with a success message.
            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, withSections(List.of(
                    section("Nobody has filled this in", List.of(), List.of(), List.of()))), null);

            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            assertThat(names(body(saved).get("sections")))
                    .containsExactly("Nobody has filled this in");
            assertThat(body(saved).get("sections").get(0).get("caseCount").asLong())
                    .as("zero, and zero is not an error")
                    .isZero();

            JsonNode reread = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan));
            assertThat(names(reread.get("sections")))
                    .as("the reload is the half a response assertion cannot make — the row has to "
                            + "be in the table, not merely echoed back")
                    .containsExactly("Nobody has filled this in");
        }

        @Test
        @DisplayName("two sections with one name are refused, because the name IS the identity")
        void duplicateSectionNamesAreRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // Storage keys a row's membership on section_name, so two sections called "Edit" would
            // be saved as two and read back as one, silently merging their contents.
            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, plan, withSections(List.of(
                    section("Edit", List.of(PAYMENTS_CASE), List.of(), List.of()),
                    section("Edit", List.of(), List.of(PAYMENTS), List.of()))), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("sections");
            assertThat(scopeRows(plan)).isEmpty();
        }

        @Test
        @DisplayName("a section naming a SIBLING project's case is refused like an unsectioned one")
        void aSectionIsCheckedAgainstTheProject() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            // The live-id check moved one level down. An id validated in the unsectioned group and
            // not inside a section is the same hole in a place nobody would look.
            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, plan, withSections(List.of(
                    section("Create an object", List.of(MOBILE_CASE), List.of(), List.of()))), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("caseIds")
                    .doesNotContain(MOBILE_CASE.toString());
            assertThat(scopeRows(plan)).isEmpty();
        }

        @Test
        @DisplayName("omitting sections leaves them alone; [] removes them")
        void absentIsNotEmpty() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            patch(LEAD, ACME, ACME_BANKING, plan, withSections(List.of(
                    section("Navigation", List.of(PAYMENTS_CASE), List.of(), List.of()))), null);

            MvcResult renamed = patch(LEAD, ACME, ACME_BANKING, plan, edit("Release 4.4"), null);
            assertThat(names(body(renamed).get("sections")))
                    .as("a rename must not empty the plan's structure")
                    .containsExactly("Navigation");

            Map<String, Object> cleared = edit("Release 4.4");
            cleared.put("sections", List.of());
            MvcResult emptied = patch(LEAD, ACME, ACME_BANKING, plan, cleared, null);

            assertThat(emptied.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(emptied).get("sections")).isEmpty();
            assertThat(body(emptied).get("caseCount").asLong())
                    .as("removing a section removes its references from the plan — they are not "
                            + "quietly relocated into the unsectioned group")
                    .isZero();
        }

        @Test
        @DisplayName("moving a case into a section IS a scope change, so it writes a version")
        void sectioningWritesAVersion() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(), List.of(PAYMENTS_CASE));
            assertThat(versionNumbers(plan)).containsExactly(1);

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("caseIds", List.of());
            payload.put("sections", List.of(
                    section("Create an object", List.of(PAYMENTS_CASE), List.of(), List.of())));

            patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            // The plan covers the same one case either way, and the version still moves: a version
            // is the plan's SCOPE as authored, and where a case sits in the document is part of it.
            assertThat(versionNumbers(plan)).containsExactly(1, 2);
        }
    }

    @Nested
    @DisplayName("The prose — the OTHER meaning of the word scope (§97 D6)")
    class Prose {

        @Test
        @DisplayName("both texts are stored and served back")
        void theProseRoundTrips() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("scopeText", "Payments, refunds and the settlement report.");
            payload.put("outOfScopeText", "Anything behind the partner feature flag.");

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(saved.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(saved).get("scopeText").asText())
                    .isEqualTo("Payments, refunds and the settlement report.");
            assertThat(body(saved).get("outOfScopeText").asText())
                    .isEqualTo("Anything behind the partner feature flag.");
        }

        @Test
        @DisplayName("null clears the text — a scalar, unlike every list on this input")
        void nullClears() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            Map<String, Object> written = edit("Release 4.3");
            written.put("scopeText", "Something somebody wrote.");
            patch(LEAD, ACME, ACME_BANKING, plan, written, null);

            Map<String, Object> cleared = edit("Release 4.3");
            cleared.put("scopeText", null);
            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, cleared, null);

            assertThat(body(saved).get("scopeText").isNull())
                    .as("the SPA submits every field it renders on every save, so an omitted "
                            + "scalar is a cleared one — the list half of the convention is the "
                            + "opposite and both are asserted in this class")
                    .isTrue();
        }

        @Test
        @DisplayName("prose is CONTENT, so editing it writes no version")
        void proseIsNotScope() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());
            assertThat(versionNumbers(plan)).containsExactly(1);

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("scopeText", "Rewritten from scratch, twice, at length.");
            payload.put("outOfScopeText", "And this too.");
            patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            // The two meanings of "scope" pull in opposite directions here and this is where it
            // shows: a plan version pins what will be RUN (§6j). Filling the prose in over three
            // sittings must not produce three versions of a scope that never moved.
            assertThat(versionNumbers(plan))
                    .as("a version per paragraph would fill the history with rows whose snapshots "
                            + "are identical, which is what SuiteVersions refuses for a rename")
                    .containsExactly(1);
        }

        @Test
        @DisplayName("blank trims to absent, so a cleared box is not a section holding a space")
        void blankIsAbsent() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("outOfScopeText", "   ");
            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(body(saved).get("outOfScopeText").isNull())
                    .as("an out-of-scope section holding a single space renders as written and "
                            + "says nothing, and its whole value is being explicit")
                    .isTrue();
        }
    }

    // =====================================================================
    // Versioning
    // =====================================================================

    @Nested
    @DisplayName("Versioning — a plan version is its SCOPE, so most edits write none")
    class Versioning {

        @Test
        @DisplayName("a created plan gets v1 with it, so plans made from now on have no history gap")
        void createWritesV1() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            assertThat(versionNumbers(plan)).containsExactly(1);
            assertThat(planColumn(plan, "CAST(current_version_no AS text)")).isEqualTo("1");
            assertThat(snapshotOf(plan, 1)).contains(PAYMENTS.toString());
        }

        @Test
        @DisplayName("changing the SCOPE writes v2 and advances the counter")
        void aScopeChangeWritesAVersion() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("suiteIds", List.of(PAYMENTS, LOGIN));

            MvcResult saved = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(body(saved).get("currentVersionNo").asInt()).isEqualTo(2);
            assertThat(versionNumbers(plan)).containsExactly(1, 2);
            assertThat(snapshotOf(plan, 2)).contains(LOGIN.toString());
        }

        /**
         * <strong>The test an unconditional bump fails.</strong> {@code V1}'s own comment defines a
         * plan version as its scope, and §6j lists what that is — a name is not in it. Written the
         * other way, the history fills with rows whose snapshots are identical, which is the noise
         * {@code SuiteVersions} refuses for a rename and the reason the hash exists at all.
         */
        @Test
        @DisplayName("renaming a plan writes NO version — a name is content, not scope")
        void renamingWritesNoVersion() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            MvcResult renamed = patch(LEAD, ACME, ACME_BANKING, plan,
                    edit("Release 4.4", "A new description"), null);

            assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(renamed).get("currentVersionNo").asInt()).isEqualTo(1);
            assertThat(versionNumbers(plan)).containsExactly(1);
            assertThat(planColumn(plan, "name")).isEqualTo("Release 4.4");
        }

        @Test
        @DisplayName("saving the same scope twice writes nothing the second time")
        void anUnchangedSaveWritesNoVersion() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            Map<String, Object> payload = edit("Release 4.3");
            payload.put("suiteIds", List.of(LOGIN));
            patch(LEAD, ACME, ACME_BANKING, plan, payload, null);
            patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(versionNumbers(plan))
                    .as("pressing Save twice must not make the history a log of who looked at it")
                    .containsExactly(1, 2);
        }

        @Test
        @DisplayName("the MILESTONE and the CRITERIA are scope too, so each writes a version")
        void milestoneAndCriteriaAreVersioned() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> milestone = edit("Release 4.3");
            milestone.put("milestoneName", "GA");
            milestone.put("milestoneDate", "2026-09-01");
            patch(LEAD, ACME, ACME_BANKING, plan, milestone, null);
            assertThat(versionNumbers(plan)).containsExactly(1, 2);

            Map<String, Object> criteria = edit("Release 4.3");
            criteria.put("milestoneName", "GA");
            criteria.put("milestoneDate", "2026-09-01");
            criteria.put("exitCriteria", List.of(criterion("pass_rate", ">=", 95, null)));
            patch(LEAD, ACME, ACME_BANKING, plan, criteria, null);

            assertThat(versionNumbers(plan)).containsExactly(1, 2, 3);
            assertThat(snapshotOf(plan, 3)).contains("pass_rate");
        }

        @Test
        @DisplayName("a status move still writes none — approving a plan does not change what is in it")
        void aStatusMoveWritesNoVersion() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());

            setStatus(LEAD, ACME_BANKING, plan, "IN_REVIEW");
            setStatus(LEAD, ACME_BANKING, plan, "APPROVED");

            assertThat(versionNumbers(plan)).containsExactly(1);
        }

        @Test
        @DisplayName("the audit row says whether the SCOPE moved, which is the only question asked")
        void theAuditRowCarriesTheVersion() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3", List.of(PAYMENTS), List.of());
            clearAudit(ACME);

            patch(LEAD, ACME, ACME_BANKING, plan, edit("Release 4.4"), null);
            assertThat(auditActions(ACME)).containsKey("plan.updated");
            // Parsed rather than string-matched: jsonb re-renders what it stored, spacing and all,
            // so an assertion over the text is really an assertion about Postgres's formatter.
            JsonNode prose = json.readTree(auditActions(ACME).get("plan.updated"));
            assertThat(prose.get("scopeChanged").asBoolean()).isFalse();
            assertThat(prose.get("versionNo").asInt()).isEqualTo(1);

            clearAudit(ACME);
            Map<String, Object> payload = edit("Release 4.4");
            payload.put("suiteIds", List.of(LOGIN));
            patch(LEAD, ACME, ACME_BANKING, plan, payload, null);
            JsonNode moved = json.readTree(auditActions(ACME).get("plan.updated"));
            assertThat(moved.get("scopeChanged").asBoolean()).isTrue();
            assertThat(moved.get("versionNo").asInt()).isEqualTo(2);
        }
    }

    // =====================================================================
    // The freeze — REMOVED with the CLOSED state (§97, M7)
    // =====================================================================
    //
    // A `Frozen` nested class of three tests lived here: a CLOSED plan refusing the edit with a
    // sentence naming the way out, a Viewer on one still getting 403 (§72's placement — the refusal
    // names a state, and a caller who may not edit plans at all has not earned that information),
    // and reopening making it editable again.
    //
    // §97 removed CLOSED, so no plan is frozen against editing. The concern the freeze answered —
    // a sign-off coming to cover criteria nobody signed — is answered by §6g's edit-after-approval
    // rule, which demotes an approved plan to DRAFT rather than refusing the save, and is asserted
    // in this class's `EditAfterApproval` coverage. What is genuinely lost is the freeze on a plan
    // whose runs are finished: under M7 such a plan stays APPROVED for ever and can be edited.

    // =====================================================================
    // Concurrency and validation
    // =====================================================================

    @Nested
    @DisplayName("If-Match, and the rules a client can answer for itself")
    class Preconditions {

        @Test
        @DisplayName("a matching If-Match saves; a stale one is 409 and writes nothing")
        void ifMatchIsHonoured() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");
            long lockVersion = body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + plan))
                    .get("lockVersion").asLong();

            assertThat(patch(LEAD, ACME, ACME_BANKING, plan, edit("Release 4.4"), lockVersion)
                    .getResponse().getStatus()).isEqualTo(200);

            // The same version again — the editor that went to lunch.
            MvcResult stale =
                    patch(LEAD, ACME, ACME_BANKING, plan, edit("Release 4.5"), lockVersion);
            assertThat(stale.getResponse().getStatus()).isEqualTo(409);
            assertThat(detailOf(stale))
                    .as("a lost update says reload-and-reapply — the one 409 this endpoint has "
                            + "left, since §97 removed the closed-plan freeze that wanted the "
                            + "opposite advice")
                    .contains("Reload");
            assertThat(planColumn(plan, "name")).isEqualTo("Release 4.4");
        }

        /*
         * 'a closed plan refuses BEFORE If-Match — a stale header is fixable and this is not' was
         * the ordering assertion for the freeze. It goes with the freeze (§97, M7): there is one
         * 409 on this endpoint now, so there is no ordering between two of them to pin.
         */

        @Test
        @DisplayName("an If-Match that is not a number is a 400 on the header, not a 500")
        void anUnparseableIfMatchIs400() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            MvcResult refused = mvc.perform(MockMvcRequestBuilders
                            .patch("/api/v1/projects/" + ACME_BANKING + "/plans/" + plan)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME))
                            .header(HttpHeaders.IF_MATCH, "not-a-number")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(edit("Release 4.4"))))
                    .andReturn();

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("If-Match");
        }

        @Test
        @DisplayName("a blank name is a 400 on the field, and the plan keeps the one it had")
        void aBlankNameIsRefused() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", "   ");

            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, plan, payload, null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("name");
            assertThat(planColumn(plan, "name")).isEqualTo("Release 4.3");
        }

        @Test
        @DisplayName("the name is trimmed and the description cleared by an explicit null")
        void scalarsFollowTheirOwnRule() throws Exception {
            UUID plan = plan(LEAD, ACME_BANKING, "Release 4.3");

            Map<String, Object> withDescription = edit("  Release 4.4  ", "Quarterly regression");
            patch(LEAD, ACME, ACME_BANKING, plan, withDescription, null);
            assertThat(planColumn(plan, "name")).isEqualTo("Release 4.4");
            assertThat(planColumn(plan, "description")).isEqualTo("Quarterly regression");

            // A scalar null clears — the one place a null does not mean "leave it alone".
            Map<String, Object> cleared = new LinkedHashMap<>();
            cleared.put("name", "Release 4.4");
            cleared.put("description", null);
            patch(LEAD, ACME, ACME_BANKING, plan, cleared, null);
            assertThat(planColumn(plan, "description")).isNull();
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private Map<String, Object> edit(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        return payload;
    }

    private Map<String, Object> edit(String name, String description) {
        Map<String, Object> payload = edit(name);
        payload.put("description", description);
        return payload;
    }

    private Map<String, Object> criterion(String metric, String op, Number value, String label) {
        Map<String, Object> criterion = new LinkedHashMap<>();
        criterion.put("metric", metric);
        criterion.put("op", op);
        criterion.put("value", value);
        criterion.put("label", label);
        return criterion;
    }

    private MvcResult patch(
            UUID userId,
            UUID companyId,
            UUID projectId,
            UUID planId,
            Map<String, Object> payload,
            Long ifMatch) throws Exception {

        var request = MockMvcRequestBuilders
                .patch("/api/v1/projects/" + projectId + "/plans/" + planId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, String.valueOf(ifMatch));
        }
        return mvc.perform(request).andReturn();
    }

    private UUID plan(UUID userId, UUID projectId, String name) throws Exception {
        return plan(userId, projectId, name, List.of(), List.of());
    }

    private UUID plan(
            UUID userId, UUID projectId, String name, List<UUID> suiteIds, List<UUID> caseIds)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("suiteIds", suiteIds);
        payload.put("caseIds", caseIds);

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/plans")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(created.getResponse().getStatus())
                .as("the fixture depends on the real create endpoint; %s",
                        created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(body(created).get("id").asText());
    }

    private void setStatus(UUID userId, UUID projectId, UUID planId, String status)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);

        MvcResult moved = mvc.perform(MockMvcRequestBuilders
                        .put("/api/v1/projects/" + projectId + "/plans/" + planId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();

        assertThat(moved.getResponse().getStatus())
                .as("the fixture depends on the real status endpoint; %s",
                        moved.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private MvcResult get(UUID userId, UUID companyId, String path) throws Exception {
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

    private String detailOf(MvcResult result) throws Exception {
        return body(result).get("detail").asText();
    }

    private static List<UUID> ids(JsonNode array) {
        List<UUID> ids = new ArrayList<>();
        array.forEach(node -> ids.add(UUID.fromString(node.asText())));
        return ids;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    /** The served sections' names, in served order — which is an assertion about the order too. */
    private static List<String> names(JsonNode sections) {
        List<String> values = new ArrayList<>();
        sections.forEach(node -> values.add(node.get("name").asText()));
        return values;
    }

    private Map<String, Object> section(
            String name, List<UUID> caseIds, List<UUID> suiteIds, List<String> tags) {

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("name", name);
        section.put("caseIds", caseIds.stream().map(UUID::toString).toList());
        section.put("suiteIds", suiteIds.stream().map(UUID::toString).toList());
        section.put("tags", tags);
        return section;
    }

    /** A rename-free edit carrying just the sections. A List rather than varargs: a generic array
     *  parameter is a {@code -Werror} heap-pollution warning, and {@code @SafeVarargs} is a claim
     *  worth more than the two characters it saves at each call site. */
    private Map<String, Object> withSections(List<Map<String, Object>> sections) {
        Map<String, Object> payload = edit("Release 4.3");
        payload.put("sections", sections);
        return payload;
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

    private void grant(
            String marker, UUID companyId, UUID roleId, UUID userId, UUID projectId, Instant now) {
        UUID id = UUID.nameUUIDFromBytes(("plan-update:" + marker).getBytes());
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

    private void suite(
            Connection connection, UUID id, UUID companyId, UUID projectId,
            String key, long keySeq, String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, parent_id, key, key_seq, path, name,
                     position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), NULL, ?, ?,
                        CAST(? AS ltree), ?, 0, 'APPROVED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, path);
            statement.setString(7, name);
            statement.executeUpdate();
        }
    }

    private void testCase(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID suiteId,
            String key, long keySeq, String title) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title,
                     current_version_no)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        ?, ?, ?, 1)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.executeUpdate();
        }
    }

    /**
     * Tags a case, in ACME.
     *
     * <p>Written straight to {@code case_tag} rather than through the case editor because the tag
     * arm resolves against that table and nothing else — and because one test tags a case
     * <em>between two reads of one plan</em>, which is how a live selector is observed at all.
     */
    private void caseTag(Connection connection, UUID caseId, String tag) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO case_tag (company_id, case_id, tag)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?)
                ON CONFLICT DO NOTHING
                """)) {
            statement.setString(1, ACME.toString());
            statement.setString(2, caseId.toString());
            statement.setString(3, tag);
            statement.executeUpdate();
        }
    }

    /** Everything a test can write, in foreign-key order, through each company's own context. */
    private void clearMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM defect_link");
                execute(connection, "DELETE FROM step_result");
                execute(connection, "DELETE FROM test_execution");
                execute(connection, "DELETE FROM test_run");
                execute(connection, "DELETE FROM plan_scope_item");
                execute(connection, "DELETE FROM test_plan_version");
                execute(connection, "DELETE FROM test_plan");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void clearAudit(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            execute(connection, "DELETE FROM audit_event");
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back through the app role, past the API
    // =====================================================================

    private String planColumn(UUID planId, String column) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_plan WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    /** The suite and case ids on the plan's scope rows, whichever column each one uses. */
    private List<UUID> scopeRows(UUID planId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT COALESCE(suite_id, case_id) FROM plan_scope_item "
                             + "WHERE plan_id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    private List<Integer> versionNumbers(UUID planId) throws SQLException {
        List<Integer> numbers = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT version_no FROM test_plan_version WHERE plan_id = CAST(? AS uuid) "
                             + "ORDER BY version_no")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    numbers.add(rows.getInt(1));
                }
            }
        }
        return numbers;
    }

    private String snapshotOf(UUID planId, int versionNo) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT CAST(snapshot AS text) FROM test_plan_version "
                             + "WHERE plan_id = CAST(? AS uuid) AND version_no = ?")) {
            statement.setString(1, planId.toString());
            statement.setInt(2, versionNo);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private Map<String, String> auditActions(UUID companyId) throws SQLException {
        Map<String, String> events = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT action, CAST(changes AS text) FROM audit_event ORDER BY occurred_at")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return events;
    }
}
