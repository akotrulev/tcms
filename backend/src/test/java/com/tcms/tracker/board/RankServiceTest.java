package com.tcms.tracker.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The fractional rank (D-d) — the arithmetic, not the wiring.
 *
 * <p>Every claim here is about a number that can be <em>subtly</em> wrong rather than absent: a
 * midpoint that collides with a neighbour, a respace that re-sorts, a growth bound that never
 * fires. None of them fails loudly, and all of them look like a backlog somebody else reordered.
 *
 * <p>The ordered read is {@code ORDER BY rank, created_at, id} throughout and never {@code ORDER BY
 * rank} alone: {@code issue.rank} is deliberately not unique, so two issues created in one project
 * in the same instant tie and {@code created_at} is what breaks it.
 */
@AutoConfigureMockMvc
@DisplayName("The fractional rank — midpoints, the respace, and the two refusals")
class RankServiceTest extends TrackerEndpointTestBase {

    private static final UUID ACME = UUID.fromString("bf100000-0000-4000-8000-000000000001");
    private static final UUID ALPHA = UUID.fromString("bf101000-0000-4000-8000-000000000001");
    private static final UUID BETA = UUID.fromString("bf101000-0000-4000-8000-000000000002");

    /** Project Manager — the tier holding {@code board:rank} (§A3). */
    private static final UUID PM_ROLE = UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID MANAGER = UUID.fromString("bf102000-0000-4000-8000-000000000001");
    private static final UUID ALPHA_GRANT = UUID.fromString("bf103000-0000-4000-8000-000000000001");
    private static final UUID BETA_GRANT = UUID.fromString("bf103000-0000-4000-8000-000000000002");

    /**
     * Enough squeezes against one pair to exhaust {@link RankService#MAX_SCALE} twice over. Exact
     * halving adds one fractional digit per step once the integer gap is spent, so forty is a
     * respace and then some — which is the point: the sequence has to stay correct across one.
     */
    private static final int SQUEEZES = 40;

    @Autowired
    private RankService ranks;

    private int nextSeq;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();

        tx.executeWithoutResult(status -> {
            company(ACME, "rank-acme", "Acme", now);
            activeUser(MANAGER, "pm@rank.test", "Paula Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(ALPHA, ACME, "RNK", "Rank Project", now);
            seedProject(BETA, ACME, "RNKB", "Other Rank Project", now);
            member(ACME, MANAGER, "Paula Manager", now);
        }));

