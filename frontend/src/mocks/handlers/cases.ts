/**
 * The case list and everything under it — filtering, sorting, faceting, the four writes, the
 * version list and the execution history.
 *
 * The filter/sort/facet helpers live here rather than in `shared.ts` because they are about
 * `TestCaseSummary` specifically; nothing else in the mock sorts by `keySeq` or counts health
 * signals.
 */
import { HttpResponse, delay, http } from 'msw';
import type {
  BulkResult,
  Facet,
  LifecycleStatus,
  TestCase,
  TestCaseSummary,
  TestStep,
} from '../../api/types';
import {
  allocateCaseKey,
  caseDetail,
  caseVersions,
  db,
  descendantSuiteIds,
  newId,
  recordCaseVersion,
  reporting,
  writeCase,
} from '../db';
import { currentUser } from '../fixtures/core';
import {
  API,
  LATENCY,
  notFound,
  paging,
  holdsOn,
  permissionsOn,
  problem,
  projectMembers,
  qualifierOn,
  requireAuth,
  userRef,
} from './shared';

// ------------------------------------------------------------ classification ----

/**
 * Applies a submitted classification to a case, or returns the message that refuses it.
 *
 * The mock enforces the server's rules rather than merely storing what it is given. A mock that
 * accepted anything would let mock mode save a payload live mode answers 400 to — the divergence
 * the refused-fields list beside it exists to prevent, and the one that let `lockVersion` sit in a
 * PATCH body for months because no server read it either.
 *
 * Three rules, matching `CaseEdit.normalizeClassification`:
 *
 *  - every id must name a value in this project's vocabulary — the mock has one project's
 *    dimensions, so "not in `dimensions`" stands in for retired, another project's, and
 *    nonexistent alike, which is also exactly how the server refuses them: one message for all
 *    three, so the response cannot be used to probe which ids exist;
 *  - at most one value per SINGLE dimension;
 *  - duplicates collapse rather than being refused.
 *
 * It also maintains the DERIVED name fields, because the fixture store is what every read comes
 * from: a case whose `classificationValueIds` said one thing while `features` said another would
 * be mock mode reproducing, locally, the exact inconsistency the server's one-filter rule exists
 * to make impossible.
 */
function applyClassification(target: TestCase, submitted: unknown): string | null {
  if (!Array.isArray(submitted)) {
    return 'Send an array of classification value ids.';
  }
  const ids = [...new Set(submitted.filter((id): id is string => typeof id === 'string'))];

  const dimensionOf = new Map<string, (typeof db.dimensions)[number]>();
  for (const dimension of db.dimensions) {
    for (const value of dimension.values) dimensionOf.set(value.id, dimension);
  }

  const singleTaken = new Set<string>();
  for (const id of ids) {
    const dimension = dimensionOf.get(id);
    if (!dimension) return 'That value is not one this project offers.';
    if (dimension.cardinality === 'SINGLE') {
      if (singleTaken.has(dimension.id)) {
        return `Choose one ${dimension.name}, not several.`;
      }
      singleTaken.add(dimension.id);
    }
  }

  const nameOn = (key: string) =>
    ids
      .filter((id) => dimensionOf.get(id)?.key === key)
      .map((id) => dimensionOf.get(id)?.values.find((value) => value.id === id)?.name ?? '')
      .filter(Boolean)
      .sort();

  target.classificationValueIds = [...ids].sort();
  target.testType = nameOn('test_type')[0] ?? null;
  target.testLevel = nameOn('test_level')[0] ?? null;
  target.features = nameOn('feature');
  return null;
}

// ----------------------------------------------------------------- filtering ----

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
    const value = db.dimensions
      .find((d) => d.key === 'feature')
      ?.values.find((v) => v.id === featureId);
    if (value) rows = rows.filter((c) => c.features.includes(value.name));
  }

  const typeId = p.get('testTypeId');
  if (typeId) {
    const value = db.dimensions.find((d) => d.key === 'test_type')?.values.find((v) => v.id === typeId);
    if (value) rows = rows.filter((c) => c.testType === value.name);
  }

  const levelId = p.get('testLevelId');
  if (levelId) {
    const value = db.dimensions.find((d) => d.key === 'test_level')?.values.find((v) => v.id === levelId);
    if (value) rows = rows.filter((c) => c.testLevel === value.name);
  }

  // The dashboard's by-owner segments link here carrying this. Without it the mock drill-down
  // silently opens everyone's cases while the live one narrows correctly — a mock-vs-live
  // divergence that would make the browser pass disagree with the test suite.
  const ownerId = p.get('ownerId');
  if (ownerId) rows = rows.filter((c) => c.owner?.id === ownerId);

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

