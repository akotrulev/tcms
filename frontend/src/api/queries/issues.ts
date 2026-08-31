/** The project's issues: the work table, one issue, its comments, its spec links, and every write. */
import {
  useMutation,
  useQueries,
  useQuery,
  useQueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import { useRef } from 'react';
import { TIMEOUTS, api, newIntentKey, qs } from '../client';
import type {
  Issue,
  IssueComment,
  IssueCommentInput,
  IssueCompletion,
  IssueDocLink,
  IssueFilters,
  IssueInput,
  IssueSummary,
  IssueUpdate,
  ProjectWorkflow,
  UserRef,
} from '../types';
import { keys } from './keys';

/**
 * The columns the work table may be ordered by — the contract's own enum, not a free string.
 *
 * `key` sorts on `seq` server-side and never on the key text: `ACME-100` sorts before `ACME-2`
 * as a string, which is the one ordering a reader would call broken on sight.
 */
export type IssueSort = 'key' | 'title' | 'status' | 'dueDate' | 'estimateHours';

/**
 * A project's issues.
 *
 * The whole filter object and the sort are in the key for `useCases`' reason: two filters are two
 * caches, and a slow answer for one chip set must not land under another's.
 *
 * `qs` spells a repeated parameter for an array, which is what `statusId` and `assigneeId` are on
 * the wire, and drops an `undefined` — so an absent filter never reaches the server as an empty
 * one. `unparented` is the exception worth naming: it is a *flag*, not `epicKey=null`, because an
 * absent parameter already means "don't filter" and a client cannot spell the difference.
 */
export function useIssues(
  projectId: string,
  filters: IssueFilters,
  sort: IssueSort,
): UseQueryResult<IssueSummary[]> {
  return useQuery({
    queryKey: keys.issues(projectId, filters, sort),
    queryFn: ({ signal }) =>
      api.get<IssueSummary[]>(`/issues${qs({ projectId, ...filters, sort })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * One issue, by KEY.
 *
 * The key is what the route carries and what a person types into a commit message, so there is no
 * id to gate on — but there is still a gate, because a route param can be empty for a render.
 */
export function useIssue(key: string): UseQueryResult<Issue> {
  return useQuery({ ...issueQueryOptions(key), enabled: key !== '' });
}

/**
 * One issue's read, as options — shared by {@link useIssue} and {@link useEpicCompletions} so the
 * overview's meters and the epic's own page are one cache entry rather than two.
 */
function issueQueryOptions(key: string) {
  return {
    queryKey: keys.issue(key),
    queryFn: ({ signal }: { signal: AbortSignal }) =>
      api.get<Issue>(`/issues/${encodeURIComponent(key)}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
  };
}

/**
 * How many epics the overview will fetch a completion for.
 *
 * `IssueSummary` carries no completion — it is epics-only and computed server-side (D8) — so the
 * only way to a meter per epic is one `GET /issues/{key}` each. That is `useExecutionSteps`' shape
 * and its judgement call: the number matters less than the screen **saying** when it has been
 * reached, and a project with forty epics is a project whose overview question is not "what is
 * each one's percentage".
 */
export const EPIC_COMPLETION_LIMIT = 12;

export interface EpicCompletionsState {
  data: Record<string, IssueCompletion | null> | undefined;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  isFetching: boolean;
  refetch: () => void;
}

/**
 * The completion of each epic named, keyed by issue key — one read each, combined into one state.
 *
 * **Combined pessimistically**, as `useExecutionSteps` is: the reader's question is about the
 * project, so a half-arrived answer rendered as a list of meters is a set of percentages that is
 * incomplete and looks complete. A partial failure is a *shorter* list with nothing on screen
 * saying so, which is the failure `DataRegion` exists to make impossible.
 *
 * A `null` value is an epic whose read arrived with no completion on it. That is not a zero and
 * must never render as one: `Issue.completion` is nullable on the wire, and a meter reading 0%
 * where the server sent nothing is D8's "percentage of nothing" one screen over.
 */
export function useEpicCompletions(epicKeys: readonly string[]): EpicCompletionsState {
  const capped = epicKeys.slice(0, EPIC_COMPLETION_LIMIT);
  return useQueries({
    queries: capped.map((key) => issueQueryOptions(key)),
    combine: (results): EpicCompletionsState => {
      const failed = results.find((result) => result.isError);
      const loaded = results.every((result) => result.data !== undefined);
      return {
        data: loaded
          ? Object.fromEntries(
              capped.map((key, index) => [key, results[index].data?.completion ?? null]),
            )
          : undefined,
        // `useQueries` over an empty list answers `[]`, and `[].some()` is false — so a project
        // with no epics is "loaded with nothing", never pending for ever.
        isPending: results.some((result) => result.isPending),
        isError: failed !== undefined,
        error: failed?.error,
        isFetching: results.some((result) => result.isFetching),
        refetch: () => results.forEach((result) => void result.refetch()),
      };
    },
  });
}

export function useIssueComments(key: string): UseQueryResult<IssueComment[]> {
  return useQuery({
    queryKey: keys.issueComments(key),
    queryFn: ({ signal }) =>
      api.get<IssueComment[]>(`/issues/${encodeURIComponent(key)}/comments`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: key !== '',
  });
}

/**
 * The specification blocks this issue links to — `tracker`'s projection, which is what carries
 * `stale`. Read-only here; the write is `documentation`'s block-link POST.
 */
export function useIssueDocLinks(key: string): UseQueryResult<IssueDocLink[]> {
  return useQuery({
    queryKey: keys.issueDocLinks(key),
    queryFn: ({ signal }) =>
      api.get<IssueDocLink[]>(`/issues/${encodeURIComponent(key)}/doc-links`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: key !== '',
  });
}

/**
 * The project's statuses, types and legal moves.
 *
 * Read by every screen that renders a status or a type — including the read-only ones — which is
 * why the endpoint costs `issue:read` rather than `issue:workflow`, and why this is keyed on the
 * project rather than under an issue: it outlives every issue that names it, and an issue-scoped
 * key would be refetched by every issue write.
 */
export function useProjectWorkflow(projectId: string): UseQueryResult<ProjectWorkflow> {
  return useQuery({
    queryKey: keys.workflow(projectId),
    queryFn: ({ signal }) =>
      api.get<ProjectWorkflow>(`/projects/${projectId}/workflow`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * Creating an issue.
 *
 * One idempotency key per mounted form, which is per user intent to add *this* issue: it survives
 * re-renders and failed attempts, so pressing Create again after a timeout replays rather than
 * making a second issue. That only holds because the form is a route rather than a dialog left
 * mounted behind `open={false}` — a permanently mounted form holds one key for the life of the
 * page and the second issue somebody types comes back as a replay of the first.
 *
 * **The key is allocated server-side and is never a field on the request**, so a create's answer
 * is the only place the caller learns what to navigate to.
 */
export function useCreateIssue(projectId: string) {
  const client = useQueryClient();
  const intentKey = useRef(newIntentKey());
  return useMutation({
    mutationFn: (input: Omit<IssueInput, 'projectId'>) =>
      api.post<Issue>(
        '/issues',
        { ...input, projectId },
        { idempotencyKey: intentKey.current, timeoutMs: TIMEOUTS.interactive },
      ),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.issuesRoot(projectId) });
      // A new story under an epic changes that epic's completion, which is a different cache
      // entry on a different screen. D8's "one number, one place" is a server-side rule; the
      // client half of it is that the number cannot outlive the row that produced it.
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
    },
  });
}

/**
 * Editing an issue's fields — **never its status.**
 *
 * A move goes through {@link useTransitionIssue}, which is the one path the workflow's legal-move
 * set is checked on. A PATCH that could set a status would be a second, unchecked spelling of the
 * one rule this module exists to enforce, and the contract has no `status` property here to send.
 */
export function useUpdateIssue(key: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: IssueUpdate) =>
      api.patch<Issue>(`/issues/${encodeURIComponent(key)}`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    onSuccess: (issue) => {
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.issuesRoot(issue.projectId) });
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
    },
  });
}

/**
 * Moving an issue.
 *
 * Invalidates THREE things, and the third is the one that is easy to miss: the issue itself, the
 * project's issue lists, and `keys.issueRoot` — because a move changes the PARENT epic's
 * completion, and the epic is a different cache entry on a different screen. D8's "one number,
 * one place" is a server-side rule; on the client the equivalent is that the number's cache
 * cannot outlive the status that produced it. The boards are the fourth, for the same reason a
 * board write has always swept them: one card is on three of them.
 *
 * **Not optimistic**, and that is a decision rather than an omission. A transition can be refused
 * by the workflow, and an optimistic move would show the card in a column the server is about to
 * reject — which is precisely the "snapping back in silence" story H5 rules out. ~~The board's
 * drag was optimistic under `useMoveWorkItem`.~~ → M2 rebuilds the board and gets the optimism
 * back there, where a *drag* makes the machinery worth its rollback; a menu press does not.
 *
 * `meta.handled` for the reason the board's move hook always carried it: the screen says which
 * move was refused and what is legal instead, which is strictly more useful than "couldn't save",
 * so the global notification handler stays out of it.
 */
export function useTransitionIssue() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ key, statusId }: { key: string; statusId: string }) =>
      api.post<Issue>(
        `/issues/${encodeURIComponent(key)}/transitions`,
        { statusId },
        { timeoutMs: TIMEOUTS.interactive },
      ),
    meta: { handled: true },
    onSuccess: (issue) => {
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.issuesRoot(issue.projectId) });
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
    },
  });
}

