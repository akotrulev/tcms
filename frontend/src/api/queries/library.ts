/**
 * The reusable library — conditions, and the step corpus that sits beside them.
 *
 * Both are search-as-you-type, so both carry the typeahead budget rather than the interactive
 * one. That is the whole reason they sit together: the endpoint class, not the entity.
 *
 * `useSharedSteps` and `useCreateSharedStep` were the third member until §239. The shared-step
 * library was retired because it was half-built: the picker wrote the entry's TITLE into the
 * step's `action` and set a reference that no read path — case detail, case version snapshot,
 * report, export — ever resolved, so the "editing the entry changes every case using it" the UI
 * promised was never true. `useStepSuggestions` below is what step reuse is now.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type { Condition, ConditionInput, StepSuggestion } from '../types';
import { base, keys } from './keys';

/**
 * The project's condition library.
 *
 * `enabled` is a parameter rather than always-on because this hook has two jobs with different
 * appetites. As a **picker** it is search-as-you-type and should fire whenever the box is used; as
 * a **resolver** — turning a case's attached ids into titles — it is pointless when the case has no
 * attachments, which is every new case. Firing it there is §94's rule about the step corpus one
 * endpoint over: a read of a list nobody has asked a question of.
 */
export function useConditions(
  projectId: string,
  q: string,
  type?: 'PRE' | 'POST',
  enabled = true,
): UseQueryResult<Condition[]> {
  return useQuery({
    queryKey: keys.conditions(projectId, q, type),
    queryFn: ({ signal }) =>
      api.get<Condition[]>(`${base(projectId)}/conditions${qs({ q, type })}`, {
        signal,
        // Search-as-you-type: a 10s budget here would be a broken experience.
        timeoutMs: TIMEOUTS.typeahead,
      }),
    enabled,
    // The library changes only when somebody edits it, so re-asking within a minute answers what
    // is already on screen — and the two panels ask for the same unfiltered list.
    staleTime: 60_000,
  });
}

/**
 * What the case editor's action field completes from (§94) — the project's own step corpus.
 *
 * ## Why it sits beside the library and is not part of it
 *
 * Same endpoint class — search-as-you-type, hence the same budget — and the opposite mechanism.
 * The retired shared-step library read a **curated** table and a pick created a **reference**;
 * this reads a corpus **derived** from every step the project's live cases already contain, and a
 * pick copies the text. The corpus needs nobody to populate it, which is the correction to the
 * library whose picker opened empty on every project where nobody remembered to fill it in — and
 * §239 finished the argument by retiring the library outright.
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
 * Adds an entry to the condition library. `library:manage`.
 *
 * Prefix-invalidated rather than keyed on the list the picker is currently mounted on, because
 * the key carries the search term: an entry created while the box reads "refund" must also appear
 * once the box is cleared, and those are two different cached lists.
 */
export function useCreateCondition(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ConditionInput) =>
      api.post<Condition>(`${base(projectId)}/conditions`, input),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: ['projects', projectId, 'conditions'] }),
  });
}

/**
 * Edits a library condition — **and every case and suite attaching it**. `library:manage`.
 *
 * ## This is the one mutation in the app whose blast radius is not the thing you are editing
 *
 * An attachment is a reference, so the edit lands everywhere the entry is used, with no prompt and
 * no fork. The caller is therefore expected to have stated the impact *before* calling — see
 * `ConditionEditDialog`, which reads `usageCount` and `suiteUsageCount` off the entry and puts them
 * in front of the author. Nothing here can do that: by the time this runs the decision is made.
 *
 * ## `If-Match`, and why the 409 is handed back rather than notified
 *
 * The entry carries a `lockVersion` and it is sent as the precondition, so an author who read the
 * impact figures cannot apply their edit to a row somebody else has since changed underneath them.
 * The dialog renders the 409 in place — `handled` — because the recovery is "reload the condition
 * and reapply", which is an action on the dialog and not a toast.
 *
 * Invalidates the CASE cache as well as the library. That is the whole point of the operation: the
 * editor is showing condition titles resolved through this entry, and leaving them stale would
 * show the author the old text on the very screen the edit was made from.
 */
export function useUpdateCondition(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      conditionId,
      lockVersion,
      input,
    }: {
      conditionId: string;
      lockVersion: number;
      input: ConditionInput;
    }) =>
      api.put<Condition>(`${base(projectId)}/conditions/${conditionId}`, input, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    meta: { handled: true },
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['projects', projectId, 'conditions'] });
      client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
    },
  });
}
