/** A run's executions, materialized on demand, and the recount a result triggers. */
import type { Evidence, TestExecution } from '../../api/types';
import { caseDetail } from './cases';
import { db, statusByKind, uid, users } from './store';

/**
 * A run's executions are materialized on first read from the run's counts, so the
 * execution screen has something coherent to show without a second fixture file.
 * Each execution pins the case *version* it executed (plan §3, detail 1).
 */
export function runExecutions(runId: string): TestExecution[] {
  const existing = db.executions.get(runId);
  if (existing) return existing;

  const run = db.runs.find((r) => r.id === runId);
  if (!run) return [];

  const counts = run.counts;
  const order: string[] = [
    ...Array<string>(counts.passed).fill('PASSED'),
    ...Array<string>(counts.failed).fill('FAILED'),
    ...Array<string>(counts.blocked).fill('BLOCKED'),
    ...Array<string>(counts.skipped).fill('SKIPPED'),
    ...Array<string>(counts.notRun).fill('UNTESTED'),
  ];

  const pool = db.cases.filter((c) => c.lifecycleStatus !== 'DEPRECATED');
  const built = order.map((kind, i) => {
    const source = pool[i % pool.length];
    const detail = caseDetail(source.id);
    const status = kind === 'UNTESTED' ? null : statusByKind[kind];
    return {
      id: uid(900 + i, 'x'),
      caseId: source.id,
      caseKey: source.key,
      caseTitle: source.title,
      caseVersionNo: detail?.currentVersionNo ?? 1,
      status,
      assignee: { id: users[0].id, displayName: users[0].displayName, email: users[0].email, avatarUrl: null },
      executedBy: { id: users[0].id, displayName: users[0].displayName, email: users[0].email, avatarUrl: null },
      executedAt: status ? run.startedAt : null,
      durationMs: status ? 1800 + i * 23 : null,
      defects: [],
      comment: kind === 'FAILED' ? 'Balance assertion failed — expected 0.00 EUR, got 45.00 EUR.' : null,
      releaseVersion: run.releaseVersion,
      stepFormat: detail?.stepFormat ?? 'STRUCTURED',
      preconditions: detail?.preconditions ?? [],
      postconditions: detail?.postconditions ?? [],
      steps: (detail?.steps ?? []).map((step, p) => ({
        stepId: step.id,
        position: p,
        keyword: step.keyword ?? null,
        action: step.action,
        testData: step.testData ?? null,
        expectedResult: step.expectedResult ?? null,
        // A failing case fails on its last step; everything before it passed.
        statusId:
          status === null
            ? null
            : kind === 'FAILED' && p === (detail?.steps?.length ?? 1) - 1
              ? statusByKind.FAILED.id
              : statusByKind.PASSED.id,
        comment: null,
        evidence: [],
      })),
      evidence: [] as Evidence[],
      lockVersion: 1,
    } satisfies TestExecution;
  });

  db.executions.set(runId, built);
  return built;
}

export function recountRun(runId: string): void {
  const run = db.runs.find((r) => r.id === runId);
  if (!run) return;
  const executions = runExecutions(runId);
  const tally = { passed: 0, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: executions.length };
  for (const e of executions) {
    switch (e.status?.kind) {
      case 'PASSED':
        tally.passed += 1;
        break;
      case 'FAILED':
        tally.failed += 1;
        break;
      case 'BLOCKED':
        tally.blocked += 1;
        break;
      case 'SKIPPED':
        tally.skipped += 1;
        break;
      default:
        tally.notRun += 1;
    }
  }
  run.counts = tally;
}
