import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import type {
  AutomationStatus,
  CaseFilters,
  HealthSignal,
  LifecycleStatus,
  Priority,
} from '../../api/types';

/**
 * Filter state lives in the URL, not in a store.
 *
 * That is a product decision, not a technical preference: a filtered view of a test
 * repository is the thing people paste into a ticket, bookmark for their release, and send
 * to a colleague ("here are the 78 cases whose spec changed"). State held in a store is
 * unshareable, and it also breaks the back button — a user who drills from a dashboard tile
 * into a filtered list expects Back to return to the dashboard.
 *
 * It also makes the dashboard's tiles trivially composable: a tile is a link carrying a
 * filter predicate, and two predicates combine by merging query parameters.
 */

export const DEFAULT_SORT = 'keySeq,asc';
export const DEFAULT_SIZE = 25;

/** Parameters that are filters, as opposed to paging or sorting. */
const FILTER_PARAMS = [
  'q',
  'suiteId',
  'featureId',
  'testTypeId',
  'testLevelId',
  'ownerId',
  'lifecycleStatus',
  'automationStatus',
  'priority',
  'healthSignal',
  'tag',
  'unfiled',
  'releaseVersion',
] as const;

export interface CaseFilterState {
  filters: CaseFilters;
  sort: string;
  /** True when any filter narrows the result — drives no-records vs no-filter-match. */
  hasActiveFilters: boolean;
  /** How many filters narrow it, each value of a multi-filter counting once. See below. */
  activeFilterCount: number;
  setFilter: (key: keyof CaseFilters, value: string | string[] | boolean | undefined) => void;
  setPage: (page: number) => void;
  setSize: (size: number) => void;
  setSort: (sort: string) => void;
  clearFilters: () => void;
}

export function useCaseFilters(): CaseFilterState {
  const [params, setParams] = useSearchParams();

  const filters = useMemo<CaseFilters>(() => {
    const multi = (key: string) => {
      const values = params.getAll(key).filter(Boolean);
      return values.length ? values : undefined;
    };
    return {
      q: params.get('q') ?? undefined,
      suiteId: params.get('suiteId') ?? undefined,
      featureId: params.get('featureId') ?? undefined,
      testTypeId: params.get('testTypeId') ?? undefined,
      testLevelId: params.get('testLevelId') ?? undefined,
      ownerId: params.get('ownerId') ?? undefined,
      lifecycleStatus: multi('lifecycleStatus') as LifecycleStatus[] | undefined,
      automationStatus: multi('automationStatus') as AutomationStatus[] | undefined,
      priority: multi('priority') as Priority[] | undefined,
      healthSignal: multi('healthSignal') as HealthSignal[] | undefined,
      tag: multi('tag'),
      // Present-and-'true' only. `?unfiled=false` would be an active filter by FILTER_PARAMS'
      // reckoning while narrowing nothing, so the "no rows match your filters" empty state would
      // claim a filter that does not exist.
      unfiled: params.get('unfiled') === 'true' ? true : undefined,
      releaseVersion: params.get('releaseVersion') ?? undefined,
      page: Number(params.get('page') ?? 0),
      size: Number(params.get('size') ?? DEFAULT_SIZE),
    };
  }, [params]);

  const sort = params.get('sort') ?? DEFAULT_SORT;

  const hasActiveFilters = FILTER_PARAMS.some((key) => params.getAll(key).some(Boolean));

  /**
   * How many filters are narrowing the list, counting each value of a multi-filter separately.
   *
   * §7 asks the empty state to count what its filters hid — *"1 284 cases exist. Three filters are
   * hiding all of them"* — and the renders audit found every empty state offering the removal
   * without the count, so a reader could not tell an empty project from an over-filtered one. It
   * lives here rather than in each screen because `CaseListPage`, `CaseSearchPage` and the strip
   * itself all need the same number, and three spellings of it would disagree.
   */
  const activeFilterCount = FILTER_PARAMS.reduce(
    (total, key) => total + params.getAll(key).filter(Boolean).length,
    0,
  );

  const setFilter = useCallback(
    (key: keyof CaseFilters, value: string | string[] | boolean | undefined) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          next.delete(key);
          if (Array.isArray(value)) {
            for (const v of value) if (v) next.append(key, v);
          } else if (value === true) {
            // A boolean filter is present or absent, never `=false` — see the parse above.
            next.set(key, 'true');
          } else if (value) {
            next.set(key, value);
          }
          // Changing a filter always returns to page 1 — staying on page 4 of a result set
          // that now has two pages shows an empty grid the user can't explain.
          next.delete('page');
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const setPage = useCallback(
    (page: number) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          if (page <= 0) next.delete('page');
          else next.set('page', String(page));
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const setSize = useCallback(
    (size: number) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          next.set('size', String(size));
          next.delete('page');
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const setSort = useCallback(
    (value: string) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          if (value === DEFAULT_SORT) next.delete('sort');
          else next.set('sort', value);
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const clearFilters = useCallback(() => {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        for (const key of FILTER_PARAMS) next.delete(key);
        next.delete('page');
        return next;
      },
      { replace: true },
    );
  }, [setParams]);

  return {
    filters,
    sort,
    hasActiveFilters,
    activeFilterCount,
    setFilter,
    setPage,
    setSize,
    setSort,
    clearFilters,
  };
}
