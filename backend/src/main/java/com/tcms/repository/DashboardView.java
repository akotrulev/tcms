package com.tcms.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code RepositoryDashboard} in {@code docs/specs/openapi.yaml} — the project's landing page.
 *
 * <h2>Every count carries the query that reproduces it</h2>
 *
 * <p>{@code filter} is the whole design of this response (plan §6o). A tile is not a number to look
 * at, it is the entrance to the working list of cases behind that number, and the SPA turns the map
 * straight into query parameters on the case list — so the keys here are
 * <strong>{@code GET /projects/{id}/cases} parameter names</strong> and nothing else. A key the case
 * list does not bind produces a tile that silently opens a <em>wider</em> set than it promised, which
 * is worse than a dead link because the user cannot tell. {@code ownerId} was exactly that until this
 * slice added it to the case list.
 *
 * @param healthComputedAt null when {@code case_health} has never been derived for this project —
 *                         see {@link DashboardService#health} for why that is not the same as an
 *                         empty {@code health} list and must not render the same way
 */
public record DashboardView(
        Instant generatedAt,
        List<TileView> lifecycle,
        List<TileView> health,
        Instant healthComputedAt,
        List<BreakdownView> byFeature,
        List<BreakdownView> byOwner,
        AutomationCoverageView automationCoverage,
        List<ActivityPointView> recentActivity) {

    /**
     * One counted, drillable tile.
     *
     * <h2>The key is the only identity, and §180 is where it stopped being one of two</h2>
     *
     * <p><del>{@code label} is display text, on the wire rather than looked up client-side,
     * uniquely on this endpoint.</del> It carried a second set of English words for enums the SPA
     * already labels from {@code lib/labels.ts}, and <strong>six of the ten health signals
     * disagreed</strong> — so the tile reading "Outdated — spec changed" opened the case list its
     * own {@code filter} composed, whose chips read "Linked spec changed". One navigation, two
     * vocabularies, and the drill-down is the single place they meet.
     *
     * <p>The trade the old wording defended — "a tile is a generic shape so a <em>new</em>
     * server-side signal renders with no frontend change" — was <strong>already not true of the
     * words</strong>, and that is what made this cheap rather than a loss. {@code HealthSignal} is
     * a contract enum: a new member regenerates {@code schema.d.ts} and makes
     * {@code healthLabels satisfies Record<HealthSignal, string>} a compile error until it is
     * labelled, and the filter bar and the grid chip need that label anyway. What the tile actually
     * bought is the {@code filter} being opaque, and <em>that</em> is untouched — a client handed a
     * key it has no label for still renders the tile and still drills, under the key itself.
     *
     * @param key      the enum constant — a {@link HealthSignal} on {@code health}, a
     *                 {@link LifecycleStatus} on {@code lifecycle}. Used for the {@code data-testid}
     *                 and for React's key as well as for the label, so it must not move.
     * @param severity how loudly it renders; {@code NEUTRAL} for a plain distribution count. On the
     *                 wire where the label is not, and the split is §179's: severity is a judgement
     *                 about how wrong something is, made where the derivations are; a label is words.
     * @param filter   the case-list query reproducing {@code count}.
     */
    public record TileView(
            String key,
            long count,
            HealthSignal.Severity severity,
            Map<String, Object> filter) {
    }

    /**
     * One stacked bar — a feature or a person, split by authoring status.
     *
     * <p><strong>{@code label} here is data and stays.</strong> A classification path
     * ({@code Payments › Refunds}) or a person's display name in <em>this</em> company is something
     * only the server can resolve; it is not a rendering of a closed set. The distinction is the one
     * §180 turns on, and this record is the side of it that keeps its words.
     */
    public record BreakdownView(String id, String label, List<SegmentView> segments) {
    }

    /**
     * One segment of a stacked bar, itself a link.
     *
     * <p><del>{@code color} is a CSS custom property name rather than a hex value, so the segment
     * follows the theme the rest of the page is drawn in.</del> <strong>Off the wire in §180, and it
     * was the sharper half of that slice.</strong> The argument was sound about the wrong question:
     * it defends "a custom property, not a hex" and says nothing about the mapping being sent at
     * all. The server composed {@code "var(--color-" + status.name().toLowerCase() + ")"} while
     * {@code StatusChip.tsx} kept {@code lifecycleVar}, a hand-written table of the identical four
     * strings — two mechanisms producing one mapping, in two stacks, compared by nothing. That is
     * §179's finding at a colour, and it had no test on either side.
     *
     * @param key a {@link LifecycleStatus} constant; the client labels and colours from it.
     */
    public record SegmentView(
            String key, long count, Map<String, Object> filter) {
    }

    /**
     * @param coveragePercent {@code automated / (total − notAutomatable)}, honestly denominated
     *                        (plan §6c). Cases nobody can automate are out of the divisor, because a
     *                        coverage figure that improves when someone marks a case impossible is a
     *                        figure that rewards giving up.
     */
    public record AutomationCoverageView(
            long automated,
            long toBeAutomated,
            long manual,
            long notAutomatable,
            double coveragePercent) {
    }

    /**
     * One day of authoring activity.
     *
     * <p>There is no {@code approved} count and that is a contract decision, not an omission — see
     * the {@code recentActivity} description in {@code openapi.yaml}. Nothing in the schema records
     * when a case was approved, so the series could only ever be a flat zero line, which states
     * something false about the client's month rather than "not measured".
     */
    public record ActivityPointView(LocalDate date, long created, long modified) {
    }

    /** A case-list link filtered to one owner, for {@link DashboardService}'s by-owner segments. */
    static Map<String, Object> ownedBy(UUID ownerId, LifecycleStatus status) {
        return Map.of("ownerId", ownerId.toString(), "lifecycleStatus", List.of(status.name()));
    }
}
