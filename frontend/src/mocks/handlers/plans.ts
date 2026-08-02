/** Test plans and the plan statistics report. */
import { HttpResponse, delay, http } from 'msw';
import type { PlanStatus, TestPlan } from '../../api/types';
import { allocatePlanKey, db, newId, planScopeCaseIds, reporting } from '../db';
import { API, LATENCY, holdsOn, notFound, problem } from './shared';

/** The server's `PlanLifecycle.permissionFor`, in the mock's own words. */
function planMovePermission(current: PlanStatus, target: PlanStatus): string {
  if (target === 'APPROVED' || target === 'CLOSED') return 'plan:approve';
  const rank: Record<PlanStatus, number> = {
    DRAFT: 0,
    IN_REVIEW: 1,
    APPROVED: 2,
    ACTIVE: 3,
    CLOSED: 4,
  };
  // Taking an approval, a start or a closure back — the arm a target-keyed rule misses.
  return rank[target] < rank[current] && rank[current] >= rank.APPROVED ? 'plan:approve' : 'plan:edit';
}

/** The server's transition table, refusal sentences included. `null` means the move is legal. */
function planMoveRefusal(current: PlanStatus, target: PlanStatus): string | null {
  switch (target) {
    case 'IN_REVIEW':
      return current === 'DRAFT'
        ? null
        : 'Only a draft plan can be submitted for review. Return this plan to draft first.';
    case 'APPROVED':
      return current === 'IN_REVIEW'
        ? null
        : 'A plan is approved after it has been reviewed. Submit it for review first.';
    case 'ACTIVE':
      return current === 'IN_REVIEW'
        ? 'This plan is still in review. Approve it, or return it to draft, before starting it.'
        : null;
    case 'CLOSED':
      return current === 'ACTIVE'
        ? null
        : 'Only a plan that is being executed can be closed. Start the plan, or delete it if it '
          + 'was never used.';
    case 'DRAFT':
      return current === 'CLOSED'
        ? 'This plan has been closed. Reopen it before returning it to draft.'
        : null;
  }
}

