package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.repository.DashboardView.TileView;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link DashboardService#health(Map)} — which tile the reader sees first, and the rule that no
 * tile is drawn for a signal at zero.
 *
 * <h2>This class exists because §184 deleted the only check on the order</h2>
 *
 * <p>{@code DashboardService.health} has sorted its tiles since the panel was built, and its own
 * comment said the sort belonged here rather than in the SPA. The SPA sorted anyway
 * ({@code severityRank} in {@code DashboardPage.tsx}), and {@code HealthPanel.test.tsx}'s "worst
 * first" test asserted <em>that</em> copy: it passed a WARNING ahead of an ERROR and checked the
 * panel swapped them. So the only assertion in the repository that a dashboard tile list is ordered
 * at all was pointed at the client, and it would have stayed green against a server that returned
 * the tiles in enum order.
 *
 * <p>§184 takes the decision §179 already took at the case grid — <em>the ranking is a property of
 * the answer, and a client-side sort is the second copy of it</em> — and applies it to the second
 * of the two screens. Deleting the copy without moving its coverage is §153's finding
 * ({@code ProvisionCompanyCommand.isRequestedBy}: six assertions aimed at the dead copy while the
 * live guard had none) and §179 called it out by name for this same panel's neighbour, so the
 * assertions arrive here in the same slice.
 *
 * <p><strong>The second key is what the SPA never had.</strong> The comparator orders by severity
 * and then by count descending; the SPA ordered by severity alone and preserved the tiebreak purely
 * because {@code Array.prototype.sort} is stable and severity happened to be the first key. A
 * coincidence is not a contract, and neither suite would have failed on the day it stopped holding.
 *
 * <h2>No database and no context</h2>
 *
 * <p>{@code health} is a static function from counts to tiles: it reads {@link HealthSignal} and a
 * {@link java.util.Comparator} and touches nothing else. §150's lane, {@code RaisedSignalTest}'s
 * call for the same reason — the sibling half of this ordering (that {@link HealthSignal.Severity}
 * is <em>declared</em> worst first) is pinned there, and neither test is worth a Postgres container.
 */
@DisplayName("Dashboard health tiles — worst first, biggest first within a severity, nothing at zero")
class HealthTileOrderTest {

    private static Map<HealthSignal, Long> counts(Object... signalThenCount) {
        Map<HealthSignal, Long> counts = new EnumMap<>(HealthSignal.class);
        for (int i = 0; i < signalThenCount.length; i += 2) {
            counts.put((HealthSignal) signalThenCount[i], ((Number) signalThenCount[i + 1]).longValue());
        }
        return counts;
    }

    private static List<String> keysOf(List<TileView> tiles) {
        return tiles.stream().map(TileView::key).toList();
    }

    @Nested
    @DisplayName("the first key: severity")
    class BySeverity {

        @Test
        @DisplayName("an ERROR is tiled above a WARNING above an INFO, whatever the counts say")
        void worstFirst() {
            // The counts run the other way on purpose. A panel ordered by size — the obvious
            // alternative, and the one the by-owner and by-feature panels genuinely use — would
            // put 156 unlinked cases above two whose spec changed underneath them, which is the
            // §179 regression at the case grid rendered as a wall of tiles instead of one chip.
            //
            // MEASURED: this test alone stays green when the sort is deleted, because these three
            // also happen to be declared in this order. That is not a flaw to fix by picking a
            // cleverer triple — it is the reason the next test exists, and stating it here is what
            // stops somebody deleting the next test as a duplicate of this one.
            List<TileView> tiles = DashboardService.health(counts(
                    HealthSignal.UNLINKED, 156,
                    HealthSignal.STALE_APPROVAL, 40,
                    HealthSignal.OUTDATED_SPEC_CHANGED, 2));

            assertThat(keysOf(tiles))
                    .as("severity outranks count, or the loudest tile is merely the widest filter")
                    .containsExactly("OUTDATED_SPEC_CHANGED", "STALE_APPROVAL", "UNLINKED");
        }

