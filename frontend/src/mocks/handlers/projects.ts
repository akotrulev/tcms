/** The project list, one project, its two vocabularies, and creating one. */
import { HttpResponse, delay, http } from 'msw';
import { db, newId } from '../db';
import { companyScopePermissions } from '../fixtures/core';
import type {
  ClassificationDimension,
  ClassificationValue,
  ClassificationValueInput,
} from '../../api/types';
import {
  API,
  LATENCY,
  notFound,
  permissionsOn,
  problem,
  projectMembers,
  requireAuth,
} from './shared';

/** Mirrors `ck_project_key_format` in V1 and `ProjectInput.key`'s pattern in the contract. */
const KEY_PATTERN = /^[A-Z0-9]{2,10}$/;
/** Mirrors `ProjectEdit.MAX_NAME` on the server. */
const MAX_NAME = 200;
/** Mirrors `ClassificationValueEdit.MAX_NAME`. */
const MAX_VALUE_NAME = 150;
/** Mirrors `ClassificationValueEdit.COLOR`. */
const COLOR_PATTERN = /^#[0-9a-fA-F]{6}$/;

/**
 * The vocabulary gate, or null when the caller may write.
 *
 * Two permissions at two scopes, from the same two sources `GET /me/permissions` answers with:
 * a company-level dimension (`locked`) needs `company:vocabulary` company-wide, a project-level one
 * needs `project:settings` on this project. Answering from anything else — a hard-coded true, or
 * one permission for both — is how a mock enables a control the server refuses.
 */
function vocabularyRefusal(projectId: string, locked: boolean) {
  const held = locked
    ? companyScopePermissions.includes('company:vocabulary')
    : permissionsOn(projectId).some((p) => p.key === 'project:settings');

  return held
    ? null
    : problem(
        403,
        'Not allowed',
        'You do not have permission to edit this classification vocabulary.',
      );
}

/**
 * Every field rule the server applies, in the same order and with the same sentences.
 *
 * `current` is the value being edited, or null on a create — and it is what keeps a rename from
 * colliding with itself, the defect every edit-side uniqueness check has by default.
 */
