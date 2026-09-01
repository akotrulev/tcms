import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useLabels, useProjectMembers, useProjectWorkflow, useReleases } from '../../api/queries';
import type { BacklogFilters, BoardFilters, IssueFilters, IssueLevel, Priority } from '../../api/types';
import type { AppliedFilter } from '../../components/kit';
import { issueLevels, priorityLabels } from '../../lib/labels';

/**
 * D34's one filter, for all five views.
 *
 * **In the URL, never in a store and never in `useState`.** Two views reading one filter cannot be
 * a `useState` in either of them, and a store would be server-state's filter living somewhere the
 * URL cannot restore from a pasted link. This hook is the only reader and the only writer.
 *
 * M2 adds `labelId` and `priority` to what M1 read. Both are repeated parameters and both are
 * **OR within themselves, AND against the others** — Trello's semantics, copied precisely because
 * they are learnable (§12.2).
 *
 * <h2>One URL, three endpoints, and the mapping is not a cast (D-v)</h2>
 *
 * `IssueFilters` and `BoardFilters` differ in **both** directions: the table's `GET /issues` takes
 * `statusId[]` and `parentKey`, the board's `/boards/{id}/cards` and the backlog's
 * `/projects/{id}/backlog` take `labelId[]` and `priority[]`. So this hook reads the URL once and
 * hands each view **the subset its own endpoint declares** — sending a board a `statusId` is a 400,
 * and it is also incoherent on its face, because a status filter would empty the columns the board
 * is drawn from.
 *
 * Since 013 T6 that is **three** subsets rather than two: the backlog takes a release filter the
 * board does not. See {@link WorkFilters}`.backlog`, which is where the reason is written.
 *
 * Two `useMemo`s over one URL, in two files, is exactly the drift this removes. Neither was wrong
 * on the day it was written, which is the point.
 *
 * <h2>`view`, `sort`, `cols` and `board` are deliberately NOT part of this</h2>
 *
 * They are which view you are looking at, not which work you are looking at, and folding them in
 * would make a filter object that changes when you switch tabs — which would evict every cached
 * list on a tab switch. {@link CARRIED} is the same list read from the other side: what
 * `clearFilters` keeps. Clearing a filter set on the stakeholder board and landing on the
 * engineering one is a surprise, and re-picking it is a second step nobody asked for; the same
 * argument covers the chosen sort and the chosen columns.
 */
export interface WorkFilters {
  /** What `GET /issues` declares — the table's and the calendar's. */
  issue: IssueFilters;
  /** What `GET /boards/{id}/cards` declares — the board's. */
  board: BoardFilters;
  /**
   * What `GET /projects/{id}/backlog` declares — {@link BacklogFilters}, the board's six **plus
   * `releaseId` and `unscheduled`** (013 T6).
   *
   * ~~One `board` member serves both.~~ → **superseded 2026-09-01 (plan 013, T6).** It served both
   * while the two endpoints declared the same six parameters, and M3 ended that: the backlog takes a
   * release filter and `GET /boards/{id}/cards` **deliberately does not**.
   *
   * A third member rather than one widened object, and it is D-v's own rule rather than a new one:
   * each view is handed **the subset its own endpoint declares**. `BacklogFilters`' own header
   * carries what the shortcut cost — `contractParameters.test.ts` reads the *type* a hook spreads
   * into `qs`, so widening `BoardFilters` made the board send two parameters its endpoint would have
   * ignored in silence.
   */
  backlog: BacklogFilters;
}

/**
 * The one filter, plus the four writes every view makes against it.
 *
 * `appendOnce` and `dropOne` exist because a repeated query parameter has no `set` — appending a
 * value already present would send it twice, and removing one means rewriting the whole family.
 */
export interface WorkFilter {
  filters: WorkFilters;
  setParam: (mutate: (next: URLSearchParams) => void) => void;
  appendOnce: (name: string, value: string) => void;
  dropOne: (name: string, value: string) => void;
  clearFilters: () => void;
}

/**
 * The parameters a filter clear keeps. Not filters — the address of the view you are looking at.
 *
 * `board` is one of them since 012 T7 put a board picker on the screen, and it is the one most
 * easily missed: it looks like a filter because it narrows what is on screen, and it is not one,
 * because it names *which board* rather than *which work*.
 *
 * `month` joined it in 013 T5 and is the same trap one door over: the Calendar's month picker looks
 * like a narrowing because fewer issues are on screen after you press it, and it is not one — it is
 * *which month you are looking at*. A clear that reset it would throw the reader back to today
 * while removing a label chip.
 */
const CARRIED = ['view', 'board', 'sort', 'cols', 'month'] as const;

