package com.tcms.tracker;

import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /issues/search} — issues by key prefix or title, across the caller's projects (021 D-j).
 *
 * <h2>The gate is borrowed, and the narrowing is the gate's own output</h2>
 *
 * <p>{@link ProjectService#readAllVisible} hands back exactly the projects this caller holds
 * {@code project:read} on, computed by the one implementation of that question. Every issue read
 * below comes from those project ids and no others, so a project the caller was never granted
 * cannot contribute a row — and an id naming an ungranted project and one naming no project at all
 * are indistinguishable, which is the disclosure rule §6t states. {@code CrossProjectSearchService}
 * one module over makes the same call for the same reason.
 *
 * <h2>Key prefix first, then title — and the order is the feature</h2>
 *
 * <p>The palette's whole job is "I know what I am looking for". Somebody typing {@code ACME-1} means
 * the issue called {@code ACME-1}, and a title match ranked above it is the result they scroll past.
 * So key-prefix hits come first in key order, then title hits in key order, with nothing appearing
 * twice.
 *
 * <h2>Read in Java rather than as one query, deliberately and provisionally</h2>
 *
 * <p>The issues of the caller's visible projects are read per project and filtered here. That is
 * honest at this size and would not be at ten thousand issues a project: the shape to reach for
 * then is a single {@code project_id IN (…)} query with the predicate in SQL and the cap applied by
 * the database, which is what {@code CaseSearch} does one module over. It is not that yet because
 * this read has no paging and one cap — twenty rows — and a projection that answered the cap
 * correctly and the ORDER wrongly would be worse than a slower one that answers both.
 */
@Service
public class IssueSearchService {

    /** The contract's cap when {@code limit} is omitted. It is a palette, not a list screen. */
    public static final int DEFAULT_LIMIT = 20;

    /** The contract's ceiling, so a caller cannot ask for a company-sized answer. */
    public static final int MAX_LIMIT = 50;

    private final ProjectService projects;
    private final IssueRepository issues;
    private final IssueTypeRepository types;
    private final IssueStatusRepository statuses;

    public IssueSearchService(
            ProjectService projects,
            IssueRepository issues,
            IssueTypeRepository types,
            IssueStatusRepository statuses) {

        this.projects = projects;
        this.issues = issues;
        this.types = types;
        this.statuses = statuses;
    }

    @Transactional(readOnly = true)
    public List<IssueSearchHitView> search(UUID userId, String query, Integer limit) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        int cap = capOf(limit);

        return projects.readAllVisible(userId, visible -> {
            List<IssueSearchHitView> byKey = new ArrayList<>();
            List<IssueSearchHitView> byTitle = new ArrayList<>();

            for (Project project : visible) {
                Map<UUID, IssueType> typesById = new LinkedHashMap<>();
                types.findByProjectIdOrderByPositionAsc(project.getId())
                        .forEach(type -> typesById.put(type.getId(), type));
                Map<UUID, IssueStatus> statusesById = new LinkedHashMap<>();
                statuses.findByProjectIdOrderByPositionAsc(project.getId())
                        .forEach(status -> statusesById.put(status.getId(), status));

                for (Issue issue : issues.findByProjectIdAndDeletedAtIsNull(project.getId())) {
                    boolean keyHit =
                            issue.getIssueKey().toLowerCase(Locale.ROOT).startsWith(needle);
                    boolean titleHit = !keyHit
                            && issue.getTitle().toLowerCase(Locale.ROOT).contains(needle);
                    if (!keyHit && !titleHit) {
                        continue;
                    }
                    IssueType type = typesById.get(issue.getTypeId());
                    IssueStatus status = statusesById.get(issue.getStatusId());
                    if (type == null || status == null) {
                        // An issue pointing outside its own project's catalogue is a defect the
                        // detail read throws on. A palette is not where somebody should meet it,
                        // and a row it cannot draw is a row it leaves out.
                        continue;
                    }
                    (keyHit ? byKey : byTitle).add(new IssueSearchHitView(
                            issue.getIssueKey(),
                            issue.getTitle(),
                            type.getLevel(),
                            project.getId(),
                            project.getName(),
                            IssueStatusView.of(status)));
                }
            }

            // key_seq is the only column that may order issue keys — sorting the STRING puts
            // ACME-100 before ACME-2 — but a cross-project list has no single sequence, so the two
            // groups are ordered by project name and then by key length before text, which is the
            // cheapest ordering that does not read as random within one project.
            Comparator<IssueSearchHitView> order = Comparator
                    .comparing(IssueSearchHitView::projectName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(hit -> hit.key().length())
                    .thenComparing(IssueSearchHitView::key);
            byKey.sort(order);
            byTitle.sort(order);

            List<IssueSearchHitView> hits = new ArrayList<>(byKey);
            hits.addAll(byTitle);
            return List.copyOf(hits.subList(0, Math.min(cap, hits.size())));
        });
    }

    /**
     * The cap, clamped rather than refused.
     *
     * <p>A palette sends what its own list can draw, and a 400 on {@code limit=500} would be a
     * refusal the caller cannot act on from a keystroke. The contract's {@code maximum: 50} is what
     * this clamps to; the absent parameter is {@link #DEFAULT_LIMIT}, applied here and not as an
     * OpenAPI {@code default:} — that would make the parameter required in the generated client.
     */
    private static int capOf(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