/**
 * Which permission a bulk action needs, and the sentence to refuse it with.
 *
 * Mirrors `BulkOperations.permission` and `BulkOperations.refusal`: only the *target status* can
 * raise the requirement above `case:edit`, and the two refusals give opposite advice because a Test
 * Engineer lacks only approval while a Viewer lacks everything.
 *
 * @returns the refusal sentence, or null when the caller may proceed
 */
function missingPermission(
  action: string | undefined,
  target: LifecycleStatus | undefined,
  held: string[],
): string | null {
  const mayEdit = held.includes('case:edit');
  const needsApproval = (action === 'SET_STATUS' || action === 'SUBMIT_FOR_REVIEW')
    && target === 'APPROVED';

  if (needsApproval && !held.includes('case:approve')) {
    return mayEdit
      ? 'Only a test lead can approve cases. Submit them for review instead.'
      : 'You do not have permission to change cases in this project.';
  }
  if (!mayEdit) {
    return 'You do not have permission to change cases in this project.';
  }
  return null;
}

/**
 * The per-action argument requirement, as field errors — `BulkOperations.parse`'s half.
 *
 * Checked before the loop, like the server: one wrong dropdown must produce one message on that
 * control, not five hundred identical skip reasons that bury it.
 */
function bulkArgumentError(
  action: string | undefined,
  payload: Record<string, unknown>,
  target: LifecycleStatus | undefined,
): Record<string, string[]> | null {
  switch (action) {
    case 'SET_STATUS':
      return target ? null : { lifecycleStatus: ['Choose a status.'] };
    case 'SUBMIT_FOR_REVIEW':
      // A contradicting status would make the request apply as something other than it says.
      return payload.lifecycleStatus && payload.lifecycleStatus !== 'IN_REVIEW'
        ? {
            lifecycleStatus: [
              'SUBMIT_FOR_REVIEW always moves cases to In review. '
                + 'Use SET_STATUS to choose a different status.',
            ],
          }
        : null;
    case 'SET_AUTOMATION_STATUS':
      if (!payload.automationStatus) return { automationStatus: ['Choose an automation status.'] };
      return payload.automationStatus === 'NOT_AUTOMATABLE'
        && !String(payload.notAutomatableReason ?? '').trim()
        ? { notAutomatableReason: ['Say why these cases cannot be automated.'] }
        : null;
    case 'MOVE_TO_SUITE':
      if (!payload.suiteId) return { suiteId: ['Choose a suite to move these cases into.'] };
      return db.suites.some((s) => s.id === payload.suiteId)
        ? null
        : { suiteId: ['That suite is not in this project.'] };
    case 'ADD_TAGS':
      return ((payload.tags as string[]) ?? []).some((t) => t.trim())
        ? null
        : { tags: ['Type at least one tag.'] };
    case 'ATTACH_CONDITIONS':
    case 'DETACH_CONDITIONS':
      return payload.conditionId ? null : { conditionId: ['Choose a condition.'] };
    // SET_OWNER needs nothing: a null ownerId is the unassign, not a missing argument.
    default:
      return null;
  }
}

