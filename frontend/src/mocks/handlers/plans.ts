/** Test plans and the plan statistics report. */
import { HttpResponse, delay, http } from 'msw';
import type { TestPlan } from '../../api/types';
import { allocatePlanKey, db, newId, planScopeCaseIds, reporting } from '../db';
import { API, LATENCY, holdsOn, notFound, problem } from './shared';

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
