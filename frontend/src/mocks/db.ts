/**
 * The mock database.
 *
 * Fixtures are *seeds*; this module holds the mutable copy the handlers read and write.
 * That distinction is the whole point — a stateless handler that returns a fixture makes
 * every create/edit screen a lie, because the list never reflects what you just saved.
 *
 * Everything is deep-cloned out of the fixtures at module load so a mutation here can
 * never write back into a `const` the rest of the app also imports.
 */
import type {
  Artifact,
  CaseStatus,
  Condition,
  Configuration,
  DocPage,
  Evidence,
  Suite,
  TestCase,
  TestCaseSummary,
  TestExecution,
  TestPlan,
  TestRun,
  WorkItem,
} from '../api/types';
import { buildCaseDetail, caseSummaries } from './fixtures/cases';
import {
  artifacts,
  caseStatuses,
  conditions,
  configurations,
  docPageDetail,
  docPages,
  plans,
  runs,
  sharedSteps,
  statusByKind,
  suites,
  uid,
  users,
} from './fixtures/core';
import {
  caseHistoryEntries,
  caseHistorySummary,
  caseVersionMarkers,
  dashboard,
  planStatistics,
  suiteHistoryEntries,
  suiteVersionMarkers,
} from './fixtures/reporting';

const clone = <T>(value: T): T => structuredClone(value);

/**
 * Display-key allocation mirrors the backend contract: `PREFIX-N`, sequential **per company**
 * per entity type (plan §6t), with `keySeq` stored alongside the text key because `TC-10000`
 * sorts before `TC-9999` lexicographically — nothing may ever order by the string.
 *
 * Per company, not per project: a company holds a handful of whole client products, so one
 * namespace per client is comfortable and makes `TC-1` unambiguous everywhere inside it. That
 * removes the qualified `PROJ / TC-1` form the UI would otherwise need in reports, search,
 * kanban and Jira bodies. Since this mock serves one company, the map is keyed by prefix alone —
 * the real backend keys it `(company_id, entity_type)`.
 *
 * No zero-padding: `keySeq` owns ordering, so padding would be decoration that also lies once
 * the sequence passes 9999.
 *
 * The real allocator must reserve a RANGE in one statement (`next_value + :n RETURNING`), never
 * increment per row — all creation in a company serialises on this one counter, so a 5,000-case
 * import one-at-a-time would be pathological. This mock is single-threaded, so it takes the
 * simple path; the constraint is the backend's.
 */
const sequences = new Map<string, number>();

function nextKey(prefix: string, seed: number): { key: string; keySeq: number } {
  const current = sequences.get(prefix) ?? seed;
  const keySeq = current + 1;
  sequences.set(prefix, keySeq);
  return { key: `${prefix}-${keySeq}`, keySeq };
}

function newId(): string {
  return crypto.randomUUID();
}

// ------------------------------------------------------------------ the store ----

interface Store {
  suites: Suite[];
  cases: TestCaseSummary[];
  /** Full detail, lazily materialized on first read so edits survive a re-read. */
  caseDetails: Map<string, TestCase>;
  conditions: Condition[];
  sharedSteps: typeof sharedSteps;
  plans: TestPlan[];
  runs: TestRun[];
  executions: Map<string, TestExecution[]>;
  docPages: typeof docPages;
  docPageDetails: Record<string, DocPage>;
  artifacts: Artifact[];
  statuses: CaseStatus[];
  configurations: Configuration[];
  workItems: WorkItem[];
  /**
   * Idempotency-Key → the record that key's intent created.
   *
   * A real server would expire these (24h is typical) and scope them per user. In-memory and
   * reset on reload is enough here — the point is that a retried create returns the original
   * record instead of making a second one, so the client's idempotency contract is actually
   * exercised rather than merely declared.
   */
  idempotency: Map<string, TestCase>;
}

export const db: Store = {
  suites: clone(suites),
  cases: clone(caseSummaries),
  caseDetails: new Map(),
  conditions: clone(conditions),
  sharedSteps: clone(sharedSteps),
  plans: clone(plans),
  runs: clone(runs),
  executions: new Map(),
  docPages: clone(docPages),
  docPageDetails: clone(docPageDetail),
  artifacts: clone(artifacts),
  statuses: clone(caseStatuses),
  configurations: clone(configurations),
  workItems: [],
  idempotency: new Map(),
};

sequences.set('TC', db.cases.length);
sequences.set('TS', db.suites.length);
sequences.set('TP', db.plans.length);
sequences.set('TR', 418);

// Suite case counts are derived, never stored — a stored count drifts the moment a case
// moves suite, and the drift is invisible until someone reconciles by hand.
export function suiteCaseCount(suiteId: string): number {
  const descendants = descendantSuiteIds(suiteId);
  return db.cases.filter((c) => descendants.has(c.suiteId)).length;
}

export function descendantSuiteIds(suiteId: string): Set<string> {
  const out = new Set<string>([suiteId]);
  let added = true;
  while (added) {
    added = false;
    for (const s of db.suites) {
      if (s.parentId && out.has(s.parentId) && !out.has(s.id)) {
        out.add(s.id);
        added = true;
      }
    }
  }
  return out;
}

