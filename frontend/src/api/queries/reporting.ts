/** The repository status dashboard, and §6e's report pipeline (§87/§88's backend, F1's client). */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, fetchArtifactJson } from '../client';
import type { ReportDefinition, ReportJob, ReportJobStatus, RepositoryDashboard } from '../types';
import { parseReportDocument, type ReportDocument } from '../../lib/reportDocument';
import { base, keys } from './keys';

export function useDashboard(projectId: string): UseQueryResult<RepositoryDashboard> {
  return useQuery({
    queryKey: keys.dashboard(projectId),
    queryFn: ({ signal }) =>
      api.get<RepositoryDashboard>(`${base(projectId)}/dashboard`, { signal, timeoutMs: TIMEOUTS.long }),
  });
}

/**
 * The two states a job never leaves.
 *
 * Exported because the *screen* needs the same predicate the poller does — a card decides whether
 * to show a spinner, a download or a failure from it — and the defect F1 is most likely to have is
 * the two disagreeing: a poller that stops on `COMPLETED` alone keeps asking a `FAILED` job for
 * ever, which renders as a report that is permanently about to be ready. One list, read twice.
 */
export const TERMINAL_JOB_STATES: readonly ReportJobStatus[] = ['COMPLETED', 'FAILED'];

export const isJobFinished = (status: ReportJobStatus): boolean =>
  TERMINAL_JOB_STATES.includes(status);

/** How often a running job is asked again. Slow enough not to hammer, fast enough to feel live. */
const POLL_INTERVAL_MS = 2_000;

/**
 * Saved report definitions (§6e's "regenerates next release with one click").
 *
 * The endpoint is declared and served, and **answers `[]` on every deployment** until R5 gives it
 * a writer. It is read anyway rather than stubbed out of the screen: the empty state is the honest
 * account of where the feature is, and a screen that learns to render the list only once rows
 * exist is a screen written against a shape nobody has seen — §81's and §87's finding, twice over.
 *
 * Declares no 403 (the operation is gated on project visibility alone), so its only refusal is the
 * 404 every project-scoped read answers.
 */
export function useReportDefinitions(projectId: string): UseQueryResult<ReportDefinition[]> {
  return useQuery({
    queryKey: keys.reportDefinitions(projectId),
    queryFn: ({ signal }) => api.get<ReportDefinition[]>(`${base(projectId)}/reports`, { signal }),
    enabled: !!projectId,
  });
}

/**
 * Asks for a report — a **202**, not a report.
 *
 * `meta: { handled: true }` because both refusals belong on the dialog that raised them: the 400
 * carries a field map naming an unknown type or an unrenderable format, and a corner toast saying
 * "check the highlighted fields" beside a dialog with no highlighted fields is the shape §65's
 * error rules exist to prevent.
 *
 * **No `idempotencyKey`, and that is a decision rather than an omission.** A key exists so a
 * retried write cannot create a second record; here a second record is a second *report*, which is
 * what a person pressing Generate twice is asking for. `useCreateRun` takes one because two runs
 * from one intent is a corrupted checklist; two identical run summaries are two files.
 *
 * The interactive budget, deliberately not the long one: this call writes a row and returns. The
 * long budget belongs on the download, where the bytes actually travel.
 */
export function useGenerateReport(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { type: string; format: string; parameters: Record<string, unknown> }) =>
      api.post<ReportJob>(`${base(projectId)}/reports/generate`, input),
    meta: { handled: true },
    // The 202 body IS the job's first state, so it is seeded rather than thrown away and re-asked
    // for. Without this the card that appears on submit renders a loading skeleton — for a row the
    // client is already holding — until the first poll lands a second or two later, which reads as
    // the request not having been accepted.
    onSuccess: (job) => client.setQueryData(keys.reportJob(projectId, job.jobId), job),
  });
}

/**
 * Watches one job to its end.
 *
 * **`refetchInterval` returns `false` once the job is finished**, so a terminal job is asked for
 * exactly once more than it needed and then never again. The two ways to get this wrong are the
 * two the plan names: polling on `!== 'COMPLETED'` never stops on a failure, and a fixed interval
 * with no terminal check keeps a completed job's request firing for as long as the tab is open —
 * on every card, for every report anybody generated this session.
 *
 * `refetchIntervalInBackground` is left off (the default): a hidden tab is not watching, and a
 * poll it cannot see is only load. The tab-focus refetch brings it current on return.
 *
 * `enabled` on a non-empty id, so the hook is safe to mount before there is a job. The card that
 * owns it is rendered per job id, so in practice the gate never fires — it is here because a hook
 * whose id can be empty is one call site away from firing at `/reports/jobs/`.
 */
