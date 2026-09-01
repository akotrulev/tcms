/**
 * Releases, readiness, the roadmap and the QA link (013 M3).
 *
 * ## Every write invalidates the list **and** the roadmap
 *
 * They are two endpoints over the same rows — `releases.html`'s table and `work-timeline.html`'s
 * bars — so a create that refreshed only the list leaves the timeline a version behind, on a screen
 * whose whole subject is what ships when. {@link invalidateReleaseReaders} is the one place that
 * pair is spelled, for `invalidateCaseLists`' reason: two call sites is how one of them gets
 * updated and the other does not.
 *
 * ## The QA link lives here rather than in `issues.ts`
 *
 * `useIssueTestCases`, `usePutIssueTestCases` and `useCaseVerifications` read and write
 * `issue_link` rows with a target of `TEST_CASE` — which is what readiness is computed over. The
 * file that owns readiness owns them, so a change to what "linked" means cannot land in one module
 * and be read from another.
 *
 * `PUT /issues/{key}/test-cases` is **the only writer of that edge** (D-p), so without it every
 * release in every instance reads `NOT_TESTED` forever.
 *
 * ## Nothing here is optimistic
 *
 * A release write has no legality the client can predict and no drag to keep up with — the two
 * conditions `projectBoards.ts`' header uses to earn the one optimistic mutation in the product.
 */
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api } from '../client';
import type {
  CaseVerification,
  IssueTestCase,
  IssueTestCasesInput,
  Release,
  ReleaseInput,
  ReleaseReadiness,
  ReleaseSummary,
  ReleaseUpdate,
  Roadmap,
} from '../types';
import { keys } from './keys';

/**
 * Every release in the project, in version order, each carrying its readiness inline.
 *
 * **An empty array is the ordinary state of a fresh instance**, not an error: nothing is migrated
 * into releases when M3 ships (D14), so the first row is one a person typed. The screen says so.
 */
export function useReleases(projectId: string): UseQueryResult<ReleaseSummary[]> {
  return useQuery({
    queryKey: keys.releases(projectId),
    queryFn: ({ signal }) =>
      api.get<ReleaseSummary[]>(`/projects/${projectId}/releases`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/** One release, **without** its readiness — that is {@link useReadiness}, a second endpoint. */
export function useRelease(releaseId: string): UseQueryResult<Release> {
  return useQuery({
    queryKey: keys.release(releaseId),
    queryFn: ({ signal }) =>
      api.get<Release>(`/releases/${releaseId}`, { signal, timeoutMs: TIMEOUTS.interactive }),
    enabled: releaseId !== '',
  });
}

/**
 * Can we ship this version — the whole of `release.html`.
 *
 * A read of its own rather than a field on the release, because the answer is over two halves and
 * one of them is null-bearing: `tests.hasRun` false means every count under it is `null` rather
 * than `0`, and a screen that read a zero would draw an untested version as a clean one.
 */
export function useReadiness(releaseId: string): UseQueryResult<ReleaseReadiness> {
  return useQuery({
    queryKey: keys.readiness(releaseId),
    queryFn: ({ signal }) =>
      api.get<ReleaseReadiness>(`/releases/${releaseId}/readiness`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: releaseId !== '',
  });
}

/** Releases in version order with their epics inside them, and the unscheduled lane (D-o). */
export function useRoadmap(projectId: string): UseQueryResult<Roadmap> {
  return useQuery({
    queryKey: keys.roadmap(projectId),
    queryFn: ({ signal }) =>
      api.get<Roadmap>(`/projects/${projectId}/roadmap`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * Create a release — `release:manage`.
 *
 * `meta.handled` because the 409 on a duplicate version lands on the version field as the server's
 * own sentence, which is strictly more useful than the global handler's "couldn't save".
 */
export function useCreateRelease(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ReleaseInput) =>
      api.post<Release>(`/projects/${projectId}/releases`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateReleaseReaders(client, projectId),
  });
}

/**
 * Edit a version, a date or a description — `release:manage`, and **never the status** (D-m).
 *
 * Renaming the version recomputes the server's ordering key, so an edit from `1.9` to `1.10` moves
 * the row: both list readers are invalidated rather than patched in place.
 */
export function useUpdateRelease(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ releaseId, input }: { releaseId: string; input: ReleaseUpdate }) =>
      api.patch<Release>(`/releases/${releaseId}`, input, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: (release) => {
      invalidateReleaseReaders(client, projectId);
      void client.invalidateQueries({ queryKey: keys.release(release.id) });
    },
  });
}

/**
 * Delete a release — `release:manage`.
 *
 * **The issues pointing at it keep existing and lose their target release**: the foreign key is
 * `ON DELETE SET NULL`, so they return to the backlog's Unscheduled section. That is why the issue
 * lists go too — a story's release chip is on a screen this write never touched.
 */
export function useDeleteRelease(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (releaseId: string) =>
      api.del<void>(`/releases/${releaseId}`, { timeoutMs: TIMEOUTS.interactive }),
    onSuccess: () => {
      invalidateReleaseReaders(client, projectId);
      void client.invalidateQueries({ queryKey: keys.issuesRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.backlogRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.boardCardsRoot });
    },
  });
}

/**
 * Mark a release released — `release:publish`, a key `release:manage` does not imply (D-m).
 *
 * **Idempotent, and readiness does not gate it.** A team may ship a version with a known failure
 * and record that they did; a client that refused would be asserting a release policy this product
 * has no way to know. The confirmation is on the screen, not here.
 */
export function usePublishRelease(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (releaseId: string) =>
      api.post<Release>(`/releases/${releaseId}/publish`, {}, { timeoutMs: TIMEOUTS.interactive }),
    onSuccess: (release) => {
      invalidateReleaseReaders(client, projectId);
      void client.invalidateQueries({ queryKey: keys.release(release.id) });
    },
  });
}

