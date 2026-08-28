/**
 * Issue-tracker integration (plan 004) — the project's bindings, the caller's own connections, the
 * issues read through them, and the links a case holds.
 *
 * ## Two owners, two gates, and that is the whole shape of this module
 *
 * A **binding** is the project's: which Jira site, which project key, which adapter settings. It is
 * non-secret and costs `integration:manage` to read or write, so its hooks take an `enabled` the
 * caller drives from the gate — `integration.ts`'s shape, for its reason.
 *
 * An **identity** is a person's: their own PAT for a host, encrypted at rest and returned by
 * nothing. It is gated on *being the caller* and on nothing else, so there is no `enabled` and no
 * permission read anywhere near it. A colleague's identity is not something this API can ask for.
 *
 * Reading an issue needs both — `project:read` decides whether a link is *visible*, the caller's
 * own credential decides whether its *content* is. That two-gate shape is why 004 adds no
 * catalogue key: there is nothing left for one to decide.
 *
 * ## An unconnected viewer is a 200, and the hooks must not turn it back into a failure
 *
 * `GET …/tracker/issues*` answers `200` with `state: 'NOT_CONNECTED' | 'UNAVAILABLE'` rather than a
 * `401` — the viewer is authenticated to TCMS perfectly well, and a `401` would trip the SPA's
 * session-recovery path (`app/queryClient.ts`, and the whole subject of `tcmsFaults.expire()`) for
 * a condition that has nothing to do with their session. So **nothing here treats a degraded state
 * as an error**: it is data, the region renders it, and `meta: { handled: true }` keeps a corner
 * notification off a screen where nothing went wrong.
 *
 * ## Nothing cached here is shared between people
 *
 * The client cache is per session and therefore already per user, which is what makes these keys
 * correct without a user segment. The **server's** cache is the one 004 D15 is about — it keys on
 * `(userId, bindingId, issueId)`, because a per-binding key is a cross-user data leak with a
 * sixty-second window. That rule lives with that cache; it is recorded here so a reader who starts
 * on this side does not conclude the key shape is the whole story.
 */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api, newIntentKey, qs, TIMEOUTS } from '../client';
import type {
  IssueLink,
  IssueLinkInput,
  TrackerBinding,
  TrackerBindingInput,
  TrackerBindingPatch,
  TrackerConnectedMember,
  TrackerConnectionCheck,
  TrackerIdentity,
  TrackerIdentityInput,
  TrackerIssueCreateInput,
  TrackerIssueCreated,
  TrackerIssueLookup,
  TrackerIssueSearch,
} from '../types';
import { base, keys } from './keys';

const tracker = (projectId: string) => `${base(projectId)}/tracker`;

// ------------------------------------------------------------------ bindings ----

/**
 * The project's bindings. Gated, like everything under `integration:manage`.
 *
 * **The verdict must be resolved, not merely fail-closed**: pass
 * `gate.pending ? false : gate.can('integration:manage')`, or the list flashes a refusal at an
 * administrator on every navigation.
 */
export function useTrackerBindings(
  projectId: string,
  enabled: boolean,
): UseQueryResult<TrackerBinding[]> {
  return useQuery({
    queryKey: keys.trackerBindings(projectId),
    queryFn: ({ signal }) =>
      api.get<TrackerBinding[]>(`${tracker(projectId)}/bindings`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && !!projectId,
    staleTime: 30_000,
  });
}

/**
 * Adds a binding.
 *
 * No `Idempotency-Key` — there is no idempotency record behind this path, and a client that sent
 * the header and trusted it would replay a timed-out create into a second binding. `meta.handled`
 * because the dialog lands `errors.config` and `errors.baseUrl` on its own inputs; a toast would be
 * a vaguer second copy of a message already beside the field.
 */
export function useCreateTrackerBinding(
  projectId: string,
): UseMutationResult<TrackerBinding, Error, TrackerBindingInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: TrackerBindingInput) =>
      api.post<TrackerBinding>(`${tracker(projectId)}/bindings`, input),
    meta: { handled: true },
    onSuccess: () => invalidateBindings(client, projectId),
  });
}

