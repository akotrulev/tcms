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
 * {@code POST}, {@code PATCH} and {@code DELETE} on {@code /projects/{projectId}/suites} — the
 * writes that make a provisioned company usable.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>Delete gated on {@code suite:manage}.</strong> The permissions are two, and the
 *       roles spec lists their separation as a boundary that was <em>previously wrong and got
 *       fixed</em>. Collapsed, a Test Engineer can delete the tree. Nothing else in the suite would
 *       notice, because every other assertion about the delete would still pass — a lead can still
 *       do it.</li>
 *   <li><strong>A path derived from the name.</strong> It works until the first rename, at which
 *       point every descendant's path names a folder that no longer exists — and paths are what
 *       subtree matching reads, so the tree keeps rendering while "run this folder" quietly stops
 *       finding cases. Asserted by renaming and re-reading the descendants.</li>
 *   <li><strong>A silently ignored move.</strong> {@code SuiteInput} carries {@code parentId} on
 *       both verbs, so a {@code PATCH} that drops it answers 200 and the client shows the folder in
 *       its new place until the next reload.</li>
 *   <li><strong>A delete that cascades.</strong> Removing a folder holding cases soft-deletes other
 *       people's work through a permission whose sibling is {@code OWN}-qualified precisely to stop
 *       that, with no preview. Asserted as a 409 <em>and</em> as the cases still being there.</li>
 *   <li><strong>A key burned by a rejected payload.</strong> The counter only moves forward, so a
 *       blank name that consumed a number leaves a permanent hole per failed attempt.</li>
 *   <li><strong>A created suite with no v1.</strong> §29's defect on cases, one entity over.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code f1…}, checked against every prefix
 * already in {@code src/test/java} before writing. Sharing them is how handoff §19's first defect
 * happened, and §29 hit it again: green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Suite write endpoints — create, rename, and the delete that refuses")
class SuiteWriteEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("f1000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("f1000000-0000-4000-8000-00000000000b");

    /** Granted to LEAD, ENGINEER and VIEWER. Carries the seeded tree. */
    private static final UUID ACME_BANKING = UUID.fromString("f1001000-0000-4000-8000-000000000001");
    /** Same company, granted to LEAD too — so key numbering can be watched spanning projects. */
    private static final UUID ACME_MOBILE = UUID.fromString("f1001000-0000-4000-8000-000000000002");
    /** Same company, granted to nobody here. */
    private static final UUID ACME_LOCKED = UUID.fromString("f1001000-0000-4000-8000-000000000003");
    private static final UUID UMBRA_BANKING = UUID.fromString("f1002000-0000-4000-8000-000000000001");

    /** Test Lead. Holds {@code suite:manage} AND {@code suite:delete}. */
    private static final UUID LEAD = UUID.fromString("f1003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer. Holds {@code suite:manage} and <strong>not</strong> {@code suite:delete} — the
     * one persona that separates the two permissions, and the reason this class has two writers.
     */
    private static final UUID ENGINEER = UUID.fromString("f1003000-0000-4000-8000-000000000002");
    /** Viewer. Holds {@code project:read} and neither write permission. */
    private static final UUID VIEWER = UUID.fromString("f1003000-0000-4000-8000-000000000003");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("f1003000-0000-4000-8000-000000000004");
    /** Test Engineer in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("f1003000-0000-4000-8000-000000000005");

    /** Root, holds no cases of its own, parent of REFUNDS. */
    private static final UUID PAYMENTS = UUID.fromString("f1004000-0000-4000-8000-000000000001");
    /** Child of PAYMENTS, holds two live cases. The folder a delete must refuse. */
    private static final UUID REFUNDS = UUID.fromString("f1004000-0000-4000-8000-000000000002");
    /** Root, empty of both cases and children. The only folder a delete may take. */
    private static final UUID SPARE = UUID.fromString("f1004000-0000-4000-8000-000000000003");
    /**
     * Root whose only occupant is an <strong>empty</strong> child suite.
     *
     * <p>The one fixture that isolates the child check: every other non-empty folder here also has
     * cases in its subtree, so the case count refuses it too and a missing child check survives.
     */
    private static final UUID NEST_PARENT = UUID.fromString("f1004000-0000-4000-8000-000000000007");
    /** Empty child of {@link #NEST_PARENT} — no cases anywhere beneath it. */
    private static final UUID NEST_CHILD = UUID.fromString("f1004000-0000-4000-8000-000000000008");
    /** Root in the sibling project, granted to nobody. */
    private static final UUID LOCKED_SUITE = UUID.fromString("f1004000-0000-4000-8000-000000000004");
    /** Root in the other client, deliberately keyed TS-1 like Acme's. */
    private static final UUID UMBRA_SUITE = UUID.fromString("f1004000-0000-4000-8000-000000000005");

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
            company(ACME, "sw-acme", "Acme", now);
            company(UMBRA, "sw-umbra", "Umbra", now);

            activeUser(LEAD, "lead@sw-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@sw-acme.test", "E. Engineer", now);
            activeUser(VIEWER, "viewer@sw-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@sw-acme.test", "N. Grant", now);
            activeUser(OUTSIDER, "out@sw-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "WAOLB", "Online Banking", now);
            seedProject(ACME_MOBILE, ACME, "WAMOB", "Mobile App", now);
            seedProject(ACME_LOCKED, ACME, "WALCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead of Acme", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);

            grantOnProject("sw-lead-banking", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("sw-lead-mobile", ACME, Role.TEST_LEAD_ID, LEAD, ACME_MOBILE, now);
            grantOnProject("sw-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("sw-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "WUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("sw-out", UMBRA, Role.TEST_ENGINEER_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedTrees();
    }

    /**
     * Puts back everything a test can write, counters included.
     *
     * <p>The counters are the part that surprises: {@code tcms_seed_company_defaults} is
     * {@code ON CONFLICT DO NOTHING}, correct for provisioning and therefore no reset. Without this,
     * every {@code TS-n} assertion would depend on the order the class ran in — green alone and red
     * in the suite, which is handoff §19's defect again.
     *
     * <p>Suites are deleted before the counter is reset and re-seeded afterwards, because the seeded
     * tree occupies TS-1..TS-3 and a create must be able to predict it gets TS-4.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                // Through each company's OWN context: RLS means the other's connection could not see
                // these rows to delete them, which is isolation working rather than a leftover row.
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite_version");
                execute(connection, "DELETE FROM suite");
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
    // The two gates, and the fact that they are two
    // =====================================================================

    @Nested
    @DisplayName("The gates — suite:manage to write, suite:delete to remove")
    class Gates {

        @Test
        @DisplayName("a Test Lead creates a suite and gets 201 with the whole suite")
        void leadCanCreate() throws Exception {
            MvcResult result = post(LEAD, ACME, ACME_BANKING, suiteInput("Onboarding", null));

            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(result).get("name").asText()).isEqualTo("Onboarding");
        }

        @Test
        @DisplayName("a Test Engineer may create and rename — organising the tree is not restricted")
        void engineerCanManage() throws Exception {
            MvcResult created = post(ENGINEER, ACME, ACME_BANKING, suiteInput("Engineer's", null));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);

            UUID id = UUID.fromString(body(created).get("id").asText());
            assertThat(patch(ENGINEER, ACME, ACME_BANKING, id, suiteInput("Renamed", null), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Engineer may NOT delete — the two permissions are genuinely two")
        void engineerCannotDelete() throws Exception {
            // THE assertion of this class. suite:delete is leads-only because deleting a subtree is
            // the most destructive repository action; suite:manage is everyone above Viewer because
            // a contributor must be able to organise freely. Gate both on suite:manage and this is
            // the only test in the suite that goes red.
            assertThat(delete(ENGINEER, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .isEqualTo(403);

            // And the folder is still there — a 403 that deleted anyway is worse than either.
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).contains(SPARE);
        }

        @Test
        @DisplayName("the same Engineer, refused the delete, DOES reach the rename")
        void theRefusalIsThePermissionAndNotABrokenRoute() throws Exception {
            // Without this the test above passes just as well against a delete route that does not
            // exist, or a caller whose token is wrong.
            assertThat(patch(ENGINEER, ACME, ACME_BANKING, SPARE,
                    suiteInput("Still writable", null), null).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a Test Lead may delete")
        void leadCanDelete() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a Viewer may read the tree and write nothing")
        void viewerIsRefusedEveryWrite() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .get("/api/v1/projects/" + ACME_BANKING + "/suites")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(VIEWER, ACME)))
                    .andReturn().getResponse().getStatus())
                    .as("the read gate every role passes — otherwise the 403s below prove nothing")
                    .isEqualTo(200);

            assertThat(post(VIEWER, ACME, ACME_BANKING, suiteInput("Nope", null))
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(patch(VIEWER, ACME, ACME_BANKING, SPARE, suiteInput("Nope", null), null)
                    .getResponse().getStatus()).isEqualTo(403);
            assertThat(delete(VIEWER, ACME, ACME_BANKING, SPARE)
                    .getResponse().getStatus()).isEqualTo(403);
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = post(VIEWER, ACME, ACME_BANKING, suiteInput("Nope", null))
                    .getResponse().getContentAsString();

            assertThat(refused)
                    .as("a key is not actionable by someone who cannot grant it to themselves, "
                            + "and naming it publishes the catalogue to exactly that caller")
                    .doesNotContain("suite:manage")
                    .doesNotContain("suite:delete");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a member with no grant gets 404 on every verb, never 403")
        void membershipConveysNoWrite() throws Exception {
            // The rows are in this caller's own company, so no cross-company assertion reaches
            // them. A 403 here would confirm the project id names something real.
            assertThat(post(NO_GRANT, ACME, ACME_BANKING, suiteInput("Nope", null))
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(patch(NO_GRANT, ACME, ACME_BANKING, SPARE, suiteInput("Nope", null), null)
                    .getResponse().getStatus()).isEqualTo(404);
            assertThat(delete(NO_GRANT, ACME, ACME_BANKING, SPARE)
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent projects answer 404 with no body")
        void theThreeUnreachableCasesAnswerIdentically() throws Exception {
            UUID absent = UUID.randomUUID();

            for (UUID projectId : List.of(ACME_LOCKED, UMBRA_BANKING, absent)) {
                MvcResult result = post(LEAD, ACME, projectId, suiteInput("Nope", null));
                assertThat(result.getResponse().getStatus())
                        .as("project %s", projectId)
                        .isEqualTo(404);
                assertThat(result.getResponse().getContentAsString())
                        .as("a 404 that explains itself is still an oracle (§6t)")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a suite in another project of the same company is 404, not renamed")
        void aSuiteIsScopedToItsProject() throws Exception {
            // LOCKED_SUITE is in ACME_LOCKED. The caller passes the gate on ACME_BANKING, so only
            // the suite query's own project predicate separates them — drop it and a lead granted
            // one project renames folders in another.
            assertThat(patch(LEAD, ACME, ACME_BANKING, LOCKED_SUITE, suiteInput("Stolen", null),
                    null).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(nameOf(ACME, LOCKED_SUITE)).isEqualTo("Internal");
        }

        @Test
        @DisplayName("the other client's suite is 404 even with an identical display key")
        void writesDoNotCrossCompanies() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, UMBRA_SUITE, suiteInput("Stolen", null),
                    null).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(delete(LEAD, ACME, ACME_BANKING, UMBRA_SUITE)
                    .getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(nameOf(UMBRA, UMBRA_SUITE)).isEqualTo("Payments");
        }

        @Test
        @DisplayName("an anonymous caller is refused on every verb")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .post("/api/v1/projects/" + ACME_BANKING + "/suites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(suiteInput("Nope", null)))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .delete("/api/v1/projects/" + ACME_BANKING + "/suites/" + SPARE))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
        }
    }

    // =====================================================================
    // Create — the key, the path, and v1
    // =====================================================================

    @Nested
    @DisplayName("Create — a server-allocated key, a derived path, and version 1")
    class Create {

        @Test
        @DisplayName("the key comes from the company's counter and continues past the seeded tree")
        void keysComeFromTheCounter() throws Exception {
            // The seeded tree took TS-1..TS-3, so the next is TS-4. A key derived from
            // MAX(key_seq)+1 would also answer TS-4 here — that is exactly why this is not the
            // assertion that catches the racing implementation, and DisplayKeyAllocationTest owns
            // that. What this pins is that the key is allocated at all and is not, say, the name.
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Fourth", null))))
                    .isEqualTo("TS-4");
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Fifth", null))))
                    .isEqualTo("TS-5");
        }

        @Test
        @DisplayName("keys advance across projects — the counter is per company, not per project")
        void keysAreCompanyScoped() throws Exception {
            // Plan §6t. Per-project numbering was the superseded decision, and it fails here: both
            // creates would answer TS-4.
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("In banking", null))))
                    .isEqualTo("TS-4");
            assertThat(keyOf(post(LEAD, ACME, ACME_MOBILE, suiteInput("In mobile", null))))
                    .isEqualTo("TS-5");
        }

        @Test
        @DisplayName("each company numbers from its own counter, so TS-1 exists twice")
        void countersDoNotCrossCompanies() throws Exception {
            // Umbra's tree is one suite, seeded at TS-1, so its next is TS-2 while Acme is at TS-4.
            assertThat(keyOf(post(OUTSIDER, UMBRA, UMBRA_BANKING, suiteInput("Theirs", null))))
                    .isEqualTo("TS-2");
        }

        @Test
        @DisplayName("a rejected payload burns no key")
        void aRefusedCreateDoesNotConsumeANumber() throws Exception {
            assertThat(post(LEAD, ACME, ACME_BANKING, suiteInput("   ", null))
                    .getResponse().getStatus()).isEqualTo(400);

            // The counter only moves forward, so a burned number is a permanent hole in the
            // company's key sequence for every failed attempt.
            assertThat(keyOf(post(LEAD, ACME, ACME_BANKING, suiteInput("Now valid", null))))
                    .isEqualTo("TS-4");
        }

        @Test
        @DisplayName("the path is derived from the key sequence, not from the name")
        void thePathIsNotASlugOfTheName() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, suiteInput("UI / E2E", null));

            assertThat(body(created).get("path").asText())
                    .as("a name-derived label is not unique, can be empty, and makes every "
                            + "rename a subtree rewrite")
                    .isEqualTo("s4")
                    .doesNotContain("ui")
                    .doesNotContain("e2e");
        }

        @Test
        @DisplayName("a child's path is the parent's path plus its own label")
        void aChildHangsOffItsParentsPath() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, suiteInput("Under refunds", REFUNDS));

            assertThat(body(created).get("path").asText())
                    .isEqualTo(pathOf(ACME, REFUNDS) + ".s4");
            assertThat(body(created).get("parentId").asText()).isEqualTo(REFUNDS.toString());
        }

        @Test
        @DisplayName("a root suite says parentId is null, explicitly rather than by omission")
        void aRootHasAnExplicitNullParent() throws Exception {
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, suiteInput("A root", null)));

            assertThat(created.has("parentId")).isTrue();
            assertThat(created.get("parentId").isNull())
                    .as("the SPA buckets on `parentId ?? null` — an absent key would work, and "
                            + "the contract marks the field present-and-nullable")
                    .isTrue();
        }

        @Test
        @DisplayName("a new suite is DRAFT — a create cannot grant its own approval")
        void aCreatedSuiteIsDraft() throws Exception {
            assertThat(body(post(LEAD, ACME, ACME_BANKING, suiteInput("Fresh", null)))
                    .get("lifecycleStatus").asText())
                    .isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("version 1 is written with the suite, so a created folder has no history gap")
        void versionOneIsWrittenWithTheRow() throws Exception {
            // §29's precedent, one entity over: SuiteVersions.writeInitial numbers this 1
            // explicitly. A decide()-style currentVersionNo + 1 would answer 2 on a fresh row,
            // whose column defaults to 1, and every suite in the product would be missing its v1.
            UUID id = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING, suiteInput("V1", null)))
                    .get("id").asText());

            assertThat(versionNumbersOf(ACME, id)).containsExactly(1);
        }

        @Test
        @DisplayName("a rename writes no version — suite_version holds composition, not content")
        void renamingDoesNotBumpTheCompositionVersion() throws Exception {
            // V1's own comment on suite_version.snapshot: a suite version captures composition and
            // explicitly not content, "otherwise every case edit bumps every ancestor suite". A
            // name is content. Bumping here is the start of that spiral, and it looks like
            // diligence.
            UUID id = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING, suiteInput("Before", null)))
                    .get("id").asText());

            patch(LEAD, ACME, ACME_BANKING, id, suiteInput("After", null), null);

            assertThat(versionNumbersOf(ACME, id)).containsExactly(1);
        }

        @Test
        @DisplayName("a create writes an audit row naming the key and the name")
        void theCreateIsAudited() throws Exception {
            post(LEAD, ACME, ACME_BANKING, suiteInput("Audited", null));

            Map<String, String> events = auditEvents(ACME);
            assertThat(events).containsKey("suite.created");
            assertThat(events.get("suite.created")).contains("TS-4").contains("Audited");
        }

        @Test
        @DisplayName("a name with a quote in it is created, and its audit row is valid JSON")
        void aQuotedNameDoesNotCorruptTheAuditRow() throws Exception {
            // audit_event.detail is JSONB and the detail string is assembled by concatenation, so
            // an unescaped name makes the document malformed, the insert fail, and the whole write
            // roll back — a folder with a quote in its name would simply be uncreatable, and the
            // error would name the audit table rather than the name.
            String awkward = "\"Payments\" \\ v2";

            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":" + json.writeValueAsString(awkward) + "}");

            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(created).get("name").asText()).isEqualTo(awkward);
            assertThat(auditEvents(ACME).get("suite.created")).contains("TS-4");
        }

        @Test
        @DisplayName("Location names the created suite")
        void locationIsSet() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING, suiteInput("Located", null));
            String id = body(created).get("id").asText();

            assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION))
                    .isEqualTo("/api/v1/projects/" + ACME_BANKING + "/suites/" + id);
        }

        @Test
        @DisplayName("the created suite appears in the tree the next read serves")
        void theCreateIsVisibleToTheRead() throws Exception {
            UUID id = UUID.fromString(body(post(LEAD, ACME, ACME_BANKING, suiteInput("Listed", null)))
                    .get("id").asText());

            assertThat(liveSuiteIds(ACME, ACME_BANKING)).contains(id);
        }

        @Test
        @DisplayName("a new suite reports caseCount 0 and conditions [], never null")
        void theCreateResponseIsTheReadShape() throws Exception {
            // Served through SuiteService's own renderer rather than assembled by the write path,
            // so a field cannot show one value on create and another on the reload after it.
            JsonNode created = body(post(LEAD, ACME, ACME_BANKING, suiteInput("Shaped", null)));

            assertThat(List.of("id", "key", "name", "path", "caseCount", "lifecycleStatus",
                    "conditions", "lockVersion"))
                    .allSatisfy(field -> assertThat(created.has(field))
                            .as("Suite.%s is required by openapi.yaml", field)
                            .isTrue());
            assertThat(created.get("caseCount").asLong()).isZero();
            assertThat(created.get("conditions").isArray()).isTrue();
            assertThat(created.get("conditions")).isEmpty();
            assertThat(created.has("companyId")).isFalse();
            assertThat(created.has("projectId")).isFalse();
        }
    }

    // =====================================================================
    // Validation
    // =====================================================================

    @Nested
    @DisplayName("Validation — keyed on the field, and the parent is checked")
    class Validation {

        @Test
        @DisplayName("a blank name is a 400 keyed on `name`")
        void aBlankNameIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING, suiteInput("   ", null));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("name"))
                    .as("the key must match the input schema's field name or the SPA lands the "
                            + "message nowhere")
                    .isTrue();
        }

        @Test
        @DisplayName("a missing name is refused the same way as a blank one")
        void aMissingNameIsRefused() throws Exception {
            MvcResult refused = post(LEAD, ACME, ACME_BANKING, "{}");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("name")).isTrue();
        }

        @Test
        @DisplayName("an over-long name is refused")
        void anOverLongNameIsRefused() throws Exception {
            String tooLong = "x".repeat(SuiteEdit.MAX_NAME + 1);

            assertThat(post(LEAD, ACME, ACME_BANKING, suiteInput(tooLong, null))
                    .getResponse().getStatus()).isEqualTo(400);
            assertThat(post(LEAD, ACME, ACME_BANKING, suiteInput("x".repeat(SuiteEdit.MAX_NAME),
                    null)).getResponse().getStatus())
                    .as("the boundary itself is accepted — otherwise the cap is off by one and "
                            + "nothing says so")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("a parent from another project is refused as a field error, not a 404")
        void aForeignParentIsRefused() throws Exception {
            // Unchecked, the folder is created under another project's suite and, since every read
            // is scoped by project_id, appears in no tree at all — created successfully and
            // immediately invisible.
            MvcResult refused =
                    post(LEAD, ACME, ACME_BANKING, suiteInput("Orphan", LOCKED_SUITE));

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("parentId")).isTrue();
        }

        @Test
        @DisplayName("a parent that does not exist at all is refused the same way")
        void anUnknownParentIsRefused() throws Exception {
            assertThat(post(LEAD, ACME, ACME_BANKING, suiteInput("Orphan", UUID.randomUUID()))
                    .getResponse().getStatus())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a blank description is stored as null, not as whitespace")
        void aBlankDescriptionIsNulled() throws Exception {
            MvcResult created = post(LEAD, ACME, ACME_BANKING,
                    "{\"name\":\"Described\",\"description\":\"   \"}");

            assertThat(body(created).get("description").isNull())
                    .as("stored as-is it renders as an empty paragraph, which reads as data loss")
                    .isTrue();
        }
    }

    // =====================================================================
    // Rename — and the move it refuses
    // =====================================================================

    @Nested
    @DisplayName("Rename — content only, and a move is refused rather than dropped")
    class Rename {

        @Test
        @DisplayName("the name and description change and the response carries them")
        void renamingWorks() throws Exception {
            MvcResult renamed = patch(LEAD, ACME, ACME_BANKING, SPARE,
                    "{\"name\":\"Renamed\",\"description\":\"Now described\"}", null);

            assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
            assertThat(body(renamed).get("name").asText()).isEqualTo("Renamed");
            assertThat(body(renamed).get("description").asText()).isEqualTo("Now described");
            assertThat(nameOf(ACME, SPARE)).isEqualTo("Renamed");
        }

        @Test
        @DisplayName("a rename does NOT change the path, of the suite or of its descendants")
        void renamingLeavesEveryPathAlone() throws Exception {
            // The whole argument for a key-derived label. With a name-derived one this rename
            // rewrites PAYMENTS' path and orphans REFUNDS' — and the tree keeps rendering, because
            // it is built from parentId, while subtree matching silently stops finding cases.
            String parentPathBefore = pathOf(ACME, PAYMENTS);
            String childPathBefore = pathOf(ACME, REFUNDS);

            assertThat(patch(LEAD, ACME, ACME_BANKING, PAYMENTS,
                    suiteInput("Completely different", null), null)
                    .getResponse().getStatus())
                    .isEqualTo(200);

            assertThat(pathOf(ACME, PAYMENTS)).isEqualTo(parentPathBefore);
            assertThat(pathOf(ACME, REFUNDS)).isEqualTo(childPathBefore);
        }

        @Test
        @DisplayName("a payload asking to move the suite is a 400, not a silently ignored field")
        void aMoveIsRefusedAndSaidSo() throws Exception {
            // SuiteInput carries parentId on both verbs, so the tempting implementation drops it on
            // PATCH and answers 200 — after which the client shows the folder in its new place
            // until the next reload, with the server having reported success.
            MvcResult refused =
                    patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Moved", PAYMENTS), null);

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("parentId")).isTrue();
            assertThat(parentOf(ACME, SPARE)).isNull();
            assertThat(nameOf(ACME, SPARE))
                    .as("the refused request changed nothing at all, name included")
                    .isEqualTo("Spare");
        }

        @Test
        @DisplayName("repeating the current parent is accepted — it is not a move")
        void resendingTheSameParentIsFine() throws Exception {
            // The SPA's dialog may round-trip the whole suite. Refusing an unchanged parent would
            // make an ordinary rename fail for a client that sent one more field than it had to.
            assertThat(patch(LEAD, ACME, ACME_BANKING, REFUNDS, suiteInput("Still a child", PAYMENTS),
                    null).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("omitting parentId on a root is accepted")
        void omittingTheParentIsUnchanged() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, PAYMENTS, suiteInput("Root renamed", null),
                    null).getResponse().getStatus())
                    .isEqualTo(200);
            assertThat(parentOf(ACME, PAYMENTS)).isNull();
        }

        @Test
        @DisplayName("a stale If-Match is a 409 and changes nothing")
        void aStalePreconditionIsRefused() throws Exception {
            long current = lockVersionOf(ACME, SPARE);

            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, SPARE,
                    suiteInput("Overwritten", null), String.valueOf(current + 7));

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(nameOf(ACME, SPARE)).isEqualTo("Spare");
        }

        @Test
        @DisplayName("the matching If-Match is accepted, quoted or bare")
        void aCurrentPreconditionIsAccepted() throws Exception {
            long current = lockVersionOf(ACME, SPARE);

            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Bare", null),
                    String.valueOf(current)).getResponse().getStatus())
                    .isEqualTo(200);

            long next = lockVersionOf(ACME, SPARE);
            assertThat(next).as("the version moved, so the second call is a real second read")
                    .isNotEqualTo(current);
            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Quoted", null),
                    "\"" + next + "\"").getResponse().getStatus())
                    .as("HTTP defines If-Match as carrying entity tags, which are quoted — a "
                            + "client library that formats it correctly is not wrong")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("an unparseable If-Match is a 400, not a 409")
        void aMalformedPreconditionIsNotAConflict() throws Exception {
            // 409 would send a client with a malformed header into a reload-and-retry loop that
            // cannot succeed.
            MvcResult refused = patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Nope", null),
                    "not-a-version");

            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
            assertThat(body(refused).get("errors").has("If-Match")).isTrue();
        }

        @Test
        @DisplayName("no If-Match at all is last-write-wins")
        void anAbsentPreconditionIsAllowed() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Importer", null), null)
                    .getResponse().getStatus())
                    .as("right for an importer with no prior read; the SPA always sends one")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a rename writes an audit row")
        void theRenameIsAudited() throws Exception {
            patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Audited rename", null), null);

            assertThat(auditEvents(ACME).get("suite.renamed")).contains("Audited rename");
        }

        @Test
        @DisplayName("a suite that does not exist is a 404")
        void anUnknownSuiteIsNotFound() throws Exception {
            assertThat(patch(LEAD, ACME, ACME_BANKING, UUID.randomUUID(),
                    suiteInput("Ghost", null), null).getResponse().getStatus())
                    .isEqualTo(404);
        }
    }

    // =====================================================================
    // Delete — and the refusal that is the feature
    // =====================================================================

    @Nested
    @DisplayName("Delete — soft, and only when the folder is genuinely empty")
    class Delete {

        @Test
        @DisplayName("an empty suite is deleted, answers 204 with no body, and leaves the tree")
        void anEmptySuiteIsDeleted() throws Exception {
            MvcResult deleted = delete(LEAD, ACME, ACME_BANKING, SPARE);

            assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
            assertThat(deleted.getResponse().getContentAsString()).isEmpty();
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).doesNotContain(SPARE);
        }

        @Test
        @DisplayName("the delete is soft — the row survives with deleted_at set")
        void theRowIsKept() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, SPARE);

            // Hard-deleting would rewrite what past runs covered: executions reference the cases
            // beneath a suite and suite_run_summary keys on the id.
            assertThat(deletedAtOf(ACME, SPARE)).isNotNull();
        }

        @Test
        @DisplayName("a suite holding live cases is refused with the count, and keeps them")
        void aSuiteWithCasesIsRefused() throws Exception {
            MvcResult refused = delete(LEAD, ACME, ACME_BANKING, REFUNDS);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText())
                    .as("a user looking at a collapsed folder cannot see what is in it, so the "
                            + "count is the actionable part")
                    .contains("2 cases");
            assertThat(liveSuiteIds(ACME, ACME_BANKING))
                    .as("a cascade would soft-delete somebody else's cases through a folder "
                            + "action, with no preview")
                    .contains(REFUNDS);
            assertThat(liveCaseCount(ACME, ACME_BANKING, REFUNDS)).isEqualTo(2);
        }

        @Test
        @DisplayName("a suite holding child suites is refused with the count")
        void aSuiteWithChildrenIsRefused() throws Exception {
            MvcResult refused = delete(LEAD, ACME, ACME_BANKING, PAYMENTS);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText()).contains("1 suite");
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).contains(PAYMENTS);
        }

        @Test
        @DisplayName("a suite whose only occupant is an EMPTY child is still refused")
        void anEmptyChildStillBlocksTheDelete() throws Exception {
            // The child check's own test, and the reason it has one: every other non-empty folder
            // in this fixture also holds cases somewhere beneath, so the subtree case count refuses
            // those too and a deleted child check SURVIVES the mutation. Here the case count is
            // zero and only the child count stands in the way — remove it and this folder is
            // deleted while the tree still renders a child hanging off it.
            MvcResult refused = delete(LEAD, ACME, ACME_BANKING, NEST_PARENT);

            assertThat(refused.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(refused).get("detail").asText())
                    .contains("1 suite")
                    .doesNotContain("case");
            assertThat(liveSuiteIds(ACME, ACME_BANKING)).contains(NEST_PARENT, NEST_CHILD);
        }

        @Test
        @DisplayName("the empty child itself deletes, and then its parent does too")
        void clearingTheChildUnblocksTheParent() throws Exception {
            // Without this the assertion above passes against a delete that refuses every folder
            // with a parent — a working endpoint and a useless one.
            assertThat(delete(LEAD, ACME, ACME_BANKING, NEST_CHILD).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(delete(LEAD, ACME, ACME_BANKING, NEST_PARENT).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("emptying a suite makes it deletable — the refusal is about state, not identity")
        void clearingTheFolderUnblocksTheDelete() throws Exception {
            // Without this the 409s above pass equally well against a delete that refuses
            // everything, which is a working endpoint and a useless one.
            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUNDS).getResponse().getStatus())
                    .isEqualTo(409);

            softDeleteCasesIn(ACME, REFUNDS);

            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUNDS).getResponse().getStatus())
                    .as("the cases are gone, so the folder is empty and the same call succeeds")
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a soft-deleted case does not keep its folder alive")
        void deletedCasesDoNotCount() throws Exception {
            // Counting them would make a folder whose cases were all deleted permanently
            // undeletable, with the UI showing it empty and the server disagreeing.
            softDeleteCasesIn(ACME, REFUNDS);

            assertThat(delete(LEAD, ACME, ACME_BANKING, REFUNDS).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a deleted suite cannot be deleted twice — the second call is 404")
        void deletingTwiceIsNotFound() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(delete(LEAD, ACME, ACME_BANKING, SPARE).getResponse().getStatus())
                    .as("204 twice would report success for a delete this request did not do")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a deleted suite cannot be renamed either")
        void aDeletedSuiteIsGoneFromEveryWrite() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, SPARE);

            assertThat(patch(LEAD, ACME, ACME_BANKING, SPARE, suiteInput("Zombie", null), null)
                    .getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("a delete writes an audit row naming the suite")
        void theDeleteIsAudited() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, SPARE);

            // The name is in the row because a deleted suite's name is otherwise only recoverable
            // by reading a soft-deleted row, which is exactly what an audit trail should not need.
            assertThat(auditEvents(ACME).get("suite.deleted")).contains("Spare").contains("TS-3");
        }

        @Test
        @DisplayName("cases in ANOTHER project's suite do not block this delete")
        void theEmptinessCheckIsScopedToTheProject() throws Exception {
            // The subtree count is a path match, and paths are not unique across projects — two
            // projects can both have a suite at path `s1`. Without the project predicate, a folder
            // is undeletable because a different project's folder has cases in it, which is
            // unexplainable from the screen the user is on.
            assertThat(delete(LEAD, ACME, ACME_MOBILE, mobileSpare()).getResponse().getStatus())
                    .isEqualTo(204);
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    private MvcResult post(UUID userId, UUID companyId, UUID projectId, String payload)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/suites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private MvcResult patch(
            UUID userId, UUID companyId, UUID projectId, UUID suiteId, String payload,
            String ifMatch) throws Exception {

        var request = MockMvcRequestBuilders
                .patch("/api/v1/projects/" + projectId + "/suites/" + suiteId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(payload);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult delete(UUID userId, UUID companyId, UUID projectId, UUID suiteId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/suites/" + suiteId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(content).as("expected a body, got none (status %d)",
                result.getResponse().getStatus()).isNotEmpty();
        return json.readTree(content);
    }

    private String keyOf(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("a key is only allocated on a successful create; body was %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(201);
        return body(result).get("key").asText();
    }

    private static String suiteInput(String name, UUID parentId) {
        String parent = parentId == null ? "" : ",\"parentId\":\"" + parentId + "\"";
        return "{\"name\":\"" + name + "\"" + parent + "}";
    }

    // =====================================================================
    // helpers — reading the database back
    //
    // Through the APP role and the company's own context, not the container superuser: it is the
    // policed path production reads through, so an assertion that could not be made under RLS is an
    // assertion about a database the product never runs on.
    // =====================================================================

    private List<UUID> liveSuiteIds(UUID companyId, UUID projectId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT id FROM suite WHERE project_id = CAST(? AS uuid) "
                             + "AND deleted_at IS NULL")) {
            statement.setString(1, projectId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(UUID.fromString(rows.getString(1)));
                }
            }
        }
        return ids;
    }

    private String nameOf(UUID companyId, UUID suiteId) throws SQLException {
        return suiteColumn(companyId, suiteId, "name");
    }

    private String pathOf(UUID companyId, UUID suiteId) throws SQLException {
        return suiteColumn(companyId, suiteId, "CAST(path AS text)");
    }

    private String parentOf(UUID companyId, UUID suiteId) throws SQLException {
        return suiteColumn(companyId, suiteId, "CAST(parent_id AS text)");
    }

    private String deletedAtOf(UUID companyId, UUID suiteId) throws SQLException {
        return suiteColumn(companyId, suiteId, "CAST(deleted_at AS text)");
    }

    private long lockVersionOf(UUID companyId, UUID suiteId) throws SQLException {
        return Long.parseLong(suiteColumn(companyId, suiteId, "CAST(lock_version AS text)"));
    }

    /** {@code column} is a literal from this class only — never request input. */
    private String suiteColumn(UUID companyId, UUID suiteId, String column) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM suite WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("suite %s is not visible to company %s", suiteId,
                        companyId).isTrue();
                return rows.getString(1);
            }
        }
    }

    private List<Integer> versionNumbersOf(UUID companyId, UUID suiteId) throws SQLException {
        List<Integer> numbers = new ArrayList<>();
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT version_no FROM suite_version WHERE suite_id = CAST(? AS uuid) "
                             + "ORDER BY version_no")) {
            statement.setString(1, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    numbers.add(rows.getInt(1));
                }
            }
        }
        return numbers;
    }

    private long liveCaseCount(UUID companyId, UUID projectId, UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM test_case WHERE project_id = CAST(? AS uuid) "
                             + "AND suite_id = CAST(? AS uuid) AND deleted_at IS NULL")) {
            statement.setString(1, projectId.toString());
            statement.setString(2, suiteId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private Map<String, String> auditEvents(UUID companyId) throws SQLException {
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

    private void softDeleteCasesIn(UUID companyId, UUID suiteId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "UPDATE test_case SET deleted_at = now() WHERE suite_id = CAST(? AS uuid)")) {
            statement.setString(1, suiteId.toString());
            statement.executeUpdate();
        }
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

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("suite-write-endpoint:" + marker).getBytes());
    }

    /**
     * Seeds the vocabularies, statuses and — the part this class needs — {@code key_sequence}.
     * Without it the allocator finds no row and every create is a 500, which is migration V5's
     * defect reproduced in a fixture.
     */
    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    /** The one spare folder in the sibling project, for the project-scoped emptiness assertion. */
    private static UUID mobileSpare() {
        return UUID.fromString("f1004000-0000-4000-8000-000000000006");
    }

    /**
     * The trees, written as raw SQL through the app role.
     *
     * <p><strong>The sibling project's suite is deliberately given the same {@code path} as this
     * project's</strong> — both {@code s1} — because paths are unique per project and nothing stops
     * two projects sharing one. That collision is what makes the subtree emptiness check's
     * {@code project_id} predicate observable; without it the assertion would pass either way.
     */
    private void seedTrees() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_MOBILE);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, null, "TS-1", 1, "Payments", "s1");
            suite(connection, REFUNDS, ACME, ACME_BANKING, PAYMENTS, "TS-2", 2, "Refunds", "s1.s2");
            suite(connection, SPARE, ACME, ACME_BANKING, null, "TS-3", 3, "Spare", "s3");
            suite(connection, NEST_PARENT, ACME, ACME_BANKING, null, "TS-92", 92, "Nest", "s92");
            suite(connection, NEST_CHILD, ACME, ACME_BANKING, NEST_PARENT, "TS-93", 93,
                    "Nest child", "s92.s93");

            // Same path as ACME_BANKING's PAYMENTS, in the same company — see the header.
            suite(connection, mobileSpare(), ACME, ACME_MOBILE, null, "TS-90", 90, "Mobile spare",
                    "s1");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, null, "TS-91", 91, "Internal", "s91");

            // Two live cases under Refunds — at path `s1.s2`, which is INSIDE the subtree of
            // ACME_BANKING's `s1`. The sibling project's spare folder is also at `s1` and is
            // deliberately left empty: a subtree count that drops its project predicate matches
            // these two cases when asked about the sibling, and refuses a delete of an empty folder
            // for reasons nothing on the user's screen can explain. Both folders are in the same
            // company, so only project_id separates them.
            cases(connection, ACME, ACME_BANKING, REFUNDS, "sw-ref", 2);

            // The counter was reset to 1 and the fixture occupies 1..3, so the next allocation must
            // be 4. Set explicitly rather than left implicit: TS-90/TS-91 exist to keep the sibling
            // trees out of the sequence a create walks, and MAX(key_seq)+1 would answer 92 — which
            // is one of the two implementations DisplayKeys exists to refuse.
            try (var statement = connection.prepareStatement(
                    "UPDATE key_sequence SET next_value = 4 WHERE entity_type = 'SUITE'")) {
                statement.executeUpdate();
            }
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // TS-1 again: keys are numbered per COMPANY, so the collision is the design working.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, null, "TS-1", 1, "Payments", "s1");
            try (var statement = connection.prepareStatement(
                    "UPDATE key_sequence SET next_value = 2 WHERE entity_type = 'SUITE'")) {
                statement.executeUpdate();
            }
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
                    (id, company_id, project_id, parent_id, key, key_seq, path, name,
                     position, lifecycle_status)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                        CAST(? AS ltree), ?, 0, 'APPROVED')
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
            statement.executeUpdate();
        }
    }

    private void cases(
            Connection connection, UUID companyId, UUID projectId, UUID suiteId, String marker,
            int live) throws SQLException {

        for (int index = 0; index < live; index++) {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO test_case
                        (id, company_id, project_id, suite_id, key, key_seq, title)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                            ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1,
                        UUID.nameUUIDFromBytes((marker + index).getBytes()).toString());
                statement.setString(2, companyId.toString());
                statement.setString(3, projectId.toString());
                statement.setString(4, suiteId.toString());
                // Unique per company, so the marker keeps two fixtures from colliding on a key and
                // failing in a way that reads like a tenancy bug.
                statement.setString(5, "TC-" + marker + index);
                statement.setLong(6, 900L + index);
                statement.setString(7, "Seeded " + marker + " " + index);
                statement.executeUpdate();
            }
        }
    }
}
