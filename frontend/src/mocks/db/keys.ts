/**
 * Display-key allocation.
 *
 * Mirrors the backend contract: `PREFIX-N`, sequential **per company** per entity type
 * (plan §6t), with `keySeq` stored alongside the text key because `TC-10000` sorts before
 * `TC-9999` lexicographically — nothing may ever order by the string.
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
 *
 * All four allocators share one map, which is why they share one file: they are one counter
 * namespace, not four per-domain concerns that happen to look alike.
 */
import { db } from './store';

const sequences = new Map<string, number>();

function nextKey(prefix: string, seed: number): { key: string; keySeq: number } {
  const current = sequences.get(prefix) ?? seed;
  const keySeq = current + 1;
  sequences.set(prefix, keySeq);
  return { key: `${prefix}-${keySeq}`, keySeq };
}

sequences.set('TC', db.cases.length);
sequences.set('TS', db.suites.length);
sequences.set('TP', db.plans.length);
sequences.set('TR', 418);
sequences.set('DOC', db.docPages.length);

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

export function allocateDocPageKey() {
  return nextKey('DOC', 0);
}
