package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The V3 seed, checked against the two other places the same catalogue lives.
 *
 * <p>The permission catalogue is transcribed three times by necessity — the spec
 * ({@code docs/specs/2026-07-30-tcms-roles-permissions-naming.md}, authoritative), the
 * REST contract ({@code docs/specs/openapi.yaml}, which the frontend generates types
 * from), and {@code V3__seed_permissions_and_roles.sql}, which is what the running
 * system actually enforces. Three transcriptions drift, and a drifted <em>permission</em>
 * list is worse than a drifted label: it decides what an admin screen claims a role can
 * do, and it decides what the evaluator lets that role actually do. Only one of those two
 * is the truth.
 *
 * <p>So this class does not re-transcribe the grants. It <strong>compares</strong> the
 * seed to the contract mechanically, and asserts the boundary properties the spec states
 * in prose — the project's own lesson that a catalogue-wide invariant needs a computed
 * check rather than a careful reading. The frontend holds the third corner with
 * {@code satisfies Record<PermissionKey, Area>} in {@code lib/permissions.ts}, which is a
 * compile error on drift.
 */
@DisplayName("Seed reference data — the permission catalogue and the six built-in roles (V3)")
class SeedDataTest extends IntegrationTestBase {

    private static final String INSTANCE_ADMIN = "Instance Admin";
    private static final String COMPANY_ADMIN = "Company Admin";
    private static final String TEST_LEAD = "Test Lead";
    private static final String TEST_ENGINEER = "Test Engineer";
    private static final String AUTOMATION_SERVICE = "Automation Service";
    private static final String VIEWER = "Viewer";

    /** V15's non-assignable baseline — company:read for every member, at COMPANY scope. */
    private static final String COMPANY_MEMBER = "Company Member";

    private static Connection connection;

    /**
     * {@code @BeforeEach}, deliberately not {@code @BeforeAll}: the Spring context — and
     * therefore Flyway, and therefore the {@code tcms_app} role its test-only callback
     * creates — is built when the test instance is post-processed, which is <em>after</em>
     * a static {@code @BeforeAll} has already run. Connecting there fails with
     * "password authentication failed for user tcms_app", which reads like a credentials
     * problem rather than an ordering one.
     */
    @BeforeEach
    void connect() throws SQLException {
        // The app role, not the owner: permission/role/role_permission are instance-level
        // and carry no RLS, and reading them with NO company context is the login path.
        // If a later migration ever puts a policy on them, this class fails rather than
        // the first login attempt in production.
        if (connection == null) {
            connection = appConnection();
        }
    }

    @AfterAll
    static void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Test
    @DisplayName("the seeded catalogue matches openapi.yaml PermissionKey exactly, both ways")
    void catalogueMatchesTheContract() throws SQLException, IOException {
        Set<String> seeded = new TreeSet<>(queryStrings("SELECT key FROM permission"));
        Set<String> contract = new TreeSet<>(permissionKeysFromContract());

        Set<String> onlyInSeed = new TreeSet<>(seeded);
        onlyInSeed.removeAll(contract);
        Set<String> onlyInContract = new TreeSet<>(contract);
        onlyInContract.removeAll(seeded);

        assertThat(onlyInSeed)
                .as("""
                        Permissions seeded into the database that openapi.yaml does not declare. The \
                        frontend cannot render or check these at all, because PermissionKey is a \
                        closed enum — add them to the contract and regenerate, or drop them from the \
                        seed with a corrective migration.""")
                .isEmpty();
        assertThat(onlyInContract)
                .as("""
                        Permissions the contract declares that nothing seeds. Every role screen will \
                        show them as universally ungranted and no grant can ever be made — add them \
                        to a corrective migration together with the roles that should hold them.""")
                .isEmpty();
        assertThat(seeded).as("the v1 catalogue is 55 keys (spec Part A3)").hasSize(55);
    }

