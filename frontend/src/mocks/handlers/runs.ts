/** Runs, their executions, and the one write that records a result. */
import { HttpResponse, delay, http } from 'msw';
import type { TestRun } from '../../api/types';
import { allocateRunKey, db, newId, recountRun, runExecutions } from '../db';
import { API, LATENCY, notFound, problem, userRef } from './shared';

export const runHandlers = [
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
];
