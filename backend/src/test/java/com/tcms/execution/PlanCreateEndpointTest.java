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
 * {@code POST /projects/{projectId}/plans} over HTTP, against real Postgres with RLS in force.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>The gate copied from the read.</strong> Every read in this module asks
 *       {@code project:read}, which all six roles hold. A create gated the same way is one the
 *       client's own read-only stakeholder may perform, and it typechecks.</li>
 *   <li><strong>A scope item from a sibling project.</strong> The foreign key accepts it and RLS
 *       accepts it — both rows are in the caller's company — so only the project predicate refuses
 *       it. A Test Lead who holds two projects can produce that id honestly.</li>
 *   <li><strong>A retried create making a second plan.</strong> The header is read from the first
 *       day this endpoint exists, so unlike §29's case there is no window in which the SPA sends it
 *       and the server ignores it.</li>
 *   <li><strong>A refused create burning a key.</strong> Numbers are never recycled, so allocating
 *       before validating leaves a permanent hole per typo.</li>
 *   <li><strong>A created plan arriving ACTIVE.</strong> {@code plan:approve} is held by fewer roles
 *       than {@code plan:create}; a create that skipped the ladder would hand every Test Engineer an
 *       approval the catalogue says they may not grant.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code ea…}, checked against every prefix
 * already in {@code src/test/java} before writing (handoff §19, and §29 hit it again).
 */
