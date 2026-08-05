/** Test plans and the plan statistics report. */
import { HttpResponse, delay, http } from 'msw';
import type { PlanSectionInput, PlanStatus, TestPlan } from '../../api/types';
import {
  allocatePlanKey,
  db,
  newId,
  planScopeCaseIds,
  reporting,
  reviewMoved,
  sectionCaseCount,
} from '../db';
import type { ReviewStanding } from '../db';
import { API, LATENCY, holdsOn, meRef, notFound, problem } from './shared';

/** The server's `PlanLifecycle.permissionFor`, in the mock's own words. */
function planMovePermission(current: PlanStatus, target: PlanStatus): string {
  if (target === 'APPROVED') return 'plan:approve';
  const rank: Record<PlanStatus, number> = { DRAFT: 0, IN_REVIEW: 1, APPROVED: 2 };
  // Taking an approval back — the arm a target-keyed rule misses.
  return rank[target] < rank[current] && rank[current] >= rank.APPROVED ? 'plan:approve' : 'plan:edit';
}

/**
 * Where a plan's status sits relative to review — the server's `PlanLifecycle.standingOf`.
 *
 * Total and unremarkable since §97 (M7) took `ACTIVE` and `CLOSED` away: with three states the plan
 * ladder maps one-to-one onto the review standings.
 */
function planStandingOf(status: PlanStatus): ReviewStanding {
  if (status === 'IN_REVIEW') return 'IN_REVIEW';
  if (status === 'APPROVED') return 'APPROVED';
  return 'OUTSIDE_REVIEW';
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
    case 'DRAFT':
      return null;
  }
}

/**
 * A stored plan as the API serves it — **every count resolved at read time, never at write time.**
 *
 * This is the whole of D7′ in the mock. A suite arm, a tag arm and a section are *selectors*: the
 * plan gains a case when somebody tags one, with nothing written to the plan. A `caseCount` computed
 * on the last `PATCH` and stored on the row cannot do that — it answers whatever was true when the
 * plan was last saved, and the screen built against it would be built against a number the server
 * does not produce (§81's finding, and §87's).
 *
 * One presenter for the list, the detail and both write responses, for `PlanService.view`'s reason:
 * a created plan rendered by a different path shows one thing on create and another on the reload
 * that follows it, with neither screen revealing which is stored.
 *
 * <h3>Criteria come back UNEVALUATED, and that is the server's behaviour rather than a shortcut</h3>
 *
 * `PlanCriteria.parse` — the only path `PlanService.view` uses, for the list and the detail alike —
 * builds every `CriterionView` with `met` and `actual` **null**, and its own comment says so
 * ("Never evaluated in this slice"). `PlanCriteria.evaluate` fills them, and it runs on **one**
 * endpoint: `/statistics`. Resolving a plan's gates means resolving its results, so a list that did
 * it per row would be the statistics endpoint run once per plan.
 *
 * The fixtures nonetheless store `met: false` / `actual: 89.1` on the plan rows, and until §100
 * `present` handed those straight back. Mock mode therefore showed a plan list with red and green
 * gates while every real deployment showed grey ones — §81's class exactly, and the specific trap
 * `lib/results.test.ts` already has a comment about: **the mock always supplied a boolean, so the
 * null branch was unreachable and the defect survived every gate and every browser pass.**
 *
 * The fixture values are left in place rather than nulled at the source, because `/statistics`
 * reads the same rows and is the one endpoint that legitimately serves them evaluated.
 */
function present(plan: TestPlan): TestPlan {
  const unevaluated = (criteria: TestPlan['exitCriteria']) =>
    (criteria ?? []).map((criterion) => ({ ...criterion, met: null, actual: null }));

  return {
    ...plan,
    caseCount: planScopeCaseIds(plan).length,
    entryCriteria: unevaluated(plan.entryCriteria),
    exitCriteria: unevaluated(plan.exitCriteria),
    sections: (plan.sections ?? []).map((section) => ({
      ...section,
      caseCount: sectionCaseCount(section),
    })),
  };
}

