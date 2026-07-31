/** Case detail materialization, and the write that keeps the list and the page agreeing. */
import type { TestCase, TestCaseSummary } from '../../api/types';
import { buildCaseDetail } from '../fixtures/cases';
import { db } from './store';

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
