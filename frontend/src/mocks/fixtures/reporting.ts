/**
 * Reporting fixtures — history, dashboard, plan statistics.
 *
 * These carry the shapes the design decisions live or die on:
 *   - a case whose results span three versions, with a v5 edit followed by failures
 *     (so the version marker earns its place on the timeline)
 *   - a suite whose membership grew mid-cycle (so the "as-executed" caveat is visible)
 *   - a plan at 89% pass over 97% coverage with two unmet exit criteria
 */
import type {
  CaseHistorySummary,
  DashboardBreakdown,
  DashboardTile,
  ExecutionHistoryEntry,
  PlanStatistics,
  RepositoryDashboard,
  SuiteRunSummary,
  VersionMarker,
} from '../../api/types';
import { caseSummaries } from './cases';
import { configurations, plans, statusByKind, suites, uid, users } from './core';

const ref = (i: number) => ({
  id: users[i].id,
  displayName: users[i].displayName,
  email: users[i].email,
  avatarUrl: null,
});

// ------------------------------------------------------------- case history ----

export const caseVersionMarkers: VersionMarker[] = [
  { versionNo: 5, occurredAt: '2026-07-24T11:20:00Z', author: ref(0), changeComment: 'Tightened the balance assertion to check currency as well as amount' },
  { versionNo: 6, occurredAt: '2026-07-26T09:05:00Z', author: ref(1), changeComment: 'Added the multi-currency example' },
  { versionNo: 7, occurredAt: '2026-07-28T16:40:00Z', author: ref(0), changeComment: null },
];

function entry(
  n: number,
  statusKind: keyof typeof statusByKind,
  caseVersionNo: number,
  executedAt: string,
  release: string,
  build: string,
  runIdx: number,
  configIdx = 0,
  source: 'MANUAL' | 'INGESTED' = 'INGESTED',
): ExecutionHistoryEntry {
  const status = statusByKind[statusKind];
  return {
    executionId: uid(500 + n, 'x'),
    statusKind: status.kind,
    statusName: status.name,
    statusColor: status.color,
    caseVersionNo,
    releaseVersion: release,
    buildNumber: build,
    runId: uid(runIdx, 'k'),
    runKey: `TR_${String(400 + runIdx).padStart(4, '0')}`,
    runName: `Nightly regression #${400 + runIdx}`,
    planKey: 'TP-1',
    configuration: configurations[configIdx].name,
    executedBy: source === 'MANUAL' ? ref(2) : ref(0),
    executedByToken: source === 'INGESTED' ? 'ci-jenkins-payments' : null,
    source,
    executedAt,
    durationMs: source === 'INGESTED' ? 1400 + n * 37 : 260_000,
    evidenceCount: statusKind === 'FAILED' ? 3 : 1,
    defects:
      statusKind === 'FAILED'
        ? [{ id: uid(n, 'j'), externalKey: `PAY-99${n}`, provider: 'JIRA', url: 'https://example.com/browse/PAY-991', statusCache: 'Open' }]
        : [],
  };
}

/**
 * TC-5's history: green through v4, a v5 edit on the 24th, then failures — the exact
 * shape that makes "the edit broke it" readable without opening a diff.
 */
export const caseHistoryEntries: ExecutionHistoryEntry[] = [
  entry(1, 'FAILED', 7, '2026-07-30T02:14:00Z', '4.3.0', '#4471', 18),
  entry(2, 'FAILED', 7, '2026-07-29T02:14:00Z', '4.3.0', '#4470', 17),
  entry(3, 'PASSED', 7, '2026-07-28T18:02:00Z', '4.3.0', '#4469', 16, 1, 'MANUAL'),
  entry(4, 'FAILED', 6, '2026-07-27T02:14:00Z', '4.3.0', '#4468', 15),
  entry(5, 'FAILED', 6, '2026-07-26T02:14:00Z', '4.3.0', '#4467', 14),
  entry(6, 'FAILED', 5, '2026-07-25T02:14:00Z', '4.2.1', '#4390', 13),
  entry(7, 'PASSED', 4, '2026-07-23T02:14:00Z', '4.2.1', '#4388', 12),
  entry(8, 'PASSED', 4, '2026-07-22T02:14:00Z', '4.2.1', '#4387', 11),
  entry(9, 'PASSED', 4, '2026-07-21T02:14:00Z', '4.2.0', '#4380', 10),
  entry(10, 'PASSED', 4, '2026-07-20T02:14:00Z', '4.2.0', '#4379', 9),
  entry(11, 'BLOCKED', 3, '2026-07-19T02:14:00Z', '4.2.0', '#4378', 8),
  entry(12, 'PASSED', 3, '2026-07-18T02:14:00Z', '4.2.0', '#4377', 7),
];

