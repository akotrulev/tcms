package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The per-project issue-key allocator, against a real Postgres with RLS in force.
 *
 * <p>There is no unit-test lane for this: the whole behaviour is one {@code UPDATE … RETURNING}
 * whose correctness is the row lock it takes and the policy it is evaluated under, and a mocked
 * {@code EntityManager} would assert the string rather than the effect.
 *
 * <p>Ids use the {@code 1b…} prefix, which no other class in the suite uses. Sharing a prefix is
 * how handoff §19's first defect happened: one class's rows answer another class's query.
 */
@DisplayName("ProjectKeys — per-project issue numbering (expansion D5)")
class ProjectKeysTest extends IntegrationTestBase {

    @Autowired
    private ProjectKeys projectKeys;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TransactionTemplate tx;

    @Test
    @DisplayName("the first allocation is PREFIX-1, and the prefix comes from project.key")
    void firstAllocationIsOne() throws SQLException {
        UUID company = givenCompany("1b01");
        UUID project = givenProject(company, "1b02", "ACME");

        try (var ignored = CompanyContext.open(company)) {
            ProjectKeys.Allocation allocation = projectKeys.allocate(project, 1);

            assertThat(allocation.prefix()).isEqualTo("ACME");
            assertThat(allocation.firstValue()).isEqualTo(1L);
            assertThat(allocation.keyAt(0)).isEqualTo("ACME-1");
        }
    }

    @Test
    @DisplayName("a range is reserved in one statement, and the next caller starts after it")
    void rangeIsReservedWhole() throws SQLException {
        UUID company = givenCompany("1b03");
        UUID project = givenProject(company, "1b04", "OLB");

        try (var ignored = CompanyContext.open(company)) {
            ProjectKeys.Allocation bulk = projectKeys.allocate(project, 500);
            ProjectKeys.Allocation next = projectKeys.allocate(project, 1);

            assertThat(bulk.keyAt(0)).isEqualTo("OLB-1");
            assertThat(bulk.keyAt(499)).isEqualTo("OLB-500");
            assertThat(next.keyAt(0)).isEqualTo("OLB-501");
        }
    }

    @Test
    @DisplayName("two projects in one company number independently")
    void projectsDoNotShareACounter() throws SQLException {
        UUID company = givenCompany("1b05");
        UUID first = givenProject(company, "1b06", "AAA");
        UUID second = givenProject(company, "1b07", "BBB");

        try (var ignored = CompanyContext.open(company)) {
            assertThat(projectKeys.allocate(first, 3).keyAt(2)).isEqualTo("AAA-3");
            assertThat(projectKeys.allocate(second, 1).keyAt(0)).isEqualTo("BBB-1");
        }
    }

    @Test
    @DisplayName("a missing counter row throws, and names the repair")
    void missingCounterThrows() throws SQLException {
        UUID company = givenCompany("1b08");
        UUID project = givenProject(company, "1b09", "GAP");
        deleteCounterRow(company, project);

        try (var ignored = CompanyContext.open(company)) {
            assertThatThrownBy(() -> projectKeys.allocate(project, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no issue key counter")
                    .hasMessageContaining("tcms_seed_project_defaults");
        }

        // Put it back. Every class in this suite shares one container, so a project deliberately
        // left without its counter is a row the NEXT class's sweep reads as a defect — which is
        // exactly what ProjectKeySequenceSchemaTest reported the first time this ran.
        restoreCounterRow(company, project);
    }

    @Test
    @DisplayName("another company's project is invisible, so allocation refuses rather than leaks")
    void anotherCompanysProjectIsUnreachable() throws SQLException {
        UUID owner = givenCompany("1b0a");
        UUID theirs = givenProject(owner, "1b0b", "THEIRS");
        UUID intruder = givenCompany("1b0c");

        // The RLS refusal and the missing row are ONE answer on purpose — see ProjectKeys'
        // javadoc. Under FORCE ROW LEVEL SECURITY the policy simply matches nothing, and an
        // allocator that could tell "another company's project" from "no counter" would be
        // reporting the existence of a row the caller may not see.
        try (var ignored = CompanyContext.open(intruder)) {
            assertThatThrownBy(() -> projectKeys.allocate(theirs, 1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no issue key counter");
        }
    }

    @Test
    @DisplayName("allocating zero or fewer keys is a caller error, not an empty reservation")
    void countMustBePositive() throws SQLException {
        UUID company = givenCompany("1b0d");
        UUID project = givenProject(company, "1b0e", "ZERO");

        try (var ignored = CompanyContext.open(company)) {
            assertThatThrownBy(() -> projectKeys.allocate(project, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private UUID givenCompany(String prefix) {
        UUID id = id(prefix);
        Instant now = Instant.now();
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, "pk-" + prefix, "Key Co " + prefix, now));
        }
        return id;
    }

    /** A project plus the defaults every creation path seeds — including {@code V2}'s counter. */
    private UUID givenProject(UUID companyId, String prefix, String key) throws SQLException {
        UUID id = id(prefix);
        Instant now = Instant.now();
        try (var ignored = CompanyContext.open(companyId)) {
            tx.executeWithoutResult(status ->
                    projects.save(Project.create(id, companyId, key, key + " product", null, now)));
        }
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, id.toString());
            statement.execute();
        }
        return id;
    }

    private void deleteCounterRow(UUID companyId, UUID projectId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM project_key_sequence WHERE project_id = CAST(? AS uuid)")) {
            statement.setString(1, projectId.toString());
            assertThat(statement.executeUpdate())
                    .as("the row this test is about must have existed before it was removed — a "
                            + "delete of nothing would make the assertion below vacuous")
                    .isEqualTo(1);
        }
    }

    private void restoreCounterRow(UUID companyId, UUID projectId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.setString(2, projectId.toString());
            statement.execute();
        }
    }

    /** Every fixture id in this class is its own four-hex prefix — the `1b…` space. */
    private static UUID id(String prefix) {
        return UUID.fromString(prefix + "0000-0000-4000-8000-000000000001");
    }
}