    @Test
    @DisplayName("exactly six assignable built-in roles, plus the non-assignable member baseline")
    void sixBuiltInRoles() throws SQLException {
        List<String> rows = queryStrings(
                "SELECT name || ' / ' || scope FROM role WHERE built_in ORDER BY name");

        assertThat(rows)
                .as("""
                        No custom roles in v1 — the six assignable ones are the complete set (spec \
                        A2), and Company Member is the seventh row V15 added: the company:read \
                        baseline every member is granted on joining. It is deliberately NOT one of \
                        the six — it is never offered, never granted by hand and never revoked, so \
                        an addition to this list is a new tier and needs a spec decision.""")
                .containsExactlyInAnyOrder(
                        INSTANCE_ADMIN + " / SYSTEM",
                        COMPANY_ADMIN + " / COMPANY",
                        TEST_LEAD + " / PROJECT",
                        TEST_ENGINEER + " / PROJECT",
                        AUTOMATION_SERVICE + " / PROJECT",
                        VIEWER + " / PROJECT",
                        COMPANY_MEMBER + " / COMPANY");
        assertThat(queryStrings("SELECT name FROM role WHERE NOT built_in"))
                .as("nothing may seed a non-built-in role while custom roles do not exist")
                .isEmpty();
    }

    @Test
    @DisplayName("the member baseline is exactly company:read — it is held by everybody")
    void theMemberBaselineIsOnlyCompanyRead() throws SQLException {
        // Enumerated rather than described, for the same reason Automation Service is: every human
        // in every company holds this grant, so anything added here is granted to all of them in
        // one edit — including, if the edit were careless, a project permission at COMPANY scope,
        // which would hand every member every project and undo roles spec §A1 outright.
        assertThat(grantsOf(COMPANY_MEMBER)).containsExactly("company:read");

        assertThat(queryStrings("""
                SELECT scope FROM role WHERE name = 'Company Member'
                """))
                .as("""
                        COMPANY scope is the whole point: PermissionEvaluator.evaluateCompanyWide \
                        reads the SYSTEM and COMPANY terms only, so a PROJECT-scope baseline would \
                        be exactly as unreachable as the five per-role grants it repairs.""")
                .containsExactly("COMPANY");
    }

    @Test
    @DisplayName("Instance Admin holds the whole catalogue — derived, with no wildcard row")
    void instanceAdminHoldsEveryPermission() throws SQLException {
        List<String> missing = queryStrings("""
                SELECT p.key FROM permission p
                 WHERE NOT EXISTS (
                    SELECT 1 FROM role_permission rp
                      JOIN role r ON r.id = rp.role_id
                     WHERE r.name = 'Instance Admin' AND rp.permission_key = p.key)
                 ORDER BY p.key
                """);

        assertThat(missing)
                .as("""
                        Permissions Instance Admin does not hold. V3 derives this grant by joining \
                        the whole catalogue, so a gap here means a later migration added a \
                        permission without extending the grant. It must fail here rather than \
                        rendering as full coverage on the roles screen — which is precisely what a \
                        '*' sentinel would have done, and why there is no such row.""")
                .isEmpty();

        assertThat(queryStrings("SELECT key FROM permission WHERE key LIKE '%*%'"))
                .as("there is no wildcard permission in this model (plan §6b)")
                .isEmpty();
    }

