/**
 * What every handler group needs: the base path, the latency, the error shapes, and the two
 * gates (authenticated, permitted).
 *
 * This is the one file the per-domain handler modules all import. Keeping `problem` and
 * `permissionsOn` here rather than duplicating them per group is what stops two handler files
 * refusing the same write in two different sentences.
 */
import { HttpResponse } from 'msw';
import { roles } from '../fixtures/core';
import { db, users } from '../db';

export const API = '/api/v1';

/** A little latency, so loading states are real rather than theoretical. */
export const LATENCY = 220;

/**
 * `Problem` from the contract. `errors` is the RFC-7807 field map the client reads as
 * `ApiError.fieldErrors`, and it is omitted rather than sent empty: a 409 carrying `errors: {}`
 * reads as "no fields were wrong" instead of "fields are not what this is about".
 */
export const problem = (
  status: number,
  title: string,
  detail: string,
  errors?: Record<string, string[]>,
) =>
  HttpResponse.json(
    { type: 'about:blank', title, status, detail, ...(errors ? { errors } : {}) },
    { status },
  );

export const notFound = (what: string) => problem(404, 'Not found', `${what} does not exist.`);

/** The server's own cap on a suite name, mirrored so a rejection reads the same in both modes. */
export const MAX_SUITE_NAME = 150;

/**
 * What the caller may do on one project, from that project's own `myRole`.
 *
 * Shared by `GET /me/permissions` and by the writes that refuse — so the mock cannot enable a
 * control through one answer and then accept the write it should have refused. An unknown project
 * yields nothing, matching the real endpoint: unreachable and non-existent are indistinguishable.
 *
 * Reads the **store**, not the fixture: a project created through `POST /projects` has to be
 * answerable here too, or the screens of a just-created project would all report "that project
 * does not exist" through a permission set nobody could explain.
 */
export function permissionsOn(projectId: string): HeldPermission[] {
  const project = db.projects.find((p) => p.id === projectId);
  const role = roles.find((r) => r.name === project?.myRole);
  return [...(role?.permissions ?? [])]
    .sort()
    .map((key) => ({ key, qualifier: qualifierFor(role?.name, key) }));
}

/** One held key and which rows it covers — the contract's `HeldPermission`. */
export interface HeldPermission {
  key: string;
  qualifier: 'ALL' | 'OWN' | 'PLAN_SCOPE';
}

/**
 * The qualified grants in the seeded catalogue — `role_permission.qualifier`, mirrored.
 *
 * **Everything absent from this table is `ALL`**, which is both the column's default and the truth
 * for the overwhelming majority of grants. Listing only the exceptions is what keeps this in step
 * with the roles spec by inspection rather than by a table nobody re-reads.
 *
 * It has to exist at all for the reason §64 recorded twice: **a mock that agrees with itself is the
 * worst kind.** Answer `ALL` everywhere and mock mode shows a Test Engineer a project-scope option
 * that live mode disables — a difference nobody sees until the deployed box behaves differently
 * from the one the feature was built against.
 */
const QUALIFIED_GRANTS: Record<string, Record<string, 'OWN' | 'PLAN_SCOPE'>> = {
  // Roles spec §A5 (V12) and the Artifacts table (V19). A Test Engineer unmakes only their own
  // work, and curates their own cycle rather than the document every cycle inherits.
  'Test Engineer': {
    'case:delete': 'OWN',
    'evidence:delete': 'OWN',
    'artifact:edit': 'PLAN_SCOPE',
  },
};

function qualifierFor(roleName: string | undefined, key: string): HeldPermission['qualifier'] {
  return QUALIFIED_GRANTS[roleName ?? '']?.[key] ?? 'ALL';
}

/**
 * Does the caller hold this permission on this project **at all** — the gate every write handler
 * asks, and the mock's half of "the answer that enables a control also decides the write".
 *
 * Its own function since §65 rather than `permissionsOn(id).includes(key)`, because that array now
 * holds objects and `.includes` on it would compile against a widened type and silently answer
 * false for everything. Deliberately ignores the qualifier: this is "is it held", exactly as
 * `PermissionSet.has` is. A handler refusing on a qualifier asks {@link qualifierOn}, so the two
 * questions cannot be confused at a call site.
 */
export function holdsOn(projectId: string, permission: string): boolean {
  return permissionsOn(projectId).some((entry) => entry.key === permission);
}

/** Which rows the caller's grant covers, or `null` when they do not hold it here. */
export function qualifierOn(
  projectId: string,
  permission: string,
): HeldPermission['qualifier'] | null {
  return permissionsOn(projectId).find((entry) => entry.key === permission)?.qualifier ?? null;
}

export function requireAuth(request: Request) {
  return request.headers.get('Authorization')?.startsWith('Bearer ')
    ? null
    : problem(401, 'Unauthorized', 'Your session expired. Sign in to continue.');
}

export const userRef = (i: number) => ({
  id: users[i].id,
  displayName: users[i].displayName,
  email: users[i].email,
  avatarUrl: null,
});

/**
 * The people who may be given work in a project — `GET /projects/{id}/members`.
 *
 * Shared, and read by **both** the members endpoint and `POST /work-items`, for the reason the
 * server shares its own predicate between the same two places: a picker that offers somebody the
 * create refuses is a form the user fills in correctly and cannot submit. Two functions here would
 * reproduce that defect inside the mock, where it is hardest to notice because nothing 400s.
 *
 * **What the fixture can and cannot express.** The server's rule is *active membership AND a grant
 * on this project*. The first half is real here — Lena Fischer is `DEACTIVATED` and is therefore
 * absent, which is the awkward row the picker has to handle. The second is not: the mock has no
 * per-project grant data, so every active person is a member of every project. A live pass is what
 * proves the grant half, and §54 ran one.
 */
export function projectMembers(projectId: string) {
  if (!db.projects.some((p) => p.id === projectId)) return [];
  return users
    .filter((u) => u.status === 'ACTIVE')
    .map((u) => ({ id: u.id, displayName: u.displayName, email: u.email, avatarUrl: null }))
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
}

export interface ListQuery {
  page: number;
  size: number;
  sort: string;
}

export function paging(url: URL): ListQuery {
  return {
    page: Number(url.searchParams.get('page') ?? 0),
    size: Number(url.searchParams.get('size') ?? 25),
    sort: url.searchParams.get('sort') ?? 'keySeq,asc',
  };
}
