package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code V5}'s schema, checked by running it rather than by reading it.
 *
 * <p>The working agreement's rule, and this class is the third table it applies to: reading
 * {@code V1} top to bottom missed five join tables without {@code company_id}, and scripting the
 * comparison found them. Three of the four checks below are about a failure that reports success —
 * a unique index that was never created, a seed block dropped by a {@code CREATE OR REPLACE}, and a
 * rank that ties with its neighbour — and none of them is visible in a green build otherwise.
 *
 * <p><strong>Read as the superuser, not as {@code tcms_owner}.</strong> All six new tables carry
 * {@code FORCE ROW LEVEL SECURITY} and {@code tcms_owner} holds no {@code BYPASSRLS}, so a count of
 * zero from that role would mean the policy is working rather than that the migration seeded
 * nothing — the distinction {@code docs/specs/tenancy-invariants.md} names as the first of its two
 * silent traps.
 */
@DisplayName("V5 — boards, the status claim, the seed function and the fractional rank")
class BoardSchemaTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Test
    @DisplayName("a second column cannot claim a status the first already holds")
    void aSecondColumnCannotClaimAStatusTheFirstAlreadyHolds() throws SQLException {
        // uq_board_column_status is the half of "exactly one column claims each status" that
        // Postgres CAN enforce, and it is the half that SURVIVED 021 R7 — a board claims a subset
        // now, but a card's column still has to be unambiguous. ~~The other half — at least one —
        // is BoardConfigService's.~~ → removed; BoardCoverageTest reports it instead of refusing it.
        Seeded seeded = seedCompany("bs-claim", "BSC");
        UUID board = boardNamed(seeded.projectId(), "PM board");
        UUID claimedStatus = firstColumnStatusOf(board);

        UUID spareColumn = insertColumn(seeded, board, "Duplicate", 99, claimedStatus);

        assertThatThrownBy(() -> claim(seeded.companyId(), board, spareColumn, claimedStatus))
                .as("""
                        A second column claimed a status the first already holds. Every drop into \
                        either column would then mean the same status, so the board would move an \
                        issue and leave the card where it was.""")
                .isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("every new project gets three boards, and between them they claim every status")
    void everyNewProjectGetsABoardWithOneColumnPerStatus() throws SQLException {
        // ~~One board with one column per status.~~ → V13 seeds THREE (021 §2.4), each claiming the
        // part of the line its reader works on. What has to hold is no longer per board — it is
        // across them: a status no board claims is a card nowhere, which is the failure that
        // reports success.
        Seeded seeded = seedCompany("bs-default", "BSD");
        UUID pmBoard = boardNamed(seeded.projectId(), "PM board");

        assertThat(count(
                "SELECT count(*) FROM board_column WHERE board_id = CAST(? AS uuid)", pmBoard))
                .as("""
                        The PM board's six columns — New, Refining, Ready to develop, Ready to \
                        deploy, Done, Blocked. A different number is the board block of \
                        tcms_seed_project_defaults having been changed or dropped.""")
                .isEqualTo(6);
        assertThat(count(
                        "SELECT count(*) FROM board_column_status WHERE board_id = CAST(? AS uuid)",
                        pmBoard))
                .as("every column of the PM board claims exactly one status")
                .isEqualTo(6);
        assertThat(count("""
                SELECT count(*) FROM issue_status s
                 WHERE s.project_id = CAST(? AS uuid)
                   AND NOT EXISTS (SELECT 1 FROM board_column_status c
                                     JOIN board b ON b.id = c.board_id
                                    WHERE b.project_id = CAST(? AS uuid) AND c.status_id = s.id)
                """, seeded.projectId(), seeded.projectId()))
                .as("""
                        Statuses NO board of the project claims. An issue in one of them has \
                        nowhere to be drawn on any board, so it vanishes rather than appearing \
                        somewhere wrong — which is the failure that reports success, and the one \
                        021 R7 traded a refusal for a read of.""")
                .isZero();
    }

    @Test
    @DisplayName("every block of the seed function survived the reproduction — all nine")
    void everyBlockOfTheSeedFunctionSurvivedTheReproduction() throws SQLException {
        // Named individually rather than counted: a count passes when one block is dropped and
        // another duplicated. V5 restated tcms_seed_project_defaults in full under CREATE OR
        // REPLACE to add the board and V13 restated it again for the eleven statuses and the three
        // boards, and a block missing from either restatement stops being seeded for every project
        // created from there on, with no error anywhere.
        Seeded seeded = seedCompany("bs-blocks", "BSB");
        UUID project = seeded.projectId();

        assertThat(rowsFor("classification_dimension", project)).isEqualTo(1);
        assertThat(rowsFor("case_status", project)).isEqualTo(7);
        assertThat(rowsFor("configuration", project)).isEqualTo(1);
        assertThat(rowsFor("doc_space", project)).isEqualTo(1);
        assertThat(rowsFor("project_key_sequence", project)).isEqualTo(1);
        assertThat(rowsFor("issue_status", project)).isEqualTo(11);
        assertThat(rowsFor("issue_type", project)).isEqualTo(6);
        assertThat(rowsFor("board", project)).isEqualTo(3);
        assertThat(count("""
                SELECT count(*) FROM issue_transition WHERE project_id = CAST(? AS uuid)
                """, project))
                .as("the workflow's transitions — the eighth block, and the one with no table of "
                        + "its own to count by project_id anywhere else in this class. Eight along "
                        + "the line, four for Rework, nine into Blocked (021 §2.3)")
                .isEqualTo(21);
    }

    @Test
    @DisplayName("a midpoint rank inserts without rewriting either neighbour")
    void aMidpointRankInsertsWithoutRewritingItsNeighbours() throws SQLException {
        // The acceptance criterion behind NUMERIC with no precision (D-d). An INTEGER position
        // column cannot say this: placing a row between two others would renumber everything below
        // the drop, which is how a backlog drag becomes a thousand-row UPDATE.
        Seeded seeded = seedCompany("bs-rank", "BSR");
        UUID a = insertIssue(seeded, 101, new BigDecimal("1024"));
        UUID b = insertIssue(seeded, 102, new BigDecimal("2048"));

        insertIssue(seeded, 103, new BigDecimal("1536"));

        assertThat(rankOf(a)).isEqualByComparingTo("1024");
        assertThat(rankOf(b)).isEqualByComparingTo("2048");

        // Twelve fractional digits is where RankService respaces the project (T4). The column must
        // hold one before that, or the service's trigger point is unreachable and every squeeze
        // past a fixed scale would silently round onto a neighbour.
        UUID deep = insertIssue(seeded, 104, new BigDecimal("1024.000000000001"));
        assertThat(rankOf(deep)).isEqualByComparingTo("1024.000000000001");
    }

    @Test
    @DisplayName("an issue inserted without a rank takes the next one in its project")
    void anIssueInsertedWithoutARankTakesTheNextOneInItsProject() throws SQLException {
        // The trigger, and it is why the column can be NOT NULL two tasks before RankService
        // exists. A column DEFAULT cannot read another row and a constant one would make every new
        // issue tie with every other, so a backlog ordered by rank would be ordered by nothing —
        // while still reading as ranked.
        Seeded seeded = seedCompany("bs-trigger", "BST");
        insertIssue(seeded, 201, new BigDecimal("4096"));

        UUID next = insertIssue(seeded, 202, null);

        assertThat(rankOf(next))
                .as("a new issue takes max(rank) + 1024 in its own project (D-d)")
                .isEqualByComparingTo("5120");

        // Per PROJECT, not per company: a second project's first issue starts from its own empty
        // sequence, or every project after the first would open with a gap the size of its
        // neighbour's backlog.
        Seeded other = seedCompany("bs-trigger-2", "BST2");
        assertThat(rankOf(insertIssue(other, 203, null))).isEqualByComparingTo("1024");
    }

    @Test
    @DisplayName("a status leaving the workflow takes its column, and only its column")
    void aStatusLeavingTheWorkflowTakesItsColumnAndOnlyItsColumn() throws SQLException {
        // D-l, and it was found by the gate rather than by reading: a plain RESTRICT on
        // default_status_id lets a BOARD refuse a WORKFLOW edit, which is backwards — D2 puts the
        // canonical status on the issue and leaves a board mapping statuses onto columns, so the
        // board follows. Thirty-eight tests said so on the first run of this migration.
        Seeded seeded = seedCompany("bs-retire", "BSX");
        UUID board = boardNamed(seeded.projectId(), "PM board");
        UUID ready = statusOf(seeded.projectId(), "Refining");

        retireStatus(seeded.projectId(), "Refining");

        assertThat(count("""
                SELECT count(*) FROM board_column
                 WHERE board_id = CAST(? AS uuid) AND default_status_id = CAST(? AS uuid)
                """, board, ready))
                .as("the column that claimed only the departing status goes with it")
                .isZero();
        assertThat(count("SELECT count(*) FROM board_column WHERE board_id = CAST(? AS uuid)", board))
                .as("and nothing else on the board moved — six columns become five")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("a folded column survives its default status leaving, and repoints at what it still holds")
    void aFoldedColumnSurvivesItsDefaultStatusLeaving() throws SQLException {
        // The case ON DELETE CASCADE would have got wrong, and silently: a stakeholder column
        // claiming three statuses would VANISH when its default one was retired, taking two live
        // statuses off the board with it and leaving them claimed by nothing.
        Seeded seeded = seedCompany("bs-folded", "BSF");
        UUID board = boardNamed(seeded.projectId(), "Dev board");
        UUID inProgress = statusOf(seeded.projectId(), "In progress");
        UUID inReview = statusOf(seeded.projectId(), "In review");
        UUID folded = insertColumn(seeded, board, "In flight", 90, inProgress);
        moveClaims(seeded, board, folded, inProgress, inReview);

        retireStatus(seeded.projectId(), "In progress");

        assertThat(count("SELECT count(*) FROM board_column WHERE id = CAST(? AS uuid)", folded))
                .as("the folded column still holds In review, so it stays")
                .isEqualTo(1);
        assertThat(uuids("SELECT default_status_id FROM board_column WHERE id = CAST(? AS uuid)",
                folded).getFirst())
                .as("and defaults to the earliest status it still claims, rather than to nothing")
                .isEqualTo(inReview);
        assertThat(count("""
                SELECT count(*) FROM board_column_status c
                 WHERE c.board_id = CAST(? AS uuid) AND c.status_id = CAST(? AS uuid)
                """, board, inReview))
                .as("""
                        In review is still claimed by this board after the retirement. ~~Statuses \
                        no column claims — zero.~~ -> superseded by 021 R7: a board claims a \
                        SUBSET, so a status this board never showed is not evidence of anything. \
                        What the cascade would have broken without failing is narrower and still \
                        true: the folded column would have VANISHED, taking a live status off the \
                        board with it, and the board would still have rendered.""")
                .isEqualTo(1);
    }

    /** A provisioned company and the project provisioning made with it. */
    private record Seeded(UUID companyId, UUID projectId) {}

    /**
     * Every project this class provisioned in the current test, so the workflow can be put back.
     *
     * <p>Two methods here <strong>retire a status</strong>, which is the whole point of them — and
     * one class over, {@code WorkflowSeedTest} asserts that <em>every</em> seeded project in the
     * shared container has the eleven defaults in order. Leaving one short is green here and red
     * there, in a class that has nothing to do with this one, and which of the two runs first is
     * not a thing either of them decides. That is §19's shape, reached through a status name.
     */
    private final List<Seeded> provisioned = new ArrayList<>();

    private Seeded seedCompany(String slug, String projectKey) {
        ProvisionCompanyResult result = provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                slug, slug + " Ltd", "ops@" + slug + ".test", "Ops Person",
                projectKey, slug + " Product"));
        Seeded seeded = new Seeded(result.companyId(), result.projectId().orElseThrow(
                () -> new AssertionError("provisioning was asked for a first project and made none")));
        provisioned.add(seeded);
        return seeded;
    }

    /**
     * Re-seeds every project this test provisioned, which puts back any status it retired.
     *
     * <p>{@code tcms_seed_project_defaults} is idempotent per NAME and inserts each status at the
     * position the seed gives it, so a retired one comes back where it was rather than at the end.
     * The board block is guarded by {@code NOT EXISTS (SELECT 1 FROM board …)} and is therefore
     * skipped — which is right: the columns the trigger removed are what those methods asserted
     * about, and re-making them would undo the thing under test after it had been read.
     */
    @AfterEach
    void putTheWorkflowsBack() throws SQLException {
        for (Seeded seeded : provisioned) {
            try (Connection connection = superuserConnection()) {
                try (PreparedStatement guc = connection.prepareStatement(
                        "SELECT set_config('app.current_company', ?, false)")) {
                    guc.setString(1, seeded.companyId().toString());
                    guc.execute();
                }
                try (PreparedStatement seed = connection.prepareStatement(
                        "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
                    seed.setString(1, seeded.companyId().toString());
                    seed.setString(2, seeded.projectId().toString());
                    seed.execute();
                }
            }
        }
        provisioned.clear();
    }

    /**
     * One of the three boards {@code tcms_seed_project_defaults} makes, by name.
     *
     * <p>~~{@code onlyBoardOf}, which asserted the seeded project has exactly one.~~ → V13 seeds
     * three (021 §2.4), so "the board" is no longer a thing a fixture can name without saying
     * which. The assertion the old helper carried survives as this one's {@code hasSize(1)}: a
     * board named twice, or not at all, is the seed having been changed under the test.
     */
    private static UUID boardNamed(UUID projectId, String name) throws SQLException {
        List<UUID> boards = new ArrayList<>();
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM board WHERE project_id = CAST(? AS uuid) AND name = ?")) {
            statement.setString(1, projectId.toString());
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    boards.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        assertThat(boards)
                .as("the seeded project must have exactly one board called '%s' — the fixture "
                        + "asserts nothing otherwise", name)
                .hasSize(1);
        return boards.getFirst();
    }

    private static UUID firstColumnStatusOf(UUID boardId) throws SQLException {
        return uuids("""
                SELECT s.status_id FROM board_column_status s
                  JOIN board_column c ON c.id = s.column_id
                 WHERE s.board_id = CAST(? AS uuid)
                 ORDER BY c."position"
                """, boardId).getFirst();
    }

    private static UUID insertColumn(Seeded seeded, UUID boardId, String name, int position,
            UUID defaultStatusId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO board_column
                            (id, company_id, board_id, name, "position", wip_limit, default_status_id)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?, NULL,
                                CAST(? AS uuid))
                        """)) {
            statement.setString(1, id.toString());
            statement.setString(2, seeded.companyId().toString());
            statement.setString(3, boardId.toString());
            statement.setString(4, name);
            statement.setInt(5, position);
            statement.setString(6, defaultStatusId.toString());
            statement.executeUpdate();
        }
        return id;
    }

    private static void claim(UUID companyId, UUID boardId, UUID columnId, UUID statusId)
            throws SQLException {
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO board_column_status (company_id, board_id, column_id, status_id)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                        """)) {
            statement.setString(1, companyId.toString());
            statement.setString(2, boardId.toString());
            statement.setString(3, columnId.toString());
            statement.setString(4, statusId.toString());
            statement.executeUpdate();
        }
    }

    /**
     * An issue written straight into the table, with {@code rank} omitted entirely when the caller
     * passes {@code null} — which is the only way to exercise the trigger, since binding NULL and
     * omitting the column are the same thing to it and neither is what an entity insert does.
     */
    private static UUID insertIssue(Seeded seeded, int keySeq, BigDecimal rank) throws SQLException {
        UUID id = UUID.randomUUID();
        String columns = "id, company_id, project_id, key_seq, issue_key, type_id, status_id, title"
                + (rank == null ? "" : ", rank");
        String values = """
                CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                (SELECT id FROM issue_type WHERE project_id = CAST(? AS uuid) AND name = 'Task'),
                (SELECT id FROM issue_status WHERE project_id = CAST(? AS uuid) AND name = 'New'),
                ?""" + (rank == null ? "" : ", ?");

        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO issue (" + columns + ") VALUES (" + values + ")")) {
            statement.setString(1, id.toString());
            statement.setString(2, seeded.companyId().toString());
            statement.setString(3, seeded.projectId().toString());
            statement.setInt(4, keySeq);
            statement.setString(5, "BS-" + keySeq + "-" + id.toString().substring(0, 8));
            statement.setString(6, seeded.projectId().toString());
            statement.setString(7, seeded.projectId().toString());
            statement.setString(8, "rank fixture " + keySeq);
            if (rank != null) {
                statement.setBigDecimal(9, rank);
            }
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID statusOf(UUID projectId, String name) throws SQLException {
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM issue_status WHERE project_id = CAST(? AS uuid) AND name = ?")) {
            statement.setString(1, projectId.toString());
            statement.setString(2, name);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("the seeded workflow must hold a status called " + name).isTrue();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    /**
     * Drops one status the way {@code WorkflowService} does — its transitions first, because
     * {@code issue_transition} references it and would otherwise refuse the delete.
     */
    private static void retireStatus(UUID projectId, String name) throws SQLException {
        UUID status = statusOf(projectId, name);
        try (Connection connection = superuserConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM issue_transition
                     WHERE from_status_id = CAST(? AS uuid) OR to_status_id = CAST(? AS uuid)
                    """)) {
                statement.setString(1, status.toString());
                statement.setString(2, status.toString());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM issue_status WHERE id = CAST(? AS uuid)")) {
                statement.setString(1, status.toString());
                assertThat(statement.executeUpdate())
                        .as("the fixture must actually retire the status")
                        .isEqualTo(1);
            }
        }
    }

    /** Folds several statuses onto one column — a status is claimed by one column per board. */
    private static void moveClaims(Seeded seeded, UUID boardId, UUID columnId, UUID... statusIds)
            throws SQLException {
        try (Connection connection = superuserConnection()) {
            for (UUID status : statusIds) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM board_column_status WHERE board_id = CAST(? AS uuid) "
                                + "AND status_id = CAST(? AS uuid)")) {
                    statement.setString(1, boardId.toString());
                    statement.setString(2, status.toString());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO board_column_status (company_id, board_id, column_id, status_id)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                        """)) {
                    statement.setString(1, seeded.companyId().toString());
                    statement.setString(2, boardId.toString());
                    statement.setString(3, columnId.toString());
                    statement.setString(4, status.toString());
                    statement.executeUpdate();
                }
            }
        }
    }

    private static BigDecimal rankOf(UUID issueId) throws SQLException {
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rank FROM issue WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, issueId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("the fixture issue must exist").isTrue();
                return rs.getBigDecimal(1);
            }
        }
    }

    /**
     * Rows one project holds in a table. The table name is interpolated because a table name cannot
     * be a bind parameter, and the only values it takes are the eight literals above.
     */
    private static long rowsFor(String table, UUID projectId) throws SQLException {
        return count("SELECT count(*) FROM " + table + " WHERE project_id = CAST(? AS uuid)",
                projectId);
    }

    private static long count(String sql, UUID... parameters) throws SQLException {
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i].toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static List<UUID> uuids(String sql, UUID parameter) throws SQLException {
        List<UUID> values = new ArrayList<>();
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    values.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return values;
    }
}