/**
 * Edits a binding — a `PATCH`, with `lockVersion` on the wire as `If-Match`.
 *
 * `provider` and `baseUrl` are absent from `TrackerBindingPatch` by the contract's decision, not by
 * this hook's: moving a binding to a different host would orphan every identity anybody connected
 * for the old one, so it is a delete and a create rather than an edit.
 */
export function useUpdateTrackerBinding(
  projectId: string,
): UseMutationResult<
  TrackerBinding,
  Error,
  { bindingId: string; lockVersion: number; patch: TrackerBindingPatch }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ bindingId, lockVersion, patch }) =>
      api.patch<TrackerBinding>(`${tracker(projectId)}/bindings/${bindingId}`, patch, {
        headers: { 'If-Match': String(lockVersion) },
      }),
    meta: { handled: true },
    onSuccess: () => invalidateBindings(client, projectId),
  });
}

/**
 * Removes a binding.
 *
 * **Nothing cascades** (004 D18), and the invalidation says so: the case's links still exist, still
 * have a working href, and merely stop previewing. What does change is which hosts the
 * connected-members list scores people against, which is why that entry is invalidated too — it is
 * under the same `tracker` prefix, so one call covers both.
 */
export function useDeleteTrackerBinding(
  projectId: string,
): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (bindingId: string) =>
      api.del<void>(`${tracker(projectId)}/bindings/${bindingId}`),
    meta: { handled: true },
    onSuccess: () => invalidateBindings(client, projectId),
  });
}

/**
 * Every entry under this project's `tracker` prefix.
 *
 * One helper rather than three call sites naming three keys: a binding write moves the list, the
 * connected-members states (the set of bound hosts changed) and every issue read made through it,
 * and the failure mode of forgetting one is a screen that never refreshes with nothing on it to
 * say so.
 */
function invalidateBindings(client: ReturnType<typeof useQueryClient>, projectId: string) {
  void client.invalidateQueries({ queryKey: keys.trackerRoot(projectId) });
}

/**
 * Who on this project has connected, per bound host (004 D19).
 *
 * **The response carries a name and a state and nothing else** — no hint, no account id, no expiry,
 * no scopes, no error text. That is the endpoint's decision and a test closes the set; this hook
 * merely must not be the place somebody "enriches" it from the identity view, which has all five.
 */
export function useTrackerConnectedMembers(
  projectId: string,
  enabled: boolean,
): UseQueryResult<TrackerConnectedMember[]> {
  return useQuery({
    queryKey: keys.trackerConnectedMembers(projectId),
    queryFn: ({ signal }) =>
      api.get<TrackerConnectedMember[]>(`${tracker(projectId)}/connected-members`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: enabled && !!projectId,
    staleTime: 30_000,
  });
}

// ------------------------------------------------------------------ identities ----

/**
 * The caller's own connections. No `enabled` and no gate: this is self-service on your own account.
 *
 * `staleTime: 0`, unlike the bindings beside it, for `useNotificationDeliveries`' reason — somebody
 * opens this immediately after connecting or testing, and a cached answer from thirty seconds ago
 * is the one state that cannot tell them whether it worked.
 */
export function useMyTrackerIdentities(): UseQueryResult<TrackerIdentity[]> {
  return useQuery({
    queryKey: keys.myTrackerIdentities,
    queryFn: ({ signal }) =>
      api.get<TrackerIdentity[]>('/me/tracker-identities', {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    staleTime: 0,
  });
}

/**
 * Connects a host, or rotates the credential for one already connected.
 *
 * **The input is forwarded untouched.** `secret` is required here — a `POST` always sets one — but
 * the form around it is `NotificationChannelInput`'s secret-set-separately shape for the *other*
 * fields, and this hook must not default, normalise or fill anything: a blank reaching the server
 * is a credential somebody merely renamed their way out of.
 */
export function useConnectTracker(): UseMutationResult<
  TrackerIdentity,
  Error,
  TrackerIdentityInput
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: TrackerIdentityInput) =>
      api.post<TrackerIdentity>('/me/tracker-identities', input),
    meta: { handled: true },
    onSuccess: () => invalidateMyTracker(client),
  });
}

