package com.tcms.tracker.imports;

import java.util.List;

/**
 * Everything one source project holds, as one read pass produced it.
 *
 * <h2>Whole, and in heap, and that is a decision with a stated ceiling (D-b)</h2>
 *
 * <p>The write is one all-or-nothing transaction, so it cannot begin until the read has finished —
 * a transaction spanning the read would hold a Postgres connection open for minutes against a third
 * party's availability, one pool slot per import, on the operation most likely to be slow. Batch
 * commits are the other tempting shape and are worse: a mid-import failure would leave <em>part of a
 * Jira project</em> in the customer's new tracker, with no way to tell which part, at the exact
 * moment they are being asked to trust the migration.
 *
 * <p>So the whole snapshot is held, and the bound is {@code tcms.tracker.imports.max-issues}. The
 * preview refuses above it <strong>naming the number</strong>.
 *
 * @param issues            every live issue, in the source's own order
 * @param statuses          every status the issues use, with counts — the mapping step's rows
 * @param types             every type the issues use, with counts
 * @param versions          fix versions, which become releases (D6)
 * @param people            every distinct assignee, reporter and comment author
 * @param fields            every field the project holds values for, for {@link DroppedFields}.
 *                          A field with zero values is absent: the preview's promise is about what
 *                          will be lost, and a column nobody filled in is not a loss.
 * @param historyEntryCount the changelog total. Counted and never imported — there is no table for
 *                          somebody else's audit trail — so the preview can say the number out loud
 *                          rather than letting it be discovered as an absence.
 */
public record SourceProjectSnapshot(
        List<SourceIssue> issues,
        List<SourceStatus> statuses,
        List<SourceType> types,
        List<SourceVersion> versions,
        List<SourcePerson> people,
        List<SourceField> fields,
        int historyEntryCount) {
}