    @Test
    @DisplayName("Test Engineer → Test Lead: the three boundary lines from spec A4")
    void testEngineerToTestLeadBoundary() throws SQLException {
        Set<String> engineer = grantsOf(TEST_ENGINEER);
        Set<String> lead = grantsOf(TEST_LEAD);

        // 1. Creative vs destructive — never blocked from producing, only from unmaking
        //    someone else's work.
        assertThat(engineer)
                .as("the destructive four are Lead-only")
                .doesNotContain("suite:delete", "plan:delete", "run:close", "result:amend");
        assertThat(engineer)
                .as("but every creative permission is held")
                .contains("suite:manage", "case:create", "case:edit", "plan:create", "run:execute");

        // 2. Review vs approve.
        assertThat(engineer).contains("case:review", "suite:review", "plan:review");
        assertThat(engineer.stream().filter(key -> key.endsWith(":approve")).toList())
                .as("only Lead+ signs off")
                .isEmpty();
        assertThat(lead).contains("case:approve", "suite:approve", "plan:approve");

        // 3. Content vs configuration.
        assertThat(engineer)
                .as("project configuration and the audit trail are Lead-only")
                .doesNotContain(
                        "project:settings", "project:members", "project:audit", "integration:manage");

        assertThat(lead)
                .as("""
                        Test Lead must be a strict superset of Test Engineer. A permission the \
                        contributor holds and the lead does not is always a transcription slip — the \
                        tiers differ by what is ADDED, never by what is taken away.""")
                .containsAll(engineer)
                .hasSizeGreaterThan(engineer.size());
    }

    @Test
    @DisplayName("Company Admin never crosses the client boundary")
    void companyAdminHoldsNoSystemPermission() throws SQLException {
        List<String> systemGrants = queryStrings("""
                SELECT rp.permission_key
                  FROM role_permission rp
                  JOIN role r ON r.id = rp.role_id
                  JOIN permission p ON p.key = rp.permission_key
                 WHERE r.name = 'Company Admin' AND p.area = 'system'
                 ORDER BY rp.permission_key
                """);

        assertThat(systemGrants)
                .as("""
                        SYSTEM-area permissions granted to Company Admin. Company lifecycle, the \
                        cross-company user directory, the role catalogue and instance settings are \
                        the operator's alone — a Company Admin holding any of them can see or act on \
                        another client (spec A4).""")
                .isEmpty();

        assertThat(grantsOf(COMPANY_ADMIN))
                .as("Company Admin is Test Lead plus company configuration, so it contains it whole")
                .containsAll(grantsOf(TEST_LEAD))
                .contains("company:settings", "company:members", "company:teams",
                        "company:vocabulary", "company:idp", "project:create", "token:issue",
                        "audit:view");
    }

