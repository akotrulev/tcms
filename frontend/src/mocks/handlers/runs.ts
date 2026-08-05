/** Runs, their executions, and the one write that records a result. */
import { HttpResponse, delay, http } from 'msw';
import type { TestRun } from '../../api/types';
import {
  allocateRunKey,
  db,
  materialiseRun,
  newId,
  planScopeCaseIds,
  recountRun,
  runExecutions,
  settingsForProject,
} from '../db';
import { currentUser } from '../fixtures/core';
import { API, LATENCY, holdsOn, notFound, problem, qualifierOn, userRef } from './shared';

/** The lifecycle's four values, for validating a status the client sent. */
const RUN_STATUSES = ['NOT_STARTED', 'IN_PROGRESS', 'COMPLETED', 'ABORTED'] as const satisfies
  readonly TestRun['status'][];

/**
 * "Closed" is a status pair, not a fifth value — the server's `RunLifecycle.isClosed`. A run that
 * has ended refuses results and evidence until somebody reopens it.
 */
const isClosedStatus = (status: TestRun['status']) =>
  status === 'COMPLETED' || status === 'ABORTED';

/** The refusal every frozen write gives, worded once here as it is worded once on the server. */
const runClosed = () =>
  problem(
    409,
    'This run cannot change that way',
    'This run has ended, so its results and evidence can no longer change. Reopen the run to make changes.',
  );

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

  /**
   * Creating a run — **and its executions**, which is the whole point and the part the old mock
   * faked. It used to invent a checklist of "the first twelve cases in the project" regardless of
   * what was asked for, and never wrote the rows at all: the count said twelve and
   * `runExecutions` reverse-engineered a plausible twelve on first read. That is exactly the
   * mock-proves-a-property-live-mode-lacks divergence §29 and §38 each found, and it would have
   * hidden the one behaviour this slice exists to deliver.
   *
   * Now the scope resolves the same way the server's does — explicit `caseIds`, else the plan's
   * expanded scope, never the union — and a run that would materialise nothing is refused, because
   * an empty run renders as 0 of 0, 100%, complete.
   */
  http.post(`${API}/projects/:projectId/runs`, async ({ params, request }) => {
    await delay(LATENCY);
    const projectId = String(params.projectId);

    if (!holdsOn(projectId, 'run:create')) {
      return problem(403, 'Not allowed', 'You do not have permission to create runs in this project.');
    }

    const idempotencyKey = request.headers.get('Idempotency-Key');
    if (idempotencyKey) {
      const already = db.runIdempotency.get(idempotencyKey);
      // The assertion this mechanism exists for: a replay must not make a SECOND checklist.
      if (already) return HttpResponse.json(already, { status: 200 });
    }

    const body = (await request.json()) as Record<string, unknown>;
    const name = String(body.name ?? '').trim();
    if (!name) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        name: ['Enter a run name.'],
      });
    }

    const plan = db.plans.find((p) => p.id === body.planId);
    if (body.planId && !plan) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        planId: ['That plan is not in this project any more.'],
      });
    }

    const requested = (body.caseIds as string[] | undefined) ?? [];
    const caseIds =
      requested.length > 0 ? requested : plan ? planScopeCaseIds(plan) : [];

    if (caseIds.length === 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        [plan ? 'planId' : 'caseIds']: [
          'This run would have nothing to execute. Choose cases, or a plan whose scope still has some.',
        ],
      });
    }

    /*
      §6g's gating rule (§82), mirrored from the server in all four of the ways it can be got wrong:

        1. it binds on the RESOLVED set (`caseIds` above already folds in the plan's scope), so
           naming a plan is not a way round it;
        2. the whole run is refused, never trimmed to the approved cases;
        3. it is asked AFTER validation, so a payload that is both malformed and gated gets the
           field errors — the ones the caller can act on;
        4. it is NOT conditioned on `reviewEnabledCase`. Off is narrow: it suppresses the reviewer
           apparatus, not the status ladder, and a mock that ANDed them would show the gate doing
           nothing on exactly the projects that turn it on.
    */
    const gating = settingsForProject(projectId);
    if (gating.requireApprovedCaseForRun) {
      const unapproved = caseIds
        .map((id) => db.cases.find((c) => c.id === id))
        .filter((c) => c && c.lifecycleStatus !== 'APPROVED')
        .map((c) => c!.key);

      // The override is consulted only here, and only for a holder of `case:approve` — so a caller
      // who sends the flag without the key gets the same refusal as one who did not send it, which
      // is the server's answer and the reason the flag can never widen reach.
      const mayOverride = body.overrideApprovalGate === true && holdsOn(projectId, 'case:approve');

      if (unapproved.length > 0 && !mayOverride) {
        const quoted = unapproved.slice(0, 5);
        const rest = unapproved.length > quoted.length ? ` and ${unapproved.length - quoted.length} more` : '';
        return problem(
          409,
          'This run needs approved cases',
          `This project only runs approved test cases. ${unapproved.length} of the ` +
            `${caseIds.length} in this run ${unapproved.length === 1 ? 'is' : 'are'} not ` +
            `approved: ${quoted.join(', ')}${rest}.`,
        );
      }
    }

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
      // NOT_STARTED with no startedAt, matching the server: a run starts when somebody records
      // the first result, not when it is named.
      status: 'NOT_STARTED',
      releaseVersion: (body.releaseVersion as string) ?? null,
      buildNumber: (body.buildNumber as string) ?? null,
      source: 'MANUAL',
      startedAt: null,
      completedAt: null,
      counts: { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: caseIds.length, total: caseIds.length },
    };
    db.runs.unshift(run);
    materialiseRun(run.id, caseIds);
    if (idempotencyKey) db.runIdempotency.set(idempotencyKey, run);
    return HttpResponse.json(run, {
      status: 201,
      headers: { Location: `${API}/projects/${projectId}/runs/${run.id}` },
    });
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

  /**
   * Completing, aborting or reopening a run — and the mock had the same hole the server did.
   *
   * `test_run.status` had no writer on either side: the fixtures ship runs that are already
   * `IN_PROGRESS`, so mock mode *looked* right while a run **created** in it stayed `NOT_STARTED`
   * for ever, exactly as live mode did. Not §52's "mock agrees with itself" — here both sides were
   * wrong the same way, which is why neither noticed.
   *
   * The transition table is the server's, deliberately duplicated rather than approximated: a mock
   * that allows a move the server refuses teaches the wrong rule more convincingly than one that
   * refuses everything.
   */
  http.put(`${API}/projects/:projectId/runs/:runId/status`, async ({ params, request }) => {
    await delay(LATENCY);
    const run = db.runs.find((r) => r.id === params.runId);
    if (!run) return notFound('That run');

    if (!holdsOn(String(params.projectId), 'run:close')) {
      return problem(403, 'Not allowed', 'Ending or reopening a run needs a test lead.');
    }

    const body = (await request.json()) as { status?: string };
    const requested = body.status;
    if (!requested || !RUN_STATUSES.includes(requested as TestRun['status'])) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        status: ['Choose a status: NOT_STARTED, IN_PROGRESS, COMPLETED or ABORTED.'],
      });
    }

    // Both live spellings mean "reopen", and which one arrived is not consulted — the server
    // derives it from startedAt so a run that never began cannot come back claiming it did.
    const target: TestRun['status'] =
      requested === 'COMPLETED' || requested === 'ABORTED'
        ? requested
        : run.startedAt
          ? 'IN_PROGRESS'
          : 'NOT_STARTED';

    if (target === run.status) return HttpResponse.json(run); // Agreement, not a conflict.

    if (isClosedStatus(run.status) && isClosedStatus(target)) {
      return problem(
        409,
        'This run cannot change that way',
        'This run has already ended. Reopen it before recording a different outcome.',
      );
    }
    if (target === 'COMPLETED' && run.status === 'NOT_STARTED') {
      return problem(
        409,
        'This run cannot change that way',
        'Nothing has been recorded in this run yet, so it cannot be completed. Abort it instead.',
      );
    }

    run.status = target;
    run.completedAt = isClosedStatus(target) ? new Date().toISOString() : null;
    return HttpResponse.json(run);
  }),

  /*
    Evidence. Both halves are modelled — the attach AND the OWN refusal on the delete — because a
    mock that disabled the button and then accepted the write would teach the wrong rule more
    convincingly than not modelling it at all (§66's argument, which is why the case handler stopped
    deferring it). The refusal below reads `createdBy` exactly as `EvidenceService` reads
    `created_by`, after the lookup and in the same order.
  */
  http.post(
    `${API}/projects/:projectId/runs/:runId/executions/:executionId/evidence`,
    async ({ params, request }) => {
      await delay(LATENCY);
      const execution = runExecutions(String(params.runId)).find((e) => e.id === params.executionId);
      if (!execution) return notFound('That execution');

      if (!holdsOn(String(params.projectId), 'evidence:attach')) {
        return problem(
          403,
          'Not allowed',
          'You do not have permission to attach evidence in this project.',
        );
      }

      // AFTER the permission, matching where the server puts it (inside the gate's callback): a
      // caller who may not attach anything is told that, not that the run is closed. The ORDER is
      // part of the rule, and a mock that gets it backwards teaches a 409 where live gives a 403.
      const attachRun = db.runs.find((r) => r.id === params.runId);
      if (attachRun && isClosedStatus(attachRun.status)) return runClosed();

      const form = await request.formData();
      const file = form.get('file');
      if (!(file instanceof File)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          file: ['Choose a file to attach.'],
        });
      }

      const caption = form.get('caption');
      const attached = {
        id: newId(),
        filename: file.name,
        contentType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
        // A blob: URL rather than the server's /content route — in mock mode there is no server to
        // serve the bytes, and this makes the thumbnail actually render what was just chosen. It is
        // the one place the mock cannot mirror the real shape, so it is said out loud here.
        url: URL.createObjectURL(file),
        thumbnailUrl: null,
        caption: typeof caption === 'string' && caption ? caption : null,
        createdBy: userRef(0),
        createdAt: new Date().toISOString(),
      };
      execution.evidence = [...(execution.evidence ?? []), attached];
      return HttpResponse.json(attached, { status: 201 });
    },
  ),

  http.delete(
    `${API}/projects/:projectId/runs/:runId/executions/:executionId/evidence/:evidenceId`,
    async ({ params }) => {
      await delay(LATENCY);
      const execution = runExecutions(String(params.runId)).find((e) => e.id === params.executionId);
      if (!execution) return notFound('That execution');

      if (!holdsOn(String(params.projectId), 'evidence:delete')) {
        return problem(
          403,
          'Not allowed',
          'You do not have permission to remove evidence in this project.',
        );
      }

      const index = (execution.evidence ?? []).findIndex((e) => e.id === params.evidenceId);
      if (index < 0) return notFound('That evidence');

      // Before authorship, as on the server, and the order is the message: a closed run refuses
      // everybody including the author, so "this is not yours" would be the wrong answer.
      const deleteRun = db.runs.find((r) => r.id === params.runId);
      if (deleteRun && isClosedStatus(deleteRun.status)) return runClosed();

      // AFTER the lookup, mirroring the server: the answer depends on the row. Evidence with no
      // recorded author (`createdBy: null`) fails it too — an OWN grant does not reach work nobody
      // can attribute.
      const author = execution.evidence[index].createdBy?.id ?? null;
      if (
        qualifierOn(String(params.projectId), 'evidence:delete') === 'OWN' &&
        author !== currentUser.id
      ) {
        return problem(
          403,
          'Forbidden',
          'You can only remove evidence you attached. Ask a test lead to remove this one.',
        );
      }

      execution.evidence = execution.evidence.filter((_, i) => i !== index);
      return new HttpResponse(null, { status: 204 });
    },
  ),

  http.patch(`${API}/projects/:projectId/runs/:runId/executions/:executionId`, async ({ params, request }) => {
    await delay(LATENCY);
    const runId = String(params.runId);
    const execution = runExecutions(runId).find((e) => e.id === params.executionId);
    if (!execution) return notFound('That execution');

    // Which permission applies depends on the ROW, not on the verb: a first result is
    // `result:submit`, changing one that exists is `result:amend`, which is lead-only. Answered
    // from the same `permissionsOn` the gate reads, so the disabled button and the refused write
    // cannot disagree — the divergence class the If-Match-in-the-body finding belongs to.
    const amending = execution.status != null;
    if (!holdsOn(String(params.projectId), amending ? 'result:amend' : 'result:submit')) {
      return problem(
        403,
        'Not allowed',
        amending
          ? 'A result has already been recorded here, and changing one needs a test lead.'
          : 'You do not have permission to record results in this project.',
      );
    }

    // After the permission and before the If-Match, exactly where the server checks it: a stale
    // header is something the caller could fix and resubmit, and this is not.
    const run = db.runs.find((r) => r.id === runId);
    if (run && isClosedStatus(run.status)) return runClosed();

    const body = (await request.json()) as {
      statusId?: string;
      comment?: string | null;
      stepResults?: { stepId?: string; statusId?: string; comment?: string | null }[];
    };

    // The precondition is the HEADER. It was read from the body here for as long as no server
    // read it at all, and the SPA sent it there — both sides agreed and both were wrong about
    // where the contract puts it. Concurrent execution is supported by design, which is exactly
    // why the lock matters: two testers on one run must not silently overwrite each other.
    const ifMatch = request.headers.get('If-Match');
    if (ifMatch !== null && ifMatch.trim() !== '') {
      const expected = Number(ifMatch.replace(/^"|"$/g, ''));
      if (Number.isNaN(expected)) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          'If-Match': ["Send the execution's lockVersion as the If-Match header, or omit the header."],
        });
      }
      if (expected !== execution.lockVersion) {
        return problem(
          409,
          'Someone else recorded a result first',
          'Reload the execution to see their result before recording yours.',
        );
      }
    }

    if (!body.statusId) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        statusId: ['Choose a result.'],
      });
    }
    const status = db.statuses.find((s) => s.id === body.statusId);
    if (!status) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        statusId: ['That result status is not one this project uses.'],
      });
    }

    execution.status = status;
    execution.executedAt = new Date().toISOString();
    execution.executedBy = userRef(0);
    execution.comment = body.comment ?? null;

    for (const update of body.stepResults ?? []) {
      const step = execution.steps?.find((s) => s.stepId === update.stepId);
      // Refused rather than dropped, matching the server: the tester marked something, and a save
      // that reports success while discarding a mark is this endpoint's worst failure.
      if (!step) {
        return problem(400, 'Validation failed', 'Check the highlighted fields.', {
          stepResults: [`Step ${update.stepId} is not part of the version this run pinned.`],
        });
      }
      if (update.statusId !== undefined) step.statusId = update.statusId;
      if (update.comment !== undefined) step.comment = update.comment;
    }

    execution.lockVersion = (execution.lockVersion ?? 0) + 1;

    // The run starts here or it never starts — the rule `TestRun.create` has asserted since §42 and
    // which neither side implemented until §72. Same instant as the result, so a run's start cannot
    // sort after the first result recorded in it.
    if (run && run.status === 'NOT_STARTED') {
      run.status = 'IN_PROGRESS';
      run.startedAt = execution.executedAt;
    }

    recountRun(runId);
    return HttpResponse.json(execution);
  }),
];
