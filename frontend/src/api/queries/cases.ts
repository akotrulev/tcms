/** The case list, the case, its writes, its versions and its execution history. */
import {
  keepPreviousData,
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useRef } from 'react';
import { TIMEOUTS, api, newIntentKey, qs } from '../client';
import type {
  Attachment,
  BulkOperation,
  BulkResult,
  CaseDraft,
  CaseFilters,
  CaseFlake,
  CaseHistorySummary,
  EntityVersion,
  DocLink,
  ExecutionHistoryEntry,
  ExternalLink,
  ExternalLinkInput,
  Paged,
  TagUsage,
  TestCase,
  TestCaseCloneInput,
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

/**
 * A **collection** reference on a plan group — a suite or a tag (§103).
 *
 * Deliberately not the plan's third arm. A directly-named case id has no read on the contract that
 * resolves it (`?q=` matches a *key*, not an id), and it is already drawn in full by its own chip.
 */
export type CaseReference = { kind: 'suite'; id: string } | { kind: 'tag'; value: string };

/**
 * One page is the ceiling for a resolved reference, and the caller is told when it bit.
 *
 * 200 rather than "everything": there is no all-pages read, and a list nobody can scroll to the end
 * of is not more honest than one that says where it stopped. `resolveReferences` compares `total`
 * against what arrived and surfaces the difference.
 */
const REFERENCE_PAGE = 200;

function referenceFilters(reference: CaseReference): CaseFilters {
  return reference.kind === 'suite'
    ? { suiteId: reference.id, size: REFERENCE_PAGE }
    : { tag: [reference.value], size: REFERENCE_PAGE };
}

/** What one reference's read produced, in the order the references were given. */
export interface ReferenceCases {
  reference: CaseReference;
  cases: TestCaseSummary[];
  total: number;
}

/**
 * Structurally a `DataRegionState<ReferenceCases[]>`, spelled out here so the data module does not
 * import from `components/`. `DataRegion` accepts it by shape; a drift between the two is a build
 * error at the call site, which is where it belongs.
 */
export interface ReferencedCasesState {
  data: ReferenceCases[] | undefined;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  isFetching: boolean;
  dataUpdatedAt: number | undefined;
  refetch: () => void;
}

/**
 * **Resolves a plan group's suite and tag references to the cases they currently catch (§103).**
 *
 * ## One read per reference, and that is the design
 *
 * The references **union** — a section naming two suites wants both — while `GET /cases` narrows,
 * and its `tag` parameter repeats and *ANDs*. One request carrying every reference therefore
 * answers the intersection: usually empty, and empty in a way that reads as "this section catches
 * nothing". N references are N reads, unioned in `lib/planScope.ts`.
 *
 * ## And the server does the resolving, always
 *
 * A suite is filed ∪ added ∪ tagged since §98. Filtering `case.suiteId` in the client is the filed
 * arm alone — §96 shipped it and produced a tag-defined suite whose count said 2 over a grid of 0.
 * `?suiteId=` is the endpoint that answers the union, and it is the only thing that can.
 *
 * The key is `keys.cases`, shared with the grid: a resolution and a filtered list asking the same
 * question are one cache entry, and `useCreateCase`/`useUpdateCase` already invalidate the
 * `['projects', p, 'cases']` prefix, so a case that gains a tag moves this list with no extra wiring.
 */
export function useReferencedCases(
  projectId: string,
  references: CaseReference[],
): ReferencedCasesState {
  return useQueries({
    queries: references.map((reference) => {
      const filters = referenceFilters(reference);
      return {
        queryKey: keys.cases(projectId, filters, 'key,asc'),
        queryFn: ({ signal }: { signal: AbortSignal }) =>
          api.get<Paged<TestCaseSummary>>(`${base(projectId)}/cases${qs({ ...filters, sort: 'key,asc' })}`, {
            signal,
            timeoutMs: TIMEOUTS.interactive,
          }),
      };
    }),
    /*
      Combined into ONE region state rather than N, because the reader's question is about the group
      and not about an arm of it. The combination is deliberately pessimistic in both directions:

        - **pending while ANY arm is pending** — a half-arrived union rendered as a list is a set of
          rows that is wrong and looks complete;
        - **failed if ANY arm failed** — the union of the arms that *did* answer is a shorter list
          with nothing on screen saying so, which is the same defect one layer down.

      `data` is still handed back when a later refetch fails, which is what lets `DataRegion` keep
      the rows and mark them stale rather than blanking them.
    */
    combine: (results): ReferencedCasesState => {
      const failed = results.find((result) => result.isError);
      const loaded = results.every((result) => result.data !== undefined);
      const oldest = results.reduce(
        (soFar, result) => Math.min(soFar, result.dataUpdatedAt || Infinity),
        Infinity,
      );
      return {
        data: loaded
          ? results.map((result, index) => ({
              reference: references[index],
              cases: result.data?.items ?? [],
              total: result.data?.total ?? 0,
            }))
          : undefined,
        isPending: results.some((result) => result.isPending),
        isError: failed !== undefined,
        error: failed?.error,
        isFetching: results.some((result) => result.isFetching),
        // The OLDEST arm, not the newest: the stale marker names how old the list is, and a union
        // is only as current as the arm that last refreshed successfully.
        dataUpdatedAt: Number.isFinite(oldest) ? oldest : undefined,
        refetch: () => results.forEach((result) => void result.refetch()),
      };
    },
  });
}

/**
 * The tags this project's cases actually carry, with how many carry each.
 *
 * **Shipped without a consumer in §97 phase 3b and this is where it gets one** — §96 shipped both
 * tag controls with an empty option list and named this endpoint as the follow-up, because a tag is
 * now a *fill mechanism* for a plan section (D8) and a picker that cannot say which tags exist makes
 * it unusable.
 *
 * Free text stays free text: this is a suggestion source, never a vocabulary. The server takes any
 * string, and a control that refused an unlisted tag would make the first use of a new one
 * impossible.
 */
export function useProjectTags(projectId: string): UseQueryResult<TagUsage[]> {
  return useQuery({
    queryKey: keys.tags(projectId),
    queryFn: ({ signal }) => api.get<TagUsage[]>(`${base(projectId)}/tags`, { signal }),
    // A project's tag vocabulary moves when somebody edits a case, not between two keystrokes in a
    // picker; the case editor's own writes invalidate the prefix this key sits under.
    staleTime: 60_000,
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
 * Clones a case (§129).
 *
 * **The same cache work as a create, because the server does the same work** — the response is a
 * whole new `TestCase` with its own key, its own version 1 and its own row in the grid, so the case
 * entry is seeded and the list, the tree (its per-suite counts moved) and the project header are
 * invalidated exactly as `useCreateCase` does. A hook that only seeded the new case would leave the
 * clone missing from the grid the user is about to be looking at.
 *
 * One `Idempotency-Key` per mounted dialog, for `useCreateCase`'s recorded reason: a key minted
 * inside `mutationFn` is a fresh one per attempt, which makes the header look right and do nothing.
 * It matters more here than on a create — the retried request duplicates every step, every
 * classification value and every **byte-copied attachment**.
 */
export function useCloneCase(projectId: string, caseId: string) {
  const client = useQueryClient();
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: TestCaseCloneInput) =>
      api.post<TestCase>(`${base(projectId)}/cases/${caseId}/clone`, input, {
        idempotencyKey: intentKey.current,
        // The attachments are copied byte for byte inside the request, so this is not an
        // interactive read's budget: a case with a handful of screenshots moves megabytes through
        // one POST, and the interactive budget would time out a request the server then completes.
        timeoutMs: TIMEOUTS.long,
      }),
    // The dialog lands `title` on its own field and everything else in a form-level banner, so the
    // global handler must stay out of it or the user is told twice.
    meta: { handled: true },
    onSuccess: (created) => {
      client.setQueryData(keys.case(projectId, created.id), created);
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
      void client.invalidateQueries({ queryKey: keys.project(projectId), exact: true });
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

/**
 * Quarantines a flaky test, or puts it back into the gate (§121).
 *
 * ## `PUT` with an absolute state, and the hook keeps it that way
 *
 * The endpoint takes the state to move to rather than a verb, so a client sending the same request
 * twice is never told 404 or 409 about a state it already asked for. This hook therefore takes
 * `quarantined` explicitly and **never derives it by negating what it read** — a toggle computed
 * from a stale cache is precisely how "the quarantine turned itself off" happens, and the two call
 * sites are two separate confirmed actions rather than one switch.
 *
 * ## Not optimistic, deliberately unlike `useMoveWorkItem`
 *
 * §106's board write is optimistic because a *drag* that springs back reads as the drop having
 * missed. This is a dialog with a confirm button, where 300ms of latency reads as the button
 * working — and the response carries three fields the client cannot compute (`quarantinedAt`, the
 * resolved `quarantinedBy`, and the derived counts, which is why the endpoint answers 200 with the
 * row rather than 204). Predicting them would mean inventing a decision-maker and a timestamp on
 * screen and then replacing them a moment later.
 *
 * The case's own cache entry is written from the response, so the panel re-renders from the server's
 * account of the decision rather than from the client's.
 */
/**
 * Links this case to a range of lines in a repository, or to a URL (§169).
 *
 * ## The response is patched into the case, and it is not an optimistic guess
 *
 * `relatedDocuments` is served from `case_doc_link`, a read model refreshed on an event — so a
 * plain invalidate refetches the case and gets **the same list back**, without the link somebody
 * just made, for up to the outbox interval. The panel would sit there looking as though the
 * request had failed with a success code on top.
 *
 * What is inserted is the server's own answer to the create, re-spelled into the shape the panel
 * reads: the id it minted, the kind and relationship it stored, the six coordinates it accepted.
 * The mirror's external arm copies those columns verbatim and stores `stale: false` (`V47` §3), so
 * this is not a second implementation of a server rule — it is the row the server described,
 * rendered a minute before the projection catches up with it. Anything the mirror decides
 * differently wins at the next refetch, which is the direction that self-corrects.
 *
 * The case key is invalidated too, so the eventual refetch reconciles rather than trusting this
 * forever.
 */
export function useLinkCaseExternally(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ExternalLinkInput) =>
      api.post<ExternalLink>(`${base(projectId)}/cases/${caseId}/external-links`, input),
    // The dialog lands per-field errors on its inputs — the endpoint reports `commitSha`, `url`,
    // `lineStart` and the rest by name precisely so a form can — and a corner toast would show
    // them detached from the fields they are about.
    meta: { handled: true },
    onSuccess: (created) => {
      client.setQueryData<TestCase>(keys.case(projectId, caseId), (previous) => {
        if (!previous) return previous;
        const already = (previous.relatedDocuments ?? []).some((link) => link.id === created.id);
        // A re-assert answers 200 with the link that was already there. Appending it again would
        // put the same claim in the panel twice on the strength of a request that wrote nothing.
        if (already) return previous;
        return {
          ...previous,
          relatedDocuments: [...(previous.relatedDocuments ?? []), asDocLink(created)],
        };
      });
      // AND NO INVALIDATE. That is the whole difference between this write and every other one in
      // this file, and it is the projection's: an immediate refetch asks for a read model the
      // event has not reached yet and gets the list WITHOUT this link, which erases the row the
      // server just described and reads as a create that failed with a 201. The reconciliation is
      // the next ordinary refetch — a remount, a focus, a stale read — by which time the mirror
      // has moved. This was found by a test, not by reasoning: the patch and the invalidate were
      // both written here first, and the panel lost the link a millisecond after showing it.
    },
  });
}

/** Removes one of this case's external links. Hard delete on the server. */
export function useUnlinkCaseExternal(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (linkId: string) =>
      api.del<void>(`${base(projectId)}/cases/${caseId}/external-links/${linkId}`),
    meta: { handled: true },
    onSuccess: (_removed, linkId) => {
      // Dropped from the cached list for the same reason the create adds to it: the mirror will
      // agree within the interval, and until it does a withdrawn claim left on the screen reads as
      // a delete that did not work.
      client.setQueryData<TestCase>(keys.case(projectId, caseId), (previous) =>
        previous
          ? {
              ...previous,
              relatedDocuments: (previous.relatedDocuments ?? []).filter(
                (link) => link.id !== linkId,
              ),
            }
          : previous,
      );
      // No invalidate, for the create's reason in the other direction: the mirror still holds the
      // row for up to the outbox interval, so a refetch now would put the withdrawn claim straight
      // back on the screen.
    },
  });
}

