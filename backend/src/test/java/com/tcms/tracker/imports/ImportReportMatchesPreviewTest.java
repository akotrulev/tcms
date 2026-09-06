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
import java.util.List;
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
 * D-f's equality: <strong>the report's dropped-field list is the preview's, word for word</strong>.
 *
 * <p>This is the module's stated done-when and it is an equality rather than a spot check on
 * purpose. The whole promise of the report is that a year from now, with the source system gone, it
 * still says exactly what the person was told <em>before</em> they pressed Confirm. Two derivations
 * would be two lists that agree today and diverge the first time somebody edits one — so there is
 * one, {@link DroppedFields}, the preview serves what it returns and the run persists it, and this
 * class is what holds the two responses to each other.
 *
 * <p>Including the reason strings, and that is the part worth stating: the reason is stored on the
 * row rather than looked up at read time, so editing the catalogue cannot retroactively rewrite a
 * report. An assertion on names and counts alone would pass over exactly that.
 */
@AutoConfigureMockMvc
@DisplayName("The report's dropped-field list is the preview's, element for element — D-f")
class ImportReportMatchesPreviewTest extends ImportTestBase {

    private static final UUID ACME = UUID.fromString("14400000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("14401000-0000-4000-8000-000000000001");

    private static final UUID LEAD = UUID.fromString("14402000-0000-4000-8000-000000000001");
    private static final UUID LEAD_GRANT = UUID.fromString("14403000-0000-4000-8000-000000000001");

    private static final int SOURCE_ISSUES = 4;

    @Autowired
    private FakeImportSource source;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "import-report-acme", "Acme", now);
            activeUser(LEAD, "lead@import-report.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "IMRP", "Import Report", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        clearImports();

        source.reset()
                .holding(new SourceProject("PROJ", "Northwind Storefront", SOURCE_ISSUES),
                        snapshot("PROJ", SOURCE_ISSUES));
    }

    private void clearImports() throws SQLException {
        try (Connection connection = appConnection(ACME);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM import_dropped_field");
            statement.executeUpdate("DELETE FROM import_run");
            statement.executeUpdate("DELETE FROM issue_label_assignment");
            statement.executeUpdate("DELETE FROM issue_label");
            statement.executeUpdate("DELETE FROM \"release\"");
        }
    }

    @Test
    @DisplayName("the report's dropped-field list is the preview's, word for word")
    void theReportMatchesThePreview() throws Exception {
        JsonNode preview = preview();
        JsonNode report = awaitTerminal(LEAD, ACME, start());

        assertThat(report.path("status").asText())
                .as("body was: %s", report)
                .isEqualTo("COMPLETED");

        assertThat(report.path("droppedFields"))
                .as("""
                    One derivation feeds both (DroppedFields, D-f). Two would be two derivations \
                    that agree today — and the whole promise of the report is that a year from now, \
                    with the source system decommissioned, it still says what the person was told \
                    before they confirmed.""")
                .isEqualTo(preview.path("droppedFields"));

        assertThat(report.path("droppedFields")).isNotEmpty();
        assertThat(report.path("droppedValueCount").asInt())
                .as("the tile counts VALUES lost, not field types — the question 009 t25 says a "
                        + "rendered paragraph cannot answer")
                .isEqualTo(SOURCE_ISSUES * 2);
    }

    @Test
    @DisplayName("the rows carry the sentence, so editing the catalogue cannot rewrite a report")
    void theReasonIsStoredPerRow() throws Exception {
        awaitTerminal(LEAD, ACME, start());

        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT reason FROM import_dropped_field WHERE field_name = ?")) {
            statement.setString(1, "Story Points");
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("no row for Story Points").isTrue();
                assertThat(rows.getString(1))
                        .as("""
                            Stored, not looked up at read time. The sentence is part of what was \
                            said at the time — the argument issue.issue_key's comment makes about \
                            composed keys.""")
                        .isEqualTo("estimates here are hours; points have no conversion");
            }
        }
    }

    // =====================================================================

    private JsonNode preview() throws Exception {
        ImportPreviewInput input = new ImportPreviewInput(PROJECT, connection(), "PROJ");

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/imports/preview")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Starts the run with {@code labelPerSprint} off, which is what the preview assumed.
     *
     * <p>Deliberate rather than incidental: with it on, the {@code Sprint} row's <em>sentence</em>
     * changes to say what was kept in its place (D-h), and the two lists would then differ for a
     * reason that is correct. The row stays in the list either way, which is the half of D-h
     * {@link #theReportMatchesThePreview} would otherwise be unable to distinguish from a bug.
     */
    private UUID start() throws Exception {
        UUID target = firstStatusId();
        ImportInput input = new ImportInput(
                PROJECT,
                connection(),
                "PROJ",
                List.of(
                        new StatusMappingEntry("New", target),
                        new StatusMappingEntry("Awaiting Triage", target)),
                new ImportOptions(false, true));

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/api/v1/imports")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(input)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("body was: %s", result.getResponse().getContentAsString())
                .isEqualTo(202);
        return UUID.fromString(
                json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private UUID firstStatusId() throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM issue_status WHERE project_id = CAST(? AS uuid) "
                                + "ORDER BY position LIMIT 1")) {
            statement.setObject(1, PROJECT);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return UUID.fromString(rows.getString(1));
            }
        }
    }
}
