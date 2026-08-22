import type { AuditEvent } from '../../api/types';

/**
 * The audit trail's grouping arithmetic, in a module rather than on the page.
 *
 * Extracted from {@link AuditLogPage} because display logic belongs in the shared layer — the same
 * rule that put §245's selection arithmetic in `executionSelection.ts` one screen over — and
 * because a function exported from a component file costs fast refresh for the whole file.
 */

/** Consecutive rows of one bulk act — one summary row, expandable to the rows it covers. */
export type AuditGroup = { key: string; bulkOperationId: string | null; rows: AuditEvent[] };

/**
 * Consecutive rows of one bulk act, folded into one group.
 *
 * **A run rather than a scan, and that is load-bearing.** Every writer stamps a transaction's rows
 * from one `Instant` and the sort is `(occurredAt DESC, id DESC)`, so one act's rows are adjacent.
 * Grouping by scanning the whole page would additionally merge two acts that happen to share an id
 * across a boundary — which cannot happen — while costing the property that makes this cheap.
 *
 * **A group that straddles a page boundary renders as two groups, and that is honest.** The page is
 * server-paged and this screen sees 25–100 rows; a 300-row act spans several. Claiming a total the
 * page cannot see would be the false statement about the past this screen exists not to make. The
 * summary therefore counts *what is here*, in those words.
 *
 * A null `bulkOperationId` is always its own group of one, so every row written before §245 — which
 * is every row in every deployment until then — renders exactly as it did.
 */
export function groupByBulkOperation(rows: AuditEvent[]): AuditGroup[] {
  const groups: AuditGroup[] = [];
  for (const row of rows) {
    const previous = groups[groups.length - 1];
    if (row.bulkOperationId && previous?.bulkOperationId === row.bulkOperationId) {
      previous.rows.push(row);
      continue;
    }
    groups.push({ key: row.id, bulkOperationId: row.bulkOperationId ?? null, rows: [row] });
  }
  return groups;
}