/**
 * The create's answer in the shape the panel reads.
 *
 * The four page properties are null and `stale` is false — which is what the mirror writes for an
 * external target, at the column, in SQL. Spelled out rather than spread, so a property added to
 * `DocLink` is a compile error here instead of an `undefined` in a rendered panel.
 */
function asDocLink(created: ExternalLink): DocLink {
  return {
    id: created.id,
    targetKind: created.targetKind,
    relationship: created.relationship,
    pageId: null,
    pageKey: null,
    pageTitle: null,
    blockId: null,
    blockExcerpt: null,
    externalRepo: created.externalRepo ?? null,
    externalPath: created.externalPath ?? null,
    externalLineStart: created.externalLineStart ?? null,
    externalLineEnd: created.externalLineEnd ?? null,
    externalCommitSha: created.externalCommitSha ?? null,
    externalUrl: created.externalUrl ?? null,
    stale: false,
  };
}

export function useQuarantineCase(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ quarantined, reason }: { quarantined: boolean; reason?: string }) =>
      api.put<CaseFlake>(`${base(projectId)}/cases/${caseId}/quarantine`, { quarantined, reason }),
    // The dialog lands the 400 on its own reason field — the server raises a missing reason as a
    // field error precisely so a form can, and a global toast would show it at the corner while the
    // dialog sat there looking like it had not been submitted.
    meta: { handled: true },
    onSuccess: (flake) => {
      // Patched into the case rather than invalidated, so the panel does not blank while a refetch
      // of the whole case runs. `flake` is the ONLY field this write can change: rewriting the
      // cached case wholesale from a response that describes one row is how a screen loses the
      // edits another tab made — the `EXCLUDED.*` mistake one table over, in a cache.
      client.setQueryData<TestCase>(keys.case(projectId, caseId), (previous) =>
        previous ? { ...previous, flake } : previous,
      );
      // The board's quarantine card (§121 phase 3) is derived from this row, and it is on a
      // different screen with a different key — without this a release leaves the card standing
      // until something else happens to invalidate the board.
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
    },
  });
}

