/**
 * CI result ingestion — what has been posted, and what a person does about the tests it could not
 * place.
 *
 * **Two endpoints and two very different gates**, which is the whole reason this is its own module
 * rather than three hooks in `runs.ts`:
 *
 *   - The **history** (`/ingestions`) is gated on project membership alone, exactly as `useRuns`
 *     is. Anybody who may read this project's runs may read how their verdicts got there.
 *   - The **mappings** (`/automation-mappings`) cost `case:read` to list and **`case:edit` to
 *     write** — because confirming that a test name means a case is the same statement as writing
 *     that case's `automationKey`: it decides where that test's results land. Automation Service
 *     deliberately holds neither, so a leaked CI token can post results and cannot re-point them.
 *
 * A screen that treated the two as one surface would gate the read on the write's permission and
 * hide the history from the Test Engineer who most needs to see that their tests bound to nothing.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api } from '../client';
import type {
  AutomationMapping,
  AutomationMappingInput,
  IngestionJob,
  IngestionJobSummary,
} from '../types';
import { base, keys } from './keys';

/** This project's ingestion history, newest first. */
export function useIngestions(projectId: string): UseQueryResult<IngestionJobSummary[]> {
  return useQuery({
    queryKey: keys.ingestions(projectId),
    queryFn: ({ signal }) =>
      api.get<IngestionJobSummary[]>(`${base(projectId)}/ingestions`, { signal }),
  });
}

/**
 * One job, with the tests it could not place.
 *
 * **A separate hook and a separate key rather than a `find` over the list**, because the two
 * endpoints serve different shapes on purpose: the list has no `unmatched` array at all, so a
 * client that read the row it already had would render an empty binder on a job with twelve
 * unmatched tests and no way to tell that anything was missing.
 */
export function useIngestion(
  projectId: string,
  jobId: string | undefined,
): UseQueryResult<IngestionJob> {
  return useQuery({
    queryKey: keys.ingestion(projectId, jobId ?? ''),
    queryFn: ({ signal }) =>
      api.get<IngestionJob>(`${base(projectId)}/ingestions/${jobId}`, { signal }),
    enabled: !!jobId,
  });
}

/** The learned test-name bindings — §6c's third ladder step. `case:read`. */
export function useAutomationMappings(projectId: string): UseQueryResult<AutomationMapping[]> {
  return useQuery({
    queryKey: keys.automationMappings(projectId),
    queryFn: ({ signal }) =>
      api.get<AutomationMapping[]>(`${base(projectId)}/automation-mappings`, { signal }),
  });
}

/**
 * Confirms that a test name means a case — creating the binding, or **re-pointing** an existing
 * one. 200 either way; there is no create-vs-update decision for a caller to make.
 *
 * **`testName` is sent exactly as it arrived and this hook must never normalize it.** The rule
 * (trim, collapse whitespace, lower-case, `Locale.ROOT`) lives on the server beside the parser that
 * applies it to every incoming report, and a second implementation here is how every mapping in a
 * company silently stops matching after one of the two changes. Pasting a served `normalizedName`
 * back is fine — it is already canonical, and normalizing it again is a no-op *because* the server
 * does it.
 *
 * Both the mapping list and the **job** are invalidated. The job's unmatched list is what the
 * binder is reading, and a name that has just been bound is no longer unmatched *for the next
 * report* — the job row itself is immutable history, so the refetch is what proves the write
 * landed rather than what changes the screen.
 */
export function useConfirmAutomationMapping(
  projectId: string,
): UseMutationResult<AutomationMapping, Error, AutomationMappingInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: AutomationMappingInput) =>
      api.post<AutomationMapping>(`${base(projectId)}/automation-mappings`, input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.automationMappings(projectId) });
    },
  });
}

/** Removes a binding. 204 whether it was there or not. */
export function useDeleteAutomationMapping(
  projectId: string,
): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (mappingId: string) =>
      // `api.del`, not `api.delete` — §70: the latter has never existed on this client, and eight
      // call sites went unchecked behind a guard that counts.
      api.del<void>(`${base(projectId)}/automation-mappings/${mappingId}`),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.automationMappings(projectId) });
    },
  });
}