export const caseHistorySummary: CaseHistorySummary = {
  scope: 'Current version (v7)',
  currentVersionNo: 7,
  executions: 3,
  passed: 1,
  failed: 2,
  lastExecutedAt: '2026-07-30T02:14:00Z',
  flakinessScore: 0.33,
  firstFailedAt: '2026-07-25T02:14:00Z',
  firstFailedInRelease: '4.2.1',
};

// ------------------------------------------------------------ suite history ----

export const suiteVersionMarkers: VersionMarker[] = [
  { versionNo: 3, occurredAt: '2026-07-26T10:15:00Z', author: ref(0), changeComment: 'Added 4 partial-refund cases' },
];

export const suiteHistoryEntries: SuiteRunSummary[] = [
  { runId: uid(18, 'k'), runKey: 'TR-418', runName: 'Nightly regression #418', suiteVersionNo: 3, configuration: 'Chrome / Windows', releaseVersion: '4.3.0', executedAt: '2026-07-30T02:41:00Z', counts: { passed: 41, failed: 4, blocked: 2, skipped: 0, notRun: 0, total: 47 } },
  { runId: uid(17, 'k'), runKey: 'TR-417', runName: 'Nightly regression #417', suiteVersionNo: 3, configuration: 'Chrome / Windows', releaseVersion: '4.3.0', executedAt: '2026-07-29T02:38:00Z', counts: { passed: 44, failed: 2, blocked: 1, skipped: 0, notRun: 0, total: 47 } },
  { runId: uid(16, 'k'), runKey: 'TR-416', runName: 'Nightly regression #416', suiteVersionNo: 3, configuration: 'Chrome / Windows', releaseVersion: '4.3.0', executedAt: '2026-07-28T02:36:00Z', counts: { passed: 40, failed: 5, blocked: 1, skipped: 1, notRun: 0, total: 47 } },
  { runId: uid(15, 'k'), runKey: 'TR-415', runName: 'Nightly regression #415', suiteVersionNo: 3, configuration: 'Chrome / Windows', releaseVersion: '4.3.0', executedAt: '2026-07-27T02:34:00Z', counts: { passed: 39, failed: 6, blocked: 2, skipped: 0, notRun: 0, total: 47 } },
  // Before v3 the suite had 43 cases — the drop after "added 4 cases" is new coverage,
  // not a regression, which is exactly what the version marker exists to say.
  { runId: uid(14, 'k'), runKey: 'TR-414', runName: 'Release 4.2 sign-off run', suiteVersionNo: 2, configuration: 'Chrome / Windows', releaseVersion: '4.2.1', executedAt: '2026-07-25T16:20:00Z', counts: { passed: 43, failed: 0, blocked: 0, skipped: 0, notRun: 0, total: 43 } },
  { runId: uid(13, 'k'), runKey: 'TR-413', runName: 'Nightly regression #413', suiteVersionNo: 2, configuration: 'Chrome / Windows', releaseVersion: '4.2.1', executedAt: '2026-07-24T02:30:00Z', counts: { passed: 41, failed: 2, blocked: 0, skipped: 0, notRun: 0, total: 43 } },
];

// ---------------------------------------------------------------- dashboard ----

const lifecycleTiles: DashboardTile[] = [
  { key: 'DRAFT', label: 'Draft', count: 142, severity: 'NEUTRAL', filter: { lifecycleStatus: ['DRAFT'] } },
  { key: 'IN_REVIEW', label: 'In review', count: 23, severity: 'INFO', filter: { lifecycleStatus: ['IN_REVIEW'] } },
  { key: 'APPROVED', label: 'Approved', count: 891, severity: 'NEUTRAL', filter: { lifecycleStatus: ['APPROVED'] } },
  { key: 'DEPRECATED', label: 'Deprecated', count: 67, severity: 'NEUTRAL', filter: { lifecycleStatus: ['DEPRECATED'] } },
];