@AutoConfigureMockMvc
@DisplayName("POST /plans — creating a test plan, its key, its scope and its refusals")
class PlanCreateEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("ea000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("ea000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("ea001000-0000-4000-8000-000000000001");
    /** Same company, LEAD only — the source of the sibling-project ids the scope must refuse. */
    private static final UUID ACME_MOBILE = UUID.fromString("ea001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("ea002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code plan:create}. */
    private static final UUID LEAD = UUID.fromString("ea003000-0000-4000-8000-000000000001");
    /** Test Engineer — also holds {@code plan:create} (roles spec §A4). */
    private static final UUID ENGINEER = UUID.fromString("ea003000-0000-4000-8000-000000000002");
    /** Viewer — reads the plans list and must be refused the create. THE persona of this class. */
    private static final UUID VIEWER = UUID.fromString("ea003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("ea003000-0000-4000-8000-000000000004");
    private static final UUID OUTSIDER = UUID.fromString("ea003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("ea004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("ea004000-0000-4000-8000-000000000002");
    private static final UUID SPARE = UUID.fromString("ea004000-0000-4000-8000-000000000003");
    private static final UUID MOBILE_SUITE = UUID.fromString("ea004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("ea004000-0000-4000-8000-000000000005");

    private static final UUID ABSENT = UUID.fromString("ea00ffff-0000-4000-8000-00000000ffff");

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

    /** In REFUNDS, beneath PAYMENTS — so the folder's subtree covers it. */
    private UUID caseA;
    /** In SPARE, outside the PAYMENTS subtree. */
    private UUID caseB;
    /** In ACME_MOBILE — a live case in the caller's own company and the wrong project. */
    private UUID mobileCase;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "pc-acme", "Acme", now);
            company(UMBRA, "pc-umbra", "Umbra", now);

            activeUser(LEAD, "lead@pc-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@pc-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@pc-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@pc-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@pc-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "PCAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "PCAMOB", "Mobile App", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grant("pc-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grant("pc-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grant("pc-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grant("pc-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "PCUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grant("pc-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        clearMutableRows();
        seedTrees();

        caseA = createCase(LEAD, ACME, ACME_BANKING, "Refund the whole captured amount", REFUNDS);
        caseB = createCase(LEAD, ACME, ACME_BANKING, "Partial refund rounds correctly", SPARE);
        mobileCase = createCase(LEAD, ACME, ACME_MOBILE, "Mobile login", MOBILE_SUITE);

        // The counter has been moved by the fixture's own case creates and by the suites; reset so
        // every TP-n assertion below is about this endpoint rather than about fixture order.
        resetPlanCounter();

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
    @DisplayName("The gate — plan:create, not the read every role holds")
    class Gate {

        @Test
        @DisplayName("a Test Lead creates a plan")
        void leadCreates() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), null);
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("a Test Engineer creates one too — plan:create is not Lead-only")
        void engineerCreates() throws Exception {
            assertThat(post(ENGINEER, ACME, ACME_BANKING, plan("Engineer's plan"), null)
                    .getResponse().getStatus()).isEqualTo(201);
        }

        @Test
        @DisplayName("a Viewer READS the list and is refused the create — 403, and nothing written")
        void viewerIsRefused() throws Exception {
            // Without the read this proves nothing: a 403 would pass just as well against a route
            // that does not exist or a token that is wrong.
            assertThat(get(VIEWER, ACME, "/projects/" + ACME_BANKING + "/plans")
                    .getResponse().getStatus()).isEqualTo(200);

            MvcResult refused = post(VIEWER, ACME, ACME_BANKING, plan("Not mine to make"), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .as("a permission key is not actionable by somebody who cannot grant it (§32)")
                    .doesNotContain("plan:create");
            assertThat(planCount(ACME)).isZero();
            assertThat(auditActions(ACME)).isEmpty();
        }

        @Test
        @DisplayName("a company member with no grant gets 404, never 403")
        void noGrantIs404() throws Exception {
            // The project IS in this caller's company, so every cross-company assertion in the
            // suite would still pass while this leaked the project's existence.
            assertThat(post(NO_GRANT, ACME, ACME_BANKING, plan("Probe"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(planCount(ACME)).isZero();
        }

        @Test
        @DisplayName("the other client is refused, and an absent project is 404")
        void otherCompanyAndAbsentAre404() throws Exception {
            assertThat(post(OUTSIDER, UMBRA, ACME_BANKING, plan("Theirs"), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(post(LEAD, ACME, ABSENT, plan("Nowhere"), null)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("the permission is checked BEFORE the payload — a Viewer's bad body is 403")
        void thePermissionIsCheckedFirst() throws Exception {
            // A 400 here would tell a caller who may not create anything which of their fields the
            // server dislikes — and, with a scope id in the body, whether that id names a live row.
            MvcResult refused = post(VIEWER, ACME, ACME_BANKING, plan(""), null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        }
    }

    // =====================================================================
    // What a created plan is
    // =====================================================================

    @Nested
    @DisplayName("The created plan — its key, its status, its scope and its Location")
    class Created {

        @Test
        @DisplayName("TP-1 is allocated, the plan is DRAFT at v1, and Location points at it")
        void theShapeOfACreatedPlan() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), null);
            JsonNode plan = body(created);

            assertThat(plan.get("key").asText()).isEqualTo("TP-1");
            assertThat(plan.get("name").asText()).isEqualTo("Release 4.3");
            assertThat(plan.get("status").asText())
                    .as("DRAFT, not ACTIVE — plan:approve is a different permission")
                    .isEqualTo("DRAFT");
            assertThat(plan.get("currentVersionNo").asInt()).isEqualTo(1);
            assertThat(plan.get("caseCount").asLong()).isZero();
            assertThat(plan.get("passRate").isNull())
                    .as("null means 'not measured'; a zero would be a second definition of pass "
                            + "rate on the same screen as the real one (§38)")
                    .isTrue();
            assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/plans/"
                            + plan.get("id").asText());
        }

        @Test
        @DisplayName("the second plan is TP-2 — the counter moves, per company")
        void keysAreSequential() throws Exception {
            post(LEAD, ACME, ACME_BANKING, plan("First"), null);
            assertThat(body(post(LEAD, ACME, ACME_BANKING, plan("Second"), null))
                    .get("key").asText()).isEqualTo("TP-2");
            // A different PROJECT in the same company shares the counter — the key is per company.
            assertThat(body(post(LEAD, ACME, ACME_MOBILE, plan("Mobile"), null))
                    .get("key").asText()).isEqualTo("TP-3");
        }

        @Test
        @DisplayName("the created plan is in the caller's own list on the very next request")
        void itIsImmediatelyVisible() throws Exception {
            UUID id = UUID.fromString(
                    body(post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), null))
                            .get("id").asText());

            assertThat(ids(body(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans"))))
                    .containsExactly(id);
            assertThat(get(LEAD, ACME, "/projects/" + ACME_BANKING + "/plans/" + id)
                    .getResponse().getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("the milestone and description round-trip; blanks become null")
        void optionalFieldsRoundTrip() throws Exception {
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("description", "  The 4.3 regression plan.  ");
            payload.put("milestoneName", "GA");
            payload.put("milestoneDate", "2026-09-01");

            JsonNode plan = body(post(LEAD, ACME, ACME_BANKING, payload, null));
            assertThat(plan.get("description").asText()).isEqualTo("The 4.3 regression plan.");
            assertThat(plan.get("milestoneName").asText()).isEqualTo("GA");
            assertThat(plan.get("milestoneDate").asText()).isEqualTo("2026-09-01");

            Map<String, Object> blank = plan("Blank");
            blank.put("description", "   ");
            blank.put("milestoneName", "");
            JsonNode second = body(post(LEAD, ACME, ACME_BANKING, blank, null));
            assertThat(second.get("description").isNull())
                    .as("whitespace stored as-is renders as a paragraph nobody wrote")
                    .isTrue();
            assertThat(second.get("milestoneName").isNull()).isTrue();
        }

        @Test
        @DisplayName("criteria come back as [] and the columns are NULL — the two are different")
        void criteriaAreEmptyAndUnset() throws Exception {
            JsonNode plan = body(post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), null));
            assertThat(plan.get("entryCriteria")).isEmpty();
            assertThat(plan.get("exitCriteria")).isEmpty();
            // The wire says "none"; the column says "nobody has decided". A plan:edit slice has to
            // tell those apart, and writing "[]" here would have destroyed the distinction.
            assertThat(criteriaColumnsOf(UUID.fromString(plan.get("id").asText())))
                    .containsExactly(null, null);
        }

        @Test
        @DisplayName("the project's configurations are served on the created plan")
        void configurationsAreServed() throws Exception {
            JsonNode plan = body(post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), null));
            assertThat(plan.get("configurations")).isNotEmpty();
            assertThat(plan.get("configurations").get(0).get("isDefault").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("an audit row names the plan by its key and records the scope size")
        void anAuditRowIsWritten() throws Exception {
            Map<String, Object> payload = plan("Release \"4.3\"\\GA");
            payload.put("suiteIds", List.of(PAYMENTS.toString()));
            post(LEAD, ACME, ACME_BANKING, payload, null);

            Map<String, String> events = auditActions(ACME);
            assertThat(events).containsOnlyKeys("plan.created");
            assertThat(events.get("plan.created"))
                    .contains("\"key\": \"TP-1\"")
                    .contains("\"scopeItems\": 1");
        }
    }

    // =====================================================================
    // The scope
    // =====================================================================

    @Nested
    @DisplayName("The scope — expanded on read, and validated against THIS project")
    class Scope {

        @Test
        @DisplayName("a folder scope item expands to its subtree, and a duplicate case dedupes")
        void theScopeExpandsAndDedupes() throws Exception {
            // PAYMENTS' subtree holds caseA (under REFUNDS). caseB is added directly, and caseA is
            // added AGAIN directly. Three scope items, two distinct cases — so a COUNT over
            // plan_scope_item says 3 and a count that forgets the subtree says 2 for the wrong
            // reason. Neither wrong answer can be mistaken for the right one.
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("suiteIds", List.of(PAYMENTS.toString()));
            payload.put("caseIds", List.of(caseB.toString(), caseA.toString()));

            JsonNode plan = body(post(LEAD, ACME, ACME_BANKING, payload, null));

            assertThat(plan.get("caseCount").asLong()).isEqualTo(2);
            assertThat(scopeItemCount(UUID.fromString(plan.get("id").asText()))).isEqualTo(3);
        }

        @Test
        @DisplayName("a repeated id in the request becomes ONE scope item")
        void theRequestIsDeduped() throws Exception {
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("caseIds", List.of(caseA.toString(), caseA.toString(), caseA.toString()));

            JsonNode plan = body(post(LEAD, ACME, ACME_BANKING, payload, null));
            assertThat(scopeItemCount(UUID.fromString(plan.get("id").asText()))).isEqualTo(1);
        }

        @Test
        @DisplayName("a suite from a SIBLING project is a 400 — the FK and RLS both accept it")
        void aSiblingProjectsSuiteIsRefused() throws Exception {
            // LEAD holds both projects, so no access check refuses this. MOBILE_SUITE is a live row
            // in this caller's own company: only the project predicate in CaseFacts says no.
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("suiteIds", List.of(MOBILE_SUITE.toString()));

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("suiteIds");
            assertThat(planCount(ACME)).isZero();
        }

        @Test
        @DisplayName("a case from a SIBLING project is a 400 on caseIds")
        void aSiblingProjectsCaseIsRefused() throws Exception {
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("caseIds", List.of(mobileCase.toString()));

            MvcResult refused = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("caseIds");
        }

        @Test
        @DisplayName("a deleted case is refused, and an absent id is refused the same way")
        void deletedAndAbsentAreRefused() throws Exception {
            softDeleteCase(ACME, caseB);

            Map<String, Object> deleted = plan("Release 4.3");
            deleted.put("caseIds", List.of(caseB.toString()));
            assertThat(post(LEAD, ACME, ACME_BANKING, deleted, null).getResponse().getStatus())
                    .isEqualTo(400);

            Map<String, Object> absent = plan("Release 4.3");
            absent.put("suiteIds", List.of(ABSENT.toString()));
            assertThat(post(LEAD, ACME, ACME_BANKING, absent, null).getResponse().getStatus())
                    .as("without the pre-check this is a foreign-key violation, i.e. a 500")
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("an EMPTY scope is fine — a named plan with no folders yet is ordinary")
        void anEmptyScopeIsAccepted() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, plan("Just named"), null);
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(created).get("caseCount").asLong()).isZero();
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation, and that a refusal costs nothing")
    class Validation {

        @Test
        @DisplayName("a blank name is a 400 on the field")
        void aBlankNameIsRefused() throws Exception {
            for (String name : new String[] {"", "   "}) {
                MvcResult refused = post(LEAD, ACME, ACME_BANKING, plan(name), null);
                assertThat(refused.getResponse().getStatus()).isEqualTo(400);
                assertThat(errorsOf(refused)).containsKey("name");
            }
        }

        @Test
        @DisplayName("over-long name, description and milestone name are each refused on their field")
        void lengthsAreRefused() throws Exception {
            MvcResult longName = post(LEAD, ACME, ACME_BANKING,
                    plan("n".repeat(PlanEdit.MAX_NAME + 1)), null);
            assertThat(errorsOf(longName)).containsKey("name");

            Map<String, Object> longDescription = plan("Fine");
            longDescription.put("description", "d".repeat(PlanEdit.MAX_DESCRIPTION + 1));
            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING, longDescription, null)))
                    .containsKey("description");

            Map<String, Object> longMilestone = plan("Fine");
            longMilestone.put("milestoneName", "m".repeat(PlanEdit.MAX_MILESTONE_NAME + 1));
            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING, longMilestone, null)))
                    .containsKey("milestoneName");
        }

        @Test
        @DisplayName("a REFUSED create burns no key — the next plan is still TP-1")
        void aRefusalBurnsNoKey() throws Exception {
            assertThat(post(LEAD, ACME, ACME_BANKING, plan(""), null)
                    .getResponse().getStatus()).isEqualTo(400);
            assertThat(post(LEAD, ACME, ACME_BANKING, plan(""), null)
                    .getResponse().getStatus()).isEqualTo(400);

            // Allocating before validating would make this TP-3, and every failed attempt anybody
            // ever makes would leave a permanent hole in the company's sequence.
            assertThat(body(post(LEAD, ACME, ACME_BANKING, plan("Finally"), null))
                    .get("key").asText()).isEqualTo("TP-1");
        }

        @Test
        @DisplayName("a refusal writes no plan, no scope item and no audit row")
        void aRefusalWritesNothing() throws Exception {
            Map<String, Object> payload = plan("");
            payload.put("caseIds", List.of(caseA.toString()));
            post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(planCount(ACME)).isZero();
            assertThat(auditActions(ACME)).isEmpty();
        }
    }

    // =====================================================================
    // Idempotency
    // =====================================================================

    @Nested
    @DisplayName("Idempotency — a retry answers the plan it already made")
    class Idempotency {

        @Test
        @DisplayName("the same key twice: 201 then 200, one plan, one key")
        void aRetryReplays() throws Exception {
            MvcResult first = post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), "intent-1");
            MvcResult second = post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), "intent-1");

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            assertThat(second.getResponse().getStatus())
                    .as("200, so a client can tell 'I made this' from 'I already had this'")
                    .isEqualTo(200);
            assertThat(body(second).get("id").asText()).isEqualTo(body(first).get("id").asText());
            assertThat(second.getResponse().getHeader(HttpHeaders.LOCATION))
                    .as("nothing was created by THIS request")
                    .isNull();
            assertThat(planCount(ACME)).isEqualTo(1);
            assertThat(body(second).get("key").asText()).isEqualTo("TP-1");
        }

        @Test
        @DisplayName("a retry with a DRIFTED body answers the original plan, not a 400")
        void aDriftedRetryStillReplays() throws Exception {
            MvcResult first = post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), "intent-2");

            // The client is asking "did my intent land". Validating first would answer 400 to a
            // request that already created a plan, sending it to fix fields on a row that exists.
            MvcResult retry = post(LEAD, ACME, ACME_BANKING, plan(""), "intent-2");

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("name").asText()).isEqualTo("Release 4.3");
            assertThat(body(retry).get("id").asText()).isEqualTo(body(first).get("id").asText());
        }

        @Test
        @DisplayName("different keys make different plans; no key at all makes a plan every time")
        void distinctIntentsAreDistinct() throws Exception {
            post(LEAD, ACME, ACME_BANKING, plan("A"), "intent-a");
            post(LEAD, ACME, ACME_BANKING, plan("B"), "intent-b");
            post(LEAD, ACME, ACME_BANKING, plan("C"), null);
            post(LEAD, ACME, ACME_BANKING, plan("D"), null);
            assertThat(planCount(ACME)).isEqualTo(4);
        }

        @Test
        @DisplayName("the SAME key in another PROJECT makes its own plan — the scope is the project")
        void theKeyIsScopedToTheProject() throws Exception {
            // The lookup and the unique index must name the same three columns. A company-wide
            // constraint with a project-scoped lookup is a validated request that 500s on insert.
            assertThat(post(LEAD, ACME, ACME_BANKING, plan("Banking"), "shared").getResponse()
                    .getStatus()).isEqualTo(201);
            assertThat(post(LEAD, ACME, ACME_MOBILE, plan("Mobile"), "shared").getResponse()
                    .getStatus()).isEqualTo(201);
            assertThat(planCount(ACME)).isEqualTo(2);
        }

        @Test
        @DisplayName("an over-long key is a 400 on the header, and no plan is made")
        void anOverLongKeyIsRefused() throws Exception {
            MvcResult refused =
                    post(LEAD, ACME, ACME_BANKING, plan("Release 4.3"), "k".repeat(201));
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(refused)).containsKey("Idempotency-Key");
            assertThat(planCount(ACME)).isZero();
        }
    }

    // =====================================================================
    // Isolation
    // =====================================================================

    @Nested
    @DisplayName("Isolation — two clients, one instance")
    class Isolation {

        @Test
        @DisplayName("both companies may hold TP-1, and neither sees the other's")
        void keysAndRowsArePerCompany() throws Exception {
            JsonNode acme = body(post(LEAD, ACME, ACME_BANKING, plan("Acme's"), null));
            JsonNode umbra = body(post(OUTSIDER, UMBRA, UMBRA_BANKING, plan("Umbra's"), null));

            assertThat(acme.get("key").asText()).isEqualTo("TP-1");
            assertThat(umbra.get("key").asText())
                    .as("keys are per COMPANY, so the collision is the design working")
                    .isEqualTo("TP-1");

            assertThat(get(OUTSIDER, UMBRA,
                    "/projects/" + ACME_BANKING + "/plans/" + acme.get("id").asText())
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(ids(body(get(OUTSIDER, UMBRA, "/projects/" + UMBRA_BANKING + "/plans"))))
                    .containsExactly(UUID.fromString(umbra.get("id").asText()));
        }

        @Test
        @DisplayName("a companyId in the body changes nothing — tenancy is never request input")
        void theBodyCannotSelectTheCompany() throws Exception {
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("companyId", UMBRA.toString());

            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, payload, null));
            assertThat(companyOfPlan(UUID.fromString(created.get("id").asText()))).isEqualTo(ACME);
        }
    }

    // =====================================================================
    // helpers — requests
    // =====================================================================

    private Map<String, Object> plan(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", null);
        payload.put("milestoneName", null);
        payload.put("milestoneDate", null);
        payload.put("suiteIds", List.of());
        payload.put("caseIds", List.of());
        return payload;
    }

    /**
     * B9 — a complete plan in one request.
     *
     * <h2>What was wrong, and it was not the flow</h2>
     *
     * <p>{@code TestPlanInput} carried six properties on the argument that <em>a plan is named
     * before anybody knows what would make it releasable</em>. That is true of the usual flow and
     * it was being enforced by a schema rather than by a form — so {@code POST …/plans/{id}/clone}
     * could express sections, a tag arm, prose and both criteria, and {@code POST …/plans} could
     * not. Clone was strictly more expressive than create, which is the shape the audit named.
     *
     * <p>The permission argument the old sentence rested on does not survive contact with the
     * catalogue either: every seeded role holding {@code plan:create} holds {@code plan:edit}, so
     * the narrow input bought a second request rather than a boundary.
     */
    @Nested
    @DisplayName("B9 — the create expresses everything the clone copies")
    class CompleteInOneRequest {

        @Test
        @DisplayName("criteria sent on the create are stored, canonicalized, and served back")
        void criteriaAreAccepted() throws Exception {
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("entryCriteria", List.of(Map.of(
                    "metric", "pass_rate", "op", ">=", "value", 90)));
            payload.put("exitCriteria", List.of(Map.of(
                    "metric", "coverage", "op", ">=", "value", 80)));

            MvcResult response = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(response.getResponse().getStatus())
                    .as("%s", response.getResponse().getContentAsString())
                    .isEqualTo(201);
            JsonNode created = body(response);

            assertThat(created.get("entryCriteria")).hasSize(1);
            assertThat(created.get("entryCriteria").get(0).get("metric").asText())
                    .isEqualTo("pass_rate");
            assertThat(created.get("exitCriteria")).hasSize(1);
            // The columns are written, not left NULL — the distinction the test above defends for a
            // plan that declared none stays exactly where it was.
            assertThat(criteriaColumnsOf(UUID.fromString(created.get("id").asText())))
                    .doesNotContainNull();
        }

        @Test
        @DisplayName("scope prose, a tag arm and sections all arrive on the create")
        void theRestOfThePlanArrives() throws Exception {
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("scopeText", "Payments and refunds.");
            payload.put("outOfScopeText", "Anything behind the partner API.");
            payload.put("tags", List.of("smoke"));
            payload.put("sections", List.of(Map.of(
                    "name", "Refunds",
                    "suiteIds", List.of(PAYMENTS.toString()),
                    "position", 0)));

            MvcResult response = post(LEAD, ACME, ACME_BANKING, payload, null);
            assertThat(response.getResponse().getStatus())
                    .as("%s", response.getResponse().getContentAsString())
                    .isEqualTo(201);
            JsonNode created = body(response);

            assertThat(created.get("scopeText").asText()).isEqualTo("Payments and refunds.");
            assertThat(created.get("outOfScopeText").asText())
                    .isEqualTo("Anything behind the partner API.");
            assertThat(created.get("tags")).hasSize(1);
            assertThat(created.get("sections")).hasSize(1);
            assertThat(created.get("sections").get(0).get("name").asText()).isEqualTo("Refunds");
        }

        @Test
        @DisplayName("a bad criterion is refused here in the words the edit refuses it in")
        void theValidatorsAreTheEditsOwn() throws Exception {
            // The reason `validateCreate` calls the same validators rather than restating them: a
            // metric this product cannot evaluate must not be storable through the door that was
            // opened later.
            Map<String, Object> payload = plan("Release 4.3");
            payload.put("exitCriteria", List.of(Map.of(
                    "metric", "vibes", "op", ">=", "value", 90)));

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(result).get("errors").has("exitCriteria")).isTrue();
        }

        @Test
        @DisplayName("sending none of the six is unchanged — every one of them is optional")
        void theUsualFlowIsUntouched() throws Exception {
            // The guard against the obvious way to get B9 wrong: making any of them required would
            // break the New Plan dialog, which asks for a name and a milestone and nothing else.
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, plan("Release 4.4"), null));

            assertThat(created.get("entryCriteria")).isEmpty();
            assertThat(created.get("scopeText").isNull()).isTrue();
            assertThat(criteriaColumnsOf(UUID.fromString(created.get("id").asText())))
                    .containsExactly(null, null);
        }
    }

    private MvcResult post(
            UUID userId, UUID companyId, UUID projectId, Map<String, Object> payload,
            String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders
                .post("/api/v1/projects/" + projectId + "/plans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
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

    private static List<UUID> ids(JsonNode array) {
        List<UUID> ids = new ArrayList<>();
        array.forEach(node -> ids.add(UUID.fromString(node.get("id").asText())));
        return ids;
    }

    private Map<String, List<String>> errorsOf(MvcResult result) throws Exception {
        Map<String, List<String>> errors = new LinkedHashMap<>();
        JsonNode node = body(result).get("errors");
        if (node == null) {
            return errors;
        }
        node.properties().forEach(field -> {
            List<String> messages = new ArrayList<>();
            field.getValue().forEach(message -> messages.add(message.asText()));
            errors.put(field.getKey(), messages);
        });
        return errors;
    }

    private UUID createCase(UUID userId, UUID companyId, UUID projectId, String title, UUID suiteId)
            throws Exception {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove it.");
        payload.put("description", "Seeded by PlanCreateEndpointTest.");
        payload.put("expectedOutcome", "It works.");
        payload.put("priority", "HIGH");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("estimatedDurationSec", 120);
        payload.put("stepFormat", "STRUCTURED");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", null);
        step.put("keyword", null);
        step.put("action", "Do the thing");
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("sharedStepId", null);
        payload.put("steps", List.of(step));
        payload.put("changeComment", null);

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
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
        UUID id = UUID.nameUUIDFromBytes(("plan-create:" + marker).getBytes());
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
                execute(connection, "DELETE FROM test_case_version");
                execute(connection, "DELETE FROM test_step");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite_version");
                execute(connection, "DELETE FROM suite");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void resetPlanCounter() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection,
                        "UPDATE key_sequence SET next_value = 1 WHERE entity_type = 'PLAN'");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1");
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2, "Refunds", "s1.s2");
            suite(connection, SPARE, ACME, ACME_BANKING, null, "TS-3", 3, "Spare", "s3");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-4", 4, "Mobile", "s4");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1");
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
            String key, long keySeq, String name, String path) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO suite
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED')
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, key);
            statement.setLong(5, keySeq);
            statement.setString(6, name);
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // helpers — reading rows back through the app role, past the API
    // =====================================================================

    private long planCount(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM test_plan");
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private long scopeItemCount(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM plan_scope_item WHERE plan_id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private List<String> criteriaColumnsOf(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT CAST(entry_criteria AS text), CAST(exit_criteria AS text) "
                             + "FROM test_plan WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                List<String> columns = new ArrayList<>();
                columns.add(rows.getString(1));
                columns.add(rows.getString(2));
                return columns;
            }
        }
    }

    private UUID companyOfPlan(UUID planId) throws SQLException {
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement(
                     "SELECT company_id FROM test_plan WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, planId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    private Map<String, String> auditActions(UUID companyId) throws SQLException {
        Map<String, String> events = new LinkedHashMap<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT action, CAST(changes AS text) FROM audit_event ORDER BY occurred_at");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                events.put(rows.getString(1), rows.getString(2));
            }
        }
        return events;
    }

    private void softDeleteCase(UUID companyId, UUID caseId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "UPDATE test_case SET deleted_at = now() WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            statement.executeUpdate();
        }
    }
}