export function useWorkFilter(): WorkFilter {
  const [params, setParams] = useSearchParams();

  const filters = useMemo<WorkFilters>(() => {
    const level = asLevel(params.get('level'));
    const epicKey = params.get('epicKey') || undefined;
    const parentKey = params.get('parentKey') || undefined;
    const unparented = params.get('unparented') === 'true' ? true : undefined;
    const assigneeId = someOf(params.getAll('assigneeId'));
    const statusId = someOf(params.getAll('statusId'));
    const labelId = someOf(params.getAll('labelId'));
    const priority = someOf(params.getAll('priority').filter(isPriority));
    /*
      `unscheduled` loses to `releaseId` when both are somehow in the URL, rather than both being
      sent: the server answers 400 for the pair, and a hand-edited link is not worth a screen-wide
      failed state. The pickers make the pair unreachable — each one clears the other.
    */
    const releaseId = params.get('release') || undefined;
    const unscheduled =
      releaseId === undefined && params.get('unscheduled') === 'true' ? true : undefined;

    const board: BoardFilters = { level, epicKey, labelId, assigneeId, priority, unparented };

    return {
      issue: { level, epicKey, parentKey, statusId, assigneeId, unparented },
      board,
      backlog: { ...board, releaseId, unscheduled },
    };
  }, [params]);

  const setParam = useCallback(
    (mutate: (next: URLSearchParams) => void) => {
      const next = new URLSearchParams(params);
      mutate(next);
      setParams(next, { replace: true });
    },
    [params, setParams],
  );

  const appendOnce = useCallback(
    (name: string, value: string) =>
      setParam((next) => {
        if (value && !next.getAll(name).includes(value)) next.append(name, value);
      }),
    [setParam],
  );

  const dropOne = useCallback(
    (name: string, value: string) =>
      setParam((next) => {
        const kept = next.getAll(name).filter((held) => held !== value);
        next.delete(name);
        for (const held of kept) next.append(name, held);
      }),
    [setParam],
  );

  const clearFilters = useCallback(() => {
    const next = new URLSearchParams();
    for (const name of CARRIED) {
      const value = params.get(name);
      if (value !== null) next.set(name, value);
    }
    setParams(next, { replace: true });
  }, [params, setParams]);

  return { filters, setParam, appendOnce, dropOne, clearFilters };
}

/**
 * The applied chips for a `BoardFilters` view — the board's and the backlog's.
 *
 * **A chip for every filter this endpoint applies, whether or not this strip can apply it.** `level`,
 * `epicKey` and `unparented` have no picker on either screen — B3 asks for label, assignee and
 * priority, and §12.2's rule is that the strip's height is bounded by the reader's own choices — but
 * they still narrow what the server returns, so a reader who set one on the Table and switched here
 * must be able to see it and take it off. A filter with no chip is one somebody stares at an empty
 * screen over.
 *
 * It lives here rather than in either screen for the reason the hook above does: two copies of one
 * list drift, and the first thing to drift is which filters get a chip at all.
 *
 * <h2>The release chip appears on the backlog and not on the board, and that is the same rule</h2>
 *
 * Both views call this hook, and what separates them is what they *pass*: `filters.board` carries no
 * `releaseId`, so the chip does not render there — see {@link WorkFilters}`.backlog`. The board is
 * genuinely not narrowed by a release (it groups by the `RELEASE` swimlane axis instead), so a chip
 * there would claim a narrowing that is not happening.
 *
 * `useReleases` is read here rather than taken as a parameter, for the reason `useLabels` and
 * `useProjectMembers` are: the chip's label is a **version**, not a uuid, and only a lookup turns
 * one into the other. It is why this is a hook and not a function.
 */
