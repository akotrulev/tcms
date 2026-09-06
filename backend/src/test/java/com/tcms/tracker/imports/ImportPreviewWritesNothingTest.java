package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * The test this module is judged on: <strong>a preview leaves the project byte-identical</strong>.
 *
 * <h2>Why an observer and not a convention</h2>
 *
 * <p>{@code import-preview.html} says <em>"No data has been written. Leaving this page now changes
 * nothing in Acme Storefront — not one issue, status or label"</em>, at the moment somebody is
 * deciding whether to trust this product with a migration off a system they have been on for years.
 * D32 makes that promise the preview's whole reason for existing. A convention is what gets
 * forgotten — one repository {@code save} inside a mapping helper, added a slice later by somebody
 * who never read the screen — so this is the thing that notices.
 *
 * <h2>The table list comes from {@code information_schema}, not from an array here</h2>
 *
 * <p>A hand-kept list is a list that covers the tables somebody remembered on the day. Every table
 * with a {@code company_id} column is company-owned by ERD §0.1's rule, so the same rule that
 * decides what RLS protects decides what this compares — and a table added in a later migration is
 * covered without anybody remembering.
 *
 * <p>Read as the container superuser, which bypasses {@code FORCE ROW LEVEL SECURITY}. Reading as
 * {@code tcms_app} would compare zero rows to zero rows for every table outside the open company
 * and report success by fail-closed silence.
 */
@AutoConfigureMockMvc
@DisplayName("A preview writes nothing — D32, and the promise import-preview.html makes")
class ImportPreviewWritesNothingTest extends ImportTestBase {

    private static final UUID ACME = UUID.fromString("14100000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("14101000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("14102000-0000-4000-8000-000000000001");
    private static final UUID LEAD_GRANT = UUID.fromString("14103000-0000-4000-8000-000000000001");

    /** Enough issues that a write of one of them would move a hash; small enough to stay fast. */
    private static final int SOURCE_ISSUES = 6;

    @Autowired
    private FakeImportSource source;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "import-preview-acme", "Acme", now);
            activeUser(LEAD, "lead@import-preview.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "IMPV", "Import Preview", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        clearLabels();

        source.reset()
                .holding(new SourceProject("PROJ", "Northwind Storefront", SOURCE_ISSUES),
                        snapshot("PROJ", SOURCE_ISSUES));
    }

