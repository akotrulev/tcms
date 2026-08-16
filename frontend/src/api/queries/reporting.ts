/** The repository status dashboard, and §6e's report pipeline (§87/§88's backend, F1's client). */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, fetchArtifactJson } from '../client';
import type {
  ReportCapabilities,
  ReportDefinition,
  ReportJob,
  ReportJobStatus,
  RepositoryDashboard,
} from '../types';
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
 * ~~The endpoint is declared and served, and **answers `[]` on every deployment** until R5 gives it
 * a writer.~~ → **superseded (§227): it has a writer.** `useSaveReportDefinition` below is the one
 * hook the backend had been waiting for — `POST` and `DELETE` were implemented, gated and tested
 * on the server, and the panel that read them had no Save control, so the list was empty on every
 * deployment for a reason no reader could see. The paragraph is kept because the *reasoning* still
 * holds and is worth not re-deriving: the panel was built against a list nobody had seen rows in,
 * which is why turning it on took a hook and a button rather than a screen.
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
 * Saves the settings behind a report so the same one can be produced again.
 *
 * Costs `report:view`, not `report:export`, which looks inverted and is the contract's own
 * decision: the row is the caller's own workspace and is visible to nobody else, so requiring the
 * export key would stop the *may read, may not carry off* persona saving the report it is allowed
 * to read. The Save control is therefore gated on `report:view` and the Generate beside it on
 * `report:export` — two different keys on one dialog, which is correct and reads as odd, hence
 * this paragraph.
 *
 * `meta: { handled: true }` for `useGenerateReport`'s reason: the 400 names a `type` this server
 * cannot compute, and that belongs on the dialog that sent it.
 */
export function useSaveReportDefinition(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { type: string; name: string; parameters: Record<string, unknown> }) =>
      api.post<ReportDefinition>(`${base(projectId)}/reports`, input),
    meta: { handled: true },
    onSuccess: () => void client.invalidateQueries({ queryKey: keys.reportDefinitions(projectId) }),
  });
}

/**
 * Deletes a saved report.
 *
 * A hard delete of a saved *question* — the reports it produced survive it, because both
 * `report_job.definition_id` and `report_artifact.definition_id` are `ON DELETE SET NULL`. So the
 * confirmation this raises must not claim anything is being removed but the entry itself.
 *
 * The invalidation is the definitions key alone and deliberately **not** the job list: those rows
 * are still there and still downloadable, they have merely stopped naming a definition.
 */
export function useDeleteReportDefinition(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (definitionId: string) =>
      api.del<void>(`${base(projectId)}/reports/${definitionId}`),
    onSuccess: () => void client.invalidateQueries({ queryKey: keys.reportDefinitions(projectId) }),
  });
}

/**
 * What this deployment can render (B5).
 *
 * The picker used to be built from the contract's `format` enum, which is the set a person may
 * *ask* for and deliberately not the set any server has installed — so every deployment offered
 * PDF and every deployment refused it with a 400. The enum stays as it is; what changed is that
 * the server now answers the other question, and this is what asks it.
 *
 * **`staleTime: Infinity`** — the answer changes when an operator installs a renderer, which no
 * session outlives. Refetching it is a request per mount for a constant.
 *
 * **No `meta: { handled: true }`.** A failure here is not rendered anywhere: the dialog falls back
 * to offering nothing rather than to a broken picker, and without the global notification the
 * reader would see a Format field with no options and no reason at all.
 */
export function useReportCapabilities(projectId: string): UseQueryResult<ReportCapabilities> {
  return useQuery({
    queryKey: keys.reportCapabilities(projectId),
    queryFn: ({ signal }) =>
      api.get<ReportCapabilities>(`${base(projectId)}/reports/capabilities`, { signal }),
    enabled: !!projectId,
    staleTime: Infinity,
  });
}

