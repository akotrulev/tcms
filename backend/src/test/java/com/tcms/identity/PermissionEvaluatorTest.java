package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The permission harness — personas 3 and 4 of the M0 isolation suite, plus the
 * evaluator's own gate matrix.
 *
 * <p>Personas 1 and 2 (the two companies with identical-looking data) live in
 * {@code RlsIsolationTest} and prove that one client cannot read another's rows. The two
 * personas here are the ones those assertions <strong>structurally cannot catch</strong>:
 *
 * <ol start="3">
 *   <li><strong>{@code memberNoGrant}</strong> — an ACTIVE member of Acme holding no
 *       grant at all. The rows they must not see <em>are in their own company</em>, so
 *       every cross-company assertion in {@code RlsIsolationTest} passes while this leak
 *       is wide open. Company membership conveys authentication and zero project
 *       visibility (roles spec A1), and this is the only test that says so.</li>
 *   <li><strong>{@code crossCompany}</strong> — Company Admin in Acme, Test Engineer in
 *       Globex. Asserts no permission bleeds across a company switch, which is where a
 *       context-keyed cache or a stale thread-local would show.</li>
 * </ol>
 *
 * <p>The fixture is deliberately hostile in the same way: both companies own a project
 * with the <strong>same key and the same name</strong>, so a test cannot pass by having
 * queried the convenient row.
 */
