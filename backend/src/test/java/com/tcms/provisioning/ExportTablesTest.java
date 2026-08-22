package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every table is walked or excluded — the export's half of ERD §0.1's argument.
 *
 * <p>{@code SchemaConventionTest} asks "does every table carry company_id"; this asks "does the
 * export know what to do with every table". They fail on the same commit for the same new table and
 * they are not the same check: a table can carry {@code company_id}, satisfy that test, and still be
 * missing from an archive because nothing walks it.
 *
 * <p>The connection is the suite's <strong>superuser</strong> one, because these are catalogue
 * questions and the catalogue is not company-scoped. Nothing here reads product data.
 */
@DisplayName("export-company — every table is classified")
class ExportTablesTest extends IntegrationTestBase {

    private static Connection catalogConnection;

    @BeforeAll
    static void connect() throws SQLException {
        catalogConnection = superuserConnection();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (catalogConnection != null) {
            catalogConnection.close();
        }
    }

    @Test
    @DisplayName("every table is either walked or excluded for a reason, and never both")
    void everyTableIsEitherWalkedOrExcludedForAReason() throws SQLException {
        ExportTables tables = ExportTables.classify(catalogConnection);

        Set<String> accountedFor = new LinkedHashSet<>(tables.walked());
        accountedFor.addAll(ExportTables.EXCLUDED.keySet());

        List<String> unaccounted = allTables().stream()
                .filter(table -> !accountedFor.contains(table))
                .toList();
        assertThat(unaccounted)
                .as("""
                        Tables the export would silently omit. Classify each in ExportTables — a \
                        company-owned table needs a company_isolation policy, and an instance-level \
                        one needs an Exclusion with a written reason.""")
                .isEmpty();

        List<String> both = tables.walked().stream()
                .filter(ExportTables.EXCLUDED::containsKey)
                .toList();
        assertThat(both)
                .as("Tables that are both walked and excluded — the partition must be a partition")
                .isEmpty();
    }

    @Test
    @DisplayName("every RLS-protected table is walked — a client's data is never left behind")
    void everyRlsProtectedTableIsWalked() throws SQLException {
        // The failure mode with no symptom: excluding a company-owned table quietly leaves a
        // client's cases out of their own export, and the archive still opens and still parses.
        ExportTables tables = ExportTables.classify(catalogConnection);
        Set<String> walked = new LinkedHashSet<>(tables.walked());

        List<String> policiedButNotWalked = tables.policied().stream()
                .filter(table -> !walked.contains(table))
                .sorted()
                .toList();

        assertThat(tables.policied())
                .as("fixture precondition: policies must have been found, or this asserts nothing")
                .isNotEmpty();
        assertThat(policiedButNotWalked)
                .as("""
                        Tables carrying a company_isolation policy that the export does not walk. \
                        Every one of them is company-owned client data by definition.""")
                .isEmpty();
    }

    @Test
    @DisplayName("every exclusion names a table that exists")
    void everyExclusionNamesATableThatExists() throws SQLException {
        // V1's own DO-block rule, one direction over: a rename must not silently drop a table's
        // classification into a name nothing matches, because the name simply stops being read.
        Set<String> actual = new LinkedHashSet<>(allTables());
        List<String> stale = ExportTables.EXCLUDED.keySet().stream()
                .filter(table -> !actual.contains(table))
                .sorted()
                .toList();

        assertThat(stale)
                .as("Exclusion entries that name no existing table — remove them or fix the name")
                .isEmpty();
    }

    @Test
    @DisplayName("company, app_user and membership_invitation are walked, not excluded")
    void theThreeInstanceLevelTablesThatTravelAreWalked() throws SQLException {
        // The three ERD §0.1 exceptions that ARE exported, each by a different rule. Asserted
        // literally because each is a decision the plan made and a later simplification would
        // reverse silently: without app_user the archive is a graph of UUIDs and every created_by
        // is unreadable, which fails the GDPR half of the goal outright.
        ExportTables tables = ExportTables.classify(catalogConnection);

        assertThat(tables.walked())
                .contains("company", "app_user", "membership_invitation");
        assertThat(ExportTables.EXCLUDED)
                .doesNotContainKeys("company", "app_user", "membership_invitation");
        assertThat(tables.companyScoped())
                .as("company and app_user have their own filters, so a WHERE company_id = ? "
                        + "ground-truth count cannot be taken over them")
                .doesNotContain("company", "app_user")
                .contains("membership_invitation");
    }

    @Test
    @DisplayName("the walked list is alphabetical, so two archives are diffable")
    void theWalkedListIsDeterministic() throws SQLException {
        List<String> walked = ExportTables.classify(catalogConnection).walked();
        assertThat(walked).isSorted();
        assertThat(walked).doesNotHaveDuplicates();
    }

    private static List<String> allTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }
}
