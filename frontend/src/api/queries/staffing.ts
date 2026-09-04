/**
 * Staffing: the company capacity view, the skills directory, one project's allocations, and the
 * caller's own capacity.
 *
 * <h2>Two scopes, and the split is the first thing to read</h2>
 *
 * Everything under `/staffing` except `capacity/me` is a **COMPANY**-scope question behind
 * `staffing:directory` / `staffing:profile` / `staffing:absence`; everything under
 * `/projects/{projectId}` is a **PROJECT**-scope one behind `staffing:read` / `staffing:allocate`.
 * A Project Manager holds the project pair and neither company key, so `useStaffingCapacity` and
 * `useStaffingPeople` 403 for them by design and `useProjectStaffing` is the read they get.
 *
 * <h2>`useMyCapacity` is the one read here with no staffing permission on it (017 D-m)</h2>
 *
 * It is the caller's own figure, and it is what the timesheet's fourth KPI tile and My Work's
 * meter are denominated over. It takes the **period's** bounds, not a week's (016 D-a): the
 * fortnight is semi-monthly and nothing configures it, so a week is a range the product does not
 * have.
 *
 * <h2>Every write sweeps two roots, and neither covers the other</h2>
 *
 * `keys.staffingRoot` is `['staffing']`; a project's own staffing is keyed under
 * `['projects', p, 'staffing', …]` so that a project-level invalidation reaches it. One allocation
 * changes both — the project screen it was written on *and* the company view, the directory, the
 * person's own tile and the over-allocation panel — so {@link invalidateStaffing} sweeps both and
 * is the only thing any write here calls.
 */
import { useMutation, useQuery, useQueryClient, type QueryClient, type UseQueryResult } from '@tanstack/react-query';
import { TIMEOUTS, api, qs } from '../client';
import type {
  Absence,
  AbsenceInput,
  Allocation,
  AllocationInput,
  AllocationPatch,
  DirectoryFilters,
  PersonCapacity,
  PersonProfile,
  PersonProfileInput,
  PersonSkillsInput,
  ProjectStaffingView,
  SelfCapacity,
  Skill,
  SkillInput,
  StaffingCapacityView,
} from '../types';
import { keys } from './keys';

/**
 * Every list a staffing write must refresh, in one place.
 *
 * Two roots because they are two subtrees and neither is a prefix of the other — see this module's
 * header and `keys.staffingRoot`'s own. A `projectId` is passed where the write named one, and
 * omitted where it did not: a profile or an absence changes *every* project's staffing, so the
 * whole `['projects']` prefix would be the honest sweep and is far too wide. The company reads it
 * actually changes are all under `staffingRoot`, and a project screen open at the time picks the
 * change up on its next mount.
 */
function invalidateStaffing(client: QueryClient, projectId?: string) {
  void client.invalidateQueries({ queryKey: keys.staffingRoot });
  if (projectId) void client.invalidateQueries({ queryKey: keys.project(projectId) });
}

/**
 * `staffing.html` — everybody's allocation over a window, plus the over-allocated subset.
 *
 * `from` and `to` are passed through **empty** when the range picker has not been touched, so the
 * server applies its own default (the first of this month, three months on) and the screen reads
 * the window out of the answer. Deriving that default here would give the product two spellings of
 * "next three months", and the subtitle would then be able to disagree with the timeline under it.
 */
