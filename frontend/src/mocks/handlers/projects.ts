/** The project list, one project, its two vocabularies, and creating one. */
import { HttpResponse, delay, http } from 'msw';
import { db, newId } from '../db';
import { companyScopePermissions, dimensions } from '../fixtures/core';
import { API, LATENCY, notFound, problem, requireAuth } from './shared';

/** Mirrors `ck_project_key_format` in V1 and `ProjectInput.key`'s pattern in the contract. */
const KEY_PATTERN = /^[A-Z0-9]{2,10}$/;
/** Mirrors `ProjectEdit.MAX_NAME` on the server. */
const MAX_NAME = 200;

export const projectHandlers = [
  http.get(`${API}/projects`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    return HttpResponse.json(db.projects);
  }),

  /*
    Create. Three properties are mirrored from the real endpoint on purpose, because a mock that
    proves a property live mode lacks is the divergence this project has paid for twice (the
    `If-Match`-in-the-body family, and the `Idempotency-Key` no server read):

      1. **The gate is COMPANY scope.** Refused with the same 403 sentence `ProjectController`
         sends, from the same company-scope permission set `GET /me/permissions` answers with — so
         the disabled button and the refused write cannot disagree.
      2. **A duplicate key is a 400 on `key`**, not a 409. The form has a field to put it on and
         the user's next action is to type a different key.
      3. **`myRole` is null and `caseCount` is 0.** The server mints no project grant for the
         creator, so there is no per-project role to report — filling it in here would make the
         mock the only place the app ever shows one.
  */
  http.post(`${API}/projects`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    if (!companyScopePermissions.includes('project:create')) {
      return problem(
        403,
        'Not allowed',
        'You do not have permission to create projects in this company.',
      );
    }

    const body = (await request.json()) as {
      key?: string;
      name?: string;
      description?: string | null;
    };
    const key = (body.key ?? '').trim();
    const name = (body.name ?? '').trim();
    const errors: Record<string, string[]> = {};

    if (!key) {
      errors.key = ['Enter a project key.'];
    } else if (!KEY_PATTERN.test(key)) {
      errors.key = ['Use 2–10 upper-case letters or digits, like OLB.'];
    } else if (db.projects.some((p) => p.key === key)) {
      errors.key = ['Another project in this company already uses that key.'];
    }
    if (!name) {
      errors.name = ['Enter a project name.'];
    } else if (name.length > MAX_NAME) {
      errors.name = [`Keep the name under ${MAX_NAME} characters.`];
    }
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const created = {
      id: newId(),
      key,
      name,
      description: body.description?.trim() || null,
      archived: false,
      caseCount: 0,
      myRole: null,
    };
    db.projects.push(created);

    return HttpResponse.json(created, {
      status: 201,
      headers: { Location: `${API}/projects/${created.id}` },
    });
  }),

  http.get(`${API}/projects/:projectId`, ({ params }) => {
    const project = db.projects.find((p) => p.id === params.projectId);
    return project ? HttpResponse.json(project) : notFound('That project');
  }),

  http.get(`${API}/projects/:projectId/classification`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(dimensions);
  }),

  http.get(`${API}/projects/:projectId/statuses`, () => HttpResponse.json(db.statuses)),
];