/**
 * A bulk operation, typed from the contract rather than restated.
 *
 * `action` was `string` and `payload` was `Record<string, unknown>` until the endpoint gained a
 * server: nothing checked either end, so a misspelled `lifecycleStatis` was an argument the server
 * silently did not receive. Both now come straight from `BulkOperation`, so the action list and the
 * payload members are whatever `openapi.yaml` says and a typo is a compile error.
 */
export type BulkInput = Pick<BulkOperation, 'caseIds' | 'action' | 'payload'> & {
  /**
   * Always sent explicitly, though the contract defaults it to `true`. The default is the safe
   * direction on the wire; being explicit here is what keeps `run(true)` and `run(false)` readable
   * as "preview" and "commit" at the one call site that matters.
   */
  dryRun: boolean;
};

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

/**
 * Creates one case for the plan detail form's Save — **the key comes from the caller**.
 *
 * ## The third shape, and the two it is not
 *
 * `useCreateCase` holds one key per mounted editor (one intent, one case). `useCreateDraftCase`
 * minted one per submit (many intents, one at a time, retries unprotected) — retired below, its
 * argument kept because the shape recurs. This caller is neither of them:
 * §97's Save writes **N cases in one loop**, so a per-hook key would make the server read rows 2…N
 * as replays of row 1 and return the first case each time — five titles typed, one case created,
 * four scope entries pointing at it, and nothing on screen that looks wrong. A per-call
 * `newIntentKey()` would avoid that and give up the protection that matters most here: a save that
 * fails at the plan step is one the author will press again, and the second press must not write
 * the cases twice.
 *
 * So the key is **the draft row's**, minted when the row appeared and carried until it is saved —
 * one key per row per intent to create *that* case. See `planDraft.NewCaseRow`.
 */
