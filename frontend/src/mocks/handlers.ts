/**
 * MSW request handlers — the stand-in for the Spring Boot API until it exists.
 *
 * These are deliberately *stateful* (see ./db.ts): a create round-trips into the list, an
 * edit into the detail, a submitted result into the run counts. A handler set that returns
 * fixtures regardless of what you sent makes every write screen untestable, because the
 * one thing you need to see — that the write landed — is the thing it fakes.
 *
 * Paths mirror docs/specs/openapi.yaml exactly. When the contract changes, change it there,
 * regenerate, then fix both sides.
 */
import { HttpResponse, delay, http } from 'msw';
import { verdictFor } from './faults';
import type {
  Artifact,
  BulkResult,
  EntityVersion,
  Facet,
  LifecycleStatus,
  Suite,
  TestCase,
  TestCaseSummary,
  TestPlan,
  TestRun,
  TestStep,
  WorkItem,
} from '../api/types';
import {
  addWorkItem,
  allocateCaseKey,
  allocatePlanKey,
  allocateRunKey,
  allocateSuiteKey,
  suiteCaseCount,
  caseDetail,
  db,
  descendantSuiteIds,
  newId,
  recountRun,
  reporting,
  runExecutions,
  suiteTree,
  users,
  workItems,
  writeCase,
} from './db';
import { currentUser, dimensions, projects, roleAssignments, roles, teams } from './fixtures/core';

const API = '/api/v1';

/** A little latency, so loading states are real rather than theoretical. */
const LATENCY = 220;

/**
 * `Problem` from the contract. `errors` is the RFC-7807 field map the client reads as
 * `ApiError.fieldErrors`, and it is omitted rather than sent empty: a 409 carrying `errors: {}`
 * reads as "no fields were wrong" instead of "fields are not what this is about".
 */
const problem = (
  status: number,
  title: string,
  detail: string,
  errors?: Record<string, string[]>,
) =>
  HttpResponse.json(
    { type: 'about:blank', title, status, detail, ...(errors ? { errors } : {}) },
    { status },
  );

const notFound = (what: string) => problem(404, 'Not found', `${what} does not exist.`);

/** The server's own cap on a suite name, mirrored so a rejection reads the same in both modes. */
const MAX_SUITE_NAME = 150;

function requireAuth(request: Request) {
  return request.headers.get('Authorization')?.startsWith('Bearer ')
    ? null
    : problem(401, 'Unauthorized', 'Your session expired. Sign in to continue.');
}

const userRef = (i: number) => ({
  id: users[i].id,
  displayName: users[i].displayName,
  email: users[i].email,
  avatarUrl: null,
});

// ----------------------------------------------------------------- filtering ----

interface ListQuery {
  page: number;
  size: number;
  sort: string;
}

function paging(url: URL): ListQuery {
  return {
    page: Number(url.searchParams.get('page') ?? 0),
    size: Number(url.searchParams.get('size') ?? 25),
    sort: url.searchParams.get('sort') ?? 'keySeq,asc',
  };
}

/**
 * Deterministic sort with a unique tiebreaker on every list (ui-rules §2). Without the
 * tiebreaker, rows jump position after an edit and paging duplicates or drops them.
 */
function sortCases(rows: TestCaseSummary[], sort: string): TestCaseSummary[] {
  const [field, dir = 'asc'] = sort.split(',');
  const sign = dir === 'desc' ? -1 : 1;
  return [...rows].sort((a, b) => {
    let primary = 0;
    switch (field) {
      case 'title':
        primary = a.title.localeCompare(b.title);
        break;
      case 'updatedAt':
        primary = a.updatedAt.localeCompare(b.updatedAt);
        break;
      case 'priority': {
        const rank = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
        primary = rank[a.priority] - rank[b.priority];
        break;
      }
      case 'lifecycleStatus':
        primary = a.lifecycleStatus.localeCompare(b.lifecycleStatus);
        break;
      case 'suiteName':
        primary = a.suiteName.localeCompare(b.suiteName);
        break;
      default:
        // Order by keySeq, never the text key — TC-10000 sorts before TC-9999.
        primary = a.keySeq - b.keySeq;
    }
    return primary * sign || a.keySeq - b.keySeq;
  });
}

const CASE_KEY_PATTERN = /^TC[_-]?(\d+)$/i;

function filterCases(url: URL): TestCaseSummary[] {
  const p = url.searchParams;
  let rows = db.cases;

  const q = p.get('q')?.trim();
  if (q) {
    // A value matching the key pattern short-circuits to an exact lookup (plan §6q) —
    // typing a key you were given should land on that case, not search for it as text.
    const asKey = CASE_KEY_PATTERN.exec(q);
    if (asKey) {
      const seq = Number(asKey[1]);
      rows = rows.filter((c) => c.keySeq === seq);
    } else {
      const needle = q.toLowerCase();
      rows = rows.filter(
        (c) => c.title.toLowerCase().includes(needle) || c.key.toLowerCase().includes(needle),
      );
    }
  }

  const suiteId = p.get('suiteId');
  if (suiteId) {
    const scope = descendantSuiteIds(suiteId);
    rows = rows.filter((c) => scope.has(c.suiteId));
  }

  const featureId = p.get('featureId');
  if (featureId) {
    const value = dimensions
      .find((d) => d.key === 'feature')
      ?.values.find((v) => v.id === featureId);
    if (value) rows = rows.filter((c) => c.features.includes(value.name));
  }

  const typeId = p.get('testTypeId');
  if (typeId) {
    const value = dimensions.find((d) => d.key === 'test_type')?.values.find((v) => v.id === typeId);
    if (value) rows = rows.filter((c) => c.testType === value.name);
  }

  const levelId = p.get('testLevelId');
  if (levelId) {
    const value = dimensions.find((d) => d.key === 'test_level')?.values.find((v) => v.id === levelId);
    if (value) rows = rows.filter((c) => c.testLevel === value.name);
  }

  const multi = (key: string) => p.getAll(key).filter(Boolean);

  const lifecycle = multi('lifecycleStatus');
  if (lifecycle.length) rows = rows.filter((c) => lifecycle.includes(c.lifecycleStatus));

  const automation = multi('automationStatus');
  if (automation.length) rows = rows.filter((c) => automation.includes(c.automationStatus));

  const priority = multi('priority');
  if (priority.length) rows = rows.filter((c) => priority.includes(c.priority));

  const health = multi('healthSignal');
  if (health.length) rows = rows.filter((c) => c.healthSignals.some((h) => health.includes(h)));

  const release = p.get('releaseVersion');
  if (release) rows = rows.filter((c) => c.lastResult?.releaseVersion === release);

  return rows;
}