/**
 * Deleting an issue.
 *
 * Takes the project as well as the key, and needs it: the delete answers 204 with no body, so
 * there is nothing to read the project id back out of, and the list invalidation is project-keyed.
 */
export function useDeleteIssue(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (key: string) =>
      api.del<void>(`/issues/${encodeURIComponent(key)}`, { timeoutMs: TIMEOUTS.interactive }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.issuesRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
    },
  });
}

/**
 * Commenting.
 *
 * Invalidates the issue as well as its comments, because commenting **adds the author as a
 * watcher** (story H8) — the watchers chip row is on the issue body, not on the comment list, so
 * a comment that refreshed only the feed would leave the reader off a list they had just joined.
 */
export function useCreateIssueComment(key: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: IssueCommentInput) =>
      api.post<IssueComment>(`/issues/${encodeURIComponent(key)}/comments`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.issueComments(key) });
      void client.invalidateQueries({ queryKey: keys.issue(key) });
    },
  });
}

/**
 * Watching, or not.
 *
 * A PUT with a boolean rather than a POST and a DELETE, because the star on the screen has two
 * states and the request says which one the person wants — not which one they think it is in. It
 * is therefore idempotent in both directions, and a double-press cannot toggle twice.
 */
export function useSetIssueWatch(key: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (watching: boolean) =>
      api.put<UserRef[]>(
        `/issues/${encodeURIComponent(key)}/watchers`,
        { watching },
        { timeoutMs: TIMEOUTS.interactive },
      ),
    onSuccess: () => client.invalidateQueries({ queryKey: keys.issue(key) }),
  });
}

