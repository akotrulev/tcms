/**
 * The reusable library — conditions and shared steps.
 *
 * Both are search-as-you-type, so both carry the typeahead budget rather than the interactive
 * one. That is the whole reason they sit together: the endpoint class, not the entity.
 */
import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type { Condition, SharedStep } from '../types';
import { base, keys } from './keys';

export function useConditions(
  projectId: string,
  q: string,
  type?: 'PRE' | 'POST',
): UseQueryResult<Condition[]> {
  return useQuery({
    queryKey: keys.conditions(projectId, q, type),
    queryFn: ({ signal }) =>
      api.get<Condition[]>(`${base(projectId)}/conditions${qs({ q, type })}`, {
        signal,
        // Search-as-you-type: a 10s budget here would be a broken experience.
        timeoutMs: TIMEOUTS.typeahead,
      }),
  });
}

export function useSharedSteps(projectId: string, q: string): UseQueryResult<SharedStep[]> {
  return useQuery({
    queryKey: keys.sharedSteps(projectId, q),
    queryFn: ({ signal }) =>
      api.get<SharedStep[]>(`${base(projectId)}/shared-steps${qs({ q })}`, {
        signal,
        timeoutMs: TIMEOUTS.typeahead,
      }),
  });
}