export function useCreatePlanCase(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      intentKey,
      ...input
    }: { intentKey: string; title: string; suiteId: string | null }) =>
      api.post<TestCase>(`${base(projectId)}/cases`, input, { idempotencyKey: intentKey }),
    // The form renders the per-step outcome itself, naming what was created before the failure.
    meta: { handled: true },
    onSuccess: (created) => {
      client.setQueryData(keys.case(projectId, created.id), created);
      void client.invalidateQueries({ queryKey: ['projects', projectId, 'cases'] });
      void client.invalidateQueries({ queryKey: keys.suites(projectId) });
    },
  });
}

/*
  ~~`useCreateDraftCase` — creates a draft case from a title alone, one idempotency key per submit.~~
  → **retired 2026-08-05 (§97 phase 5)** with its only caller, the plan document's *Add a test case*
  bar. That bar contradicted three of this slice's decisions at once: it required a suite (D2 says
  none), it created the case the moment the button was pressed (§5 says nothing is created until
  Save), and it filed the plan's cases as bullets in a document rather than in the plan's own scope.

  Its argument is kept because it is about **keys and not about that bar**, and the shape it names
  will come back: a key per *submit* is right for a control from which somebody adds ten cases in a
  row, where a key per mounted component would make the tenth case a replay of the first. §97's Save
  is the third shape again — a key per ROW, see `useCreatePlanCase` above — because it writes N in
  one loop and must survive being pressed twice.
*/

