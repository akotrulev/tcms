package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Counts the rows on each side of the {@code work_item → issue} migration in {@code V3}.
 *
 * <p>The point of doing it this way rather than by reading {@code V3}: the migration is one
 * {@code INSERT … SELECT} inside a {@code DO} block over a cursor, and the failure mode that
 * matters — a project whose rows silently did not move, because the outer loop read a
 * RLS-protected table with no company context, or because the status {@code CASE} had no arm for a
 * value that table actually holds — produces a shorter result set and no error at all. A reading of
 * the SQL cannot find that; a count on both sides can. Plan 009's done-when says so explicitly:
 * <em>"the work_item migration verified by counting rows either side, not by reading the SQL"</em>.
 *
 * <p><strong>Its own containers, and not {@code IntegrationTestBase}'s.</strong> The count before
 * has to be taken on a database migrated to {@code V2} and no further, which the shared container —
 * migrated to head before the first test runs — cannot offer. So each test here starts a container,
 * migrates to {@code V2}, seeds {@code work_item} rows, migrates to {@code V3} and counts. That is
 * slower than every other class in the suite, and it is the price of asserting over a table the
 * ordinary fixture has already migrated past.
 *
 * <p><strong>Seeded and read as the container's superuser.</strong> Every table involved carries
 * {@code FORCE ROW LEVEL SECURITY}, so a fixture written as {@code tcms_owner} would insert nothing
 * and count nothing, twice, and agree with itself. Flyway still migrates as {@code tcms_owner},
 * which is the whole point — the migration under test must clear the policy the way production
 * does.
 */
@DisplayName("work_item → issue — counted on both sides, not read")
class WorkItemMigrationTest {

    /**
     * The five members of {@code WorkItemStatus}. Every one appears in the fixture, so that a
     * status with no arm in {@code V3}'s {@code CASE} produces a NULL {@code status_id}, the
     * {@code NOT NULL} refuses the insert and the migration fails LOUDLY — which it can only do if
     * a row carrying that status exists to fail on.
     */
    private static final List<String> WORK_ITEM_STATUSES =
            List.of("TODO", "IN_PROGRESS", "IN_REVIEW", "BLOCKED", "DONE");

    /** Two companies, three projects between them — so a per-company loop that stops after the
     * first company is a shorter result set rather than an empty one. */
    private static final UUID COMPANY_A = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID COMPANY_B = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_A1 = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_A2 = UUID.fromString("b0000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_B1 = UUID.fromString("b0000000-0000-4000-8000-000000000003");
    private static final UUID USER_A = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID USER_B = UUID.fromString("c0000000-0000-4000-8000-000000000002");

    @Test
    @DisplayName("every work_item became an issue, and every link an issue_link")
    void everyWorkItemBecameAnIssue() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            long before = countAllRows(db, "work_item");
            long linksBefore = countAllRows(db, "work_item_link");
            assertThat(before)
                    .as("the fixture must hold work_item rows, or both counts are zero and agree")
                    .isGreaterThan(0);
            assertThat(linksBefore).isGreaterThan(0);

            migrateTo(db, "3");

            assertThat(countAllRows(db, "issue"))
                    .as("issues after the migration against work_items before it. A project whose "
                            + "rows did not move produces a shorter result set and NO ERROR — the "
                            + "outer loop reads work_item, which is RLS-protected, so a loop that "
                            + "forgot to set app.current_company migrates nothing and succeeds")
                    .isEqualTo(before);
            assertThat(countAllRows(db, "issue_link")).isEqualTo(linksBefore);

            // The soft-deleted row moved too. Migrating only the live ones would look right in
            // every screen and lose a row a downstream time_entry may already point at.
            assertThat(rowsWhere(db, "issue", "deleted_at IS NOT NULL")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("every migrated issue has a key and the story-level Task type")
    void everyMigratedIssueHasAKeyAndTheStoryLevelTaskType() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            migrateTo(db, "3");

            assertThat(rowsWhere(db, "issue", "issue_key IS NULL")).isZero();
            assertThat(distinctCount(db, "issue", "company_id, issue_key"))
                    .isEqualTo(countAllRows(db, "issue"));
            assertThat(rowsWhere(db, "issue i JOIN project p ON p.id = i.project_id",
                    "i.issue_key NOT LIKE p.key || '-%'")).isZero();

            // D12 and D29: the story-level type named Task, so "nothing you typed changed meaning
            // underneath you". Not the TASK-level Sub-task — these rows are parentless and the
            // depth rule refuses an orphan at that level.
            assertThat(typeNamesAndLevelsOfEveryIssue(db)).containsOnly(entry("Task", "STORY"));
            assertThat(rowsWhere(db, "issue", "parent_id IS NOT NULL")).isZero();
        }
    }