    /**
     * Two of the methods below write, so the class empties what they wrote before each one.
     *
     * <p>Per method rather than per class, for {@code clearIssues}' reason: a collision seeded by
     * one method would otherwise make another method's blocker list depend on which ran first.
     */
    private void clearLabels() throws SQLException {
        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM issue_label_assignment");
            statement.executeUpdate("DELETE FROM issue_label");
        }
    }

    @Test
    @DisplayName("a preview leaves every company-owned table byte-identical")
    void aPreviewWritesNothing() throws Exception {
        Map<String, String> before = snapshotOfEveryCompanyOwnedTable();

        JsonNode preview = preview(200);

        assertThat(preview.path("totals").path("issues").asInt())
                .as("it really did read — a preview that wrote nothing because it did nothing "
                        + "would pass every other assertion in this class")
                .isEqualTo(SOURCE_ISSUES);

        assertThat(snapshotOfEveryCompanyOwnedTable())
                .as("""
                    import-preview.html promises "No data has been written. Leaving this page now \
                    changes nothing in Acme Storefront — not one issue, status or label", and D32 \
                    makes that promise the whole reason the preview exists. A convention is what \
                    gets forgotten; this is the observer.""")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the fixture can see a write, so the comparison above is not vacuous")
    void theSnapshotNoticesAWrite() throws Exception {
        Map<String, String> before = snapshotOfEveryCompanyOwnedTable();

        // Without this the rule above is "" equals "" for every table if the query ever stops
        // finding rows — the failure mode ImportUrlGuardMirrorsTheOutboundGuardTest carries its
        // own precondition test for.
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO issue_label (id, company_id, project_id, name, color) "
                                + "VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), "
                                + "'written-by-the-fixture', '#123456')")) {
            statement.setObject(1, ACME);
            statement.setObject(2, PROJECT);
            statement.executeUpdate();
        }

        assertThat(snapshotOfEveryCompanyOwnedTable()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("the mapping is a suggestion: a name that matches, and one that does not")
    void statusesAreSuggestedAndNeverDecided() throws Exception {
        JsonNode statuses = preview(200).path("statuses");

        JsonNode backlog = rowFor(statuses, "New");
        assertThat(backlog.path("matched").asBoolean()).isTrue();
        assertThat(backlog.path("suggestedStatusId").isNull()).isFalse();

        JsonNode unknown = rowFor(statuses, "Awaiting Triage");
        assertThat(unknown.path("matched").asBoolean())
                .as("the seeded workflow has no status of that name, and the mapping step shows "
                        + "it unmapped rather than defaulting it into the wrong column")
                .isFalse();
        assertThat(unknown.path("suggestedStatusId").isNull()).isTrue();
    }

    @Test
    @DisplayName("the dropped-field list names a custom field the way the report will (D-f)")
    void droppedFieldsCarryTheirReason() throws Exception {
        JsonNode dropped = preview(200).path("droppedFields");

        JsonNode points = rowFor(dropped, "Story Points", "name");
        assertThat(points.path("reason").asText()).contains("points have no conversion");
        assertThat(points.path("valueCount").asInt()).isEqualTo(SOURCE_ISSUES);

        assertThat(rowFor(dropped, "Custom: \"Squad\"", "name").path("reason").asText())
                .isEqualTo("no custom fields in this product");
    }

    @Test
    @DisplayName("a source key that already exists here is a blocker, not a run that dies at four")
    void acollidingKeyIsABlocker() throws Exception {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue
                            (id, company_id, project_id, key_seq, issue_key, type_id, status_id,
                             title, created_by, created_at, updated_at)
                        SELECT gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid), 9001, 'PROJ-1',
                               t.id, s.id, 'Already here', CAST(? AS uuid), now(), now()
                        FROM issue_type t, issue_status s
                        WHERE t.project_id = CAST(? AS uuid) AND s.project_id = CAST(? AS uuid)
                        LIMIT 1
                        """)) {
            statement.setObject(1, ACME);
            statement.setObject(2, PROJECT);
            statement.setObject(3, LEAD);
            statement.setObject(4, PROJECT);
            statement.setObject(5, PROJECT);
            statement.executeUpdate();
        }

        JsonNode blockers = preview(200).path("blockers");

        assertThat(blockers).hasSize(1);
        assertThat(blockers.get(0).path("kind").asText()).isEqualTo("KEY_COLLISION");
        assertThat(blockers.get(0).path("keys").get(0).asText()).isEqualTo("PROJ-1");
    }

    private JsonNode preview(int expected) throws Exception {
        ImportPreviewInput input = new ImportPreviewInput(PROJECT, connection(), "PROJ");

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/imports/preview")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(expected);

        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode rowFor(JsonNode rows, String name) {
        return rowFor(rows, name, "sourceStatusName");
    }

    private static JsonNode rowFor(JsonNode rows, String name, String field) {
        for (JsonNode row : rows) {
            if (name.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError("no row where " + field + " is '" + name + "' in " + rows);
    }

    /**
     * Every company-owned table, hashed.
     *
     * <p>Ordered by the row's own text rather than by a primary key, so the digest is stable
     * without this test needing to know each table's key — and so a table whose key is composite is
     * covered on the same terms as every other.
     */
    private static Map<String, String> snapshotOfEveryCompanyOwnedTable() throws SQLException {
        Map<String, String> digests = new LinkedHashMap<>();

        try (Connection connection = superuserConnection()) {
            for (String table : companyOwnedTables(connection)) {
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery(
                                "SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), "
                                        + "'empty') FROM \"" + table + "\" t")) {
                    rows.next();
                    digests.put(table, rows.getString(1));
                }
            }
        }
        assertThat(digests)
                .as("information_schema returned no company-owned tables at all — the query has "
                        + "stopped finding them, and every comparison in this class is vacuous")
                .hasSizeGreaterThan(50);
        return digests;
    }

    private static List<String> companyOwnedTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'company_id'
                ORDER BY table_name
                """);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        return tables;
    }
}
