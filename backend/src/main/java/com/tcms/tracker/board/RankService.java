package com.tcms.tracker.board;

import com.tcms.project.GateResult;
import com.tcms.project.Project;
import com.tcms.project.ProjectService;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.Issue;
import com.tcms.tracker.IssueRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The fractional rank (D-d) — where an issue sits in its project's order.
 *
 * <h2>Neighbour-relative, never absolute</h2>
 *
 * <p>The client sends the issue the dragged row landed next to, not a number. Two people dragging at
 * once each computed a midpoint from a list they read seconds ago, and sending the number races
 * while sending the neighbour does not — the server reads the ranks inside the transaction that
 * writes. <strong>Exactly one of {@code afterKey} and {@code beforeKey}</strong>: both, or neither,
 * is refused rather than guessed at, because an empty body is not a request to move to the top.
 *
 * <h2>Why the column has no precision</h2>
 *
 * <p>Postgres {@code NUMERIC} without a precision is arbitrary precision, so a midpoint between two
 * neighbours <strong>always exists</strong> and placing one row <strong>never rewrites another</strong>.
 * A fixed {@code NUMERIC(12,4)} runs out after roughly fourteen inserts between one pair and then
 * returns a rank equal to a neighbour's — silently, with no error, and two rows whose order is
 * decided by whatever the tiebreaker happens to be.
 *
 * <h2>Growth, and the one place it is bounded</h2>
 *
 * <p>Precision is not free: exact halving adds a fractional digit per squeeze against the same
 * neighbour. The bound is here rather than in the type — past {@link #MAX_SCALE} fractional digits
 * the project is respaced to {@code 1024, 2048, 3072 …} in one statement and the caller is told, so
 * it can drop its own stale copy of every other row's rank.
 *
 * <h2>What this class does NOT own</h2>
 *
 * <p>The <strong>creation</strong> path. {@code issue.rank} is {@code NOT NULL} and is filled on
 * insert by {@code V5}'s {@code issue_rank_default} trigger, which stands aside whenever an insert
 * supplies a rank (D-k). Re-implementing "the next rank" here would be a second definition of it,
 * holding only for the callers that remembered to ask.
 */
@Service
public class RankService {

    /**
     * Twelve fractional digits, which exact halving reaches after a dozen squeezes against one
     * neighbour and no ordinary drag session comes near.
     */
    static final int MAX_SCALE = 12;

    private static final BigDecimal SPACING = new BigDecimal("1024");
    private static final BigDecimal TWO = new BigDecimal("2");

    /**
     * Ranking is its own key, unlike moving a card: {@code board:rank} is held by the tiers that
     * decide what is next, and dragging a backlog is not editing the issues in it.
     */
    private static final String RANK = "board:rank";

    private final ProjectService projects;
    private final IssueRepository issues;
    private final EntityManager entities;

    public RankService(ProjectService projects, IssueRepository issues, EntityManager entities) {
        this.projects = projects;
        this.issues = issues;
        this.entities = entities;
    }

    /** What happened. Sealed, so the controller's mapping over it is exhaustive at compile time. */
    public sealed interface Outcome {

        /**
         * The neighbours do not describe a gap — both were sent, neither was, one names nothing, or
         * one is in another project.
         */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Placed(RankResultView result) implements Outcome {
        }
    }

    public GateResult<Outcome> place(UUID userId, String key, RankInput input) {
        Optional<Issue> issue = issues.findByIssueKeyAndDeletedAtIsNull(key);
        if (issue.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        return projects.writeGranted(userId, issue.get().getProjectId(), RANK,
                project -> apply(project, key, input));
    }

    /**
     * Runs inside {@code writeGranted}'s transaction, and re-reads every row it touches.
     *
     * <p>The lookup in {@link #place} ran in the repository's own read-only transaction and its
     * entity is detached by the time this is called — the same shape {@code TransitionService} has,
     * where the detached instance is merged back by an explicit {@code save}. Here the rebalance
     * needs a {@code refresh}, which a detached entity refuses, so the managed copies are read here
     * rather than passed in.
     */
    private Outcome apply(Project project, String key, RankInput input) {
        Issue issue = issues.findByIssueKeyAndDeletedAtIsNull(key).orElseThrow();
        String after = blankToNull(input == null ? null : input.afterKey());
        String before = blankToNull(input == null ? null : input.beforeKey());

        if (after != null && before != null) {
            return refusal("afterKey", "Send the issue above OR the issue below, not both. The "
                    + "server computes the rank between them from what it reads now, which is what "
                    + "keeps two people dragging at once from overwriting each other.");
        }
        if (after == null && before == null) {
            return refusal("afterKey", "Name the issue this one should sit after, or the issue it "
                    + "should sit before. A rank is a position between two rows, never a number.");
        }

        String neighbourKey = after != null ? after : before;
        String field = after != null ? "afterKey" : "beforeKey";
        if (neighbourKey.equals(issue.getIssueKey())) {
            return refusal(field, "An issue cannot be placed relative to itself.");
        }

        Optional<Issue> neighbour = issues.findByIssueKeyAndDeletedAtIsNull(neighbourKey);
        if (neighbour.isEmpty()) {
            return refusal(field, "There is no issue called " + neighbourKey + ".");
        }
        if (!neighbour.get().getProjectId().equals(project.getId())) {
            // Not merely wrong: a rank is project-wide, so a midpoint against a number from another
            // project's sequence lands the issue in an order nobody can explain.
            return refusal(field, neighbourKey + " is in another project. An issue is ranked among "
                    + "its own project's work, so a neighbour from a different one describes no gap.");
        }

        Issue beside = neighbour.get();
        BigDecimal target = midpointAround(issue, beside, after != null);
        boolean rebalanced = target.scale() > MAX_SCALE;
        if (rebalanced) {
            rebalance(project.getId());
            entities.refresh(issue);
            entities.refresh(beside);
            target = midpointAround(issue, beside, after != null);
        }

        issue.rankAt(target);
        issues.save(issue);
        return new Outcome.Placed(
                new RankResultView(issue.getIssueKey(), target.toPlainString(), rebalanced));
    }

    /**
     * The rank that lands {@code moved} immediately after (or before) {@code neighbour}.
     *
     * <p>The far side of the gap is the neighbour's own neighbour in the ordered read, <em>excluding
     * the row being moved</em> — which is what makes re-sending the same request idempotent rather
     * than walking the issue one gap further each time.
     */
    private BigDecimal midpointAround(Issue moved, Issue neighbour, boolean placeAfter) {
        BigDecimal beyond = adjacentRank(moved, neighbour, placeAfter);
        BigDecimal lower = placeAfter ? neighbour.getRank() : beyond;
        BigDecimal upper = placeAfter ? beyond : neighbour.getRank();
        return midpoint(lower, upper);
    }

    /**
     * Halving is exact in base ten, so this never rounds and never returns a value equal to either
     * neighbour. The open-ended cases step a whole {@link #SPACING} rather than halving toward
     * nothing.
     */
    private static BigDecimal midpoint(BigDecimal lower, BigDecimal upper) {
        if (lower == null && upper == null) {
            return SPACING;
        }
        if (lower == null) {
            return upper.subtract(SPACING);
        }
        if (upper == null) {
            return lower.add(SPACING);
        }
        return lower.add(upper).divide(TWO).stripTrailingZeros();
    }

    /**
     * The rank of the row on the far side of the neighbour, or null when the neighbour is the end of
     * the list.
     *
     * <p>Compared as the whole ordering tuple {@code (rank, created_at, id)} rather than on
     * {@code rank} alone, because {@code rank} is deliberately not unique: two issues created in one
     * project in the same instant tie, and a query that stepped by rank alone would jump over one of
     * them and place the card on the wrong side of it.
     */
    private BigDecimal adjacentRank(Issue moved, Issue neighbour, boolean placeAfter) {
        String sql = placeAfter
                ? """
                  SELECT rank FROM issue
                   WHERE project_id = :projectId AND deleted_at IS NULL AND id <> :movedId
                     AND (rank, created_at, id) > (:rank, :createdAt, :neighbourId)
                   ORDER BY rank, created_at, id
                   LIMIT 1
                  """
                : """
                  SELECT rank FROM issue
                   WHERE project_id = :projectId AND deleted_at IS NULL AND id <> :movedId
                     AND (rank, created_at, id) < (:rank, :createdAt, :neighbourId)
                   ORDER BY rank DESC, created_at DESC, id DESC
                   LIMIT 1
                  """;

        entities.flush();
        List<?> rows = entities.createNativeQuery(sql)
                .setParameter("projectId", neighbour.getProjectId())
                .setParameter("movedId", moved.getId())
                .setParameter("rank", neighbour.getRank())
                .setParameter("createdAt", neighbour.getCreatedAt())
                .setParameter("neighbourId", neighbour.getId())
                .getResultList();
        return rows.isEmpty() ? null : (BigDecimal) rows.get(0);
    }

    /**
     * Respaces the whole project to {@code 1024, 2048, 3072 …} in one statement.
     *
     * <p>The only thing in this class that touches a row the caller did not name, and
     * {@code row_number()} over the <em>existing</em> order is what makes it order-preserving — a
     * respace that re-sorted would silently rearrange a backlog somebody spent an afternoon on, and
     * nothing would report it.
     *
     * <p>Native rather than JPQL because {@code row_number()} is a window function, and scoped to
     * the company as well as the project so the statement says out loud what RLS is already
     * enforcing.
     */
    private void rebalance(UUID projectId) {
        entities.flush();
        entities.createNativeQuery("""
                UPDATE issue i SET rank = ranked.seq * 1024
                  FROM (SELECT id, row_number() OVER (ORDER BY rank, created_at, id) AS seq
                          FROM issue
                         WHERE company_id = :companyId
                           AND project_id = :projectId
                           AND deleted_at IS NULL) ranked
                 WHERE i.id = ranked.id
                """)
                .setParameter("companyId", CompanyContext.require())
                .setParameter("projectId", projectId)
                .executeUpdate();
    }

    private static Outcome refusal(String field, String message) {
        return new Outcome.Invalid(Map.of(field, List.of(message)));
    }

    private static String blankToNull(String key) {
        return key == null || key.isBlank() ? null : key;
    }
}
