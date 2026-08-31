package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tcms.identity.Role;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * The issue-to-specification edge, end to end: {@code documentation} writes the link and
 * {@code tracker} mirrors it.
 *
 * <h2>The failures this class exists to catch, and why none of them throws</h2>
 *
 * <ol>
 *   <li><strong>A mirror that is patched rather than rebuilt.</strong> An upsert per row adds and
 *       updates correctly and cannot express a <em>withdrawal</em>, so an unlinked block goes on
 *       being rendered on the epic for ever. Nothing fails, and the only symptom is a claim
 *       somebody explicitly removed. {@link #anUnlinkedBlockLeavesTheMirror} is the assertion that
 *       tells the two implementations apart.</li>
 *   <li><strong>{@code tracker} writing {@code doc_link}.</strong> D-e: the write lives in the
 *       module that owns the table, and the only endpoint here is a read.</li>
 *   <li><strong>An excerpt refreshed to the current text.</strong> The panel quotes what was
 *       linked; replacing it silently would make {@code stale} a flag with nothing behind it.</li>
 *   <li><strong>A link into another project's page.</strong> RLS bounds the company, which is the
 *       wrong bound for a link — a sibling project's page is this company's.</li>
 * </ol>
 */
@AutoConfigureMockMvc
@DisplayName("Issue documentation links — documentation writes them, tracker mirrors them")
class IssueDocLinkTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("3b000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("3b001000-0000-4000-8000-000000000001");
    private static final UUID SIBLING = UUID.fromString("3b001000-0000-4000-8000-000000000002");

    private static final UUID LEAD = UUID.fromString("3b002000-0000-4000-8000-000000000001");

    private static final UUID LEAD_GRANT = UUID.fromString("3b003000-0000-4000-8000-000000000001");
    private static final UUID SIBLING_GRANT =
            UUID.fromString("3b003000-0000-4000-8000-000000000002");

    /**
     * A long enough paragraph that the 200-character bound actually cuts, so the ellipsis rule is
     * exercised rather than assumed.
     */
    private static final String LONG_STATEMENT =
            "A payment made with a card that expired between authorisation and capture is declined, "
                    + "and the customer is told which card it was and that it has expired, rather "
                    + "than being shown a generic failure they cannot act on from the checkout page.";

    @Autowired
    private DocLinkProjection projection;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "tracker-doclink-acme", "Acme", now);
            activeUser(LEAD, "lead@tracker-doclink.test", "Leonora Lead", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "DLNK", "Doc Link Project", now);
            seedProject(SIBLING, ACME, "DLNS", "Sibling Project", now);
            member(ACME, LEAD, "Leonora Lead", now);
        }));

        grantOnProject(LEAD_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, PROJECT);
        grantOnProject(SIBLING_GRANT, ACME, Role.TEST_LEAD_ID, LEAD, SIBLING);
        // The company's five key counters, which only provision-company writes in production. A doc
        // page allocates DOC-n from one of them, so without this every page create is a 500 naming
        // the counter — which reads as documentation being broken rather than as an unseeded
        // fixture.
        seedCompanyDefaults();
        seedProjectDefaults(ACME, PROJECT);
        seedProjectDefaults(ACME, SIBLING);
        clearIssues(List.of(ACME));
        clearLinks();
    }

    @Test
    void linkingAnEpicToABlockWritesAnIssueSourcedDocLinkAndMirrorsIt() throws Exception {
        UUID block = aBlockOn(PROJECT, "Expired cards", LONG_STATEMENT);
        String epic = anEpic("Checkout hardening");

        assertThat(link(PROJECT, pageOf(block), block, issueId(epic))).isEqualTo(201);
        assertThat(sourceTypesInDocLink()).containsExactly("ISSUE");

        refresh();

        JsonNode links = docLinks(epic);
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("targetKind").asText()).isEqualTo("DOC_BLOCK");
        assertThat(links.get(0).get("relationship").asText()).isEqualTo("VERIFIES");
        assertThat(links.get(0).get("pageTitle").asText()).isEqualTo("Expired cards");
        assertThat(links.get(0).get("blockId").asText()).isEqualTo(block.toString());
    }

    @Test
    void theExcerptIsWhatWasLinkedAndIsCutWithAnEllipsis() throws Exception {
        // A quotation trimmed without a mark is a claim about where the spec ends.
        UUID block = aBlockOn(PROJECT, "Expired cards", LONG_STATEMENT);
        String epic = anEpic("Checkout hardening");
        link(PROJECT, pageOf(block), block, issueId(epic));

        refresh();

        String excerpt = docLinks(epic).get(0).get("blockExcerpt").asText();
        assertThat(excerpt).hasSize(200).endsWith("…").startsWith("A payment made with a card");
    }

    @Test
    void anUnlinkedBlockLeavesTheMirror() throws Exception {
        // THE test of this class. A per-row upsert cannot see a link that was REMOVED, so it adds
        // and updates correctly for ever and goes on asserting a claim somebody withdrew.
        UUID block = aBlockOn(PROJECT, "Expired cards", LONG_STATEMENT);
        String epic = anEpic("Checkout hardening");
        link(PROJECT, pageOf(block), block, issueId(epic));
        refresh();
        assertThat(docLinks(epic)).hasSize(1);

        unlinkEverything();
        refresh();

        assertThat(docLinks(epic)).isEmpty();
    }

    @Test
    void aPageEditedAfterTheIssueMakesTheLinkStale() throws Exception {
        // Story H1: an epic linked to a spec block says so when the spec moves underneath it. PAGE
        // granularity, because doc_block carries no timestamp of its own.
        UUID block = aBlockOn(PROJECT, "Expired cards", LONG_STATEMENT);
        String epic = anEpic("Checkout hardening");
        link(PROJECT, pageOf(block), block, issueId(epic));

        refresh();
        assertThat(docLinks(epic).get(0).get("stale").asBoolean()).isFalse();

        touchPageVersion(pageOf(block));
        refresh();

        assertThat(docLinks(epic).get(0).get("stale").asBoolean()).isTrue();
    }

    @Test
    void aLinkToASiblingProjectsPageIsRefusedRatherThanMirrored() throws Exception {
        // RLS bounds the statement to the COMPANY, which is the wrong bound for a link: the sibling
        // project's page is this company's, so RLS returns it and only the project predicate stops
        // its title rendering on this epic.
        UUID elsewhere = aBlockOn(SIBLING, "Somebody else's spec", LONG_STATEMENT);
        String epic = anEpic("Checkout hardening");

        assertThat(link(PROJECT, pageOf(elsewhere), elsewhere, issueId(epic))).isEqualTo(404);

        refresh();
        assertThat(docLinks(epic)).isEmpty();
    }

    @Test
    void aSoftDeletedIssuesLinksLeaveTheMirror() throws Exception {
        UUID block = aBlockOn(PROJECT, "Expired cards", LONG_STATEMENT);
        String epic = anEpic("Checkout hardening");
        link(PROJECT, pageOf(block), block, issueId(epic));
        refresh();

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/issues/" + epic)
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();
        refresh();

        assertThat(mirroredLinkCount()).isZero();
    }

    // =====================================================================
    // Request helpers
    // =====================================================================

    private void refresh() {
        inCompany(ACME, () -> projection.refresh());
    }

    private String anEpic(String title) throws Exception {
        MvcResult workflow = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/projects/" + PROJECT + "/workflow")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();
        assertThat(workflow.getResponse().getStatus())
                .as(workflow.getResponse().getContentAsString())
                .isEqualTo(200);
        UUID epicType = null;
        for (JsonNode type : json.readTree(workflow.getResponse().getContentAsString()).get("types")) {
            if (type.get("name").asText().equals("Epic")) {
                epicType = UUID.fromString(type.get("id").asText());
            }
        }
        assertThat(epicType).as("the seeded workflow has an Epic type").isNotNull();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", PROJECT.toString());
        payload.put("typeId", epicType.toString());
        payload.put("title", title);

        MvcResult created = mvc.perform(MockMvcRequestBuilders.post("/api/v1/issues")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(payload)))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(created.getResponse().getContentAsString()).get("key").asText();
    }

    private UUID aBlockOn(UUID projectId, String title, String text) throws Exception {
        String payload = "{\"title\":" + json.writeValueAsString(title)
                + ",\"blocks\":[{\"blockType\":\"paragraph\",\"text\":"
                + json.writeValueAsString(text) + "}]}";

        MvcResult created = mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/doc-pages")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("blocks").get(0).get("id").asText());
    }

    private int link(UUID projectId, UUID pageId, UUID blockId, UUID issueId) throws Exception {
        return mvc.perform(MockMvcRequestBuilders
                        .post("/api/v1/projects/" + projectId + "/doc-pages/" + pageId
                                + "/blocks/" + blockId + "/links")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "sourceType", "ISSUE", "sourceId", issueId.toString()))))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode docLinks(String key) throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders
                        .get("/api/v1/issues/" + key + "/doc-links")
                        .header("Authorization", "Bearer " + tokenFor(LEAD, ACME)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    // =====================================================================
    // Database helpers — the source table is another module's and has no read endpoint here
    // =====================================================================

    private UUID issueId(String key) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM issue WHERE issue_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("issue %s exists", key).isTrue();
                return (UUID) rows.getObject(1);
            }
        }
    }

    private UUID pageOf(UUID blockId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT page_id FROM doc_block WHERE id = CAST(? AS uuid)")) {
            statement.setObject(1, blockId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as("block %s exists", blockId).isTrue();
                return (UUID) rows.getObject(1);
            }
        }
    }

    private List<String> sourceTypesInDocLink() throws SQLException {
        List<String> types = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement =
                        connection.prepareStatement("SELECT source_type FROM doc_link");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                types.add(rows.getString(1));
            }
        }
        return types;
    }

    private long mirroredLinkCount() throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM issue_doc_link");
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Unlinks through the table rather than the endpoint — the projection is what is under test. */
    private void unlinkEverything() throws SQLException {
        execute("DELETE FROM doc_link");
    }

    private void clearLinks() throws SQLException {
        execute("DELETE FROM issue_doc_link");
        execute("DELETE FROM doc_link");
    }

    /**
     * A page version newer than the issue, which is what {@code stale} compares.
     *
     * <p>Written directly because the alternative — editing the page through its endpoint — also
     * moves the issue's own {@code updated_at} in some orderings, and the point of this test is the
     * comparison rather than the editing.
     */
    private void touchPageVersion(UUID pageId) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO doc_page_version
                            (id, company_id, page_id, version_no, snapshot, content_hash,
                             created_at)
                        VALUES (gen_random_uuid(), CAST(? AS uuid), CAST(? AS uuid),
                                (SELECT COALESCE(max(version_no), 0) + 1 FROM doc_page_version
                                  WHERE page_id = CAST(? AS uuid)),
                                '{}'::jsonb, 'test-touch', now() + interval '1 minute')
                        """)) {
            statement.setObject(1, ACME);
            statement.setObject(2, pageId);
            statement.setObject(3, pageId);
            statement.executeUpdate();
        }
    }

    private void seedCompanyDefaults() throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setObject(1, ACME);
            statement.execute();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
