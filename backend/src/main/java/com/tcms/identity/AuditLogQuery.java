package com.tcms.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The audit trail's filters, carried as one value.
 *
 * <p>Seven parameters between the controller and the repository, and a record rather than seven
 * arguments so neither {@link AuditLogService} nor {@link AuditLogController} grows a signature
 * where two adjacent {@code UUID}s can be swapped without the compiler noticing —
 * {@code projectId} and {@code actorUserId} are exactly that pair.
 *
 * <p><strong>Every filter is nullable and null means "unfiltered", never "match null".</strong>
 * That is the distinction the repository's {@code :value IS NULL OR column = :value} spells out,
 * and it matters most for {@code projectId} on the company trail: rows with no project are the
 * company-scope acts, and a filter that matched them on an absent parameter would make "the whole
 * company's trail" mean "the acts belonging to no project".
 *
 * @param projectId    the company trail's project filter. Unused by the project trail, whose
 *                     project comes from the path and is a scope rather than a filter.
 * @param actorUserId  acts by one person
 * @param action       one exact dotted verb. Matched exactly and never as a prefix — {@code case.}
 *                     is a naming convention, not a family, and a prefix match would promote it to
 *                     one where nothing else in the product treats it as one.
 * @param occurredAfter  inclusive lower bound
 * @param occurredBefore inclusive upper bound
 */
public record AuditLogQuery(
        UUID projectId,
        UUID actorUserId,
        String action,
        Instant occurredAfter,
        Instant occurredBefore,
        int page,
        int size) {

    /** {@code PageMeta}'s ceiling, as everywhere else in this contract. */
    static final int MAX_PAGE_SIZE = 200;

    /** The contract's default, and what a client that sends no {@code size} gets. */
    static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Clamps paging to what the contract declares, so a hand-built request cannot ask for the whole
     * table in one response.
     *
     * <p>In the compact constructor rather than in the service, because the service is not the only
     * possible caller and a ceiling enforced at one call site is a ceiling the next call site does
     * not have. A negative page and a zero size both become the default rather than a 400: the
     * contract declares no 400 on either read, and refusing a paging parameter would make a
     * malformed bookmark an error page instead of the first page.
     */
    public AuditLogQuery {
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    }
}
