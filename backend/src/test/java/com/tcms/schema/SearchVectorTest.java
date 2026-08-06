package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The V2 search vectors — the part of the schema static analysis explicitly could not
 * settle, per ERD §10 point 3.
 *
 * <h2>Why the two mechanisms differ, and why that is tested rather than trusted</h2>
 *
 * <p>{@code test_case}, {@code condition} and {@code shared_step} use
 * {@code GENERATED ... STORED}: all their searchable text is same-row, so a generated
 * column cannot be bypassed by any INSERT path and cannot fall out of step with the row.
 * {@code doc_page} cannot — a page's text lives in {@code doc_block} rows, and a
 * generation expression may only read its own row — so it is trigger-maintained from both
 * sides. That asymmetry is the interesting part of this file: the trigger path has failure
 * modes (a missed DELETE, a recursion, a stale vector after a title edit) that the
 * generated path structurally cannot have, so each one gets an assertion.
 *
 * <p>The weighting matters as much as the content. Plan §6h/§6q require title {@code 'A'}
 * and body {@code 'B'} so a match in the title outranks one buried in step three, and
 * {@code ts_rank} cannot recover a weight that was never stored — an unweighted vector
 * looks completely correct in a {@code @@} test and quietly ranks wrong forever.
 */
@DisplayName("Search vectors — generated columns and the doc_page triggers (V2)")
class SearchVectorTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("5e00c400-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("5e00c400-0000-4000-8000-000000000002");
    private static final UUID SUITE = UUID.fromString("5e00c400-0000-4000-8000-000000000003");
    private static final UUID CASE = UUID.fromString("5e00c400-0000-4000-8000-000000000004");
    private static final UUID SPACE = UUID.fromString("5e00c400-0000-4000-8000-000000000005");
    private static final UUID PAGE = UUID.fromString("5e00c400-0000-4000-8000-000000000006");
    private static final UUID BLOCK = UUID.fromString("5e00c400-0000-4000-8000-000000000007");

    private static Connection connection;

    /**
     * {@code @BeforeEach} rather than {@code @BeforeAll}: the Spring context — and so
     * Flyway, and so the {@code tcms_app} role created by its test-only callback — is built
     * when the test instance is post-processed, which is after a static {@code @BeforeAll}
     * would already have tried to connect. The symptom is a misleading
     * "password authentication failed for user tcms_app".
     */
    @BeforeEach
    void seed() throws SQLException {
        if (connection != null) {
            return;
        }
        connection = appConnection(COMPANY);
        execute("""
                INSERT INTO company (id, slug, display_name)
                VALUES ('%s', 'searchco', 'Search Co') ON CONFLICT DO NOTHING
                """.formatted(COMPANY));
        execute("""
                INSERT INTO project (id, company_id, key, name)
                VALUES ('%s', '%s', 'SRCH', 'Search Fixture') ON CONFLICT DO NOTHING
                """.formatted(PROJECT, COMPANY));
        execute("""
                INSERT INTO suite (id, company_id, project_id, key, key_seq, name)
                VALUES ('%s', '%s', '%s', 'TS-1', 1, 'Root') ON CONFLICT DO NOTHING
                """.formatted(SUITE, COMPANY, PROJECT));
    }

    @AfterAll
    static void close() throws SQLException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Test
    @DisplayName("test_case.search_vector is generated, weighted, and GIN-indexed")
    void testCaseVectorIsGeneratedAndWeighted() throws SQLException {
        execute("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title, description, objective)
                VALUES ('%s', '%s', '%s', '%s', 'TC-1', 1,
                        'Partial refund of a settled card payment',
                        'The gateway returns a partial capture receipt',
                        'Prove settlement adjusts')
                ON CONFLICT DO NOTHING
                """.formatted(CASE, COMPANY, PROJECT, SUITE));

        String vector = queryOne(
                "SELECT search_vector::text FROM test_case WHERE id = '" + CASE + "'");

        // Written with no reference to search_vector at all: a generated column has no
        // INSERT path that can skip it, which is the entire reason three of the four
        // tables use one.
        assertThat(vector).as("a generated column populates on INSERT without being named").isNotEmpty();
        assertThat(vector)
                .as("title terms carry weight A")
                .contains("'refund':2A")
                .contains("'payment':7A");
        assertThat(vector)
                .as("description and objective carry weight B — an unweighted vector still matches "
                        + "but ranks wrong forever, and ts_rank cannot recover the lost weight")
                .contains("'gateway':9B")
                .contains("'settlement'");

        assertThat(isGenerated("test_case", "search_vector"))
                .as("""
                        test_case.search_vector must stay GENERATED. If a later migration converts it \
                        to a plain trigger-maintained column, every INSERT path becomes a place the \
                        vector can be forgotten — the failure mode is a case that simply never \
                        appears in search, which nothing else in the suite would notice.""")
                .isTrue();

        assertThat(indexesOn("test_case"))
                .as("dropping the column drops its GIN index silently — V2 recreates it, and a "
                        + "search_vector with no GIN index means every search sequential-scans")
                .contains("test_case_search_idx");
    }

    @Test
    @DisplayName("the generated vector follows an UPDATE with no trigger involved")
    void generatedVectorFollowsUpdates() throws SQLException {
        execute("""
                INSERT INTO test_case
                    (id, company_id, project_id, suite_id, key, key_seq, title)
                VALUES ('%s', '%s', '%s', '%s', 'TC-2', 2, 'Chargeback reconciliation')
                """.formatted(UUID.randomUUID(), COMPANY, PROJECT, SUITE));

        execute("UPDATE test_case SET title = 'Dispute reconciliation' WHERE key = 'TC-2'");

        String vector = queryOne("SELECT search_vector::text FROM test_case WHERE key = 'TC-2'");
        assertThat(vector).contains("'disput':1A");
        assertThat(vector).as("the old title must be gone, not merely joined").doesNotContain("chargeback");
    }

    @Test
    @DisplayName("condition indexes JSONB string leaves, not JSON keys")
    void conditionVectorIndexesStringLeavesOnly() throws SQLException {
        UUID condition = UUID.randomUUID();
        execute("""
                INSERT INTO condition (id, company_id, project_id, title, type, body)
                VALUES ('%s', '%s', '%s', 'Log in as standard user', 'PRE',
                        '{"steps":[{"action":"Enter valid credentials"}]}'::jsonb)
                """.formatted(condition, COMPANY, PROJECT));

        String vector = queryOne(
                "SELECT search_vector::text FROM condition WHERE id = '" + condition + "'");

        assertThat(vector).as("title at A").contains("'log':1A");
        assertThat(vector).as("body strings at B").contains("'credenti':8B");
        assertThat(vector)
                .as("""
                        The '["string"]' filter on jsonb_to_tsvector is what keeps JSON KEYS out of \
                        the vector. Without it "action" and "steps" become search terms present in \
                        every single row, so every query matches everything and relevance collapses \
                        — while the index still looks perfectly healthy.""")
                .doesNotContain("'action'")
                .doesNotContain("'step'");
    }

    @Test
    @DisplayName("doc_page's vector is maintained from BOTH the page and its blocks")
    void docPageVectorIsMaintainedFromBothSides() throws SQLException {
        execute("""
                INSERT INTO doc_space (id, company_id, project_id, name)
                VALUES ('%s', '%s', '%s', 'Handbook') ON CONFLICT DO NOTHING
                """.formatted(SPACE, COMPANY, PROJECT));
        execute("""
                INSERT INTO doc_page
                    (id, company_id, space_id, project_id, key, key_seq, path, slug, title)
                VALUES ('%s', '%s', '%s', '%s', 'DOC-1', 1, 'refunds', 'refunds', 'Refund policy')
                """.formatted(PAGE, COMPANY, SPACE, PROJECT));

        // On INSERT there are no blocks yet, so the page is title-only. This is the state a
        // generated column would have been stuck in permanently.
        assertThat(pageVector()).contains("'refund':1A").doesNotContain("B");

        execute("""
                INSERT INTO doc_block (id, company_id, page_id, position, block_type, content)
                VALUES ('%s', '%s', '%s', 0, 'paragraph',
                        '{"text":"Partial refunds settle overnight"}'::jsonb)
                """.formatted(BLOCK, COMPANY, PAGE));
        assertThat(pageVector())
                .as("block content arrives at weight B via the block-side trigger")
                .contains("'overnight':6B");

        execute("UPDATE doc_block SET content = '{\"text\":\"Refunds are irreversible\"}'::jsonb "
                + "WHERE id = '" + BLOCK + "'");
        assertThat(pageVector())
                .as("an edited block replaces its terms rather than accumulating them")
                .contains("'irrevers'")
                .doesNotContain("overnight");

        execute("DELETE FROM doc_block WHERE id = '" + BLOCK + "'");
        assertThat(pageVector())
                .as("""
                        DELETE is the trigger case that gets forgotten, and forgetting it is silent: \
                        deleted text keeps matching, so search returns a page whose content no longer \
                        mentions the term. Only the title may remain.""")
                .contains("'refund':1A")
                .doesNotContain("irrevers");
    }

    @Test
    @DisplayName("a title edit re-reads the blocks rather than dropping them")
    void titleEditPreservesBlockTerms() throws SQLException {
        UUID page = UUID.randomUUID();
        execute("""
                INSERT INTO doc_page
                    (id, company_id, space_id, project_id, key, key_seq, path, slug, title)
                VALUES ('%s', '%s', '%s', '%s', 'DOC-2', 2, 'settlement', 'settlement',
                        'Settlement windows')
                """.formatted(page, COMPANY, SPACE, PROJECT));
        execute("""
                INSERT INTO doc_block (id, company_id, page_id, position, block_type, content)
                VALUES ('%s', '%s', '%s', 0, 'paragraph',
                        '{"text":"Batches close at midnight UTC"}'::jsonb)
                """.formatted(UUID.randomUUID(), COMPANY, page));

        execute("UPDATE doc_page SET title = 'Settlement and cutoff windows' WHERE id = '" + page + "'");

        String vector = queryOne(
                "SELECT search_vector::text FROM doc_page WHERE id = '" + page + "'");
        assertThat(vector).as("the new title").contains("'cutoff'");
        assertThat(vector)
                .as("""
                        and the block terms survive it. The page-side trigger recomputes from the \
                        blocks rather than from NEW alone; a naive title-only implementation wipes \
                        the body every time someone renames a page, which is invisible until \
                        somebody searches for text that is still plainly on screen.""")
                .contains("'midnight'");
    }

    @Test
    @DisplayName("doc_page has no generated column and every search_vector table has its GIN index")
    void doNotSilentlySwapTheMechanisms() throws SQLException {
        assertThat(isGenerated("doc_page", "search_vector"))
                .as("""
                        doc_page.search_vector must NOT be generated — a generation expression may \
                        only read its own row, so making it generated would silently reduce doc \
                        search to titles only. If Postgres ever permits cross-row generation, delete \
                        the triggers in the same migration rather than leaving both.""")
                .isFalse();

        assertThat(indexesOn("condition")).contains("condition_search_idx");
        assertThat(indexesOn("shared_step")).contains("shared_step_search_idx");
        assertThat(indexesOn("doc_page")).contains("doc_page_search_idx");
    }

    private static String pageVector() throws SQLException {
        return queryOne("SELECT search_vector::text FROM doc_page WHERE id = '" + PAGE + "'");
    }

    private static boolean isGenerated(String table, String column) throws SQLException {
        return "ALWAYS".equals(queryOne("""
                SELECT is_generated FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = '%s' AND column_name = '%s'
                """.formatted(table, column)));
    }

    private static Set<String> indexesOn(String table) throws SQLException {
        Set<String> indexes = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT indexname FROM pg_indexes WHERE tablename = '" + table + "'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        return indexes;
    }

    private static void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryOne(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("query returned no row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }
}
