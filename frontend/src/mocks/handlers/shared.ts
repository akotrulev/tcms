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
export function permissionsOn(projectId: string): string[] {
  const project = db.projects.find((p) => p.id === projectId);
  const role = roles.find((r) => r.name === project?.myRole);
  return [...(role?.permissions ?? [])].sort();
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