/**
 * Replacing the project's workflow — story B1's add, rename, reorder and re-kind, in one write.
 *
 * **The whole document, because reordering is a statement about the set.** A sequence of
 * per-status writes has an intermediate state in which two statuses claim one position, and that
 * is precisely what the settings screen's ↑ / ↓ buttons generate. The contract and
 * `WorkflowService` both make this argument; this hook is the third place it has to hold, because
 * a client that sent one status at a time would produce the broken intermediate the endpoint was
 * shaped to avoid.
 *
 * `meta.handled` for `useTransitionIssue`'s reason: the two 409s this can answer are sentences
 * worth reading — *"4 issues are in Blocked"* and *"Keep one status that means done"* — and the
 * screen renders them beside the control that caused them. The global notification handler would
 * replace both with "couldn't save".
 *
 * Sweeps the boards as well as the workflow: a renamed status is the name on every card, and
 * `columnsFor` builds the board's columns from the cards it was handed.
 */
export function useReplaceWorkflow(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (workflow: ProjectWorkflow) =>
      api.put<ProjectWorkflow>(`/projects/${projectId}/workflow`, workflow, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: (workflow) => {
      client.setQueryData(keys.workflow(projectId), workflow);
      void client.invalidateQueries({ queryKey: keys.issuesRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
    },
  });
}

/**
 * Removing one status, naming where its issues go.
 *
 * A DELETE with a body, which is the one in this product — the destination is a destructive
 * decision and a query parameter makes one look like a filter (the contract's own words). It is
 * therefore `api.delWithBody` rather than `api.del`, so `contractDoubles` compares the payload.
 *
 * **Not the same operation as a status dropped from {@link useReplaceWorkflow}'s document.** That
 * PUT refuses a removal it was given no destination for, with a 409 naming the count; this is the
 * removal that answers the question, and it moves the issues in the same transaction so no issue
 * is ever pointing at a status that is gone.
 */
export function useRemoveWorkflowStatus(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ statusId, reassignToStatusId }: { statusId: string; reassignToStatusId: string }) =>
      api.delWithBody<void>(
        `/projects/${projectId}/workflow/statuses/${statusId}`,
        { reassignToStatusId },
        { timeoutMs: TIMEOUTS.interactive },
      ),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.workflow(projectId) });
      // Every issue that held the removed status now holds another one, so the lists, the detail
      // reads and all three boards are stale — this is the one write in the module that changes a
      // status without a transition.
      void client.invalidateQueries({ queryKey: keys.issuesRoot(projectId) });
      void client.invalidateQueries({ queryKey: keys.issueRoot });
      void client.invalidateQueries({ queryKey: keys.workItemRoot });
    },
  });
}