export const caseHandlers = [
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
      // And they wrote it, which is a distinct claim from owning it — the one `case:delete`'s OWN
      // qualifier reads. It follows that a case you just created is one you can always delete,
      // which is the behaviour the server has too (`TestCase.create` sets `created_by`).
      createdBy: userRef(0),
      // Set below by applyClassification, from `classificationValueIds`. Left null/empty here
      // rather than read off the body: `testType`/`testLevel`/`features` are RESPONSE fields —
      // names the server derives from the assignments — and are not on `TestCaseInput` at all, so
      // a create that trusted them would be inventing a shape no client can send.
      classificationValueIds: [],
      testType: null,
      testLevel: null,
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

    if (body.classificationValueIds !== undefined) {
      const refusal = applyClassification(created, body.classificationValueIds);
      if (refusal) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          classificationValueIds: [refusal],
        });
      }
    }

    const saved = writeCase(created);
    /*
      v1, written with the case.

      A created case must have NO gap: `CaseVersions.initial` on the server exists precisely because
      deriving the number from the counter would give a fresh case v2 and leave its v1 permanently
      absent — indistinguishable from the historical accident that afflicts every case older than
      the write path. Recorded here too, or mock mode would show the created case an empty history
      while live mode shows it v1.
    */
    recordCaseVersion(saved.id, {
      versionNo: 1,
      createdBy: userRef(0),
      createdAt: saved.updatedAt,
      changeComment: typeof body.changeComment === 'string' && body.changeComment.trim()
        ? body.changeComment.trim()
        : null,
      pinned: false,
    });
    // Recorded only after the write succeeded — a key stored before a failure would make the
    // retry return a record that was never created.
    if (idempotencyKey) db.idempotency.set(idempotencyKey, saved);
    return HttpResponse.json(saved, { status: 201 });
  }),

  /**
   * Preview-then-commit bulk operations, in agreement with `CaseBulkService`.
   *
   * Two defects were fixed here when the server landed, and both are the "mock agrees with itself"
   * class §52 names — each was invisible while this handler was the only implementation:
   *
   *  1. **Ids that resolved to nothing were DROPPED** (`if (!row) continue`), so `total` counted 44
   *     while the three buckets accounted for 42 and the user was told nothing about the other two.
   *     Every requested id now lands in exactly one bucket.
   *  2. **Six of the eight actions answered "not supported yet"** while the contract declared them.
   *
   * It also enforces `case:approve`, from the same `permissionsOn` the mock's own
   * `GET /me/permissions` answers — otherwise the dialog would enable the option in mock mode and
   * the write would succeed, and the gate would only ever be seen in live mode.
   */
  http.post(`${API}/projects/:projectId/cases/bulk`, async ({ request, params }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);
    const body = (await request.json()) as {
      caseIds?: string[];
      action?: string;
      dryRun?: boolean;
      payload?: Record<string, unknown>;
    };

    // Absent means preview, matching the contract's default — the safe direction.
    const dryRun = body.dryRun ?? true;
    const payload = body.payload ?? {};
    const action = body.action;

    // De-duplicated, in the caller's order, exactly as the server does it.
    const ids = [...new Set(body.caseIds ?? [])];
    if (ids.length === 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        caseIds: ['Select at least one case.'],
      });
    }
    if (ids.length > 500) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        caseIds: [`Select at most 500 cases at a time. This selection has ${ids.length}.`],
      });
    }

    // SUBMIT_FOR_REVIEW is SET_STATUS(IN_REVIEW) — aliased here as it is on the server, so there is
    // one implementation of the state change on this side too.
    const target =
      action === 'SUBMIT_FOR_REVIEW'
        ? ('IN_REVIEW' as LifecycleStatus)
        : (payload.lifecycleStatus as LifecycleStatus | undefined);

    // Keys only — this gate is "is it held", and `missingPermission` compares against a list of
    // keys. The qualifier is not consulted because no bulk action is qualified; the day one is,
    // that function needs the pair rather than a set membership test.
    const held = permissionsOn(projectId).map((entry) => entry.key);
    const missing = missingPermission(action, target, held);
    if (missing) {
      return problem(403, 'Not allowed', missing);
    }

    const invalid = bulkArgumentError(action, payload, target);
    if (invalid) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', invalid);
    }

    const changed: string[] = [];
    const unchanged: string[] = [];
    const skipped: { key: string; reason: string }[] = [];

    for (const id of ids) {
      const row = db.cases.find((c) => c.id === id);
      if (!row) {
        // NEVER dropped. See the handler comment — this is the accounting defect.
        skipped.push({ key: id, reason: 'This case is no longer in this project.' });
        continue;
      }
      const detail = db.caseDetails.get(id);

      if (action === 'SET_STATUS' || action === 'SUBMIT_FOR_REVIEW') {
        if (row.lifecycleStatus === target) {
          unchanged.push(row.key);
        } else if (target === 'APPROVED' && row.lifecycleStatus !== 'IN_REVIEW') {
          // §6g: APPROVED is reachable only from IN_REVIEW. The wording is the server's, word for
          // word — the same failure must not read as two different problems in the two modes.
          skipped.push({ key: row.key, reason: 'A draft must be reviewed before it can be approved.' });
        } else {
          if (!dryRun) {
            row.lifecycleStatus = target as LifecycleStatus;
            if (detail) detail.lifecycleStatus = target as LifecycleStatus;
          }
          changed.push(row.key);
        }
      } else if (action === 'SET_AUTOMATION_STATUS') {
        const next = payload.automationStatus as TestCaseSummary['automationStatus'];
        if (row.automationStatus === next) {
          unchanged.push(row.key);
        } else {
          if (!dryRun) {
            row.automationStatus = next;
            if (detail) detail.automationStatus = next;
          }
          changed.push(row.key);
        }
      } else if (action === 'SET_OWNER') {
        const next = (payload.ownerId as string | null) ?? null;
        if ((row.owner?.id ?? null) === next) {
          unchanged.push(row.key);
        } else {
          if (!dryRun) {
            const owner = next === null ? null : projectMembers(projectId).find((m) => m.id === next) ?? null;
            row.owner = owner;
            if (detail) detail.owner = owner;
          }
          changed.push(row.key);
        }
      } else if (action === 'MOVE_TO_SUITE') {
        const next = payload.suiteId as string;
        if (row.suiteId === next) {
          unchanged.push(row.key);
        } else {
          if (!dryRun) {
            row.suiteId = next;
            if (detail) detail.suiteId = next;
          }
          changed.push(row.key);
        }
      } else if (action === 'ADD_TAGS') {
        const next = (payload.tags as string[]) ?? [];
        const existing = row.tags ?? [];
        if (next.every((tag) => existing.includes(tag))) {
          unchanged.push(row.key);
        } else {
          if (!dryRun) {
            row.tags = [...new Set([...existing, ...next])];
            if (detail) detail.tags = row.tags;
          }
          changed.push(row.key);
        }
      } else if (action === 'ATTACH_CONDITIONS') {
        const condition = db.conditions.find((c) => c.id === payload.conditionId);
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
      } else if (action === 'DETACH_CONDITIONS') {
        const attached = detail?.preconditions?.some((p) => p.conditionId === payload.conditionId);
        if (!attached) {
          unchanged.push(row.key);
        } else {
          if (!dryRun && detail) {
            detail.preconditions = (detail.preconditions ?? []).filter(
              (p) => p.conditionId !== payload.conditionId,
            );
          }
          changed.push(row.key);
        }
      } else {
        skipped.push({ key: row.key, reason: 'That operation is not supported yet.' });
      }
    }

    const result: BulkResult = {
      dryRun,
      changed,
      unchanged,
      skipped,
      total: ids.length,
      // One audit event for the whole operation, and none at all when nothing changed.
      auditEventId: dryRun || changed.length === 0 ? null : newId(),
    };
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

    // The five fields on `TestCaseInput` the real endpoint has no write path for are REFUSED, not
    // ignored — accepting and dropping them is a save that reports success and discards what the
    // user typed. Mirrored here so mock mode cannot pass a payload live mode rejects.
    //
    // `classificationValueIds` LEFT this list when the server grew a write path for it. It is now
    // applied below, with the same three rules the server enforces, because a mock that merely
    // stopped refusing would let mock mode accept a sibling project's value that live mode rejects
    // — the divergence this comment exists to prevent, arriving through the other door.
    const unsupported = [
      'ownerId',
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

    const classificationChanged = body.classificationValueIds !== undefined;
    if (classificationChanged) {
      const refusal = applyClassification(next, body.classificationValueIds);
      if (refusal) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          classificationValueIds: [refusal],
        });
      }
    }

    // A version is written on explicit save only, and skipped when nothing changed.
    const substantive =
      writable.some((f) => body[f] !== undefined)
      || Array.isArray(body.steps)
      || classificationChanged;
    if (substantive) next.currentVersionNo = (next.currentVersionNo ?? 1) + 1;
    next.lockVersion = (next.lockVersion ?? 1) + 1;

    if (substantive) {
      // The counter and the history row are advanced together, because a case whose detail page
      // reads v8 beside a version list whose newest entry is v7 is two screens of one case
      // disagreeing — and neither of them says which is right. The server writes both in one
      // transaction for exactly this reason.
      //
      // `changeComment` is carried from the payload rather than invented: it is the sentence the
      // panel displays, the editor has a field for it, and a comment that silently failed to save
      // is a note its author believes they left.
      recordCaseVersion(next.id, {
        versionNo: next.currentVersionNo ?? 1,
        createdBy: userRef(0),
        createdAt: new Date().toISOString(),
        changeComment: typeof body.changeComment === 'string' && body.changeComment.trim()
          ? body.changeComment.trim()
          : null,
        // Nothing has executed this version yet — it was made a moment ago.
        pinned: false,
      });
    }

    return HttpResponse.json(writeCase(next));
  }),

  /*
    The delete, and the one refusal this mock can honestly reproduce.

    A caller whose role does not hold `case:delete` gets the server's 403 — which matters because
    the SPA's gate reads the SAME source (`permissionsOn`), so a mock that accepted the write would
    let a disabled button and a successful request coexist.

    ~~NOT modelled: the `OWN` refusal.~~ → modelled now. It was deferred because the qualifier was
    not on the wire (§65) and the case's author was not either, so the only fact available here was
    `owner` — and refusing on `owner` would refuse on a DIFFERENT fact than the server uses, which
    teaches the wrong rule more convincingly than not modelling it at all. Both halves landed, so
    the refusal below reads `createdBy` exactly as `CaseDeleteService` reads `created_by`.

    This is the half that keeps the mock from agreeing with itself: the SPA's Delete button asks
    `Gate.canOnAuthored` and this handler asks `qualifierOn`, both over the same seeded catalogue,
    so a disabled button and an accepted write cannot coexist in mock mode.
  */
  http.delete(`${API}/projects/:projectId/cases/:caseId`, async ({ params, request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    if (!holdsOn(String(params.projectId), 'case:delete')) {
      return problem(
        403,
        'Forbidden',
        'You do not have permission to delete test cases in this project.',
      );
    }

    const id = String(params.caseId);
    const index = db.cases.findIndex((c) => c.id === id);
    if (index < 0) return notFound('That test case');

    // AFTER the lookup and in this order, mirroring CaseDeleteService: the answer depends on the
    // row, and a caller who fails this check has already read the case, so 403 conceals nothing a
    // 404 would. An unauthored case (`createdBy: null`) fails it too — an OWN grant does not reach
    // work nobody can attribute.
    const author = db.cases[index].createdBy?.id ?? null;
    if (qualifierOn(String(params.projectId), 'case:delete') === 'OWN'
        && author !== currentUser.id) {
      return problem(
        403,
        'Forbidden',
        'You can only delete test cases you created. Ask a test lead to delete this one.',
      );
    }

    // Removed from the store rather than flagged: the mock has no soft-delete column, and every
    // read here already goes through `db.cases`. What the server keeps — the row, for the runs
    // that pinned a version — has no reader in mock mode.
    db.cases.splice(index, 1);
    db.caseDetails.delete(id);
    return new HttpResponse(null, { status: 204 });
  }),

  /*
    The version list — what the case detail's history panel reads.

    An empty array is a 200 and NOT a 404. A case authored before the write path existed has no
    version rows at all, which is an ordinary state of a perfectly readable case; a 404 there would
    tell the reader their case had been deleted, on most of the cases in an installation being
    migrated. The server draws the same line for the same reason (`CaseVersionService`).

    Read from `db.caseVersions` rather than derived from `currentVersionNo`, so a save the user just
    made appears here carrying the comment they typed — see the store's field header for what
    deriving it from the counter costs.
  */
  http.get(`${API}/projects/:projectId/cases/:caseId/versions`, async ({ params }) => {
    await delay(LATENCY);
    const versions = caseVersions(String(params.caseId));
    return versions ? HttpResponse.json(versions) : notFound('That test case');
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
];
