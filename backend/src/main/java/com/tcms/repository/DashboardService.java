package com.tcms.repository;

import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ClassificationValueView;
import com.tcms.project.ProjectConfigurationService;
import com.tcms.project.ProjectService;
import com.tcms.repository.DashboardView.ActivityPointView;
import com.tcms.repository.DashboardView.AutomationCoverageView;
import com.tcms.repository.DashboardView.BreakdownView;
import com.tcms.repository.DashboardView.SegmentView;
import com.tcms.repository.DashboardView.TileView;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The repository status dashboard (plan §6o) — a project's landing page, and the first screen that
 * says what state the <em>whole</em> repository is in rather than what one case says.
 *
 * <h2>Every number here is a link, and that is the design</h2>
 *
 * <p>§6o's rule is that a count which cannot be drilled into is decoration: "78 cases whose spec
 * changed" is only useful if it is one click from the list of those 78, filtered, shareable and
 * bulk-actionable. So every tile and every bar segment carries the case-list query that reproduces
 * it, and the SPA turns that map straight into query parameters.
 *
 * <p>The rule has a sharp edge that this slice ran into: <strong>a filter key the case list does not
 * bind produces a tile that opens a wider set than it promised</strong>, silently. The by-owner bars
 * emitted {@code ownerId} — a parameter {@code GET /cases} did not have — so "Ana · Approved" would
 * have opened <em>everyone's</em> approved cases under a heading naming Ana. It reads as working.
 * The parameter now exists; the alternative fix, dropping the owner from the link, would have been
 * §6o's own anti-pattern.
 *
 * <h2>Counted over the same predicate as the list it links to</h2>
 *
 * <p>Every count is a tally through {@link CaseSpecifications} — the case list's own {@code WHERE},
 * reached with {@link CaseQuery#unfiltered()}. So a tile's number and the grid behind it cannot
 * disagree about what counts: both exclude soft-deleted cases, both exclude cases hanging off a
 * soft-deleted folder, and neither can learn one of those rules without the other. Two hand-written
 * predicates would agree on the day they were written.
 *
 * <h2>Computed on read, like the plan statistics beside it</h2>
 *
 * <p>Six queries per request — the gate, the suite tree, the vocabularies, three tallies, two
 * breakdowns and two activity reads — none of them per case. §6o describes a maintained rollup and
 * this is not one, for {@link com.tcms.execution.PlanStatisticsService}'s reason: a figure recomputed
 * per request cannot go stale relative to a case somebody authored a second ago, and this is the page
 * they land on straight afterwards. The activity series is the one part whose cost grows with the
 * project's <em>recent</em> authoring rather than staying flat — see
 * {@link DashboardTallies#creationTimes}, which states the bound.
 */
@Service
public class DashboardService {

    /** The window the activity chart plots, inclusive of today. The SPA's axis is drawn from it. */
    static final int ACTIVITY_DAYS = 30;

    private final ProjectService projects;
    private final ProjectConfigurationService configuration;
    private final SuiteRepository suites;
    private final CaseSearch search;
    private final DashboardTallies tallies;
    private final CaseHealthRepository healthRows;
    private final CaseOwners owners;
    private final Clock clock;

    public DashboardService(
            ProjectService projects,
            ProjectConfigurationService configuration,
            SuiteRepository suites,
            CaseSearch search,
            DashboardTallies tallies,
            CaseHealthRepository healthRows,
            CaseOwners owners,
            Clock clock) {

        this.projects = projects;
        this.configuration = configuration;
        this.suites = suites;
        this.search = search;
        this.tallies = tallies;
        this.healthRows = healthRows;
        this.owners = owners;
        this.clock = clock;
    }

    /**
     * One project's dashboard, or empty when the caller may not see the project.
     *
     * <p>The gate is {@link ProjectService#readVisible}, borrowed unchanged — the same two gates
     * {@code GET /projects/{projectId}} applies, company then explicit project grant. Nothing in this
     * class decides visibility, for {@link CaseService}'s reason: one gate that every endpoint
     * borrows fails every endpoint's tests when it is weakened, where a second copy here would fail
     * only the tests somebody remembered to write.
     */
    @Transactional(readOnly = true)
    public Optional<DashboardView> dashboard(UUID userId, UUID projectId) {
        UUID companyId = CompanyContext.require();

        return projects.readVisible(userId, projectId, project ->
                compute(companyId, userId, project.getId()));
    }

    private DashboardView compute(UUID companyId, UUID userId, UUID projectId) {
        Instant now = clock.instant();
        List<Suite> liveSuites = suites.findLiveForProject(projectId);

        CaseQuery unfiltered = CaseQuery.unfiltered();
        CaseScope scope = new CaseScope(
                projectId,
                // No suite filter here — the dashboard is the whole project — so no resolved
                // membership to bound it with (§98).
                null,
                liveSuites.stream().map(Suite::getId).toList(),
                // Unfiled cases count on the dashboard, and NOT counting them is why the empty-tree
                // guard that used to stand here is gone (§96). A project with no suites can hold
                // cases now — all of them unfiled — so returning zeros for one would be a dashboard
                // that says a project is empty while its grid lists forty cases. CaseScope tolerates
                // an empty suite set exactly when this flag is on, which is the same condition.
                true,
                null,
                null,
                null);

        List<ClassificationDimensionView> vocabularies =
                configuration.findClassification(userId, projectId).orElse(List.of());

        return new DashboardView(
                now,
                lifecycle(search.tally(unfiltered, scope, "lifecycleStatus", LifecycleStatus.class)),
                health(search.tallyHealth(unfiltered, scope)),
                healthRows.latestComputedAt(projectId),
                byFeature(unfiltered, scope, vocabularies),
                byOwner(companyId, unfiltered, scope),
                automation(search.tally(
                        unfiltered, scope, "automationStatus", AutomationStatus.class)),
                activity(unfiltered, scope, now));
    }

    // =====================================================================
    // tiles
    // =====================================================================

    /**
     * One tile per authoring status, <strong>including the statuses at zero</strong>.
     *
     * <p>Deliberately unlike the case list's facets, which drop empty values — and the difference is
     * worth stating because the two sit one click apart. A facet value is a filter <em>offer</em>,
     * and offering a chip that yields an empty grid is a dead end. A lifecycle tile is a
     * <em>distribution</em> over a closed set of four, where the zero is the information: "0
     * approved" is the single most useful number on a repository nobody has reviewed, and a panel
     * that silently dropped it would show three tiles and look complete.
     *
     * <p>That case is not hypothetical today. No endpoint can move a case out of {@code DRAFT} —
     * create fixes it and the editor deliberately has no status control until the review workflow
     * (§6g) — so a live project shows everything under Draft and three zeros. The panel reporting
     * that honestly is the point.
     */
    private static List<TileView> lifecycle(Map<LifecycleStatus, Long> counts) {
        List<TileView> tiles = new ArrayList<>(LifecycleStatus.values().length);
        for (LifecycleStatus status : LifecycleStatus.values()) {
            tiles.add(new TileView(
                    status.name(),
                    status.label(),
                    counts.getOrDefault(status, 0L),
                    // IN_REVIEW is the one that is about somebody: work waiting on a reviewer, which
                    // is an action rather than a state. The other three are just where cases are.
                    status == LifecycleStatus.IN_REVIEW
                            ? HealthSignal.Severity.INFO
                            : HealthSignal.Severity.NEUTRAL,
                    Map.of("lifecycleStatus", List.of(status.name()))));
        }
        return List.copyOf(tiles);
    }

    /**
     * One tile per raised health signal, worst first — and <strong>nothing for the signals at
     * zero</strong>.
     *
     * <p>The opposite rule to {@link #lifecycle} above, and for the opposite reason. Health is an
     * open-ended set of things that are <em>wrong</em>: a tile reading "0 stale approvals" is a
     * filter that opens an empty grid, and nine of them bury the two that matter. The panel is
     * headed "Needs attention", so an absent tile reads correctly as nothing to attend to.
     *
     * <p><strong>Which is exactly why {@code healthComputedAt} exists beside this.</strong> Nothing
     * writes {@code case_health} — the signals are derived from executions and documentation links,
     * and the computation has no owner yet — so this list is empty in every deployment, and an empty
     * "Needs attention" panel is a page telling the reader everything is fine. It is not; nobody has
     * looked. The timestamp is null in exactly that case and the SPA renders a different sentence for
     * it. Same defect class as §46's {@code met: null} rendering as a failed gate, caught before it
     * could ship rather than after.
     */
    private static List<TileView> health(Map<HealthSignal, Long> counts) {
        List<TileView> tiles = new ArrayList<>();
        for (HealthSignal signal : HealthSignal.values()) {
            long count = counts.getOrDefault(signal, 0L);
            if (count == 0) {
                continue;
            }
            tiles.add(new TileView(
                    signal.name(),
                    signal.label(),
                    count,
                    signal.severity(),
                    Map.of("healthSignal", List.of(signal.name()))));
        }
        // Sorted here rather than left to the SPA, which sorts by severity too: the order is a
        // property of the answer, and a client that forgot would render a broken case below a
        // missing link. The SPA's own sort is then a no-op over an already-ordered list.
        tiles.sort(Comparator.comparing(TileView::severity)
                .thenComparing(Comparator.comparingLong(TileView::count).reversed()));
        return List.copyOf(tiles);
    }

    // =====================================================================
    // breakdowns
    // =====================================================================

    /**
     * Cases per Feature, split by authoring status.
     *
     * <p><strong>The bars do not sum to the project's case count, and must not.</strong> Feature is
     * the one MULTI dimension (§6d): a case covering checkout and refunds belongs in both bars. This
     * is a breakdown, not a partition, and the alternative — attributing each case to one feature —
     * would need a rule for picking which, invented here, disagreeing with every other reading of the
     * same data.
     *
     * <p>Labelled by {@code path} rather than by {@code name}: the vocabulary is hierarchical, and
     * two features called "Refunds" under different parents are two different things a reader must be
     * able to tell apart. The separator matches the SPA's own rendering of a classification path.
     *
     * <p>Values nobody has classified a case under are omitted. Unlike a lifecycle status, a Feature
     * vocabulary is client-authored and can hold dozens of aspirational entries; a panel of mostly
     * empty bars would bury the ones carrying work. Ordered by case count, so the biggest area of the
     * product is the first thing read.
     */
    private List<BreakdownView> byFeature(
            CaseQuery request, CaseScope scope, List<ClassificationDimensionView> vocabularies) {

        Map<UUID, String> labels = new LinkedHashMap<>();
        for (ClassificationDimensionView dimension : vocabularies) {
            if (!dimension.key().equals(DimensionKeys.FEATURE)) {
                continue;
            }
            for (ClassificationValueView value : dimension.values()) {
                labels.put(value.id(), label(value, dimension.values()));
            }
        }

        Map<UUID, Map<LifecycleStatus, Long>> counts =
                tallies.byClassification(request, scope, List.copyOf(labels.keySet()));

        List<BreakdownView> bars = new ArrayList<>(counts.size());
        for (Map.Entry<UUID, Map<LifecycleStatus, Long>> entry : counts.entrySet()) {
            bars.add(new BreakdownView(
                    entry.getKey().toString(),
                    labels.get(entry.getKey()),
                    segments(entry.getValue(), status -> Map.of(
                            "featureId", entry.getKey().toString(),
                            "lifecycleStatus", List.of(status.name())))));
        }
        bars.sort(byTotalDescending());
        return List.copyOf(bars);
    }

    /**
     * A value's full path through its vocabulary — {@code Payments › Refunds}.
     *
     * <p>Walked up through {@code parentId} rather than read from the {@code ltree} {@code path}
     * column, because that column holds slugs and this is display text. The walk is over a list of
     * tens held in memory, and it is bounded by the vocabulary's size so a cycle — which the
     * {@code V2} trigger already refuses — cannot spin here either.
     */
    private static String label(ClassificationValueView value, List<ClassificationValueView> all) {
        Map<UUID, ClassificationValueView> byId = new HashMap<>();
        for (ClassificationValueView candidate : all) {
            byId.put(candidate.id(), candidate);
        }

        List<String> names = new ArrayList<>();
        ClassificationValueView current = value;
        while (current != null && names.size() <= all.size()) {
            names.add(0, current.name());
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        return String.join(" › ", names);
    }

    /**
     * Cases per owner, split by authoring status.
     *
     * <p>A partition, unlike {@link #byFeature} — a case has at most one owner — <em>minus the
     * unowned</em>, which is the one thing to know when reading it against the tiles above. See
     * {@link DashboardTallies#byOwner} for why an "unassigned" bar would be the one row on the panel
     * whose own link could not reproduce it.
     *
     * <p>An owner id that resolves to no account is dropped rather than rendered under a placeholder:
     * {@link CaseOwners} answers with what it can resolve, and inventing "Unknown user" for a row
     * whose link works perfectly well would put a fiction on a screen people plan from. Ordered by
     * case count.
     */
    private List<BreakdownView> byOwner(UUID companyId, CaseQuery request, CaseScope scope) {
        Map<UUID, Map<LifecycleStatus, Long>> counts = tallies.byOwner(request, scope);
        Map<UUID, UserRefView> people = owners.resolve(companyId, counts.keySet());

        List<BreakdownView> bars = new ArrayList<>(counts.size());
        for (Map.Entry<UUID, Map<LifecycleStatus, Long>> entry : counts.entrySet()) {
            UserRefView person = people.get(entry.getKey());
            if (person == null) {
                continue;
            }
            bars.add(new BreakdownView(
                    entry.getKey().toString(),
                    person.displayName(),
                    segments(entry.getValue(),
                            status -> DashboardView.ownedBy(entry.getKey(), status))));
        }
        bars.sort(byTotalDescending());
        return List.copyOf(bars);
    }

    /**
     * One bar's segments, in lifecycle order and skipping the empty ones.
     *
     * <p>Empty segments are dropped here where they are kept in {@link #lifecycle}, and the rule is
     * the same one both times: a tile is a distribution the reader is asked to read, a segment is a
     * one-pixel sliver they are asked to click. A zero-width link is not a link.
     *
     * <p>The colours are CSS custom-property <em>names</em>, resolved by the browser against whatever
     * theme is active. A hex value computed here would be the single element on the page that ignored
     * dark mode.
     */
    private static List<SegmentView> segments(
            Map<LifecycleStatus, Long> counts,
            Function<LifecycleStatus, Map<String, Object>> filter) {

        List<SegmentView> segments = new ArrayList<>();
        for (LifecycleStatus status : LifecycleStatus.values()) {
            long count = counts.getOrDefault(status, 0L);
            if (count == 0) {
                continue;
            }
            segments.add(new SegmentView(
                    status.name(),
                    status.label(),
                    count,
                    "var(--color-" + status.name().toLowerCase(Locale.ROOT)
                            .replace('_', '-') + ")",
                    filter.apply(status)));
        }
        return List.copyOf(segments);
    }

    private static Comparator<BreakdownView> byTotalDescending() {
        Comparator<BreakdownView> byTotal = Comparator.comparingLong(bar ->
                bar.segments().stream().mapToLong(SegmentView::count).sum());
        // Label breaks the tie so the order is stable between two requests over unchanged data —
        // otherwise a panel of equally-sized bars reshuffles on every refresh, which reads as the
        // numbers moving.
        return byTotal.reversed().thenComparing(BreakdownView::label);
    }

    // =====================================================================
    // automation coverage
    // =====================================================================

    /**
     * Automation coverage, denominated against what <em>can</em> be automated (plan §6c).
     *
     * <p>{@code NOT_AUTOMATABLE} cases are out of the divisor, and that is the whole honesty of the
     * figure: with them in, marking a case impossible <em>improves</em> the number, so the metric
     * would reward giving up. {@code V1}'s {@code ck_not_automatable_reason} is the other half —
     * a case cannot sit in that status without a written reason, so the exclusion cannot be used
     * casually.
     *
     * <p>Zero when nothing is automatable, rather than a division by zero or a null. A project whose
     * every case is marked impossible has no coverage figure to report, and 0% is the reading that
     * does not overstate it.
     */
    private static AutomationCoverageView automation(Map<AutomationStatus, Long> counts) {
        long automated = counts.getOrDefault(AutomationStatus.AUTOMATED, 0L);
        long toBeAutomated = counts.getOrDefault(AutomationStatus.TO_BE_AUTOMATED, 0L);
        long manual = counts.getOrDefault(AutomationStatus.MANUAL, 0L);
        long notAutomatable = counts.getOrDefault(AutomationStatus.NOT_AUTOMATABLE, 0L);

        long automatable = automated + toBeAutomated + manual;
        return new AutomationCoverageView(
                automated,
                toBeAutomated,
                manual,
                notAutomatable,
                automatable == 0 ? 0.0 : Math.round(automated * 1000.0 / automatable) / 10.0);
    }

    // =====================================================================
    // activity
    // =====================================================================

    /**
     * Thirty days of authoring, one point per day, <strong>including the days nothing happened</strong>.
     *
     * <p>The zero days are not padding. A chart drawn only from the days with activity compresses a
     * quiet fortnight into no space at all, so a burst of work three weeks ago sits next to today and
     * the shape lies about the tempo — which is the only thing a thirty-day chart is read for.
     *
     * <p>Bucketed in <strong>UTC</strong>, matching {@code generatedAt} and every other timestamp on
     * the wire. A viewer in UTC+13 sees their late evening counted under tomorrow; the alternative is
     * a per-request time zone the server would have to be told, and a "today" that disagrees with
     * every other date on the screen.
     */
    private List<ActivityPointView> activity(CaseQuery request, CaseScope scope, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate from = today.minusDays(ACTIVITY_DAYS - 1L);
        Instant since = from.atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<LocalDate, Long> created = perDay(tallies.creationTimes(request, scope, since));
        Map<LocalDate, Long> modified = perDay(tallies.editTimes(request, scope, since));

        List<ActivityPointView> days = new ArrayList<>(ACTIVITY_DAYS);
        for (int offset = 0; offset < ACTIVITY_DAYS; offset++) {
            LocalDate day = from.plusDays(offset);
            days.add(new ActivityPointView(
                    day,
                    created.getOrDefault(day, 0L),
                    modified.getOrDefault(day, 0L)));
        }
        return List.copyOf(days);
    }

    /** The same thirty days, all empty — for a project with nothing in it. */
    private static List<ActivityPointView> quietDays(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate from = today.minusDays(ACTIVITY_DAYS - 1L);

        List<ActivityPointView> days = new ArrayList<>(ACTIVITY_DAYS);
        for (int offset = 0; offset < ACTIVITY_DAYS; offset++) {
            days.add(new ActivityPointView(from.plusDays(offset), 0, 0));
        }
        return List.copyOf(days);
    }

    private static Map<LocalDate, Long> perDay(Collection<Instant> moments) {
        Map<LocalDate, Long> counts = new HashMap<>();
        for (Instant moment : moments) {
            counts.merge(LocalDate.ofInstant(moment, ZoneOffset.UTC), 1L, Long::sum);
        }
        return counts;
    }
}
