import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useLabels, useProjectMembers } from '../../api/queries';
import type { BoardFilters, IssueFilters, IssueLevel, Priority } from '../../api/types';
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
  /** What `GET /issues` declares — the table's. */
  issue: IssueFilters;
  /** What `GET /boards/{id}/cards` and `GET /projects/{id}/backlog` declare — the board's and the backlog's. */
  board: BoardFilters;
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
 */
const CARRIED = ['view', 'board', 'sort', 'cols'] as const;

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

    return {
      issue: { level, epicKey, parentKey, statusId, assigneeId, unparented },
      board: { level, epicKey, labelId, assigneeId, priority, unparented },
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
 * The applied chips for a `BoardFilters` view — the board's and the backlog's, which are the same
 * six parameters and therefore the same six chips.
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
 */
export function useBoardFilterChips(
  projectId: string,
  filters: BoardFilters,
  { setParam, dropOne }: Pick<WorkFilter, 'setParam' | 'dropOne'>,
): AppliedFilter[] {
  const labels = useLabels(projectId);
  const members = useProjectMembers(projectId, true);

  return [
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