    @Test
    @DisplayName("keys are numbered oldest-first and the counter moved past them")
    void keysAreNumberedOldestFirstAndTheCounterMovedPastThem() throws Exception {
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            migrateTo(db, "3");

            // The oldest task in each project is -1, and the counter's next_value is one past the
            // highest key_seq — so the first issue created after the upgrade does not collide with
            // a migrated one, which is a unique-constraint violation on somebody's first use of the
            // feature.
            for (UUID projectId : projectIdsWithIssues(db)) {
                assertThat(keySeqInCreatedAtOrder(db, projectId))
                        .as("project %s numbers its issues in work_item.created_at order", projectId)
                        .isSorted();
                assertThat(keySeqInCreatedAtOrder(db, projectId).getFirst()).isEqualTo(1L);
                assertThat(nextValueOf(db, projectId)).isEqualTo(maxKeySeq(db, projectId) + 1);
            }
        }
    }

    @Test
    @DisplayName("a private note on a migrated task still resolves")
    void aPrivateNoteOnAMigratedTaskStillResolves() throws Exception {
        // D11: derived_item_annotation survives M1 and matters MORE, not less. Its rows are keyed
        // on (user_id, source_type, source_id), so the migration carrying work_item.id forward is
        // what keeps them pointing at something — minting fresh ids would have orphaned every
        // private note and personal due date in the instance, silently.
        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            UUID annotated = annotateSomeWorkItem(db);
            migrateTo(db, "3");

            assertThat(rowsWhere(db, "derived_item_annotation a JOIN issue i ON i.id = a.source_id",
                    "a.source_id = '" + annotated + "' AND a.source_type = 'ISSUE'")).isEqualTo(1);
            assertThat(rowsWhere(db, "derived_item_annotation", "source_type = 'TASK'"))
                    .as("'TASK' rows are relabelled rather than left to rot — the vocabulary is "
                            + "free text, so nothing would have complained")
                    .isZero();
        }
    }

    @Test
    @DisplayName("every work_item status has an arm in the mapping, and the fixture proves it")
    void everyWorkItemStatusHasAnArmInTheMapping() throws Exception {
        // The silent one, inverted into a loud one. A status with no CASE arm produces a NULL
        // status_id and the NOT NULL refuses the insert, failing the whole migration — but only if
        // a row with that status exists. So this asserts the fixture covers all five, which is what
        // makes the loud failure REACHABLE, and then asserts the destinations.
        assertThat(WORK_ITEM_STATUSES)
                .containsExactlyInAnyOrder("TODO", "IN_PROGRESS", "IN_REVIEW", "BLOCKED", "DONE");

        try (PostgreSQLContainer<?> db = freshDatabase()) {
            migrateTo(db, "2");
            seedWorkItems(db);
            migrateTo(db, "3");

            // TODO maps to Backlog and NOT to Ready: Ready claims the issue is refined and
            // estimated, which nothing about a pre-upgrade task supports.
            assertThat(statusNameByOriginalStatus(db)).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "TODO", "Backlog",
                    "IN_PROGRESS", "In Progress",
                    "IN_REVIEW", "In Review",
                    "BLOCKED", "Blocked",
                    "DONE", "Done"));

            // done_at is set for the DONE row in each of the three projects and for no other,
            // because a lead time is computed from it and a migrated task that was never done has
            // no honest value to put there.
            assertThat(rowsWhere(db, "issue", "done_at IS NOT NULL")).isEqualTo(3);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The container, the migrator and the fixture
    // ---------------------------------------------------------------------------------------

    private static PostgreSQLContainer<?> freshDatabase() {
        PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                .withDatabaseName("tcms")
                .withUsername("tcms_super")
                .withPassword("super-secret")
                // The same privilege split the rest of the suite runs under, for the same reason:
                // Flyway must migrate as a NON-superuser, or FORCE ROW LEVEL SECURITY is advisory
                // here and mandatory in production, and this class exists to test a migration whose
                // whole difficulty is that policy.
                .withInitScript("db/testinit/tcms-roles.sql");
        db.start();
        return db;
    }

    private static void migrateTo(PostgreSQLContainer<?> db, String version) {
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), "tcms_owner", "owner-secret")
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    /**
     * Two companies, three projects, and one {@code work_item} per status in each project.
     *
     * <p>Created at spaced {@code created_at} values in a deliberately shuffled insert order, so
     * that a migration numbering by insertion rather than by age is a failure and not a
     * coincidence.
     */
    private static void seedWorkItems(PostgreSQLContainer<?> db) throws SQLException {
        try (Connection connection = superuser(db); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO app_user (id, email, canonical_name, status) VALUES
                        ('%s', 'a@wim.test', 'A Person', 'ACTIVE'),
                        ('%s', 'b@wim.test', 'B Person', 'ACTIVE');
                    """.formatted(USER_A, USER_B));

            seedCompany(statement, COMPANY_A, "wim-a", Map.of(PROJECT_A1, "WIMA", PROJECT_A2, "WIMB"));
            seedCompany(statement, COMPANY_B, "wim-b", Map.of(PROJECT_B1, "WIMC"));
        }
    }

    private static void seedCompany(Statement statement, UUID companyId, String slug,
            Map<UUID, String> projects) throws SQLException {
        statement.execute("INSERT INTO company (id, slug, display_name) VALUES ('%s', '%s', '%s')"
                .formatted(companyId, slug, slug));
        // tcms_seed_project_defaults refuses to run without matching company context, deliberately
        // — it would otherwise write rows the policy then hides.
        statement.execute("SELECT set_config('app.current_company', '%s', false)".formatted(companyId));

        for (Map.Entry<UUID, String> project : projects.entrySet()) {
            statement.execute("""
                    INSERT INTO project (id, company_id, key, name) VALUES ('%s', '%s', '%s', '%s')
                    """.formatted(project.getKey(), companyId, project.getValue(), project.getValue()));
            statement.execute("SELECT tcms_seed_project_defaults('%s', '%s')"
                    .formatted(companyId, project.getKey()));

            // Newest first in insert order, oldest last: the row inserted LAST must be numbered -1.
            int age = 0;
            for (String status : WORK_ITEM_STATUSES.reversed()) {
                statement.execute("""
                        INSERT INTO work_item (id, company_id, project_id, title, description, kind,
                                               status, priority, due_date, assignee_id, created_at,
                                               created_by, updated_at, deleted_at)
                        VALUES (gen_random_uuid(), '%s', '%s', '%s in %s', 'seeded', 'TASK',
                                '%s', 'MEDIUM', DATE '2026-09-01', '%s',
                                TIMESTAMPTZ '2026-01-01 00:00:00Z' + INTERVAL '%d days',
                                '%s', now(), %s)
                        """.formatted(companyId, project.getKey(), status, project.getValue(),
                                status, USER_A, 100 - age, USER_B,
                                // One soft-deleted row in the whole fixture, on the BLOCKED card.
                                "BLOCKED".equals(status) && PROJECT_A1.equals(project.getKey())
                                        ? "now()" : "NULL"));
                age++;
            }

            statement.execute("""
                    INSERT INTO work_item_link (company_id, work_item_id, target_type, target_id)
                    SELECT company_id, id, 'TEST_CASE', gen_random_uuid()
                      FROM work_item WHERE project_id = '%s'
                    """.formatted(project.getKey()));
        }
        statement.execute("SELECT set_config('app.current_company', '', false)");
    }

    private static UUID annotateSomeWorkItem(PostgreSQLContainer<?> db) throws SQLException {
        try (Connection connection = superuser(db); Statement statement = connection.createStatement()) {
            UUID workItemId = UUID.fromString(single(statement,
                    "SELECT id::text FROM work_item WHERE project_id = '" + PROJECT_A1
                            + "' ORDER BY created_at LIMIT 1"));
            statement.execute("""
                    INSERT INTO derived_item_annotation
                        (company_id, user_id, source_type, source_id, note, personal_due_date, flagged)
                    VALUES ('%s', '%s', 'TASK', '%s', 'mine', DATE '2026-09-09', true)
                    """.formatted(COMPANY_A, USER_A, workItemId));
            return workItemId;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Ground truth, all of it as the superuser — see the class comment
    // ---------------------------------------------------------------------------------------

    private static long countAllRows(PostgreSQLContainer<?> db, String table) throws SQLException {
        return scalar(db, "SELECT count(*) FROM " + table);
    }

    private static long rowsWhere(PostgreSQLContainer<?> db, String from, String predicate)
            throws SQLException {
        return scalar(db, "SELECT count(*) FROM " + from + " WHERE " + predicate);
    }

    private static long distinctCount(PostgreSQLContainer<?> db, String table, String columns)
            throws SQLException {
        return scalar(db, "SELECT count(*) FROM (SELECT DISTINCT " + columns + " FROM " + table + ") d");
    }

    private static List<UUID> projectIdsWithIssues(PostgreSQLContainer<?> db) throws SQLException {
        return query(db, "SELECT DISTINCT project_id::text FROM issue ORDER BY 1").stream()
                .map(UUID::fromString)
                .toList();
    }

    private static List<Long> keySeqInCreatedAtOrder(PostgreSQLContainer<?> db, UUID projectId)
            throws SQLException {
        return query(db, "SELECT key_seq::text FROM issue WHERE project_id = '" + projectId
                + "' ORDER BY created_at, id").stream().map(Long::valueOf).toList();
    }

    private static long maxKeySeq(PostgreSQLContainer<?> db, UUID projectId) throws SQLException {
        return scalar(db, "SELECT max(key_seq) FROM issue WHERE project_id = '" + projectId + "'");
    }

    private static long nextValueOf(PostgreSQLContainer<?> db, UUID projectId) throws SQLException {
        return scalar(db, "SELECT next_value FROM project_key_sequence WHERE project_id = '"
                + projectId + "'");
    }

    private static Map<String, String> typeNamesAndLevelsOfEveryIssue(PostgreSQLContainer<?> db)
            throws SQLException {
        return pairs(db, """
                SELECT DISTINCT t.name, t.level FROM issue i JOIN issue_type t ON t.id = i.type_id
                """);
    }

    /** The original {@code work_item.status} against the {@code issue_status} it became. */
    private static Map<String, String> statusNameByOriginalStatus(PostgreSQLContainer<?> db)
            throws SQLException {
        // The title carries the original status, which is what lets this be asserted after
        // work_item is no longer readable from the issue side.
        return pairs(db, """
                SELECT DISTINCT split_part(i.title, ' in ', 1), s.name
                  FROM issue i JOIN issue_status s ON s.id = i.status_id
                """);
    }

    private static long scalar(PostgreSQLContainer<?> db, String sql) throws SQLException {
        return Long.parseLong(query(db, sql).getFirst());
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> query(PostgreSQLContainer<?> db, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = superuser(db);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    private static Map<String, String> pairs(PostgreSQLContainer<?> db, String sql)
            throws SQLException {
        Map<String, String> found = new LinkedHashMap<>();
        try (Connection connection = superuser(db);
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                found.put(rs.getString(1), rs.getString(2));
            }
        }
        return found;
    }

    private static Connection superuser(PostgreSQLContainer<?> db) throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }
}
