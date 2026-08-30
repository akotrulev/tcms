package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The {@code company:read} baseline — <strong>the repair for a permission the spec granted to
 * every member and nobody below Company Admin could answer for.</strong>
 *
 * <p>Roles spec §A3 grants {@code company:read} to "every member", and {@code V3} seeds it onto
 * most of the built-in roles saying exactly that. Most of them are PROJECT-scope, and
 * {@link PermissionEvaluator#evaluateCompanyWide} answers from the SYSTEM and COMPANY terms only —
 * by design, because "held on any project" would make {@code case:delete} on one project read as a
 * company-wide capability. So the permission was unreachable at the only scope that asks about the
 * company (handoff §39; four tests went red on their first run, all of them the Test Lead persona
 * reading an empty directory).
 *
 * <p>{@code V15} grants every member an explicit COMPANY-scope grant of {@link Role#COMPANY_MEMBER_ID},
 * which holds exactly {@code company:read}. This class asserts the four things that has to be true
 * of, and the first one is derived from the catalogue rather than written out:
 *
 * <ol>
 *   <li><strong>Every built-in role that holds {@code company:read} answers for it at company
 *       scope</strong> — computed from {@code role_permission}, so a seventh role added later is
 *       covered without an edit here. A hand-written list of five would have passed before the
 *       repair too, for four of them, by testing the seed instead of the question.</li>
 *   <li><strong>Every join path grants it</strong> — provisioning, its top-up re-run, and
 *       invitation acceptance.</li>
 *   <li><strong>Re-granting writes nothing</strong>, so a re-run and a concurrent acceptance are
 *       both safe.</li>
 *   <li><strong>It is not administrable</strong> — a member without it fails closed rather than
 *       being quietly repaired by a gate that asks a weaker question.</li>
 * </ol>
 *
 * <p>Ids use the {@code 8c…} prefix, which no other class uses: sharing one is how handoff §19's
 * first defect happened, and this class proved it again on its first full-suite run. It was
 * written against {@code b1…} — {@code CaseCreateEndpointTest}'s namespace — so its "a REMOVED
 * membership is not topped back up" test offboarded <em>that</em> class's Test Engineer, and five
 * case-creation tests failed with a 404 that reads like a broken project fixture. Green alone,
 * red in the suite. The prefix was checked against every {@code UUID.fromString} in the suite
 * before being chosen the second time.
 */
@DisplayName("The company:read baseline — every member can answer a company-level question")
class CompanyReadBaselineTest extends IntegrationTestBase {

    private static final String READ_COMPANY = "company:read";

    private static final UUID COMPANY = UUID.fromString("8c000000-0000-4000-8000-00000000000a");
    private static final UUID PROJECT = UUID.fromString("8c001000-0000-4000-8000-000000000001");

    /** A member holding no role grant beyond the baseline — the person §A1 describes. */
    private static final UUID PLAIN = UUID.fromString("8c003000-0000-4000-8000-0000000000ff");

    @Autowired
    private PermissionEvaluator permissions;

    @Autowired
    private AdminDirectoryService directory;

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
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            if (companies.findById(COMPANY).isEmpty()) {
                companies.save(Company.provision(COMPANY, "baseline-co", "Baseline Ltd", now));
            }
            activeUser(PLAIN, "plain@baseline.test", now);
        });
        inCompany(COMPANY, () -> tx.executeWithoutResult(status -> {
            if (projects.findScopedById(PROJECT).isEmpty()) {
                projects.save(Project.create(PROJECT, COMPANY, "BSL", "Baseline Product", null, now));
            }
            member(COMPANY, PLAIN, "P. Member", now);
            grantBaseline(COMPANY);
        }));
    }

    // =====================================================================
    // 1. The catalogue-derived check
    // =====================================================================

    @Nested
    @DisplayName("Derived from the catalogue, not from a list of five roles")
    class Catalogue {

        /**
         * The assertion handoff §39's four red tests were really about.
         *
         * <p>Derived from {@code role_permission}: for every built-in role the seed says holds
         * {@code company:read}, a member granted that role <em>at the role's own scope</em> must be
         * able to answer {@code company:read} at <strong>company</strong> scope. Before {@code V15}
         * this failed for Test Lead, Test Engineer and Viewer — three quarters of the company's
         * people — and it fails again the day someone adds a role holding {@code company:read} and
         * forgets that a PROJECT-scope grant cannot carry it.
         */
        @Test
        @DisplayName("every built-in role holding company:read answers for it at COMPANY scope")
        void everyRoleHoldingCompanyReadAnswersAtCompanyScope() throws SQLException {
            List<RoleRow> holders = rolesHolding(READ_COMPANY);
            assertThat(holders)
                    .as("no role holds company:read at all — the seed comparison is broken, not this")
                    .isNotEmpty();

            Instant now = Instant.now();
            for (RoleRow role : holders) {
                UUID userId = userFor(role);
                inCompany(COMPANY, () -> tx.executeWithoutResult(status -> {
                    activeUserIn(userId, "role-" + role.shortName() + "@baseline.test", now);
                    member(COMPANY, userId, role.name() + " persona", now);
                    grantBaseline(COMPANY);
                    grantAtItsOwnScope(role, userId, now);
                }));

                assertThat(companyWide(userId).has(READ_COMPANY))
                        .as("""
                                A member holding '%s' cannot answer company:read at company scope. \
                                That role is %s-scope, and evaluateCompanyWide reads the SYSTEM and \
                                COMPANY terms only — so the permission the roles spec grants to \
                                every member is invisible to the only question that asks about the \
                                company. The repair is the Company Member baseline (V15), not a \
                                wider evaluator: "held anywhere in this company" would make \
                                case:delete on one project read as a company-wide capability.""",
                                role.name(), role.scope())
                        .isTrue();
            }
        }

        @Test
        @DisplayName("a member holding no role at all still answers company:read")
        void aPlainMemberAnswersCompanyRead() {
            // Membership grants authentication and ZERO project visibility (§A1) — but it does
            // grant company:read, which is the one thing §A3 says every member holds.
            assertThat(companyWide(PLAIN).has(READ_COMPANY)).isTrue();
        }

        @Test
        @DisplayName("and the baseline carries nothing else — no project comes with it")
        void theBaselineCarriesNothingElse() {
            // The failure this kills is the tempting shortcut: granting Viewer company-wide. That
            // would answer company:read too, and hand every member every project present and
            // future — the boundary §A1 exists to draw.
            assertThat(companyWide(PLAIN).keys())
                    .as("the baseline is exactly company:read at company scope")
                    .containsExactly(READ_COMPANY);
            // evaluate(user, project) is SYSTEM + COMPANY + PROJECT, so the baseline shows up here
            // too — which is correct and is the point: company:read is held everywhere in this
            // company. What must NOT appear is anything about the project itself.
            assertThat(evaluate(PLAIN, PROJECT).keys())
                    .as("""
                            A member with no project grant sees nothing OF a project. If the \
                            baseline grew a project:read, company membership would have become \
                            project visibility and roles spec §A1 would be gone.""")
                    .containsExactly(READ_COMPANY);
        }
    }

    // =====================================================================
    // 2. Every join path
    // =====================================================================

    @Nested
    @DisplayName("Every path into a company grants it")
    class JoinPaths {

        @Test
        @DisplayName("a membership row with no baseline grant answers NO — it fails closed")
        void withoutTheGrantThereIsNoCompanyRead() throws SQLException {
            UUID stranger = UUID.fromString("8c003000-0000-4000-8000-000000000001");
            Instant now = Instant.now();
            UUID viewerGrant = UUID.fromString("8c005000-0000-4000-8000-000000000001");
            inCompany(COMPANY, () -> tx.executeWithoutResult(status -> {
                activeUserIn(stranger, "stranger@baseline.test", now);
                member(COMPANY, stranger, "S. Stranger", now);
                // A PROJECT-scope Viewer grant, which carries company:read in role_permission.
                // That is what makes this test the pin on the repair rather than a restatement of
                // it: the workaround AdminDirectoryService.reachOf carried until §43 — "held
                // company-wide OR on any project" — answers YES here off exactly this grant, and
                // the direct company-scope question answers NO.
                if (roleAssignments.findById(viewerGrant).isEmpty()) {
                    roleAssignments.save(RoleAssignment.forUserOnProject(
                            viewerGrant, COMPANY, Role.VIEWER_ID, stranger, PROJECT, null, now));
                }
            }));
            deleteBaselineOf(stranger);

            // Fail-closed is the right direction, and the repair is a re-run of the CLI. Anything
            // that reintroduces the workaround — here or in the next surface to gate on a
            // company-scope permission — turns this green assertion red.
            assertThat(companyWide(stranger).has(READ_COMPANY)).isFalse();
            // And the admin surface asks the same question, which is the half that can regress
            // silently: AdminDirectoryService.reachOf is where the workaround lived, and every
            // other persona in the suite holds both a project grant AND the baseline — so nothing
            // else in 746 tests can tell the two readings apart.
            AdminReach withoutBaseline = reachOf(stranger);
            assertThat(withoutBaseline.readsCompany()).isFalse();
            assertThat(withoutBaseline.visibleProjects())
                    .as("the project grant is real and still confers project:read — it is only "
                            + "company:read it must not carry company-wide")
                    .containsExactly(PROJECT);

            inCompany(COMPANY, () -> tx.executeWithoutResult(status -> grantBaseline(COMPANY)));
            assertThat(companyWide(stranger).has(READ_COMPANY)).isTrue();
            assertThat(reachOf(stranger).readsCompany()).isTrue();
        }

        @Test
        @DisplayName("re-granting writes nothing — a re-run and a concurrent acceptance are safe")
        void theGrantIsIdempotent() {
            int first = inCompany(COMPANY, () -> tx.execute(status -> grantBaseline(COMPANY)));
            int second = inCompany(COMPANY, () -> tx.execute(status -> grantBaseline(COMPANY)));

            assertThat(first).as("everyone already has it by the time this test runs").isZero();
            assertThat(second).isZero();
            assertThat(baselineGrantsOf(PLAIN))
                    .as("""
                            Two baseline rows would give one person two grants, and V13's partial \
                            unique index with NULLS NOT DISTINCT is what makes that impossible \
                            rather than merely unlikely.""")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a REMOVED membership is not granted one")
        void offboardingIsNotToppedBackUp() throws SQLException {
            UUID leaver = UUID.fromString("8c003000-0000-4000-8000-000000000002");
            Instant now = Instant.now();
            inCompany(COMPANY, () -> tx.executeWithoutResult(status -> {
                activeUserIn(leaver, "leaver@baseline.test", now);
                member(COMPANY, leaver, "L. Leaver", now);
            }));
            deleteBaselineOf(leaver);
            setMembershipStatus(leaver, "REMOVED");

            inCompany(COMPANY, () -> tx.executeWithoutResult(status -> grantBaseline(COMPANY)));

            assertThat(baselineGrantsOf(leaver))
                    .as("""
                            A grant handed back to someone who was offboarded is a row an \
                            administrator has to know to go and delete — and the next top-up would \
                            put it back.""")
                    .isZero();
        }
    }

    // =====================================================================
    // helpers
    // =====================================================================

    /** One row of the role catalogue, as the seed defines it. */
    private record RoleRow(UUID id, String name, String scope) {

        String shortName() {
            return name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
        }
    }

    private List<RoleRow> rolesHolding(String permissionKey) throws SQLException {
        List<RoleRow> rows = new ArrayList<>();
        String sql = """
                SELECT r.id, r.name, r.scope
                  FROM role r
                  JOIN role_permission rp ON rp.role_id = r.id
                 WHERE r.built_in AND rp.permission_key = ?
                 ORDER BY r.name
                """;
        try (var connection = appConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, permissionKey);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new RoleRow(
                            UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3)));
                }
            }
        }
        return rows;
    }

    /**
     * A distinct account per role, derived from the role id so the fixture cannot collide with
     * another class's and does not need a hand-maintained list beside the catalogue query.
     */
    private static UUID userFor(RoleRow role) {
        return UUID.nameUUIDFromBytes(("baseline-persona:" + role.id()).getBytes(
                java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Grants the role the way the product would: PROJECT-scope roles on a project, COMPANY-scope
     * roles company-wide. Coercing either would be testing an arrangement
     * {@code RoleGrantService.scopeError} refuses.
     */
    private void grantAtItsOwnScope(RoleRow role, UUID userId, Instant now) {
        if (Role.COMPANY_MEMBER_ID.equals(role.id())) {
            return;
        }
        UUID grantId = UUID.nameUUIDFromBytes(
                ("baseline-grant:" + role.id() + userId).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        if (roleAssignments.findById(grantId).isPresent()) {
            return;
        }
        switch (role.scope()) {
            case "PROJECT" -> roleAssignments.save(RoleAssignment.forUserOnProject(
                    grantId, COMPANY, role.id(), userId, PROJECT, null, now));
            case "COMPANY" -> roleAssignments.save(RoleAssignment.forUserOnCompany(
                    grantId, COMPANY, role.id(), userId, null, now));
            // SYSTEM grants live in instance_role_assignment and are not a membership question;
            // the account's baseline is what this test is about, and Instance Admin holds
            // company:read through the SYSTEM term regardless.
            default -> { }
        }
    }

    /** V15's statement, exactly as provisioning and invitation acceptance run it. */
    private int grantBaseline(UUID companyId) {
        Object created = entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
        return ((Number) created).intValue();
    }

    private void deleteBaselineOf(UUID userId) throws SQLException {
        try (var connection = appConnection(COMPANY);
             var statement = connection.prepareStatement(
                     "DELETE FROM role_assignment WHERE role_id = ? AND user_id = ?")) {
            statement.setObject(1, Role.COMPANY_MEMBER_ID);
            statement.setObject(2, userId);
            statement.executeUpdate();
        }
    }

    private void setMembershipStatus(UUID userId, String status) throws SQLException {
        try (var connection = appConnection(COMPANY);
             var statement = connection.prepareStatement(
                     "UPDATE company_membership SET status = ? WHERE user_id = ?")) {
            statement.setString(1, status);
            statement.setObject(2, userId);
            statement.executeUpdate();
        }
    }

    private long baselineGrantsOf(UUID userId) {
        return inCompany(COMPANY, () -> tx.execute(status ->
                roleAssignments.findByUserId(userId).stream()
                        .filter(grant -> Role.COMPANY_MEMBER_ID.equals(grant.getRoleId()))
                        .count()));
    }

    /** The admin surface's own question, which is where the workaround §43 removed used to live. */
    private AdminReach reachOf(UUID userId) {
        try (var ignored = CompanyContext.open(COMPANY)) {
            return tx.execute(status -> directory.reachOf(userId));
        }
    }

    private PermissionSet companyWide(UUID userId) {
        return inCompany(COMPANY, () -> permissions.evaluateCompanyWide(userId));
    }

    private PermissionSet evaluate(UUID userId, UUID projectId) {
        return inCompany(COMPANY, () -> permissions.evaluate(userId, projectId));
    }

    private void activeUser(UUID id, String email, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, email, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    /**
     * The same, callable with a company context open. {@code app_user} is instance-level and
     * carries no filter, so an open context changes nothing about the write.
     */
    private void activeUserIn(UUID id, String email, Instant now) {
        activeUser(id, email, now);
    }

    private void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    private static void inCompany(UUID companyId, Runnable action) {
        try (var ignored = CompanyContext.open(companyId)) {
            action.run();
        }
    }

    private static <T> T inCompany(UUID companyId, Supplier<T> action) {
        try (var ignored = CompanyContext.open(companyId)) {
            return action.get();
        }
    }
}