/**
 * Every report this project has generated, newest first (§227).
 *
 * ## What it replaced, and why the replacement is a server list rather than a better client one
 *
 * The reports screen used to show *the jobs this tab had asked for*, held in component state, and
 * a reload lost every download link permanently while the artifacts sat in the store named by
 * nothing a client could ask for. `ReportsPage` said so at length and refused to work around it
 * with ids in `localStorage` — correctly: a client-side register is a second, drifting copy of a
 * table that already exists, which is the shape §81 found when the mock invented a feature the
 * server did not have. The fix was always the endpoint, and this is its client.
 *
 * ## It polls while anything is unfinished, and stops when nothing is
 *
 * The same predicate `useReportJob` uses, read over the list: a screen with one running job asks
 * again, a screen with none does not. Polling on `length > 0` instead would keep every reports
 * page in the company firing every two seconds for ever over rows that can no longer change.
 *
 * `meta: { handled: true }` — a failure here renders in the region, which is the whole panel; a
 * corner toast repeating it is the second copy §65's error rules exist to prevent.
 */
export function useReportJobs(projectId: string): UseQueryResult<ReportJob[]> {
  return useQuery({
    queryKey: keys.reportJobs(projectId),
    queryFn: ({ signal }) => api.get<ReportJob[]>(`${base(projectId)}/reports/jobs`, { signal }),
    enabled: !!projectId,
    refetchInterval: (query) =>
      (query.state.data ?? []).some((job) => !isJobFinished(job.status)) ? POLL_INTERVAL_MS : false,
    meta: { handled: true },
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
    /*
      `type` and `parameters` are optional because a saved definition supplies both — the contract's
      §92 arm, where the request is the *override* and the stored row is the base. Sending an empty
      `parameters: {}` alongside a `definitionId` would be harmless today (the merge is shallow) and
      is still not done: it reads as a caller clearing the saved selection.
    */
    mutationFn: (input: {
      definitionId?: string;
      type?: string;
      format: string;
      parameters?: Record<string, unknown>;
    }) => api.post<ReportJob>(`${base(projectId)}/reports/generate`, input),
    meta: { handled: true },
    // The 202 body IS the job's first state, so it is seeded rather than thrown away and re-asked
    // for. Without this the card that appears on submit renders a loading skeleton — for a row the
    // client is already holding — until the first poll lands a second or two later, which reads as
    // the request not having been accepted.
    //
    // The list is invalidated as well as seeded, and the two are not redundant: the seed is what
    // makes the *card* immediate, and the invalidation is what puts the new row in the list the
    // screen actually renders. Seeding the list by hand instead would mean composing its order
    // here — a second copy of the server's `requestedAt DESC`.
    onSuccess: (job) => {
      client.setQueryData(keys.reportJob(projectId, job.jobId), job);
      void client.invalidateQueries({ queryKey: keys.reportJobs(projectId) });
    },
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
 * built the path itself would hold a second copy of the server's routing, and moving the route
 * would then be a breaking change nothing detects — which is exactly what §227 did move it. So
 * this hook takes the URL as data and `fetchArtifactJson` carries the credential to it — see that
 * function's same-origin guard, which exists because this URL *is* server data.
 *
 * ## It costs `report:view` — and until §227 that was a finding rather than a design
 *
 * ~~The artifact route is gated on `report:export`, because it is the download. Reading a report on
 * screen therefore costs the same key as carrying it away, which contradicts the split §6e names
 * and §87 made expressible (*"may read on screen, may not carry off"*): a persona holding
 * `report:view` alone can watch a job succeed and then be refused the report it is watching.~~ →
 * **closed by the second of the two fixes that paragraph offered.** `modelUrl` now points at
 * `GET …/reports/jobs/{jobId}/model`, which is the on-screen view's own route and costs
 * `report:view`; the artifact route still costs `report:export` and is still what `downloadUrl`
 * names. Both keys were seeded in M0 and the product could not tell them apart until then.
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
