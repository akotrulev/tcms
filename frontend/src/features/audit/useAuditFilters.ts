/**
 * The audit trail's filter state, in the URL.
 *
 * `useCaseFilters`' arrangement and its reasoning: a filtered view is the thing people paste into
 * a ticket. It matters more here than anywhere else in the product — "everything Ana did to this
 * project between the 4th and the 9th" is an *answer* somebody is sending to somebody else, and a
 * filter set that lived in component state would make that answer unsendable.
 */
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { AuditFilters } from '../../api/types';

export const DEFAULT_SIZE = 50;

/** Parameters that NARROW the trail, as opposed to paging it. Drives no-records vs no-match. */
const FILTER_PARAMS = ['projectId', 'actorUserId', 'action', 'occurredAfter', 'occurredBefore'] as const;

export type AuditFilterKey = (typeof FILTER_PARAMS)[number];

export interface AuditFilterState {
  filters: AuditFilters;
  hasActiveFilters: boolean;
  setFilter: (key: AuditFilterKey, value: string | undefined) => void;
  setPage: (page: number) => void;
  setSize: (size: number) => void;
  clearFilters: () => void;
}

export function useAuditFilters(): AuditFilterState {
  const [params, setParams] = useSearchParams();

  const filters = useMemo<AuditFilters>(
    () => ({
      projectId: params.get('projectId') ?? undefined,
      actorUserId: params.get('actorUserId') ?? undefined,
      action: params.get('action') ?? undefined,
      occurredAfter: params.get('occurredAfter') ?? undefined,
      occurredBefore: params.get('occurredBefore') ?? undefined,
      page: Number(params.get('page') ?? 0),
      size: Number(params.get('size') ?? DEFAULT_SIZE),
    }),
    [params],
  );

  const hasActiveFilters = FILTER_PARAMS.some((key) => !!params.get(key));

  const setFilter = useCallback(
    (key: AuditFilterKey, value: string | undefined) => {
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous);
          if (value) next.set(key, value);
          else next.delete(key);
          // Changing a filter always returns to page 1 — staying on page 4 of a result set that
          // now has two pages shows an empty grid the reader cannot explain.
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
          // Defaults are deleted rather than written, so a shared URL carries only what was
          // actually chosen and a later change of default does not silently pin the old one.
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
          if (size === DEFAULT_SIZE) next.delete('size');
          else next.set('size', String(size));
          next.delete('page');
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

  return { filters, hasActiveFilters, setFilter, setPage, setSize, clearFilters };
}