/**
 * Attaches a file to a case's **definition** (#49).
 *
 * `api.upload`, never `api.post`: the body is `FormData`, and the client's one rule about that is
 * that no `Content-Type` may be hand-set — the browser generates `multipart/form-data; boundary=…`
 * and the boundary is the only thing telling the server where a part ends. `upload` also defaults
 * to the **long** budget, because a 10MB file over hotel wifi outruns the 10s interactive one and
 * the failure then reads as a broken server rather than a slow link.
 *
 * ## The case is invalidated, not patched
 *
 * The response is the new `Attachment` and the screen renders `TestCase.attachments`, so a
 * `setQueryData` here would be the client re-assembling a list the server already serves — §67's
 * own reasoning for the evidence strip, and the shape §52's mock/client divergence came out of.
 * The list is **detail-only** (`TestCaseSummary` has no `attachments`), so no grid key moves.
 *
 * `handled`, because the panel renders the refusal in place: a **413** carries no problem document
 * at all (the servlet container refuses the request before any handler runs), and a corner toast
 * for it would be detached from the control that raised it.
 */
export function useAttachCaseFile(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append('file', file);
      return api.upload<Attachment>(`${base(projectId)}/cases/${caseId}/attachments`, form);
    },
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.case(projectId, caseId) });
    },
  });
}