export function useReportJob(projectId: string, jobId: string): UseQueryResult<ReportJob> {
  return useQuery({
    queryKey: keys.reportJob(projectId, jobId),
    queryFn: ({ signal }) =>
      api.get<ReportJob>(`${base(projectId)}/reports/jobs/${jobId}`, { signal }),
    enabled: !!projectId && !!jobId,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status && isJobFinished(status)) return false;
      return POLL_INTERVAL_MS;
    },
    // A finished job is immutable — the row never changes again — so there is nothing for a
    // window-focus refetch to discover. Left at the default while it runs; the interval above is
    // what keeps it current, and `staleTime` would only fight it.
    meta: { handled: true },
  });
}

/**
 * The stored report model — what the on-screen view renders (F2, slice-plan decision F).
 *
 * ## It reads the artifact the export was rendered from, and that is the whole design
 *
 * §6e: *"the on-screen report view consumes the same model via JSON, so what you see is what
 * exports."* Every job writes a `JSON` artifact beside whatever format was asked for, so this
 * screen and the file a client is sent are one computation rendered twice. The alternative the
 * plan rejected — a synchronous `preview` endpoint that recomputes — is a second code path
 * producing a second answer, and it would reintroduce the long request the 202 removed.
 *
 * ## The URL comes from the job and is never composed here
 *
 * `modelUrl` is server-supplied for `downloadUrl`'s reason (§67 on `Evidence.url`): a client that
 * built `/projects/{id}/reports/artifacts/{artifactId}/content` itself would hold a second copy of
 * the server's routing, and moving the route would then be a breaking change nothing detects. So
 * this hook takes the URL as data and `fetchArtifactJson` carries the credential to it — see that
 * function's same-origin guard, which exists because this URL *is* server data.
 *
 * ## What it needs is `report:export`, not `report:view` — and that is a finding, not a design
 *
 * The artifact route is gated on `report:export`, because it is the download. Reading a report on
 * screen therefore costs the same key as carrying it away, which contradicts the split §6e names
 * and §87 made expressible (*"may read on screen, may not carry off"*): a persona holding
 * `report:view` alone can watch a job succeed and then be refused the report it is watching. The
 * fix belongs to the server — either the model artifact is served under `report:view`, or the
 * on-screen view gets a route of its own — and this client cannot paper over it, so
 * `ReportViewPage` renders that 403 as the sentence it is. Recorded in the handoff; unobservable
 * in mock mode, which signs in holding every key.
 *
 * `staleTime: Infinity` because the artifact is **immutable**: a completed job's bytes never
 * change, so a refetch can only re-download a file this client already parsed. `enabled` on the
 * URL, so the hook is mounted from the first render of a job that has not finished yet and simply
 * does not fire until there is something to fetch — the dependent-query gate `data-fetching`
 * requires, resolving to a *state* rather than a permanent skeleton because the page renders the
 * job's own PENDING/RUNNING/FAILED rendering above it.
 */
export function useReportModel(
  projectId: string,
  jobId: string,
  modelUrl: string | null | undefined,
): UseQueryResult<ReportDocument> {
  return useQuery({
    queryKey: keys.reportModel(projectId, jobId),
    // Parsed at the boundary rather than cast — `lib/reportDocument.ts` says why at length, and the
    // short version is that this is the one response in the app with no generated type behind it.
    //
    // The narrowing is a real check rather than `modelUrl!`: `enabled` below already stops the
    // function running without a URL, but a non-null assertion would keep compiling the day a
    // caller adds a condition to `enabled`, and the failure would be a request to `undefined`.
    queryFn: async ({ signal }) => {
      if (!modelUrl) throw new Error('This report job has no stored model to display.');
      return parseReportDocument(
        await fetchArtifactJson(modelUrl, { signal, timeoutMs: TIMEOUTS.long }),
      );
    },
    enabled: !!projectId && !!jobId && !!modelUrl,
    staleTime: Infinity,
    // The region renders this failure in place, with the 403 above wording itself — a corner toast
    // repeating it beside a page whose entire body is that message is the second copy §65's error
    // rules exist to prevent.
    meta: { handled: true },
  });
}
