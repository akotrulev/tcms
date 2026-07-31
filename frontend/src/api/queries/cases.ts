/** The case list, the case, its writes, its versions and its execution history. */
import {
  keepPreviousData,
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useRef } from 'react';
import { TIMEOUTS, api, newIntentKey, qs } from '../client';
import type {
  BulkResult,
  CaseFilters,
  CaseHistorySummary,
  EntityVersion,
  ExecutionHistoryEntry,
  Paged,
  TestCase,
  TestCaseSummary,
  VersionMarker,
} from '../types';
import { base, keys } from './keys';

export function useCases(
  projectId: string,
  filters: CaseFilters,
  sort: string,
): UseQueryResult<Paged<TestCaseSummary>> {
  return useQuery({
    // Every filter, the page, the size and the sort are in the key: a slow response for
    // "ref" can no longer overwrite the rows for "refund".
    queryKey: keys.cases(projectId, filters, sort),
    queryFn: ({ signal }) =>
      api.get<Paged<TestCaseSummary>>(`${base(projectId)}/cases${qs({ ...filters, sort })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    /**
     * Keep the previous key's rows on screen while the next ones load, so changing a filter or
     * a page does not flash a skeleton over data the user is reading. The filters live in the
     * URL, so **every keystroke in the search box is a new query key** — without this the grid
     * empties and refills as you type, which reads as the list breaking rather than narrowing.
     *
     * `keepPreviousData` rather than the equivalent inline `(previous) => previous`: TanStack
     * memoizes the placeholder by comparing the *identity* of this option against the previous
     * render's (`queryObserver.ts`), and an inline arrow is a new identity every render, so the
     * memoized branch is never taken and the placeholder is recomputed on each pass. Identical
     * result today, and the kind of thing that stops being identical the moment a `select` is
     * added — `select` re-runs on every recompute.
     *
     * What this does NOT do, deliberately: a *failed* first load of a new key still shows the
     * failed body, not the old rows. TanStack only substitutes placeholder data while the query
     * is `pending`, which is the correct line — rows for the filter you replaced, presented as
     * the answer to the filter you typed, would be a lie the staleness marker cannot fix.
     */
    placeholderData: keepPreviousData,
  });
}

export function useCase(projectId: string, caseId: string | undefined): UseQueryResult<TestCase> {
  return useQuery({
    queryKey: keys.case(projectId, caseId ?? ''),
    queryFn: ({ signal }) => api.get<TestCase>(`${base(projectId)}/cases/${caseId}`, { signal }),
    enabled: !!caseId,
  });
}

export function useCreateCase(projectId: string) {
  const client = useQueryClient();
  /*
    One key per mounted editor — that is, per user intent to create *this* case. It survives
    re-renders and failed attempts, so pressing Save again after a timeout sends the same key and
    the server recognizes the retry instead of creating a second case. A key generated inside
    `mutationFn` would be a fresh one per attempt, which is the mistake that makes the header
    look right and do nothing.
  */
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: Record<string, unknown>) =>
      api.post<TestCase>(`${base(projectId)}/cases`, input, { idempotencyKey: intentKey.current }),
    // The editor maps field errors onto the inputs and renders anything else in a form-level
    // banner, so the global handler must stay out of it or the user sees the same failure twice.
    meta: { handled: true },
    onSuccess: (created) => {
      client.setQueryData(keys.case(projectId, created.id), created);
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
    },
  });
}

/**
 * Edits a case.
 *
 * The version the client read travels in `If-Match`, **not** in the body. A PATCH body states the
 * desired state; the version it started from is a precondition about that state, and sending it as
 * a field puts a value on the input schema that must never be persisted. `openapi.yaml` has always
 * specified the header — this hook sent `lockVersion` in the body for as long as no server read
 * either, and the real endpoint reads the header (backend M1 slice 7).
 *
 * `lockVersion` is required rather than optional: omitting the header is last-write-wins, which is
 * correct for an importer with no prior read and never correct for an editor. Making the caller
 * pass it means a screen cannot silently opt out of conflict detection by forgetting.
 */
export function useUpdateCase(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ lockVersion, ...input }: Record<string, unknown> & { lockVersion: number }) =>
      api.patch<TestCase>(`${base(projectId)}/cases/${caseId}`, input, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    // Same reasoning as the create above — and this one also renders 409 conflicts itself,
    // which are a decision to hand back rather than a generic failure to announce.
    meta: { handled: true },
    onSuccess: (updated) => {
      client.setQueryData(keys.case(projectId, caseId), updated);
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      void client.invalidateQueries({ queryKey: keys.caseVersions(projectId, caseId) });
    },
  });
}

/**
 * Soft-deletes a case.
 *
 * `meta.handled` because the refusal is the message: `case:delete` is `OWN`-qualified for a Test
 * Engineer, so a 403 here says *this* case is someone else's — a sentence the dialog renders in
 * place, beside the case it is about. A global toast would show it at the corner and let the
 * dialog close as though nothing had happened.
 *
 * The case's own cached entry is **removed**, not invalidated: a refetch of a deleted case is a
 * 404, which the detail screen would render as an error where the honest answer is "it is gone".
 */
export function useDeleteCase(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (caseId: string) => api.del<void>(`${base(projectId)}/cases/${caseId}`),
    meta: { handled: true },
    onSuccess: (_result, caseId) => {
      client.removeQueries({ queryKey: keys.case(projectId, caseId) });
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      // The tree renders per-suite case counts, so a delete changes it even though no suite row
      // was touched.
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
      // The project header carries a case count too — stale by one until this lands. `exact`,
      // because `keys.project` is a PREFIX of every key above it: without it this one call would
      // invalidate the case list, the tree and the case that was just removed from the cache.
      void client.invalidateQueries({ queryKey: keys.project(projectId), exact: true });
    },
  });
}

export interface BulkInput {
  caseIds: string[];
  /** One of the contract's `BulkOperation.action` values. */
  action: string;
  /** Action-specific arguments — the contract's `payload`. */
  payload?: Record<string, unknown>;
  dryRun: boolean;
}

/**
 * Bulk edits are preview-then-commit: the same call with `dryRun` gives the preview, and
 * the result always names what changed, what was already compliant, and what was skipped
 * and why. Mass edits without a preview are how someone destroys a suite at 5pm.
 */
export function useBulkCases(projectId: string): UseMutationResult<BulkResult, Error, BulkInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: BulkInput) =>
      api.post<BulkResult>(`${base(projectId)}/cases/bulk`, input, { timeoutMs: TIMEOUTS.long }),
    // Bulk results are reported per item by the dialog. A blanket notification over a partial
    // failure is the worse half of the defect §6f exists to prevent.
    meta: { handled: true },
    onSuccess: (result) => {
      if (!result.dryRun) {
        void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      }
    },
  });
}

/**
 * One authored version.
 *
 * `createdBy` is nullable straight from the generated type: `test_case_version.created_by` is a
 * nullable column with no foreign key to `app_user`, so a version can be genuinely unattributed
 * (imported content) or carry an id that resolves to no account. The server serves `null` for
 * both, which is the honest answer — inventing a name would be a false claim about who changed a
 * test. §35 shipped against a hand-widened alias because the contract still said non-null; the
 * contract was corrected at integration and the alias deleted with it.
 */
export type CaseVersion = EntityVersion;

/**
 * A case's authored history, newest first.
 *
 * Unpaged, because the server's list is: it is bounded by how many times a human has saved one
 * case, and the panel needs the whole thing to be able to say which version is the earliest one
 * recorded.
 */
export function useCaseVersions(
  projectId: string,
  caseId: string | undefined,
): UseQueryResult<CaseVersion[]> {
  return useQuery({
    queryKey: keys.caseVersions(projectId, caseId ?? ''),
    queryFn: ({ signal }) => api.get<CaseVersion[]>(`${base(projectId)}/cases/${caseId}/versions`, { signal }),
    enabled: !!caseId,
  });
}

export interface CaseHistoryResponse {
  summary: CaseHistorySummary;
  entries: ExecutionHistoryEntry[];
  versionMarkers: VersionMarker[];
}

export function useCaseHistory(
  projectId: string,
  caseId: string | undefined,
  scope: 'CURRENT_VERSION' | 'ALL_VERSIONS',
): UseQueryResult<CaseHistoryResponse> {
  return useQuery({
    queryKey: keys.caseHistory(projectId, caseId ?? '', scope),
    queryFn: ({ signal }) =>
      api.get<CaseHistoryResponse>(`${base(projectId)}/cases/${caseId}/history${qs({ scope })}`, { signal }),
    enabled: !!caseId,
  });
}
