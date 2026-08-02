/**
 * The reusable library — conditions and shared steps.
 *
 * Both are search-as-you-type, so both carry the typeahead budget rather than the interactive
 * one. That is the whole reason they sit together: the endpoint class, not the entity.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type { Condition, SharedStep, SharedStepInput } from '../types';
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

/**
 * Adds an entry to the step library. `library:manage`.
 *
 * Invalidates by prefix rather than by the key the picker is currently mounted on, because the
 * key carries the search term: a new entry created while the box reads "refund" must also appear
 * once the box is cleared, and those are two different cached lists. `keys.sharedSteps` is a
 * function of `q`, so the prefix is the only thing that reaches all of them.
 *
 * `handled` — the dialog renders its own field errors and its own 403, so the global notification
 * would be a second, vaguer copy of a message already on screen.
 */
export function useCreateSharedStep(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: SharedStepInput) =>
      api.post<SharedStep>(`${base(projectId)}/shared-steps`, input),
    meta: { handled: true },
    onSuccess: () =>
      client.invalidateQueries({ queryKey: ['projects', projectId, 'shared-steps'] }),
  });
}
