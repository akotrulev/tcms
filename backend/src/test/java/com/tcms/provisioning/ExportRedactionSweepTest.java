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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sweeps every column in the schema for a name that suggests a credential, and fails unless each is
 * accounted for.
 *
 * <p><strong>The point is that it fails for a column that does not exist yet.</strong> A careful
 * reading of the redaction list proves the list is internally consistent and nothing else; this
 * derives the question from the catalogue, which is the shape ERD §0.1 and
 * {@code SchemaConventionTest} already paid for twice.
 */
@DisplayName("export-company — no credential leaves this instance")
class ExportRedactionSweepTest extends IntegrationTestBase {

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
    @DisplayName("every suspect column is redacted, in an excluded table, or deliberately kept")
    void everySuspectColumnIsRedactedExcludedOrDeliberatelyKept() throws SQLException {
        List<String> unaccounted = new ArrayList<>();

        for (Map.Entry<String, String> column : allColumns()) {
            String table = column.getKey();
            String name = column.getValue();
            if (!ExportRedactions.isSuspect(name)) {
                continue;
            }
            boolean dropped = ExportRedactions.droppedFrom(table).contains(name);
            boolean excluded = ExportTables.EXCLUDED.containsKey(table);
            boolean kept = ExportRedactions.DELIBERATELY_KEPT.containsKey(table + "." + name);
            if (!dropped && !excluded && !kept) {
                unaccounted.add(table + "." + name);
            }
        }

        assertThat(unaccounted)
                .as("""
                        Columns whose NAME suggests credential material and which an export archive \
                        would carry to a client, a data-protection officer or a ticket attachment. \
                        There are exactly three ways out and each is a decision: \
                        (1) add it to ExportRedactions.DROPPED_COLUMNS, which drops the column from \
                        every exported row of that table; \
                        (2) exclude the whole table in ExportTables.EXCLUDED with a reason, if it \
                        holds nothing else a client could want; \
                        (3) add "table.column" to ExportRedactions.DELIBERATELY_KEPT with a written \
                        reason, if the name is misleading — the four content_hash columns are \
                        content addresses of the client's own snapshots and are kept that way.""")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing is kept or dropped for a column that is gone")
    void nothingIsKeptOrDroppedForAColumnThatIsGone() throws SQLException {
        // Both directions. A stale exemption is how the NEXT column of that name slips through
        // unnoticed: the entry keeps the list looking complete while matching nothing.
        Set<String> actual = new LinkedHashSet<>();
        for (Map.Entry<String, String> column : allColumns()) {
            actual.add(column.getKey() + "." + column.getValue());
        }

        List<String> staleKeeps = ExportRedactions.DELIBERATELY_KEPT.keySet().stream()
                .filter(entry -> !actual.contains(entry))
                .sorted()
                .toList();
        assertThat(staleKeeps)
                .as("DELIBERATELY_KEPT entries naming no existing column")
                .isEmpty();

        List<String> staleDrops = new ArrayList<>();
        ExportRedactions.DROPPED_COLUMNS.forEach((table, columns) -> columns.stream()
                .map(column -> table + "." + column)
                .filter(entry -> !actual.contains(entry))
                .forEach(staleDrops::add));
        assertThat(staleDrops)
                .as("""
                        DROPPED_COLUMNS entries naming no existing column. A renamed secret column \
                        would be exported in full while this list still looked complete.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the six known secrets are on the dropped list, by name")
    void theSixKnownSecretsAreOnTheDroppedList() {
        // Asserted literally, and this is the arm that fails if somebody "simplifies"
        // DROPPED_COLUMNS while the sweep above still passes because the table got excluded
        // instead — which would take api_token's name, prefix and revocation date with it.
        assertThat(ExportRedactions.droppedFrom("app_user")).contains("password_hash");
        assertThat(ExportRedactions.droppedFrom("api_token")).contains("token_hash");
        assertThat(ExportRedactions.droppedFrom("membership_invitation")).contains("token_hash");
        assertThat(ExportRedactions.droppedFrom("notification_channel")).contains("secret");
        assertThat(ExportRedactions.droppedFrom("identity_provider"))
                .contains("client_secret_encrypted", "bind_password_encrypted");
    }

    @Test
    @DisplayName("the four content hashes and the secret hint are kept, not swept away")
    void theContentAddressesSurvive() {
        // The reason the redaction list is enumerated rather than a `%hash%` pattern. Losing these
        // destroys the integrity check on every version snapshot in the archive.
        assertThat(ExportRedactions.DELIBERATELY_KEPT)
                .containsKeys("test_case_version.content_hash", "suite_version.content_hash",
                        "test_plan_version.content_hash", "doc_page_version.content_hash",
                        "notification_channel.secret_hint", "api_token.token_prefix");
        assertThat(ExportRedactions.droppedFrom("test_case_version")).isEmpty();
        assertThat(ExportRedactions.droppedFrom("notification_channel"))
                .doesNotContain("secret_hint");
    }

    @Test
    @DisplayName("every DELIBERATELY_KEPT entry carries a reason")
    void everyKeepCarriesAReason() {
        // The rule SchemaConventionTest.INSTANCE_LEVEL_TABLES carries in comments, made mechanical
        // here because this map's values ARE the reasons and an empty one is an exemption nobody
        // has to justify.
        assertThat(ExportRedactions.DELIBERATELY_KEPT)
                .allSatisfy((entry, reason) -> assertThat(reason)
                        .as("%s is kept with no reason given", entry)
                        .isNotBlank());
    }

    /** Every {@code (table, column)} in the public schema. */
    private static List<Map.Entry<String, String>> allColumns() throws SQLException {
        List<Map.Entry<String, String>> columns = new ArrayList<>();
        String sql = """
                SELECT c.table_name, c.column_name
                  FROM information_schema.columns c
                  JOIN pg_tables t ON t.schemaname = c.table_schema AND t.tablename = c.table_name
                 WHERE c.table_schema = 'public'
                 ORDER BY c.table_name, c.column_name
                """;
        try (Statement statement = catalogConnection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                columns.add(Map.entry(rs.getString(1), rs.getString(2)));
            }
        }
        assertThat(columns)
                .as("fixture precondition: the catalogue must have been read, or the sweep "
                        + "asserts nothing")
                .isNotEmpty();
        return columns;
    }
}
