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
      evidence: seededEvidence(kind, i),
      lockVersion: 1,
    } satisfies TestExecution;
  });

  db.executions.set(runId, built);
  return built;
}

/**
 * The executions a **newly created** run starts with — one per case, none of them recorded.
 *
 * Distinct from `runExecutions` above, which reverse-engineers a seeded run's checklist from its
 * counts because the fixtures carry counts and not rows. This is the create path, and it is the
 * one that has to be honest: the server materialises an execution per case in scope pinned to that
 * case's current version, so the mock does the same rather than synthesising a plausible spread of
 * verdicts. A created run with results already in it would make the mock disagree with the server
 * about the one thing this slice is for.
 *
 * @param caseIds the resolved scope, in the order the checklist is worked
 */
export function materialiseRun(runId: string, caseIds: string[]): TestExecution[] {
  const run = db.runs.find((r) => r.id === runId);
  const built = caseIds.map((caseId, i) => {
    const source = db.cases.find((c) => c.id === caseId);
    const detail = caseDetail(caseId);
    return {
      id: uid(700 + i, 'n'),
      caseId,
      caseKey: source?.key ?? 'TC-?',
      caseTitle: source?.title ?? 'Unknown case',
      // The pin, resolved at creation. An edit after this must not move it — which the mock
      // cannot demonstrate, but it can at least not contradict.
      caseVersionNo: detail?.currentVersionNo ?? 1,
      status: null,
      // Null for both, which is the ordinary state of a run nobody has started — and the state
      // that made §38's person-lookup a 500 on the server.
      assignee: null,
      executedBy: null,
      executedAt: null,
      durationMs: null,
      defects: [],
      comment: null,
      releaseVersion: run?.releaseVersion ?? null,
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
        statusId: null,
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

/**
 * The live cases a plan's scope covers — the suite subtree expansion, deduped.
 *
 * A suite item means the folder **and everything beneath it**, which the server answers with an
 * `ltree` descendant query. Here the tree is the `path` string, so a descendant is anything whose
 * path starts with the folder's own path — the same relation, spelled for the shape the mock has.
 */
export function planScopeCaseIds(planId: string): string[] {
  const suiteIds = db.planScope.get(planId) ?? [];
  if (suiteIds.length === 0) return [];

  const roots = db.suites.filter((suite) => suiteIds.includes(suite.id));
  const covered = new Set(
    db.suites
      .filter((suite) => roots.some((root) => suite.path === root.path || suite.path.startsWith(`${root.path}.`)))
      .map((suite) => suite.id),
  );

  return db.cases
    // An unfiled case is in no suite's subtree, so a suite-scoped plan never covers it. It
    // reaches a run by being named directly, which is the case_id arm of plan scope.
    .filter(
      (row) => row.suiteId !== null && covered.has(row.suiteId) && row.lifecycleStatus !== 'DEPRECATED',
    )
    .map((row) => row.id);
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

/**
 * Evidence on a seeded execution — only on the ones that FAILED, which is where a tester actually
 * attaches a screenshot.
 *
 * **The authors deliberately disagree with the executor.** `executedBy` is `users[0]`, who is also
 * the signed-in `currentUser`; seeding evidence to that same person would let a client that read
 * the wrong field — or ignored authorship entirely — pass every assertion and render an enabled
 * Delete on every row. §64's rule: seeded to the answer, both implementations look correct.
 *
 * So a failed execution carries one row authored by the caller (Delete enabled), one by a colleague
 * (disabled, with the OWN sentence), and one with **no author at all** — the arm that exists only
 * in code, governs the oldest rows in a real installation, and appears in no fixture unless
 * somebody puts it there deliberately.
 */
function seededEvidence(kind: string, i: number): Evidence[] {
  if (kind !== 'FAILED') return [];
  const at = '2026-07-28T09:14:00Z';
  const person = (n: number) => ({
    id: users[n].id,
    displayName: users[n].displayName,
    email: users[n].email,
    avatarUrl: null,
  });
  return [
    {
      id: uid(940 + i * 3, 'x'),
      filename: 'balance-assertion.png',
      contentType: 'image/png',
      sizeBytes: 184_320,
      url: TRANSPARENT_PNG,
      thumbnailUrl: null,
      caption: 'Balance shows 45.00 EUR after the refund',
      createdBy: person(0),
      createdAt: at,
    },
    {
      id: uid(941 + i * 3, 'x'),
      filename: 'server.log',
      contentType: 'text/plain',
      sizeBytes: 20_480,
      url: TRANSPARENT_PNG,
      thumbnailUrl: null,
      caption: null,
      createdBy: person(1 % users.length),
      createdAt: at,
    },
    {
      id: uid(942 + i * 3, 'x'),
      filename: 'imported-from-the-old-tool.png',
      contentType: 'image/png',
      sizeBytes: 51_200,
      url: TRANSPARENT_PNG,
      thumbnailUrl: null,
      caption: null,
      // No author. Not an oversight — see the header.
      createdBy: null,
      createdAt: at,
    },
  ];
}

/**
 * A 1×1 transparent PNG as a data: URI, so a thumbnail genuinely renders in mock mode.
 *
 * There is no server here to serve the `/content` route the real `url` points at, and a dead `src`
 * would make the evidence strip look broken rather than populated.
 */
const TRANSPARENT_PNG =
  'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';