export function useStaffingCapacity(from: string, to: string): UseQueryResult<StaffingCapacityView> {
  return useQuery({
    queryKey: keys.staffing.capacity(from, to),
    queryFn: ({ signal }) =>
      api.get<StaffingCapacityView>(`/staffing/capacity${qs({ from, to })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
  });
}

/**
 * The caller's own capacity over a window — no staffing permission, because it is about them.
 *
 * `enabled` on both bounds rather than on one: the contract makes them **required** here (unlike
 * every other window in this module), so a call with one of them missing is a 400 rather than a
 * defaulted answer.
 */
export function useMyCapacity(from: string, to: string): UseQueryResult<SelfCapacity> {
  return useQuery({
    queryKey: keys.staffing.myCapacity(from, to),
    queryFn: ({ signal }) =>
      api.get<SelfCapacity>(`/staffing/capacity/me${qs({ from, to })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: from !== '' && to !== '',
  });
}

/**
 * `directory.html` — who has which skills and when they are free.
 *
 * The filters are the key, not just the query string, which is what makes the URL-held chips
 * cacheable and the answer shareable. **The match count is served rather than counted here**: the
 * screen says *"3 of 11 people match"*, and the 11 is the company's whole membership, which a
 * filtered array cannot report.
 */
export function useStaffingPeople(filters: DirectoryFilters): UseQueryResult<PersonCapacity[]> {
  return useQuery({
    queryKey: keys.staffing.people(filters),
    queryFn: ({ signal }) =>
      api.get<PersonCapacity[]>(`/staffing/people${qs({ ...filters })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
  });
}

/** One person — `staffing:directory`, **or** the caller is that person (the contract's own arm). */
export function useStaffingPerson(
  userId: string,
  from: string,
  to: string,
): UseQueryResult<PersonCapacity> {
  return useQuery({
    queryKey: keys.staffing.person(userId, from, to),
    queryFn: ({ signal }) =>
      api.get<PersonCapacity>(`/staffing/people/${userId}${qs({ from, to })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: userId !== '',
  });
}

/**
 * The company's skill vocabulary — what the directory's skill filter is built from.
 *
 * Reference data that outlives every screen reading it, so it is fetched once and left alone; it
 * changes only when somebody adds a skill, and {@link useCreateSkill} sweeps the root.
 */
export function useSkills(): UseQueryResult<Skill[]> {
  return useQuery({
    queryKey: keys.staffing.skills,
    queryFn: ({ signal }) =>
      api.get<Skill[]>('/staffing/skills', { signal, timeoutMs: TIMEOUTS.interactive }),
  });
}

/** Somebody's absences in a window — anybody else's needs `staffing:directory`; your own never does. */
export function useAbsences(userId: string, from: string, to: string): UseQueryResult<Absence[]> {
  return useQuery({
    queryKey: keys.staffing.absences(userId, from, to),
    queryFn: ({ signal }) =>
      api.get<Absence[]>(`/staffing/people/${userId}/absences${qs({ from, to })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: userId !== '',
  });
}

/**
 * Who is on **this** project and at what percentage — `staffing:read`, PROJECT scope.
 *
 * Each person carries their company-wide total and the over-allocation flag and **names no other
 * project** (017 D-f). That is the endpoint's decision, not this hook's, and it is why the project
 * screen shows a number where the company one shows a breakdown.
 */
export function useProjectStaffing(
  projectId: string,
  from: string,
  to: string,
): UseQueryResult<ProjectStaffingView> {
  return useQuery({
    queryKey: keys.staffing.projectStaffing(projectId, from, to),
    queryFn: ({ signal }) =>
      api.get<ProjectStaffingView>(`/projects/${projectId}/staffing${qs({ from, to })}`, {
        signal,
        timeoutMs: TIMEOUTS.interactive,
      }),
    enabled: projectId !== '',
  });
}

/**
 * Allocating a person to a project.
 *
 * **The 409 is the feature, not an error to hide** (017 D-n): two allocations of the same person to
 * the same project over overlapping days are refused rather than merged, because `staffing.html`
 * draws one bar per person per project and an overlap is not a thing the screen can render. The
 * same person on two *different* projects is the ordinary case and is exactly what over-allocation
 * exists to report. `meta.handled` so the dialog words the refusal beside the form rather than
 * letting it scroll away as a toast.
 */
export function useCreateAllocation(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: AllocationInput) =>
      api.post<Allocation>(`/projects/${projectId}/allocations`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client, projectId),
  });
}

/**
 * Changing an allocation's percentage or range — a **true delta**, an absent field left alone.
 *
 * There is deliberately no `If-Match`: `Allocation` carries no `lockVersion` on the wire, so there
 * is nothing to precondition on. What that costs is bounded by the delta — two callers editing one
 * allocation lose only the fields they both named — and it is recorded here rather than left for a
 * reader to discover from a lost edit.
 */
export function useUpdateAllocation(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ allocationId, patch }: { allocationId: string; patch: AllocationPatch }) =>
      api.patch<Allocation>(`/projects/${projectId}/allocations/${allocationId}`, patch, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client, projectId),
  });
}

/** Removing an allocation. A plan somebody abandoned is not a state worth keeping. */
export function useDeleteAllocation(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (allocationId: string) =>
      api.del<void>(`/projects/${projectId}/allocations/${allocationId}`, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client, projectId),
  });
}

/**
 * Recording an absence — **`staffing:absence` unless the person is the caller** (017 D-h).
 *
 * The catalogue text is precise: the key covers *"record absence on somebody else's behalf"*.
 * Recording that you are away on Thursday is not an administrative act, and a product that made it
 * one would be a product where nobody records absence — which would make every capacity figure in
 * this module a lie by omission. The hook takes the person either way; the server decides.
 */
export function useCreateAbsence(userId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: AbsenceInput) =>
      api.post<Absence>(`/staffing/people/${userId}/absences`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client),
  });
}

/** Removing an absence — `useCreateAbsence`'s rule, from the other end. */
export function useDeleteAbsence(userId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (absenceId: string) =>
      api.del<void>(`/staffing/people/${userId}/absences/${absenceId}`, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client),
  });
}

/**
 * Creating or replacing somebody's profile — a `PUT` because a profile is one row per person and
 * the caller has no id to address it by.
 *
 * This is the write that takes a person out of the no-profile state, so it stales every capacity
 * figure in the company at once — which is what the root sweep is for.
 */
export function usePutProfile(userId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: PersonProfileInput) =>
      api.put<PersonProfile>(`/staffing/people/${userId}/profile`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client),
  });
}

/**
 * Replacing somebody's whole skill set — **the payload is the set, not a delta**.
 *
 * A chip list is a set, and a body naming one skill takes the others off. Said here as well as in
 * the contract because a caller reaching for this hook to "add a skill" would send exactly that
 * body and silently clear the rest.
 */
export function usePutPersonSkills(userId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: PersonSkillsInput) =>
      api.put<Skill[]>(`/staffing/people/${userId}/skills`, input, {
        timeoutMs: TIMEOUTS.interactive,
      }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client),
  });
}

/**
 * Adding a skill to the company vocabulary.
 *
 * **No `Idempotency-Key`, and the omission is the parameter's own scope rather than an oversight**:
 * it deduplicates within `(company, project, key)` and a skill is not addressed to a project. The
 * retry it would guard against is already harmless — a repeated name answers `409`, not a second
 * row.
 */
export function useCreateSkill() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: SkillInput) =>
      api.post<Skill>('/staffing/skills', input, { timeoutMs: TIMEOUTS.interactive }),
    meta: { handled: true },
    onSuccess: () => invalidateStaffing(client),
  });
}