function validateValue(
  dimension: ClassificationDimension,
  body: ClassificationValueInput,
  current: ClassificationValue | null,
): Record<string, string[]> | null {
  const errors: Record<string, string[]> = {};
  const name = (body.name ?? '').trim();

  if (!name) {
    errors.name = ['Enter a name.'];
  } else if (name.length > MAX_VALUE_NAME) {
    errors.name = [`Keep the name under ${MAX_VALUE_NAME} characters.`];
  }

  const parentId = current ? current.parentId : (body.parentId ?? null);
  if (current) {
    // Refused rather than dropped: dropping it is the answer where the client shows the value in
    // its new place, reloads, and finds it back — with the server having reported success.
    if (body.parentId != null && body.parentId !== current.parentId) {
      errors.parentId = ['Moving a value is not supported yet. Leave the parent unchanged.'];
    }
    if (body.dimensionId != null && body.dimensionId !== dimension.id) {
      errors.dimensionId = ['A value cannot change dimension. Retire it and add a new one.'];
    }
  } else if (parentId != null && !dimension.values.some((v) => v.id === parentId)) {
    // One sentence for "another axis" and "another project's tree" alike — telling them apart
    // tells the caller that the id they guessed names a real value somewhere else.
    errors.parentId = ['That value is not in this dimension.'];
  }

  const color = body.color?.trim();
  if (color && !COLOR_PATTERN.test(color)) {
    errors.color = ['Use a six-digit hex colour, like #b7791f.'];
  }

  const duplicate = dimension.values.some(
    (v) =>
      v.id !== current?.id &&
      (v.parentId ?? null) === parentId &&
      v.name.toLowerCase() === name.toLowerCase(),
  );
  if (name && !errors.parentId && duplicate) {
    errors.name = ['Another value here already has that name.'];
  }

  return Object.keys(errors).length > 0 ? errors : null;
}

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
    return HttpResponse.json(db.dimensions);
  }),

  /*
    The vocabulary's three writes.

    Every rule the server enforces is enforced here too, rather than the handler storing what it is
    given. A mock that merely accepts is the divergence this project has now paid for three times:
    mock mode saves a payload live mode answers 400 to, and nobody sees it until the deployed box
    behaves differently from the one the feature was built against (§64, §68).

    The gate is the interesting one, because it is TWO gates and the mock persona sits on the line:
    a Test Lead on OLB holds `project:settings` and not `company:vocabulary`, so in mock mode the
    Feature tree is editable and the three company vocabularies render read-only with their reason.
    Both branches of the affordance are reachable without editing a fixture, which §31's own note
    about `project:create` says is the state to aim for.
  */
  http.post(`${API}/projects/:projectId/classification/values`, async ({ request, params }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const body = (await request.json()) as ClassificationValueInput;
    const dimension = db.dimensions.find((d) => d.id === body.dimensionId);
    // 404 before any permission question — the permission to ask is a property of the dimension,
    // so a caller whose dimension does not resolve cannot be told which one they failed.
    if (!dimension) return notFound('That dimension');

    const refusal = vocabularyRefusal(String(params.projectId), dimension.locked);
    if (refusal) return refusal;

    const errors = validateValue(dimension, body, null);
    if (errors) return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);

    const id = newId();
    const parent = dimension.values.find((v) => v.id === body.parentId);
    const created = {
      id,
      parentId: body.parentId ?? null,
      name: (body.name ?? '').trim(),
      // The label is derived from the id and not from the name, exactly as `ClassificationValue`
      // does it: a name-derived path makes every rename a subtree rewrite, and `tcms_ltree_label`
      // has no answer at all for a name with no ASCII letters in it.
      path: parent ? `${parent.path}.v${id.replace(/-/g, '')}` : `v${id.replace(/-/g, '')}`,
      color: body.color?.trim() || null,
    };
    dimension.values.push(created);

    return HttpResponse.json(created, { status: 201 });
  }),

  http.put(
    `${API}/projects/:projectId/classification/values/:valueId`,
    async ({ request, params }) => {
      const denied = requireAuth(request);
      if (denied) return denied;
      await delay(LATENCY);

      const valueId = String(params.valueId);
      const dimension = db.dimensions.find((d) => d.values.some((v) => v.id === valueId));
      if (!dimension) return notFound('That value');

      const refusal = vocabularyRefusal(String(params.projectId), dimension.locked);
      if (refusal) return refusal;

      const value = dimension.values.find((v) => v.id === valueId)!;
      const body = (await request.json()) as ClassificationValueInput;

      const errors = validateValue(dimension, body, value);
      if (errors) return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);

      value.name = (body.name ?? '').trim();
      value.color = body.color?.trim() || null;
      // `path` is deliberately untouched — that is the whole point of an id-derived label, and a
      // mock that rewrote it here would hide a rename bug the server cannot have.
      return HttpResponse.json(value);
    },
  ),

  http.delete(
    `${API}/projects/:projectId/classification/values/:valueId`,
    async ({ request, params }) => {
      const denied = requireAuth(request);
      if (denied) return denied;
      await delay(LATENCY);

      const valueId = String(params.valueId);
      const dimension = db.dimensions.find((d) => d.values.some((v) => v.id === valueId));
      if (!dimension) return notFound('That value');

      const refusal = vocabularyRefusal(String(params.projectId), dimension.locked);
      if (refusal) return refusal;

      const children = dimension.values.filter((v) => v.parentId === valueId).length;
      if (children > 0) {
        return problem(409, 'Still in use', 'Retire the values underneath this one first.', {
          children: [String(children)],
        });
      }

      // Retiring is a soft delete on the server and the row survives; from a client's side the
      // only observable difference is that it stops being served, which is what this does.
      dimension.values = dimension.values.filter((v) => v.id !== valueId);
      return new HttpResponse(null, { status: 204 });
    },
  ),

  http.get(`${API}/projects/:projectId/statuses`, () => HttpResponse.json(db.statuses)),

  /*
    Who may be given work here. 404 for an unreachable project rather than an empty array — the
    contract declares it, and §6t requires an unreachable project and a non-existent one to be
    indistinguishable. An empty member list would confirm that the project exists.
  */
  http.get(`${API}/projects/:projectId/members`, async ({ params }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);
    return db.projects.some((p) => p.id === projectId)
      ? HttpResponse.json(projectMembers(projectId))
      : notFound('That project');
  }),
];