@DisplayName("Permission evaluator — the union, its gates, and personas 3 and 4")
class PermissionEvaluatorTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GLOBEX = UUID.fromString("44444444-4444-4444-4444-444444444444");
    /** Provisioned already suspended, so no test has to mutate a company another test reads. */
    private static final UUID ZENITH = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final UUID ACME_PROJECT = UUID.fromString("33330000-0000-4000-8000-000000000001");
    private static final UUID GLOBEX_PROJECT = UUID.fromString("44440000-0000-4000-8000-000000000001");
    private static final UUID ZENITH_PROJECT = UUID.fromString("55550000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID MEMBER_NO_GRANT = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID CROSS_COMPANY = UUID.fromString("00000000-0000-4000-8000-0000000000a3");
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-4000-8000-0000000000a4");
    private static final UUID TEAM_ENGINEER = UUID.fromString("00000000-0000-4000-8000-0000000000a5");
    private static final UUID INVITED_ONLY = UUID.fromString("00000000-0000-4000-8000-0000000000a6");
    private static final UUID DEACTIVATED_OPERATOR = UUID.fromString("00000000-0000-4000-8000-0000000000a7");
    private static final UUID ZENITH_LEAD = UUID.fromString("00000000-0000-4000-8000-0000000000a8");
    /**
     * Company Admin over Acme <em>and</em> Test Engineer on Acme's project — the one persona that
     * separates "strongest wins" from "last row read wins" when the same permission arrives twice
     * with two qualifiers.
     */
    private static final UUID DUAL_GRANT = UUID.fromString("00000000-0000-4000-8000-0000000000a9");

    private static final UUID ACME_QA_TEAM = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    /*
     * ids `ab` and `ac`, checked against every tail already in src/test/java before writing —
     * `c1` was taken by AppUserEmailCaseTest and reusing it cost a debugging round. The guards in
     * this class's seed are `if (findById(...).isEmpty())`, so a colliding id does NOT fail loudly:
     * the row already exists, creation is skipped, and this class inherits the other one's account
     * state. That zeroes the ACCOUNT gate, so every permission reads false and the failure looks
     * like the feature under test is broken rather than the fixture. Handoff §19, and §29 and §54
     * each hit it again.
     */

    /**
     * Company-wide Test Lead, plus a Viewer grant on {@link #ACME_PROJECT} — the override persona.
     *
     * <p>Both halves are needed to tell the two failure modes apart: without the company-wide grant
     * a missing inheritance looks the same as a working override, and without the project grant a
     * broken override looks the same as working inheritance.
     */
    private static final UUID INHERITOR = UUID.fromString("00000000-0000-4000-8000-0000000000ab");

    /**
     * Company-wide Test Engineer + Test Lead on {@link #ACME_PROJECT} — the same shape one level
     * down, where what differs between the two roles is the {@code artifact:edit} QUALIFIER
     * ({@code PLAN_SCOPE} vs {@code ALL}) rather than the set of keys.
     */
    private static final UUID QUALIFIED_INHERITOR =
            UUID.fromString("00000000-0000-4000-8000-0000000000ac");

    /** A second Acme project with no grant naming anybody here — where inheritance is observable. */
    private static final UUID ACME_SECOND_PROJECT =
            UUID.fromString("33330000-0000-4000-8000-000000000002");

    @Autowired
    private PermissionEvaluator evaluator;

    @Autowired
    private PermissionCatalogue catalogue;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private TeamMembershipRepository teamMemberships;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private InstanceRoleAssignmentRepository instanceRoleAssignments;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        // --- instance level: companies and accounts, no company context ------------
        tx.executeWithoutResult(status -> {
            provisionCompany(ACME, "acme-perm", "Acme Corporation", false, now);
            provisionCompany(GLOBEX, "globex-perm", "Globex Inc", false, now);
            provisionCompany(ZENITH, "zenith-perm", "Zenith Ltd", true, now);

            activeUser(LEAD, "lead@example.test", now);
            activeUser(MEMBER_NO_GRANT, "nogrant@example.test", now);
            activeUser(CROSS_COMPANY, "cross@example.test", now);
            activeUser(OPERATOR, "operator@example.test", now);
            activeUser(TEAM_ENGINEER, "team@example.test", now);
            activeUser(INVITED_ONLY, "invited@example.test", now);
            activeUser(ZENITH_LEAD, "zenith@example.test", now);
            activeUser(DUAL_GRANT, "dual@example.test", now);
            activeUser(INHERITOR, "inheritor@example.test", now);
            activeUser(QUALIFIED_INHERITOR, "qualified@example.test", now);

            // Deactivated, but holding the strongest grant in the product — so the
            // account gate is proven to cover SYSTEM scope, not just company scope.
            if (users.findById(DEACTIVATED_OPERATOR).isEmpty()) {
                AppUser user = AppUser.invite(
                        DEACTIVATED_OPERATOR, "gone@example.test", "Former Operator", now);
                user.activate("x", now);
                user.deactivate(now);
                users.save(user);
            }

            grantInstanceAdmin(OPERATOR, now);
            grantInstanceAdmin(DEACTIVATED_OPERATOR, now);
        });

        // --- Acme -----------------------------------------------------------------
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ACME_PROJECT, ACME, "acme-marker", now);
            // A distinct key: uq_project_key is per company, and every other project in this class
            // deliberately reuses "OLB" ACROSS companies to prove isolation. A second one INSIDE
            // Acme is the one case that has to differ.
            seedProjectWithKey(
                    ACME_SECOND_PROJECT, ACME, "OLB2", "Online Banking II", "acme-second-marker", now);

            member(ACME, LEAD, "Lead", true, now);
            member(ACME, MEMBER_NO_GRANT, "No Grant", true, now);
            member(ACME, CROSS_COMPANY, "Cross", true, now);
            member(ACME, TEAM_ENGINEER, "Team Engineer", true, now);
            // INVITED, deliberately not accepted — the gate must zero their grant.
            member(ACME, INVITED_ONLY, "Invitee", false, now);
            member(ACME, DEACTIVATED_OPERATOR, "Former Operator", true, now);
            // OPERATOR is deliberately NOT a member of any company.

            if (teams.findById(ACME_QA_TEAM).isEmpty()) {
                teams.save(Team.create(ACME_QA_TEAM, ACME, "QA", null, null, now));
            }
            joinTeam(ACME, ACME_QA_TEAM, TEAM_ENGINEER, now);

            grantOnProject("a1", ACME, Role.TEST_LEAD_ID, LEAD, ACME_PROJECT, now);
            grantOnProject("a6", ACME, Role.TEST_LEAD_ID, INVITED_ONLY, ACME_PROJECT, now);
            grantOnProject("a7", ACME, Role.TEST_LEAD_ID, DEACTIVATED_OPERATOR, ACME_PROJECT, now);
            grantOnCompany("a3", ACME, Role.COMPANY_ADMIN_ID, CROSS_COMPANY, now);
            grantToTeamOnProject("b1", ACME, Role.TEST_ENGINEER_ID, ACME_QA_TEAM, ACME_PROJECT, now);

            // Both grants on the same user, the weaker one PROJECT-scoped and arriving second in
            // the evaluator's union — so a "last term wins" merge answers OWN for a Company Admin.
            member(ACME, DUAL_GRANT, "Dual Grant", true, now);
            grantOnCompany("a9c", ACME, Role.COMPANY_ADMIN_ID, DUAL_GRANT, now);
            grantOnProject("a9p", ACME, Role.TEST_ENGINEER_ID, DUAL_GRANT, ACME_PROJECT, now);

            // Inheritance: the company-wide role, and the project that overrides it downward.
            member(ACME, INHERITOR, "Inheritor", true, now);
            grantOnCompany("c1c", ACME, Role.TEST_LEAD_ID, INHERITOR, now);
            grantOnProject("c1p", ACME, Role.VIEWER_ID, INHERITOR, ACME_PROJECT, now);

            member(ACME, QUALIFIED_INHERITOR, "Qualified Inheritor", true, now);
            grantOnCompany("c2c", ACME, Role.TEST_ENGINEER_ID, QUALIFIED_INHERITOR, now);
            grantOnProject("c2p", ACME, Role.TEST_LEAD_ID, QUALIFIED_INHERITOR, ACME_PROJECT, now);
        }));

        // --- Globex: same project key and name as Acme's --------------------------
        inCompany(GLOBEX, () -> tx.executeWithoutResult(status -> {
            seedProject(GLOBEX_PROJECT, GLOBEX, "globex-marker", now);
            member(GLOBEX, CROSS_COMPANY, "Cross (Vendor)", true, now);
            grantOnProject("g3", GLOBEX, Role.TEST_ENGINEER_ID, CROSS_COMPANY, GLOBEX_PROJECT, now);
        }));

        // --- Zenith: suspended ----------------------------------------------------
        inCompany(ZENITH, () -> tx.executeWithoutResult(status -> {
            seedProject(ZENITH_PROJECT, ZENITH, "zenith-marker", now);
            member(ZENITH, ZENITH_LEAD, "Zenith Lead", true, now);
            grantOnProject("z1", ZENITH, Role.TEST_LEAD_ID, ZENITH_LEAD, ZENITH_PROJECT, now);
        }));
    }

    // =========================================================================
    // Persona 3 — the member with no project grant
    // =========================================================================

    @Nested
    @DisplayName("Persona 3 — an ACTIVE member holding no grant")
    class MemberWithNoGrant {

        @Test
        @DisplayName("resolves to nothing on a project in their OWN company")
        void seesNothingInTheirOwnCompany() {
            // The leak RlsIsolationTest structurally cannot catch: this project IS in
            // this user's company, so every cross-company assertion passes regardless.
            PermissionSet permissions = evaluateIn(ACME, MEMBER_NO_GRANT, ACME_PROJECT);

            assertThat(permissions.isEmpty())
                    .as("company membership conveys authentication and ZERO project visibility")
                    .isTrue();
            assertThat(permissions.has("case:read")).isFalse();
            assertThat(permissions.has("project:read")).isFalse();
        }

        @Test
        @DisplayName("holds nothing company-wide either")
        void seesNothingCompanyWide() {
            assertThat(evaluateCompanyWideIn(ACME, MEMBER_NO_GRANT).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("the fixture is real — the same project DOES yield permissions to a grant holder")
        void theProjectIsNotSimplyEmpty() {
            // Without this, "sees nothing" would also pass if the project did not exist,
            // if the seed silently failed, or if the evaluator returned empty for everyone.
            assertThat(evaluateIn(ACME, LEAD, ACME_PROJECT).has("case:read")).isTrue();
        }
    }

    // =========================================================================
    // Persona 4 — the cross-company user
    // =========================================================================

    @Nested
    @DisplayName("Persona 4 — Company Admin in Acme, Test Engineer in Globex")
    class CrossCompanyUser {

        @Test
        @DisplayName("holds Company Admin capability in Acme")
        void companyAdminInAcme() {
            PermissionSet acme = evaluateCompanyWideIn(ACME, CROSS_COMPANY);

            assertThat(acme.hasAll("company:settings", "company:members", "project:create")).isTrue();
            // COMPANY scope covers every project, present and future — with no project grant.
            assertThat(evaluateIn(ACME, CROSS_COMPANY, ACME_PROJECT)
                    .hasAll("suite:delete", "run:close", "case:approve"))
                    .isTrue();
        }

        @Test
        @DisplayName("holds ONLY Test Engineer capability in Globex — no admin bleed")
        void testEngineerInGlobex() {
            PermissionSet globex = evaluateIn(GLOBEX, CROSS_COMPANY, GLOBEX_PROJECT);

            assertThat(globex.hasAll("case:create", "case:edit", "run:execute")).isTrue();

            // The bleed assertions. Each names a capability they genuinely hold in Acme.
            assertThat(globex.has("company:settings"))
                    .as("Company Admin in Acme must confer nothing in Globex")
                    .isFalse();
            assertThat(globex.has("project:create")).isFalse();
            assertThat(globex.has("case:approve")).isFalse();
            // ~~run:close~~ — V52 (§210) gave it to Test Engineer, so it stopped being a capability
            // that could only have arrived from Acme and the assertion stopped detecting a bleed.
            // project:members is the replacement: Lead-and-up, so a contributor in Globex must not
            // have it however they are administered elsewhere.
            assertThat(globex.has("project:members")).isFalse();
            assertThat(globex.has("project:settings")).isFalse();
        }

        @Test
        @DisplayName("switching company back and forth does not accumulate permissions")
        void switchingCompaniesDoesNotAccumulate() {
            // Where a context-keyed cache or a stale thread-local would show. Evaluated
            // in the order Acme -> Globex -> Acme so a leak in either direction fails.
            assertThat(evaluateCompanyWideIn(ACME, CROSS_COMPANY).has("company:settings")).isTrue();
            assertThat(evaluateIn(GLOBEX, CROSS_COMPANY, GLOBEX_PROJECT).has("company:settings"))
                    .isFalse();
            assertThat(evaluateCompanyWideIn(ACME, CROSS_COMPANY).has("company:settings")).isTrue();
        }

        @Test
        @DisplayName("a foreign project id resolves to NOTHING, not to this company's company-wide grants")
        void foreignProjectIsUnreachable() {
            // Found by this test failing on the first run, and it was the evaluator that
            // was wrong, not the assertion. COMPANY-scope grants carry no project id, so
            // before the fix a Globex project id evaluated in Acme context picked up
            // Acme's Company Admin grant and answered "yes" — i.e. it answered "does this
            // user hold X somewhere in Acme", not the question asked.
            //
            // Both projects share a key and a name, so this cannot pass by coincidence.
            PermissionSet permissions = evaluateIn(ACME, CROSS_COMPANY, GLOBEX_PROJECT);

            assertThat(permissions.isEmpty())
                    .as("a project outside the active company yields no capability at all")
                    .isTrue();

            // Stated separately: this user genuinely holds both of these in Acme, so an
            // empty result here is the boundary working rather than the user being powerless.
            assertThat(evaluateIn(ACME, CROSS_COMPANY, ACME_PROJECT).hasAll("case:create", "case:approve"))
                    .isTrue();
        }

        @Test
        @DisplayName("an unknown project id resolves to nothing, identically to a foreign one")
        void unknownProjectIsIndistinguishableFromAForeignOne() {
            // §6t: unreachable and non-existent must be indistinguishable, or the response
            // becomes an oracle for what exists in another client's tenancy.
            assertThat(evaluateIn(ACME, CROSS_COMPANY, UUID.randomUUID()).isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // The gates
    // =========================================================================

    @Nested
    @DisplayName("Gates — each zeroes the union without contributing to it")
    class Gates {

        @Test
        @DisplayName("an INVITED membership conveys nothing, even holding a Test Lead grant")
        void invitedMembershipIsNotAMembership() {
            // An unaccepted invitation must not be a way to read a client's data before
            // agreeing to anything. The grant exists and is deliberately ignored.
            assertThat(evaluateIn(ACME, INVITED_ONLY, ACME_PROJECT).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a DEACTIVATED account holds nothing — including its SYSTEM grant")
        void deactivationIsTheInstanceWideOffSwitch() {
            assertThat(evaluateIn(ACME, DEACTIVATED_OPERATOR, ACME_PROJECT).isEmpty()).isTrue();
            assertThat(evaluateCompanyWideIn(ACME, DEACTIVATED_OPERATOR).isEmpty()).isTrue();
            assertThat(evaluator.evaluateInstanceWide(DEACTIVATED_OPERATOR).isEmpty())
                    .as("a deactivated operator must lose Instance Admin too, or deactivation is not an off switch")
                    .isTrue();
        }

        @Test
        @DisplayName("a SUSPENDED company zeroes its members")
        void suspensionIsHonouredInCode() {
            // The status column enforces nothing on its own (plan §6s) — every row Zenith
            // owns still exists and is still readable by RLS. This is the code that stops it.
            assertThat(evaluateIn(ZENITH, ZENITH_LEAD, ZENITH_PROJECT).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("but an Instance Admin still reaches a SUSPENDED company — or it is unrecoverable")
        void suspensionDoesNotLockOutTheOperator() {
            assertThat(evaluateIn(ZENITH, OPERATOR, ZENITH_PROJECT).coversEverything())
                    .as("suspend-never-delete requires that SYSTEM survive suspension")
                    .isTrue();
        }
    }

    // =========================================================================
    // SYSTEM scope
    // =========================================================================

    @Nested
    @DisplayName("SYSTEM scope — ungated by membership, by design")
    class SystemScope {

        @Test
        @DisplayName("an Instance Admin who is a member of NO company still holds everything in one")
        void instanceAdminNeedsNoMembership() {
            // The decision the spec left ambiguous. An operator provisions a company
            // before any membership row exists; gating SYSTEM on membership would lock
            // them out of the company they just created.
            assertThat(memberOf(ACME, OPERATOR))
                    .as("fixture precondition: the operator is deliberately not a member")
                    .isFalse();

            assertThat(evaluateIn(ACME, OPERATOR, ACME_PROJECT).coversEverything()).isTrue();
        }

        @Test
        @DisplayName("full coverage is DERIVED from the catalogue, never a stored wildcard")
        void coverageIsDerived() {
            PermissionSet operator = evaluateIn(ACME, OPERATOR, ACME_PROJECT);

            assertThat(operator.keys())
                    .as("every catalogued permission, counted — a '*' sentinel would pass this vacuously")
                    .hasSameSizeAs(catalogue.keys())
                    .containsExactlyInAnyOrderElementsOf(catalogue.keys());
            assertThat(operator.keys()).doesNotContain("*");
        }

        @Test
        @DisplayName("resolves with NO company context open — the login path")
        void systemGrantsResolveBeforeAnyCompanyIsActive() {
            try (var ignored = CompanyContext.clear()) {
                assertThat(evaluator.evaluateInstanceWide(OPERATOR).coversEverything()).isTrue();
            }
        }

        @Test
        @DisplayName("a regular user holds nothing instance-wide")
        void aCompanyAdminIsNotAnOperator() {
            // Company Admin must not cross the client boundary. Company-scope grants live
            // in a different table, so a leak here would mean the SYSTEM query is reading
            // the wrong one.
            assertThat(evaluator.evaluateInstanceWide(CROSS_COMPANY).isEmpty()).isTrue();
            assertThat(evaluateCompanyWideIn(ACME, CROSS_COMPANY).has("company:delete"))
                    .as("company:delete is SYSTEM scope — Company Admin must not hold it")
                    .isFalse();
        }
    }

    // =========================================================================
    // Team-granted roles
    // =========================================================================

    @Nested
    @DisplayName("Team grants — one hop, and company-bound")
    class TeamGrants {

        @Test
        @DisplayName("a role granted to a team resolves for its members")
        void teamGrantConfersItsRole() {
            PermissionSet permissions = evaluateIn(ACME, TEAM_ENGINEER, ACME_PROJECT);

            assertThat(permissions.hasAll("case:create", "case:edit", "run:execute")).isTrue();
            assertThat(permissions.has("case:approve"))
                    .as("Test Engineer, not Test Lead — the team grant must not widen the role")
                    .isFalse();
        }

        @Test
        @DisplayName("a team grant confers nothing in another company")
        void teamGrantDoesNotCrossCompanies() {
            assertThat(evaluateIn(GLOBEX, TEAM_ENGINEER, GLOBEX_PROJECT).isEmpty()).isTrue();
        }
    }

    // =========================================================================
    // The Test Engineer / Test Lead boundary (roles spec A4)
    // =========================================================================

    @Test
    @DisplayName("the two lines separating Test Engineer from Test Lead hold — through the evaluator")
    void testEngineerToTestLeadBoundary() {
        PermissionSet engineer = evaluateIn(ACME, TEAM_ENGINEER, ACME_PROJECT);
        PermissionSet lead = evaluateIn(ACME, LEAD, ACME_PROJECT);

        /*
          ~~1. content vs shared structure and judgement — V51 (§207) rewrote this line.~~ → DELETED
          by V52 (§210), which is the third statement of a line that has been rewritten twice: a
          contributor creates, edits and deletes everything in the project.

          This test is the EVALUATOR's view of what SeedDataTest.testEngineerToTestLeadBoundary
          asserts against the catalogue, and the duplication is deliberate — that one reads
          role_permission, this one reads what a caller resolves to after scopes, teams and
          qualifiers have composed. A grant present in the seed and lost in the union fails only
          here.
        */
        assertThat(engineer.hasAll("case:create", "case:edit", "plan:create", "run:create")).isTrue();
        assertThat(engineer.hasAll("case:delete", "evidence:delete", "plan:delete")).isTrue();
        assertThat(engineer.hasAll(
                "suite:delete", "artifact:delete", "run:close", "result:amend", "doc:publish"))
                .as("the five V52 moved down")
                .isTrue();
        assertThat(lead.hasAll("suite:delete", "plan:delete", "run:close", "result:amend")).isTrue();

        // 1. review vs approve — contributors review, only Lead+ signs off.
        assertThat(engineer.hasAll("case:review", "suite:review", "plan:review")).isTrue();
        assertThat(engineer.hasAny("case:approve", "suite:approve", "plan:approve")).isFalse();
        assertThat(lead.hasAll("case:approve", "suite:approve", "plan:approve")).isTrue();

        // 2. content vs configuration.
        assertThat(engineer.hasAny(
                "project:settings", "project:members", "project:audit", "integration:manage"))
                .isFalse();
        assertThat(lead.hasAll(
                "project:settings", "project:members", "project:audit", "integration:manage"))
                .isTrue();
    }

    // =========================================================================
    // The evaluator's own contract
    // =========================================================================

    @Nested
    @DisplayName("Contract — fails loudly rather than answering a different question")
    class Contract {

        @Test
        @DisplayName("evaluating without a company context throws, rather than resolving to nothing")
        void requiresCompanyContext() {
            // Returning empty here would be indistinguishable from a legitimate denial,
            // and would hide a missing CompanyContext.open() at the call site forever.
            try (var ignored = CompanyContext.clear()) {
                assertThatThrownBy(() -> evaluator.evaluate(LEAD, ACME_PROJECT))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("No company context is active");
            }
        }

        @Test
        @DisplayName("a null projectId is refused — it would silently answer a company-wide question")
        void refusesNullProject() {
            inCompany(ACME, () -> assertThatThrownBy(() -> evaluator.evaluate(LEAD, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evaluateCompanyWide"));
        }

        @Test
        @DisplayName("an unknown permission key throws instead of quietly denying")
        void unknownPermissionKeyThrows() {
            // A typo returning false is indistinguishable from a real denial, so the bug
            // presents as "this user is missing a permission" and is investigated against
            // the grants, where nothing is wrong.
            PermissionSet permissions = evaluateIn(ACME, LEAD, ACME_PROJECT);

            assertThatThrownBy(() -> permissions.has("case:edt"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown permission key");
        }

        @Test
        @DisplayName("hasAny validates every argument, not just up to the first match")
        void hasAnyDoesNotShortCircuitPastATypo() {
            PermissionSet permissions = evaluateIn(ACME, LEAD, ACME_PROJECT);

            assertThatThrownBy(() -> permissions.hasAny("case:read", "case:edt"))
                    .as("short-circuiting on the first match would let a typo through unnoticed")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("company-wide evaluation does not fold in a single project's grants")
        void companyWideIsNotTheUnionOfProjects() {
            // A Test Lead holds project:settings on their project. Reporting that
            // company-wide would read as authority over the whole company.
            assertThat(evaluateCompanyWideIn(ACME, LEAD).has("project:settings")).isFalse();
            assertThat(evaluateIn(ACME, LEAD, ACME_PROJECT).has("project:settings")).isTrue();
        }
    }

    // =========================================================================
    // The OWN qualifier (roles spec §A5)
    // =========================================================================

    @Nested
    @DisplayName("The OWN qualifier — which ROWS a grant covers, not whether it is held")
    class Qualifier {

        @Test
        @DisplayName("a Test Lead's case:delete is unqualified")
        void leadDeletesAnything() {
            assertThat(qualifierIn(ACME, LEAD, ACME_PROJECT, "case:delete"))
                    .isEqualTo(PermissionQualifier.ALL);
        }

        @Test
        @DisplayName("a Test Engineer's case:delete is ALL — V51 reversed V12's whole point")
        void engineerDeletesAnybodys() {
            // Was OWN from V12 until V51 (§207): CRUD on plans, cases and steps belongs to every
            // non-Viewer contributor no matter who created the row. Through a TEAM grant, kept from
            // the original, so the value is still proven to survive the hop rather than being read
            // only off a direct assignment — the hop is what a qualifier could be lost on, and a
            // grant that arrives ALL because the team term was dropped would look identical here.
            // SeedDataTest.ownIsCarriedByNoGrant is the one that reads the seed; this reads the
            // evaluator.
            assertThat(qualifierIn(ACME, TEAM_ENGINEER, ACME_PROJECT, "case:delete"))
                    .isEqualTo(PermissionQualifier.ALL);
        }

        @Test
        @DisplayName("evidence:delete moved with it — the other half of V51")
        void engineerDeletesAnybodysEvidence() {
            assertThat(qualifierIn(ACME, TEAM_ENGINEER, ACME_PROJECT, "evidence:delete"))
                    .isEqualTo(PermissionQualifier.ALL);
        }

        @Test
        @DisplayName("a Test Engineer's case:EDIT is NOT qualified — the superseded reading, pinned")
        void editingIsProducingAndStaysUnqualified() {
            // The roles spec carried "case:edit is OWN-qualified" until 2026-07-31 and reversed it:
            // editing is producing, deleting is unmaking. Restoring the old line means a Test
            // Engineer cannot fix a wrong expected result in a colleague's case, in a product whose
            // whole subject is a SHARED repository. This assertion is what makes that a build
            // failure rather than a support ticket.
            assertThat(qualifierIn(ACME, TEAM_ENGINEER, ACME_PROJECT, "case:edit"))
                    .isEqualTo(PermissionQualifier.ALL);
        }

        @Test
        @DisplayName("strongest wins — a Company Admin who is also a project's Test Engineer keeps ALL")
        void anExtraWeakerGrantTakesNothingAway() {
            // The merge rule, and the failure it prevents: a role ADDED to a user must never
            // subtract capability, or granting someone a project role demotes them.
            assertThat(qualifierIn(ACME, DUAL_GRANT, ACME_PROJECT, "case:delete"))
                    .isEqualTo(PermissionQualifier.ALL);
        }

        @Test
        @DisplayName("not held at all is NONE, and NONE is not OWN")
        void aViewerlessMemberHoldsNothing() {
            // MEMBER_NO_GRANT holds no grant, so there is no qualifier to report. Answering OWN
            // here would let them delete anything they had somehow authored.
            assertThat(qualifierIn(ACME, MEMBER_NO_GRANT, ACME_PROJECT, "case:delete"))
                    .isEqualTo(PermissionQualifier.NONE);
        }

        @Test
        @DisplayName("every gate that zeroes the union zeroes the qualifier too")
        void theGatesApplyHereAsWell() {
            // Each is reachable without evaluate() ever being called, so each must be re-checked
            // rather than assumed to have been passed by an earlier call.
            assertThat(qualifierIn(ACME, DEACTIVATED_OPERATOR, ACME_PROJECT, "case:delete"))
                    .as("a deactivated account")
                    .isEqualTo(PermissionQualifier.NONE);
            assertThat(qualifierIn(ZENITH, ZENITH_LEAD, ZENITH_PROJECT, "case:delete"))
                    .as("a suspended company")
                    .isEqualTo(PermissionQualifier.NONE);
            assertThat(qualifierIn(ACME, INVITED_ONLY, ACME_PROJECT, "case:delete"))
                    .as("an unaccepted invitation")
                    .isEqualTo(PermissionQualifier.NONE);
            assertThat(qualifierIn(ACME, CROSS_COMPANY, GLOBEX_PROJECT, "case:delete"))
                    .as("a project outside the active company")
                    .isEqualTo(PermissionQualifier.NONE);
            assertThat(qualifierIn(ACME, LEAD, UUID.randomUUID(), "case:delete"))
                    .as("a project that does not exist")
                    .isEqualTo(PermissionQualifier.NONE);
        }

        @Test
        @DisplayName("an unknown key throws rather than answering NONE")
        void aTypoIsNotADenial() {
            // NONE from a typo reads as "this user is not allowed" and gets investigated against
            // the grants, where nothing is wrong. Same rule PermissionSet#has follows.
            assertThatThrownBy(() -> qualifierIn(ACME, LEAD, ACME_PROJECT, "case:delet"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("case:delet");
        }

        @Test
        @DisplayName("a null projectId is refused, like evaluate's")
        void thereIsNoCompanyWideQualifier() {
            assertThatThrownBy(() -> inCompany(ACME, () ->
                    evaluator.qualifierOf(LEAD, null, "case:delete")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // The collapse (§65) — one computation, two questions
    // =========================================================================

    @Nested
    @DisplayName("evaluate and qualifierOf are one traversal, and cannot answer differently")
    class OneComputation {

        /**
         * The property the collapse exists for, over every persona and every key at once.
         *
         * <p>Before §65 these were two methods resolving the same three terms, each with its own
         * copy of the replace-don't-union boundary, and <strong>nothing failed if one copy was
         * edited</strong>. It is true by construction now — {@code qualifierOf} reads
         * {@code evaluate}'s map — which is exactly why it is worth pinning: the day somebody
         * re-splits them for a performance argument, this is what says the two halves drifted.
         *
         * <p>Derived from the catalogue rather than from a list of keys, per the working
         * agreement's rule about catalogue-wide invariants: a key added next year is covered
         * without this test being edited.
         */
        @Test
        @DisplayName("every key a caller holds reports a qualifier, and every key they don't reports NONE")
        void theTwoQuestionsAgreeOnEveryKeyInTheCatalogue() {
            for (UUID persona : List.of(LEAD, TEAM_ENGINEER, DUAL_GRANT, INHERITOR, MEMBER_NO_GRANT)) {
                PermissionSet held = evaluateIn(ACME, persona, ACME_PROJECT);

                for (String key : catalogue.keys()) {
                    PermissionQualifier viaGate = qualifierIn(ACME, persona, ACME_PROJECT, key);

                    if (held.has(key)) {
                        assertThat(viaGate)
                                .as("%s holds %s, so the gate must name the rows it covers", persona, key)
                                .isNotEqualTo(PermissionQualifier.NONE);
                    } else {
                        assertThat(viaGate)
                                .as("%s does not hold %s, so the gate must refuse", persona, key)
                                .isEqualTo(PermissionQualifier.NONE);
                    }
                }
            }
        }

        @Test
        @DisplayName("a held permission is never qualified to NONE — that state cannot be built")
        void nothingIsHeldOverNoRows() {
            // The invariant the wire depends on: `permissions` carries one entry per held key with
            // a qualifier that means something, so "held, covering nothing" must be unrepresentable
            // rather than merely unproduced. A consumer reading presence as capability — which is
            // every consumer — would be wrong about exactly that entry.
            assertThatThrownBy(() -> PermissionSet.of(
                    catalogue, Map.of("case:delete", PermissionQualifier.NONE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ABSENT");
        }
    }

    // =========================================================================
    // Inheritance — a company-wide project role, and the project that overrides it
    // =========================================================================

    @Nested
    @DisplayName("Inheritance — a company-wide role applies everywhere the project has not spoken")
    class Inheritance {

        @Test
        @DisplayName("a company-wide Test Lead holds the role on a project with no grant of its own")
        void theCompanyWideRoleIsInherited() {
            // ACME_SECOND_PROJECT has no grant naming INHERITOR at all. Before inheritance existed
            // this was zero permissions and a 404 on every project-scoped path.
            assertThat(evaluateIn(ACME, INHERITOR, ACME_SECOND_PROJECT).has("plan:create")).isTrue();
            assertThat(evaluateIn(ACME, INHERITOR, ACME_SECOND_PROJECT).has("project:read")).isTrue();
        }

        @Test
        @DisplayName("a project grant REPLACES the inherited role, even when it confers less")
        void theProjectGrantOverridesDownward() {
            // THE assertion of this nest, and the only place in the evaluator where adding a row
            // REMOVES a permission. INHERITOR is a company-wide Test Lead and a Viewer on
            // ACME_PROJECT; a Viewer cannot create plans, so the inherited Test Lead must not leak
            // through. Implement the boundary as a union — the obvious reading, and what every
            // other term in this class does — and this is the only test that goes red.
            assertThat(evaluateIn(ACME, INHERITOR, ACME_PROJECT).has("plan:create")).isFalse();

            // Still a Viewer there, not nobody: the override replaces, it does not revoke.
            assertThat(evaluateIn(ACME, INHERITOR, ACME_PROJECT).has("project:read")).isTrue();
        }

        @Test
        @DisplayName("the override is per project — the inherited role still applies elsewhere")
        void theOverrideDoesNotLeakToOtherProjects() {
            // Guards the mirror mistake: scoping the override to the caller rather than to the
            // project would demote them everywhere, which reads as "inheritance is broken".
            assertThat(evaluateIn(ACME, INHERITOR, ACME_SECOND_PROJECT).has("plan:create")).isTrue();
            assertThat(evaluateIn(ACME, INHERITOR, ACME_PROJECT).has("plan:create")).isFalse();
        }

        @Test
        @DisplayName("a COMPANY-scope role is NEVER overridden by a project grant")
        void companyAdminSurvivesAProjectGrant() {
            // DUAL_GRANT is a Company Admin company-wide and a Test Engineer on ACME_PROJECT.
            // Without the exemption, anyone holding project:members could neutralise the company's
            // administrator on a project by granting them a weaker role — a privilege inversion,
            // and the same shape as §43/§45 where a mis-scoped row silently decided access.
            assertThat(evaluateIn(ACME, DUAL_GRANT, ACME_PROJECT).has("company:members")).isTrue();
            // And the project grant still adds what it confers, because COMPANY-scope keys are
            // unioned rather than replaced.
            assertThat(evaluateIn(ACME, DUAL_GRANT, ACME_PROJECT).has("case:create")).isTrue();
        }

        @Test
        @DisplayName("an inherited role confers no COMPANY-level authority")
        void inheritingEveryProjectIsNotCompanyAuthority() {
            // evaluateCompanyWide's own header states the rule this extends: leading every project
            // is not the same as administering the company, so a company-wide Test Lead must not
            // be able to create projects or change company settings.
            assertThat(evaluateCompanyWideIn(ACME, INHERITOR).has("project:create")).isFalse();
            assertThat(evaluateCompanyWideIn(ACME, INHERITOR).has("company:settings")).isFalse();
        }

        @Test
        @DisplayName("the qualifier follows the same override, not the stronger of the two")
        void theQualifierIsOverriddenToo() throws SQLException {
            /*
              The half that is easy to leave behind. QUALIFIED_INHERITOR is a company-wide Test
              Engineer and a Test Lead on ACME_PROJECT. Merging the terms would give ALL everywhere
              by strongest-wins; overriding gives ALL only where the project says so.

              THE QUALIFIER IS SET BY THIS TEST, and that is new. It used to come from the seed —
              artifact:edit was PLAN_SCOPE for Test Engineer (V19) — until V52 (§210) de-qualified
              it, after which NO seeded grant carries a qualifier and this rule became undrivable
              from personas. The alternative was to delete the test, and the rule it covers is one of
              the two the whole scope model rests on (RoleAssignmentRepository's header states it),
              so the fixture supplies what the catalogue no longer does.

              Restored in a finally, because `role_permission` is instance-wide catalogue data with
              no company_id: leaking PLAN_SCOPE out of this method would silently narrow a grant for
              every test class that runs after it in the same container.
            */
            qualify("artifact:edit", "PLAN_SCOPE");
            try {
                assertThat(qualifierIn(ACME, QUALIFIED_INHERITOR, ACME_PROJECT, "artifact:edit"))
                        .as("the project grant replaces the inherited one")
                        .isEqualTo(PermissionQualifier.ALL);
                assertThat(qualifierIn(ACME, QUALIFIED_INHERITOR, ACME_SECOND_PROJECT, "artifact:edit"))
                        .as("and the inherited one still applies where the project said nothing")
                        .isEqualTo(PermissionQualifier.PLAN_SCOPE);
            } finally {
                qualify("artifact:edit", "ALL");
            }
        }

        /** Sets Test Engineer's qualifier for one key. See the caller for why this is necessary. */
        private void qualify(String permissionKey, String qualifier) throws SQLException {
            try (Connection connection = migrationOwnerConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE role_permission SET qualifier = ?
                          WHERE role_id = ? AND permission_key = ?
                         """)) {
                statement.setString(1, qualifier);
                statement.setObject(2, Role.TEST_ENGINEER_ID);
                statement.setString(3, permissionKey);
                statement.executeUpdate();
            }
        }
    }

    // =========================================================================
    // The transaction-scoped memo
    // =========================================================================

    /**
     * Every other test in this class calls the evaluator outside any shared transaction, so each
     * call opens its own and the memo never spans two of them — which is why none of those tests
     * changed when the memo arrived. These are the ones that put two calls in ONE transaction,
     * where the memo is observable.
     */
    @Nested
    @DisplayName("The transaction-scoped memo")
    class TransactionScopedMemo {

        @Test
        @DisplayName("a repeated question in one transaction is answered with the same instance")
        void memoizesWithinOneTransaction() {
            inCompany(ACME, () -> tx.executeWithoutResult(status -> {
                PermissionSet first = evaluator.evaluate(LEAD, ACME_PROJECT);
                PermissionSet second = evaluator.evaluate(LEAD, ACME_PROJECT);

                assertThat(second)
                        .as("one transaction, one resolution — the memo's whole claim")
                        .isSameAs(first);
            }));
        }

        @Test
        @DisplayName("two questions differing only in scope do not collide in the memo")
        void projectAndCompanyQuestionsStayDistinct() {
            inCompany(ACME, () -> tx.executeWithoutResult(status -> {
                PermissionSet onProject = evaluator.evaluate(LEAD, ACME_PROJECT);
                PermissionSet companyWide = evaluator.evaluateCompanyWide(LEAD);

                // A Test Lead's project grant must not leak into the company-level answer via a
                // shared memo entry — the same boundary evaluateCompanyWide's header draws,
                // asserted against the cache key rather than the resolution.
                assertThat(onProject.has("project:settings")).isTrue();
                assertThat(companyWide.has("project:settings")).isFalse();
            }));
        }

        @Test
        @DisplayName("the memo dies with its transaction, so a grant change lands on the next one")
        void doesNotOutliveTheTransaction() {
            PermissionSet before = evaluateIn(ACME, LEAD, ACME_PROJECT);
            assertThat(before.has("case:edit")).isTrue();

            inCompany(ACME, () -> tx.executeWithoutResult(status ->
                    roleAssignments.findById(assignmentId("a1"))
                            .ifPresent(roleAssignments::delete)));

            // The @BeforeEach seed's findById guard restores the grant for the next test.
            assertThat(evaluateIn(ACME, LEAD, ACME_PROJECT).isEmpty())
                    .as("a revoke is effective on the very next transaction — the property "
                            + "RoleGrantService's no-session-revocation decision rests on")
                    .isTrue();
        }

        @Test
        @DisplayName("a grant mutation inside the transaction needs forgetMemoizedAnswers")
        void mutationInsideTheTransactionNeedsTheForgetSeam() {
            inCompany(ACME, () -> tx.executeWithoutResult(status -> {
                PermissionSet before = evaluator.evaluate(LEAD, ACME_PROJECT);
                assertThat(before.has("case:edit")).isTrue();

                roleAssignments.findById(assignmentId("a1")).ifPresent(roleAssignments::delete);
                roleAssignments.flush();

                assertThat(evaluator.evaluate(LEAD, ACME_PROJECT))
                        .as("pinned, not merely tolerated: the transaction reads its pre-write "
                                + "answer until the seam is called, which is exactly why "
                                + "RoleGrantService calls it before the self-lockout check")
                        .isSameAs(before);

                evaluator.forgetMemoizedAnswers();
                assertThat(evaluator.evaluate(LEAD, ACME_PROJECT).isEmpty())
                        .as("after the seam, the same transaction sees its own write")
                        .isTrue();

                status.setRollbackOnly();
            }));
        }
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private PermissionQualifier qualifierIn(
            UUID companyId, UUID userId, UUID projectId, String permissionKey) {
        return inCompany(companyId, () -> evaluator.qualifierOf(userId, projectId, permissionKey));
    }

    private PermissionSet evaluateIn(UUID companyId, UUID userId, UUID projectId) {
        return inCompany(companyId, () -> evaluator.evaluate(userId, projectId));
    }

    private PermissionSet evaluateCompanyWideIn(UUID companyId, UUID userId) {
        return inCompany(companyId, () -> evaluator.evaluateCompanyWide(userId));
    }

    private boolean memberOf(UUID companyId, UUID userId) {
        return inCompany(companyId, () -> tx.execute(status ->
                memberships.existsByCompanyIdAndUserIdAndStatus(
                        companyId, userId, MembershipStatus.ACTIVE)));
    }

    private static <T> T inCompany(UUID companyId, Supplier<T> action) {
        try (var ignored = CompanyContext.open(companyId)) {
            return action.get();
        }
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private void provisionCompany(UUID id, String slug, String name, boolean suspended, Instant now) {
        if (companies.findById(id).isEmpty()) {
            Company company = Company.provision(id, slug, name, now);
            if (suspended) {
                company.suspend();
            }
            companies.save(company);
        }
    }

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    private void grantInstanceAdmin(UUID userId, Instant now) {
        if (instanceRoleAssignments.findByUserId(userId).isEmpty()) {
            instanceRoleAssignments.save(InstanceRoleAssignment.forUser(
                    UUID.randomUUID(), Role.INSTANCE_ADMIN_ID, userId, null, now));
        }
    }

    private void seedProject(UUID id, UUID companyId, String marker, Instant now) {
        // Same key and name in both companies — see the class comment.
        seedProjectWithKey(id, companyId, "OLB", "Online Banking", marker, now);
    }

    private void seedProjectWithKey(
            UUID id, UUID companyId, String key, String name, String marker, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, marker, now));
        }
    }

    private void member(UUID companyId, UUID userId, String displayName, boolean accepted, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            if (accepted) {
                membership.accept(now);
            }
            memberships.save(membership);
        }
    }

    private void joinTeam(UUID companyId, UUID teamId, UUID userId, Instant now) {
        if (teamMemberships.findById(new TeamMembershipId(teamId, userId)).isEmpty()) {
            teamMemberships.save(TeamMembership.join(companyId, teamId, userId, false, now));
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

    private void grantToTeamOnProject(
            String marker, UUID companyId, UUID roleId, UUID teamId, UUID projectId, Instant now) {
        UUID id = assignmentId(marker);
        if (roleAssignments.findById(id).isEmpty()) {
            roleAssignments.save(RoleAssignment.forTeamOnProject(
                    id, companyId, roleId, teamId, projectId, null, now));
        }
    }

    /** Stable ids so the seed is idempotent across the class's many @BeforeEach runs. */
    private static UUID assignmentId(String marker) {
        return UUID.nameUUIDFromBytes(("role-assignment:" + marker).getBytes());
    }
}
