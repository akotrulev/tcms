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
import type { Condition, SharedStep, SharedStepInput, StepSuggestion } from '../types';
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
 * What the case editor's action field completes from (§94) — the project's own step corpus.
 *
 * ## Why it sits beside the library and is not part of it
 *
 * Same endpoint class — search-as-you-type, hence the same budget — and the opposite mechanism.
 * `useSharedSteps` reads a **curated** table and a pick creates a **reference**; this reads a
 * corpus **derived** from every step the project's live cases already contain, and a pick copies
 * the text. The corpus needs nobody to populate it, which is the correction to the library whose
 * picker opens empty on every project where nobody remembered to fill it in.
 *
 * ## `enabled` on a non-blank fragment
 *
 * The endpoint answers the whole corpus for a blank `q`, and the editor deliberately does not ask
 * for it. A step row is not a browsing surface: mounting an eight-step case would fire eight reads
 * of a thousand-row list nobody has asked a question of yet, and the answer would open a popup
 * over a field the author has not touched. Suggestions appear once there is something to narrow.
 */
export function useStepSuggestions(projectId: string, q: string): UseQueryResult<StepSuggestion[]> {
  return useQuery({
    queryKey: keys.stepSuggestions(projectId, q),
    queryFn: ({ signal }) =>
      api.get<StepSuggestion[]>(`${base(projectId)}/step-suggestions${qs({ q })}`, {
        signal,
        timeoutMs: TIMEOUTS.typeahead,
      }),
    enabled: q.trim().length > 0,
    /*
      The corpus changes only when somebody saves a case, so re-asking for the same fragment
      within a minute answers what is already on screen. Without it, every remount of a row — and
      the list remounts on an append, a move and a delete — re-fires the read the author is
      currently reading the answer to.
    */
    staleTime: 60_000,
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
