package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code DELETE /projects/{projectId}/cases/{caseId}} — <del>and the {@code OWN} qualifier it is the
 * first caller of</del> → <strong>and, since {@code V51} (§207), the proof that authorship does
 * <em>not</em> decide it.</strong>
 *
 * <p>This class was written to pin the opposite. {@code case:delete} was {@code OWN}-qualified for
 * Test Engineer from {@code V12} until {@code V51}, and the assertions below were the only thing
 * standing between a contributor and a colleague's case. The requirement reversed: CRUD on plans,
 * cases and steps belongs to every non-Viewer contributor no matter who first created the row. The
 * tests are inverted rather than deleted, because "a Test Engineer deletes a colleague's case" is
 * now the behaviour somebody could regress by reinstating a qualifier, and it needs a witness for
 * the same reason its opposite did.
 *
 * <h2>The failures this class exists to catch</h2>
 *
 * <ol>
 *   <li><strong>Authorship reintroduced.</strong> Requalify the grant, or hard-code an author
 *       comparison at the gate, and a contributor can no longer clean up a shared repository. The
 *       assertion is {@link Authorship#engineerDeletesSomeoneElses}, and note what it does NOT
 *       depend on: it drives HTTP, so it fails for a qualifier in the seed
 *       ({@code SeedDataTest.ownIsCarriedByNoGrant} catches that one first) <em>and</em> for an
 *       authorship check written in Java that no seed data can see.</li>
 *   <li><strong>The delete widened past the Viewer line.</strong> The mirror failure. "Not a
 *       Viewer" is the whole boundary now, so the Viewer 403 is carrying more weight than it used
 *       to — it is the only refusal left on this endpoint that is about the caller rather than the
 *       route.</li>
 *   <li><strong>An unattributable row treated as special.</strong> {@code created_by} is null on
 *       every case written before {@code POST} existed. Those rows were the <em>hardest</em> case
 *       under {@code OWN} and are now the same as any other; a gate still consulting the author
 *       refuses them, which is how a half-reverted change would present.</li>
 *   <li><strong>A hard delete.</strong> Past runs pin the version they executed; the row must
 *       survive with {@code deleted_at} set, and no read may return it. This matters MORE after
 *       {@code V51}, not less: soft delete is the thing the reversal's argument leans on for
 *       recoverability now that the qualifier is gone.</li>
 *   <li><strong>A delete that reaches across the two path segments.</strong> The project gate is not
 *       the case gate: {@code /projects/{granted}/cases/{ungranted}} must be a 404.</li>
 * </ol>
 *
 * <p>Company and user ids are unique to this class — {@code d4…}, checked against every prefix
 * already in {@code src/test/java} before writing. Sharing them is how handoff §19's first defect
 * happened, and §29 hit it again: green alone, red in the full suite.
 */
@AutoConfigureMockMvc
@DisplayName("Case delete — soft, gated on case:delete, and NOT scoped to the author (V51)")
class CaseDeleteEndpointTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("d4000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("d4000000-0000-4000-8000-00000000000b");

    private static final UUID ACME_BANKING = UUID.fromString("d4001000-0000-4000-8000-000000000001");
    /** Same company, granted to nobody here — the sibling-project 404. */
    private static final UUID ACME_LOCKED = UUID.fromString("d4001000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_BANKING = UUID.fromString("d4002000-0000-4000-8000-000000000001");

    /** Test Lead — holds {@code case:delete} unqualified. */
    private static final UUID LEAD = UUID.fromString("d4003000-0000-4000-8000-000000000001");
    /**
     * Test Engineer — holds {@code case:delete} <del>as {@code OWN}</del> unqualified since
     * {@code V51}. The persona of this class, and the one whose reach changed.
     */
    private static final UUID ENGINEER = UUID.fromString("d4003000-0000-4000-8000-000000000002");
    /** A second Test Engineer, so "someone else's case" is authored by a real account. */
    private static final UUID COLLEAGUE = UUID.fromString("d4003000-0000-4000-8000-000000000003");
    /** Viewer — holds {@code project:read} and no write permission at all. */
    private static final UUID VIEWER = UUID.fromString("d4003000-0000-4000-8000-000000000004");
    /** ACTIVE member of Acme holding nothing — persona 3. Must get 404, never 403. */
    private static final UUID NO_GRANT = UUID.fromString("d4003000-0000-4000-8000-000000000005");
    /**
     * Company Admin over Acme <em>and</em> Test Engineer on this project — the caller who proves
     * "strongest wins" over HTTP rather than only in the evaluator.
     */
    private static final UUID ADMIN = UUID.fromString("d4003000-0000-4000-8000-000000000006");
    /** Test Lead in Umbra only. */
    private static final UUID OUTSIDER = UUID.fromString("d4003000-0000-4000-8000-000000000007");

    private static final UUID PAYMENTS = UUID.fromString("d4004000-0000-4000-8000-000000000001");
    private static final UUID LOCKED_SUITE = UUID.fromString("d4004000-0000-4000-8000-000000000002");
    private static final UUID UMBRA_SUITE = UUID.fromString("d4004000-0000-4000-8000-000000000003");

    /** Authored by {@link #ENGINEER}. */
    private static final UUID ENGINEER_CASE = UUID.fromString("d4005000-0000-4000-8000-000000000001");
    /** A second one, so a test that deletes need not fight one that asserts the row is intact. */
    private static final UUID ENGINEER_CASE_2 = UUID.fromString("d4005000-0000-4000-8000-000000000002");
    /** Authored by {@link #COLLEAGUE} — <del>the case an OWN-qualified caller must not touch</del>. */
    private static final UUID COLLEAGUE_CASE = UUID.fromString("d4005000-0000-4000-8000-000000000003");
    /** A second of the colleague's, for the assertions that consume one. */
    private static final UUID COLLEAGUE_CASE_2 = UUID.fromString("d4005000-0000-4000-8000-000000000004");
    /** {@code created_by} IS NULL — a case older than the create endpoint. */
    private static final UUID ORPHAN_CASE = UUID.fromString("d4005000-0000-4000-8000-000000000005");
    /** In the sibling project nobody here is granted. */
    private static final UUID LOCKED_CASE = UUID.fromString("d4005000-0000-4000-8000-000000000006");
    /** In the other client, deliberately keyed like Acme's. */
    private static final UUID UMBRA_CASE = UUID.fromString("d4005000-0000-4000-8000-000000000007");

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
            company(ACME, "cd-acme", "Acme", now);
            company(UMBRA, "cd-umbra", "Umbra", now);

            activeUser(LEAD, "lead@cd-acme.test", "L. Lead", now);
            activeUser(ENGINEER, "eng@cd-acme.test", "E. Engineer", now);
            activeUser(COLLEAGUE, "colleague@cd-acme.test", "C. Colleague", now);
            activeUser(VIEWER, "viewer@cd-acme.test", "V. Viewer", now);
            activeUser(NO_GRANT, "nogrant@cd-acme.test", "N. Grant", now);
            activeUser(ADMIN, "admin@cd-acme.test", "A. Admin", now);
            activeUser(OUTSIDER, "out@cd-umbra.test", "O. Outsider", now);
        });

        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_BANKING, ACME, "DAOLB", "Online Banking", now);
            seedProject(ACME_LOCKED, ACME, "DALCK", "Internal Tools", now);

            member(ACME, LEAD, "L. Lead", now);
            member(ACME, ENGINEER, "E. Engineer", now);
            member(ACME, COLLEAGUE, "C. Colleague", now);
            member(ACME, VIEWER, "V. Viewer", now);
            member(ACME, NO_GRANT, "N. Grant", now);
            member(ACME, ADMIN, "A. Admin", now);

            grantOnProject("cd-lead", ACME, Role.TEST_LEAD_ID, LEAD, ACME_BANKING, now);
            grantOnProject("cd-eng", ACME, Role.TEST_ENGINEER_ID, ENGINEER, ACME_BANKING, now);
            grantOnProject("cd-col", ACME, Role.TEST_ENGINEER_ID, COLLEAGUE, ACME_BANKING, now);
            grantOnProject("cd-view", ACME, Role.VIEWER_ID, VIEWER, ACME_BANKING, now);

            // Both, on the same project. The PROJECT-scope engineer grant is the weaker one, and a
            // merge that let it win would restrict a Company Admin to their own cases.
            grantOnCompany("cd-admin-company", ACME, Role.COMPANY_ADMIN_ID, ADMIN, now);
            grantOnProject("cd-admin-project", ACME, Role.TEST_ENGINEER_ID, ADMIN, ACME_BANKING, now);
        }));

        inCompany(UMBRA, () -> tx.executeWithoutResult(status -> {
            seedProject(UMBRA_BANKING, UMBRA, "DUOLB", "Online Banking", now);
            member(UMBRA, OUTSIDER, "O. Outsider", now);
            grantOnProject("cd-out", UMBRA, Role.TEST_LEAD_ID, OUTSIDER, UMBRA_BANKING, now);
        }));

        seedCompanyDefaults(ACME);
        seedCompanyDefaults(UMBRA);
        resetMutableRows();
        seedContent();
    }

    /**
     * Puts back everything a test can write.
     *
     * <p>Every test in this class deletes something, so without this the second test to name a case
     * finds it already gone and asserts 404 where it meant 204 — green alone, red in the suite,
     * which is handoff §19's defect wearing this class's clothes.
     */
    private void resetMutableRows() throws SQLException {
        for (UUID companyId : List.of(ACME, UMBRA)) {
            try (Connection connection = appConnection(companyId)) {
                execute(connection, "DELETE FROM audit_event");
                execute(connection, "DELETE FROM test_case");
                execute(connection, "DELETE FROM suite");
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    // =====================================================================
    // The gates — held, not held, and unreachable
    // =====================================================================

    @Nested
    @DisplayName("The gates — case:delete, and the 404 that comes before it")
    class Gates {

        @Test
        @DisplayName("a Test Lead deletes a colleague's case and gets 204 with no body")
        void leadCanDelete() throws Exception {
            MvcResult result = delete(LEAD, ACME, ACME_BANKING, COLLEAGUE_CASE);

            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
        }

        @Test
        @DisplayName("a Viewer is refused, and the case is still there")
        void viewerIsRefused() throws Exception {
            assertThat(delete(VIEWER, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(403);
            // A 403 that deleted anyway is worse than either answer on its own.
            assertThat(isLive(ACME, ENGINEER_CASE)).isTrue();
        }

        @Test
        @DisplayName("the Viewer's refusal is the permission and not a broken route")
        void theViewerReachesTheCase() throws Exception {
            // Without this, the test above passes just as well against a route that does not exist
            // or a token that is wrong.
            assertThat(get(VIEWER, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a 403 body names no permission key")
        void theRefusalDoesNotPublishTheCatalogue() throws Exception {
            String refused = delete(VIEWER, ACME, ACME_BANKING, ENGINEER_CASE)
                    .getResponse().getContentAsString();

            assertThat(refused)
                    .as("a key is not actionable by someone who cannot grant it to themselves")
                    .doesNotContain("case:delete");
            assertThat(refused).contains("permission");
        }

        @Test
        @DisplayName("a member with no grant gets 404, never 403")
        void membershipConveysNoDelete() throws Exception {
            // The row is in this caller's own company, so no cross-company assertion reaches it. A
            // 403 here would confirm the project id names something real (§6t).
            MvcResult result = delete(NO_GRANT, ACME, ACME_BANKING, ENGINEER_CASE);

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).isEmpty();
            assertThat(isLive(ACME, ENGINEER_CASE)).isTrue();
        }

        @Test
        @DisplayName("ungranted, foreign and non-existent projects answer 404 with no body")
        void theThreeUnreachableProjectsAnswerIdentically() throws Exception {
            for (UUID projectId : List.of(ACME_LOCKED, UMBRA_BANKING, UUID.randomUUID())) {
                MvcResult result = delete(LEAD, ACME, projectId, ENGINEER_CASE);
                assertThat(result.getResponse().getStatus()).as("project %s", projectId)
                        .isEqualTo(404);
                assertThat(result.getResponse().getContentAsString()).isEmpty();
            }
        }

        @Test
        @DisplayName("a case in another project of the same company is 404, and survives")
        void aCaseIsScopedToItsProject() throws Exception {
            // The caller passed the gate on ACME_BANKING, so only the case query's own project
            // predicate separates them — drop it and a lead granted one project deletes another's
            // cases, with every cross-company test still green.
            assertThat(delete(LEAD, ACME, ACME_BANKING, LOCKED_CASE).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(isLive(ACME, LOCKED_CASE)).isTrue();
        }

        @Test
        @DisplayName("the other client's case is 404 even holding a lead grant here")
        void deletesDoNotCrossCompanies() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, UMBRA_CASE).getResponse().getStatus())
                    .isEqualTo(404);
            assertThat(isLive(UMBRA, UMBRA_CASE)).isTrue();
        }

        @Test
        @DisplayName("an unknown case id is 404, indistinguishable from an ungranted one")
        void anUnknownCaseIs404() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, UUID.randomUUID())
                    .getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("an anonymous caller is refused")
        void anonymousIsRejected() throws Exception {
            assertThat(mvc.perform(MockMvcRequestBuilders
                            .delete("/api/v1/projects/" + ACME_BANKING + "/cases/" + ENGINEER_CASE))
                    .andReturn().getResponse().getStatus())
                    .isEqualTo(401);
            assertThat(isLive(ACME, ENGINEER_CASE)).isTrue();
        }
    }

    // =====================================================================
    // The qualifier — the reason this slice exists
    // =====================================================================

    @Nested
    @DisplayName("Authorship does not decide it — a Test Engineer deletes anyone's case (V51)")
    class Authorship {

        @Test
        @DisplayName("a Test Engineer deletes a case they authored")
        void engineerCanDeleteTheirOwn() throws Exception {
            assertThat(delete(ENGINEER, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(isLive(ACME, ENGINEER_CASE)).isFalse();
        }

        @Test
        @DisplayName("a Test Engineer deletes a COLLEAGUE'S — THE assertion of this class, inverted")
        void engineerDeletesSomeoneElses() throws Exception {
            // §207. This asserted 403 from §65 until V51, and it was the single test the whole OWN
            // qualifier existed to satisfy. The requirement it now encodes: a shared test repository
            // whose rows answer to their author is a set of private notebooks in one table, and the
            // duplicate a colleague left behind is exactly the row somebody else has to remove.
            //
            // It drives HTTP rather than reading the grant, which is what makes it worth keeping
            // after the reversal: SeedDataTest.ownIsCarriedByNoGrant catches a requalified GRANT,
            // and only this catches an authorship comparison hard-coded in Java, which no amount of
            // seed data can see.
            MvcResult result = delete(ENGINEER, ACME, ACME_BANKING, COLLEAGUE_CASE);

            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(isLive(ACME, COLLEAGUE_CASE)).isFalse();
        }

        @Test
        @DisplayName("deleting a peer's case attributes the removal to the DELETER, not the author")
        void aColleaguesCaseRecordsWhoRemovedIt() throws Exception {
            // The recoverability the V51 argument leans on, plus the attribution it newly needs.
            // Under OWN, whoever deleted a case had written it, so updated_by and created_by were
            // the same person and nothing distinguished them. They diverge now, and this is the row
            // an administrator restoring a case reads to find out who removed somebody else's work.
            assertThat(delete(ENGINEER, ACME, ACME_BANKING, COLLEAGUE_CASE_2).getResponse().getStatus())
                    .isEqualTo(204);

            assertThat(isLive(ACME, COLLEAGUE_CASE_2))
                    .as("soft — the row survives, which is what makes the reversal recoverable")
                    .isFalse();
            assertThat(caseColumn(ACME, COLLEAGUE_CASE_2, "CAST(updated_by AS text)"))
                    .as("TestCase.softDelete records the actor; the author stays on created_by")
                    .isEqualTo(ENGINEER.toString());
            assertThat(caseColumn(ACME, COLLEAGUE_CASE_2, "CAST(created_by AS text)"))
                    .isEqualTo(COLLEAGUE.toString());
        }

        @Test
        @DisplayName("a Viewer is still refused, and that 403 is now the whole boundary")
        void aViewerIsStillRefused() throws Exception {
            // What is left of the gate. Every non-Viewer contributor deletes; the Viewer does not,
            // and there is no longer a second, softer refusal beside this one — the message that
            // said "you can only delete test cases you created" is unreachable from seeded data
            // (CaseDeleteService keeps it for a future OWN grant, deliberately).
            MvcResult result = delete(VIEWER, ACME, ACME_BANKING, ENGINEER_CASE_2);

            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(isLive(ACME, ENGINEER_CASE_2)).isTrue();
            assertThat(json.readTree(result.getResponse().getContentAsString()).get("detail").asText())
                    .as("names no permission key — a key is not actionable by someone who cannot "
                            + "grant it to themselves")
                    .doesNotContain("case:delete");
        }

        @Test
        @DisplayName("a case with no recorded author is deleted like any other")
        void anUnattributableCaseIsNoLongerSpecial() throws Exception {
            // created_by is null on every case older than POST. Under OWN these were the hardest
            // rows — unattributable, so nobody's own, so refused to the contributor and 403 by
            // design. They are now ordinary, and this is the assertion that catches a HALF-reverted
            // change: a gate still consulting the author passes every test above and refuses here,
            // because null equals nobody.
            assertThat(delete(ENGINEER, ACME, ACME_BANKING, ORPHAN_CASE).getResponse().getStatus())
                    .isEqualTo(204);
            assertThat(isLive(ACME, ORPHAN_CASE)).isFalse();
        }

        @Test
        @DisplayName("a Test Lead deletes the same rows — the tier above lost nothing")
        void aLeadStillDeletesAnything() throws Exception {
            // The mirror of the widening. V51 raised the contributor to the lead's reach on this
            // key; it must not have done it by lowering the lead.
            assertThat(delete(LEAD, ACME, ACME_BANKING, COLLEAGUE_CASE).getResponse().getStatus())
                    .isEqualTo(204);
        }

        @Test
        @DisplayName("a Company Admin who also holds a Test Engineer grant keeps the unqualified one")
        void anExtraWeakerGrantTakesNothingAway() throws Exception {
            // Strongest wins, over HTTP. NOTE this assertion went vacuous at V51 and is kept
            // knowingly: both grants are ALL now, so it can no longer fail for the reason it was
            // written (a merge letting the weaker PROJECT-scope grant win, quietly demoting a
            // Company Admin who is given a project role). PermissionQualifierTest and
            // SeedDataTest.noPermissionCarriesTwoDifferentRestrictiveQualifiers cover the merge on
            // the qualifier that IS still populated. Deleting this would leave the HTTP-level
            // regression uncovered the day any grant becomes restrictive again.
            assertThat(delete(ADMIN, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(204);
        }
    }

    // =====================================================================
    // What a delete does to the data
    // =====================================================================

    @Nested
    @DisplayName("Soft — the row survives, every read stops returning it")
    class Effect {

        @Test
        @DisplayName("deleted_at is set and the content is still on the row")
        void theDeleteIsSoft() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(204);

            // A past run pinned the version it executed and must keep resolving what it covered
            // (§3 lesson 1). A hard delete rewrites history rather than removing a case.
            assertThat(caseColumn(ACME, ENGINEER_CASE, "CAST(deleted_at AS text)")).isNotNull();
            assertThat(caseColumn(ACME, ENGINEER_CASE, "title")).isEqualTo("Engineer's case");
            assertThat(caseColumn(ACME, ENGINEER_CASE, "CAST(updated_by AS text)"))
                    .isEqualTo(LEAD.toString());
        }

        @Test
        @DisplayName("the detail endpoint answers 404 afterwards")
        void theCaseStopsBeingReadable() throws Exception {
            assertThat(get(LEAD, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(200);

            delete(LEAD, ACME, ACME_BANKING, ENGINEER_CASE);

            assertThat(get(LEAD, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .as("offering edit, execute and link on a removed case is worse than saying "
                            + "it is gone")
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("it leaves the list, and the cases beside it stay")
        void theListLosesExactlyOneRow() throws Exception {
            assertThat(listedCaseIds()).contains(ENGINEER_CASE, COLLEAGUE_CASE);

            delete(LEAD, ACME, ACME_BANKING, ENGINEER_CASE);

            assertThat(listedCaseIds())
                    .doesNotContain(ENGINEER_CASE)
                    .contains(COLLEAGUE_CASE, ENGINEER_CASE_2);
        }

        @Test
        @DisplayName("a second delete is 404, not a second 204")
        void deletingTwiceIsNotIdempotentlySuccessful() throws Exception {
            assertThat(delete(LEAD, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(204);
            // Reporting success for a delete this request did not perform is the same untruth as a
            // 201 on a replayed create.
            assertThat(delete(LEAD, ACME, ACME_BANKING, ENGINEER_CASE).getResponse().getStatus())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("the suite above it is untouched")
        void nothingCascadesUpward() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, ENGINEER_CASE);

            try (Connection connection = appConnection(ACME);
                 var statement = connection.prepareStatement(
                         "SELECT deleted_at FROM suite WHERE id = CAST(? AS uuid)")) {
                statement.setString(1, PAYMENTS.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isNull();
                }
            }
        }

        @Test
        @DisplayName("an audit row records the key and the title, escaped")
        void theDeleteIsAudited() throws Exception {
            delete(LEAD, ACME, ACME_BANKING, COLLEAGUE_CASE);

            Map<String, String> events = auditEvents(ACME);
            assertThat(events).containsKey("case.deleted");
            // The title is the part that must be recoverable without reading a soft-deleted row —
            // and it carries a quote and a backslash on purpose: concatenated raw it produces a
            // malformed JSONB document, which fails the insert and takes the delete down with it.
            assertThat(json.readTree(events.get("case.deleted")).get("title").asText())
                    .isEqualTo("The \"colleague\" case \\ v2");
            assertThat(json.readTree(events.get("case.deleted")).get("key").asText())
                    .isEqualTo("TC-cd-col");
        }

        @Test
        @DisplayName("a refused delete writes no audit row")
        void nothingIsRecordedForARefusal() throws Exception {
            // Only the Viewer refuses now. The engineer-on-a-colleague's-case arm was here until
            // V51 (§207) and had to go, because that call is a 204 — leaving it would have made
            // this test assert "no audit row" about a delete that succeeded, which is the exact
            // inverse of what it exists for.
            delete(VIEWER, ACME, ACME_BANKING, ENGINEER_CASE);

            assertThat(auditEvents(ACME)).doesNotContainKey("case.deleted");
        }
    }

    // =====================================================================
    // helpers — HTTP
    // =====================================================================

    private MvcResult delete(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .delete("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private MvcResult get(UUID userId, UUID companyId, UUID projectId, UUID caseId)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + projectId + "/cases/" + caseId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId, companyId)))
                .andReturn();
    }

    private List<UUID> listedCaseIds() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + ACME_BANKING + "/cases")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        List<UUID> ids = new java.util.ArrayList<>();
        json.readTree(result.getResponse().getContentAsString()).get("items")
                .forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
        return ids;
    }

    private String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    // =====================================================================
    // helpers — reading the database back
    //
    // Through the APP role and the company's own context, not the container superuser: it is the
    // policed path production reads through.
    // =====================================================================

    private boolean isLive(UUID companyId, UUID caseId) throws SQLException {
        return caseColumn(companyId, caseId, "CAST(deleted_at AS text)") == null;
    }

    /** {@code column} is a literal from this class only — never request input. */
    private String caseColumn(UUID companyId, UUID caseId, String column) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT " + column + " FROM test_case WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, caseId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("case %s is not visible to company %s", caseId, companyId)
                        .isTrue();
                return rows.getString(1);
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

    private void grantOnCompany(
            String marker, UUID companyId, UUID roleId, UUID userId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(
                    RoleAssignment.forUserOnCompany(id, companyId, roleId, userId, null, now));
        }
    }

    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("case-delete-endpoint:" + marker).getBytes());
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    private void seedContent() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            seedProjectDefaults(connection, ACME, ACME_BANKING);
            seedProjectDefaults(connection, ACME, ACME_LOCKED);

            suite(connection, PAYMENTS, ACME, ACME_BANKING, "TS-cd-1", 1, "Payments", "s1");
            suite(connection, LOCKED_SUITE, ACME, ACME_LOCKED, "TS-cd-2", 2, "Internal", "s2");

            testCase(connection, ENGINEER_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cd-eng", 901,
                    "Engineer's case", ENGINEER);
            testCase(connection, ENGINEER_CASE_2, ACME, ACME_BANKING, PAYMENTS, "TC-cd-eng2", 902,
                    "Engineer's other case", ENGINEER);
            // The quote and the backslash are the audit escaper's test input, carried by a fixture
            // rather than by a test that writes its own row.
            testCase(connection, COLLEAGUE_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cd-col", 903,
                    "The \"colleague\" case \\ v2", COLLEAGUE);
            testCase(connection, COLLEAGUE_CASE_2, ACME, ACME_BANKING, PAYMENTS, "TC-cd-col2", 904,
                    "The colleague's other case", COLLEAGUE);
            testCase(connection, ORPHAN_CASE, ACME, ACME_BANKING, PAYMENTS, "TC-cd-orph", 905,
                    "Written before authorship was recorded", null);
            testCase(connection, LOCKED_CASE, ACME, ACME_LOCKED, LOCKED_SUITE, "TC-cd-lock", 906,
                    "In the locked project", LEAD);
        }
        try (Connection connection = appConnection(UMBRA)) {
            seedProjectDefaults(connection, UMBRA, UMBRA_BANKING);
            // Deliberately keyed like Acme's: keys are numbered per company, so the collision is
            // the design working and a query that leaned on key uniqueness would find two rows.
            suite(connection, UMBRA_SUITE, UMBRA, UMBRA_BANKING, "TS-cd-1", 1, "Payments", "s1");
            testCase(connection, UMBRA_CASE, UMBRA, UMBRA_BANKING, UMBRA_SUITE, "TC-cd-eng", 901,
                    "Engineer's case", null);
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
            Connection connection, UUID id, UUID companyId, UUID projectId, String key, long keySeq,
            String name, String path) throws SQLException {

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

    /** @param authorId written to {@code created_by}; null seeds a case older than {@code POST} */
    private void testCase(
            Connection connection, UUID id, UUID companyId, UUID projectId, UUID suiteId,
            String key, long keySeq, String title, UUID authorId) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, created_by)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                        ?, ?, ?, CAST(? AS uuid))
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, id.toString());
            statement.setString(2, companyId.toString());
            statement.setString(3, projectId.toString());
            statement.setString(4, suiteId.toString());
            statement.setString(5, key);
            statement.setLong(6, keySeq);
            statement.setString(7, title);
            statement.setString(8, authorId == null ? null : authorId.toString());
            statement.executeUpdate();
        }
    }
}
