import type { BoardConfig, IssueStatusRef } from '../../api/types';

/**
 * ~~Story B2's invariant — *every status claimed by exactly one column* — and the draft it is
 * computed over.~~ → **half of it, since 021 R7 (D-e).** No JSX, which is the point of the file.
 *
 * <h2>What R7 took out, and what it left</h2>
 *
 * B2 had two halves and only one of them survives here. *Claimed at most once per board* is a real
 * invariant, is a unique index on the server, and still refuses a save. *Claimed by **every**
 * board* is gone — it is the rule that forced every board to show the whole lifecycle, and a board
 * that claims a subset is the entire point of the three-board contract.
 *
 * So {@link Coverage.unclaimed} is still **derived**, and {@link Coverage.holds} no longer reads it.
 * A status this board does not claim is now an ordinary configuration and gets a **warning**, not a
 * refusal; the failure the old rule actually protected against — a card in a status *no board at
 * all* shows — is a different question over a different set, answered by
 * `GET /projects/{id}/boards/coverage` and rendered by `CoverageMatrix`. It cannot be answered from
 * one board's draft, which is why it is not answered here.
 *
 * It is not in `ColumnEditor.tsx` beside its only renderer for the reason `cardMove.ts` is not in
 * `ProjectBoardView.tsx`: a module exporting a component **and** a function trips oxlint's
 * `react(only-export-components)`, and the lint gate is zero warnings. The split has a second
 * payoff the board's did too — the derivation is unit-testable without rendering anything, and both
 * readers of it (the editor's banner and the page's coverage table) ask the same function, so a
 * board cannot be refused by one and accepted by the other.
 */

/**
 * A column being edited. `id` is null for one this session added, which is also what tells the
 * server to create it rather than update it (`BoardColumnInput.id`, absent to create).
 *
 * `rowId` is the React key and is stable across a reorder or a rename, for `WorkflowSettingsPage`'s
 * reason: keying on `id` would remount every unsaved column the moment one of them was saved.
 */
export interface DraftColumn {
  id: string | null;
  rowId: string;
  name: string;
  wipLimit: number | null;
  statusIds: string[];
  defaultStatusId: string;
}

/**
 * Story B2's invariant, in one place: every status claimed by **exactly one** column, and no column
 * claiming nothing.
 *
 * The third arm is the contract's, not the mockup's — `BoardColumnInput.statusIds` is `minItems: 1`
 * and the service *"refuses to create a column claiming nothing, because it is a column no card can
 * ever be in"*. It is reachable here the moment somebody unclaims a column's last status, so it is
 * checked beside the other two rather than left to the 400.
 */
export interface Coverage {
  /**
   * Statuses no column on this board claims, in workflow order.
   *
   * **Not a refusal since 021 R7** — a board claims a subset, so this is the ordinary state of every
   * board §2.4 seeds. It is still derived because the screen still says it: the panel notes which
   * statuses this board does not show, which is a fact a person configuring one wants.
   */
  unclaimed: IssueStatusRef[];
  /** Statuses more than one column claims — see the header for why this is derived, not assumed. */
  claimedTwice: IssueStatusRef[];
  /** Columns claiming nothing. */
  empty: DraftColumn[];
  /** The two arms the save is still refused for. `unclaimed` is deliberately not one of them. */
  holds: boolean;
}

export function claimantsOf(
  columns: readonly DraftColumn[],
  statusId: string,
): DraftColumn[] {
  return columns.filter((column) => column.statusIds.includes(statusId));
}

export function coverageOf(
  statuses: readonly IssueStatusRef[],
  columns: readonly DraftColumn[],
): Coverage {
  const unclaimed: IssueStatusRef[] = [];
  const claimedTwice: IssueStatusRef[] = [];
  for (const status of statuses) {
    const count = claimantsOf(columns, status.id).length;
    if (count === 0) unclaimed.push(status);
    if (count > 1) claimedTwice.push(status);
  }
  const empty = columns.filter((column) => column.statusIds.length === 0);
  return {
    unclaimed,
    claimedTwice,
    empty,
    // 021 D-e: `claimedTwice` (a unique index) and `empty` (`minItems: 1` on the contract) are the
    // two the server still refuses. `unclaimed` is a warning the screen renders and never a gate.
    holds: claimedTwice.length === 0 && empty.length === 0,
  };
}

/**
 * What the board's banner says when the save is refused — the sentence the plan's own test reads.
 *
 * One sentence per arm rather than a list of everything wrong at once: the arms are repaired one
 * at a time and a reader fixing the first does not need the other restated under it. ~~Three
 * arms.~~ → **two, since 021 D-e**: an unclaimed status is a warning on the coverage matrix now,
 * and a sentence here would say *refused* about something that saves.
 */
export function coverageProblem(coverage: Coverage): string | null {
  if (coverage.claimedTwice.length > 0) {
    return `Claimed by more than one column: ${coverage.claimedTwice.map((s) => s.name).join(', ')}.`;
  }
  if (coverage.empty.length > 0) {
    const names = coverage.empty.map((column) => column.name || 'the unnamed column').join(', ');
    return `Claiming no status: ${names}. A column no card can ever be in is refused.`;
  }
  return null;
}

/**
 * The same verdict as a clause rather than a sentence — what the coverage table's footer says
 * beside a board's name.
 *
 * Two registers off one derivation, and deliberately not the same string twice: the table lists
 * *which board* is wrong, the board's own panel says *what to do about it*, and the two sit a
 * screen apart. One sentence rendered in both places is the same words twice within a scroll of
 * each other, which reads as a rendering bug rather than as two facts.
 */
export function coverageHeadline(coverage: Coverage): string | null {
  const names = (rows: readonly { name: string }[]) =>
    rows.map((row) => row.name || 'an unnamed column').join(', ');
  if (coverage.claimedTwice.length > 0) {
    return `${names(coverage.claimedTwice)} on more than one column`;
  }
  if (coverage.empty.length > 0) return `${names(coverage.empty)} claiming no status`;
  return null;
}

/** The draft a board arrives as. */
export function draftOf(board: BoardConfig): DraftColumn[] {
  return board.columns.map((column, index) => ({
    id: column.id,
    rowId: `${column.id}-${index}`,
    name: column.name,
    wipLimit: column.wipLimit ?? null,
    statusIds: column.statuses.map((status) => status.id),
    defaultStatusId: column.defaultStatusId,
  }));
}

/**
 * What this board does **not** show — a note on its own panel, never a refusal (021 D-e).
 *
 * The sentence a person configuring a board wants and the one the old rule made unnecessary by
 * making the state impossible: these statuses exist in the project's workflow and this board has no
 * column for them, so a card in one of them is simply not here. Whether it is anywhere at all is the
 * coverage matrix's question, which is over every board and cannot be answered from this draft.
 */
export function unclaimedNote(coverage: Coverage): string | null {
  if (coverage.unclaimed.length === 0) return null;
  const names = coverage.unclaimed.map((status) => status.name).join(', ');
  return `Not on this board: ${names}. A board claims a subset of the workflow — a card in one of `
    + 'these is on whichever board does claim it, and the coverage table below says which.';
}
