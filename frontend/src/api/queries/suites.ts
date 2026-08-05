/** The suite tree and its three writes. */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { api } from '../client';
import type { LifecycleStatus, Suite, SuiteHistoryResponse } from '../types';
import { base, keys } from './keys';

export function useSuites(projectId: string): UseQueryResult<Suite[]> {
  return useQuery({
    queryKey: keys.suites(projectId),
    queryFn: ({ signal }) => api.get<Suite[]>(`${base(projectId)}/suites`, { signal }),
  });
}

/**
 * The three suite writes share one input shape, because the contract declares one `SuiteInput`
 * for both verbs. `parentId` means different things on each: on a create it chooses the parent,
 * and on a rename the server accepts it only when it repeats the stored one — moving is refused
 * with a field error rather than silently dropped, so the rename dialog does not send it at all.
 */
export type SuiteInput = {
  name: string;
  parentId?: string | null;
  description?: string | null;
  /**
   * The tags this suite claims (§96). **Absent means "leave them alone" and `[]` means "clear
   * them"** — the same split every collection on this API uses, and the reason the rename dialog
   * always sends the field: a save that omitted it could not clear the last rule.
   */
  tagRules?: string[];
  /**
   * The cases added to this suite **individually** — the membership arm no screen authors yet.
   * The endpoint takes it and validates it against the project's live cases; the suite dialog
   * offers tag rules only, so this is named here rather than left to be discovered as missing.
   */
  caseIds?: string[];
};

export function useCreateSuite(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: SuiteInput) => api.post<Suite>(`${base(projectId)}/suites`, input),
    // The dialog renders field errors on its own inputs and the 403 in its own alert, so the
    // global notification would be a second, vaguer copy of a message already on screen.
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.suites(projectId) }),
  });
}

export function useRenameSuite(projectId: string, suiteId: string) {
  const client = useQueryClient();
  return useMutation({
    // `lockVersion` travels as `If-Match`, never in the body — the header is where the contract
    // puts it, and a body field would be a value on the input schema that must never persist.
    // The SPA sent it in the body against the mock for as long as no server read it, which is
    // exactly the divergence this call site exists not to repeat.
    mutationFn: ({ lockVersion, ...input }: SuiteInput & { lockVersion: number }) =>
      api.patch<Suite>(`${base(projectId)}/suites/${suiteId}`, input, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.suites(projectId) }),
  });
}

export function useDeleteSuite(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (suiteId: string) => api.del<void>(`${base(projectId)}/suites/${suiteId}`),
    // 409 here is "this folder still holds things", whose detail names the counts — a message
    // the dialog shows in place, beside the folder it is about. Handed to the global surface it
    // would arrive as a toast detached from the thing it describes.
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
      // The case list is scoped by suite, so a deleted folder must not stay selectable in a
      // filter chip that now matches nothing.
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
    },
  });
}

/**
 * Moves a suite along §6g's ladder.
 *
 * `PUT`, not `PATCH`, and no `If-Match`: the body names an absolute target state rather than a
 * delta against something this client read, so there is nothing to lose to a concurrent save —
 * `useSetPlanStatus` and `useSetRunStatus` make the same call. The rename beside it keeps its
 * header, because a name typed over a stale copy really does lose the other author's words.
 *
 * `handled`, because both refusals are sentences the caller has to read next to the folder they are
 * about: a 403 (this edge is a lead's) and a 409 (the ladder does not have this move).
 */
export function useSetSuiteStatus(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    // The suite is a MUTATION VARIABLE, not a hook argument, because the one caller is the tree's
    // kebab menu — which acts on whichever folder it was opened on. `useRenameSuite` takes its id
    // up front for the opposite reason: its caller is a dialog mounted per suite.
    mutationFn: ({ suiteId, status }: { suiteId: string; status: LifecycleStatus }) =>
      api.put<Suite>(`${base(projectId)}/suites/${suiteId}/status`, { status }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
      // A case's own screen renders the folder it sits in, and the case list's suite filter reads
      // the tree — both show a status that has just moved.
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
    },
  });
}

/**
 * One suite's execution history.
 *
 * The type argument is the **generated** `SuiteHistoryResponse`, and that is the whole lesson of
 * §52. This hook used to declare its own `{ runs: SuiteRunSummary[]; … }` literal here — while the
 * contract called the array `entries`. A hand-written type argument is generated from nothing, so
 * the compiler had no second opinion to compare it against, and the page would have read
 * `undefined` off the real server's body. `apiResponseTypes.test.ts` now refuses an inline literal
 * at any `api.*` call site.
 */
export function useSuiteHistory(
  projectId: string,
  suiteId: string | undefined,
): UseQueryResult<SuiteHistoryResponse> {
  return useQuery({
    queryKey: keys.suiteHistory(projectId, suiteId ?? ''),
    queryFn: ({ signal }) =>
      api.get<SuiteHistoryResponse>(`${base(projectId)}/suites/${suiteId}/history`, { signal }),
    enabled: !!suiteId,
  });
}
