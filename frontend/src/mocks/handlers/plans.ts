/** Test plans and the plan statistics report. */
import { HttpResponse, delay, http } from 'msw';
import type { TestPlan } from '../../api/types';
import { allocatePlanKey, db, newId, reporting } from '../db';
import { API, LATENCY, notFound } from './shared';

export const planHandlers = [
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
];