export const planHandlers = [
  http.get(`${API}/projects/:projectId/plans`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(db.plans);
  }),

  /**
   * Creating a plan. Three properties the mock has to share with the server, or mock mode proves
   * something live mode does not have — which is the class of divergence §29 and §38 each found:
   *
   *  1. **The same refusal**, from the same `permissionsOn` the affordance's `PermissionGate`
   *     reads, so the disabled button and the refused write cannot disagree.
   *  2. **`Idempotency-Key` honoured.** The hook sends one per mounted dialog; a mock that
   *     ignored it would let a retry make a second plan here and not on the server.
   *  3. **The scope remembered**, so `caseCount` expands the folders and a run started from this
   *     plan has a checklist. See `db.planScope`.
   */
  http.post(`${API}/projects/:projectId/plans`, async ({ params, request }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);

    if (!holdsOn(projectId, 'plan:create')) {
      return problem(
        403,
        'Not allowed',
        'You do not have permission to create test plans in this project.',
      );
    }

    const idempotencyKey = request.headers.get('Idempotency-Key');
    if (idempotencyKey) {
      const already = db.planIdempotency.get(idempotencyKey);
      // 200 rather than 201, and before validation: the client is asking whether its intent
      // landed, so a drifted retry answers the plan it already made.
      if (already) return HttpResponse.json(already, { status: 200 });
    }

    const body = (await request.json()) as Record<string, unknown>;
    const name = String(body.name ?? '').trim();
    if (!name) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        name: ['Enter a plan name.'],
      });
    }

    const suiteIds = ((body.suiteIds as string[] | undefined) ?? []).filter((id) =>
      db.suites.some((suite) => suite.id === id),
    );
    if (suiteIds.length !== ((body.suiteIds as string[] | undefined) ?? []).length) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        suiteIds: ['One of the selected suites is not in this project any more.'],
      });
    }

    const { key } = allocatePlanKey();
    const id = newId();
    db.planScope.set(id, suiteIds);

    const plan: TestPlan = {
      id,
      key,
      name,
      status: 'DRAFT',
      milestoneName: (body.milestoneName as string) ?? null,
      milestoneDate: (body.milestoneDate as string) ?? null,
      // The expansion, not the number of scope items — a plan that says "40 cases" for 27 is
      // §32's defect one entity over.
      caseCount: planScopeCaseIds(id).length,
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
    if (idempotencyKey) db.planIdempotency.set(idempotencyKey, plan);
    return HttpResponse.json(plan, {
      status: 201,
      headers: { Location: `${API}/projects/${projectId}/plans/${id}` },
    });
  }),

  http.get(`${API}/projects/:projectId/plans/:planId`, async ({ params }) => {
    await delay(LATENCY);
    const plan = db.plans.find((p) => p.id === params.planId);
    return plan ? HttpResponse.json(plan) : notFound('That test plan');
  }),

  /**
   * The ladder. Three properties shared with the server, in the server's ORDER — a mock that gets
   * the order backwards teaches a 409 where live gives a 403, which is the divergence class §72
   * named on the run's own status endpoint:
   *
   *  1. **The permission is read from the EDGE**, not from the target — so "Return to draft" on an
   *     approved plan asks `plan:approve` here exactly as it does live. A mock keyed on the target
   *     would enable the one control the server refuses.
   *  2. **The permission first, the ladder second.** A caller who may not move the plan is told 403
   *     rather than being handed a 409 describing a ladder they may not climb.
   *  3. **Same status in, 200 out, nothing changed** — no audit, no re-stamp.
   */
  http.put(`${API}/projects/:projectId/plans/:planId/status`, async ({ params, request }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);
    const plan = db.plans.find((p) => p.id === params.planId);
    if (!plan) return notFound('That test plan');

    const body = (await request.json()) as { status?: PlanStatus };
    const target = body.status;
    const known: PlanStatus[] = ['DRAFT', 'IN_REVIEW', 'APPROVED', 'ACTIVE', 'CLOSED'];

    // An unreadable status names no edge, so it falls back to the lower key — the server's rule,
    // and the reason a Viewer gets 403 rather than learning the vocabulary.
    const permission =
      target && known.includes(target) ? planMovePermission(plan.status, target) : 'plan:edit';
    if (!holdsOn(projectId, permission)) {
      return problem(
        403,
        'Not allowed',
        'You do not have permission to make this change to the plan status.',
      );
    }
    if (!target || !known.includes(target)) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        status: ['Choose a status: DRAFT, IN_REVIEW, APPROVED, ACTIVE or CLOSED.'],
      });
    }

    if (target === plan.status) return HttpResponse.json(plan);

    const refusal = planMoveRefusal(plan.status, target);
    if (refusal) return problem(409, 'Not possible right now', refusal);

    plan.status = target;
    return HttpResponse.json(plan);
  }),

  /** Refused while a run names the plan, with the count — the server's 409, same sentence shape. */
  http.delete(`${API}/projects/:projectId/plans/:planId`, async ({ params }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);
    if (!holdsOn(projectId, 'plan:delete')) {
      return problem(403, 'Not allowed', 'Deleting a test plan needs a test lead.');
    }

    const index = db.plans.findIndex((p) => p.id === params.planId);
    if (index < 0) return notFound('That test plan');

    const runs = db.runs.filter((run) => run.planId === db.plans[index].id).length;
    if (runs > 0) {
      return problem(
        409,
        'Not possible right now',
        `${runs} ${runs === 1 ? 'test run has' : 'test runs have'} been executed against this ` +
          `plan, so deleting it would leave ${runs === 1 ? 'that run' : 'those runs'} pointing at ` +
          'a plan nobody can open. Close the plan instead.',
      );
    }

    db.plans.splice(index, 1);
    return new HttpResponse(null, { status: 204 });
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
];