/**
 * Removes a file from a case. **A hard delete — the bytes go with the row.**
 *
 * Costs `case:edit`, the same key the upload does, and deliberately **without** `evidence:delete`'s
 * `OWN` arm: whoever may edit the case may remove any of its files, including one somebody else
 * attached. See `components/AttachmentList.tsx` and the endpoint's own description — an authorship
 * arm here would make removing the screenshot stricter than removing the step that references it.
 */
export function useDeleteCaseFile(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (attachmentId: string) =>
      api.del<void>(`${base(projectId)}/cases/${caseId}/attachments/${attachmentId}`),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.case(projectId, caseId) });
    },
  });
}

/**
 * The case's buffered autosave draft, or `null` when there is none (§128).
 *
 * ## `null` is a value here, and it must not be `undefined`
 *
 * The endpoint answers **204** for a readable case with nothing buffered — which is the state of
 * almost every case almost all of the time, not an edge — and `api.get` maps a 204 to `undefined`.
 * TanStack Query treats an `undefined` return as a programming error and throws
 * *"Query data cannot be undefined"*, so the ordinary answer would surface as a failed query and
 * the editor would render an error where the truth is "you have no unsaved work". Normalising to
 * `null` at the boundary is what keeps "no draft" a **loaded** state.
 *
 * ## No `staleTime`, and no refetch on focus either
 *
 * Read **once per editor mount** and then owned by the mutations below. It is deliberately not
 * kept live: the only writer of this row while the editor is open is the editor itself, and a
 * refetch landing mid-typing would offer to restore the draft the user is currently writing.
 * `refetchOnWindowFocus` is off here for that reason and only that reason — the standing rule that
 * a returning tab must not show yesterday's data is about *server-owned* data, and this row's
 * current value is on screen in the form.
 */
export function useCaseDraft(
  projectId: string,
  caseId: string | undefined,
): UseQueryResult<CaseDraft | null> {
  return useQuery({
    queryKey: keys.caseDraft(projectId, caseId ?? ''),
    queryFn: ({ signal }) =>
      api
        .get<CaseDraft | undefined>(`${base(projectId)}/cases/${caseId}/draft`, { signal })
        .then((draft) => draft ?? null),
    enabled: !!caseId,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  });
}

/**
 * Buffers the editor's current state — the autosave.
 *
 * ## `meta.handled`, and this one is the strongest case for it in the module
 *
 * Every other `handled` mutation here renders its own failure somewhere. This one renders it in a
 * status line and **nowhere else**, because the caller is a timer rather than a person: a global
 * toast for a failed autosave would appear unbidden, mid-sentence, once per interval, about
 * something the user did not ask for and cannot act on. The honest surface is the editor saying it
 * has not been able to buffer — which is what `CaseEditorPage` renders — and the work itself is
 * still safe in the form.
 *
 * ## The cache is written from the response, not invalidated
 *
 * An invalidation would refetch the row this call just wrote, on every keystroke interval, to learn
 * what it already knows. Worse, the refetch could land after a later autosave and put an older
 * `updatedAt` on screen. The response is the row.
 */
export function useSaveCaseDraft(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { lockVersion: number; content: Record<string, unknown> }) =>
      api.put<CaseDraft>(`${base(projectId)}/cases/${caseId}/draft`, input),
    meta: { handled: true },
    onSuccess: (draft) => {
      client.setQueryData(keys.caseDraft(projectId, caseId), draft);
    },
  });
}

/**
 * Throws the buffered draft away.
 *
 * The cache entry is set to `null` rather than invalidated: the endpoint answers 204 and there is
 * nothing to refetch, and a refetch would spend a round trip to be told the same thing. `null` is
 * the loaded "no draft" state — see {@link useCaseDraft} for why it is not `undefined`.
 */
export function useDiscardCaseDraft(projectId: string, caseId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api.del<void>(`${base(projectId)}/cases/${caseId}/draft`),
    meta: { handled: true },
    onSuccess: () => {
      client.setQueryData(keys.caseDraft(projectId, caseId), null);
    },
  });
}
