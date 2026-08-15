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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 *
 * <p>~~The boundary properties are how the spec's own tables are held to~~ →
 * <del>they were not, and two documents said otherwise for four days</del>.
 * {@link #theSeededMatrixIsTheSpecsMatrix} (§214) is the comparison the property assertions had
 * been standing in for: every cell of §A3, seven roles, both directions. Before it, a property
 * test pinned the one delta somebody was arguing about and the other 385 cells were compared to
 * nothing — while {@code packages/client/src/test/roleMatrix.ts} parsed that same document to
 * decide what prose in four corpora may claim. The property assertions are kept and are not
 * redundant: an equality says two documents differ, and they say <em>why</em> a cell is what it
 * is.
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

    /** V40's non-assignable ceiling — carried by API tokens only, never held by a person. */
    private static final String AGENT = "Agent";

    /** The roles spec, whose §A3 decides the whole matrix. */
    private static final String ROLES_SPEC =
            "docs/specs/2026-07-30-tcms-roles-permissions-naming.md";

    /**
     * §A3's column headings, as the role names the {@code role} table holds.
     *
     * <p>Three tables with three different shapes: the eight {@code PROJECT} tables carry the six
     * abbreviations, the {@code COMPANY} table carries {@code Every member} — which is V15's
     * {@code Company Member} baseline and not a seventh tier — and the {@code SYSTEM} table carries
     * {@code IA} alone. A heading outside this map fails the parse rather than being skipped: a
     * column nobody compares is the shape this whole test was written against.
     *
     * <p>{@code Agent} is deliberately absent, because §A3 does not have a column for it. It is
     * §A6a's derived role and {@link #agentIsTestEngineerMinusNothingItCouldUse} is what pins it.
     */
    private static final Map<String, String> SPEC_COLUMNS = Map.of(
            "IA", INSTANCE_ADMIN,
            "CA", COMPANY_ADMIN,
            "TL", TEST_LEAD,
            "TE", TEST_ENGINEER,
            "AS", AUTOMATION_SERVICE,
            "V", VIEWER,
            "Every member", COMPANY_MEMBER);

    /** A `key` in the first cell of an §A3 row, of which there may be several. */
    private static final Pattern SPEC_KEY = Pattern.compile("`([a-z]+:[a-z]+)`");

    private static final String COMPANY_READ_IS_SEEDED_PER_ROLE = """
            V3 seeds company:read onto five of the six built-in roles and §A3's COMPANY table has \
            no column that can say so — it says `Every member`, which is V15's baseline role. The \
            paragraph under that table records both halves: "The per-role grants above are \
            unchanged and still correct; what carries the permission company-wide is the Company \
            Member baseline". The row is INERT rather than wrong — a PROJECT-scope grant of a \
            COMPANY-scope key is unreachable from evaluateCompanyWide — and companyReadIsAlwaysExplicit \
            is what pins it as deliberate. Removing it is a migration and a spec edit, not a \
            correction here.""";

    /**
     * Cells where the seeded grant and §A3's table differ on purpose, keyed {@code role / key}.
     *
     * <p>Pinned in BOTH directions by {@link #theSeededMatrixIsTheSpecsMatrix}: an entry that stops
     * diverging fails the build the same way an unexplained divergence does. §162's rule — a
     * one-directional ignore list cannot report a member that stopped needing to be on it — and the
     * reason {@code ArchitectureRulesTest}'s and {@code ContractEnumTest}'s both-ways maps were the
     * populations that held when it swept them.
     *
     * <p>All three members are the same fact, and the spec states it in prose immediately under the
     * table that cannot express it.
     */
    private static final Map<String, String> MATRIX_DIVERGENCE_BY_DECISION = Map.of(
            TEST_LEAD + " / company:read", COMPANY_READ_IS_SEEDED_PER_ROLE,
            TEST_ENGINEER + " / company:read", COMPANY_READ_IS_SEEDED_PER_ROLE,
            VIEWER + " / company:read", COMPANY_READ_IS_SEEDED_PER_ROLE);

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
    @DisplayName("permission.area matches lib/permissions.ts exactly — the claim two headers made")
    void areasMatchTheSpa() throws SQLException, IOException {
        // V3's header and Permission's class javadoc have both said, since M0, that `area` "matches
        // lib/permissions.ts exactly, because both sides group a 55-item list for humans and two
        // different groupings would be two different products". Nothing checked it, and §188 is why
        // that matters more than it looks: `permission.area` is READERLESS — no endpoint serves it,
        // no query selects it, PermissionRepository declares no method. Its whole justification for
        // existing is a claim about a file in another stack.
        //
        // The three checks around it do NOT cover this. catalogueMatchesTheContract compares the
        // KEYS to openapi.yaml, and the SPA's own `satisfies Record<PermissionKey, Area>` holds its
        // keys to the same contract at compile time — so both ends are pinned to the same 55 keys
        // and NEITHER looks at the value. A permission moved from 'repository' to 'project' on one
        // side alone typechecks, seeds, and passes every one of them.
        //
        // Reading a file outside backend/ is deliberate and it is the same argument
        // permissionKeysFromContract makes for reading the YAML: the catalogue is a repo-level
        // artifact, and this is the only place on either side that can make the divergence a build
        // failure. IT ALSO NEEDS A LINE IN build.gradle.kts — §185's lesson, and the reason that
        // file already lists ../docs/specs: Gradle tracks a Test task's classpath and resources, so
        // without the declaration an edit to permissions.ts leaves :test UP-TO-DATE and this rule
        // is green by cache, which is worse than not having it.
        Map<String, String> seeded = new TreeMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT key, area FROM permission")) {
            while (rs.next()) {
                seeded.put(rs.getString(1), rs.getString(2));
            }
        }

        assertThat(seeded)
                .as("""
                        The seeded permission areas and the SPA's permissionAreas disagree. Both \
                        sides group the same 55 keys for humans and two different groupings are two \
                        different products — V3's header and Permission's javadoc have claimed they \
                        match since M0, and this is the first thing to check it. Nothing in the \
                        backend READS permission.area (ArchitectureRulesTest.READERLESS_BY_DECISION), \
                        so agreeing with lib/permissions.ts is the entire reason the column is \
                        maintained: decide which grouping is right, then fix the other side — the \
                        seed with a corrective migration, or the .ts file.""")
                .isEqualTo(permissionAreasFromTheSpa());
    }

    @Test
    @DisplayName("every cell of spec §A3 is the grant the database holds — seven roles, both ways")
    void theSeededMatrixIsTheSpecsMatrix() throws SQLException, IOException {
        // The link everything else in this repository assumes exists.
        //
        // Two headers describe a chain — `migration -> SeedDataTest -> spec -> the packages rules`
        // (roleMatrix.ts, and handoff §213) — and until this test the middle arrow was four
        // assertions about PROPERTIES, not a comparison of the table. testEngineerToTestLeadBoundary
        // pins one delta of one pair; automationServiceIsTheSmallestRole and
        // viewerIsReadOnlyPlusCommentAndExport pin two hand-written lists that name the spec in
        // their message and never read it; instanceAdminHoldsEveryPermission derives from the
        // catalogue. Everything else — 385 cells over seven roles — was compared to nothing. A
        // migration that granted Company Admin one key the spec withholds, or a spec edit that
        // quietly widened Viewer, passed every test in this class, and then BOTH `packages/` rules
        // went on answering questions about the catalogue out of the widened document.
        //
        // So this is the comparison the surrounding assertions have been standing in for. It does
        // not replace them: a property test says WHY a cell is what it is and fails with the
        // argument attached, which is worth more than an equality when the cell is the tier line.
        // What it adds is the cells nobody thought to name.
        //
        // The population, measured before the test was written rather than after it ran green:
        // three cells, all one documented fact, all in MATRIX_DIVERGENCE_BY_DECISION. So this is a
        // guard in §201's and §204's sense — a way the two could disagree is removed, rather than a
        // disagreement repaired — and saying so plainly is the point of measuring first.
        Map<String, String> unexplained = new TreeMap<>();
        Set<String> explained = new TreeSet<>();

        for (Map.Entry<String, Set<String>> row : matrixFromTheSpec().entrySet()) {
            String role = row.getKey();
            Set<String> declared = row.getValue();
            Set<String> seeded = grantsOf(role);

            Set<String> extra = new TreeSet<>(seeded);
            extra.removeAll(declared);
            Set<String> missing = new TreeSet<>(declared);
            missing.removeAll(seeded);

            for (String key : extra) {
                record(unexplained, explained, role, key,
                        "the database grants it and §A3's row for it says —");
            }
            for (String key : missing) {
                record(unexplained, explained, role, key,
                        "§A3 grants it and no migration has ever seeded it");
            }
        }

        assertThat(unexplained)
                .as("""
                        Cells where the seeded catalogue and roles spec §A3 disagree. The SPEC IS \
                        AUTHORITATIVE (CLAUDE.md's precedence table, and the spec's own header), so \
                        the repair is normally a corrective migration — but decide which side is \
                        wrong before writing one, because the other direction is a document that \
                        two `packages/` rules parse to decide what prose may claim about a role. A \
                        divergence that is deliberate goes in MATRIX_DIVERGENCE_BY_DECISION with \
                        the argument for it, not into this assertion.""")
                .isEmpty();

        assertThat(explained)
                .as("""
                        MATRIX_DIVERGENCE_BY_DECISION entries that no longer describe a divergence. \
                        The seed and the spec now agree at these cells, so the argument for the \
                        exception has outlived the exception — delete the entry. §162: a \
                        one-directional ignore list cannot report a member that stopped needing to \
                        be on it, which is why this half exists at all.""")
                .containsExactlyInAnyOrderElementsOf(MATRIX_DIVERGENCE_BY_DECISION.keySet());
    }

    private static void record(Map<String, String> unexplained, Set<String> explained,
            String role, String key, String how) {
        String cell = role + " / " + key;
        if (MATRIX_DIVERGENCE_BY_DECISION.containsKey(cell)) {
            explained.add(cell);
            return;
        }
        unexplained.put(cell, how);
    }

    @Test
    @DisplayName("exactly six built-in roles, plus two rows that are not among them")
    void sixBuiltInRoles() throws SQLException {
        List<String> rows = queryStrings(
                "SELECT name || ' / ' || scope FROM role WHERE built_in ORDER BY name");

        assertThat(rows)
                .as("""
                        No custom roles in v1 — these six are the complete role set (spec A2; \
                        five of them are assignable to a person since V41 made Automation \
                        Service a credential-only ceiling), and two further rows exist that \
                        nobody ever chooses for a person: \
                        Company Member (V15), the company:read baseline granted on joining, and \
                        Agent (V40), the ceiling an API token carries. An addition to this list is \
                        a new tier and needs a spec decision — which is what this assertion is for, \
                        and it is why it had to be edited deliberately rather than adjusted after \
                        a red run.""")
                .containsExactlyInAnyOrder(
                        INSTANCE_ADMIN + " / SYSTEM",
                        COMPANY_ADMIN + " / COMPANY",
                        TEST_LEAD + " / PROJECT",
                        TEST_ENGINEER + " / PROJECT",
                        AUTOMATION_SERVICE + " / PROJECT",
                        VIEWER + " / PROJECT",
                        COMPANY_MEMBER + " / COMPANY",
                        AGENT + " / PROJECT");
        assertThat(queryStrings("SELECT name FROM role WHERE NOT built_in"))
                .as("nothing may seed a non-built-in role while custom roles do not exist")
                .isEmpty();
    }

    @Test
    @DisplayName("exactly three roles are non-assignable, and one of them IS one of the six")
    void nonAssignableRolesAreExactlyTheThree() throws SQLException {
        // The flag V40 added, asserted as data rather than trusted from the migration that wrote
        // it. It is read by RoleGrantService's grant path, so a row that flipped to assignable
        // would not fail anything — it would simply let an administrator grant it, which is the
        // outcome with no error attached.
        assertThat(queryStrings("SELECT name FROM role WHERE NOT assignable ORDER BY name"))
                .as("""
                        Company Member is a consequence of membership; Agent and Automation \
                        Service are capability ceilings a CREDENTIAL carries, and nobody is ever \
                        either of them. Automation Service (V41) is the one that is also one of \
                        the six, which is why spec §A2 no longer says "the six are the complete \
                        set of assignable roles" — five of them are. Any OTHER role appearing \
                        here has been made ungrantable by a migration that did not say so.""")
                .containsExactly(AGENT, AUTOMATION_SERVICE, COMPANY_MEMBER);
    }

    @Test
    @DisplayName("the five a person may hold are the five, and Instance Admin is among them")
    void theAssignableRolesAreTheFive() throws SQLException {
        // The other direction, and it is not the complement restated: Instance Admin is
        // assignable = true and is STILL refused by POST /role-assignments, for a scope-shaped
        // reason (SYSTEM grants live in instance_role_assignment, ERD §0.1). Conflating the two
        // would put "choose a role" on a refusal whose real message names where those grants live,
        // so this assertion pins that Instance Admin is NOT quietly swept in with the ceilings.
        assertThat(queryStrings("SELECT name FROM role WHERE assignable ORDER BY name"))
                .containsExactly(
                        COMPANY_ADMIN, INSTANCE_ADMIN, TEST_ENGINEER, TEST_LEAD, VIEWER);
    }

    @Test
    @DisplayName("Agent is Test Engineer minus one unreachable key — nothing is withheld (V52)")
    void agentIsTestEngineerMinusNothingItCouldUse() throws SQLException {
        // STILL a delta check, and the delta is now empty by decision rather than by five stated
        // exclusions. V52 (§210) settled that an agent is the same as an engineer, with delete
        // operations gated by USER CONSENT IN THE MCP TOOL DEFINITIONS instead of by the catalogue.
        //
        // Read the shape of this test before changing it. The obvious simplification — assert Agent
        // is a subset of Test Engineer, or drop the test now that nothing is withheld — is the one
        // that fails at the moment it matters. Equality is the invariant; a subset check would stay
        // green while a future migration widened Test Engineer and left Agent behind, which is a
        // ceiling that has silently stopped tracking the role it is defined as. V52 statement 3
        // derives Agent's grants FROM Test Engineer's precisely so the data cannot drift, and this
        // is the assertion that catches a later migration that enumerates instead.
        //
        // The history is worth keeping because the test's shape came out of it: V40 defined Agent as
        // "Test Engineer minus three", and computing the delta rather than checking a subset is what
        // caught V51 granting plan:delete to Test Engineer (§207) — an exclusion whose stated reason
        // had evaporated while the enumeration stayed green. Three became five, and now five becomes
        // zero.
        //
        // WHAT THIS TEST NO LONGER GUARDS, stated so its absence is not read as an oversight: an
        // agent credential can now delete every case, plan, suite, artifact and evidence row its
        // owner can. The consent prompt that replaces this ceiling lives in the MCP server, and the
        // `tcms_` bearer authenticates against /api/* without it — see V52's header and roles spec
        // §A6a for the objection, which was raised, recorded and overruled rather than missed.
        Set<String> agent = grantsOf(AGENT);
        Set<String> engineer = grantsOf(TEST_ENGINEER);

        Set<String> removed = new TreeSet<>(engineer);
        removed.removeAll(agent);
        assertThat(removed)
                .as("""
                        Permissions Test Engineer holds and Agent does not. Exactly one since V52, \
                        and it is a REACHABILITY exclusion rather than a ceiling: a PROJECT-scope \
                        grant of company:read is unanswerable by evaluateCompanyWide, and a token \
                        has no membership row and so no V15 baseline to hold it reachably the way a \
                        human contributor does (§43, and companyReadIsAlwaysExplicit, which is what \
                        caught this on V52's first run). Any OTHER key here means a migration \
                        granted the contributor something and did not carry it across — the role has \
                        stopped tracking what it is defined as, which reads on the roles screen as \
                        an agent that mysteriously cannot do its owner's job.""")
                .containsExactly("company:read");

        Set<String> added = new TreeSet<>(agent);
        added.removeAll(engineer);
        assertThat(added)
                .as("""
                        Permissions Agent holds and Test Engineer does not. This is the direction \
                        that must never have a row, before V52 and after it: an agent that can do \
                        something the contributor role cannot has stopped being a ceiling at all. \
                        The token intersection (plan §6p) still narrows to the ISSUER's own grant, \
                        so this would not by itself let an agent exceed its owner — but it would \
                        mean the role no longer describes what it claims to.""")
                .isEmpty();

        assertThat(agent)
                .as("""
                        The two things an agent still must not do, and they are the tier line rather \
                        than a ceiling of its own: approvals and project configuration are Test \
                        Lead's, so a contributor-shaped role cannot hold them. Asserted here as well \
                        as in testEngineerToTestLeadBoundary because THIS is the role a credential \
                        carries: if the tier line ever moves, the blast radius includes every \
                        outstanding agent token, and that should fail in the test named after the \
                        credential too.""")
                .doesNotContain("case:approve", "suite:approve", "plan:approve",
                        "project:settings", "project:members", "project:audit",
                        "integration:manage");
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
    @DisplayName("Test Engineer → Test Lead: exactly approvals and project configuration (V52)")
    void testEngineerToTestLeadBoundary() throws SQLException {
        Set<String> engineer = grantsOf(TEST_ENGINEER);
        Set<String> lead = grantsOf(TEST_LEAD);

        // THE WHOLE BOUNDARY, as a closed set rather than as three descriptions. V52 (§210) redrew
        // it: a Test Lead is a Test Engineer plus giving approvals and managing access to the
        // project, and nothing else.
        //
        // The exactness is the point, and it is what the previous shape could not do. This test used
        // to assert three separate lines with contains/doesNotContain, which pins the keys somebody
        // thought to name and says nothing about a key nobody did — so a migration granting the lead
        // a sixth kind of power passed. Both directions of an exact set are now closed: a key that
        // leaves the lead's exclusive list has been given to contributors, and a key that joins it
        // has been taken away from them. Either is a tier decision and needs roles spec §A4 edited
        // first.
        //
        // Line 1 of the old three is GONE, not relaxed. It was "creative vs destructive" (V3, V12),
        // then "content vs shared structure and judgement" (V51, eight days ago), and the
        // requirement behind V52 removes it outright: a contributor creates, edits and deletes. Two
        // attempts to state that line in six weeks is the argument for not attempting a third.
        Set<String> leadOnly = new TreeSet<>(lead);
        leadOnly.removeAll(engineer);
        assertThat(leadOnly)
                .as("""
                        Everything a Test Lead may do that a Test Engineer may not. EXACTLY seven \
                        keys, and they are the two things spec §A4 now names: giving approvals, and \
                        adding and managing permission on the project. A key appearing here is a \
                        capability taken away from contributors; a key disappearing is one handed to \
                        them. Neither is a migration side effect worth discovering later.""")
                .containsExactly(
                        "case:approve", "integration:manage", "plan:approve",
                        "project:audit", "project:members", "project:settings", "suite:approve");

        assertThat(lead)
                .as("""
                        Test Lead must be a strict superset of Test Engineer. A permission the \
                        contributor holds and the lead does not is always a transcription slip — the \
                        tiers differ by what is ADDED, never by what is taken away.""")
                .containsAll(engineer);

        // The positive half. The delta above is a subtraction, so it stays green if BOTH roles lose
        // a key — which is exactly what a careless revoke looks like. These name the grants V52
        // moved down, so reverting one fails here rather than silently rebalancing the delta.
        assertThat(engineer)
                .as("""
                        The V52 grants, asserted positively. suite:delete and artifact:delete were \
                        Lead-only as SHARED STRUCTURE; run:close and result:amend as JUDGEMENTS \
                        ABOUT THE WORK; doc:publish as a readiness signal. All five are a \
                        contributor's now, because the requirement draws the line at approvals and \
                        configuration and nowhere else.""")
                .contains("suite:delete", "artifact:delete", "run:close", "result:amend",
                        "doc:publish");
        assertThat(engineer)
                .as("and the V51 grants, which V52 does not disturb")
                .contains("plan:delete", "case:delete", "evidence:delete");
        assertThat(engineer)
                .as("every creative permission is held")
                .contains("suite:manage", "case:create", "case:edit", "plan:create", "run:execute");
        assertThat(engineer)
                .as("contributors review; only Lead+ signs off")
                .contains("case:review", "suite:review", "plan:review");
        assertThat(engineer.stream().filter(key -> key.endsWith(":approve")).toList())
                .as("author-cannot-approve-own-work is untouched, and so is who may approve at all")
                .isEmpty();
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
    @DisplayName("nothing may review work it may not comment on — the tiers are a ladder, not three sets")
    void everyReviewerIsAlsoACommenter() throws SQLException {
        // THE COMPUTED CHECK behind ReviewSubjectType.COMMENT_PERMISSION, which is ONE key —
        // case:comment — for all three subject types, where reviewing and approving are triples.
        //
        // §6g describes three tiers and the roles spec's Review table lists them in order:
        // case:comment (Viewer and up) below *:review (Test Engineer and up) below *:approve (Test
        // Lead and up). "Below" is what makes one comment key the right shape rather than a
        // shortcut: a reviewer is a commenter with more rights, so gating the comment endpoint on
        // the bottom key gates it for everybody who should reach it.
        //
        // If a role were ever granted plan:review WITHOUT case:comment, the ladder would stop being
        // a ladder and that role could record a binding-adjacent verdict on a plan while being
        // refused the remark explaining it — a 403 on the weaker act and a 201 on the stronger one,
        // which reads as a defect from every direction. The repair is to grant case:comment, or to
        // add the two missing comment keys to the catalogue and key the gate off the subject type
        // like the review keys; never to delete this check.
        //
        // Written over the catalogue rather than over the six roles by name, so a seventh role
        // added in a later migration is covered without anyone remembering this reasoning exists.
        assertThat(queryStrings("""
                SELECT DISTINCT r.name
                  FROM role r
                  JOIN role_permission rp ON rp.role_id = r.id
                 WHERE rp.permission_key IN ('case:review', 'suite:review', 'plan:review')
                   AND NOT EXISTS (SELECT 1 FROM role_permission held
                                    WHERE held.role_id = r.id
                                      AND held.permission_key = 'case:comment')
                 ORDER BY r.name
                """))
                .as("""
                        Roles that may review work but not comment on it. ReviewService gates the \
                        comment endpoint on case:comment for ALL THREE subject types, which is \
                        sound only while the commenting tier sits below the reviewing one. Grant \
                        case:comment to these roles, or add suite:comment/plan:comment to the \
                        catalogue and key the gate off the subject type; do not delete this \
                        check.""")
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
    @DisplayName("company:teams and company:members still coincide — so the org-chart gate is untested")
    void teamManagementAndMemberManagementAreStillHeldTogether() throws SQLException {
        // THIS CHECK IS NOT AN INVARIANT. It records a coincidence, so that the day it stops being
        // true somebody is told.
        //
        // TeamWriteService gates on company:teams. TeamEndpointTest proves the gate is a real
        // permission check — reverting it to a constant reddens exactly six tests — but it CANNOT
        // prove the gate asks the right KEY, because V3 grants company:teams and company:members to
        // exactly the same roles. Every persona the suite can build holds both or neither, so a
        // mutation swapping one key for the other reddens NOTHING. The distinction the gate makes
        // is real in the code and unobservable in the seeded data.
        //
        // That is §61's "vacuously true" shape: a property that holds by accident, where the
        // accident is invisible at the place the rule is written. It is recorded here rather than
        // papered over, because the failure mode is silent — a future role granted company:members
        // alone would silently gain, or be silently refused, the whole org chart depending on which
        // key the gate names, and no existing test would move.
        //
        // WHEN THIS FAILS: that is the good case. A role now holds one without the other, the
        // discrimination has become observable, and the repair is to add the persona to
        // TeamEndpointTest.Gate and assert the two keys apart — NOT to re-align the seed so this
        // check passes again.
        assertThat(queryStrings("""
                SELECT r.name
                  FROM role r
                 WHERE EXISTS (SELECT 1 FROM role_permission rp
                                WHERE rp.role_id = r.id AND rp.permission_key = 'company:teams')
                    <> EXISTS (SELECT 1 FROM role_permission rp
                                WHERE rp.role_id = r.id AND rp.permission_key = 'company:members')
                 ORDER BY r.name
                """))
                .as("""
                        Roles holding exactly one of company:teams and company:members. This is \
                        not a defect — it means the org-chart gate can finally be tested apart \
                        from the membership gate. Add this role as a persona in \
                        TeamEndpointTest.Gate and assert that it may do one and not the other, \
                        then update this check to expect it.""")
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
    @DisplayName("OWN is carried by no grant — empty by decision (V51), and pinned so it stays that way")
    void ownIsCarriedByNoGrant() throws SQLException {
        // The population V51 emptied, asserted in BOTH directions, because an empty set is the one
        // result that proves nothing on its own: a seeding bug that dropped every OWN row and a
        // decision that removed the only two look identical from here.
        //
        // The direction that matters is the future one. `OWN` is still a legal CHECK value, still a
        // PermissionQualifier member, and IdentityProjectAccess.canDoOnAuthored still resolves it —
        // kept on purpose against the next permission that needs "only what you wrote". So the
        // mechanism is loaded and unused, which is exactly the state in which a later migration can
        // requalify a grant and reintroduce authorship-scoped CRUD without anyone deciding to. This
        // fails on that migration and names the decision it would be reversing.
        //
        // NOT a claim that OWN should never be used again. A grant appearing here is a failure only
        // in the sense that it must be a DELIBERATE edit to this assertion and to roles spec §A5 —
        // the same bar V40's enumeration sets for widening a credential.
        assertThat(queryStrings("""
                SELECT r.name || ' / ' || rp.permission_key
                  FROM role_permission rp
                  JOIN role r ON r.id = rp.role_id
                 WHERE rp.qualifier = 'OWN'
                 ORDER BY 1
                """))
                .as("""
                        Grants carrying the OWN qualifier. V51 (§207) removed the only two — Test \
                        Engineer's case:delete and evidence:delete — because CRUD on plans, cases \
                        and steps belongs to every non-Viewer contributor no matter who created the \
                        row. A row here means authorship-scoped CRUD is back; roles spec §A5 must \
                        say so first.""")
                .isEmpty();

        assertThat(queryStrings("SELECT DISTINCT qualifier FROM role_permission ORDER BY 1"))
                .as("""
                        The qualifiers actually in play. Since V52 that is ALL and nothing else, so \
                        this no longer distinguishes "OWN has no rows" from "the query is wrong" — \
                        everyQualifierIsAll is the assertion that carries that job now, and it \
                        counts rows rather than reading a column that could be misnamed.""")
                .containsExactly("ALL");
    }

    @Test
    @DisplayName("every grant is unqualified — both qualifiers empty by decision (V52)")
    void everyQualifierIsAll() throws SQLException {
        // The column-wide version of ownIsCarriedByNoGrant, and it exists because V52 emptied the
        // other value too. `artifact:edit` was the only key that ever carried PLAN_SCOPE — Test
        // Engineer's grant (V19) and Agent's copy of it (V40) — and §210 de-qualified both: a
        // contributor creates, edits and deletes, unqualified, so the project-scope artifact every
        // plan inherits read-only is no longer a separate tier of edit.
        //
        // WHAT THIS MEANS FOR THE MECHANISM, stated because it changes the argument for keeping it.
        // V51 gave three reasons for retaining an unpopulated OWN; reason 2 was "PLAN_SCOPE is the
        // same shape and IS populated, so the column and the resolution seam are earning their keep
        // regardless". That reason expired here. PermissionQualifier's two restrictive members,
        // IdentityProjectAccess.canDoOnAuthored and the plan-scope resolution arm are now ALL
        // unreachable from seeded data, and they are kept on the two reasons that survive: the
        // qualifier is DATA, so deleting an arm makes a future qualified grant fail OPEN at
        // whichever gate forgot it; and rebuilding either narrowing later costs a migration, a gate
        // change and a contract change.
        //
        // So this is deliberately a check on the DATA and not a licence to delete the code. A row
        // appearing here is not a failure in the sense of a bug — it is a requirement that the
        // migration adding it also edited roles spec §A5, because a qualified grant is a narrowing
        // no screen renders and no reader expects.
        assertThat(queryStrings("""
                SELECT r.name || ' / ' || rp.permission_key || ' = ' || rp.qualifier
                  FROM role_permission rp
                  JOIN role r ON r.id = rp.role_id
                 WHERE rp.qualifier <> 'ALL'
                 ORDER BY 1
                """))
                .as("""
                        Every qualified grant in the catalogue. Empty since V52. OWN emptied at V51 \
                        (§207, CRUD is not authorship-scoped); PLAN_SCOPE emptied at V52 (§210, \
                        artifact:edit is unqualified for contributors). A row here means a narrowing \
                        came back — decide it in the spec first.""")
                .isEmpty();

        assertThat(queryStrings("SELECT count(*)::text FROM role_permission"))
                .as("""
                        The sanity half, and the reason the assertion above is not self-proving: an \
                        empty result and a misnamed table produce the same green. This counts the \
                        rows the query above filters, so a typo shows up as a zero.""")
                .doesNotContain("0");
    }

    @Test
    @DisplayName("artifact:edit is unqualified for every role that holds it (V52)")
    void artifactEditIsUnqualifiedEverywhere() throws SQLException {
        // The inverse of what this test asserted from V19 until V52, and kept rather than deleted
        // because the DIRECTION is what matters. Test Engineer's grant was ALL until V19 narrowed
        // it, PLAN_SCOPE until V52 widened it back, and the widening is a SILENT one: an
        // artifact:edit gate written against the old data simply stops narrowing, with no error
        // anywhere. Nothing 500s, nothing 403s, and the project-scope artifact every cycle inherits
        // read-only (plan §6n) becomes editable by a contributor.
        //
        // That is a real capability change, decided in §210 — a contributor edits, unqualified —
        // and this assertion is where it is written down as data. Restoring the qualifier means
        // editing the spec's Artifacts table first.
        assertThat(queryStrings("""
                SELECT r.name || ' / ' || rp.permission_key || ' = ' || rp.qualifier
                  FROM role_permission rp
                  JOIN role r ON r.id = rp.role_id
                 WHERE rp.permission_key = 'artifact:edit'
                 ORDER BY r.name
                """))
                .as("""
                        Every grant of artifact:edit. All five must be ALL since V52. A role \
                        appearing here that the spec's Artifacts table does not grant is the defect \
                        — the spec is authoritative. Agent is Test Engineer entire since V52, so its \
                        row is the contributor's spelling by construction, not by transcription.""")
                .containsExactly(
                        "Agent / artifact:edit = ALL",
                        "Company Admin / artifact:edit = ALL",
                        "Instance Admin / artifact:edit = ALL",
                        "Test Engineer / artifact:edit = ALL",
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
        for (String role : List.of(AUTOMATION_SERVICE, AGENT)) {
            assertThat(grantsOf(role))
                    .as("""
                            except the two token-carried roles (%s). A token is not a member: it has \
                            no membership row and therefore no V15 baseline grant, and a PROJECT-scope \
                            role's company:read is unreachable from evaluateCompanyWide anyway — which \
                            is the whole reason V15 exists. Granting it here would look like a company \
                            surface that no evaluation path can answer.""", role)
                    .doesNotContain("company:read");
        }
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

    /**
     * {@code permissionAreas} as written in {@code frontend/src/lib/permissions.ts}.
     *
     * <p>Parsed from the source rather than from anything generated, for the same reason
     * {@link #permissionKeysFromContract} parses the YAML: this map is hand-maintained and is
     * therefore the thing that can drift. The {@code satisfies Record<PermissionKey, Area>} the
     * class header credits holds the <em>keys</em> to the contract at compile time and says nothing
     * whatever about the <em>values</em>, which is the half being compared here.
     */
    private static Map<String, String> permissionAreasFromTheSpa() throws IOException {
        Path source = locateRepositoryFile("frontend/src/lib/permissions.ts");
        String typescript = Files.readString(source);

        int start = typescript.indexOf("export const permissionAreas = {");
        assertThat(start)
                .as("lib/permissions.ts no longer exports permissionAreas — fix this parse rather "
                        + "than deleting the comparison")
                .isNotNegative();
        int end = typescript.indexOf("\n} as const satisfies Record<PermissionKey, Area>;", start);
        assertThat(end)
                .as("permissionAreas no longer closes with `satisfies Record<PermissionKey, Area>` "
                        + "— that clause is what makes the SPA's copy exhaustive, so its "
                        + "disappearance is a finding and not a parse problem")
                .isNotNegative();

        Map<String, String> areas = new TreeMap<>();
        Matcher matcher = Pattern.compile("'([^']+)':\\s*'([^']+)'")
                .matcher(typescript.substring(start, end));
        while (matcher.find()) {
            areas.put(matcher.group(1), matcher.group(2));
        }
        assertThat(areas).as("parsed no permission areas out of lib/permissions.ts").isNotEmpty();
        return areas;
    }

    /**
     * The permission matrix as roles spec §A3 draws it, role name to granted keys.
     *
     * <p>Parsed from the markdown for the same reason {@link #permissionKeysFromContract} parses
     * the YAML and {@link #permissionAreasFromTheSpa} parses the TypeScript: the spec is the
     * authority the other three transcriptions defer to, so a comparison against a fourth copy of
     * it would be worth nothing. {@code packages/client/src/test/roleMatrix.ts} parses the same
     * tables for the `PROJECT` scope alone; this reads all three scopes, because the seeded rows it
     * is compared against do not stop at one.
     *
     * <p>A parser that silently reads nothing looks exactly like a matrix that agrees, which is the
     * failure mode every check in this class is written against — so the parse asserts its own
     * yield three ways: 55 distinct keys (the catalogue, pinned independently by
     * {@link #catalogueMatchesTheContract}), no empty role, and an unrecognised column heading
     * fails rather than being skipped.
     */
    private static Map<String, Set<String>> matrixFromTheSpec() throws IOException {
        List<String> lines = Files.readAllLines(locateRepositoryFile(ROLES_SPEC));
        int start = headingIndex(lines, "### A3.");
        int end = headingIndex(lines, "### A4.");

        Map<String, Set<String>> held = new TreeMap<>();
        SPEC_COLUMNS.values().forEach(role -> held.put(role, new TreeSet<>()));

        Set<String> keysSeen = new TreeSet<>();
        List<String> columns = List.of();
        for (String line : lines.subList(start, end)) {
            if (!line.startsWith("|")) {
                columns = List.of();
                continue;
            }
            List<String> cells = cellsOf(line);
            if (cells.isEmpty()) {
                continue;
            }
            if ("Permission".equals(cells.get(0))) {
                columns = cells;
                continue;
            }
            // A table's `|---|:--:|` separator, and any `|`-led line before a header was seen.
            if (columns.isEmpty() || cells.stream().allMatch(cell -> cell.matches(":?-+:?"))) {
                continue;
            }

            List<String> keys = new ArrayList<>();
            Matcher matcher = SPEC_KEY.matcher(cells.get(0));
            while (matcher.find()) {
                keys.add(matcher.group(1));
            }
            assertThat(keys)
                    .as("§A3 row '%s' declares no backticked permission key — the row shape "
                            + "changed, and a parse that skips it reads as universal agreement",
                            cells.get(0))
                    .isNotEmpty();
            keysSeen.addAll(keys);

            for (int column = 1; column < columns.size(); column++) {
                String heading = columns.get(column);
                // The COMPANY and SYSTEM tables end in a prose column that grants nothing.
                if ("Covers".equals(heading)) {
                    continue;
                }
                String role = SPEC_COLUMNS.get(heading);
                assertThat(role)
                        .as("§A3 has a column headed '%s' and this test does not know which seeded "
                                + "role that is. A column nobody compares is exactly what this "
                                + "test exists to stop — add it to SPEC_COLUMNS, or, if a seventh "
                                + "tier really arrived, take the decision first (spec §A2).",
                                heading)
                        .isNotNull();
                // `—` is the only spelling of "not granted" §A3 uses, and its legend says so;
                // anything else is a grant, possibly narrowed by a rule rather than by the
                // qualifier column (Automation Service's `case:create`, the one live instance).
                String cell = column < cells.size() ? cells.get(column) : "—";
                if (!"—".equals(cell)) {
                    held.get(role).addAll(keys);
                }
            }
        }

        assertThat(keysSeen)
                .as("§A3 parsed as %d keys and the catalogue is 55. Either a table stopped matching "
                        + "the parse or the spec lost a row — both are findings, and neither is "
                        + "fixed by relaxing this number.", keysSeen.size())
                .hasSize(55);
        held.forEach((role, keys) -> assertThat(keys)
                .as("§A3 parsed no grant at all for %s, which no role in this product has", role)
                .isNotEmpty());
        return held;
    }

    private static List<String> cellsOf(String row) {
        String[] parts = row.split("\\|", -1);
        if (parts.length < 3) {
            return List.of();
        }
        return List.of(parts).subList(1, parts.length - 1).stream().map(String::trim).toList();
    }

    private static int headingIndex(List<String> lines, String heading) {
        int index = -1;
        for (int line = 0; line < lines.size() && index < 0; line++) {
            if (lines.get(line).startsWith(heading)) {
                index = line;
            }
        }
        assertThat(index)
                .as("%s no longer contains a '%s' heading — fix this parse rather than deleting "
                        + "the comparison", ROLES_SPEC, heading)
                .isNotNegative();
        return index;
    }

    private static Path locateContract() {
        return locateRepositoryFile("docs/specs/openapi.yaml");
    }

    private static Path locateRepositoryFile(String relative) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                relative + " not found above " + Path.of("").toAbsolutePath()
                        + " — the backend, the contract and the SPA must be checked out together, "
                        + "since the SPA is served from the boot jar and all three share one "
                        + "catalogue.");
    }
}