/** Disconnects a host. Every link previewed through it degrades; nothing is deleted. */
export function useDisconnectTracker(): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (identityId: string) => api.del<void>(`/me/tracker-identities/${identityId}`),
    meta: { handled: true },
    onSuccess: () => invalidateMyTracker(client),
  });
}

/**
 * Calls the tracker once and records what it said.
 *
 * The **check** is the answer (`ok`, `checkedAt`, `error`) and the row is also rewritten server
 * side, so both are refreshed. `error` here is the one place a tracker's own refusal reaches a
 * person — theirs, about their credential — and it is shown to nobody else.
 */
export function useTestTrackerIdentity(): UseMutationResult<
  TrackerConnectionCheck,
  Error,
  string
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (identityId: string) =>
      api.post<TrackerConnectionCheck>(`/me/tracker-identities/${identityId}/test`, undefined, {
        // A tracker that is down consumes the whole budget, and this is the one call whose entire
        // purpose is finding that out — so it gets the interactive one rather than the typeahead's.
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateMyTracker(client),
  });
}

/**
 * The caller's identities, and every issue read anywhere.
 *
 * Connecting or disconnecting changes what **every** chip in the app can show, in every project —
 * an identity is per host, not per project — so the invalidation reaches all of them. It is a broad
 * sweep by design: the alternative is a chip that keeps saying "connect your account" for as long
 * as its entry stays fresh after the person did.
 */
function invalidateMyTracker(client: ReturnType<typeof useQueryClient>) {
  void client.invalidateQueries({ queryKey: keys.myTrackerIdentities });
  void client.invalidateQueries({ queryKey: keys.projects });
}

// ------------------------------------------------------------------ issues ----

/**
 * The issue typeahead, as the caller (004 Phase 2).
 *
 * `TIMEOUTS.typeahead`, not `interactive`: this fires as somebody types into a link dialog, and a
 * completion that waits ten seconds on somebody else's server is broken however correct its answer.
 * Gated on a term, like every other search in this directory.
 */
export function useTrackerIssueSearch(
  projectId: string,
  bindingId: string | undefined,
  q: string,
): UseQueryResult<TrackerIssueSearch> {
  return useQuery({
    queryKey: keys.trackerIssues(projectId, bindingId ?? '', q),
    queryFn: ({ signal }) =>
      api.get<TrackerIssueSearch>(
        `${tracker(projectId)}/issues${qs({ q, bindingId })}`,
        { signal, timeoutMs: TIMEOUTS.typeahead },
      ),
    // No `bindingId` gate, unlike the lookup below: the parameter is optional on this endpoint and
    // an omitted one searches the project's **default** binding, which is what a link dialog wants
    // — the person picking an issue has no reason to know a binding exists. The result carries the
    // binding it came from, which is what makes the link that follows well-formed.
    enabled: !!projectId && q.trim().length > 0,
    // The answer is one person's live view of somebody else's server. Holding it is the thing D15
    // forbids at the server's cache and there is no reason to do it here either.
    staleTime: 0,
    meta: { handled: true },
  });
}

/**
 * One issue, live (004 D17).
 *
 * **`bindingId` is part of the question, not an optimisation.** Identity is `(provider, host, id)`:
 * two bindings to different hosts holding the same numeric id are two different issues, and a read
 * keyed on the id alone would show one under the other's name.
 *
 * `enabled` is false without a binding, which is the D18 state — the binding was deleted, the link
 * still renders, and there is nothing to preview it through. The chip's fallback is not a failure
 * and this hook must not fire to discover that.
 */