/** Facet counts are computed over the *filtered* set so the numbers match what's shown. */
function facetsFor(rows: TestCaseSummary[]): Facet[] {
  const count = <T extends string>(pick: (c: TestCaseSummary) => T[]) => {
    const tally = new Map<string, number>();
    for (const row of rows) for (const v of pick(row)) tally.set(v, (tally.get(v) ?? 0) + 1);
    return [...tally.entries()].map(([id, n]) => ({ id, label: id, count: n }));
  };

  return [
    { key: 'lifecycleStatus', label: 'Status', values: count((c) => [c.lifecycleStatus]) },
    { key: 'automationStatus', label: 'Automation', values: count((c) => [c.automationStatus]) },
    { key: 'priority', label: 'Priority', values: count((c) => [c.priority]) },
    { key: 'healthSignal', label: 'Health', values: count((c) => c.healthSignals) },
  ];
}

// ------------------------------------------------------------------ handlers ----

export const handlers = [
  /**
   * Fault injection, first in the list so it can pre-empt every other handler.
   *
   * MSW resolves handlers in order and lets a resolver fall through by returning nothing, so one
   * wildcard here covers every endpoint — the alternative, threading a check into all forty
   * handlers below, would be forty chances to forget one.
   *
   * Everything it does is controlled from the console via `tcmsFaults` (see ./faults.ts), and
   * with no fault armed it returns `undefined` and costs nothing.
   */
  http.all(`${API}/*`, async ({ request }) => {
    const verdict = verdictFor(new URL(request.url).pathname);

    switch (verdict.kind) {
      case 'none':
        return undefined;

      case 'hang':
        // Longer than the longest budget in the client, so the client's own deadline is what
        // ends the request — which is exactly the behaviour under test.
        await delay(120_000);
        return undefined;

      case 'drift':
        // A 200 whose body is the wrong shape. This must surface as a FAILED region, never as
        // an empty one: "the service is broken" and "there is nothing here" are different facts,
        // and a `catch` that turns the first into the second is the defect being guarded against.
        return HttpResponse.json({ unexpected: 'shape' });

      case 'status':
        return HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Injected fault',
            status: verdict.status,
            detail: 'This failure was injected from the console for resilience testing.',
          },
          {
            status: verdict.status,
            headers: {
              // A correlation id on every failure, so the notification's copyable reference and
              // the dedupe-by-id behaviour can both actually be exercised.
              'x-correlation-id': `fault-${Math.random().toString(36).slice(2, 10)}`,
              ...(verdict.retryAfter !== null ? { 'retry-after': String(verdict.retryAfter) } : {}),
            },
          },
        );
    }
  }),

  // ------------------------------------------------------------------ auth ----
  http.post(`${API}/auth/login`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as { email?: string; password?: string };

    if (!body.email || !body.password) {
      return problem(400, 'Missing credentials', 'Enter your email and password.');
    }
    // Any password of 4+ characters signs you in as the seeded lead — but a short one
    // still fails, so the form's error path is reachable in the prototype.
    if (body.password.length < 4) {
      return problem(401, 'Sign-in failed', "That email and password don't match an account.");
    }

    return HttpResponse.json({
      token: `mock.${btoa(body.email)}.jwt`,
      expiresAt: new Date(Date.now() + 8 * 3600_000).toISOString(),
      user: currentUser,
    });
  }),

  http.post(`${API}/auth/logout`, () => new HttpResponse(null, { status: 204 })),

  /*
    Invitation redemption. Both handlers answer a **bodiless 404** for every rejection —
    unknown token, already redeemed, and (in the real service) expired, membership removed or
    account deactivated. That is the contract, and it is a security property rather than
    laziness: told apart, the responses would let anyone with a token-shaped string learn which
    addresses exist on this instance. A mock that helpfully explained "already used" would make
    the UI's one non-committal panel look like a bug and invite someone to "improve" it.
  */
  http.get(`${API}/auth/invitations/:token`, async ({ params }) => {
    await delay(LATENCY);
    const invitation = db.invitations.get(String(params.token));
    if (!invitation || invitation.consumed) return new HttpResponse(null, { status: 404 });

    return HttpResponse.json({
      email: invitation.email,
      displayName: invitation.displayName,
      passwordRequired: invitation.passwordRequired,
    });
  }),

  http.post(`${API}/auth/invitations/:token/accept`, async ({ params, request }) => {
    await delay(LATENCY);
    const invitation = db.invitations.get(String(params.token));
    if (!invitation || invitation.consumed) return new HttpResponse(null, { status: 404 });

    const body = (await request.json().catch(() => ({}))) as { password?: string };
    const password = body?.password;

    // 422 — and unlike the 404s, this one DOES carry a reason: it describes the password just
    // submitted, not the account or the instance, so saying "too short" leaks nothing.
    if (invitation.passwordRequired) {
      if (!password) {
        return problem(422, 'The password was not accepted', 'Choose a password to activate your account.');
      }
      // Counted in bytes, matching BCrypt's real limit and the server's own check.
      const bytes = new TextEncoder().encode(password).length;
      if (bytes < 12) {
        return problem(422, 'The password was not accepted', 'Use at least 12 characters.');
      }
      if (bytes > 72) {
        return problem(422, 'The password was not accepted', 'That is too long — 72 bytes is the maximum.');
      }
    } else if (password) {
      // Rejected rather than ignored: holding an invitation to a second company must not confer
      // the ability to overwrite an existing credential.
      return problem(
        422,
        'The password was not accepted',
        'This account already has a password. Sign in with the one you use today.',
      );
    }

    // Consumed only after the password was accepted, so a typo cannot burn the only way in.
    invitation.consumed = true;
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/me`, ({ request }) => requireAuth(request) ?? HttpResponse.json(currentUser)),

  /*
    One project, one answer — `{ projectId, permissions }`, matching the contract and the real
    MeController. This handler used to return an ARRAY of per-project entries, which nothing
    consumed and no endpoint ever produced; it would have failed the first consumer written
    against it the moment live mode existed. Drift in an unused handler is still drift.

    It also used to answer **Test Lead for every project**, which was harmless while nothing
    called it and became actively misleading the moment the disable-don't-hide gate did: a mock
    that grants everything can only ever render the enabled branch, so the whole feature would
    have been unprovable in the mode the app is developed in. The answer now comes from the
    project's own `myRole`, and the fixtures already carry a project where the caller is a
    **Viewer** — MOB, seeded that way precisely because read-only is the default on joining.

    No project id means the company-wide question, which the real endpoint answers with the
    SYSTEM + COMPANY terms only — deliberately NOT the union of the caller's project grants.
    `company:read` is the honest mock of that for a plain member.
  */
  http.get(`${API}/me/permissions`, ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;

    const projectId = new URL(request.url).searchParams.get('projectId');
    if (!projectId) return HttpResponse.json({ projectId: null, permissions: ['company:read'] });

    // An unknown project answers with the empty set rather than a 404, matching the real
    // endpoint: an unreachable project and a non-existent one must be indistinguishable (§6t).
    const project = projects.find((p) => p.id === projectId);
    const role = roles.find((r) => r.name === project?.myRole);
    return HttpResponse.json({ projectId, permissions: [...(role?.permissions ?? [])].sort() });
  }),

  // -------------------------------------------------------------- projects ----
  http.get(`${API}/projects`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    return HttpResponse.json(projects);
  }),

  http.get(`${API}/projects/:projectId`, ({ params }) => {
    const project = projects.find((p) => p.id === params.projectId);
    return project ? HttpResponse.json(project) : notFound('That project');
  }),

  http.get(`${API}/projects/:projectId/classification`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(dimensions);
  }),

  http.get(`${API}/projects/:projectId/statuses`, () => HttpResponse.json(db.statuses)),

  // ---------------------------------------------------------------- suites ----
  http.get(`${API}/projects/:projectId/suites`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(suiteTree());
  }),

  /*
    Aligned with the real endpoint in one pass, because the divergences were the kind that make
    mock mode prove a property live mode does not have — the same class as the `If-Match`-in-the-
    body and honoured-Idempotency-Key findings the backend slices kept turning up. Three of them
    were here: the path was slugged from the NAME (so a rename would have had to rewrite the
    subtree), a parent from outside the project was accepted, and there was no DELETE at all.
  */
  http.post(`${API}/projects/:projectId/suites`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as { name?: string; parentId?: string | null; description?: string | null };

    const name = body.name?.trim() ?? '';
    const errors: Record<string, string[]> = {};
    if (!name) errors.name = ['Enter a name.'];
    else if (name.length > MAX_SUITE_NAME) {
      errors.name = [`Keep the name under ${MAX_SUITE_NAME} characters.`];
    }

    const parent = body.parentId ? db.suites.find((s) => s.id === body.parentId) : null;
    // A parent outside the project is a FIELD error, not a 404: the project resolved, and the
    // message belongs on the picker. Unchecked, the folder is created under another project's
    // suite and appears in no tree at all.
    if (body.parentId && !parent) errors.parentId = ['That suite is not in this project.'];

    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const { key, keySeq } = allocateSuiteKey();
    const suite: Suite = {
      id: newId(),
      key,
      parentId: body.parentId ?? null,
      name,
      description: body.description?.trim() || null,
      // Derived from the key sequence, exactly as the server does, and NOT from the name: a
      // name-derived label is not unique, can be empty, and would make every rename a subtree
      // rewrite.
      path: parent ? `${parent.path}.s${keySeq}` : `s${keySeq}`,
      caseCount: 0,
      lifecycleStatus: 'DRAFT',
      conditions: [],
      lockVersion: 0,
    };
    db.suites.push(suite);
    return HttpResponse.json(suite, { status: 201 });
  }),

  http.get(`${API}/projects/:projectId/suites/:suiteId`, ({ params }) => {
    const suite = suiteTree().find((s) => s.id === params.suiteId);
    return suite ? HttpResponse.json(suite) : notFound('That suite');
  }),

  http.patch(`${API}/projects/:projectId/suites/:suiteId`, async ({ params, request }) => {
    await delay(LATENCY);
    const suite = db.suites.find((s) => s.id === params.suiteId);
    if (!suite) return notFound('That suite');

    const body = (await request.json()) as { name?: string; parentId?: string | null; description?: string | null };

    // The precondition is the HEADER, never a body field. It was read from the body here for as
    // long as no server read it at all, which is exactly how a divergence survives: both sides
    // agreed, and both were wrong about where the contract puts it.
    const ifMatch = request.headers.get('If-Match');
    if (ifMatch !== null && ifMatch.trim() !== '') {
      const expected = Number(ifMatch.replace(/^"|"$/g, ''));
      if (Number.isNaN(expected)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          'If-Match': ["Send the suite's lockVersion as the If-Match header, or omit the header."],
        });
      }
      if (expected !== suite.lockVersion) {
        return problem(
          409,
          'Someone else saved first',
          'Reload the suite to see the newer version, then reapply your change.',
        );
      }
    }

    const name = body.name?.trim() ?? '';
    const errors: Record<string, string[]> = {};
    if (!name) errors.name = ['Enter a name.'];
    else if (name.length > MAX_SUITE_NAME) {
      errors.name = [`Keep the name under ${MAX_SUITE_NAME} characters.`];
    }
    // Refused, not ignored. Dropping it silently is the answer that lets a client show the folder
    // in its new place, reload, and find it back — with the server having reported success.
    if (body.parentId !== undefined && body.parentId !== null && body.parentId !== suite.parentId) {
      errors.parentId = ['Moving a suite is not supported yet. Leave the parent unchanged.'];
    }
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    suite.name = name;
    if (body.description !== undefined) suite.description = body.description?.trim() || null;
    // `lifecycleStatus` is NOT applied any more: it is not on SuiteInput, and a status an
    // ordinary edit can set is an approval its author granted themselves (§6g).
    suite.lockVersion = (suite.lockVersion ?? 0) + 1;
    return HttpResponse.json({ ...suite, caseCount: suiteCaseCount(suite.id) });
  }),

  http.delete(`${API}/projects/:projectId/suites/:suiteId`, async ({ params }) => {
    await delay(LATENCY);
    const index = db.suites.findIndex((s) => s.id === params.suiteId);
    if (index < 0) return notFound('That suite');
    const suite = db.suites[index];

    // Empty means empty of BOTH: a folder can hold no cases and still hold folders, and either
    // one removed silently is a node the tree still renders children under. A cascade would
    // delete other people's cases through a folder action with no preview, which is why the
    // server refuses it rather than offering a "delete everything" flag.
    const childSuites = db.suites.filter((s) => s.parentId === suite.id).length;
    const cases = suiteCaseCount(suite.id);
    if (childSuites > 0 || cases > 0) {
      const parts: string[] = [];
      if (childSuites > 0) parts.push(`${childSuites} ${childSuites === 1 ? 'suite' : 'suites'}`);
      if (cases > 0) parts.push(`${cases} ${cases === 1 ? 'case' : 'cases'}`);
      return problem(
        409,
        'Suite is not empty',
        `This suite still holds ${parts.join(' and ')}. Move or delete them first.`,
      );
    }

    db.suites.splice(index, 1);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/projects/:projectId/suites/:suiteId/history`, async () => {
    await delay(LATENCY);
    return HttpResponse.json({
      runs: reporting.suiteHistoryEntries,
      versionMarkers: reporting.suiteVersionMarkers,
    });
  }),

  // ----------------------------------------------------------------- cases ----
  http.get(`${API}/projects/:projectId/cases`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const url = new URL(request.url);
    const { page, size, sort } = paging(url);
    const filtered = filterCases(url);
    const sorted = sortCases(filtered, sort);

    return HttpResponse.json({
      items: sorted.slice(page * size, page * size + size),
      page,
      size,
      total: filtered.length,
      facets: facetsFor(filtered),
    });
  }),

  http.post(`${API}/projects/:projectId/cases`, async ({ request }) => {
    await delay(LATENCY);

    /*
      Idempotency, implemented rather than assumed.

      The client sends one `Idempotency-Key` per user intent and reuses it on every retry of that
      intent, so a timed-out create can be safely tried again. That guarantee is only real if the
      server actually dedupes — so the mock does, and the behaviour is provable end to end
      instead of being a header nobody reads.
    */
    const idempotencyKey = request.headers.get('Idempotency-Key');
    if (idempotencyKey) {
      const previous = db.idempotency.get(idempotencyKey);
      // 200, not 201: nothing was created this time. The client gets the original record back.
      if (previous) return HttpResponse.json(previous, { status: 200 });
    }

    const body = (await request.json()) as Record<string, unknown>;
    const title = String(body.title ?? '').trim();
    const suiteId = String(body.suiteId ?? '');

    // Field-keyed errors, so the form can land them on the right inputs rather than
    // dumping one banner over a form the user can't fix.
    const errors: Record<string, string[]> = {};
    if (!title) errors.title = ['Enter a title.'];
    if (!suiteId) errors.suiteId = ['Choose a suite.'];
    /*
      The six fields the real endpoint refuses, refused here in the same words.

      Each needs a write path the backend has not built (a membership check for the owner,
      cardinality validation for classification, the library-vs-inline shape for conditions). The
      mock echoing them back would make mock mode accept a payload live mode rejects — the exact
      divergence that let the SPA put `lockVersion` in the PATCH body for months, because no server
      read either. Absent means unchanged; present means 400, in both modes.
    */
    for (const field of [
      'ownerId',
      'classificationValueIds',
      'tags',
      'automationTags',
      'preconditions',
      'postconditions',
    ]) {
      if (body[field] != null) errors[field] = ['This field cannot be changed here yet.'];
    }
    if (Object.keys(errors).length) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, detail: 'Check the highlighted fields.', errors },
        { status: 400 },
      );
    }

    const { key, keySeq } = allocateCaseKey();
    const steps = ((body.steps as TestStep[] | undefined) ?? []).map((s, i) => ({
      ...s,
      id: s.id ?? newId(),
      position: i,
    }));

    const created: TestCase = {
      id: newId(),
      key,
      keySeq,
      title,
      suiteId,
      suiteName: db.suites.find((s) => s.id === suiteId)?.name ?? '',
      priority: (body.priority as TestCase['priority']) ?? 'MEDIUM',
      lifecycleStatus: 'DRAFT',
      automationStatus: (body.automationStatus as TestCase['automationStatus']) ?? 'MANUAL',
      stepFormat: (body.stepFormat as TestCase['stepFormat']) ?? 'STRUCTURED',
      // The creator owns what they wrote — `ownerId` is refused above, so there is nothing to
      // resolve. `userRef(0)` is this mock's stand-in for the signed-in user throughout.
      owner: userRef(0),
      testType: (body.testType as string) ?? null,
      testLevel: (body.testLevel as string) ?? null,
      features: [],
      tags: [],
      healthSignals: ['NEVER_EXECUTED'],
      lastResult: null,
      updatedAt: new Date().toISOString(),
      description: (body.description as string) ?? null,
      objective: (body.objective as string) ?? null,
      expectedOutcome: (body.expectedOutcome as string) ?? null,
      severity: (body.severity as TestCase['priority']) ?? 'MEDIUM',
      estimatedDurationSec: (body.estimatedDurationSec as number) ?? null,
      automationTags: [],
      automationKey: (body.automationKey as string) ?? null,
      notAutomatableReason: (body.notAutomatableReason as string) ?? null,
      gherkinSource: (body.gherkinSource as string) ?? null,
      steps,
      preconditions: [],
      postconditions: [],
      relatedDocuments: [],
      currentVersionNo: 1,
      lockVersion: 1,
    };

    const saved = writeCase(created);
    // Recorded only after the write succeeded — a key stored before a failure would make the
    // retry return a record that was never created.
    if (idempotencyKey) db.idempotency.set(idempotencyKey, saved);
    return HttpResponse.json(saved, { status: 201 });
  }),

  http.post(`${API}/projects/:projectId/cases/bulk`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as {
      caseIds?: string[];
      action?: string;
      dryRun?: boolean;
      payload?: Record<string, unknown>;
    };
    const ids = body.caseIds ?? [];
    const dryRun = body.dryRun ?? false;

    const changed: string[] = [];
    const unchanged: string[] = [];
    const skipped: { key: string; reason: string }[] = [];

    for (const id of ids) {
      const row = db.cases.find((c) => c.id === id);
      if (!row) continue;

      if (body.action === 'SET_STATUS') {
        const target = body.payload?.lifecycleStatus as LifecycleStatus;
        if (row.lifecycleStatus === target) {
          unchanged.push(row.key);
        } else if (target === 'APPROVED' && row.lifecycleStatus === 'DRAFT') {
          // A real guard, not a mock formality: draft goes to review before approval.
          skipped.push({ key: row.key, reason: 'A draft must be reviewed before it can be approved.' });
        } else {
          if (!dryRun) {
            row.lifecycleStatus = target;
            const detail = db.caseDetails.get(id);
            if (detail) detail.lifecycleStatus = target;
          }
          changed.push(row.key);
        }
      } else if (body.action === 'ATTACH_CONDITIONS') {
        const detail = caseDetail(id);
        const condition = db.conditions.find((c) => c.id === body.payload?.conditionId);
        if (!detail || !condition) {
          skipped.push({ key: row.key, reason: 'The condition no longer exists.' });
        } else if (detail.preconditions?.some((p) => p.conditionId === condition.id)) {
          unchanged.push(row.key);
        } else {
          if (!dryRun) {
            detail.preconditions = [
              ...(detail.preconditions ?? []),
              {
                id: newId(),
                position: detail.preconditions?.length ?? 0,
                conditionId: condition.id,
                title: condition.title,
                body: condition.body,
                inline: false,
                inherited: false,
                inheritedFrom: null,
              },
            ];
          }
          changed.push(row.key);
        }
      } else {
        skipped.push({ key: row.key, reason: 'That operation is not supported yet.' });
      }
    }

    const result: BulkResult = { dryRun, changed, unchanged, skipped, total: ids.length };
    return HttpResponse.json(result);
  }),

  http.get(`${API}/projects/:projectId/cases/:caseId`, async ({ params }) => {
    await delay(LATENCY);
    const detail = caseDetail(String(params.caseId));
    return detail ? HttpResponse.json(detail) : notFound('That test case');
  }),

  http.patch(`${API}/projects/:projectId/cases/:caseId`, async ({ params, request }) => {
    await delay(LATENCY);
    const existing = caseDetail(String(params.caseId));
    if (!existing) return notFound('That test case');

    const body = (await request.json()) as Record<string, unknown>;

    // The precondition is the If-Match header, not a body field — see `useUpdateCase`. Read the
    // same way the real endpoint reads it, quoted form included: a mock that accepts a shape the
    // server refuses is a mock that hides the bug until live mode (§27's condition-order defect,
    // arriving through a different door).
    const ifMatch = request.headers.get('If-Match');
    if (ifMatch !== null && ifMatch.trim() !== '') {
      const expected = Number(ifMatch.trim().replace(/^"(.*)"$/, '$1'));
      if (!Number.isInteger(expected)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          'If-Match': ["Send the case's lockVersion as the If-Match header, or omit the header."],
        });
      }
      if (expected !== existing.lockVersion) {
        return problem(409, 'Someone else saved first', 'Reload the case to see the newer version, then reapply your change.');
      }
    }

    if (typeof body.title === 'string' && !body.title.trim()) {
      return HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Validation failed',
          status: 400,
          detail: 'Check the highlighted fields.',
          errors: { title: ['Enter a title.'] },
        },
        { status: 400 },
      );
    }

    // The six fields on `TestCaseInput` the real endpoint has no write path for are REFUSED, not
    // ignored — accepting and dropping them is a save that reports success and discards what the
    // user typed. Mirrored here so mock mode cannot pass a payload live mode rejects.
    const unsupported = [
      'ownerId',
      'classificationValueIds',
      'tags',
      'automationTags',
      'preconditions',
      'postconditions',
    ] as const;
    const refused = unsupported.filter((field) => body[field] !== undefined);
    if (refused.length > 0) {
      return problem(
        400,
        'Validation failed',
        'Check the highlighted fields.',
        Object.fromEntries(refused.map((f) => [f, ['This field cannot be changed here yet.']])),
      );
    }

    // Only fields the update path actually persists are applied — and the form only
    // renders these, so an edit can never show success and discard the value (ui-rules §3).
    // `lifecycleStatus` is absent because it is absent from `TestCaseInput`: promotion through
    // draft → review → approved is the review workflow's (plan §6g), and a status an ordinary
    // edit can set is an approval anyone grants themselves by saving.
    const writable = [
      'title',
      'description',
      'objective',
      'expectedOutcome',
      'priority',
      'severity',
      'automationStatus',
      'automationKey',
      'notAutomatableReason',
      'estimatedDurationSec',
      'stepFormat',
      'gherkinSource',
      'suiteId',
    ] as const;

    const next: TestCase = { ...existing };
    for (const field of writable) {
      if (body[field] !== undefined) {
        (next as Record<string, unknown>)[field] = body[field];
      }
    }
    if (Array.isArray(body.steps)) {
      next.steps = (body.steps as TestStep[]).map((s, i) => ({ ...s, id: s.id ?? newId(), position: i }));
    }
    if (typeof body.suiteId === 'string') {
      next.suiteName = db.suites.find((s) => s.id === body.suiteId)?.name ?? next.suiteName;
    }

    // A version is written on explicit save only, and skipped when nothing changed.
    const substantive = writable.some((f) => body[f] !== undefined) || Array.isArray(body.steps);
    if (substantive) next.currentVersionNo = (next.currentVersionNo ?? 1) + 1;
    next.lockVersion = (next.lockVersion ?? 1) + 1;

    return HttpResponse.json(writeCase(next));
  }),

  http.delete(`${API}/projects/:projectId/cases/:caseId`, async ({ params }) => {
    await delay(LATENCY);
    const id = String(params.caseId);
    const index = db.cases.findIndex((c) => c.id === id);
    if (index < 0) return notFound('That test case');
    db.cases.splice(index, 1);
    db.caseDetails.delete(id);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/projects/:projectId/cases/:caseId/versions`, async ({ params }) => {
    await delay(LATENCY);
    const detail = caseDetail(String(params.caseId));
    if (!detail) return notFound('That test case');
    const total = detail.currentVersionNo ?? 1;
    const versions: EntityVersion[] = Array.from({ length: total }, (_, i) => {
      const versionNo = total - i;
      const marker = reporting.caseVersionMarkers.find((m) => m.versionNo === versionNo);
      return {
        versionNo,
        createdBy: marker?.author ?? userRef(0),
        createdAt: marker?.occurredAt ?? '2026-07-01T09:00:00Z',
        changeComment: marker?.changeComment ?? null,
        // A version is pinned when some execution still references it, so it can never be
        // pruned — that pin is what keeps past reports honest.
        pinned: versionNo <= 7 && versionNo >= 3,
      };
    });
    return HttpResponse.json(versions);
  }),

  http.get(`${API}/projects/:projectId/cases/:caseId/history`, async ({ request }) => {
    await delay(LATENCY);
    const scope = new URL(request.url).searchParams.get('scope') ?? 'CURRENT_VERSION';
    const all = reporting.caseHistoryEntries;
    const entries = scope === 'ALL_VERSIONS' ? all : all.filter((e) => e.caseVersionNo === 7);

    return HttpResponse.json({
      summary:
        scope === 'ALL_VERSIONS'
          ? {
              ...reporting.caseHistorySummary,
              scope: 'All versions',
              executions: all.length,
              passed: all.filter((e) => e.statusKind === 'PASSED').length,
              failed: all.filter((e) => e.statusKind === 'FAILED').length,
            }
          : reporting.caseHistorySummary,
      entries,
      versionMarkers: reporting.caseVersionMarkers,
    });
  }),

  // ------------------------------------------------- conditions & steps ----
  http.get(`${API}/projects/:projectId/conditions`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const q = url.searchParams.get('q')?.toLowerCase();
    const type = url.searchParams.get('type');
    let rows = db.conditions;
    // A BOTH entry matches type=PRE and type=POST, because BOTH means "usable in either
    // position" — not a third role. Exact matching would make marking a condition reusable
    // everywhere the one way to hide it everywhere. The real API does the same; see
    // ConditionType.matching on the backend, which is where the decision is written down.
    if (type) rows = rows.filter((c) => c.type === type || (type !== 'BOTH' && c.type === 'BOTH'));
    if (q) rows = rows.filter((c) => c.title.toLowerCase().includes(q));
    // Most-reused first — the whole point of a reusable-condition search is to surface
    // the one 400 cases already share, not the one someone wrote yesterday.
    return HttpResponse.json([...rows].sort((a, b) => b.usageCount - a.usageCount || a.title.localeCompare(b.title)));
  }),

  http.get(`${API}/projects/:projectId/shared-steps`, async ({ request }) => {
    await delay(LATENCY);
    const q = new URL(request.url).searchParams.get('q')?.toLowerCase();
    const rows = q ? db.sharedSteps.filter((s) => s.title.toLowerCase().includes(q)) : db.sharedSteps;
    return HttpResponse.json([...rows].sort((a, b) => b.usageCount - a.usageCount));
  }),

  // ----------------------------------------------------------------- plans ----
  http.get(`${API}/projects/:projectId/plans`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(db.plans);
  }),

  http.post(`${API}/projects/:projectId/plans`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as Record<string, unknown>;
    const name = String(body.name ?? '').trim();
    if (!name) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, detail: 'Check the highlighted fields.', errors: { name: ['Enter a name.'] } },
        { status: 400 },
      );
    }
    const { key } = allocatePlanKey();
    const plan: TestPlan = {
      id: newId(),
      key,
      name,
      status: 'DRAFT',
      milestoneName: (body.milestoneName as string) ?? null,
      milestoneDate: (body.milestoneDate as string) ?? null,
      caseCount: 0,
      passRate: null,
      coverage: null,
      description: (body.description as string) ?? null,
      entryCriteria: [],
      exitCriteria: [],
      configurations: db.configurations.filter((c) => c.isDefault),
      currentVersionNo: 1,
      lockVersion: 1,
    };
    db.plans.unshift(plan);
    return HttpResponse.json(plan, { status: 201 });
  }),

  http.get(`${API}/projects/:projectId/plans/:planId`, async ({ params }) => {
    await delay(LATENCY);
    const plan = db.plans.find((p) => p.id === params.planId);
    return plan ? HttpResponse.json(plan) : notFound('That test plan');
  }),

  http.get(`${API}/projects/:projectId/plans/:planId/statistics`, async ({ params, request }) => {
    await delay(LATENCY);
    const plan = db.plans.find((p) => p.id === params.planId);
    if (!plan) return notFound('That test plan');

    const policy = new URL(request.url).searchParams.get('aggregation') ?? 'LATEST';
    const base = reporting.planStatistics;

    if (plan.id !== base.planId) {
      // A plan with no statistics is a real state, not an error — return the empty shape
      // so the screen renders its "not executed yet" case rather than a failure.
      return HttpResponse.json({
        ...base,
        planId: plan.id,
        planKey: plan.key,
        planName: plan.name,
        counts: { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: plan.caseCount, total: plan.caseCount },
        coveragePercent: 0,
        byConfiguration: [],
        bySuite: [],
        blockers: [],
        notRun: [],
        trend: [],
        exitCriteria: plan.exitCriteria ?? [],
        latestExecutionAt: null,
      });
    }

    // The policy is echoed back because it must be displayed on any multi-run report —
    // two teams reading "94% pass" otherwise mean different things (plan §3, detail 9).
    return HttpResponse.json({ ...base, aggregationPolicy: policy });
  }),

  // ------------------------------------------------------------------ runs ----
  http.get(`${API}/projects/:projectId/runs`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const planId = url.searchParams.get('planId');
    const status = url.searchParams.get('status');
    let rows = db.runs;
    if (planId) rows = rows.filter((r) => r.planId === planId);
    if (status) rows = rows.filter((r) => r.status === status);
    return HttpResponse.json(
      [...rows].sort((a, b) => (b.startedAt ?? '').localeCompare(a.startedAt ?? '') || a.key.localeCompare(b.key)),
    );
  }),

  http.post(`${API}/projects/:projectId/runs`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as Record<string, unknown>;
    const name = String(body.name ?? '').trim();
    if (!name) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, detail: 'Check the highlighted fields.', errors: { name: ['Enter a name.'] } },
        { status: 400 },
      );
    }
    const plan = db.plans.find((p) => p.id === body.planId);
    const caseIds = (body.caseIds as string[] | undefined) ?? db.cases.slice(0, 12).map((c) => c.id);
    const { key } = allocateRunKey();

    const run: TestRun = {
      id: newId(),
      key,
      name,
      planId: plan?.id ?? null,
      planKey: plan?.key ?? null,
      configuration:
        db.configurations.find((c) => c.id === body.configurationId) ??
        db.configurations.find((c) => c.isDefault) ??
        db.configurations[0],
      status: 'IN_PROGRESS',
      releaseVersion: (body.releaseVersion as string) ?? null,
      buildNumber: (body.buildNumber as string) ?? null,
      source: 'MANUAL',
      startedAt: new Date().toISOString(),
      completedAt: null,
      counts: { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: caseIds.length, total: caseIds.length },
    };
    db.runs.unshift(run);
    return HttpResponse.json(run, { status: 201 });
  }),

  http.get(`${API}/projects/:projectId/runs/:runId`, async ({ params }) => {
    await delay(LATENCY);
    const run = db.runs.find((r) => r.id === params.runId);
    if (!run) return notFound('That test run');
    const executions = runExecutions(run.id);
    return HttpResponse.json({
      ...run,
      executions: executions.map(({ steps: _s, evidence: _e, preconditions: _p, postconditions: _q, ...summary }) => summary),
    });
  }),

  http.get(`${API}/projects/:projectId/runs/:runId/executions/:executionId`, async ({ params }) => {
    await delay(LATENCY);
    const execution = runExecutions(String(params.runId)).find((e) => e.id === params.executionId);
    return execution ? HttpResponse.json(execution) : notFound('That execution');
  }),

  http.patch(`${API}/projects/:projectId/runs/:runId/executions/:executionId`, async ({ params, request }) => {
    await delay(LATENCY);
    const runId = String(params.runId);
    const execution = runExecutions(runId).find((e) => e.id === params.executionId);
    if (!execution) return notFound('That execution');

    const body = (await request.json()) as {
      statusId?: string;
      comment?: string | null;
      lockVersion?: number;
      stepResults?: { stepId?: string; statusId?: string; comment?: string | null }[];
    };

    // Concurrent execution is supported by design, which is exactly why the lock matters:
    // two testers on one run must not silently overwrite each other (open question #7).
    if (body.lockVersion !== undefined && body.lockVersion !== execution.lockVersion) {
      return problem(409, 'Someone else recorded a result first', 'Reload the execution to see their result before recording yours.');
    }

    if (body.statusId) {
      const status = db.statuses.find((s) => s.id === body.statusId);
      if (!status) return problem(400, 'Unknown status', 'That result status no longer exists.');
      execution.status = status;
      execution.executedAt = new Date().toISOString();
      execution.executedBy = userRef(0);
    }
    if (body.comment !== undefined) execution.comment = body.comment;

    for (const update of body.stepResults ?? []) {
      const step = execution.steps?.find((s) => s.stepId === update.stepId);
      if (!step) continue;
      if (update.statusId !== undefined) step.statusId = update.statusId;
      if (update.comment !== undefined) step.comment = update.comment;
    }

    execution.lockVersion = (execution.lockVersion ?? 1) + 1;
    recountRun(runId);
    return HttpResponse.json(execution);
  }),

  // --------------------------------------------------------- documentation ----
  http.get(`${API}/projects/:projectId/doc-pages`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(db.docPages);
  }),

  http.get(`${API}/projects/:projectId/doc-pages/:pageId`, async ({ params }) => {
    await delay(LATENCY);
    const page = db.docPageDetails[String(params.pageId)];
    if (page) return HttpResponse.json(page);

    // Pages without authored blocks are a normal state in a young docs space.
    const summary = db.docPages.find((p) => p.id === params.pageId);
    if (!summary) return notFound('That page');
    return HttpResponse.json({ ...summary, currentVersionNo: 1, lockVersion: 1, blocks: [] });
  }),

  // ------------------------------------------------------------- artifacts ----
  http.get(`${API}/projects/:projectId/artifacts`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const scope = url.searchParams.get('scope');
    const planId = url.searchParams.get('planId');

    if (scope === 'PLAN' || planId) {
      // Plan scope also returns project-scope artifacts, flagged inherited — the
      // inheritance has to be visible or people duplicate what they already have.
      const planScoped = db.artifacts.filter((a) => a.scope === 'PLAN');
      const inherited: Artifact[] = db.artifacts
        .filter((a) => a.scope === 'PROJECT')
        .map((a) => ({ ...a, inherited: true }));
      return HttpResponse.json([...planScoped, ...inherited]);
    }
    const rows = scope ? db.artifacts.filter((a) => a.scope === scope) : db.artifacts;
    return HttpResponse.json(rows);
  }),

  // ------------------------------------------------------------- reporting ----
  http.get(`${API}/projects/:projectId/dashboard`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);
    return HttpResponse.json({ ...reporting.dashboard, generatedAt: new Date().toISOString() });
  }),

  http.get(`${API}/projects/:projectId/reports`, async () => {
    await delay(LATENCY);
    return HttpResponse.json([]);
  }),

  http.post(`${API}/projects/:projectId/reports/generate`, async () => {
    await delay(600);
    return HttpResponse.json(
      {
        id: newId(),
        status: 'QUEUED',
        format: 'PDF',
        requestedAt: new Date().toISOString(),
        downloadUrl: null,
      },
      { status: 202 },
    );
  }),

  // ---------------------------------------------------------------- admin ----
  http.get(`${API}/users`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(users);
  }),

  http.get(`${API}/teams`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(teams);
  }),

  http.get(`${API}/roles`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(roles);
  }),

  http.get(`${API}/role-assignments`, async ({ request }) => {
    await delay(LATENCY);
    const projectId = new URL(request.url).searchParams.get('projectId');
    const rows = projectId ? roleAssignments.filter((a) => a.projectId === projectId) : roleAssignments;
    return HttpResponse.json(rows);
  }),

  // ------------------------------------------------------------ work items ----
  http.get(`${API}/work-items`, async ({ request }) => {
    await delay(LATENCY);
    const url = new URL(request.url);
    const projectId = url.searchParams.get('projectId') ?? projects[0].id;
    const project = projects.find((p) => p.id === projectId) ?? projects[0];
    const mine = url.searchParams.get('mine') === 'true';

    let items = workItems(project.id, project.key);
    if (mine) items = items.filter((w) => w.assignee?.id === currentUser.id);
    return HttpResponse.json(items);
  }),

  http.post(`${API}/work-items`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as Record<string, unknown>;
    const title = String(body.title ?? '').trim();
    if (!title) {
      return HttpResponse.json(
        { type: 'about:blank', title: 'Validation failed', status: 400, detail: 'Check the highlighted fields.', errors: { title: ['Enter a title.'] } },
        { status: 400 },
      );
    }
    const project = projects.find((p) => p.id === body.projectId) ?? projects[0];
    const item: WorkItem = {
      id: newId(),
      derived: false,
      sourceType: null,
      sourceId: null,
      kind: 'TASK',
      title,
      description: (body.description as string) ?? null,
      status: (body.status as string) ?? 'To do',
      priority: (body.priority as WorkItem['priority']) ?? 'MEDIUM',
      dueDate: (body.dueDate as string) ?? null,
      overdue: false,
      assignee: userRef(0),
      projectId: project.id,
      projectKey: project.key,
      linkedKey: null,
      allowedTransitions: ['To do', 'In progress', 'In review', 'Done'],
    };
    return HttpResponse.json(addWorkItem(item), { status: 201 });
  }),

  http.patch(`${API}/work-items/:itemId`, async ({ params, request }) => {
    await delay(120);
    const body = (await request.json()) as { status?: string };
    const item = db.workItems.find((w) => w.id === params.itemId);

    // Derived cards are not stored, so their status can't be written here at all — it is
    // computed from the source. Moving one means acting on the source (plan §6l).
    if (!item) {
      return problem(
        409,
        "That card can't be moved here",
        'This card mirrors an execution or review. Update the execution or review itself and the card follows.',
      );
    }
    if (body.status) item.status = body.status;
    return HttpResponse.json(item);
  }),
];
