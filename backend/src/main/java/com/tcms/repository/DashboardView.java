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
     * @param key      stable and machine-readable — the enum constant, not the label. The SPA uses it
     *                 for the {@code data-testid} and for React's key, so it must not move when the
     *                 wording does.
     * @param label    display text. On the wire rather than looked up client-side, uniquely on this
     *                 endpoint — see {@link LifecycleStatus#label()} for the reason.
     * @param severity how loudly it renders; {@code NEUTRAL} for a plain distribution count.
     * @param filter   the case-list query reproducing {@code count}.
     */
    public record TileView(
            String key,
            String label,
            long count,
            HealthSignal.Severity severity,
            Map<String, Object> filter) {
    }

    /** One stacked bar — a feature or a person, split by authoring status. */
    public record BreakdownView(String id, String label, List<SegmentView> segments) {
    }

    /**
     * One segment of a stacked bar, itself a link.
     *
     * @param color a CSS custom property name rather than a hex value, so the segment follows the
     *              theme the rest of the page is drawn in — including dark mode, which a server-side
     *              hex would break in exactly one place on one screen.
     */
    public record SegmentView(
            String key, String label, long count, String color, Map<String, Object> filter) {
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
