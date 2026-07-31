/** Case detail materialization, and the write that keeps the list and the page agreeing. */
import type { CaseVersion } from '../../api/queries/cases';
import type { TestCase, TestCaseSummary } from '../../api/types';
import { buildCaseDetail } from '../fixtures/cases';
import { db, reporting, users } from './store';

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

/**
 * A case's authored versions, newest first — materialized once, then appended to by writes.
 *
 * **The seeded history deliberately starts at v2, and that is not an off-by-one.** Every case in
 * the fixture set predates the write path, exactly as every case in a real installation upgraded to
 * it does: their first edit wrote v2 and their v1 is permanently absent (see `CaseVersions` on the
 * server, which refuses to backfill one — a synthetic v1 would assert that the pre-edit content was
 * the post-edit content). A mock that hands out a gapless 1..N makes the panel's "earliest
 * recorded" line unreachable in mock mode, which is the shape of §27's condition-order defect: the
 * mock agreeing with itself and disagreeing with the server.
 *
 * A case at v1 gets `[]` — the never-edited case older than the writer, whose history is genuinely
 * empty and must render as "no recorded history" rather than as a failure.
 *
 * Cases *created* through `POST` never reach here: the handler records their v1 as it writes them,
 * so their history is gapless from the first read. The two populations are distinguishable by
 * exactly the fact that matters — whether a v1 exists.
 */
export function caseVersions(id: string): CaseVersion[] | undefined {
  const existing = db.caseVersions.get(id);
  if (existing) return existing;

  const detail = caseDetail(id);
  if (!detail) return undefined;

  const current = detail.currentVersionNo ?? 1;
  const versions: CaseVersion[] = [];
  for (let versionNo = current; versionNo >= 2; versionNo--) {
    const marker = reporting.caseVersionMarkers.find((m) => m.versionNo === versionNo);
    versions.push({
      versionNo,
      // Null on the oldest surviving version of every case: `created_by` is nullable and carries no
      // FK, so an unattributable version is a state a real row is in — and it is the row most
      // likely to be one, because it predates the endpoint that records an author. Without one of
      // these in the fixtures, the panel's "Unknown author" branch is never rendered before it
      // reaches a customer's imported data.
      createdBy: versionNo === 2 ? null : (marker?.author ?? seededAuthor(versionNo)),
      createdAt: marker?.occurredAt ?? seededDate(versionNo),
      changeComment: marker?.changeComment ?? null,
      // A version is pinned when an execution still references it, which is what keeps past
      // reports honest. Nothing in the product writes an execution yet, so the server answers
      // false everywhere — but the contract allows true and the panel must render it, so one
      // seeded row does.
      pinned: versionNo === 3,
    });
  }

  db.caseVersions.set(id, versions);
  return versions;
}

/**
 * Prepends a version — newest first is the contract's order, so a new one goes to the front.
 *
 * Materializes the existing history first, and that call is load-bearing rather than tidy: reading
 * `db.caseVersions` directly would find nothing for a case nobody has opened the panel on yet, seed
 * the map with just the new row, and **permanently lose every earlier version** — the seeding pass
 * only ever runs on an absent key. The symptom is a case that had six versions showing one, right
 * after a save, which reads as the save having destroyed the history.
 */
export function recordCaseVersion(id: string, version: CaseVersion): void {
  db.caseVersions.set(id, [version, ...(caseVersions(id) ?? [])]);
}

const seededAuthor = (versionNo: number) => {
  const user = users[versionNo % users.length];
  return { id: user.id, displayName: user.displayName, email: user.email, avatarUrl: null };
};

/** Stable, so a re-read does not shuffle the dates under a panel the user is looking at. */
const seededDate = (versionNo: number) =>
  new Date(Date.UTC(2026, 4, versionNo, 9, 30)).toISOString();
