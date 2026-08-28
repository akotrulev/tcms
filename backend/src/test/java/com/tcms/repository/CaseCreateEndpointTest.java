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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * {@code POST /projects/{projectId}/cases} — the other half of authoring.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>A create gated like a read.</strong> Every read in this module asks
 *       {@code project:read}, which all six roles hold, so a create gated the same way is one a Viewer
 *       may perform — and a Viewer is the client's own stakeholder, invited to read coverage without
 *       adding to it.</li>
 *   <li><strong>A key allocated by anything other than the counter.</strong> {@code MAX(key_seq) + 1}
 *       and read-then-write both pass a single-writer test and issue duplicates under the load a client
 *       importing a suite produces. What is asserted here instead is the observable consequence: keys
 *       advance across projects, never repeat, and each company starts at 1.</li>
 *   <li><strong>A created case with no v1.</strong> {@code CaseVersions.decide} would number the first
 *       version 2, because a fresh row's {@code current_version_no} defaults to 1 — reproducing on a
 *       brand-new case the gap that is only honest for cases predating versioning.</li>
 *   <li><strong>A retry creating a second case.</strong> The SPA has sent one {@code Idempotency-Key}
 *       per intent since Phase 1 and the mock honours it; nothing on the server did. The assertion is
 *       that the second call answers with the <em>same</em> case and writes no second version, audit
 *       row or key.</li>
 *   <li><strong>A field accepted and discarded.</strong> The same six the update endpoint refuses, for
 *       the same reason, so mock mode cannot pass a payload live mode rejects.</li>
 *   <li><strong>A step id adopted from another case.</strong> On a create every submitted id belongs to
 *       somebody else, so honoring one would move that step's recorded results onto this case.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — sharing them with another test class is how
 * handoff §19's first defect happened, green alone and red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Case create endpoint — keys, v1, and what it must refuse")
class CaseCreateEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("b1000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("b1000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. */
    private static final UUID ACME_BANKING = UUID.fromString("b1001000-0000-4000-8000-000000000001");
    /** Same company, granted to LEAD as well — so key numbering can be observed spanning projects. */
    private static final UUID ACME_MOBILE = UUID.fromString("b1001000-0000-4000-8000-000000000002");
    /** Same company, granted to nobody here. */
    private static final UUID ACME_LOCKED = UUID.fromString("b1001000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_BANKING = UUID.fromString("b1002000-0000-4000-8000-000000000001");

    /** Test Lead on ACME_BANKING and ACME_MOBILE. Holds {@code case:create}. */
    private static final UUID LEAD = UUID.fromString("b1003000-0000-4000-8000-000000000001");
    /** Test Engineer on ACME_BANKING. Holds {@code case:create}. */
    private static final UUID ENGINEER = UUID.fromString("b1003000-0000-4000-8000-000000000002");
    /** Viewer on ACME_BANKING. Holds {@code project:read} and NOT {@code case:create}. */
    private static final UUID VIEWER = UUID.fromString("b1003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("b1003000-0000-4000-8000-000000000004");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("b1003000-0000-4000-8000-000000000005");

    private static final UUID PAYMENTS = UUID.fromString("b1004000-0000-4000-8000-000000000001");
    private static final UUID REFUNDS = UUID.fromString("b1004000-0000-4000-8000-000000000002");
    private static final UUID MOBILE_SUITE = UUID.fromString("b1004000-0000-4000-8000-000000000003");
    private static final UUID LOCKED_SUITE = UUID.fromString("b1004000-0000-4000-8000-000000000004");
    private static final UUID UMBRA_SUITE = UUID.fromString("b1004000-0000-4000-8000-000000000005");

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
            company(ACME, "create-acme", "Acme", now);
            company(UMBRA, "create-umbra", "Umbra", now);

            activeUser(LEAD, "lead@create-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@create-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@create-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@create-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@create-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "CAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "CAMOB", "Mobile App", now);
            seedProject(ACME_LOCKED, ACME, "CALCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("cr-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("cr-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grantOnProject("cr-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("cr-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "CUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("cr-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        seedTrees();
        resetMutableRows();
        seedExistingCases();
    }

    /**
     * Deletes everything a test can write and puts the key counters back to 1.
     *
     * <h2>The counters are the part a reader would not expect</h2>
     *
     * <p>{@code tcms_seed_company_defaults} is {@code ON CONFLICT DO NOTHING} — correct for
     * provisioning, and it therefore does <strong>not</strong> reset {@code next_value}. Without this
     * every assertion about {@code TC-1} would depend on the order the class happened to run in: green
     * alone, red in the suite, which is handoff §19's defect wearing a different hat.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                // Through each company's OWN context: RLS means the other's connection could not see
                // these rows to delete them, which is the isolation working rather than a leftover row
                // to debug later.
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "UPDATE key_sequence SET next_value = 1");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gate
    // =====================================================================

    @Nested
    @DisplayName("The gate — case:create, and 403 only after the read gate has passed")
    class TheGate {

        @Test
        @DisplayName("a Test Lead creates a case and gets 201 with the whole case")
        void leadCanCreate() throws Exception {
            MvcResult result = post(LEAD, ACME, ACME_BANKING, newCase("First case"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("title").asText()).isEqualTo("First case");
        }

        @Test
        @DisplayName("a Test Engineer creates a case — case:create is theirs unqualified")
        void engineerCanCreate() throws Exception {
            assertThat(post(ENGINEER, ACME, ACME_BANKING, newCase("Engineer's case"), null)
                    .getResponse().getStatus())
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a Viewer who CAN read the project gets 403, and no case is written")
        void viewerIsForbidden() throws Exception {
            // The failure this catches: gating the create on project:read, which every role holds.
            assertThat(listStatus(VIEWER, ACME, ACME_BANKING))
                    .as("a Viewer must be able to read the list and still not add to it")
                    .isEqualTo(200);

            MvcResult result = post(VIEWER, ACME, ACME_BANKING, newCase("Viewer wrote this"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(casesIn(ACME_BANKING)).isEmpty();
        }

        @Test
        @DisplayName("the 403 body names the action and never the permission key")
        void forbiddenBodyIsActionable() throws Exception {
            MvcResult result = post(VIEWER, ACME, ACME_BANKING, newCase("nope"), null);

            assertThat(body(result).get("detail").asText())
                    .contains("permission to create test cases");
            // Naming case:create publishes the catalogue to the one caller who cannot use it.
            assertThat(result.getResponse().getContentAsString()).doesNotContain("case:create");
        }

        @Test
        @DisplayName("a member with no grant gets 404 with no body — persona 3, not 403")
        void noGrantIsNotFound() throws Exception {
            // 403 here would confirm the project exists, which is the inference channel §6t closes.
            MvcResult result = post(NO_GRANT, ACME, ACME_BANKING, newCase("x"), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        }

        @Test
        @DisplayName("a project in the caller's own company that they hold no grant on answers 404")
        void anUngrantedSiblingProjectIsNotFound() throws Exception {
            MvcResult result = post(ENGINEER, ACME, ACME_LOCKED, newCase("x", LOCKED_SUITE), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(casesIn(ACME_LOCKED)).isEmpty();
        }

        @Test
        @DisplayName("another client's project answers 404 and nothing is written into it")
        void crossCompanyIsNotFound() throws Exception {
            MvcResult result = post(LEAD, ACME, UMBRA_BANKING, newCase("x", UMBRA_SUITE), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(casesIn(UMBRA_BANKING)).isEmpty();
        }

        @Test
        @DisplayName("a caller from another company cannot create in it through their own token either")
        void theOtherDirectionIsAlsoRefused() throws Exception {
            assertThat(post(OUTSIDER, UMBRA, ACME_BANKING, newCase("x"), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(casesIn(ACME_BANKING)).isEmpty();
        }

        @Test
        @DisplayName("unauthenticated is 401")
        void unauthenticatedIsRejected() throws Exception {
            int status = mvc.perform(MockMvcRequestBuilders.post(path(ACME_BANKING))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(newCase("x"))))
                    .andReturn().getResponse().getStatus();

            assertThat(status).isEqualTo(401);
        }
    }

    // =====================================================================
    // Keys
    // =====================================================================

    @Nested
    @DisplayName("Display keys — company-scoped, sequential, and never reused")
    class Keys {

        @Test
        @DisplayName("the first case in a company is TC-1, and the key is server-allocated")
        void firstKeyIsOne() throws Exception {
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, newCase("First"), null));

            assertThat(created.get("key").asText()).isEqualTo("TC-1");
        }

        @Test
        @DisplayName("keys advance ACROSS projects, because the counter is per company (§6t)")
        void keysAreCompanyScopedNotProjectScoped() throws Exception {
            // Per-project numbering was the superseded decision (handoff §3). Two cases in different
            // projects of one company must not both be TC-1 — the key is what a client pastes into a
            // ticket, and two of them meaning different cases is the whole failure.
            JsonNode inBanking = body(post(LEAD, ACME, ACME_BANKING, newCase("Banking"), null));
            JsonNode inMobile =
                    body(post(LEAD, ACME, ACME_MOBILE, newCase("Mobile", MOBILE_SUITE), null));

            assertThat(inBanking.get("key").asText()).isEqualTo("TC-1");
            assertThat(inMobile.get("key").asText()).isEqualTo("TC-2");
        }

        @Test
        @DisplayName("another company numbers independently and starts at TC-1 again")
        void companiesNumberIndependently() throws Exception {
            post(LEAD, ACME, ACME_BANKING, newCase("Acme one"), null);
            post(LEAD, ACME, ACME_BANKING, newCase("Acme two"), null);

            JsonNode theirs =
                    body(post(OUTSIDER, UMBRA, UMBRA_BANKING, newCase("Theirs", UMBRA_SUITE), null));

            // Not TC-3: a shared counter would tell one client how many cases another has written.
            assertThat(theirs.get("key").asText()).isEqualTo("TC-1");
        }

        @Test
        @DisplayName("key_seq is stored and is the numeric part — the only column that may sort keys")
        void keySeqIsStoredAndAgreesWithTheKey() throws Exception {
            post(LEAD, ACME, ACME_BANKING, newCase("First"), null);
            post(LEAD, ACME, ACME_BANKING, newCase("Second"), null);

            // TC-10000 sorts before TC-9999 as text (§3 lesson 6), so every sort resolves on key_seq
            // and the two have to agree on every row.
            assertThat(casesIn(ACME_BANKING))
                    .extracting(CaseRow::key, CaseRow::keySeq)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("TC-1", 1L),
                            org.assertj.core.groups.Tuple.tuple("TC-2", 2L));
        }

        @Test
        @DisplayName("no two cases ever share a key")
        void keysAreUnique() throws Exception {
            for (int i = 0; i < 6; i++) {
                post(LEAD, ACME, ACME_BANKING, newCase("Case " + i), null);
            }

            assertThat(casesIn(ACME_BANKING)).extracting(CaseRow::key)
                    .doesNotHaveDuplicates()
                    .containsExactly("TC-1", "TC-2", "TC-3", "TC-4", "TC-5", "TC-6");
        }

        @Test
        @DisplayName("a rejected payload does not consume a key")
        void aRefusedCreateDoesNotBurnAKey() throws Exception {
            // Numbers are never recycled, so allocating before validating would leave a permanent hole
            // for every typo — and the first case a client ever sees would be TC-4.
            post(LEAD, ACME, ACME_BANKING, newCase(" "), null);
            post(LEAD, ACME, ACME_BANKING, newCase(""), null);

            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, newCase("At last"), null));

            assertThat(created.get("key").asText()).isEqualTo("TC-1");
        }
    }

    // =====================================================================
    // Version 1
    // =====================================================================

    @Nested
    @DisplayName("Version 1 — written with the case, so a created case has no gap")
    class VersionOne {

        @Test
        @DisplayName("creating a case writes v1, and the case says it is at v1")
        void v1IsWritten() throws Exception {
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, newCase("Versioned"), null));
            UUID caseId = UUID.fromString(created.get("id").asText());

            assertThat(created.get("currentVersionNo").asInt()).isEqualTo(1);
            assertThat(versionsOf(caseId)).singleElement()
                    .satisfies(row -> assertThat(row.versionNo()).isEqualTo(1));
        }

        @Test
        @DisplayName("v1 is NOT v2 — the decide() path would have numbered it 2 and left no v1")
        void theFirstVersionIsNotNumberedTwo() throws Exception {
            // current_version_no is NOT NULL DEFAULT 1, so decide() answers 1 + 1. Reusing it here
            // would give every case in the product a missing first version, indistinguishable from the
            // pre-versioning gap that is only honest for cases predating the write path.
            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, newCase("Numbered"), null));

            assertThat(versionsOf(caseId)).extracting(VersionRow::versionNo).containsExactly(1);
        }

        @Test
        @DisplayName("a subsequent edit writes v2, so the created case's history is gapless")
        void anEditAfterCreateBecomesV2() throws Exception {
            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, newCase("Original"), null));

            patch(LEAD, ACME, ACME_BANKING, caseId, newCase("Edited"));

            assertThat(versionsOf(caseId)).extracting(VersionRow::versionNo).containsExactly(2, 1);
        }

        @Test
        @DisplayName("re-saving the created content unchanged writes no v2 — the create's hash is comparable")
        void anUnchangedEditAfterCreateWritesNothing() throws Exception {
            // The property that makes this work: initial() hashes the same normalized CaseEdit that
            // decide() hashes. A create that hashed something else would make the first edit after
            // every create look like a change.
            Map<String, Object> payload = newCase("Stable");
            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, payload, null));

            patch(LEAD, ACME, ACME_BANKING, caseId, payload);

            assertThat(versionsOf(caseId)).extracting(VersionRow::versionNo).containsExactly(1);
        }

        @Test
        @DisplayName("v1 records its author and comment, and is not pinned")
        void v1RecordsWhoAndWhy() throws Exception {
            Map<String, Object> payload = newCase("Documented");
            payload.put("changeComment", "Imported from the migration spreadsheet");

            UUID caseId = idOf(post(ENGINEER, ACME, ACME_BANKING, payload, null));

            assertThat(versionsOf(caseId)).singleElement().satisfies(row -> {
                assertThat(row.createdBy()).isEqualTo(ENGINEER);
                assertThat(row.changeComment()).isEqualTo("Imported from the migration spreadsheet");
                // Nothing pins yet — test_execution has no writer — so false is honest, not a
                // placeholder.
                assertThat(row.pinned()).isFalse();
            });
        }

        @Test
        @DisplayName("the v1 snapshot is the whole resolved case, including its key and steps")
        void theSnapshotHoldsTheWholeCase() throws Exception {
            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, newCase("Snapshot me"), null));

            JsonNode snapshot = json.readTree(versionsOf(caseId).getFirst().snapshot());

            assertThat(snapshot.get("title").asText()).isEqualTo("Snapshot me");
            assertThat(snapshot.get("key").asText()).isEqualTo("TC-1");
            assertThat(snapshot.get("currentVersionNo").asInt()).isEqualTo(1);
            assertThat(snapshot.get("steps")).hasSize(2);
            assertThat(snapshot.has("preconditions")).isTrue();
        }
    }

    // =====================================================================
    // Idempotency
    // =====================================================================

    @Nested
    @DisplayName("Idempotency — one key is one intent, not one attempt")
    class Idempotency {

        @Test
        @DisplayName("the same key twice creates ONE case, and the retry answers 200 with it")
        void aRetryReturnsTheSameCase() throws Exception {
            String key = "intent-" + UUID.randomUUID();

            MvcResult first = post(LEAD, ACME, ACME_BANKING, newCase("Once"), key);
            MvcResult second = post(LEAD, ACME, ACME_BANKING, newCase("Once"), key);

            assertThat(first.getResponse().getStatus()).isEqualTo(201);
            // 200, not 201: nothing was created by the second call, and a client can tell "I made
            // this" from "I already had this".
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            assertThat(idOf(second)).isEqualTo(idOf(first));
            assertThat(casesIn(ACME_BANKING)).hasSize(1);
        }

        @Test
        @DisplayName("a retry writes no second version, audit row or key")
        void aRetryWritesNothing() throws Exception {
            String key = "intent-" + UUID.randomUUID();

            post(LEAD, ACME, ACME_BANKING, newCase("Once"), key);
            post(LEAD, ACME, ACME_BANKING, newCase("Once"), key);

            assertThat(versionsOf(idOf(post(LEAD, ACME, ACME_BANKING, newCase("Once"), key))))
                    .hasSize(1);
            assertThat(auditRows()).hasSize(1);
            // The counter did not move either, so the next real create is TC-2 and not TC-4.
            assertThat(body(post(LEAD, ACME, ACME_BANKING, newCase("Next"), null)).get("key").asText())
                    .isEqualTo("TC-2");
        }

        @Test
        @DisplayName("a retry whose payload has drifted still returns the original case, not a 400")
        void aRetryIgnoresADriftedPayload() throws Exception {
            // The client is asking "did my intent land", and answering 400 would send it to fix fields
            // on a case that already exists. The key is checked before validation for exactly this.
            String key = "intent-" + UUID.randomUUID();
            post(LEAD, ACME, ACME_BANKING, newCase("Original"), key);

            MvcResult retry = post(LEAD, ACME, ACME_BANKING, newCase(""), key);

            assertThat(retry.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(retry).get("title").asText()).isEqualTo("Original");
        }

        @Test
        @DisplayName("two different keys with identical bodies create two cases")
        void differentKeysCreateDifferentCases() throws Exception {
            // The key identifies the intent, not the content: two people may legitimately write the
            // same case twice, and deduplicating on the body would refuse a real second case.
            post(LEAD, ACME, ACME_BANKING, newCase("Same title"), "intent-a");
            post(LEAD, ACME, ACME_BANKING, newCase("Same title"), "intent-b");

            assertThat(casesIn(ACME_BANKING)).hasSize(2)
                    .extracting(CaseRow::key).containsExactly("TC-1", "TC-2");
        }

        @Test
        @DisplayName("no key at all creates a case every time — the header is optional")
        void withoutAKeyEveryCallCreates() throws Exception {
            post(LEAD, ACME, ACME_BANKING, newCase("One"), null);
            post(LEAD, ACME, ACME_BANKING, newCase("One"), null);

            assertThat(casesIn(ACME_BANKING)).hasSize(2);
        }

        @Test
        @DisplayName("a blank key is treated as absent, not as a key everyone shares")
        void aBlankKeyIsAbsent() throws Exception {
            // Stored as-is, a blank key becomes ONE key that every misconfigured client replays
            // against, and the project accepts exactly one case from all of them, forever. The
            // assertion has to send the SAME blank value twice: two different blanks would not
            // collide even without the trim, which is how the first version of this test passed
            // against a mutation that removed it.
            post(LEAD, ACME, ACME_BANKING, newCase("One"), "   ");
            post(LEAD, ACME, ACME_BANKING, newCase("Two"), "   ");
            post(LEAD, ACME, ACME_BANKING, newCase("Three"), "");
            post(LEAD, ACME, ACME_BANKING, newCase("Four"), "");

            assertThat(casesIn(ACME_BANKING)).hasSize(4);
        }

        @Test
        @DisplayName("the same key in a DIFFERENT project creates a case, matching the constraint's scope")
        void keysAreScopedToTheProject() throws Exception {
            // The unique index is (company_id, project_id, key) and the lookup names the same three.
            // A company-wide constraint with a per-project lookup would pass the check and violate the
            // index — a validated request that 500s on insert.
            String key = "intent-shared";

            MvcResult inBanking = post(LEAD, ACME, ACME_BANKING, newCase("Banking"), key);
            MvcResult inMobile =
                    post(LEAD, ACME, ACME_MOBILE, newCase("Mobile", MOBILE_SUITE), key);

            assertThat(inBanking.getResponse().getStatus()).isEqualTo(201);
            assertThat(inMobile.getResponse().getStatus()).isEqualTo(201);
            assertThat(idOf(inMobile)).isNotEqualTo(idOf(inBanking));
        }

        @Test
        @DisplayName("an absurdly long key is refused rather than stored")
        void anOverlongKeyIsRefused() throws Exception {
            MvcResult result =
                    post(LEAD, ACME, ACME_BANKING, newCase("Long key"), "k".repeat(201));

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("Idempotency-Key");
            assertThat(casesIn(ACME_BANKING)).isEmpty();
        }
    }

    // =====================================================================
    // The created row
    // =====================================================================

    @Nested
    @DisplayName("What the server assigns, and what it refuses to be told")
    class ServerAssigned {

        @Test
        @DisplayName("a new case is DRAFT — a create cannot approve itself")
        void newCasesAreDraft() throws Exception {
            // A status an ordinary create could set is an approval its author granted themselves,
            // which belongs to the review workflow (§6g). lifecycleStatus is not on TestCaseInput.
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, newCase("Fresh"), null));

            assertThat(created.get("lifecycleStatus").asText()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("the creator owns the case")
        void theCreatorIsTheOwner() throws Exception {
            // ownerId is refused as an input — choosing someone else's case needs a membership check —
            // but a case nobody owns is a row nobody is answerable for, and authorship is what
            // case:delete's OWN qualifier will resolve against.
            JsonNode created = body(post(ENGINEER, ACME, ACME_BANKING, newCase("Mine"), null));

            assertThat(created.get("owner").get("id").asText()).isEqualTo(ENGINEER.toString());
        }

        @Test
        @DisplayName("created_at and created_by are recorded on the row")
        void creationIsAttributed() throws Exception {
            UUID caseId = idOf(post(ENGINEER, ACME, ACME_BANKING, newCase("Attributed"), null));

            assertThat(casesIn(ACME_BANKING)).singleElement().satisfies(row -> {
                assertThat(row.createdBy()).isEqualTo(ENGINEER);
                assertThat(row.createdAt()).isNotNull();
            });
            assertThat(caseId).isNotNull();
        }

        @Test
        @DisplayName("the response carries a Location naming the created case")
        void locationNamesTheCase() throws Exception {
            MvcResult result = post(LEAD, ACME, ACME_BANKING, newCase("Located"), null);

            assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/cases/" + idOf(result));
        }

        @Test
        @DisplayName("the created case is immediately readable through GET, with the same shape")
        void theCreatedCaseIsReadable() throws Exception {
            // The create response and GET are one renderer. Two would differ by exactly the field a
            // reader checks first, with neither screen revealing which is stored.
            MvcResult created = post(LEAD, ACME, ACME_BANKING, newCase("Readable"), null);
            UUID caseId = idOf(created);

            MvcResult fetched = mvc.perform(MockMvcRequestBuilders
                            .get(path(ACME_BANKING) + "/" + caseId)
                            .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                    .andReturn();

            assertThat(fetched.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(fetched).get("key").asText()).isEqualTo(body(created).get("key").asText());
            assertThat(body(fetched).get("title").asText()).isEqualTo("Readable");
        }

        @Test
        @DisplayName("steps are stored with fresh ids and positions from the payload order")
        void stepsAreStoredInOrder() throws Exception {
            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, newCase("Stepped"), null));

            assertThat(stepsOf(caseId)).extracting(StepRow::position, StepRow::action)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(0, "Open the refund screen"),
                            org.assertj.core.groups.Tuple.tuple(1, "Submit"));
        }

        @Test
        @DisplayName("an audit row records the creation, with the key an auditor recognises it by")
        void creationIsAudited() throws Exception {
            UUID caseId = idOf(post(ENGINEER, ACME, ACME_BANKING, newCase("Audited"), null));

            assertThat(auditRows()).singleElement().satisfies(row -> {
                assertThat(row.action()).isEqualTo("case.created");
                assertThat(row.targetType()).isEqualTo("test_case");
                assertThat(row.targetIds()).containsExactly(caseId);
                assertThat(row.actorUserId()).isEqualTo(ENGINEER);
                assertThat(row.projectId()).isEqualTo(ACME_BANKING);
                assertThat(row.companyId()).isEqualTo(ACME);
                assertThat(row.changes()).contains("TC-1").contains("\"versionNo\": 1");
            });
        }

        @Test
        @DisplayName("a Gherkin case stores STEPS — 201, with keyword, doc string and table")
        void gherkinCasesStoreSteps() throws Exception {
            // ~~a Gherkin case stores its scenario and no steps~~ → §255: step_format is a lens.
            // This is the arm the whole initiative exists for — a scenario that is rows is a
            // scenario a tester can record a verdict against, step by step.
            Map<String, Object> payload = newCase("Gherkin case");
            payload.put("stepFormat", "GHERKIN");
            Map<String, Object> given = gherkinStep("GIVEN", "a captured payment");
            given.put("docString", "{\n  \"amount\": 100\n}");
            Map<String, Object> when = gherkinStep("WHEN", "I refund these");
            when.put("dataTable", List.of(List.of("id", "amount"), List.of("p-1", "100")));
            payload.put("steps", List.of(
                    given, when, gherkinStep("THEN", "the payment reaches REFUNDED")));

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);
            UUID caseId = idOf(result);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(stepsOf(caseId)).hasSize(3);
            JsonNode steps = body(result).get("steps");
            assertThat(steps.get(0).get("keyword").asText()).isEqualTo("GIVEN");
            assertThat(steps.get(0).get("docString").asText()).contains("\"amount\": 100");
            assertThat(steps.get(1).get("dataTable").get(1).get(0).asText()).isEqualTo("p-1");
            assertThat(body(result).get("gherkinSource").isNull())
                    .as("a case with rows never carries the retired blob — the two would be the "
                            + "same procedure spelled twice, disagreeing after the first edit")
                    .isTrue();
        }

        @Test
        @DisplayName("a keyword-less step in the Gherkin lens is 400, keyed on steps")
        void gherkinNeedsKeywords() throws Exception {
            Map<String, Object> payload = newCase("Gherkin case");
            payload.put("stepFormat", "GHERKIN");
            payload.put("steps", List.of(
                    gherkinStep("GIVEN", "a captured payment"),
                    step(null, "I refund it")));

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result).get("steps")).contains("Step 2 needs a keyword.");
        }

        @Test
        @DisplayName("importedFrom is null on a case nobody imported")
        void nothingIsImportedYet() throws Exception {
            // V69 adds the columns and 001- T6 adds their writer, so this is the whole of what
            // T1 can assert about them — and it is worth asserting: a required contract field
            // the server never emits is a key every consumer has to guess the absence of.
            MvcResult result = post(LEAD, ACME, ACME_BANKING, newCase("Ordinary"), null);

            assertThat(body(result).get("importedFrom").isNull()).isTrue();
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation — the same rules as an edit, refused the same way")
    class Validation {

        @Test
        @DisplayName("a blank title is 400 keyed on title, and nothing is written")
        void blankTitleIsRefused() throws Exception {
            MvcResult result = post(LEAD, ACME, ACME_BANKING, newCase("   "), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("title");
            assertThat(casesIn(ACME_BANKING)).isEmpty();
        }

        @Test
        @DisplayName("a suite from another project is 400 keyed on suiteId, not 404")
        void aForeignSuiteIsAFieldError() throws Exception {
            // The project resolved fine and the caller submitted a value it does not offer, so the
            // message belongs on the picker. Accepting it would put the case in another project's tree,
            // where no read filters it out — so it disappears from both.
            MvcResult result =
                    post(LEAD, ACME, ACME_BANKING, newCase("Misfiled", MOBILE_SUITE), null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("suiteId");
        }

        @Test
        @DisplayName("each still-refused field is refused by name, not silently dropped")
        void theRefusedFieldsAreNamed() throws Exception {
            // It was six. `classificationValueIds` left the list in §68,
            // `preconditions`/`postconditions` in §95 and `tags` in §96 — each field leaving in turn is what
            // CaseUpdateRequest's header says will happen, and the boundary is deleted in the one
            // place it was written. `classificationValueIds` stays HERE, but for a different
            // reason: it is accepted now and this random id is refused because the project does not
            // offer it, which is a vocabulary error rather than an unimplemented field.
            Map<String, List<String>> refused = new LinkedHashMap<>();
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("ownerId", ENGINEER.toString());
            values.put("classificationValueIds", List.of(UUID.randomUUID().toString()));
            values.put("automationTags", List.of("nightly"));

            for (Map.Entry<String, Object> field : values.entrySet()) {
                Map<String, Object> payload = newCase("With " + field.getKey());
                payload.put(field.getKey(), field.getValue());
                MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

                assertThat(result.getResponse().getStatus())
                        .as("%s must be refused, not accepted and dropped", field.getKey())
                        .isEqualTo(400);
                refused.putAll(errorsOf(result));
            }

            assertThat(refused).containsOnlyKeys(
                    "ownerId", "classificationValueIds", "automationTags");
            assertThat(casesIn(ACME_BANKING)).isEmpty();
        }

        @Test
        @DisplayName("an INLINE condition is refused by name — the half of §95 that is not built")
        void anInlineConditionIsRefusedByName() throws Exception {
            // Well-formed against CaseConditionInput, unlike the map the old six-field test sent,
            // which Jackson refused at binding and which therefore produced a bodiless 400 that
            // proved nothing about the validator. Nothing renders an inline attachment, so storing
            // one would be a save that succeeds and a condition the author can never see again.
            Map<String, Object> payload = newCase("With an inline condition");
            payload.put("preconditions", List.of(Map.of("inlineBody", List.of("Do it by hand"))));

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("preconditions");
            assertThat(casesIn(ACME_BANKING)).isEmpty();
        }

        @Test
        @DisplayName("a condition from another project is refused — the FK would have accepted it")
        void aForeignConditionIsAFieldError() throws Exception {
            Map<String, Object> payload = newCase("Reaching across");
            payload.put("preconditions",
                    List.of(Map.of("conditionId", UUID.randomUUID().toString())));

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("preconditions");
        }

        @Test
        @DisplayName("a submitted step id is refused — on a create it can only be another case's step")
        void aStepIdIsRefused() throws Exception {
            // Adopting it would move that step's identity, and therefore every result recorded against
            // it, onto this brand-new case.
            Map<String, Object> payload = newCase("Thief");
            payload.put("steps", List.of(step(EXISTING_STEP, "Someone else's step")));

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("steps");
        }

        @Test
        @DisplayName("an automation key another case already holds is 400, not a 500 from the index")
        void aTakenAutomationKeyIsAFieldError() throws Exception {
            // test_case_automation_key_idx is company-wide and does not exclude soft-deleted rows, so
            // the retired case seeded below still reserves its key. A constraint violation here would
            // be a form that validated and then 500ed on submit.
            Map<String, Object> payload = newCase("Duplicate runner");
            payload.put("automationStatus", "AUTOMATED");
            payload.put("automationKey", TAKEN_AUTOMATION_KEY);

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(errorsOf(result)).containsKey("automationKey");
        }

        @Test
        @DisplayName("AUTOMATED with no automation key is refused")
        void automatedNeedsAKey() throws Exception {
            Map<String, Object> payload = newCase("Automated, unnamed");
            payload.put("automationStatus", "AUTOMATED");
            payload.put("automationKey", null);

            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING, payload, null)))
                    .containsKey("automationKey");
        }

        @Test
        @DisplayName("NOT_AUTOMATABLE with no reason is refused — the database check agrees")
        void notAutomatableNeedsAReason() throws Exception {
            Map<String, Object> payload = newCase("Impossible");
            payload.put("automationStatus", "NOT_AUTOMATABLE");
            payload.put("notAutomatableReason", null);

            assertThat(errorsOf(post(LEAD, ACME, ACME_BANKING, payload, null)))
                    .containsKey("notAutomatableReason");
        }

        /*
          ~~STRUCTURED with no steps is refused, and GHERKIN with no scenario too.~~
          → REVERSED IN §200, and moved to `TheProcedureMayBeDeferred` below, which asserts the
          opposite of every line this test held.

          It is left here as a comment rather than deleted because it is the one rule where this
          class's own title — "the same rules as an edit, refused the same way" — stopped being
          true, and a reader who finds only the new test cannot tell whether the old behaviour was
          considered or merely never existed. It was considered from M0 to §199 and it made the
          plan's Test cases tab unable to save.
        */

        @Test
        @DisplayName("blank optional text is stored as null, not as an empty string")
        void blankOptionalsBecomeNull() throws Exception {
            // '' and "never filled in" would otherwise be two values that render identically, so the
            // next query for unfilled objectives finds half of them.
            Map<String, Object> payload = newCase("Sparse");
            payload.put("objective", "  ");
            payload.put("description", "");

            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, payload, null));

            assertThat(casesIn(ACME_BANKING)).singleElement()
                    .satisfies(row -> assertThat(row.objective()).isNull());
            assertThat(caseId).isNotNull();
        }
    }

    /**
     * §200 — the one rule a create is more permissive about than an edit.
     *
     * <p>Every other test in {@code Validation} above asserts that this endpoint refuses what
     * {@code PATCH} refuses. These assert the single exception and, just as importantly, its two
     * bounds: {@code PATCH} still refuses, so the asymmetry is real rather than a rule that quietly
     * went away everywhere; and the stub is a {@code DRAFT}, which is what stops the deferral from
     * meaning "an approved case need not say how it is run".
     *
     * <p>The reason the rule moved is {@code useCreatePlanCase}: the plan's Test cases tab authors a
     * case from a title, and from §97 to §199 every Save on that tab with a row in it answered 400.
     */
    @Nested
    @DisplayName("The procedure may be deferred on create, and only on create (§200)")
    class TheProcedureMayBeDeferred {

        @Test
        @DisplayName("a STRUCTURED case with no steps is created, and stores none")
        void structuredWithoutStepsIsAStub() throws Exception {
            Map<String, Object> payload = newCase("Cover the refund path");
            payload.put("steps", List.of());

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(stepsOf(idOf(result))).isEmpty();
        }

        @Test
        @DisplayName("a GHERKIN case with no steps is created too — one lens, one rule")
        void gherkinWithoutStepsIsAStubToo() throws Exception {
            // ~~StepFormat makes steps and a scenario ALTERNATIVES~~ → §255: there is one storage,
            // so this is the SAME rule as the arm above rather than its twin. The test stays
            // because the lens is still a field the author picks, and a deferral that held in one
            // lens and not the other would refuse the same act depending on a choice they have
            // not made yet.
            Map<String, Object> payload = newCase("Cover the refund path");
            payload.put("stepFormat", "GHERKIN");
            payload.put("steps", List.of());

            MvcResult result = post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(stepsOf(idOf(result))).isEmpty();
        }

        @Test
        @DisplayName("the stub is a DRAFT — deferring the procedure never approves anything")
        void theStubIsADraft() throws Exception {
            // The whole reason this is cheap. case_health calls a missing procedure a fault only
            // once a case is APPROVED, so a stub is ordinary work in progress until somebody signs
            // it off without finishing it — which is exactly the state INCOMPLETE was written for.
            Map<String, Object> payload = newCase("Cover the refund path");
            payload.put("steps", List.of());

            post(LEAD, ACME, ACME_BANKING, payload, null);

            assertThat(casesIn(ACME_BANKING)).singleElement()
                    .satisfies(row -> assertThat(row.lifecycleStatus()).isEqualTo("DRAFT"));
        }

        @Test
        @DisplayName("PATCHing the stub back unchanged is a no-op — the asymmetry was the defect")
        void theEditPathRoundTripsIt() throws Exception {
            /*
              ~~The funnel. A stub reaches its steps by being opened and saved, and this is the rule
              that makes saving it mean writing it. If this ever answers 200, the deferral has
              stopped being a create-path concession and become the product's position on whether a
              case says how to run it.~~ → REVERSED BY B2, and the reversal is deliberate.

              What the old rule actually charged for was the ROUND TRIP: this exact request — a
              stub read back and sent again unchanged — was a 400 about steps nobody had written,
              for a caller who had changed nothing. §200 made a stepless case a legal stored row,
              and this endpoint then refused to store that row back, so every non-form client had
              to invent a procedure to fix a typo in a stub's title.

              The funnel is not gone; it moved to where the author is. The SPA's editor requires a
              procedure in its own schema, which is the screen the argument was ever about. What
              the server still refuses is the body that is genuinely destructive — `steps: []`
              against a case that HAS steps — and `CaseUpdateEndpointTest.PartialEdits` drives both
              sides of that line.
            */
            Map<String, Object> payload = newCase("Cover the refund path");
            payload.put("steps", List.of());
            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, payload, null));

            MvcResult edited = patchFor(LEAD, ACME, ACME_BANKING, caseId, payload);

            assertThat(edited.getResponse().getStatus())
                    .as("a stub must round-trip: %s", edited.getResponse().getContentAsString())
                    .isEqualTo(200);
        }

        /*
          §144's check, on the operation it was never run against.

          `TestCaseInput` is ONE schema serving two operations, and until now the two enforced the
          same rules, so comparing `required` to the PATCH endpoint's behaviour compared it to
          both. This slice ends that. `required` is unchanged and still correct — neither deferred
          field was ever in it — but "still correct" is a claim about the create path that nothing
          in the suite made, and the shape §144 exists to prevent is exactly a contract that
          describes one verb while a caller uses the other.

          So the same experiment, on POST: drop each editable field in turn from a payload the
          endpoint accepts, and whatever it then refuses is what `required` has to say. It fails in
          both directions, and it is what would notice if a later slice made the create path
          stricter or looser again without saying so on the contract.
        */
        @Test
        @DisplayName("the contract's required set is exactly what CREATE refuses to work without too")
        void contractRequiredMatchesWhatCreateEnforces() throws Exception {
            var contract = com.tcms.contract.OpenApiContract.load();
            Set<String> declared = contract.requiredOf("#/components/schemas/TestCaseInput");

            Set<String> enforced = new LinkedHashSet<>();
            for (String field : newCase("Refund the whole payment").keySet()) {
                // The two automation fields, and ONLY those two: `steps` is the update path's
                // conditional field and this operation defers it outright, so it belongs to
                // neither set here and dropping it is what the tests above assert.
                if (field.equals("automationKey") || field.equals("notAutomatableReason")) {
                    continue;
                }
                Map<String, Object> without = newCase("Refund the whole payment");
                without.remove(field);

                MvcResult result = post(LEAD, ACME, ACME_BANKING, without, null);
                if (result.getResponse().getStatus() == 400
                        && errorsOf(result).containsKey(field)) {
                    enforced.add(field);
                }
            }

            assertThat(enforced).as("the server must refuse SOMETHING, or this test proves nothing")
                    .isNotEmpty();
            assertThat(declared)
                    .as("""
                        `TestCaseInput.required` must name exactly the fields the CREATE path \
                        refuses to work without, not only the fields PATCH does. One schema serves \
                        both verbs, and since §200 they no longer enforce the same rules — so a \
                        rule that moves on one path and not the other is a contract that is true \
                        of whichever operation somebody happened to test.""")
                    .containsExactlyInAnyOrderElementsOf(enforced);
        }

        @Test
        @DisplayName("the stub's v1 is written like any other, so its history is not a gap")
        void theStubGetsItsVersionOne() throws Exception {
            // A stub is a case, not a placeholder for one: it holds a key, an owner and a version.
            // Skipping v1 here would give the first REAL save of every plan-authored case a v2 with
            // nothing before it, which is precisely what §129's create-writes-v1 rule exists to
            // prevent.
            Map<String, Object> payload = newCase("Cover the refund path");
            payload.put("steps", List.of());

            UUID caseId = idOf(post(LEAD, ACME, ACME_BANKING, payload, null));

            assertThat(versionsOf(caseId)).singleElement()
                    .satisfies(version -> assertThat(version.versionNo()).isEqualTo(1));
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private static String path(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/cases";
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private MvcResult post(
            UUID userId, UUID companyId, UUID projectId,
            Map<String, Object> payload, String idempotencyKey) throws Exception {

        var request = MockMvcRequestBuilders.post(path(projectId))
                .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return mvc.perform(request).andReturn();
    }

    private void patch(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            Map<String, Object> payload) throws Exception {

        patchFor(userId, companyId, projectId, caseId, payload);
    }

    /** The same edit, for the tests that assert on how it was refused rather than on its effect. */
    private MvcResult patchFor(UUID userId, UUID companyId, UUID projectId, UUID caseId,
            Map<String, Object> payload) throws Exception {

        return mvc.perform(MockMvcRequestBuilders.patch(path(projectId) + "/" + caseId)
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
    }

    private int listStatus(UUID userId, UUID companyId, UUID projectId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path(projectId))
                        .header("Authorization", "Bearer " + tokenFor(userId, companyId)))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("the endpoint answered with no body").isNotEmpty();
        return json.readTree(content);
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(body(result).get("id").asText());
    }

    private Map<String, List<String>> errorsOf(MvcResult result) throws Exception {
        JsonNode errors = body(result).get("errors");
        assertThat(errors).as("a 400 must carry an errors map the form can land").isNotNull();

        Map<String, List<String>> byField = new LinkedHashMap<>();
        errors.propertyStream().forEach(entry -> {
            List<String> messages = new ArrayList<>();
            entry.getValue().forEach(node -> messages.add(node.asText()));
            byField.put(entry.getKey(), messages);
        });
        return byField;
    }

    /** An automation key the seeded, soft-deleted case still reserves. */
    private static final String TAKEN_AUTOMATION_KEY = "com.example.retired.RefundIT#gone";

    /** A step belonging to the seeded case, which no create may adopt. */
    private static final UUID EXISTING_STEP =
            UUID.nameUUIDFromBytes("case-create-step:existing".getBytes());

    /**
     * The payload the SPA's editor submits on create — every field it renders, and none it does not.
     *
     * <p>A mutable map so each test overrides exactly the field it is about, and a fresh one per call
     * so a mutation in one test cannot reach another.
     */
    private static Map<String, Object> newCase(String title) {
        return newCase(title, REFUNDS);
    }

    private static Map<String, Object> newCase(String title, UUID suiteId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("suiteId", suiteId.toString());
        payload.put("objective", "Prove a merchant can recover funds.");
        payload.put("description", "Refunds the whole captured amount.");
        payload.put("expectedOutcome", "The payment reaches REFUNDED.");
        payload.put("priority", "CRITICAL");
        payload.put("severity", "HIGH");
        payload.put("automationStatus", "MANUAL");
        payload.put("automationKey", null);
        payload.put("notAutomatableReason", null);
        payload.put("estimatedDurationSec", 240);
        payload.put("stepFormat", "STRUCTURED");
        // No ids: the SPA sends null for a step being added, and on a create every id would be
        // somebody else's.
        payload.put("steps", List.of(
                step(null, "Open the refund screen"),
                step(null, "Submit")));
        payload.put("changeComment", null);
        return payload;
    }

    /** The same, in the Gherkin lens — every step carries a keyword (DEC-2 R1). */
    private static Map<String, Object> gherkinStep(String keyword, String action) {
        Map<String, Object> step = step(null, action);
        step.put("keyword", keyword);
        return step;
    }

    private static Map<String, Object> step(UUID id, String action) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id == null ? null : id.toString());
        step.put("keyword", null);
        step.put("action", action);
        step.put("testData", null);
        step.put("expectedResult", null);
        step.put("docString", null);
        step.put("dataTable", null);
        return step;
    }

    // =====================================================================
    // helpers — reading the result back through the app role
    // =====================================================================

    private record CaseRow(
            UUID id, String key, long keySeq, String title, String objective, String lifecycleStatus,
            UUID ownerId, UUID createdBy, Instant createdAt, String idempotencyKey) {
    }

    /** Ordered by {@code key_seq}, which is the only column that may order a key (§3 lesson 6). */
    private List<CaseRow> casesIn(UUID projectId) throws SQLException {
        UUID companyId = projectId.equals(UMBRA_BANKING) ? UMBRA : ACME;
        List<CaseRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement("""
                     SELECT id, key, key_seq, title, objective, lifecycle_status, owner_id,
                            created_by, created_at, created_idempotency_key
                       FROM test_case
                      WHERE project_id = CAST(? AS uuid) AND deleted_at IS NULL
                      ORDER BY key_seq ASC
                     """)) {
            statement.setString(1, projectId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CaseRow(
                            UUID.fromString(rs.getString(1)),
                            rs.getString(2),
                            rs.getLong(3),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getString(6),
                            rs.getString(7) == null ? null : UUID.fromString(rs.getString(7)),
                            rs.getString(8) == null ? null : UUID.fromString(rs.getString(8)),
                            rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
                            rs.getString(10)));
                }
            }
        }
        return rows;
    }

    private record VersionRow(
            int versionNo, String snapshot, String contentHash, String changeComment,
            boolean pinned, UUID createdBy) {
    }

    /** Newest first. */
    private List<VersionRow> versionsOf(UUID caseId) throws SQLException {
        List<VersionRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT version_no, snapshot::text, content_hash, change_comment, pinned,
                            created_by
                       FROM test_case_version
                      WHERE case_id = CAST(? AS uuid)
                      ORDER BY version_no DESC
                     """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String author = rs.getString(6);
                    rows.add(new VersionRow(
                            rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getBoolean(5), author == null ? null : UUID.fromString(author)));
                }
            }
        }
        return rows;
    }

    private record StepRow(UUID id, int position, String action) {
    }

    private List<StepRow> stepsOf(UUID caseId) throws SQLException {
        List<StepRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT id, position, action FROM test_step
                      WHERE case_id = CAST(? AS uuid) ORDER BY position ASC
                     """)) {
            statement.setString(1, caseId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new StepRow(
                            UUID.fromString(rs.getString(1)), rs.getInt(2), rs.getString(3)));
                }
            }
        }
        return rows;
    }

    private record AuditRow(
            UUID companyId, UUID projectId, UUID actorUserId, String action, String targetType,
            List<UUID> targetIds, String changes) {
    }

    private List<AuditRow> auditRows() throws SQLException {
        List<AuditRow> rows = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
             var statement = connection.prepareStatement("""
                     SELECT company_id, project_id, actor_user_id, action, target_type,
                            target_ids, changes::text
                       FROM audit_event
                      ORDER BY occurred_at DESC
                     """);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                List<UUID> targets = new ArrayList<>();
                for (Object id : (Object[]) rs.getArray(6).getArray()) {
                    targets.add((UUID) id);
                }
                rows.add(new AuditRow(
                        UUID.fromString(rs.getString(1)),
                        rs.getString(2) == null ? null : UUID.fromString(rs.getString(2)),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getString(5), targets, rs.getString(7)));
            }
        }
        return rows;
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
        UUID id = UUID.nameUUIDFromBytes(("case-create:" + marker).getBytes());
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forUserOnProject(
                    id, companyId, roleId, userId, projectId, null, now));
        }
    }

    // =====================================================================
    // helpers — fixture, raw SQL through the ORDINARY APP ROLE
    //
    // Same reasoning as every sibling test: the app role is the policed path production writes
    // through, so a fixture that could not be written under RLS is one that lies about what the
    // endpoint will see.
    // =====================================================================

    /**
     * Seeds the company's vocabularies, statuses and — the part this class needs —
     * {@code key_sequence}. Without it the allocator finds no row and every create is a 500, which is
     * migration V5's defect reproduced in a test fixture.
     */
    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);
            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "p");
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2, "Refunds", "p.r");
            suite(connection, MOBILE_SUITE, ACME, ACME_MOBILE, null, "TS-3", 3, "Login", "l");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, null, "TS-4", 4, "Internal", "i");
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "p");
        }
    }

    /**
     * Two rows every test needs and no test may count: a soft-deleted case whose automation key stays
     * reserved, and a live case owning a step no create may adopt.
     *
     * <p>Both are in {@code ACME_MOBILE} rather than {@code ACME_BANKING}, so
     * {@code casesIn(ACME_BANKING)} can assert "nothing was written" without having to subtract them.
     * They are re-seeded after {@link #resetMutableRows()} deleted them, and their keys sit far above
     * the counter so they cannot collide with an allocated one.
     */
    private void seedExistingCases() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            retiredCase(connection, UUID.nameUUIDFromBytes("case-create-case:retired".getBytes()),
                    MOBILE_SUITE, "TC-900", 900, "Retired runner", TAKEN_AUTOMATION_KEY, true);
            retiredCase(connection, UUID.nameUUIDFromBytes("case-create-case:owner".getBytes()),
                    MOBILE_SUITE, "TC-901", 901, "Owns a step", null, false);
            step(connection, EXISTING_STEP,
                    UUID.nameUUIDFromBytes("case-create-case:owner".getBytes()));
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
                    (id, company_id, project_id, key, key_seq, name, position, lifecycle_status, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, 0, 'APPROVED', NULL)
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

    private void retiredCase(
            Connection connection, UUID id, UUID suiteId, String key, long keySeq, String title,
            String automationKey, boolean deleted) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, step_format,
                     priority, lifecycle_status, automation_status, automation_key,
                     current_version_no, lock_version, updated_at, deleted_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        ?, 'STRUCTURED', 'MEDIUM', 'APPROVED', ?, CAST(? AS text), 1, 0,
                        CAST(? AS timestamptz), CAST(? AS timestamptz))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, ACME_MOBILE.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, automationKey == null ? "MANUAL" : "AUTOMATED");
            statement.setString(9, automationKey);
            statement.setString(10, "2026-01-01T00:00:00Z");
            statement.setString(11, deleted ? "2026-01-01T00:00:00Z" : null);
            statement.executeUpdate();
        }
    }

    private void step(Connection connection, UUID id, UUID caseId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO test_step
                    (id, company_id, case_id, position, keyword, action, test_data,
                     expected_result)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), 0, NULL,
                        'Someone else''s step', NULL, NULL)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, ACME.toString());
            statement.setString(3, caseId.toString());
            statement.executeUpdate();
        }
    }
}