        grantOnProject(ALPHA_GRANT, ACME, PM_ROLE, MANAGER, ALPHA);
        grantOnProject(BETA_GRANT, ACME, PM_ROLE, MANAGER, BETA);
        seedProjectDefaults(ACME, ALPHA);
        seedProjectDefaults(ACME, BETA);
        clearIssues(List.of(ACME));
        nextSeq = 1;
    }

    @Test
    @DisplayName("squeezing against the same neighbour forty times never collides and never re-sorts")
    void insertingBetweenTheSameTwoNeighboursRepeatedlyNeverCollides() throws SQLException {
        // The acceptance criterion, and the reason the column is NUMERIC with no precision:
        // arbitrary precision means a midpoint ALWAYS exists. A fixed NUMERIC(12,4) runs out after
        // roughly fourteen inserts between one pair and then silently returns a rank equal to a
        // neighbour's — no error, and two rows whose order is decided by the tiebreaker.
        String top = issue(ALPHA, "top");
        String bottom = issue(ALPHA, "bottom");

        List<String> expected = new ArrayList<>(List.of(top, bottom));
        String pivot = bottom;
        for (int squeeze = 0; squeeze < SQUEEZES; squeeze++) {
            String moved = issue(ALPHA, "squeeze " + squeeze);
            placed(place(moved, before(pivot)));
            expected.add(expected.indexOf(pivot), moved);
            pivot = moved;
        }

        assertThat(ranksOf(ALPHA))
                .as("a midpoint that equalled a neighbour would leave two rows ordered by nothing")
                .doesNotHaveDuplicates();
        assertThat(orderOf(ALPHA))
                .as("each squeeze lands immediately above the row it was sent before, and stays "
                        + "there across the respace that happens partway through")
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("the respace fires on precision, preserves the order, and says so")
    void rebalancingRespacesTheProjectAndSaysSo() throws SQLException {
        // Growth is bounded by the SERVICE, not by the type. The client is TOLD, because after a
        // respace its own copy of every other row's rank is stale — `rebalanced: true` is an
        // instruction to refetch, not a failure.
        String top = issue(ALPHA, "top");
        String bottom = issue(ALPHA, "bottom");

        List<String> expectedAfterRespace = null;
        RankResultView respaced = null;
        String pivot = bottom;
        for (int squeeze = 0; squeeze < SQUEEZES && respaced == null; squeeze++) {
            String moved = issue(ALPHA, "squeeze " + squeeze);
            List<String> beforeThePlacement = orderOf(ALPHA);
            RankResultView result = placed(place(moved, before(pivot)));
            if (result.rebalanced()) {
                expectedAfterRespace = withMovedAbove(beforeThePlacement, moved, pivot);
                respaced = result;
            }
            pivot = moved;
        }

        assertThat(respaced)
                .as("MAX_SCALE is never reached, so the growth bound is decoration")
                .isNotNull();
        assertThat(ranksOf(ALPHA))
                .as("row_number() * 1024 — the respace hands every row a whole number back")
                .allMatch(rank -> rank.stripTrailingZeros().scale() <= 0);
        assertThat(orderOf(ALPHA))
                .as("""
                        row_number() OVER (ORDER BY rank, id) is ORDER-PRESERVING. A respace that \
                        re-sorted would silently rearrange a backlog somebody spent an afternoon \
                        on, and nothing would report it.""")
                .containsExactlyElementsOf(expectedAfterRespace);
        assertThat(new BigDecimal(respaced.rank()))
                .as("the placement still happened — a respace is not a refusal")
                .isNotNull();
        assertThat(top).isNotBlank();
    }

    @Test
    @DisplayName("placing before the first row puts it above everything, with no neighbour beneath")
    void placingBeforeTheFirstRowPutsItAtTheTop() throws SQLException {
        String first = issue(ALPHA, "first");
        issue(ALPHA, "second");
        String moved = issue(ALPHA, "moved");

        RankResultView result = placed(place(moved, before(first)));

        assertThat(new BigDecimal(result.rank()))
                .as("nothing is below the top row, so the midpoint is a whole spacing above it")
                .isLessThan(rankOf(first));
        assertThat(orderOf(ALPHA)).startsWith(moved, first);
    }

    @Test
    @DisplayName("placing after the last row puts it at the bottom")
    void placingAfterTheLastRowPutsItAtTheBottom() throws SQLException {
        issue(ALPHA, "first");
        String last = issue(ALPHA, "last");
        String moved = issue(ALPHA, "moved");

        RankResultView result = placed(place(moved, after(last)));

        assertThat(new BigDecimal(result.rank())).isGreaterThan(rankOf(last));
        assertThat(orderOf(ALPHA)).endsWith(last, moved);
    }

    @Test
    @DisplayName("a neighbour in another project is refused, not approximated")
    void refusesANeighbourInAnotherProject() throws SQLException {
        // Not merely wrong: rank is project-wide, so a midpoint computed against a number from
        // another project's sequence lands the issue in an order nobody can explain.
        String mine = issue(ALPHA, "mine");
        String theirs = issue(BETA, "theirs");

        RankService.Outcome.Invalid refused = invalid(place(mine, before(theirs)));

        assertThat(refused.errors()).containsKey("beforeKey");
    }

    @Test
    @DisplayName("both neighbours at once is a 400 — the client is asked for the gap, not the number")
    void refusesBothNeighboursAtOnce() throws SQLException {
        String above = issue(ALPHA, "above");
        String below = issue(ALPHA, "below");
        String moved = issue(ALPHA, "moved");

        RankService.Outcome.Invalid refused =
                invalid(place(moved, new RankInput(above, below)));

        assertThat(refused.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("neither neighbour is a 400 — an empty body is not a request to move to the top")
    void refusesNeitherNeighbour() throws SQLException {
        String moved = issue(ALPHA, "moved");

        RankService.Outcome.Invalid refused = invalid(place(moved, new RankInput(null, null)));

        assertThat(refused.errors()).isNotEmpty();
    }

    @Test
    @DisplayName("an issue cannot be placed relative to itself")
    void refusesTheIssueAsItsOwnNeighbour() throws SQLException {
        String moved = issue(ALPHA, "moved");

        assertThat(invalid(place(moved, after(moved))).errors()).isNotEmpty();
    }

    @Test
    @DisplayName("a new issue is already ranked, at the end, by V5's trigger — not by this service")
    void aNewIssueIsRankedAtTheEndByTheTrigger() throws SQLException {
        // D-k. `issue.rank` is NOT NULL and filled on insert by tcms_issue_rank_default, which is
        // what let the column be NOT NULL two tasks before RankService existed. Re-implementing it
        // on the creation path would be a second definition of "the next rank".
        String first = issue(ALPHA, "first");
        String second = issue(ALPHA, "second");

        assertThat(rankOf(second)).isGreaterThan(rankOf(first));
        assertThat(orderOf(ALPHA)).containsExactly(first, second);
    }

    // =====================================================================

    private static RankInput before(String key) {
        return new RankInput(null, key);
    }

    private static RankInput after(String key) {
        return new RankInput(key, null);
    }

    /**
     * The order a respace must reproduce: the placement that triggered it had already happened, so
     * the moved row sits immediately above the row it was sent before.
     */
    private static List<String> withMovedAbove(List<String> before, String moved, String pivot) {
        List<String> expected = new ArrayList<>(before);
        expected.remove(moved);
        expected.add(expected.indexOf(pivot), moved);
        return expected;
    }

    private GateResult<RankService.Outcome> place(String key, RankInput input) {
        return scoped(() -> ranks.place(MANAGER, key, input));
    }

    /**
     * Every call goes through the company scope guard, and that is not fixture noise.
     *
     * <p>A request arrives with one because the session establishes it; a test calling the service
     * in-process does not, and without it {@code app.current_company} is unset, RLS filters every
     * row and the gate answers "no such project" — fail-closed and silent.
     */
    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return call.get();
        }
    }

    private static RankService.Outcome allowed(GateResult<RankService.Outcome> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<RankService.Outcome>) gate).value();
    }

    private static RankResultView placed(GateResult<RankService.Outcome> gate) {
        RankService.Outcome outcome = allowed(gate);
        assertThat(outcome).isInstanceOf(RankService.Outcome.Placed.class);
        return ((RankService.Outcome.Placed) outcome).result();
    }

    private static RankService.Outcome.Invalid invalid(GateResult<RankService.Outcome> gate) {
        RankService.Outcome outcome = allowed(gate);
        assertThat(outcome).isInstanceOf(RankService.Outcome.Invalid.class);
        return (RankService.Outcome.Invalid) outcome;
    }

    /**
     * One issue, inserted through the app connection so V5's {@code issue_rank_default} trigger
     * fills the rank exactly as it does for a real creation.
     */
    private String issue(UUID projectId, String title) throws SQLException {
        int seq = nextSeq++;
        String key = (projectId.equals(ALPHA) ? "RNK-" : "RNKB-") + seq;
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO issue (id, company_id, project_id, key_seq, issue_key,
                                           type_id, status_id, title)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, ?,
                                (SELECT id FROM issue_type
                                  WHERE project_id = CAST(? AS uuid) AND name = 'Task'),
                                (SELECT id FROM issue_status
                                  WHERE project_id = CAST(? AS uuid) AND name = 'Backlog'),
                                ?)
                        """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ACME);
            statement.setObject(3, projectId);
            statement.setInt(4, seq);
            statement.setString(5, key);
            statement.setObject(6, projectId);
            statement.setObject(7, projectId);
            statement.setString(8, title);
            statement.executeUpdate();
        }
        return key;
    }

    private List<String> orderOf(UUID projectId) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT issue_key FROM issue
                         WHERE project_id = CAST(? AS uuid) AND deleted_at IS NULL
                         ORDER BY rank, created_at, id
                        """)) {
            statement.setObject(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    keys.add(rows.getString(1));
                }
            }
        }
        return keys;
    }

    private List<BigDecimal> ranksOf(UUID projectId) throws SQLException {
        List<BigDecimal> found = new ArrayList<>();
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT rank FROM issue
                         WHERE project_id = CAST(? AS uuid) AND deleted_at IS NULL
                         ORDER BY rank, created_at, id
                        """)) {
            statement.setObject(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getBigDecimal(1));
                }
            }
        }
        return found;
    }

    private BigDecimal rankOf(String key) throws SQLException {
        try (Connection connection = appConnection(ACME);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT rank FROM issue WHERE issue_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
            }
        }
    }
}
