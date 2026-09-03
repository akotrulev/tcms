/**
 * The Jira / Azure DevOps offramp — connect, preview, map, run, report (014 M4).
 *
 * ## The token never becomes a query key, and this is the sentence that says so
 *
 * `POST /imports/source-projects` and `POST /imports/preview` are **reads**. They answer with a
 * list and with a dry run, they write nothing, and every instinct in this directory says a read is
 * a `useQuery`. They are `useMutation` anyway, and it is not a style choice:
 *
 * **A query key is a cache key.** TanStack Query stores the key alongside the entry, the devtools
 * panel renders it verbatim, and any persister added later writes it to disk. Both of these
 * operations carry an `ImportConnection`, and an `ImportConnection` carries somebody's API token —
 * so a `useQuery` keyed on its arguments would put a live credential into the cache, onto a
 * developer's screen, and eventually into `localStorage`. D-a's whole promise, the one
 * `import-connect.html` prints under the token field — *"the token is used for this import and is
 * not stored afterwards"* — dies at that line and nothing on any screen changes.
 *
 * So: **two keys in `keys.imports`, for the run and the project's list, and no third.** The two
 * credential-carrying operations are mutations, and their results live in the wizard component's
 * own state, which the tab throws away. A browser reload therefore means re-entering the token, and
 * the connect step says so out loud — that cost is the thing being bought, not a defect to fix by
 * caching.
 *
 * If you are reading this because you are about to turn one of them into a `useQuery`: the
 * behaviour you want (don't re-ask the source for its project list on every keystroke) is already
 * there, in the wizard's state. What you would be adding is the credential in the cache.
 *
 * ## The run is polled, and the poll stops
 *
 * `POST /imports` answers `202` — the read pass against the source takes minutes for a real project
 * — so {@link useImportRun} refetches while the status is `RUNNING` and stops the moment it is not.
 * A poll that ran on forever would be the spinner with no end that the server-side reaper exists to
 * prevent, reproduced on the client.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { TIMEOUTS, api, newIntentKey, qs } from '../client';
import type {
  ImportConnection,
  ImportInput,
  ImportPreview,
  ImportPreviewInput,
  ImportRun,
  ImportRunSummary,
  SourceProjectList,
} from '../types';
import { keys } from './keys';

/** How often a `RUNNING` run is re-read. */
const POLL_MS = 3_000;

/**
 * The projects this connection can see — **a mutation, and the header says why**.
 *
 * `TIMEOUTS.long` rather than `interactive`: this dials somebody else's server, and a site behind a
 * slow corporate proxy answering in twelve seconds is a working connection, not a failed one.
 */
export function useListSourceProjects() {
  return useMutation({
    mutationFn: (connection: ImportConnection) =>
      api.post<SourceProjectList>('/imports/source-projects', connection, {
        timeoutMs: TIMEOUTS.long,
      }),
    // The 502 carries the source system's own sentence, which is strictly more useful under the
    // token field than the global handler's "couldn't load". The connect step renders it there.
    meta: { handled: true },
  });
}

/**
 * The dry run — **this writes nothing**, and a test on the server snapshots every company-owned
 * table before and after to prove it.
 *
 * A mutation for {@link useListSourceProjects}' reason, and `TIMEOUTS.long` for it too: the preview
 * pages a real project's issues.
 */
export function usePreviewImport() {
  return useMutation({
    mutationFn: (input: ImportPreviewInput) =>
      api.post<ImportPreview>('/imports/preview', input, { timeoutMs: TIMEOUTS.long }),
    meta: { handled: true },
  });
}

/**
 * Confirm — `POST /imports`, with an `Idempotency-Key`.
 *
 * The key is generated **per intent, once per call**, which is what makes a timed-out confirm safe:
 * a request that ran past its deadline may well have started the run server-side, and without a key
 * the retry would start a second one that immediately fails on the `409` for having already
 * imported this source project. One key per user intent is `newIntentKey`'s whole contract.
 *
 * Invalidates the project's list, so the run appears on `settings-import.html` the moment it
 * starts rather than after the next full load.
 */
export function useStartImport(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ImportInput) =>
      api.post<ImportRun>('/imports', input, {
        timeoutMs: TIMEOUTS.interactive,
        idempotencyKey: newIntentKey(),
      }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.imports.forProject(projectId) });
    },
  });
}

/**
 * A project's past imports, newest first.
 *
 * **No call leaves this server** — the report is kept indefinitely and reaches a source system that
 * was decommissioned a year ago — which is also why the dev fault switch's three refusal arms leave
 * this read alone.
 */
export function useImportRuns(projectId: string): UseQueryResult<ImportRunSummary[]> {
  return useQuery({
    queryKey: keys.imports.forProject(projectId),
    queryFn: ({ signal }) =>
      api.get<ImportRunSummary[]>(`/imports${qs({ projectId })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * One run, polled while it is `RUNNING` and left alone once it is not.
 *
 * `refetchInterval` reads the query's own data rather than a flag passed in, so the poll stops on
 * the response that finished the run instead of one tick later — and a caller cannot forget to
 * turn it off.
 */
export function useImportRun(importRunId: string): UseQueryResult<ImportRun> {
  return useQuery({
    queryKey: keys.imports.run(importRunId),
    queryFn: ({ signal }) =>
      api.get<ImportRun>(`/imports/${importRunId}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: importRunId !== '',
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? POLL_MS : false),
  });
}
