/** The project list, one project, the two per-project vocabularies, and creating one. */
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { api } from '../client';
import type {
  CaseStatus,
  ClassificationDimension,
  ClassificationValue,
  ClassificationValueInput,
  Project,
  ProjectInput,
  UserRef,
} from '../types';
import { base, keys } from './keys';

export function useProjects(): UseQueryResult<Project[]> {
  return useQuery({
    queryKey: keys.projects,
    queryFn: ({ signal }) => api.get<Project[]>('/projects', { signal }),
    // The project list changes rarely and is read on every screen via the rail.
    staleTime: 5 * 60_000,
  });
}

/**
 * Creates a project — the one mutation in the app whose permission is company-scope.
 *
 * Three things here are decisions:
 *
 *   - **`meta: { handled: true }`.** The dialog puts field errors on its own inputs (`key` is the
 *     one that fails, and it fails for a reason the user can act on) and the 403 in its own alert.
 *     A global toast would be a second, vaguer copy of a message already on screen.
 *   - **The whole project list is invalidated, not appended to.** `caseCount` and `myRole` are
 *     computed per caller by the server, so a locally-assembled row would be the client's guess at
 *     two fields it cannot compute. The list is one small request and it is already cached for
 *     five minutes.
 *   - **No `Idempotency-Key`.** Deliberate, and the opposite of `useCreateCase`: the key the user
 *     types *is* the idempotency key, because `(company, key)` is unique — a retry of a create
 *     that in fact succeeded cannot make a second project, it is refused on the field. Sending a
 *     header the server does not read is how the mock came to prove a property live mode lacked.
 */
export function useCreateProject(): UseMutationResult<Project, Error, ProjectInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ProjectInput) => api.post<Project>('/projects', input),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.projects }),
  });
}

export function useProject(projectId: string | undefined): UseQueryResult<Project> {
  return useQuery({
    queryKey: keys.project(projectId ?? ''),
    queryFn: ({ signal }) => api.get<Project>(base(projectId!), { signal }),
    enabled: !!projectId,
    staleTime: 5 * 60_000,
  });
}

/**
 * The people who can be given work in this project.
 *
 * The server defines this as *active company members holding a grant on the project*, and
 * validates `assigneeId` on `POST /work-items` against the same predicate — one implementation,
 * two callers. That is what makes a picker built from this list honest: it cannot offer somebody
 * the create will refuse, which would be a form the user fills in correctly and cannot submit.
 *
 * **`enabled` rather than an unconditional fetch.** The only consumer is a dialog, and a board
 * nobody is adding a task to should not be asking who could be assigned one. The gate is a
 * parameter rather than being derived from `projectId`, because the dialog's open state is the
 * thing that decides it.
 *
 * `staleTime` matches the other per-project vocabularies: staffing changes on the timescale of
 * onboarding, not of a page view, and a picker that refetches every time it opens flashes an
 * empty select for no reason.
 */
export function useProjectMembers(
  projectId: string,
  enabled: boolean,
): UseQueryResult<UserRef[]> {
  return useQuery({
    queryKey: keys.projectMembers(projectId),
    queryFn: ({ signal }) => api.get<UserRef[]>(`${base(projectId)}/members`, { signal }),
    enabled: enabled && !!projectId,
    staleTime: 5 * 60_000,
  });
}

export function useClassification(projectId: string): UseQueryResult<ClassificationDimension[]> {
  return useQuery({
    queryKey: keys.classification(projectId),
    queryFn: ({ signal }) => api.get<ClassificationDimension[]>(`${base(projectId)}/classification`, { signal }),
    staleTime: 5 * 60_000,
  });
}

/**
 * The vocabulary's three writes — add a value, rename or recolour one, retire one.
 *
 * All three invalidate the same key and nothing else, because `GET /classification` is the *only*
 * reader of these rows in the app: the case editor's picker, the case list's facets and the
 * dashboard's by-feature breakdown all render from it. Assembling the new value into the cache by
 * hand would be the client's guess at `path`, which the server derives — and a picker built on a
 * guessed path is one that disagrees with the next reload.
 *
 * **`meta: { handled: true }`** on all three: the editor puts field errors on its own inputs and
 * the 403 in its own alert, so a global toast would be a second, vaguer copy of a message already
 * on screen. Same decision, same reason, as `useCreateProject` above.
 *
 * **No optimistic update on the retire**, deliberately. It is refused when the value still has
 * live children, and an optimistic removal would take the row off screen and then put it back with
 * an error beside it — for the one action whose failure the user most needs to read.
 */
export function useCreateClassificationValue(
  projectId: string,
): UseMutationResult<ClassificationValue, Error, ClassificationValueInput> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: ClassificationValueInput) =>
      api.post<ClassificationValue>(`${base(projectId)}/classification/values`, input),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.classification(projectId) }),
  });
}

export function useUpdateClassificationValue(
  projectId: string,
): UseMutationResult<ClassificationValue, Error, { valueId: string; input: ClassificationValueInput }> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ valueId, input }) =>
      api.put<ClassificationValue>(`${base(projectId)}/classification/values/${valueId}`, input),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.classification(projectId) }),
  });
}

export function useRetireClassificationValue(
  projectId: string,
): UseMutationResult<void, Error, string> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (valueId: string) =>
      api.del<void>(`${base(projectId)}/classification/values/${valueId}`),
    meta: { handled: true },
    onSuccess: () => client.invalidateQueries({ queryKey: keys.classification(projectId) }),
  });
}

export function useStatuses(projectId: string): UseQueryResult<CaseStatus[]> {
  return useQuery({
    queryKey: keys.statuses(projectId),
    queryFn: ({ signal }) => api.get<CaseStatus[]>(`${base(projectId)}/statuses`, { signal }),
    staleTime: 5 * 60_000,
  });
}