export const planHandlers = [
  http.get(`${API}/projects/:projectId/plans`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(db.plans.map(present));
  }),

  /**
   * Creating a plan. Three properties the mock has to share with the server, or mock mode proves
   * something live mode does not have — which is the class of divergence §29 and §38 each found:
   *
   *  1. **The same refusal**, from the same `permissionsOn` the affordance's `PermissionGate`
   *     reads, so the disabled button and the refused write cannot disagree.
   *  2. **`Idempotency-Key` honoured.** The hook sends one per mounted dialog; a mock that
   *     ignored it would let a retry make a second plan here and not on the server.
   *  3. **The scope is on the row**, so `caseCount` expands the folders and a run started from
   *     this plan has a checklist. See `planScopeCaseIds`, which resolves every arm off the plan.
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
      if (already) return HttpResponse.json(present(already), { status: 200 });
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

    const plan: TestPlan = {
      id,
      key,
      name,
      status: 'DRAFT',
      milestoneName: (body.milestoneName as string) ?? null,
      milestoneDate: (body.milestoneDate as string) ?? null,
      // Filled from the resolved scope below, once the row exists to resolve. The expansion, not
      // the number of scope items — a plan that says "40 cases" for 27 is §32's defect one entity
      // over.
      caseCount: 0,
      passRate: null,
      coverage: null,
      description: (body.description as string) ?? null,
      entryCriteria: [],
      exitCriteria: [],
      configurations: db.configurations.filter((c) => c.isDefault),
      suiteIds,
      // The API accepts individually-named cases and no dialog offers them, exactly as live.
      caseIds: [],
      // §97: a plan is created before anybody can say what is out of scope for it, so the prose
      // and the sections arrive on the edit — the same argument entry/exit criteria already make.
      tags: [],
      sections: [],
      scopeText: null,
      outOfScopeText: null,
      currentVersionNo: 1,
      lockVersion: 1,
    };
    db.plans.unshift(plan);
    if (idempotencyKey) db.planIdempotency.set(idempotencyKey, plan);
    return HttpResponse.json(present(plan), {
      status: 201,
      headers: { Location: `${API}/projects/${projectId}/plans/${id}` },
    });
  }),

  http.get(`${API}/projects/:projectId/plans/:planId`, async ({ params }) => {
    await delay(LATENCY);
    const plan = db.plans.find((p) => p.id === params.planId);
    return plan ? HttpResponse.json(present(plan)) : notFound('That test plan');
  }),

  /**
   * Editing a plan. Four properties shared with the server, and the last two are the interesting
   * ones — each is a rule that looks like an implementation detail and is really the endpoint:
   *
   *  1. **The permission first**, from the same `permissionsOn` the affordance reads.
   *  2. **A closed plan is refused with 409**, and the refusal comes *after* the permission and
   *     *before* the `If-Match`, which is the order the server checks them in. A mock that put the
   *     freeze first would hand a Viewer a sentence about the plan's state.
   *  3. **An omitted list means "leave it alone"; `[]` clears it.** Read the other way, renaming a
   *     plan through this handler would empty its scope — and mock mode would then teach a
   *     behaviour live mode does not have, which is §52's "mock agrees with itself" class.
   *  4. **A version is written only when the SCOPE moves.** `currentVersionNo` is on the wire, so a
   *     mock that bumped it on every save would show a number the server would not.
   */
  http.patch(`${API}/projects/:projectId/plans/:planId`, async ({ params, request }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);
    const plan = db.plans.find((p) => p.id === params.planId);
    if (!plan) return notFound('That test plan');

    if (!holdsOn(projectId, 'plan:edit')) {
      return problem(
        403,
        'Not allowed',
        'You do not have permission to edit test plans in this project.',
      );
    }

    const ifMatch = request.headers.get('If-Match');
    if (ifMatch !== null && Number(ifMatch.replace(/"/g, '')) !== plan.lockVersion) {
      return problem(
        409,
        'Someone else saved first',
        'Reload the plan to see the newer version, then reapply your change.',
      );
    }

    const body = (await request.json()) as Record<string, unknown>;
    const name = String(body.name ?? '').trim();
    if (!name) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        name: ['Enter a plan name.'],
      });
    }

    const submittedSuites = body.suiteIds as string[] | undefined;
    if (submittedSuites?.some((id) => !db.suites.some((suite) => suite.id === id))) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        suiteIds: ['One of the selected suites is not in this project any more.'],
      });
    }

    // The same closed vocabulary the server validates against, so a metric the editor could not
    // offer is refused in both modes rather than only in one.
    const metrics = ['pass_rate', 'coverage', 'open_critical', 'cases_approved', 'build_available'];
    for (const field of ['entryCriteria', 'exitCriteria'] as const) {
      const submitted = body[field] as { metric?: string; value?: number }[] | undefined;
      const bad = submitted?.find((c) => !c.metric || !metrics.includes(c.metric));
      if (bad) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          [field]: [
            `'${bad.metric}' is not a metric a plan can be gated on. Valid metrics: `
              + `${[...metrics].sort().join(', ')}.`,
          ],
        });
      }
    }

    // The server hashes a PlanSnapshot; this is the same fields compared by value. Comparing
    // by *presence* instead — "did the request mention a milestone" — would bump the version on a
    // save that resubmitted the milestone unchanged, which is what the SPA's own dialog does every
    // time, so mock mode would count versions live mode does not.
    //
    // §97 puts the tags and the sections in it and deliberately leaves the two PROSE fields out:
    // scope text is what the plan says it will test, the scope items are what it will run, and
    // only the second is the thing a version pins. Wording the intent more carefully must not
    // count as a change of scope — the call `suite_version`'s schema comment already makes about
    // a name.
    const snapshot = () =>
      JSON.stringify([
        plan.milestoneName,
        plan.milestoneDate,
        plan.entryCriteria,
        plan.exitCriteria,
        [...(plan.suiteIds ?? [])].sort(),
        [...(plan.caseIds ?? [])].sort(),
        [...(plan.tags ?? [])].sort(),
        plan.sections,
      ]);
    const before = snapshot();

    plan.name = name;
    if ('description' in body) plan.description = (body.description as string) ?? null;
    if ('milestoneName' in body) plan.milestoneName = (body.milestoneName as string) ?? null;
    if ('milestoneDate' in body) plan.milestoneDate = (body.milestoneDate as string) ?? null;
    if (submittedSuites) plan.suiteIds = [...new Set(submittedSuites)].sort();
    if (body.caseIds) plan.caseIds = [...new Set(body.caseIds as string[])].sort();
    if (body.tags) plan.tags = [...new Set(body.tags as string[])].sort();
    if (body.entryCriteria) plan.entryCriteria = body.entryCriteria as TestPlan['entryCriteria'];
    if (body.exitCriteria) plan.exitCriteria = body.exitCriteria as TestPlan['exitCriteria'];
    // Absent leaves the prose alone; `null` clears it. The pair `'x' in body` distinguishes, which
    // `body.x ?? plan.x` would not — and a save that omitted a field it did not edit would empty
    // it, which is §82's own finding about the settings form arriving here.
    if ('scopeText' in body) plan.scopeText = (body.scopeText as string) ?? null;
    if ('outOfScopeText' in body) plan.outOfScopeText = (body.outOfScopeText as string) ?? null;

    // The WHOLE section list is replaced when it is present — sections carry no ids, so there is
    // nothing to match a partial update against, and `position` is assigned from the order sent
    // rather than trusted from the client (two sections claiming position 0 is a render order the
    // author cannot fix from the screen).
    if (body.sections) {
      plan.sections = (body.sections as PlanSectionInput[]).map((section, position) => {
        const arms = {
          caseIds: [...new Set(section.caseIds ?? [])],
          suiteIds: [...new Set(section.suiteIds ?? [])],
          tags: [...new Set(section.tags ?? [])],
        };
        return {
          name: String(section.name ?? '').trim(),
          position,
          ...arms,
          // Stored as zero and never read: `present` resolves it on the way out. A count written
          // here would be the one the row keeps, and a section defined by a tag would report what
          // it caught at save time for ever.
          caseCount: 0,
        };
      });
    }

    // A name, a description and the scope prose are content, not scope, so they are outside the
    // snapshot above and renaming a plan writes no version.
    if (snapshot() !== before) plan.currentVersionNo += 1;
    plan.lockVersion += 1;

    return HttpResponse.json(present(plan));
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
    const known: PlanStatus[] = ['DRAFT', 'IN_REVIEW', 'APPROVED'];

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
        status: ['Choose a status: DRAFT, IN_REVIEW or APPROVED.'],
      });
    }

    if (target === plan.status) return HttpResponse.json(present(plan));

    const refusal = planMoveRefusal(plan.status, target);
    if (refusal) return problem(409, 'Not possible right now', refusal);

    /*
      §6g's gating rule for plans (§82) was applied here, on the one edge `DRAFT → ACTIVE`. It went
      with that edge in §97 (M7), along with `PlanStatusInput.overrideApprovalGate`. §6g's *case*
      gate is untouched and still lives on the run-create handler.
    */

    plan.status = target;
    // The review record moves with the status, inside the same handler the server writes it in the
    // same transaction — see `db/reviews.ts` for the three rules and why nothing else writes rows.
    reviewMoved(
      projectId,
      'PLAN',
      plan.id,
      plan.currentVersionNo,
      planStandingOf(target),
      meRef(),
    );
    return HttpResponse.json(present(plan));
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
