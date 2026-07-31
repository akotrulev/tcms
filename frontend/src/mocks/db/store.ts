/**
 * The mock database — the store itself.
 *
 * Fixtures are *seeds*; this module holds the mutable copy the handlers read and write.
 * That distinction is the whole point — a stateless handler that returns a fixture makes
 * every create/edit screen a lie, because the list never reflects what you just saved.
 *
 * Everything is deep-cloned out of the fixtures at module load so a mutation here can
 * never write back into a `const` the rest of the app also imports.
 *
 * **The store stays one object.** The per-domain helpers around it live in sibling files, but
 * `db` itself does not split: the suite tree reads the case list, work items read executions,
 * and a store carved into per-domain pieces would have to reassemble itself before answering
 * any of those. One store, many readers.
 */
import type {
  Artifact,
  CaseStatus,
  Condition,
  Configuration,
  DocPage,
  Suite,
  TestCase,
  TestCaseSummary,
  TestExecution,
  TestPlan,
  TestRun,
  WorkItem,
} from '../../api/types';
import { caseSummaries } from '../fixtures/cases';
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
} from '../fixtures/core';
import {
  caseHistoryEntries,
  caseHistorySummary,
  caseVersionMarkers,
  dashboard,
  planStatistics,
  suiteHistoryEntries,
  suiteVersionMarkers,
} from '../fixtures/reporting';

const clone = <T>(value: T): T => structuredClone(value);

export function newId(): string {
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
  /**
   * Outstanding invitations, keyed by their token.
   *
   * Two seeded tokens rather than one, because the accept screen has two genuinely different
   * shapes and only one of them is the obvious case: an invitee with no account chooses a
   * password, while someone already on the instance being added to a second company must NOT
   * be offered one (the API rejects a password there rather than ignoring it).
   *
   * Redeeming sets `consumed`, so a second use answers 404 exactly as the real service does —
   * single-use is the property the screen's "this link no longer works" panel exists for, and
   * a mock that let a token work twice would make it unreachable.
   */
  invitations: Map<string, MockInvitation>;
}

export interface MockInvitation {
  email: string;
  displayName: string;
  passwordRequired: boolean;
  consumed: boolean;
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
  invitations: new Map<string, MockInvitation>([
    [
      // Open /accept-invitation?token=new-user-token to walk the first-account path.
      'new-user-token',
      {
        email: 'nina.marek@example.com',
        displayName: 'Nina Marek',
        passwordRequired: true,
        consumed: false,
      },
    ],
    [
      // …and ?token=existing-user-token for the second-company path, which offers no password.
      'existing-user-token',
      {
        email: 'ana.petrova@example.com',
        displayName: 'Ana Petrova',
        passwordRequired: false,
        consumed: false,
      },
    ],
  ]),
};

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

export { users, configurations, statusByKind, uid };
