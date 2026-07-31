package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The two-company isolation harness (plan §6s, ERD §0.1) — the deliverable that makes
 * isolation <em>proven</em> rather than intended.
 *
 * <p>The fixture is deliberately hostile: both companies get a project with the
 * <strong>same key and the same name</strong>. Identical-looking data is what turns a
 * leak into a visible failure — with distinct names, a test can pass because it
 * happened to query the right row rather than because the boundary held.
 */
@DisplayName("Company isolation — RLS + Hibernate filter (plan §6s)")
class RlsIsolationTest extends IntegrationTestBase {

    private static final UUID ACME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID GLOBEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seedTwoCompaniesWithIdenticalLookingData() {
        // Companies are instance-level, so this runs with no company context.
        tx.executeWithoutResult(status -> {
            if (companies.findById(ACME).isEmpty()) {
                companies.save(Company.provision(ACME, "acme", "Acme Corporation", Instant.now()));
            }
            if (companies.findById(GLOBEX).isEmpty()) {
                companies.save(Company.provision(GLOBEX, "globex", "Globex Inc", Instant.now()));
            }
        });

        seedProject(ACME, "acme-olb");
        seedProject(GLOBEX, "globex-olb");
        seedSystemGrant();
    }

    /**
     * One instance-level operator grant, owned by this class.
     *
     * <p>Seeded here rather than relied upon from elsewhere: {@code PermissionEvaluatorTest}
     * also creates operator grants, and leaning on that would make this class pass or fail
     * depending on test ordering — the kind of coupling that turns into a flake nobody can
     * reproduce. Both tables involved are instance-level, so this needs no company context.
     */
    private void seedSystemGrant() {
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO app_user (id, email, canonical_name, status)
                    VALUES ('00000000-0000-4000-8000-00000000000f', 'rls-operator@example.test',
                            'RLS Operator', 'ACTIVE')
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.execute("""
                    INSERT INTO instance_role_assignment (id, role_id, user_id)
                    VALUES ('00000000-0000-4000-8000-00000000000e',
                            '10000000-0000-4000-8000-000000000001',
                            '00000000-0000-4000-8000-00000000000f')
                    ON CONFLICT (id) DO NOTHING
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the instance-level operator grant", e);
        }
    }

    private void seedProject(UUID companyId, String marker) {
        try (var ignored = CompanyContext.open(companyId)) {
            tx.executeWithoutResult(status -> {
                if (projects.findByKey("OLB").isEmpty()) {
                    // Same key AND same name in both companies — see the class comment.
                    projects.save(Project.create(
                            UUID.randomUUID(), companyId, "OLB", "Online Banking", marker, Instant.now()));
                }
            });
        }
    }

    // The precondition every assertion in this class rests on — that the app role can
    // neither bypass RLS nor own the tables it reads — is asserted by PrivilegeSplitTest,
    // which owns the whole two-role arrangement including the migration owner. It used to
    // be duplicated here; one home for the claim is the point of having a home for it.

    @Test
    @DisplayName("TRAP 2 — an unset GUC yields zero rows, not every row")
    void unsetCompanyContextYieldsZeroRows() throws SQLException {
        // The highest-severity silent-inversion risk in the schema. The policy reads
        // current_setting('app.current_company', true)::uuid, where `true` makes a
        // missing setting return NULL and `company_id = NULL` is never true. Get the
        // flag or the comparison wrong and a context-less query returns THE ENTIRE
        // INSTANCE. A green suite does not prove this — only this assertion does.
        long actuallyPresent = countAsOwner("project");
        assertThat(actuallyPresent).as("both companies' projects exist").isGreaterThanOrEqualTo(2);

        try (Connection connection = appConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM project")) {
            rs.next();
            assertThat(rs.getLong(1))
                    .as("a query with NO company context must see nothing — not everything")
                    .isZero();
        }
    }

    @Test
    @DisplayName("TRAP 2 holds on a RECYCLED connection too, where the GUC resets to '' and not to unset")
    void unsetCompanyContextYieldsZeroRowsOnARecycledConnection() throws SQLException {
        // The test above opens a fresh connection, so it only ever exercises a GUC that
        // was NEVER set. Production has a Hikari pool: a connection serves one company's
        // transaction and is then handed to a context-less one.
        //
        // Postgres does not return that GUC to "undefined". Once a custom setting has been
        // assigned — even with set_config(..., is_local => true) — its reset value for the
        // rest of the SESSION is the empty string. So current_setting(name, true) returns
        // '' rather than NULL, and the original policy's ''::uuid raised
        // "invalid input syntax for type uuid" instead of yielding zero rows.
        //
        // Fails closed rather than open, so it was never a leak — but it turned every
        // instance-level read touching a company-owned table into a 500 on any warm
        // connection, and it made this class's trap-2 assertion narrower than it reads.
        try (Connection connection = appConnection()) {
            // The pool's previous borrower: one company-scoped transaction, issuing exactly
            // what CompanyAwareTransactionManager issues — set_config(..., is_local => true)
            // — and committing. No RESET is needed to reproduce this; ending the transaction
            // is what leaves the setting at '' rather than unset.
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT set_config('app.current_company', ?, true)")) {
                statement.setString(1, ACME.toString());
                statement.execute();
            }
            connection.commit();
            connection.setAutoCommit(true);

            // The next borrower has no company context at all.
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT count(*) FROM project")) {
                rs.next();
                assertThat(rs.getLong(1))
                        .as("a recycled connection with no company must see zero rows, not raise")
                        .isZero();
            }
        }
    }

    @Test
    @DisplayName("each company sees only its own project, despite identical keys and names")
    void companiesCannotSeeEachOther() {
        List<String> acmeMarkers = descriptionsVisibleTo(ACME);
        List<String> globexMarkers = descriptionsVisibleTo(GLOBEX);

        assertThat(acmeMarkers).containsExactly("acme-olb");
        assertThat(globexMarkers).containsExactly("globex-olb");

        // Stated as its own assertion because "sees one row" would also pass if it were
        // consistently the WRONG one row.
        assertThat(acmeMarkers).doesNotContain("globex-olb");
        assertThat(globexMarkers).doesNotContain("acme-olb");
    }

    @Test
    @DisplayName("a project id from another company resolves to nothing, not to a row")
    void foreignIdIsUnreachable() {
        UUID globexProjectId = idOfProjectIn(GLOBEX);

        try (var ignored = CompanyContext.open(ACME)) {
            tx.executeWithoutResult(status ->
                    assertThat(projects.findScopedById(globexProjectId))
                            .as("Acme must not resolve a Globex project by id")
                            .isEmpty());
        }
    }

    @Test
    @DisplayName("SYSTEM-scope grants remain readable with no company context")
    void instanceLevelTablesWorkWithoutContext() throws SQLException {
        // The login path: minting a token requires reading SYSTEM grants before any
        // company is active. instance_role_assignment is instance-level and carries no
        // RLS precisely so this works — see its table comment and the audit note.
        //
        // Asserted as "the app role sees exactly what the owner sees" rather than as a
        // row count. The count was previously zero and asserted as such, which coupled
        // this test to the table being empty — PermissionEvaluatorTest seeds operator
        // grants, and a stale count would have failed here while the property under test
        // was still perfectly true. Comparing against the owner proves the real claim (no
        // rows are being filtered out) and cannot go stale as fixtures grow.
        long visibleToOwner = countAsOwner("instance_role_assignment");
        assertThat(visibleToOwner)
                .as("fixture precondition: there must be rows, or 'nothing is hidden' is vacuous")
                .isPositive();

        try (Connection connection = appConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM instance_role_assignment")) {
            rs.next();
            assertThat(rs.getLong(1))
                    .as("readable in full, not blocked and not filtered, with no company context")
                    .isEqualTo(visibleToOwner);
        }
    }

    @Test
    @DisplayName("RLS blocks WRITING a row into another company, not just reading one")
    void cannotWriteIntoAnotherCompany() {
        // The policies declare USING with no explicit WITH CHECK, and Postgres then
        // reuses USING as the insert check — so the read boundary is also the write
        // boundary. That is the behaviour we want, but it is implicit in the DDL rather
        // than stated, so it gets its own test: if someone later adds an explicit
        // WITH CHECK (true) to "fix" an insert error, this fails instead of silently
        // allowing a company to plant rows in another's tenancy.
        //
        // Found by mutation-testing the harness, not by reading the DDL.
        try (var ignored = CompanyContext.open(ACME)) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                    projects.saveAndFlush(Project.create(
                            UUID.randomUUID(), GLOBEX, "PLANT", "Planted", "should-not-persist",
                            Instant.now()))))
                    .as("writing a GLOBEX row while ACME is active must be refused by the database")
                    .isInstanceOf(Exception.class);
        }

        try (var ignored = CompanyContext.open(GLOBEX)) {
            tx.executeWithoutResult(status ->
                    assertThat(projects.findByKey("PLANT"))
                            .as("and nothing may have landed in the target company")
                            .isEmpty());
        }
    }

    @Test
    @DisplayName("CompanyContext.require() fails loudly rather than silently reading nothing")
    void requireFailsWithoutContext() {
        try (var ignored = CompanyContext.clear()) {
            assertThatThrownBy(CompanyContext::require)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No company context is active");
        }
    }

    @Test
    @DisplayName("a nested scope restores the outer company on close")
    void scopesNestAndRestore() {
        try (var ignored = CompanyContext.open(ACME)) {
            assertThat(CompanyContext.current()).contains(ACME);
            try (var alsoIgnored = CompanyContext.open(GLOBEX)) {
                assertThat(CompanyContext.current()).contains(GLOBEX);
            }
            // A leak here is how a company switch bleeds into the next operation.
            assertThat(CompanyContext.current()).contains(ACME);
        }
        assertThat(CompanyContext.current()).isEmpty();
    }

    private List<String> descriptionsVisibleTo(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            return tx.execute(status ->
                    projects.findAllByOrderByNameAsc().stream().map(Project::getDescription).toList());
        }
    }

    private UUID idOfProjectIn(UUID companyId) {
        try (var ignored = CompanyContext.open(companyId)) {
            return tx.execute(status -> projects.findByKey("OLB").orElseThrow().getId());
        }
    }

    private static long countAsOwner(String table) throws SQLException {
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
