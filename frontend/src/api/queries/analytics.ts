/**
 * Delivery analytics: the release burn-up, cycle time per issue type, and the cumulative flow.
 *
 * <h2>Three reads, one permission, and no writes</h2>
 *
 * All three cost `report:view`, which every project-scoped role that renders a rail at all holds —
 * so there is no gate to model here and no mutation to invalidate. Nothing on this screen changes
 * anything; what the figures report changes when the *work* changes, which is the tracker's.
 *
 * <h2>`TIMEOUTS.long`, not `interactive`</h2>
 *
 * These are aggregations over a projection — a fold across every counted issue in a release, or
 * every issue in a project — not a typeahead. The interactive budget would abort a legitimate
 * answer on a large project and report it to the reader as a failure.
 *
 * <h2>The fourth panel is not here, deliberately</h2>
 *
 * `reports.html`'s estimate-accuracy table is composed from `time`'s own
 * `GET /time/rollup/epics/{key}` (020 D-i). `reporting` computes nothing for it: an analytics
 * endpoint would mean reading `issue_time_rollup`, which 016 D-c placed off-limits to every module
 * but `time`, or re-deriving what *approved* means — a second definition of the word, in a second
 * table.
 */
import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { TIMEOUTS, api, qs } from '../client';
import type { BurnUpReport, CumulativeFlowReport, CycleTimeReport } from '../types';
import { base, keys } from './keys';

/** The windows the contract offers. Exported so the pickers cannot drift from the schema. */
export const CYCLE_TIME_WINDOWS = [30, 90, 180] as const;
export const FLOW_WINDOWS = [8, 12, 26] as const;

export const DEFAULT_CYCLE_TIME_DAYS = 90;
export const DEFAULT_FLOW_WEEKS = 12;

/**
 * The burn-up for one release.
 *
 * `enabled` gates on a chosen release rather than defaulting to the newest one: "the latest
 * release" is a different question with its own answer on the roadmap, and a chart that silently
 * changed which release it was about is what a picker exists to prevent. Until one is picked the
 * panel renders its own empty state, which is a truthful screen rather than a spinner.
 */
export function useBurnUp(
  projectId: string,
  releaseId: string | null,
): UseQueryResult<BurnUpReport> {
  return useQuery({
    queryKey: keys.analyticsBurnUp(projectId, releaseId ?? ''),
    enabled: releaseId !== null,
    queryFn: ({ signal }) =>
      api.get<BurnUpReport>(
        `${base(projectId)}/analytics/burn-up${qs({ releaseId: releaseId ?? '' })}`,
        { signal, timeoutMs: TIMEOUTS.long },
      ),
  });
}

/** Median and p90 days from creation to done, per issue type. */
export function useCycleTime(projectId: string, days: number): UseQueryResult<CycleTimeReport> {
  return useQuery({
    queryKey: keys.analyticsCycleTime(projectId, days),
    queryFn: ({ signal }) =>
      api.get<CycleTimeReport>(`${base(projectId)}/analytics/cycle-time${qs({ days })}`, {
        signal,
        timeoutMs: TIMEOUTS.long,
      }),
  });
}

/** Issues per status band, weekly — four bands, one of them the absence of a record. */
export function useFlow(projectId: string, weeks: number): UseQueryResult<CumulativeFlowReport> {
  return useQuery({
    queryKey: keys.analyticsFlow(projectId, weeks),
    queryFn: ({ signal }) =>
      api.get<CumulativeFlowReport>(`${base(projectId)}/analytics/flow${qs({ weeks })}`, {
        signal,
        timeoutMs: TIMEOUTS.long,
      }),
  });
}
