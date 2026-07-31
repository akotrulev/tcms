package com.tcms.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code sort=field,dir} → an {@code ORDER BY}, over the fields a case list may honestly be
 * ordered on.
 *
 * <h2>An allow-list, because the parameter is user input reaching a query</h2>
 *
 * <p>The field name arrives from the URL and ends up naming a column. Passed through to
 * {@code root.get(name)} it would let any request name any mapped attribute — {@code companyId}
 * among them — and while ordering by a column does not disclose it, "user input selects the column"
 * is one refactor away from "user input selects the predicate". An unknown field falls back to
 * {@link #DEFAULT} rather than failing: the SPA's grid offers sortable headers this endpoint cannot
 * serve (below), and a 400 on a header click is a dead end the user cannot act on.
 *
 * <h2>Never the text key</h2>
 *
 * <p>{@code key} resolves to {@code keySeq}. Ordering on the text is the §3-lesson-6 bug:
 * {@code TC-10000} sorts before {@code TC-9999}, the grid looks sorted, and nothing is visibly
 * wrong until a company writes its ten-thousandth case — by which point the order has been quietly
 * wrong for a while.
 *
 * <h2>Enums sort by meaning, not by spelling — and this is the difference that matters</h2>
 *
 * <p>{@code priority} stored as text sorts CRITICAL, HIGH, <strong>LOW, MEDIUM</strong>. That is
 * alphabetical, it is what a plain column sort gives, and it is wrong in the way this project keeps
 * finding: the column looks sorted, every value is in a plausible place, and nothing fails. The
 * same applies to {@code lifecycleStatus} (APPROVED before DRAFT) and {@code automationStatus}
 * (AUTOMATED before MANUAL).
 *
 * <p>So every enum column orders by its <em>declaration</em> order instead, through a CASE
 * expression built from the enum itself ({@link #byDeclaredOrder}). The declaration order of each
 * enum is its semantic order — most-urgent-first for priority, earliest-lifecycle-first for the
 * other two — which is stated on those types and is the thing a reader has to be able to trust.
 *
 * <p><strong>The SPA's mock sorts these alphabetically</strong> ({@code localeCompare} in
 * {@code handlers.ts}), so mock and live disagree here on purpose. The mock is the one that is
 * wrong; recorded rather than matched.
 *
 * <h2>{@code suiteName} is a scalar subquery, not a join</h2>
 *
 * <p>A case carries {@code suiteId} and no mapped association (see {@link TestCase}), so ordering
 * by the folder's name needs another table. A correlated scalar subquery does it without a join and
 * without the mapped {@code @ManyToOne} that would make every 200-row page a lazy-load storm.
 *
 * <h2>One field the SPA offers and this cannot sort by</h2>
 *
 * <p>{@code owner} would need {@code app_user} inside this module's ORDER BY. {@code lastResult}
 * and {@code healthSignals} are already {@code sortable: false} in {@code CaseGrid.tsx};
 * {@code owner} is not, so clicking that header reorders by key — in live mode <em>and</em> in mock
 * mode, whose handler falls back the same way. Identical in both, and a real gap: recorded in the
 * handoff rather than papered over here.
 */
final class CaseSort {

    /** The contract's example and the SPA's {@code DEFAULT_SORT}. */
    static final String DEFAULT = "keySeq,asc";

    private CaseSort() {
    }

    /**
     * The {@code ORDER BY} list for one request, always ending in {@code keySeq}.
     *
     * <p>The trailing {@code keySeq} is not decoration. Every other sortable field has duplicates —
     * a project holds two hundred cases at priority MEDIUM — and Postgres guarantees no ordering
     * within a tie, so without a total order the same case can appear on page 1 and page 2, or on
     * neither. Paging over an unstable sort is the defect that reads as "rows go missing", and it
     * shows up only once the result set outgrows one page, which is to say never in a fixture that
     * fits on one.
     */
    static List<Order> orderBy(
            String sort, Root<TestCase> root, CriteriaQuery<?> query, CriteriaBuilder cb) {

        String[] parts = (sort == null || sort.isBlank() ? DEFAULT : sort).split(",", 2);
        boolean descending = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim());

        List<Order> orders = new ArrayList<>();
        Expression<?> primary = expression(parts[0].trim(), root, query, cb);
        if (primary != null) {
            orders.add(descending ? cb.desc(primary) : cb.asc(primary));
        }
        orders.add(cb.asc(root.get("keySeq")));
        return orders;
    }

    /**
     * The expression for one field name, or null when the field <em>is</em> the tie-breaker or is
     * not sortable — in both cases {@code keySeq} alone is the whole ordering.
     */
    private static Expression<?> expression(
            String field, Root<TestCase> root, CriteriaQuery<?> query, CriteriaBuilder cb) {

        return switch (field.toLowerCase(Locale.ROOT)) {
            case "key", "keyseq" -> null;
            case "title" -> root.get("title");
            case "updatedat" -> root.get("updatedAt");
            case "lifecyclestatus" ->
                    byDeclaredOrder(root.get("lifecycleStatus"), LifecycleStatus.values(), cb);
            case "priority" -> byDeclaredOrder(root.get("priority"), Priority.values(), cb);
            case "automationstatus" ->
                    byDeclaredOrder(root.get("automationStatus"), AutomationStatus.values(), cb);
            case "suitename" -> suiteName(root, query, cb);
            default -> null;
        };
    }

    /**
     * {@code CASE WHEN col = 'CRITICAL' THEN 0 WHEN col = 'HIGH' THEN 1 … END} — the enum's own
     * declaration order, as a sortable integer.
     *
     * <p>Derived from {@code values()} rather than written out, so a new member is ordered
     * correctly the moment it is declared and there is no second list to forget to update. That is
     * the same "derive the invariant, don't restate it" rule §3 lesson 10 records: a hand-written
     * rank table would go stale silently, and the symptom would be one status sorting to the end
     * for no visible reason.
     */
    private static <E extends Enum<E>> Expression<Integer> byDeclaredOrder(
            Path<E> column, E[] values, CriteriaBuilder cb) {

        CriteriaBuilder.Case<Integer> ranked = cb.selectCase();
        for (E value : values) {
            ranked = ranked.when(cb.equal(column, value), value.ordinal());
        }
        // Unreachable while the column is NOT NULL and CHECK-constrained to these values; present
        // because otherwise a row outside the enum would sort as NULL, which Postgres puts LAST on
        // an ascending sort and FIRST on a descending one — the most confusing possible outcome for
        // a row that should not exist.
        return ranked.otherwise(values.length);
    }

    private static Expression<String> suiteName(
            Root<TestCase> root, CriteriaQuery<?> query, CriteriaBuilder cb) {

        Subquery<String> name = query.subquery(String.class);
        Root<Suite> suite = name.from(Suite.class);
        name.select(suite.get("name")).where(cb.equal(suite.get("id"), root.get("suiteId")));
        return name;
    }
}