export function useBoardFilterChips(
  projectId: string,
  filters: BacklogFilters,
  { setParam, dropOne }: Pick<WorkFilter, 'setParam' | 'dropOne'>,
): AppliedFilter[] {
  const labels = useLabels(projectId);
  const members = useProjectMembers(projectId, true);
  /*
    Gated on the filter actually being set, so the board and an unfiltered backlog do not pay a
    release list read to render no chip. `useReleases` is `enabled` on a non-empty project id, so an
    empty string is the off switch the hook already understands.
  */
  const releases = useReleases(filters.releaseId ? projectId : '');

  return [
    ...(filters.releaseId
      ? [
          {
            key: `release:${filters.releaseId}`,
            label: `Release · ${
              releases.data?.find((release) => release.id === filters.releaseId)?.version
              ?? filters.releaseId
            }`,
            onRemove: () => setParam((next) => next.delete('release')),
          },
        ]
      : []),
    ...(filters.unscheduled
      ? [
          {
            key: 'unscheduled',
            label: 'No release',
            onRemove: () => setParam((next) => next.delete('unscheduled')),
          },
        ]
      : []),
    ...(filters.labelId ?? []).map((id) => ({
      key: `label:${id}`,
      label: `Label · ${labels.data?.find((label) => label.id === id)?.name ?? id}`,
      onRemove: () => dropOne('labelId', id),
    })),
    ...(filters.assigneeId ?? []).map((id) => ({
      key: `assignee:${id}`,
      label: `Assignee · ${members.data?.find((member) => member.id === id)?.displayName ?? id}`,
      onRemove: () => dropOne('assigneeId', id),
    })),
    ...(filters.priority ?? []).map((value) => ({
      key: `priority:${value}`,
      label: `Priority · ${priorityLabels[value]}`,
      onRemove: () => dropOne('priority', value),
    })),
    ...(filters.level
      ? [
          {
            key: `level:${filters.level}`,
            label: `Level · ${issueLevels[filters.level]}`,
            onRemove: () => setParam((next) => next.delete('level')),
          },
        ]
      : []),
    ...(filters.epicKey
      ? [
          {
            key: `epic:${filters.epicKey}`,
            label: `Epic · ${filters.epicKey}`,
            onRemove: () => setParam((next) => next.delete('epicKey')),
          },
        ]
      : []),
    ...(filters.unparented
      ? [
          {
            key: 'unparented',
            label: 'No epic',
            onRemove: () => setParam((next) => next.delete('unparented')),
          },
        ]
      : []),
  ];
}

/**
 * The applied chips for an `IssueFilters` view — the table's and, since 013 T5, the calendar's.
 *
 * Extracted for {@link useBoardFilterChips}' reason, and it is the same reason the second time: two
 * copies of one chip list drift, and the first thing to drift is *which* filters get a chip at all.
 * The table had built this inline while it was the only reader; the calendar reads the same six
 * parameters off the same URL, so a second copy would have regrown the drift the hook removes.
 *
 * **A chip for every filter this endpoint applies, whether or not the strip can apply it.**
 * `epicKey` and `parentKey` have a chip and no picker on purpose: `GET /issues` takes both, so a
 * reader who narrowed to one epic on the Board and switched here is looking at a narrowed list —
 * and what they need is to *see* it and take it off, not a second place to apply it.
 */
export function useIssueFilterChips(
  projectId: string,
  filters: IssueFilters,
  { setParam, dropOne }: Pick<WorkFilter, 'setParam' | 'dropOne'>,
): AppliedFilter[] {
  const workflow = useProjectWorkflow(projectId);
  const members = useProjectMembers(projectId, true);

  return [
    ...(filters.level
      ? [
          {
            key: `level:${filters.level}`,
            label: `Level · ${issueLevels[filters.level]}`,
            onRemove: () => setParam((next) => next.delete('level')),
          },
        ]
      : []),
    ...(filters.statusId ?? []).map((id) => ({
      key: `status:${id}`,
      label: `Status · ${workflow.data?.statuses.find((status) => status.id === id)?.name ?? id}`,
      onRemove: () => dropOne('statusId', id),
    })),
    ...(filters.assigneeId ?? []).map((id) => ({
      key: `assignee:${id}`,
      label: `Assignee · ${members.data?.find((member) => member.id === id)?.displayName ?? id}`,
      onRemove: () => dropOne('assigneeId', id),
    })),
    ...(filters.epicKey
      ? [
          {
            key: `epic:${filters.epicKey}`,
            label: `Epic · ${filters.epicKey}`,
            onRemove: () => setParam((next) => next.delete('epicKey')),
          },
        ]
      : []),
    ...(filters.parentKey
      ? [
          {
            key: `parent:${filters.parentKey}`,
            label: `Parent · ${filters.parentKey}`,
            onRemove: () => setParam((next) => next.delete('parentKey')),
          },
        ]
      : []),
    ...(filters.unparented
      ? [
          {
            key: 'unparented',
            label: 'No epic',
            onRemove: () => setParam((next) => next.delete('unparented')),
          },
        ]
      : []),
  ];
}

/** An empty array is an absent filter, never one that matches nothing — `qs` drops `undefined`. */
function someOf<T>(values: T[]): T[] | undefined {
  return values.length > 0 ? values : undefined;
}

function asLevel(value: string | null): IssueLevel | undefined {
  return value === 'EPIC' || value === 'STORY' || value === 'TASK' ? value : undefined;
}

function isPriority(value: string): value is Priority {
  return value === 'CRITICAL' || value === 'HIGH' || value === 'MEDIUM' || value === 'LOW';
}