const healthTiles: DashboardTile[] = [
  { key: 'OUTDATED_SPEC_CHANGED', label: 'Outdated — spec changed', count: 78, severity: 'ERROR', filter: { healthSignal: ['OUTDATED_SPEC_CHANGED'] } },
  { key: 'STALE_APPROVAL', label: 'Stale approval', count: 31, severity: 'WARNING', filter: { healthSignal: ['STALE_APPROVAL'] } },
  { key: 'NEVER_EXECUTED', label: 'Never executed', count: 44, severity: 'WARNING', filter: { healthSignal: ['NEVER_EXECUTED'] } },
  { key: 'STALE_AUTOMATION', label: 'Stale automation', count: 12, severity: 'ERROR', filter: { healthSignal: ['STALE_AUTOMATION'] } },
  { key: 'UNLINKED', label: 'Unlinked', count: 156, severity: 'INFO', filter: { healthSignal: ['UNLINKED'] } },
  { key: 'LONG_UNEXECUTED', label: 'Not run in 90 days', count: 63, severity: 'WARNING', filter: { healthSignal: ['LONG_UNEXECUTED'] } },
];

const seg = (key: string, label: string, count: number, color: string, filter: Record<string, unknown>) => ({
  key, label, count, color, filter,
});

const byFeature: DashboardBreakdown[] = [
  {
    id: 'f-payments', label: 'Payments',
    segments: [
      seg('APPROVED', 'Approved', 210, 'var(--color-approved)', { featureId: 'payments', lifecycleStatus: ['APPROVED'] }),
      seg('IN_REVIEW', 'In review', 8, 'var(--color-in-review)', { featureId: 'payments', lifecycleStatus: ['IN_REVIEW'] }),
      seg('DRAFT', 'Draft', 22, 'var(--color-draft)', { featureId: 'payments', lifecycleStatus: ['DRAFT'] }),
    ],
  },
  {
    id: 'f-refunds', label: 'Payments › Refunds',
    segments: [
      seg('APPROVED', 'Approved', 96, 'var(--color-approved)', { featureId: 'refunds', lifecycleStatus: ['APPROVED'] }),
      seg('IN_REVIEW', 'In review', 6, 'var(--color-in-review)', { featureId: 'refunds', lifecycleStatus: ['IN_REVIEW'] }),
      seg('DRAFT', 'Draft', 14, 'var(--color-draft)', { featureId: 'refunds', lifecycleStatus: ['DRAFT'] }),
    ],
  },
  {
    id: 'f-checkout', label: 'Checkout',
    segments: [
      seg('APPROVED', 'Approved', 41, 'var(--color-approved)', { featureId: 'checkout', lifecycleStatus: ['APPROVED'] }),
      seg('IN_REVIEW', 'In review', 3, 'var(--color-in-review)', { featureId: 'checkout', lifecycleStatus: ['IN_REVIEW'] }),
      seg('DRAFT', 'Draft', 78, 'var(--color-draft)', { featureId: 'checkout', lifecycleStatus: ['DRAFT'] }),
    ],
  },
  {
    id: 'f-settlement', label: 'Settlement',
    segments: [
      seg('APPROVED', 'Approved', 62, 'var(--color-approved)', { featureId: 'settlement', lifecycleStatus: ['APPROVED'] }),
      seg('DRAFT', 'Draft', 9, 'var(--color-draft)', { featureId: 'settlement', lifecycleStatus: ['DRAFT'] }),
    ],
  },
];

const byOwner: DashboardBreakdown[] = users.slice(0, 4).map((u, i) => ({
  id: u.id,
  label: u.displayName,
  segments: [
    seg('APPROVED', 'Approved', [180, 240, 160, 120][i], 'var(--color-approved)', { ownerId: u.id, lifecycleStatus: ['APPROVED'] }),
    seg('IN_REVIEW', 'In review', [4, 9, 6, 2][i], 'var(--color-in-review)', { ownerId: u.id, lifecycleStatus: ['IN_REVIEW'] }),
    seg('DRAFT', 'Draft', [18, 34, 51, 39][i], 'var(--color-draft)', { ownerId: u.id, lifecycleStatus: ['DRAFT'] }),
  ],
}));

export const dashboard: RepositoryDashboard = {
  generatedAt: '2026-07-30T09:42:00Z',
  lifecycle: lifecycleTiles,
  health: healthTiles,
  byFeature,
  byOwner,
  automationCoverage: {
    automated: 604,
    toBeAutomated: 187,
    manual: 268,
    notAutomatable: 64,
    // 604 / (1123 - 64) — honestly denominated against what CAN be automated
    coveragePercent: 57.0,
  },
  recentActivity: Array.from({ length: 30 }, (_, i) => {
    const d = new Date('2026-07-30T00:00:00Z');
    d.setUTCDate(d.getUTCDate() - (29 - i));
    return {
      date: d.toISOString().slice(0, 10),
      created: [0, 2, 5, 1, 8, 3, 0, 0, 4, 9, 12, 6, 2, 0, 0, 7, 11, 4, 3, 5, 0, 0, 9, 14, 8, 6, 2, 0, 0, 3][i],
      approved: [0, 1, 3, 0, 5, 2, 0, 0, 2, 6, 9, 4, 1, 0, 0, 4, 8, 2, 2, 3, 0, 0, 6, 10, 5, 4, 1, 0, 0, 2][i],
      modified: [1, 4, 8, 3, 12, 6, 1, 0, 7, 14, 18, 9, 4, 1, 0, 11, 16, 7, 5, 8, 0, 1, 13, 21, 12, 9, 3, 1, 0, 5][i],
    };
  }),
};

