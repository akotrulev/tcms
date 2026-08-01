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
 * <h2>The four are §6l's defaults minus one, and that is a decision</h2>
 *
 * <p>§6l's default column set is {@code To Do · In Progress · Blocked · In Review · Done}.
 * {@code BLOCKED} is <strong>deliberately absent</strong>: the board renders exactly the columns
 * this enum names, and a status with no column is a card that exists, is returned by the API, and
 * appears nowhere on screen — invisible rather than misplaced, which is the worse of the two. Adding
 * it is a contract change, a label, and a fifth column in the grid; doing that without the layout
 * work would ship the invisible-card defect on purpose.
 */
public enum WorkItemStatus {
    TODO,
    IN_PROGRESS,
    IN_REVIEW,
    DONE
}
