/**
 * Test case fixtures.
 *
 * A dozen hand-written cases carry the interesting shapes (Gherkin vs structured,
 * inherited conditions, doc links with a stale one, an AUTOMATED case that stopped
 * reporting), then the set is padded to a realistic volume so grids, facets and
 * pagination are exercised against something that behaves like a real repository.
 */
import type { HealthSignal, TestCase, TestCaseSummary } from '../../api/types';
import { conditions, currentUser, suites, uid, users } from './core';

const ref = (i: number) => ({
  id: users[i].id,
  displayName: users[i].displayName,
  email: users[i].email,
  avatarUrl: null,
});

const SUITE_REFUNDS = suites[2].id;
const SUITE_PARTIAL = suites[3].id;
const SUITE_CAPTURE = suites[1].id;
const SUITE_CART = suites[5].id;
const SUITE_SETTLE = suites[6].id;
const SUITE_QUARANTINE = suites[7].id;

const suiteName = (id: string) => suites.find((s) => s.id === id)?.name ?? '';

interface Seed {
  key: string;
  title: string;
  suiteId: string;
  priority: TestCase['priority'];
  lifecycle: TestCase['lifecycleStatus'];
  automation: TestCase['automationStatus'];
  format: TestCase['stepFormat'];
  owner: number;
  type: string;
  level: string;
  features: string[];
  health: HealthSignal[];
  last?: { statusKind: string; executedAt: string; releaseVersion: string };
  tags?: string[];
}