/**
 * The cases that prove one story (H7), each with the latest result it has **anywhere**.
 *
 * Deliberately not scoped to a release: this asks *does this case pass*, and readiness asks *did it
 * pass for the version we are shipping*. The two are different questions and this is the one a
 * story's own panel is about.
 */
export function useIssueTestCases(key: string): UseQueryResult<IssueTestCase[]> {
  return useQuery({
    queryKey: keys.issueTestCases(key),
    queryFn: ({ signal }) =>
      api.get<IssueTestCase[]>(`/issues/${encodeURIComponent(key)}/test-cases`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: key !== '',
  });
}

/**
 * Replace the cases a story is proven by — `issue:edit`, because linking a case edits the story.
 *
 * A `PUT` of the whole set rather than add/remove pairs, for `usePutIssueLabels`' reason: the
 * control is a multi-select whose state *is* the set, and pairs let a stale client unlink a case
 * somebody else had just linked.
 *
 * It invalidates **readiness and the release list** as well as the story: what readiness counts is
 * these rows, so a link written on a story's own screen changes the verdict on a release screen
 * nobody navigated away from.
 */
export function usePutIssueTestCases(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, input }: { key: string; input: IssueTestCasesInput }) =>
      api.put<IssueTestCase[]>(`/issues/${encodeURIComponent(key)}/test-cases`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    onSuccess: (_cases, { key }) => {
      void client.invalidateQueries({ queryKey: keys.issueTestCases(key) });
      void client.invalidateQueries({ queryKey: keys.caseVerifications(projectId) });
      invalidateReleaseReaders(client, projectId);
      void client.invalidateQueries({ queryKey: keys.releaseRoot });
    },
  });
}

/**
 * Which stories each case verifies — the reverse direction of the same fact (D-i).
 *
 * **Only cases with at least one link appear**, so the payload is proportional to the links and not
 * to the catalogue; a caller merges it into its own grid and renders an em dash for a missing key.
 */
export function useCaseVerifications(projectId: string): UseQueryResult<CaseVerification[]> {
  return useQuery({
    queryKey: keys.caseVerifications(projectId),
    queryFn: ({ signal }) =>
      api.get<CaseVerification[]>(`/projects/${projectId}/case-verifications`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * The list and the roadmap, together, because they are two renderings of the same rows.
 *
 * Spelled once here rather than at seven call sites — the failure mode of the second copy is that
 * one write refreshes the table and leaves the timeline showing a release that no longer exists.
 *
 * **Exported since 013 T6, for one caller outside this file.** `useBulkUpdateIssues` in `issues.ts`
 * is the backlog's `Move to release`, and a story changing which version it targets moves the
 * readiness of the release it left as well as the one it joined — so that mutation sweeps
 * {@link keys.releaseRoot} and calls this. Exported rather than copied for the reason it exists at
 * all: a second spelling of the pair is how one of the two stops being invalidated.
 */
export function invalidateReleaseReaders(
  client: ReturnType<typeof useQueryClient>,
  projectId: string,
) {
  void client.invalidateQueries({ queryKey: keys.releases(projectId) });
  void client.invalidateQueries({ queryKey: keys.roadmap(projectId) });
}
