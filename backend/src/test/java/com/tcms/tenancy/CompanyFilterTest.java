package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The entity-level half of the {@code company_id} rule — the counterpart to
 * {@code SchemaConventionTest}, which enforces the same invariant over tables.
 *
 * <p>{@code Project}'s javadoc has claimed since M0 that "{@code CompanyFilterTest}
 * [enforces the filter] over every entity". It did not exist. Four more company-owned
 * entities arrived with the {@code identity} module, which is exactly the situation the
 * missing check was supposed to cover, so it is written here rather than documented
 * again.
 *
 * <h2>Derived from the database, not from a list in this file</h2>
 *
 * <p>The set of company-owned tables is read from {@code pg_class.relrowsecurity} — the
 * live truth about which tables Postgres is protecting. A hard-coded list here would be a
 * third copy of the same fact (after V1's array and the ERD's exception list) and would
 * go stale in exactly the silent way handoff §6 lesson 9 describes: reading a file top to
 * bottom missed five join tables; scripting the comparison found them.
 */
@DisplayName("Company filter — every company-owned entity carries it (ERD §0.1)")
class CompanyFilterTest extends IntegrationTestBase {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @DisplayName("every entity mapped to an RLS-protected table declares the company filter")
    void companyOwnedEntitiesCarryTheFilter() throws SQLException {
        Set<String> rlsProtected = tablesWithRowLevelSecurity();
        var missing = new TreeMap<String, String>();

        for (var entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            String table = tableNameOf(type);
            if (rlsProtected.contains(table) && !declaresCompanyFilter(type)) {
                missing.put(type.getSimpleName(), table);
            }
        }

        assertThat(missing)
                .as("""
                    These entities map to RLS-protected tables but carry no @Filter(name = CompanyFilter.NAME).
                    RLS would still stop a cross-company READ, but the application layer would be silently \
                    absent — and a Hibernate filter is what scopes a query BEFORE the database has to refuse \
                    it. Add the annotation; do not add the table to an exception list.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no instance-level entity declares the filter — it would break the login path")
    void instanceLevelEntitiesDoNotCarryTheFilter() throws SQLException {
        // The reverse direction, and the more dangerous one to get wrong. A filter on
        // app_user, role or instance_role_assignment would evaluate `company_id = :companyId`
        // against a table that has no such column — and on the login path, where SYSTEM
        // grants must be readable with NO company open, it is precisely the query that
        // must not be scoped. Stated as its own test because "all the filters are present"
        // would also pass if filters were present everywhere.
        Set<String> rlsProtected = tablesWithRowLevelSecurity();
        var wronglyFiltered = new TreeMap<String, String>();

        for (var entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            String table = tableNameOf(type);
            if (!rlsProtected.contains(table) && declaresCompanyFilter(type)) {
                wronglyFiltered.put(type.getSimpleName(), table);
            }
        }

        assertThat(wronglyFiltered)
                .as("instance-level entities must not be company-filtered — they have no company_id")
                .isEmpty();
    }

    @Test
    @DisplayName("the identity module's split across the RLS boundary is the documented one")
    void identityModuleSitsOnBothSidesDeliberately() throws SQLException {
        // Identity is the one module that spans the boundary, and which side a table sits
        // on is the whole reason instance_role_assignment exists as a separate table. If a
        // future migration were to put app_user under RLS, or lift role_assignment out of
        // it, that is a decision — this fails so it cannot be an accident.
        Set<String> rlsProtected = tablesWithRowLevelSecurity();

        assertThat(rlsProtected)
                .as("company-owned identity tables")
                .contains("company_membership", "team", "team_membership", "role_assignment");

        assertThat(rlsProtected)
                .as("""
                    instance-level identity tables. app_user carries no company_id because one account \
                    spans companies; instance_role_assignment carries none because a SYSTEM grant under \
                    RLS would be invisible in every company context, permanently.""")
                .doesNotContain(
                        "app_user", "role", "permission", "role_permission", "instance_role_assignment");
    }

    private static boolean declaresCompanyFilter(Class<?> type) {
        Filter filter = type.getAnnotation(Filter.class);
        return filter != null && CompanyFilter.NAME.equals(filter.name());
    }

    /** The mapped table name, falling back to JPA's default of the entity's simple name. */
    private static String tableNameOf(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table != null && !table.name().isBlank()) {
            return table.name().toLowerCase(Locale.ROOT);
        }
        return type.getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static Set<String> tablesWithRowLevelSecurity() throws SQLException {
        Set<String> tables = new HashSet<>();
        try (Connection connection = superuserConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                     SELECT c.relname
                       FROM pg_class c
                       JOIN pg_namespace n ON n.oid = c.relnamespace
                      WHERE c.relrowsecurity
                        AND n.nspname = current_schema()
                     """)) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertThat(tables)
                .as("the RLS set must be non-empty, or both directions of this test pass vacuously")
                .isNotEmpty();
        return new TreeSet<>(tables);
    }
}