const seeds: Seed[] = [
  { key: 'TC-1', title: 'Full refund of a settled card payment succeeds', suiteId: SUITE_REFUNDS, priority: 'CRITICAL', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'GHERKIN', owner: 0, type: 'Functional', level: 'API', features: ['Payments', 'Refunds'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:11:00Z', releaseVersion: '4.3.0' }, tags: ['smoke'] },
  { key: 'TC-2', title: 'Full refund moves the original payment to REFUNDED', suiteId: SUITE_REFUNDS, priority: 'HIGH', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'GHERKIN', owner: 0, type: 'Functional', level: 'API', features: ['Refunds'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:12:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-3', title: 'A payment cannot be refunded twice', suiteId: SUITE_REFUNDS, priority: 'HIGH', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 1, type: 'Functional', level: 'API', features: ['Refunds'], health: ['OUTDATED_SPEC_CHANGED'], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:13:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-4', title: 'Refund appears in the merchant statement within one banking day', suiteId: SUITE_REFUNDS, priority: 'MEDIUM', lifecycle: 'APPROVED', automation: 'MANUAL', format: 'STRUCTURED', owner: 1, type: 'Functional', level: 'UI / E2E', features: ['Refunds', 'Settlement'], health: ['LONG_UNEXECUTED'], last: { statusKind: 'PASSED', executedAt: '2026-05-02T13:00:00Z', releaseVersion: '4.1.0' } },
  { key: 'TC-5', title: 'Partial refund reduces the refundable balance', suiteId: SUITE_PARTIAL, priority: 'CRITICAL', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'GHERKIN', owner: 0, type: 'Functional', level: 'API', features: ['Refunds', 'Partial refund'], health: ['STALE_APPROVAL'], last: { statusKind: 'FAILED', executedAt: '2026-07-30T02:14:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-6', title: 'Several partial refunds may not exceed the captured amount', suiteId: SUITE_PARTIAL, priority: 'CRITICAL', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'GHERKIN', owner: 0, type: 'Functional', level: 'API', features: ['Partial refund'], health: [], last: { statusKind: 'FAILED', executedAt: '2026-07-30T02:15:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-7', title: 'Refund reason is recorded in the audit log', suiteId: SUITE_REFUNDS, priority: 'LOW', lifecycle: 'APPROVED', automation: 'MANUAL', format: 'STRUCTURED', owner: 2, type: 'Functional', level: 'UI / E2E', features: ['Refunds'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-29T14:00:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-8', title: 'Partial refund above the remaining balance is rejected', suiteId: SUITE_PARTIAL, priority: 'HIGH', lifecycle: 'IN_REVIEW', automation: 'TO_BE_AUTOMATED', format: 'GHERKIN', owner: 1, type: 'Functional', level: 'API', features: ['Partial refund'], health: ['NEVER_EXECUTED'] },
  { key: 'TC-9', title: 'Refund is rejected after the 180-day window', suiteId: SUITE_REFUNDS, priority: 'MEDIUM', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 1, type: 'Functional', level: 'API', features: ['Refunds'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:16:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-10', title: 'Refund exactly on day 180 is accepted', suiteId: SUITE_REFUNDS, priority: 'MEDIUM', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 1, type: 'Functional', level: 'API', features: ['Refunds'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:17:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-11', title: 'Refunding a disputed payment is blocked', suiteId: SUITE_REFUNDS, priority: 'HIGH', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 0, type: 'Functional', level: 'API', features: ['Refunds'], health: ['STALE_AUTOMATION'], last: { statusKind: 'PASSED', executedAt: '2026-06-14T02:10:00Z', releaseVersion: '4.2.0' } },
  { key: 'TC-12', title: 'Partial refund of a multi-currency payment uses the capture rate', suiteId: SUITE_PARTIAL, priority: 'HIGH', lifecycle: 'DRAFT', automation: 'TO_BE_AUTOMATED', format: 'STRUCTURED', owner: 2, type: 'Functional', level: 'API', features: ['Partial refund'], health: ['NEVER_EXECUTED', 'INCOMPLETE'] },
  { key: 'TC-13', title: 'Card capture with a valid PAN succeeds', suiteId: SUITE_CAPTURE, priority: 'CRITICAL', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'GHERKIN', owner: 1, type: 'Functional', level: 'API', features: ['Card capture'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:02:00Z', releaseVersion: '4.3.0' }, tags: ['smoke'] },
  { key: 'TC-14', title: 'Capture is declined for an expired card', suiteId: SUITE_CAPTURE, priority: 'HIGH', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'GHERKIN', owner: 1, type: 'Functional', level: 'API', features: ['Card capture'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:03:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-15', title: 'Capture form rejects a non-numeric PAN before submit', suiteId: SUITE_CAPTURE, priority: 'LOW', lifecycle: 'APPROVED', automation: 'MANUAL', format: 'STRUCTURED', owner: 3, type: 'Usability', level: 'UI / E2E', features: ['Card capture'], health: ['UNLINKED'], last: { statusKind: 'PASSED', executedAt: '2026-07-22T10:00:00Z', releaseVersion: '4.2.1' } },
  { key: 'TC-16', title: 'Capture endpoint rejects an unauthenticated caller', suiteId: SUITE_CAPTURE, priority: 'CRITICAL', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 0, type: 'Security', level: 'API', features: ['Card capture'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:04:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-17', title: 'Capture sustains 500 requests per second for 10 minutes', suiteId: SUITE_CAPTURE, priority: 'MEDIUM', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 1, type: 'Performance', level: 'API', features: ['Card capture'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-27T22:00:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-18', title: 'Cart total updates when a line quantity changes', suiteId: SUITE_CART, priority: 'HIGH', lifecycle: 'DRAFT', automation: 'MANUAL', format: 'STRUCTURED', owner: 3, type: 'Functional', level: 'UI / E2E', features: ['Cart'], health: ['NEVER_EXECUTED', 'UNLINKED'] },
  { key: 'TC-19', title: 'Removing the last cart line shows the empty state', suiteId: SUITE_CART, priority: 'LOW', lifecycle: 'DRAFT', automation: 'MANUAL', format: 'STRUCTURED', owner: 3, type: 'Usability', level: 'UI / E2E', features: ['Cart'], health: ['NEVER_EXECUTED', 'UNLINKED'] },
  { key: 'TC-20', title: 'Cart survives a page reload', suiteId: SUITE_CART, priority: 'MEDIUM', lifecycle: 'IN_REVIEW', automation: 'TO_BE_AUTOMATED', format: 'STRUCTURED', owner: 2, type: 'Functional', level: 'UI / E2E', features: ['Cart'], health: ['NEVER_EXECUTED'] },
  { key: 'TC-21', title: 'Nightly settlement batches all captured payments', suiteId: SUITE_SETTLE, priority: 'CRITICAL', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 0, type: 'Functional', level: 'Integration', features: ['Settlement'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-30T03:00:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-22', title: 'Settlement report reconciles to the ledger', suiteId: SUITE_SETTLE, priority: 'CRITICAL', lifecycle: 'APPROVED', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 0, type: 'Functional', level: 'Database', features: ['Settlement'], health: [], last: { statusKind: 'BLOCKED', executedAt: '2026-07-30T03:05:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-23', title: 'Settlement retries a failed bank submission', suiteId: SUITE_SETTLE, priority: 'HIGH', lifecycle: 'APPROVED', automation: 'NOT_AUTOMATABLE', format: 'STRUCTURED', owner: 1, type: 'Functional', level: 'Integration', features: ['Settlement'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-18T09:00:00Z', releaseVersion: '4.2.1' } },
  { key: 'TC-24', title: 'Screen reader announces the refund confirmation', suiteId: SUITE_REFUNDS, priority: 'MEDIUM', lifecycle: 'APPROVED', automation: 'NOT_AUTOMATABLE', format: 'STRUCTURED', owner: 2, type: 'Accessibility', level: 'UI / E2E', features: ['Refunds'], health: [], last: { statusKind: 'PASSED', executedAt: '2026-07-10T11:00:00Z', releaseVersion: '4.2.1' } },
  { key: 'TC-25', title: 'PaymentsApiTest#shouldRejectZeroAmount', suiteId: SUITE_QUARANTINE, priority: 'MEDIUM', lifecycle: 'DRAFT', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 0, type: 'Functional', level: 'API', features: [], health: ['UNLINKED', 'INCOMPLETE'], last: { statusKind: 'PASSED', executedAt: '2026-07-30T02:20:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-26', title: 'PaymentsApiTest#shouldHandleIdempotencyKey', suiteId: SUITE_QUARANTINE, priority: 'MEDIUM', lifecycle: 'DRAFT', automation: 'AUTOMATED', format: 'STRUCTURED', owner: 0, type: 'Functional', level: 'API', features: [], health: ['UNLINKED', 'INCOMPLETE'], last: { statusKind: 'FAILED', executedAt: '2026-07-30T02:21:00Z', releaseVersion: '4.3.0' } },
  { key: 'TC-27', title: 'Refund webhook is delivered within 30 seconds', suiteId: SUITE_REFUNDS, priority: 'MEDIUM', lifecycle: 'DEPRECATED', automation: 'MANUAL', format: 'STRUCTURED', owner: 4, type: 'Functional', level: 'Integration', features: ['Refunds'], health: ['ORPHANED'], last: { statusKind: 'PASSED', executedAt: '2026-03-11T10:00:00Z', releaseVersion: '4.0.0' } },
];

function toSummary(s: Seed, index: number): TestCaseSummary {
  return {
    id: uid(100 + index, 'e'),
    key: s.key,
    keySeq: index + 1,
    title: s.title,
    suiteId: s.suiteId,
    suiteName: suiteName(s.suiteId),
    priority: s.priority,
    lifecycleStatus: s.lifecycle,
    automationStatus: s.automation,
    stepFormat: s.format,
    owner: ref(s.owner),
    testType: s.type,
    testLevel: s.level,
    features: s.features,
    tags: s.tags ?? [],
    healthSignals: s.health,
    lastResult: s.last
      ? {
          statusKind: s.last.statusKind as TestCaseSummary['lastResult'] extends null
            ? never
            : NonNullable<TestCaseSummary['lastResult']>['statusKind'],
          executedAt: s.last.executedAt,
          releaseVersion: s.last.releaseVersion,
        }
      : null,
    updatedAt: '2026-07-28T12:00:00Z',
  };
}

export const caseSummaries: TestCaseSummary[] = seeds.map(toSummary);

const gherkinSteps = (id: string) => [
  { id: `${id}-s1`, position: 0, keyword: 'GIVEN' as const, action: 'a settled card payment of 120.00 EUR exists', testData: null, expectedResult: null, sharedStepId: null },
  { id: `${id}-s2`, position: 1, keyword: 'WHEN' as const, action: 'the merchant requests a full refund', testData: null, expectedResult: null, sharedStepId: null },
  { id: `${id}-s3`, position: 2, keyword: 'THEN' as const, action: 'the refund is accepted with status PENDING', testData: null, expectedResult: null, sharedStepId: null },
  { id: `${id}-s4`, position: 3, keyword: 'AND' as const, action: 'the original payment moves to REFUNDED', testData: null, expectedResult: null, sharedStepId: null },
  { id: `${id}-s5`, position: 4, keyword: 'AND' as const, action: 'the refundable balance becomes 0.00 EUR', testData: null, expectedResult: null, sharedStepId: null },
];

const structuredSteps = (id: string) => [
  { id: `${id}-s1`, position: 0, keyword: null, action: 'Open the payment detail for a settled payment', testData: 'Payment ref: PMT-88213', expectedResult: 'The payment detail drawer opens showing status SETTLED', sharedStepId: null },
  { id: `${id}-s2`, position: 1, keyword: null, action: 'Click Refund and enter the refund amount', testData: 'Amount: 45.00 EUR', expectedResult: 'The refund form accepts the amount and enables Confirm', sharedStepId: null },
  { id: `${id}-s3`, position: 2, keyword: null, action: 'Select a refund reason and confirm', testData: 'Reason: Customer request', expectedResult: 'A confirmation toast reads "Refund submitted" and the drawer shows a pending refund line', sharedStepId: null },
  { id: `${id}-s4`, position: 3, keyword: null, action: 'Open the audit log for the payment', testData: null, expectedResult: 'The newest audit entry records the refund, the amount, the reason and the acting user', sharedStepId: null },
];

const cond = (i: number, position: number, inherited = false, from: string | null = null) => ({
  id: `${conditions[i].id}-att-${position}`,
  position,
  conditionId: conditions[i].id,
  title: conditions[i].title,
  body: conditions[i].body,
  inline: false,
  inherited,
  inheritedFrom: from,
});

export function buildCaseDetail(summary: TestCaseSummary): TestCase {
  const isGherkin = summary.stepFormat === 'GHERKIN';
  return {
    ...summary,
    description:
      'Verifies the refund path end to end, including the effect on the original payment and the refundable balance.',
    objective: 'Prove that a merchant can recover funds for a settled payment without manual intervention.',
    expectedOutcome:
      'The refund is accepted, the original payment reaches a terminal refunded state, and the balance reflects the refunded amount.',
    severity: summary.priority,
    estimatedDurationSec: 240,
    automationTags: summary.automationStatus === 'AUTOMATED' ? ['@regression', '@payments'] : [],
    automationKey:
      summary.automationStatus === 'AUTOMATED'
        ? `com.example.payments.RefundIT#${summary.key.toLowerCase()}`
        : null,
    notAutomatableReason:
      summary.automationStatus === 'NOT_AUTOMATABLE'
        ? 'Requires a bank sandbox submission that cannot be triggered on demand.'
        : null,
    gherkinSource: isGherkin
      ? `Feature: Refunds\n\n  Scenario: ${summary.title}\n    Given a settled card payment of 120.00 EUR exists\n    When the merchant requests a full refund\n    Then the refund is accepted with status PENDING\n    And the original payment moves to REFUNDED\n    And the refundable balance becomes 0.00 EUR`
      : null,
    steps: isGherkin ? gherkinSteps(summary.id) : structuredSteps(summary.id),
    preconditions: [
      cond(0, 0, true, 'Refunds'),
      cond(3, 1),
    ],
    postconditions: [cond(4, 0)],
    relatedDocuments: [
      {
        id: `${summary.id}-dl1`,
        targetKind: 'DOC_BLOCK',
        relationship: 'VERIFIES',
        pageId: uid(2, 'p'),
        pageKey: 'DOC-2',
        pageTitle: 'Refunds',
        blockId: uid(204, 'b'),
        blockExcerpt:
          'A full refund returns the entire captured amount. The original payment moves to REFUNDED and cannot be refunded again.',
        externalRepo: null, externalPath: null, externalLineStart: null,
        externalLineEnd: null, externalCommitSha: null, externalUrl: null,
        stale: summary.healthSignals.includes('OUTDATED_SPEC_CHANGED'),
      },
      {
        id: `${summary.id}-dl2`,
        targetKind: 'EXTERNAL_GIT',
        relationship: 'DERIVED_FROM',
        pageId: null, pageKey: null, pageTitle: null, blockId: null, blockExcerpt: null,
        externalRepo: 'acme/payments-service',
        externalPath: 'docs/specs/refunds.md',
        externalLineStart: 118,
        externalLineEnd: 134,
        externalCommitSha: 'a3f91c2',
        externalUrl: null,
        stale: false,
      },
    ],
    currentVersionNo: summary.key === 'TC-5' ? 7 : 3,
    lockVersion: 3,
  };
}

export const currentUserRef = {
  id: currentUser.id,
  displayName: currentUser.displayName,
  email: currentUser.email,
  avatarUrl: null,
};
