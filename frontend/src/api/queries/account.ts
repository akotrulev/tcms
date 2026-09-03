/** The caller's own connected AI assistants — list, connect, revoke (015 D26). */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api } from '../client';
import type { Assistant, AssistantInput, IssuedAssistant } from '../types';
import { keys } from './keys';

/**
 * The caller's own assistants.
 *
 * **No `enabled` gate**, unlike `useApiTokens` beside it in `admin.ts`, and the difference is the
 * whole of D26: `/api-tokens` administers the company's credentials and is *refused* without
 * `token:issue`, so firing it ungated turns a correct 403 into a broken screen. This endpoint costs
 * authentication only — an assistant grants nothing its owner lacks, so issuing one is not a
 * privileged act — and there is no permission for a caller to be missing.
 *
 * A **revoked** assistant is in this list and stays in it, `revokedAt` set. Its acts are in each
 * issue's history attributed to its owner through it, and a list that dropped the row would leave
 * a reader reading "via laptop-agent" beside a profile that says no such thing ever existed.
 */
export function useMyAssistants(): UseQueryResult<Assistant[]> {
  return useQuery({
    queryKey: keys.myAssistants,
    queryFn: ({ signal }) => api.get<Assistant[]>('/me/assistants', { signal }),
    staleTime: 30_000,
  });
}

/**
 * Connects one — **the mutation whose response is a credential.**
 *
 * `useIssueApiToken`'s three decisions, unchanged and for the same reasons, because this is the
 * second endpoint in the product that answers with a plaintext secret:
 *
 *   - **Nothing here writes the secret anywhere.** No `setQueryData`, no optimistic insert. The
 *     plaintext lives in this mutation's `data` and in the page's own state, and dies when the page
 *     unmounts — which is what makes the "shown once" warning true rather than decorative.
 *   - **`meta: { handled: true }`.** The form lands the `name` error on its own input; a toast
 *     would be a vaguer second copy of a sentence already on screen.
 *   - **Only the assistant list is invalidated.** A token confers nothing on anybody signed in, so
 *     no grant and no project list can have changed.
 */
export function useConnectAssistant() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: AssistantInput) =>
      api.post<IssuedAssistant>('/me/assistants', input, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.myAssistants });
    },
  });
}

/**
 * Revokes one. `204` whether it was live or already revoked, so a second press is not an error.
 *
 * Invalidates rather than removing, for `useRevokeApiToken`'s reason: the row is kept and marked,
 * so the list is expected to come back the same length with one entry changed.
 */
export function useRevokeAssistant() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (assistantId: string) =>
      api.del<void>(`/me/assistants/${encodeURIComponent(assistantId)}`, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.myAssistants });
    },
  });
}