export function useTrackerIssue(
  projectId: string,
  bindingId: string | null,
  issueId: string,
): UseQueryResult<TrackerIssueLookup> {
  return useQuery({
    queryKey: keys.trackerIssue(projectId, bindingId ?? '', issueId),
    queryFn: ({ signal }) =>
      api.get<TrackerIssueLookup>(
        `${tracker(projectId)}/issues/${encodeURIComponent(issueId)}${qs({ bindingId })}`,
        { signal, timeoutMs: TIMEOUTS.interactive },
      ),
    enabled: !!projectId && !!bindingId && !!issueId,
    staleTime: 0,
    meta: { handled: true },
  });
}

/**
 * Files a new issue, **as the caller** (004 Phase 3).
 *
 * `Idempotency-Key`, unlike every other create in this module, and for the reason the header
 * exists: the request reaches somebody else's server, so a timeout here genuinely may have created
 * the issue, and retrying without a key is how a failed step ends up with two defects filed against
 * it. One key per intent, generated by the panel when it opens, not per attempt.
 *
 * The `description` is composed **client-side** — the case key, the step, the verdict, the run, the
 * environment and a permalink back. That is the contract's decision: what a defect report says is a
 * product decision this SPA makes visibly, rather than a template buried in an adapter.
 */
export function useCreateTrackerIssue(
  projectId: string,
): UseMutationResult<TrackerIssueCreated, Error, TrackerIssueCreateInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: TrackerIssueCreateInput) =>
      api.post<TrackerIssueCreated>(`${tracker(projectId)}/issues`, input, {
        idempotencyKey: newIntentKey(),
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: (_created, input) => {
      // The ledger row and the defect link the follow-up writes both hang off the run, and the
      // outbox backstop may write the link instead — so the run is refreshed either way.
      if (input.executionId) void client.invalidateQueries({ queryKey: keys.runsRoot(projectId) });
    },
  });
}

// ------------------------------------------------------------------ issue links ----

/**
 * A case's links out to tracker issues.
 *
 * **Ungated and never degraded.** These rows are ours: they live in `doc_link`, they are served
 * from our database, and they keep rendering when the tracker is down, when the token expired and
 * when the binding was deleted. Only what a chip can *say* about one depends on the tracker.
 */
export function useIssueLinks(
  projectId: string,
  caseId: string | undefined,
): UseQueryResult<IssueLink[]> {
  return useQuery({
    queryKey: keys.issueLinks(projectId, caseId ?? ''),
    queryFn: ({ signal }) =>
      api.get<IssueLink[]>(`${base(projectId)}/cases/${caseId!}/issue-links`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: !!projectId && !!caseId,
  });
}

/** Links an issue to a case (`case:edit`). The id is stored; the key never is (004 D17). */
export function useCreateIssueLink(
  projectId: string,
  caseId: string,
): UseMutationResult<IssueLink, Error, IssueLinkInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: IssueLinkInput) =>
      api.post<IssueLink>(`${base(projectId)}/cases/${caseId}/issue-links`, input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.issueLinks(projectId, caseId) });
      // The link participates in `SpecLinkage`'s predicate (D11/D13), which the case's own health
      // signals are derived from — so the case document is stale the moment a link lands.
      void client.invalidateQueries({ queryKey: keys.case(projectId, caseId) });
    },
  });
}

/** Removes one link. The issue in the tracker is untouched — nothing here reaches out. */
export function useDeleteIssueLink(
  projectId: string,
  caseId: string,
): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (linkId: string) =>
      api.del<void>(`${base(projectId)}/cases/${caseId}/issue-links/${linkId}`),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.issueLinks(projectId, caseId) });
      void client.invalidateQueries({ queryKey: keys.case(projectId, caseId) });
    },
  });
}
