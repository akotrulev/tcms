package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * {@code /projects/{projectId}/boards} and {@code /boards/{boardId}} — the five configuration
 * operations, over the wire.
 *
 * <h2>The two gates are different, and the contract says so by which statuses it declares</h2>
 *
 * <p>Neither read declares a 403. {@code board:read} is held by all six project roles — a board is
 * a lens over work the caller can already see — so a project grant already implies it and a 403 arm
 * would be unreachable. The three writes declare one, and the key is {@code board:configure}:
 * <strong>there is no {@code board:move}</strong> (§A3 line 260), because a second key for an act an
 * existing key already covers is a permission somebody can hold without being able to use it.
 *
 * <p>Test Lead is the interesting denial rather than Viewer, and it is a decision rather than an
 * accident: D3 gave Test Lead {@code board:read} and no more, so a project staffed with a Test Lead
 * and no Project Manager cannot configure a board at all. That is recorded at {@code V2}'s grant
 * block, and this is what it looks like from the outside.
 */
@AutoConfigureMockMvc
@DisplayName("Board configuration endpoints — read by everyone, written by board:configure")
class BoardConfigEndpointTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("b4000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("b4001000-0000-4000-8000-000000000001");

    /** Project Manager. {@code Role} names only the roles production code branches on. */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("b4002000-0000-4000-8000-000000000001");
    private static final UUID LEAD = UUID.fromString("b4002000-0000-4000-8000-000000000002");

    private static final UUID MANAGER_GRANT = UUID.fromString("b4003000-0000-4000-8000-000000000001");
    private static final UUID LEAD_GRANT = UUID.fromString("b4003000-0000-4000-8000-000000000002");

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "board-endpoint-acme", "Acme", now);
            activeUser(MANAGER, "pm@board-endpoint.test", "Paula Manager", now);
            activeUser(LEAD, "lead@board-endpoint.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "BEND", "Board Endpoint Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(MANAGER_GRANT, ACME, PM_ROLE, MANAGER, PROJECT);
        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
        clearIssues(List.of(ACME));
        restoreBoards();
    }

    @AfterEach
    void putTheBoardsBack() throws SQLException {
        clearIssues(List.of(ACME));
        restoreBoards();
    }

    @Test
    @DisplayName("a project arrives with a board nobody had to configure")
    void aProjectArrivesWithABoardNobodyHadToConfigure() throws Exception {
        JsonNode boards = json.readTree(get(MANAGER, "/projects/" + PROJECT + "/boards")
                .getResponse().getContentAsString());

        assertThat(boards).hasSize(1);
        assertThat(boards.get(0).get("name").asText()).isEqualTo("Team board");
        assertThat(boards.get(0).get("columnCount").asInt())
                .as("one column per status, which is what makes a drop unambiguous")
                .isEqualTo(6);
        assertThat(boards.get(0).get("swimlaneBy").asText()).isEqualTo("EPIC");
    }

    @Test
    @DisplayName("the configuration read carries the columns in position order, statuses in workflow order")
    void theConfigurationReadCarriesTheColumnsInOrder() throws Exception {
        JsonNode board = json.readTree(
                get(MANAGER, "/boards/" + seededBoardId()).getResponse().getContentAsString());

        List<String> columnNames = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        board.get("columns").forEach(column -> {
            columnNames.add(column.get("name").asText());
            positions.add(column.get("position").asInt());
            assertThat(column.get("statuses"))
                    .as("a column claiming nothing is a column no card can ever be in")
                    .isNotEmpty();
            assertThat(column.get("defaultStatusId").asText())
                    .as("the default is always one of the statuses the column claims")
                    .isEqualTo(column.get("statuses").get(0).get("id").asText());
        });

        assertThat(columnNames)
                .containsExactly("Backlog", "Ready", "In Progress", "In Review", "Blocked", "Done");
        assertThat(positions).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(board.get("projectId").asText()).isEqualTo(PROJECT.toString());
    }

    @Test
    @DisplayName("a Test Lead reads the boards and cannot configure one")
    void aTestLeadReadsTheBoardsAndCannotConfigureOne() throws Exception {
        assertThat(get(LEAD, "/projects/" + PROJECT + "/boards").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(get(LEAD, "/boards/" + seededBoardId()).getResponse().getStatus())
                .isEqualTo(200);

        assertThat(post(LEAD, "/projects/" + PROJECT + "/boards", coarseBoardBody())
                .getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a second board is created with 201 and the project then has two")
    void aSecondBoardIsCreatedAndTheProjectThenHasTwo() throws Exception {
        MvcResult created = post(MANAGER, "/projects/" + PROJECT + "/boards", coarseBoardBody());

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode board = json.readTree(created.getResponse().getContentAsString());
        assertThat(board.get("name").asText()).isEqualTo("Stakeholder board");
        assertThat(board.get("columns")).hasSize(3);
        assertThat(board.get("columns").get(1).get("statuses"))
                .as("a folded column claims three statuses and shows a card in any of them")
                .hasSize(3);

        assertThat(json.readTree(get(MANAGER, "/projects/" + PROJECT + "/boards")
                .getResponse().getContentAsString()))
                .hasSize(2);
    }

    @Test
    @DisplayName("a board leaving a status unclaimed is refused with 422 and problem+json")
    void aBoardLeavingAStatusUnclaimedIsRefusedWith422() throws Exception {
        String body = """
                {"name":"Half a board","swimlaneBy":"NONE","columns":[
                  {"name":"Everything else","statusIds":["%s"],"defaultStatusId":"%s"}]}
                """.formatted(statusId("Backlog"), statusId("Backlog"));

        MvcResult refused = post(MANAGER, "/projects/" + PROJECT + "/boards", body);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentType())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(json.readTree(refused.getResponse().getContentAsString()).get("detail").asText())
                .contains("Done");
    }

    @Test
    @DisplayName("deleting the project's only board is refused with 422; a second one deletes with 204")
    void deletingTheOnlyBoardIsRefusedAndASecondOneDeletes() throws Exception {
        assertThat(delete(MANAGER, "/boards/" + seededBoardId()).getResponse().getStatus())
                .isEqualTo(422);

        UUID second = UUID.fromString(json.readTree(
                post(MANAGER, "/projects/" + PROJECT + "/boards", coarseBoardBody())
                        .getResponse().getContentAsString())
                .get("id").asText());

        assertThat(delete(MANAGER, "/boards/" + second).getResponse().getStatus()).isEqualTo(204);
        assertThat(get(MANAGER, "/boards/" + second).getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a board of another company is a 404, not a 403")
    void aBoardOfAnotherCompanyIsA404() throws Exception {
        assertThat(get(MANAGER, "/boards/" + UUID.randomUUID()).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(patch(MANAGER, "/boards/" + UUID.randomUUID(), coarseBoardBody())
                .getResponse().getStatus())
                .isEqualTo(404);
    }

    // =====================================================================

    private MvcResult get(UUID userId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/api/v1" + path)
                .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    private MvcResult post(UUID userId, String path, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1" + path)
                .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn();
    }

    private MvcResult patch(UUID userId, String path, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.patch("/api/v1" + path)
                .header("Authorization", "Bearer " + tokenFor(userId, ACME))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn();
    }

    private MvcResult delete(UUID userId, String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete("/api/v1" + path)
                .header("Authorization", "Bearer " + tokenFor(userId, ACME)))
                .andReturn();
    }

    private String coarseBoardBody() throws SQLException {
        return """
                {"name":"Stakeholder board","swimlaneBy":"ASSIGNEE","columns":[
                  {"name":"Not started","statusIds":["%s","%s"],"defaultStatusId":"%s"},
                  {"name":"In flight","statusIds":["%s","%s","%s"],"defaultStatusId":"%s","wipLimit":4},
                  {"name":"Done","statusIds":["%s"],"defaultStatusId":"%s"}]}
                """.formatted(
                statusId("Backlog"), statusId("Ready"), statusId("Backlog"),
                statusId("In Progress"), statusId("In Review"), statusId("Blocked"),
                statusId("In Progress"),
                statusId("Done"), statusId("Done"));
    }

    private UUID statusId(String name) throws SQLException {
        return single("""
                SELECT id FROM issue_status
                 WHERE project_id = CAST(? AS uuid) AND name = ?
                """, name);
    }

    private UUID seededBoardId() throws SQLException {
        return single("SELECT id FROM board WHERE project_id = CAST(? AS uuid) ORDER BY name", null);
    }

    private UUID single(String sql, String name) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, PROJECT);
            if (name != null) {
                statement.setString(2, name);
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("the fixture found no row for: " + sql);
                }
                return UUID.fromString(rows.getString(1));
            }
        }
    }

    /**
     * Drops every board of the project and re-seeds the one {@code tcms_seed_project_defaults}
     * makes.
     *
     * <p>The board rows go first because the seed function's board block is guarded by
     * {@code NOT EXISTS (SELECT 1 FROM board …)} — a project that still has a board row keeps
     * whatever columns the last method left on it.
     */
    private void restoreBoards() throws SQLException {
        try (Connection connection = appConnection(ACME)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM board WHERE project_id = CAST(? AS uuid)")) {
                statement.setObject(1, PROJECT);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                statement.setObject(1, ACME);
                statement.setObject(2, PROJECT);
                statement.execute();
            }
        }
    }
}