// ---------------------------------------------------------- plan statistics ----

const failing = caseSummaries.filter((c) => c.lastResult?.statusKind === 'FAILED' || c.lastResult?.statusKind === 'BLOCKED');

export const planStatistics: PlanStatistics = {
  planId: plans[0].id,
  planKey: 'TP-1',
  planName: 'Release 4.3 regression',
  planVersionNo: 2,
  aggregationPolicy: 'LATEST',
  counts: { passed: 278, failed: 21, blocked: 6, skipped: 0, notRun: 7, total: 312 },
  coveragePercent: 97.4,
  latestExecutionAt: '2026-07-30T02:41:00Z',
  stale: false,
  byConfiguration: [
    { configuration: 'Chrome / Windows', counts: { passed: 148, failed: 6, blocked: 2, skipped: 0, notRun: 0, total: 156 } },
    { configuration: 'Safari / macOS', counts: { passed: 96, failed: 14, blocked: 3, skipped: 0, notRun: 5, total: 118 } },
    { configuration: 'Firefox / Linux', counts: { passed: 34, failed: 1, blocked: 1, skipped: 0, notRun: 2, total: 38 } },
  ],
  bySuite: [
    { suiteId: suites[2].id, suiteKey: 'TS-3', suiteName: 'Refunds', counts: { passed: 108, failed: 9, blocked: 3, skipped: 0, notRun: 2, total: 122 } },
    { suiteId: suites[1].id, suiteKey: 'TS-2', suiteName: 'Card capture', counts: { passed: 74, failed: 3, blocked: 1, skipped: 0, notRun: 0, total: 78 } },
    { suiteId: suites[3].id, suiteKey: 'TS-4', suiteName: 'Partial refunds', counts: { passed: 41, failed: 8, blocked: 2, skipped: 0, notRun: 3, total: 54 } },
    { suiteId: suites[6].id, suiteKey: 'TS-7', suiteName: 'Settlement', counts: { passed: 55, failed: 1, blocked: 0, skipped: 0, notRun: 2, total: 58 } },
  ],
  blockers: failing.map((c, i) => ({
    id: uid(700 + i, 'x'),
    caseId: c.id,
    caseKey: c.key,
    caseTitle: c.title,
    caseVersionNo: 7,
    status: statusByKind[c.lastResult?.statusKind ?? 'FAILED'],
    assignee: ref(0),
    executedBy: ref(0),
    executedAt: c.lastResult?.executedAt ?? null,
    durationMs: 1800,
    defects: [{ id: uid(800 + i, 'j'), externalKey: `PAY-9${90 + i}`, provider: 'JIRA', url: 'https://example.com/browse/PAY-990', statusCache: i % 2 ? 'In Progress' : 'Open' }],
  })),
  // Projected down to PlanCaseRef's six fields rather than handed the whole summary. The mock
  // used to serve a full TestCaseSummary here, which the contract no longer declares — and a mock
  // richer than the contract is a mock that lets a page read a field the real server never sends.
  notRun: caseSummaries
    .filter((c) => !c.lastResult)
    .slice(0, 5)
    .map((c) => ({
      id: c.id,
      key: c.key,
      keySeq: c.keySeq,
      title: c.title,
      suiteId: c.suiteId,
      suiteName: c.suiteName,
    })),
  exitCriteria: plans[0].exitCriteria ?? [],
  trend: [
    { runKey: 'TR-413', executedAt: '2026-07-24T02:30:00Z', passRate: 95.3 },
    { runKey: 'TR-414', executedAt: '2026-07-25T16:20:00Z', passRate: 100 },
    { runKey: 'TR-415', executedAt: '2026-07-27T02:34:00Z', passRate: 83.0 },
    { runKey: 'TR-416', executedAt: '2026-07-28T02:36:00Z', passRate: 85.1 },
    { runKey: 'TR-417', executedAt: '2026-07-29T02:38:00Z', passRate: 93.6 },
    { runKey: 'TR-418', executedAt: '2026-07-30T02:41:00Z', passRate: 89.1 },
  ],
};
