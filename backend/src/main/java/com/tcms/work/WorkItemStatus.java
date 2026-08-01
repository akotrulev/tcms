package com.tcms.work;

/**
 * A board column — {@code WorkItemStatus} in {@code docs/specs/openapi.yaml}.
 *
 * <h2>Why this is an enum and not the {@code TEXT} column {@code V1} declares</h2>
 *
 * <p>{@code work_item.status} is {@code TEXT NOT NULL DEFAULT 'TODO'} with no check constraint, so
 * the database will hold anything. Until §53 the contract agreed — {@code status} was an
 * unconstrained {@code string} — and the SPA compared it against English display text
 * ({@code 'To do'}). The mock served the same English because it had been written from the page, so
 * <strong>mock mode agreed with itself perfectly</strong> and nothing could reveal that a server
 * answering {@code TODO} would file every card into no column at all and render an empty board with
 * no error anywhere.
 *
 * <p>That is §52's defect one level down. There, two sides spelled a field differently; here,
 * neither side could be <em>wrong</em>, because no vocabulary had ever been fixed. Naming the four
 * values in the contract and mapping them to an enum on both sides is what makes disagreement
 * expressible — and therefore catchable.
 *
 * <p>Persisted {@code EnumType.STRING}, never ordinal: the column already holds {@code 'TODO'} as
 * its default, and an ordinal mapping would silently reinterpret every existing row the day someone
 * inserts a member in the middle.
 *
 * <h2>The five are §6l's defaults, and the fifth arrived a slice late on purpose</h2>
 *
 * <p>§6l's default column set is {@code To Do · In Progress · Blocked · In Review · Done}.
 * {@code BLOCKED} was <strong>deliberately absent until §54</strong>, and the reason is worth
 * keeping now that it is here: the board renders exactly the columns this enum names, so a status
 * with no column is a card that exists, is returned by the API, and appears <em>nowhere on
 * screen</em> — invisible rather than misplaced, which is the worse of the two failures. Adding the
 * value alone would have shipped that defect on purpose; the value, the label and the fifth grid
 * column land together.
 *
 * <p>The SPA now asserts the pairing rather than promising it: {@code BoardPage}'s
 * {@code _everyStatusHasAColumn} is a compile error naming any status the grid has no column for,
 * because {@code satisfies readonly WorkItemStatus[]} rejects a column that is not a status and
 * silently accepts a <em>subset</em>. Only one direction was ever checked.
 *
 * <h2>Nothing here refuses a transition, including into and out of {@code BLOCKED}</h2>
 *
 * <p>A task's status is its own — {@code BoardService.transitionsFor} gives a stored task every
 * column but its current one — so blocking and unblocking need no workflow. In particular there is
 * no rule that a blocked task must return to the column it came from: the board is where people
 * correct their own record of what is happening, and a board that argues is a board people stop
 * updating.
 */
public enum WorkItemStatus {
    TODO,
    IN_PROGRESS,
    BLOCKED,
    IN_REVIEW,
    DONE
}