    @Test
    @DisplayName("no PROJECT-scope role is granted a permission it cannot reach")
    void everyProjectScopeRoleHoldsTheVisibilityPrecondition() throws SQLException {
        // THE COMPUTED CHECK, not a reading. Derived from the catalogue, so a role added or a
        // grant edited a year from now is covered without anyone remembering this rule.
        //
        // `project:read` is not one capability among the others: it is the permission
        // ProjectAccess.canView asks, and every project-scoped endpoint asks canView first so
        // that "another company's project" and "a project you hold no grant on" are the same
        // 404 (§6t). A PROJECT-scope role without it does not lose the project list — it 404s
        // on every project-scoped path, so EVERY permission it holds is unreachable and the
        // role is inert over HTTP while looking correct in the seed, in the spec and on the
        // roles screen.
        //
        // That is exactly how Automation Service shipped from V3 until V16: five permissions,
        // none of them reachable, discovered only when handoff §42 wrote the first test that
        // asked as that role. Reading V3 did not find it and could not have — the row is
        // absent, and absence is what a reading is worst at. Same family as the company:read
        // gap (§43): a permission the spec grants, made unreachable by a gate ordering nobody
        // checked against the catalogue. Two in one session is what makes this a script.
        assertThat(queryStrings("""
                SELECT r.name
                  FROM role r
                 WHERE r.scope = 'PROJECT'
                   AND EXISTS (SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id)
                   AND NOT EXISTS (SELECT 1 FROM role_permission rp
                                    WHERE rp.role_id = r.id AND rp.permission_key = 'project:read')
                 ORDER BY r.name
                """))
                .as("""
                        PROJECT-scope roles holding permissions but not project:read. Every path \
                        these roles can reach asks canView first, so each of these roles is inert \
                        over HTTP: not degraded, not partially working — 404 on everything. Grant \
                        project:read in a migration; do NOT repair it by weakening what canView \
                        asks, which would change the meaning of the one predicate two hundred call \
                        sites depend on.""")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing may approve a case it may not edit — the bulk gate asks only the stronger key")
    void everyApproverIsAlsoAnEditor() throws SQLException {
        // THE COMPUTED CHECK behind a simplification in BulkOperations#permission, which resolves
        // ONE permission per operation: case:approve when the target status is APPROVED, case:edit
        // otherwise. Asking only the stronger key is correct precisely while every role holding
        // approve also holds edit — and that is a property of the seeded catalogue, not a law.
        //
        // If a role were ever granted case:approve WITHOUT case:edit, that role could move cases to
        // APPROVED through the bulk endpoint and the gate would never ask whether it may write a
        // case at all. The failure is a widening, not an outage: the role gets MORE than intended
        // and nothing errors, which is the §45/§61 shape both of those sections were about.
        //
        // The repair is to grant the missing case:edit, or to make the gate ask both keys — never
        // to delete this check. It is written over the whole catalogue rather than over the six
        // roles by name so a seventh role added in a later migration is covered without anyone
        // remembering that this reasoning exists.
        assertThat(queryStrings("""
                SELECT r.name
                  FROM role r
                 WHERE EXISTS (SELECT 1 FROM role_permission rp
                                WHERE rp.role_id = r.id AND rp.permission_key = 'case:approve')
                   AND NOT EXISTS (SELECT 1 FROM role_permission rp
                                    WHERE rp.role_id = r.id AND rp.permission_key = 'case:edit')
                 ORDER BY r.name
                """))
                .as("""
                        Roles that may approve a case but not edit one. CaseBulkService gates a \
                        status change on ONE key — case:approve for APPROVED, case:edit otherwise \
                        — which is only sound while approvers are a subset of editors. Grant \
                        case:edit to these roles, or make BulkOperations#permission require both \
                        keys; do not delete this check.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no permission is granted with two different restrictive qualifiers")
    void noPermissionCarriesTwoDifferentRestrictiveQualifiers() throws SQLException {
        // THE COMPUTED CHECK that keeps PermissionQualifier#strongest total, and the reason that
        // method throws rather than picking.
        //
        // "Strongest wins" is a real rule with real consequences: a caller holding a permission
        // through two grants gets the union of what each covers, because demoting them for holding
        // an ADDITIONAL role would make adding a role take capability away. It is expressible as a
        // single value only while at most one restrictive qualifier is in play per key. ALL absorbs
        // anything and NONE is the identity, so those merges are always answerable — but OWN and
        // PLAN_SCOPE restrict along DIFFERENT axes, one by who wrote the row and one by what scope
        // it sits at. Their union is "rows you wrote OR rows at plan scope", and PermissionQualifier
        // has no value that says it.
        //
        // Before V19 this was vacuous — one restrictive value cannot disagree with itself — which
        // is exactly why nobody had to think about it and exactly the shape §50 warns about: the
        // property held by accident, and the accident was invisible at the place the rule was
        // written (an ordinal comparison over an enum's declaration order).
        //
        // The repair for a violation here is never to relax the merge. It is to decide which single
        // restriction the key carries, because a key that means two different things to two roles
        // is a key that cannot be rendered on the permissions screen either.
        assertThat(queryStrings("""
                SELECT rp.permission_key || ' → ' || string_agg(DISTINCT rp.qualifier, ', ')
                  FROM role_permission rp
                 WHERE rp.qualifier <> 'ALL'
                 GROUP BY rp.permission_key
                HAVING COUNT(DISTINCT rp.qualifier) > 1
                 ORDER BY 1
                """))
                .as("""
                        Permission keys granted with two different restrictive qualifiers. \
                        PermissionQualifier#strongest cannot merge these — it throws rather than \
                        silently narrowing or widening a grant — so a caller holding both would get \
                        a 500 at the gate instead of an authorization answer. Decide on one \
                        restriction for the key; do NOT make the merge pick a winner, which would \
                        make an authorization decision depend on enum declaration order.""")
                .isEmpty();
    }

    @Test
    @DisplayName("Test Engineer edits a plan's artifacts and not the project's — the V19 grant")
    void testEngineerHoldsArtifactEditOnlyAtPlanScope() throws SQLException {
        // The roles spec's Artifacts table states this as `plan scope only`, and V12's two-value
        // CHECK could not express it. It is asserted as DATA rather than through the endpoint
        // because the endpoint's gate reads this row: a test that only drove HTTP would pass on a
        // day the grant said ALL and the gate had been written to match it.
        //
        // The direction matters. Test Engineer's grant was ALL until V19 — V12's backfill default,
        // correct for every row that existed then — so this does not fail closed. An artifact:edit
        // gate written against the old data lets a Test Engineer rewrite the test strategy that
        // every cycle inherits read-only (plan §6n), which is a silent widening, not an outage.
        assertThat(queryStrings("""
                SELECT r.name || ' / ' || rp.permission_key || ' = ' || rp.qualifier
                  FROM role_permission rp
                  JOIN role r ON r.id = rp.role_id
                 WHERE rp.permission_key = 'artifact:edit'
                 ORDER BY r.name
                """))
                .as("""
                        Every grant of artifact:edit. Test Engineer must be PLAN_SCOPE; the three \
                        roles that own the project's shared documents must be ALL. A role appearing \
                        here that the spec's Artifacts table does not grant is the defect — the \
                        spec is authoritative.""")
                .containsExactly(
                        "Company Admin / artifact:edit = ALL",
                        "Instance Admin / artifact:edit = ALL",
                        "Test Engineer / artifact:edit = PLAN_SCOPE",
                        "Test Lead / artifact:edit = ALL");
    }

    @Test
    @DisplayName("Automation Service is exactly the six permissions of spec A6")
    void automationServiceIsTheSmallestRole() throws SQLException {
        // A leaked CI token is the realistic breach, so this grant is enumerated rather
        // than described: any addition here widens what a stolen token can do, and must be
        // a deliberate edit to both the spec and this assertion.
        //
        // project:read joined the list in V16 and is the visibility precondition, not a sixth
        // capability — see that migration for why it does not widen §A6's argument about what a
        // stolen token can do. The five capabilities below are unchanged.
        assertThat(grantsOf(AUTOMATION_SERVICE))
                .containsExactlyInAnyOrder(
                        "case:read", "case:create", "run:create", "result:submit", "evidence:attach",
                        "project:read");

        assertThat(grantsOf(AUTOMATION_SERVICE))
                .as("""
                        A token that can read every report is a data-exfiltration path with no \
                        operational benefit, and one that can edit or delete real cases turns a leak \
                        into destruction.""")
                .doesNotContain("report:view", "report:export", "case:edit", "case:delete",
                        "run:close", "project:audit", "doc:read");
    }

    @Test
    @DisplayName("Viewer reads, comments and exports — and writes nothing")
    void viewerIsReadOnlyPlusCommentAndExport() throws SQLException {
        Set<String> viewer = grantsOf(VIEWER);

        assertThat(viewer)
                .as("built for the client reviewing their own coverage (spec A2, A3)")
                .contains("case:read", "case:comment", "report:view", "report:export",
                        "doc:read", "artifact:read", "project:read", "company:read");
        assertThat(viewer)
                .as("Viewer is the default at every entry point, so anything writable here is granted "
                        + "by accident to everyone who joins")
                .doesNotContain("case:create", "case:edit", "case:delete", "suite:manage",
                        "doc:edit", "run:execute", "result:submit", "workitem:manage",
                        "case:review", "case:approve");
    }

    @Test
    @DisplayName("company:read is an explicit grant on every member role, not implied by membership")
    void companyReadIsAlwaysExplicit() throws SQLException {
        for (String role : List.of(COMPANY_ADMIN, TEST_LEAD, TEST_ENGINEER, VIEWER)) {
            assertThat(grantsOf(role))
                    .as("%s must hold company:read explicitly — one uniform mechanism, so every "
                            + "check in the code is a real permission check (spec A3)", role)
                    .contains("company:read");
        }
        assertThat(grantsOf(AUTOMATION_SERVICE))
                .as("except the non-human role, which has no company surface at all")
                .doesNotContain("company:read");
    }

    @Test
    @DisplayName("every permission carries an area and a description")
    void catalogueRowsAreComplete() throws SQLException {
        assertThat(queryStrings("""
                SELECT key FROM permission
                 WHERE coalesce(area, '') = '' OR coalesce(description, '') = ''
                 ORDER BY key
                """))
                .as("""
                        Permissions with no area or no description. The area is what makes a 55-item \
                        list legible on the roles screen, and it must match lib/permissions.ts — an \
                        unareaed permission has nowhere to render.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the seed is idempotent — re-running V3 changes nothing")
    void seedIsIdempotent() throws SQLException {
        // Flyway will not re-run V3, but a repair, a squashed baseline or a hand-run of the
        // file during recovery all will. ON CONFLICT DO NOTHING is what makes that safe,
        // and this proves it rather than assuming it.
        long before = count("role_permission");
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO role_permission (role_id, permission_key)
                    SELECT '10000000-0000-4000-8000-000000000001', key FROM permission
                    ON CONFLICT DO NOTHING
                    """);
        }
        assertThat(count("role_permission")).isEqualTo(before);
    }

    private static Set<String> grantsOf(String roleName) throws SQLException {
        Set<String> grants = new LinkedHashSet<>();
        String sql = """
                SELECT rp.permission_key
                  FROM role_permission rp JOIN role r ON r.id = rp.role_id
                 WHERE r.name = ? ORDER BY rp.permission_key
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, roleName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    grants.add(rs.getString(1));
                }
            }
        }
        assertThat(grants).as("role '%s' must be seeded with at least one permission", roleName)
                .isNotEmpty();
        return grants;
    }