export function suiteTree(): Suite[] {
  return db.suites.map((s) => ({ ...s, caseCount: suiteCaseCount(s.id) }));
}

// -------------------------------------------------------------------- cases ----

export function caseDetail(id: string): TestCase | undefined {
  const existing = db.caseDetails.get(id);
  if (existing) return existing;
  const summary = db.cases.find((c) => c.id === id);
  if (!summary) return undefined;
  const detail = buildCaseDetail(summary);
  db.caseDetails.set(id, detail);
  return detail;
}

/** Writes both the summary row and the detail record so the list and the page agree. */
export function writeCase(detail: TestCase): TestCase {
  db.caseDetails.set(detail.id, detail);
  const index = db.cases.findIndex((c) => c.id === detail.id);
  const summary: TestCaseSummary = {
    id: detail.id,
    key: detail.key,
    keySeq: detail.keySeq,
    title: detail.title,
    suiteId: detail.suiteId,
    suiteName: db.suites.find((s) => s.id === detail.suiteId)?.name ?? '',
    priority: detail.priority,
    lifecycleStatus: detail.lifecycleStatus,
    automationStatus: detail.automationStatus,
    stepFormat: detail.stepFormat,
    owner: detail.owner,
    testType: detail.testType,
    testLevel: detail.testLevel,
    features: detail.features,
    tags: detail.tags,
    healthSignals: detail.healthSignals,
    lastResult: detail.lastResult,
    updatedAt: new Date().toISOString(),
  };
  if (index >= 0) db.cases[index] = summary;
  else db.cases.unshift(summary);
  return detail;
}

export function allocateCaseKey() {
  return nextKey('TC', 0);
}

export function allocateSuiteKey() {
  return nextKey('TS', 0);
}

export function allocatePlanKey() {
  return nextKey('TP', 0);
}

export function allocateRunKey() {
  return nextKey('TR', 0);
}

// --------------------------------------------------------------- executions ----

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

// -------------------------------------------------------------- work items ----

/**
 * Derived work items are computed on every read, never stored — that is what makes a
 * card unable to diverge from its execution (plan §6l).
 */
export function workItems(projectId: string, projectKey: string): WorkItem[] {
  const meRef = {
    id: users[0].id,
    displayName: users[0].displayName,
    email: users[0].email,
    avatarUrl: null,
  };

  const fromExecutions: WorkItem[] = db.runs
    .filter((r) => r.status === 'IN_PROGRESS')
    .flatMap((run) =>
      runExecutions(run.id)
        .filter((e) => !e.status)
        .slice(0, 4)
        .map((e) => ({
          id: `derived-exec-${e.id}`,
          derived: true,
          sourceType: 'EXECUTION',
          sourceId: e.id,
          kind: 'EXECUTION' as const,
          title: e.caseTitle,
          description: `Execute in ${run.name}`,
          status: 'In progress',
          priority: 'HIGH' as const,
          dueDate: null,
          overdue: false,
          assignee: meRef,
          projectId,
          projectKey,
          linkedKey: e.caseKey,
          // A derived card may only move where its source can legally go.
          allowedTransitions: ['In progress', 'Done'],
        })),
    );

  const fromReviews: WorkItem[] = db.cases
    .filter((c) => c.lifecycleStatus === 'IN_REVIEW')
    .map((c) => ({
      id: `derived-review-${c.id}`,
      derived: true,
      sourceType: 'REVIEW',
      sourceId: c.id,
      kind: 'REVIEW' as const,
      title: `Review ${c.title}`,
      description: null,
      status: 'In review',
      priority: c.priority,
      dueDate: null,
      overdue: false,
      assignee: c.owner,
      projectId,
      projectKey,
      linkedKey: c.key,
      allowedTransitions: ['In review', 'Done'],
    }));

  const fromStale: WorkItem[] = db.cases
    .filter((c) => c.healthSignals.includes('OUTDATED_SPEC_CHANGED') || c.healthSignals.includes('STALE_AUTOMATION'))
    .map((c) => ({
      id: `derived-maint-${c.id}`,
      derived: true,
      sourceType: 'CASE',
      sourceId: c.id,
      kind: 'CASE_MAINTENANCE' as const,
      title: `Update ${c.key} — ${c.healthSignals.includes('STALE_AUTOMATION') ? 'automation stopped reporting' : 'linked spec changed'}`,
      description: null,
      status: 'To do',
      priority: 'MEDIUM' as const,
      dueDate: null,
      overdue: false,
      assignee: c.owner,
      projectId,
      projectKey,
      linkedKey: c.key,
      allowedTransitions: ['To do', 'In progress', 'Done'],
    }));

  return [...db.workItems.filter((w) => w.projectId === projectId), ...fromExecutions, ...fromReviews, ...fromStale];
}

export function addWorkItem(item: WorkItem): WorkItem {
  db.workItems.unshift(item);
  return item;
}

// Read-only reporting fixtures — no screen writes these, so they stay by reference.
export const reporting = {
  caseHistoryEntries,
  caseHistorySummary,
  caseVersionMarkers,
  suiteHistoryEntries,
  suiteVersionMarkers,
  dashboard,
  planStatistics,
};

export { newId, users, configurations, statusByKind, uid };