        @Test
        @DisplayName("the order does not follow the enum's declaration order")
        void notDeclarationOrder() {
            // The regression this whole family keeps producing: `HealthSignal.values()` is the
            // loop above, so "forgot to sort" and "sorted correctly" are the same code path for
            // any input whose declaration order happens to agree. UNLINKED is declared BEFORE
            // FAILING_REPEATEDLY (§179 found this at the grid, where it hid a failing case behind
            // a missing link), so this pair is the one that tells the two apart.
            assertThat(List.of(HealthSignal.values()).indexOf(HealthSignal.UNLINKED))
                    .as("if UNLINKED stops being declared first this test stops testing anything")
                    .isLessThan(List.of(HealthSignal.values()).indexOf(HealthSignal.FAILING_REPEATEDLY));

            List<TileView> tiles = DashboardService.health(counts(
                    HealthSignal.UNLINKED, 156,
                    HealthSignal.FAILING_REPEATEDLY, 3));

            assertThat(keysOf(tiles)).containsExactly("FAILING_REPEATEDLY", "UNLINKED");
        }
    }

    @Nested
    @DisplayName("the second key: count, descending")
    class ByCount {

        @Test
        @DisplayName("within one severity the bigger count is tiled first")
        void biggestFirstWithinASeverity() {
            // The key the SPA's deleted copy did not have. Both of these are ERROR, so severity
            // decides nothing and the comparator's second clause is the only thing under test.
            List<TileView> tiles = DashboardService.health(counts(
                    HealthSignal.OUTDATED_SPEC_CHANGED, 4,
                    HealthSignal.CONDITION_DRIFT, 61));

            assertThat(tiles).allSatisfy(tile ->
                    assertThat(tile.severity()).isEqualTo(HealthSignal.Severity.ERROR));
            assertThat(keysOf(tiles)).containsExactly("CONDITION_DRIFT", "OUTDATED_SPEC_CHANGED");
        }

        @Test
        @DisplayName("count never lifts a tile past a worse one")
        void countIsSubordinateToSeverity() {
            // Both keys in one input, arranged so that either key alone gives a different answer:
            // by severity alone the two ERRORs tie and arrive in declaration order
            // (OUTDATED_SPEC_CHANGED, CONDITION_DRIFT); by count alone STALE_APPROVAL leads.
            List<TileView> tiles = DashboardService.health(counts(
                    HealthSignal.OUTDATED_SPEC_CHANGED, 1,
                    HealthSignal.CONDITION_DRIFT, 9,
                    HealthSignal.STALE_APPROVAL, 900));

            assertThat(keysOf(tiles))
                    .containsExactly("CONDITION_DRIFT", "OUTDATED_SPEC_CHANGED", "STALE_APPROVAL");
        }
    }

    @Nested
    @DisplayName("what is not tiled")
    class Absent {

        @Test
        @DisplayName("a signal at zero gets no tile, which is the opposite rule to lifecycle")
        void zeroIsNotATile() {
            // Stated in `health`'s javadoc and asserted nowhere: health is an open-ended set of
            // things that are WRONG, so "0 stale approvals" is a filter that opens an empty grid,
            // and eleven of them bury the one that matters. The panel is headed "Needs attention".
            List<TileView> tiles = DashboardService.health(counts(
                    HealthSignal.CONDITION_DRIFT, 0,
                    HealthSignal.UNLINKED, 7));

            assertThat(keysOf(tiles)).containsExactly("UNLINKED");
        }

        @Test
        @DisplayName("a signal absent from the tally is the same as a signal at zero")
        void absentIsZero() {
            // `tallyHealth` returns only the signals it counted, so the two spellings of "none"
            // both reach here and must not diverge — an absent key that produced a tile would
            // render every signal on every project.
            assertThat(DashboardService.health(new EnumMap<>(HealthSignal.class))).isEmpty();
        }
    }

    @Test
    @DisplayName("every raised signal is tiled exactly once, carrying its own filter")
    void oneTilePerRaisedSignal() {
        // The completeness half: the assertions above all read the ORDER of a short list, and a
        // sort that silently dropped or duplicated a tile would satisfy every one of them.
        Map<HealthSignal, Long> all = new EnumMap<>(HealthSignal.class);
        for (HealthSignal signal : HealthSignal.values()) {
            all.put(signal, 1L);
        }

        List<TileView> tiles = DashboardService.health(all);

        assertThat(tiles).hasSize(HealthSignal.values().length);
        assertThat(keysOf(tiles))
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(
                        List.of(HealthSignal.values()).stream().map(Enum::name).toList());
        assertThat(tiles).allSatisfy(tile -> assertThat(tile.filter())
                .as("a tile whose filter does not name its own signal drills into the wrong set")
                .isEqualTo(Map.of("healthSignal", List.of(tile.key()))));
    }
}