    private static List<String> queryStrings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static long count(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * The {@code PermissionKey} enum as written in {@code docs/specs/openapi.yaml}.
     *
     * <p>Parsed from the YAML rather than from the generated TypeScript, because the YAML
     * is the contract and the {@code .d.ts} is one of its outputs. Reaching outside
     * {@code backend/} is deliberate: the contract is a repo-level artifact shared by both
     * stacks, and this comparison is the only thing on the backend side that can make
     * contract drift a build failure.
     */
    private static List<String> permissionKeysFromContract() throws IOException {
        Path contract = locateContract();
        String yaml = Files.readString(contract);

        int start = yaml.indexOf("    PermissionKey:");
        assertThat(start).as("openapi.yaml no longer declares a PermissionKey schema").isNotNegative();
        int enumStart = yaml.indexOf("enum:", start);
        // The next schema at the same indentation ends the block.
        int end = yaml.indexOf("\n    RoleAssignment:", enumStart);
        assertThat(end)
                .as("PermissionKey is no longer followed by RoleAssignment — fix this parse rather "
                        + "than deleting the comparison")
                .isNotNegative();

        List<String> keys = new ArrayList<>();
        Matcher matcher = Pattern.compile("- '([^']+)'").matcher(yaml.substring(enumStart, end));
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        assertThat(keys).as("parsed no permission keys out of openapi.yaml").isNotEmpty();
        return keys;
    }

    private static Path locateContract() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("docs/specs/openapi.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "docs/specs/openapi.yaml not found above " + Path.of("").toAbsolutePath()
                        + " — the backend and the contract must be checked out together, since the "
                        + "SPA is served from the boot jar and both sides share one catalogue.");
    }
}
