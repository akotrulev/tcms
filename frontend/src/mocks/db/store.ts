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
  ClassificationDimension,
  Condition,
  Configuration,
  DocPage,
  Project,
  ProjectSettings,
  RoleAssignment,
  Suite,
  Team,
  TestCase,
  TestCaseSummary,
  TestExecution,
  TestPlan,
  TestRun,
  WorkItem,
} from '../../api/types';
import type { CaseVersion } from '../../api/queries/cases';
import type { MockReview } from './reviews';
import { caseSummaries } from '../fixtures/cases';
import {
  artifacts,
  caseStatuses,
  conditions,
  configurations,
  dimensions,
  teams,
  docPageDetail,
  docPages,
  plans,
  projects,
  reviews,
  roleAssignments,
  runs,
  sharedSteps,
  statusByKind,
  suites,
  uid,
  users,
  workItems as workItemSeeds,
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
  /**
   * The project list — mutable, because it is now written to.
   *
   * It was read straight from the fixture until `POST /projects` existed, and a create that does
   * not land in the list is precisely the lie this store exists to prevent: the picker would report
   * success and then render the same cards it rendered before.
   */
  projects: Project[];
  /**
   * The grants — mutable, for the same reason and a sharper one.
   *
   * The whole point of `POST /role-assignments` is that somebody can now see a project. An admin
   * screen that reports success and then renders the same four rows is not merely stale; it is
   * reporting the opposite of what happened.
   */
  roleAssignments: RoleAssignment[];
  suites: Suite[];
  cases: TestCaseSummary[];
  /** Full detail, lazily materialized on first read so edits survive a re-read. */
  caseDetails: Map<string, TestCase>;
  /**
   * Case id → its authored versions, **newest first**, lazily materialized like `caseDetails`.
   *
   * Held rather than recomputed per request, because the panel that reads it has to show the
   * version an edit *just wrote*, carrying the comment its author typed. Synthesizing the list from
   * `currentVersionNo` on every read — which is what this mock did before there was a server to
   * disagree with — makes the history a function of a counter: every entry gets the same invented
   * author, no comment survives a save, and the round-trip the mock exists to prove cannot happen.
   */
  caseVersions: Map<string, CaseVersion[]>;
  conditions: Condition[];
  sharedSteps: typeof sharedSteps;
  plans: TestPlan[];
  /**
   * The review record (plan §6g), mutable because submitting and approving write to it — the whole
   * point of the panel is that a verdict recorded in mock mode is there on the next read.
   */
  reviews: MockReview[];
  /**
   * The projects' §6g settings, keyed by project id — mutable, because the settings screen writes
   * them and every artifact screen reads them to decide whether a review panel exists at all.
   *
   * **Seeded ON for the demo project and OFF for the others**, deliberately. §72's rule is that the
   * mock's fixtures must not sit in a state a fresh install cannot produce — and here the trap runs
   * both ways, because the fixtures already carry reviews. A store that defaulted every project to
   * "review on" would show mock mode a panel that a real, freshly provisioned project never has;
   * one that defaulted them all to off would hide the review fixtures §76 added and make the panel
   * look unbuilt. Both states are real, so both are present.
   */
  projectSettings: Map<string, ProjectSettings>;
  runs: TestRun[];
  executions: Map<string, TestExecution[]>;
  docPages: typeof docPages;
  docPageDetails: Record<string, DocPage>;
  artifacts: Artifact[];
  /**
   * The classification vocabulary — in the STORE since it got a writer, not read from the fixture.
   *
   * Every other vocabulary here was already cloned; this one was imported directly by its handler
   * because nothing could change it. A value added through the editor has to survive to the next
   * `GET /classification` or the picker it exists to fill would never show it.
   */
  dimensions: ClassificationDimension[];
  /**
   * The org chart — in the STORE since §71, not read from the fixture.
   *
   * Same move `dimensions` made and for the same reason: it was imported directly by its handler
   * because nothing could change it. A team created in the editor has to survive to the next
   * `GET /teams`, or the grant dialog's team picker would still be offering the fixture's three.
   *
   * It is also the store entry that makes mock mode stop agreeing with itself. `team` had no writer
   * on the server for the whole life of this screen, so live mode served `[]` while these fixtures
   * made the mock look finished.
   */
  teams: Team[];
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
   * The same mechanism for the two M2 creates, kept as separate maps rather than one widened to a
   * union: a lookup that can answer a `TestPlan` to the case endpoint is a replay that returns the
   * wrong shape, and the type is the only thing that would have caught it.
   */
  planIdempotency: Map<string, TestPlan>;
  runIdempotency: Map<string, TestRun>;
  /**
   * Plan id → the suite ids in its scope.
   *
   * The wire shape carries `caseCount`, not the scope, so the mock has to remember what was sent
   * in order to answer the two questions that depend on it: the plan's own count, and — the one
   * that matters — **which cases a run created from this plan materialises**. Without it a plan
   * created in mock mode has an empty scope, and a run started from it is an empty checklist,
   * which is precisely the state the server refuses.
   */
  planScope: Map<string, string[]>;
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
  projects: clone(projects),
  roleAssignments: clone(roleAssignments),
  suites: clone(suites),
  cases: clone(caseSummaries),
  caseDetails: new Map(),
  caseVersions: new Map(),
  conditions: clone(conditions),
  sharedSteps: clone(sharedSteps),
  plans: clone(plans),
  reviews: clone(reviews) as MockReview[],
  projectSettings: new Map(),
  runs: clone(runs),
  executions: new Map(),
  docPages: clone(docPages),
  docPageDetails: clone(docPageDetail),
  artifacts: clone(artifacts),
  dimensions: clone(dimensions),
  teams: clone(teams),
  statuses: clone(caseStatuses),
  configurations: clone(configurations),
  workItems: clone(workItemSeeds),
  idempotency: new Map(),
  planIdempotency: new Map(),
  runIdempotency: new Map(),
  planScope: new Map(),
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
