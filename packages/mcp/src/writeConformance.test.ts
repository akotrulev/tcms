/**
 * Surface spec §8.3 over the tool registry — *every create arm sends a key, every edit arm sends
 * `If-Match`, over the registry rather than per tool*.
 *
 * **Four comments in this package have cited this check since §140 and it did not exist.** What
 * `writes.test.ts` held under the name `header conformance` reads the registry's *declarations* — that
 * every arm's `kind` is one the table knows, that every permission is well-formed, that every arm's
 * permission is inside its tool's gate — and asserts nothing whatever about a header. Its own file
 * header says it is *"the same check the CLI runs over its command registry, deliberately … two
 * conformance tests that disagreed about what a create is would be the thing that lets them drift"*.
 * They disagreed: the CLI's drives each command through a recording `fetch` and reads what arrived,
 * and this side, on eight of its twenty-two arms, drove nothing at all.
 *
 * A declaration check cannot fail on the thing the declaration is *for*. `kind: 'create'` and a `run`
 * that forgets `createIntent` are consistent with each other — the registry says what the arm should
 * do and the arm is the only thing that does it — so the two agree right up until the request goes out
 * without the header. That is the whole population this file exists over.
 *
 * ## The rule is two questions, not one
 *
 * The first is the spec's: **did the arm send what its `kind` implies.** The second arrived with this
 * file and is the one that found something: **does the operation it wrote to declare that header at
 * all.** A create sending `Idempotency-Key` at an endpoint that takes no such parameter looks correct
 * from every angle inside this package — the arm is declared a create, it sends a key, the check that
 * reads only declarations passes — and buys the caller nothing, which is the mistake the parameter's
 * own description in `openapi.yaml` names in as many words: *"a key generated per attempt is the
 * mistake that makes the header look right and do nothing."* One arm was in that state; see
 * {@link CREATE_WITHOUT_A_KEY}.
 *
 * ## Why the table is hand-maintained and the check still fails on what it does not know
 *
 * An arm is `{action, kind, permission}` and nothing in it says how to call the tool — the input a
 * `propose` needs and the reads a `record` makes are properties of the handler. So {@link INVOCATIONS}
 * is written out, and a registry entry with no invocation is a **failure** rather than a skip: a
 * conformance check that silently ignores what it does not recognise is a check on its own table,
 * which is §108's shape and the reason the CLI's says the same thing.
 */
import { describe, expect, it } from 'vitest';

import {
  contractPathOfRequest,
  declaredHeadersOf,
  declaredPermissionsOf,
} from '../../client/src/test/contractBodies.js';
import {
  absence,
  allocation,
  artifact,
  board,
  boardSummary,
  bulkResult,
  cardMoveResult,
  label,
  rankResult,
  caseDetail,
  casePage,
  caseStatus,
  caseSummary,
  docPage,
  docPageSummary,
  execution,
  executionSummary,
  plan,
  proposal,
  review,
  run as runFixture,
  runDetail,
  issue,
  issueComment,
  issueTestCase,
  projectWorkflow,
  timeEntry,
  timesheetPeriod,
  userRef,
  release as releaseFixture,
  releaseSummary,
  suite,
  client as clientFixture,
  clientContact,
  clientDetail,
  budget,
  expense,
  invoiceDetail,
  rateCard,
  rateCardSlot,
} from '../../client/src/test/fixtures.js';
import { WRITE_TOOLS } from './writes.js';
import { api, agentRoutes, sessionWith, withApi, type Answer, type Api } from './test/harness.js';

/** The headers each `kind` is a promise about — surface spec §8.3, as the CLI's copy states it. */
const IMPLIED: Record<string, readonly string[]> = {
  create: ['idempotency-key'],
  edit: ['if-match'],
  // Neither, and asserted rather than left unsaid: a state transition names an absolute target and
  // a delete names a row, so a precondition here would refuse a request over a version the caller
  // never claimed to have based anything on.
  state: [],
  delete: [],
};

/** Both halves of the question, so a header sent where none is implied fails too. */
const WATCHED = ['idempotency-key', 'if-match'] as const;

/**
 * Arms whose endpoint does not offer the header their kind implies, with what that costs.
 *
 * ~~Create arms whose endpoint offers no `Idempotency-Key`~~ — the map was named for its only
 * member and audit C10 gave it a second of a different shape, so the name is now narrower than
 * the population. Left as it is rather than renamed: it is quoted by name in the failure message
 * above and in `writes.ts`, and a rename is a diff across three files that says nothing new.
 *
 * Empty is the target. Two members were the measurement at §234; ONE is, since V66 closed the
 * report generate (audit E2).
 */
const CREATE_WITHOUT_A_KEY: Record<string, string> = {
  'tcms_write_client.record':
    'POST /clients declares no Idempotency-Key, and the parameter\'s own scope is the reason '
    + '`tcms_write_absence.record` below gives: it deduplicates within `(company, project, key)` '
    + 'and a client is addressed to no project. What a lost response costs is bounded by a '
    + 'CONSTRAINT rather than by a header — `uq_client_name UNIQUE (company_id, name)` means the '
    + 'retry answers 400 on `name` rather than recording the organisation twice.',
  'tcms_write_client.contact-add':
    'POST /clients/{clientId}/contacts, the same scope wall, and this one has no constraint behind '
    + 'it: `client_contact` is deliberately NOT unique on email, because two people at one client '
    + 'may share invoices@. So a lost response leaves a duplicate contact — one row, on a list the '
    + 'client page renders in full, with a delete beside it.',
  'tcms_write_client.edit':
    'PUT /clients/{clientId} declares no If-Match and `Client` carries no lockVersion, so two '
    + 'people editing one client at once lose the earlier edit. This arm reads the record before '
    + 'writing it, which narrows the window but does not close it — closing it is a contract change '
    + 'on both the response and the request, as it is for `tcms_write_artifact.edit` below.',
  'tcms_write_client.contact-edit':
    'PUT /clients/{clientId}/contacts/{contactId}, the same wall as `tcms_write_client.edit` above '
    + 'and listed separately rather than covered by implication. Narrower: the loss is one '
    + 'person\'s phone number or role, on a record the client page shows in full.',
  'tcms_write_billing.draft-invoice':
    'POST /invoices declares no Idempotency-Key, the same scope wall as `tcms_write_client.record` '
    + 'above: the parameter deduplicates within `(company, project, key)` and an invoice is '
    + 'addressed to a CLIENT. There is no constraint standing in for it either — two drafts over '
    + 'one period for one client are a legitimate state, so a lost response leaves a second draft. '
    + 'It cannot double-bill: a draft stores no lines, both drafts offer the same hours, and '
    + 'whichever is issued first takes them (019 D-c).',
  'tcms_write_billing.record-expense':
    'POST /expenses, the same wall, and an expense is addressed to a project — so this one is the '
    + 'parameter\'s own scope and is closable by adding it. It has not been added because nothing '
    + 'in the product reads an expense back except the CLI verb that lists them (019 D-j), so a '
    + 'duplicate is visible and removable by the same caller that made it.',
  'tcms_write_billing.set-budget':
    'PUT /projects/{projectId}/budget declares no If-Match, and `Budget` carries no lockVersion on '
    + 'the wire to precondition on — the wall every edit entry here meets. Bounded by the row: a '
    + 'budget is a basis, an amount and a currency, and two people setting one at once lose a '
    + 'ceiling rather than a body of work.',
  'tcms_write_billing.issue-invoice':
    'POST …/issue declares no If-Match, and the arm is declared `delete` for its consent prompt '
    + 'rather than `edit`, so the map would not require one either way. Listed anyway because it is '
    + 'the one IRREVERSIBLE act in the module and a reader checking what protects it should find '
    + 'the answer here: the SERVER does, with a 409 for an invoice that is already ISSUED. A '
    + 'precondition header would be a weaker guard than a state check, not a stronger one.',
  'tcms_write_rate_card.set':
    'PUT /rate-cards/{rateCardId} declares no If-Match and `RateCard` carries no lockVersion, so '
    + 'two people editing one card at once lose the earlier edit — `tcms_write_client.edit`\'s wall '
    + 'exactly. WORSE here than there, and stated rather than implied: the arm is a FULL '
    + 'replacement including the entries, so the loss is a whole rate card and not one field of '
    + 'one. The POST half of this arm meets the create wall instead, and `uq_rate_card_client` '
    + 'bounds it — a retried create answers 409 rather than giving one client two cards.',
  'tcms_write_issue.update':
    'PATCH /issues/{key} declares no If-Match, and there is nothing to precondition on even if it '
    + 'did: `Issue` carries no `lockVersion` on the wire at all. So closing this is a contract '
    + 'change on both the response and the request, not a header here — the same wall the artifact '
    + 'edit below meets. What it costs is bounded by the endpoint being a TRUE delta: `IssueUpdate` '
    + 'says an absent key means leave it, so two callers editing one issue lose only the fields '
    + 'they both named, where the case, suite and plan arms would overwrite the whole set.',
  'tcms_write_backlog.label-update':
    'PATCH /labels/{labelId} declares no If-Match, and `Label` carries no `lockVersion` on the wire '
    + 'to precondition on, so closing it is a contract change on both halves rather than a header '
    + 'here — the same wall the issue update above meets. What it costs is bounded by the row: a '
    + 'label is a name and a colour token, the arm sends both because `LabelInput` requires both, '
    + 'and two people renaming one label at once lose a rename rather than a body of work.',
  'tcms_write_issue.attach-pr':
    'PATCH /issues/{key} a third time, so it inherits the issue update entry above and is listed '
    + 'separately for the same reason `release` is — a by-decision map that covered an arm by '
    + 'implication could not report the day one of them stopped needing it. What it costs is the '
    + 'narrowest of the three: the arm sends one field that only ever goes from empty to a URL, so '
    + 'two callers racing it lose a link to a pull request that is still in the history as a '
    + '`issue.pr_url_set` row.',
  'tcms_write_allocation.change':
    'PATCH …/allocations/{allocationId} declares no If-Match, and `Allocation` carries no '
    + '`lockVersion` on the wire to precondition on — the wall the entries above meet, and it '
    + 'arrives with the schema rather than after it (017 T1). Bounded by the endpoint being a TRUE '
    + 'delta: two people editing one allocation lose only the fields they both named, and the set '
    + 'is a percentage, two dates and a note.',
  'tcms_write_absence.record':
    'POST …/absences declares no Idempotency-Key, and the parameter\'s own scope is the reason: it '
    + 'deduplicates within `(company, project, key)` and an absence is not addressed to a project. '
    + 'What a lost response costs is a duplicate absence row over the same days, which '
    + 'double-counts nothing — capacity is computed per DAY from the absences covering it (017 D-a), '
    + 'so a second identical row removes the same hours the first did, and it is visible on the '
    + 'person\'s own absence list to remove.',
  'tcms_write_artifact.edit':
    'PATCH …/artifacts/{artifactId} declares no If-Match, so two callers correcting one register '
    + 'row race and the later write wins silently. It cannot simply be added: the endpoint would '
    + 'need a version to compare, and `Artifact` does not carry `lockVersion` on the wire at all — '
    + 'the column exists and is deliberately unserved (§229). So closing this is a contract change '
    + 'on both the response and the request, not a header here. What it costs is bounded by what '
    + 'the edit can reach: three metadata fields, no payload and no scope, on a row whose real '
    + 'corrections go through supersede.',
  'tcms_write_release.update':
    'PATCH /releases/{releaseId} declares no If-Match, and `Release` carries no `lockVersion` on '
    + 'the wire to precondition on — the same wall the three above meet, and it arrives with the '
    + 'schema rather than after it (013 T1). Bounded by the endpoint being a true delta: two '
    + 'people editing one release lose only the fields they both named, and the set is three — a '
    + 'version, a date and a description.',
  'tcms_write_issue.release':
    'PATCH /issues/{key} again, so this shares the issue update entry above verbatim and is listed '
    + 'separately because a by-decision map that covered an arm by implication could not report '
    + 'the day one of them stopped needing it. What it costs here is narrower still: the arm sends '
    + 'one field, so two callers retargeting one issue in the same moment lose a release target '
    + 'and nothing else.',
  // ~~'tcms_report.generate' — POST …/reports/generate declares no Idempotency-Key and
  // ReportController takes no such header, so a retried 202 queues a second job.~~ → CLOSED by
  // V66 (audit E2): the endpoint declares the parameter and replays it, and the arm sends a key
  // again. It was the older of the two entries and the one §205 found; what remains is C10's,
  // which is an If-Match the contract cannot yet express.
};

describe('write conformance — surface spec §8.3', () => {
  const arms = WRITE_TOOLS.flatMap((tool) =>
    (tool.arms ?? []).map((arm) => ({ tool, arm, id: `${tool.name}.${arm.action}` })));

  it('finds every arm of every write tool', () => {
    // A vacuity guard. A registry this could not see would make every assertion below pass by
    // having nothing to assert on, which reads identically to a green run.
    expect(arms.length).toBeGreaterThanOrEqual(31);
    expect(WRITE_TOOLS.every((tool) => (tool.arms ?? []).length > 0)).toBe(true);
  });

  it('can read a stated gate out of the contract at all', () => {
    /*
      The §8.1 check's vacuity guard, and it has to be a guard on the READER rather than a count of
      arms: the gate is read out of each operation's prose, and prose is a source that can quietly
      stop answering. A rewrite that drops the sentence, or a `Requires` spelled differently, turns
      every assertion below into a no-op that reads exactly like a pass.
    */
    const suite = '/projects/{projectId}/suites/{suiteId}';
    expect(declaredPermissionsOf('delete', suite)).toContain('suite:delete');
    expect(declaredPermissionsOf('patch', suite)).toContain('suite:manage');
  });

  it.each(arms.map((entry) => [entry.id, entry] as const))(
    '%s sends what its declaration implies',
    async (id, { arm }) => {
      const invocation = INVOCATIONS[id];
      if (!invocation) {
        throw new Error(
          `'${id}' declares a write and this test does not know how to invoke it. Add it to `
            + 'INVOCATIONS — the check exists so a new arm cannot ship without its headers being '
            + 'asserted, and skipping what it does not recognise would make it a check on this table.',
        );
      }

      const stub = api(agentRoutes(invocation.routes));
      await drive(stub, invocation);

      const sent = stub.write(invocation.path);
      const carried = WATCHED.filter((header) => sent.headers.get(header) !== null);

      /*
        Resolved from the URL the arm actually sent rather than from a column in the table below.
        A hand-written operation name would be a second declaration of where the arm writes, and it
        would agree with the arm right up until somebody moved one — which is the exact shape this
        file exists over, one layer up.
      */
      const contractPath = contractPathOfRequest(sent.path);
      expect(contractPath, `${id} wrote to ${sent.method} ${sent.path}, which resolves to no path in openapi.yaml`)
        .toBeDefined();
      const declared = declaredHeadersOf(sent.method.toLowerCase(), contractPath ?? '');
      expect(declared, `${id} wrote to ${sent.method} ${sent.path}, which the contract has no operation for`)
        .toBeDefined();

      const implied = IMPLIED[arm.kind];
      expect(implied, `${id} declares kind '${arm.kind}', which this check has no rule for`).toBeDefined();

      // What it *should* have sent: what the kind implies, minus anything the operation does not
      // offer. A header the endpoint never reads is not a weaker version of the guarantee, it is the
      // absence of it wearing the guarantee's name.
      const expected = (implied ?? []).filter((header) => (declared ?? []).includes(header));
      expect(carried, `${id} sent [${carried.join(', ')}] to ${sent.method} ${sent.path}`)
        .toEqual(expected);

      /*
        Surface spec §8.1 — *"assert its declared permission set equals the permission set the
        wrapped endpoints actually enforce, derived from the code rather than from a list in this
        document."* Specified in §140 and not built until now, which mattered because
        `writes.test.ts` asserts only that an arm's permission is inside its own tool's gate: that
        is self-consistency, and a gate wrong in both places is consistent.

        The endpoint the arm actually wrote to is the source, resolved above from the URL. §8.1's
        live catch is `tcms_review_verdict`, whose arms name `case:review` for a subject that may
        be a suite or a plan — see `ToolArm.permissionFor`, which is why the resolved key is
        checked rather than the declared one where an arm has both.
      */
      const enforced = declaredPermissionsOf(sent.method.toLowerCase(), contractPath ?? '') ?? [];
      if (enforced.length > 0) {
        const claimed = arm.permissionFor?.(invocation.input) ?? arm.permission;
        expect(enforced, `${id} declares '${claimed}' and ${sent.method} ${sent.path} requires `
          + `${enforced.map((key) => `'${key}'`).join(' or ')}. A refused caller is sent to an `
          + 'administrator to ask for the wrong thing.')
          .toContain(claimed);
      }

      const missing = (implied ?? []).filter((header) => !(declared ?? []).includes(header));
      if (missing.length > 0) {
        expect(CREATE_WITHOUT_A_KEY[id], `${id} is a '${arm.kind}' and ${sent.method} ${sent.path} `
          + `declares no ${missing.join(', ')}. Either the contract should offer it, or the shortfall `
          + 'is a decision and belongs in CREATE_WITHOUT_A_KEY with what it costs a caller.')
          .toBeDefined();
      }
    },
  );

  it('records nothing as a decision that is not one', () => {
    // The by-decision map's other direction, §162's rule: a one-way ignore list cannot report a
    // member that stopped needing to be on it, so the entry has to name a live arm.
    for (const id of Object.keys(CREATE_WITHOUT_A_KEY)) {
      expect(arms.some((entry) => entry.id === id), `${id} is not an arm in the registry`).toBe(true);
    }
  });
});

/* ------------------------------------------------------------------- driving ---- */

interface Invocation {
  input: Record<string, unknown>;
  /** The tool to call — the arm's `action` goes in `input` where the tool takes one. */
  tool: string;
  /** A suffix of the path the write goes to, as `Api.write` matches it. */
  path: string;
  routes: Record<string, Answer>;
}

async function drive(stub: Api, invocation: Invocation): Promise<void> {
  const tool = WRITE_TOOLS.find((candidate) => candidate.name === invocation.tool);
  if (!tool) throw new Error(`No write tool ${invocation.tool}`);
  const session = await sessionWith(stub);
  await withApi(stub, () => tool.run({ ...session, callId: 'mcp-7' }, invocation.input));
}

/* ----------------------------------------------------------------- fixtures ---- */

const CASE_ROW = caseSummary();
const CASE = caseDetail();
const CASES = { '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) } } as const;

const SUITE = suite();
const SUITES = { 'GET /suites': { status: 200, body: [SUITE] } } as const;

const PAGE = docPage();
const PAGES = { 'GET /doc-pages': { status: 200, body: [docPageSummary()] } } as const;

const PLAN = plan();
const PLANS = { 'GET /plans': { status: 200, body: [PLAN] } } as const;

const RUN = runFixture({ key: 'TR-1' });
const RUNS = { 'GET /runs': { status: 200, body: [RUN] } } as const;

const ARTIFACT = artifact();
// The edit arm reads the register to compose the whole-set body — `ArtifactEditInput` requires
// title and category, so an edit naming only one of them would clear the other.
const ARTIFACTS = { 'GET /artifacts': { status: 200, body: [ARTIFACT] } } as const;

const REVIEW = review({ id: 'rv-1', state: 'IN_REVIEW' });
const REVIEWS = { 'GET /reviews': { status: 200, body: [REVIEW] } } as const;

const ISSUE = issue();
/** The create and move arms resolve a NAME against this, which is the point of taking one. */
const WORKFLOW = { 'GET /workflow': { status: 200, body: projectWorkflow() } } as const;

const PERIOD_ID = 'tsp-1';
/** Whatever the decision was, the endpoint answers the period — one body serves all three arms. */
const DECIDED = timesheetPeriod({ state: 'APPROVED', editable: false, loggedHours: 21, approvedHours: 21 });

const BOARD = board();
/*
  Method-keyed, and here it has to be: `/projects/p-1/labels` and `/issues/ACME-124/labels` both end
  in `/labels`, so an unkeyed route would answer the issue's PUT with the project's list and the
  exchange check would report the wrong body against the wrong operation.
*/
const BOARDS = {
  'GET /boards': { status: 200, body: [boardSummary()] },
  'GET /boards/bd-1': { status: 200, body: BOARD },
} as const;
const LABELS = { 'GET /labels': { status: 200, body: [label()] } } as const;

const RELEASE = releaseFixture();
/** The version → id lookup every release-naming arm makes, method-keyed like the board routes. */
const RELEASES = { 'GET /releases': { status: 200, body: [releaseSummary()] } } as const;

/** The name → id lookup the allocate arm makes, so `person` may be typed rather than pasted. */
const MEMBERS = { 'GET /members': { status: 200, body: [userRef()] } } as const;

/** The four `TestCaseInput` requires, plus the step a STRUCTURED case needs to be acceptable. */
const PROPOSABLE = {
  title: 'Sign out clears the session',
  stepFormat: 'STRUCTURED',
  priority: 'HIGH',
  automationStatus: 'MANUAL',
  steps: [{ action: 'Sign out', expectedResult: 'The session is gone.' }],
};

const BLOCK_LINK = {
  id: 'bl-1',
  sourceType: 'TEST_CASE',
  sourceId: 'c-1',
  sourceKey: 'TC-42',
  sourceTitle: 'Login works',
  relationship: 'VERIFIES',
};

const REPORT_JOB = {
  jobId: 'rj-1',
  type: 'run_summary',
  status: 'PENDING',
  format: 'HTML',
  requestedAt: '2026-08-16T09:30:00Z',
  // Required and nullable since 005 S1: null until the worker has written the artifact, which is
  // exactly the state a freshly-queued job is in. Absent is not the same claim as null — it is what
  // makes a size-aware client print `undefined`.
  sizeBytes: null,
  downloadUrl: null,
  modelUrl: null,
  error: null,
};

/**
 * How to reach each arm, keyed `tool.action`.
 *
 * The bodies are the `@tcms/client` factories rather than literals, so §203's comparison is the one
 * deciding whether a fixture is shaped like the resource and this table only has to be *reachable*.
 */
const INVOCATIONS: Record<string, Invocation> = {
  'tcms_write_client.record': {
    tool: 'tcms_write_client',
    input: { action: 'record', name: 'Acme Retail' },
    path: '/clients',
    routes: { 'POST /clients': { status: 201, body: clientFixture() } },
  },
  'tcms_write_client.edit': {
    tool: 'tcms_write_client',
    input: { action: 'edit', client: 'cl-1', status: 'ARCHIVED' },
    path: '/clients/cl-1',
    routes: {
      // The read the arm makes first: `PUT` is a full replacement, so the arm merges over the
      // current record rather than blanking what the caller did not name.
      'GET /clients/cl-1': { status: 200, body: clientDetail() },
      'PUT /clients/cl-1': { status: 200, body: clientFixture({ status: 'ARCHIVED' }) },
    },
  },
  'tcms_write_client.contact-add': {
    tool: 'tcms_write_client',
    input: { action: 'contact-add', client: 'cl-1', name: 'Hanna Lund' },
    path: '/clients/cl-1/contacts',
    routes: { 'POST /clients/cl-1/contacts': { status: 201, body: clientContact() } },
  },
  'tcms_write_client.contact-edit': {
    tool: 'tcms_write_client',
    input: { action: 'contact-edit', client: 'cl-1', contact: 'cc-1', name: 'Hanna Lund' },
    path: '/clients/cl-1/contacts/cc-1',
    routes: { 'PUT /clients/cl-1/contacts/cc-1': { status: 200, body: clientContact() } },
  },
  'tcms_write_client.contact-remove': {
    tool: 'tcms_write_client',
    input: { action: 'contact-remove', client: 'cl-1', contact: 'cc-1' },
    path: '/clients/cl-1/contacts/cc-1',
    routes: { 'DELETE /clients/cl-1/contacts/cc-1': { status: 204, body: null } },
  },
  /* Billing (019 T1). Seven arms on one tool, plus the rate card's two. */
  'tcms_write_billing.set-budget': {
    tool: 'tcms_write_billing',
    input: { action: 'set-budget', project: 'p-1', basis: 'HOURS', amount: 600 },
    path: '/projects/p-1/budget',
    routes: { 'PUT /projects/p-1/budget': { status: 200, body: budget() } },
  },
  'tcms_write_billing.clear-budget': {
    tool: 'tcms_write_billing',
    input: { action: 'clear-budget', project: 'p-1' },
    path: '/projects/p-1/budget',
    routes: { 'DELETE /projects/p-1/budget': { status: 204, body: null } },
  },
  'tcms_write_billing.record-expense': {
    tool: 'tcms_write_billing',
    input: {
      action: 'record-expense', project: 'p-1', on: '2026-09-02',
      what: 'Device lab hire', amount: 240, currency: 'EUR',
    },
    path: '/expenses',
    routes: { 'POST /expenses': { status: 201, body: expense() } },
  },
  'tcms_write_billing.remove-expense': {
    tool: 'tcms_write_billing',
    input: { action: 'remove-expense', expense: 'ex-1' },
    path: '/expenses/ex-1',
    routes: { 'DELETE /expenses/ex-1': { status: 204, body: null } },
  },
  'tcms_write_billing.draft-invoice': {
    tool: 'tcms_write_billing',
    input: { action: 'draft-invoice', client: 'cl-1', from: '2026-09-01', to: '2026-09-30' },
    path: '/invoices',
    routes: { 'POST /invoices': { status: 201, body: invoiceDetail() } },
  },
  'tcms_write_billing.discard-invoice': {
    tool: 'tcms_write_billing',
    input: { action: 'discard-invoice', invoice: 'inv-1' },
    path: '/invoices/inv-1',
    routes: { 'DELETE /invoices/inv-1': { status: 204, body: null } },
  },
  'tcms_write_billing.issue-invoice': {
    tool: 'tcms_write_billing',
    input: { action: 'issue-invoice', invoice: 'inv-1' },
    path: '/invoices/inv-1/issue',
    routes: { 'POST /invoices/inv-1/issue': { status: 200, body: invoiceDetail() } },
  },
  'tcms_write_rate_card.set': {
    tool: 'tcms_write_rate_card',
    // The list read is what the arm uses to choose between POST and PUT, and `card: null` on the
    // slot is what makes this the POST — a client's first rate card, which is every client's.
    input: {
      action: 'set', client: 'cl-1', currency: 'EUR',
      entries: [{ scope: 'ALL_ROLES', hourlyRate: 90, effectiveFrom: '2026-01-01' }],
    },
    path: '/rate-cards',
    routes: {
      'GET /rate-cards': { status: 200, body: [rateCardSlot()] },
      'POST /rate-cards': { status: 201, body: rateCard() },
    },
  },
  'tcms_write_rate_card.remove': {
    tool: 'tcms_write_rate_card',
    input: { action: 'remove', card: 'rc-1' },
    path: '/rate-cards/rc-1',
    routes: { 'DELETE /rate-cards/rc-1': { status: 204, body: null } },
  },
  'tcms_write_allocation.allocate': {
    tool: 'tcms_write_allocation',
    input: { action: 'allocate', person: 'Ana Engineer', percent: 50, startsOn: '2026-09-01', endsOn: '2026-11-30' },
    path: '/allocations',
    routes: {
      ...MEMBERS,
      'POST /allocations': { status: 201, body: allocation() },
    },
  },
  'tcms_write_allocation.change': {
    tool: 'tcms_write_allocation',
    input: { action: 'change', allocation: 'al-1', percent: 30 },
    path: '/allocations/al-1',
    routes: { 'PATCH /allocations/al-1': { status: 200, body: allocation({ percent: 30 }) } },
  },
  'tcms_write_allocation.remove': {
    tool: 'tcms_write_allocation',
    input: { action: 'remove', allocation: 'al-1' },
    path: '/allocations/al-1',
    routes: { 'DELETE /allocations/al-1': { status: 204 } },
  },
  'tcms_write_absence.record': {
    tool: 'tcms_write_absence',
    // No `person`, so it records the CALLER's own — the arm that needs no staffing key at all
    // (017 D-h), and the one a session can always reach.
    input: { action: 'record', startsOn: '2026-09-24', endsOn: '2026-09-24' },
    path: '/absences',
    routes: { 'POST /absences': { status: 201, body: absence({ startsOn: '2026-09-24', endsOn: '2026-09-24' }) } },
  },
  'tcms_write_absence.remove': {
    tool: 'tcms_write_absence',
    input: { action: 'remove', absence: 'ab-1' },
    path: '/absences/ab-1',
    routes: { 'DELETE /absences/ab-1': { status: 204 } },
  },
  'tcms_write_case.propose': {
    tool: 'tcms_write_case',
    input: { action: 'propose', testCase: PROPOSABLE },
    path: '/ai-proposals',
    routes: { 'POST /ai-proposals': { status: 201, body: proposal() } },
  },
  'tcms_write_case.edit': {
    tool: 'tcms_write_case',
    input: { action: 'edit', case: 'TC-42', title: 'Login again' },
    path: '/cases/c-1',
    routes: {
      ...CASES,
      'GET /cases/c-1': { status: 200, body: CASE },
      'PATCH /cases/c-1': { status: 200, body: CASE },
    },
  },
  'tcms_write_case.submit': {
    tool: 'tcms_write_case',
    input: { action: 'submit', case: 'TC-42' },
    path: '/cases/bulk',
    routes: {
      ...CASES,
      'POST /cases/bulk': { status: 200, body: bulkResult({ total: 1, changed: ['TC-42'] }) },
    },
  },
  'tcms_write_suite.create': {
    tool: 'tcms_write_suite',
    input: { action: 'create', name: 'Smoke' },
    path: '/suites',
    routes: { 'POST /suites': { status: 201, body: SUITE } },
  },
  'tcms_write_suite.edit': {
    tool: 'tcms_write_suite',
    input: { action: 'edit', suite: 'TS-1', name: 'Smoke again' },
    path: '/suites/s-1',
    routes: {
      ...SUITES,
      'GET /suites/s-1': { status: 200, body: SUITE },
      'PATCH /suites/s-1': { status: 200, body: SUITE },
    },
  },
  'tcms_write_suite.status': {
    tool: 'tcms_write_suite',
    input: { action: 'status', suite: 'TS-1', status: 'IN_REVIEW' },
    path: '/suites/s-1/status',
    routes: { ...SUITES, 'PUT /suites/s-1/status': { status: 200, body: SUITE } },
  },
  'tcms_write_case.delete': {
    tool: 'tcms_write_case',
    input: { action: 'delete', case: 'TC-42' },
    path: '/cases/c-1',
    routes: { ...CASES, 'DELETE /cases/c-1': { status: 204 } },
  },
  'tcms_write_doc_page.create': {
    tool: 'tcms_write_doc_page',
    input: { action: 'create', title: 'Payments spec' },
    path: '/doc-pages',
    routes: { 'POST /doc-pages': { status: 201, body: PAGE } },
  },
  'tcms_write_doc_page.edit': {
    tool: 'tcms_write_doc_page',
    input: { action: 'edit', page: 'DOC-1', title: 'Renamed' },
    path: '/doc-pages/dp-1',
    routes: {
      ...PAGES,
      'GET /doc-pages/dp-1': { status: 200, body: PAGE },
      'PATCH /doc-pages/dp-1': { status: 200, body: PAGE },
    },
  },
  'tcms_write_doc_page.link': {
    tool: 'tcms_write_doc_page',
    input: { action: 'link', page: 'DOC-1', block: 'b-1', case: 'TC-42' },
    path: '/blocks/b-1/links',
    routes: {
      ...PAGES,
      ...CASES,
      'POST /blocks/b-1/links': { status: 201, body: BLOCK_LINK },
    },
  },
  'tcms_write_suite.delete': {
    tool: 'tcms_write_suite',
    input: { action: 'delete', suite: 'TS-1' },
    path: '/suites/s-1',
    routes: { ...SUITES, 'DELETE /suites/s-1': { status: 204 } },
  },
  'tcms_write_doc_page.unlink': {
    tool: 'tcms_write_doc_page',
    input: { action: 'unlink', page: 'DOC-1', link: 'l-1' },
    path: '/links/l-1',
    routes: { ...PAGES, 'DELETE /links/l-1': { status: 204 } },
  },
  'tcms_write_doc_page.adopt': {
    tool: 'tcms_write_doc_page',
    input: { action: 'adopt', page: 'DOC-1', plan: 'TP-1' },
    path: '/plans/tp-1/document',
    routes: {
      ...PAGES,
      ...PLANS,
      'PUT /plans/tp-1/document': { status: 200, body: PAGE },
    },
  },
  'tcms_write_doc_page.publish': {
    tool: 'tcms_write_doc_page',
    input: { action: 'publish', page: 'DOC-1', status: 'PUBLISHED' },
    path: '/doc-pages/dp-1/status',
    routes: { ...PAGES, 'PUT /doc-pages/dp-1/status': { status: 200, body: PAGE } },
  },
  'tcms_write_plan.create': {
    tool: 'tcms_write_plan',
    input: { action: 'create', name: 'Release 4.2' },
    path: '/plans',
    routes: { 'POST /plans': { status: 201, body: PLAN } },
  },
  'tcms_write_plan.edit': {
    tool: 'tcms_write_plan',
    input: { action: 'edit', plan: 'TP-1', name: 'Release 4.3' },
    path: '/plans/tp-1',
    routes: {
      ...PLANS,
      'GET /plans/tp-1': { status: 200, body: PLAN },
      'PATCH /plans/tp-1': { status: 200, body: PLAN },
    },
  },
  'tcms_write_plan.status': {
    tool: 'tcms_write_plan',
    input: { action: 'status', plan: 'TP-1', status: 'IN_REVIEW' },
    path: '/plans/tp-1/status',
    routes: { ...PLANS, 'PUT /plans/tp-1/status': { status: 200, body: PLAN } },
  },
  'tcms_write_plan.delete': {
    tool: 'tcms_write_plan',
    input: { action: 'delete', plan: 'TP-1' },
    path: '/plans/tp-1',
    routes: { ...PLANS, 'DELETE /plans/tp-1': { status: 204 } },
  },
  'tcms_write_run.create': {
    tool: 'tcms_write_run',
    input: { action: 'create', name: 'Nightly' },
    path: '/runs',
    routes: { 'POST /runs': { status: 201, body: RUN } },
  },
  'tcms_write_run.clone': {
    tool: 'tcms_write_run',
    input: { action: 'clone', run: 'TR-1', name: 'Nightly, again' },
    path: '/runs/r-1/clone',
    routes: { ...RUNS, 'POST /runs/r-1/clone': { status: 201, body: RUN } },
  },
  'tcms_write_run.status': {
    tool: 'tcms_write_run',
    input: { action: 'status', run: 'TR-1', status: 'COMPLETED' },
    path: '/runs/r-1/status',
    routes: { ...RUNS, 'PUT /runs/r-1/status': { status: 200, body: RUN } },
  },
  'tcms_record_result.record': {
    tool: 'tcms_record_result',
    input: { run: 'TR-1', case: 'TC-42', status: 'Passed' },
    path: '/executions/e-1',
    routes: {
      ...RUNS,
      'GET /runs/r-1': {
        status: 200,
        body: runDetail({ ...RUN, executions: [executionSummary({ id: 'e-1' })] }),
      },
      '/statuses': { status: 200, body: [caseStatus()] },
      'GET /executions/e-1': { status: 200, body: execution({ id: 'e-1' }) },
      'PATCH /executions/e-1': { status: 200, body: execution({ id: 'e-1' }) },
    },
  },
  'tcms_review_comment.comment': {
    tool: 'tcms_review_comment',
    input: { subject: 'TC-42', body: 'Looks right.' },
    path: '/reviews/rv-1/comments',
    routes: { ...CASES, ...REVIEWS, 'POST /reviews/rv-1/comments': { status: 201, body: REVIEW } },
  },
  'tcms_review_verdict.verdict': {
    tool: 'tcms_review_verdict',
    input: { action: 'verdict', subject: 'TC-42', verdict: 'LGTM' },
    path: '/participations',
    routes: { ...CASES, ...REVIEWS, 'POST /participations': { status: 201, body: REVIEW } },
  },
  'tcms_review_verdict.resolve': {
    tool: 'tcms_review_verdict',
    input: { action: 'resolve', subject: 'TC-42', commentId: 'cm-1' },
    path: '/resolution',
    routes: { ...CASES, ...REVIEWS, 'PUT /resolution': { status: 200, body: REVIEW } },
  },
  'tcms_write_issue.create': {
    tool: 'tcms_write_issue',
    input: { action: 'create', typeName: 'Story', title: 'Repair the flaky sign-in case' },
    path: '/issues',
    routes: { ...WORKFLOW, 'POST /issues': { status: 201, body: ISSUE } },
  },
  'tcms_write_issue.update': {
    tool: 'tcms_write_issue',
    input: { action: 'update', issue: 'ACME-124', title: 'Sign-out clears the session, everywhere' },
    path: '/issues/ACME-124',
    routes: { 'PATCH /issues/ACME-124': { status: 200, body: ISSUE } },
  },
  'tcms_write_issue.move': {
    tool: 'tcms_write_issue',
    input: { action: 'move', issue: 'ACME-124', statusName: 'In Progress' },
    path: '/issues/ACME-124/transitions',
    routes: { ...WORKFLOW, 'POST /issues/ACME-124/transitions': { status: 200, body: ISSUE } },
  },
  'tcms_write_issue.comment': {
    tool: 'tcms_write_issue',
    input: { action: 'comment', issue: 'ACME-124', body: 'Reproduced on staging.' },
    path: '/issues/ACME-124/comments',
    routes: { 'POST /issues/ACME-124/comments': { status: 201, body: issueComment() } },
  },
  'tcms_write_issue.move-card': {
    tool: 'tcms_write_issue',
    input: { action: 'move-card', issue: 'ACME-124', column: 'In flight' },
    path: '/cards/ACME-124/move',
    routes: { ...BOARDS, 'POST /cards/ACME-124/move': { status: 200, body: cardMoveResult() } },
  },
  'tcms_write_issue.label': {
    tool: 'tcms_write_issue',
    input: { action: 'label', issue: 'ACME-124', labels: ['Flaky'] },
    path: '/issues/ACME-124/labels',
    routes: { ...LABELS, 'PUT /issues/ACME-124/labels': { status: 200, body: [label()] } },
  },
  'tcms_write_issue.cases': {
    tool: 'tcms_write_issue',
    input: { action: 'cases', issue: 'ACME-124', cases: ['TC-42'] },
    path: '/issues/ACME-124/test-cases',
    routes: {
      ...CASES,
      'PUT /issues/ACME-124/test-cases': { status: 200, body: [issueTestCase()] },
    },
  },
  'tcms_write_issue.release': {
    tool: 'tcms_write_issue',
    input: { action: 'release', issue: 'ACME-124', release: '2.4' },
    path: '/issues/ACME-124',
    routes: { ...RELEASES, 'PATCH /issues/ACME-124': { status: 200, body: ISSUE } },
  },
  'tcms_write_issue.attach-pr': {
    tool: 'tcms_write_issue',
    input: {
      action: 'attach-pr',
      issue: 'ACME-124',
      prUrl: 'https://github.com/northwind/acme-storefront/pull/482',
    },
    path: '/issues/ACME-124',
    routes: { 'PATCH /issues/ACME-124': { status: 200, body: ISSUE } },
  },
  'tcms_write_release.create': {
    tool: 'tcms_write_release',
    input: { action: 'create', version: '2.6' },
    path: '/releases',
    routes: { 'POST /projects/p-1/releases': { status: 201, body: RELEASE } },
  },
  'tcms_write_release.update': {
    tool: 'tcms_write_release',
    input: { action: 'update', release: '2.4', targetDate: '2026-11-21' },
    path: '/releases/rl-1',
    routes: { ...RELEASES, 'PATCH /releases/rl-1': { status: 200, body: RELEASE } },
  },
  'tcms_write_release.publish': {
    tool: 'tcms_write_release',
    input: { action: 'publish', release: '2.4' },
    path: '/releases/rl-1/publish',
    routes: {
      ...RELEASES,
      'POST /releases/rl-1/publish': {
        status: 200,
        body: releaseFixture({ status: 'RELEASED', releasedAt: '2026-09-01T10:00:00Z' }),
      },
    },
  },
  'tcms_write_release.delete': {
    tool: 'tcms_write_release',
    input: { action: 'delete', release: '2.4' },
    path: '/releases/rl-1',
    routes: { ...RELEASES, 'DELETE /releases/rl-1': { status: 204 } },
  },
  'tcms_write_backlog.rank': {
    tool: 'tcms_write_backlog',
    input: { action: 'rank', issue: 'ACME-124', after: 'ACME-99' },
    path: '/issues/ACME-124/rank',
    routes: { 'PUT /issues/ACME-124/rank': { status: 200, body: rankResult() } },
  },
  'tcms_write_backlog.label-create': {
    tool: 'tcms_write_backlog',
    input: { action: 'label-create', name: 'Regression', color: 'label-5' },
    path: '/labels',
    routes: { 'POST /labels': { status: 201, body: label({ name: 'Regression', color: 'label-5' }) } },
  },
  'tcms_write_backlog.label-update': {
    tool: 'tcms_write_backlog',
    input: { action: 'label-update', label: 'Flaky', name: 'Flaky again' },
    path: '/labels/lb-1',
    routes: { ...LABELS, 'PATCH /labels/lb-1': { status: 200, body: label({ name: 'Flaky again' }) } },
  },
  'tcms_write_backlog.label-delete': {
    tool: 'tcms_write_backlog',
    input: { action: 'label-delete', label: 'Flaky' },
    path: '/labels/lb-1',
    routes: { ...LABELS, 'DELETE /labels/lb-1': { status: 204 } },
  },
  'tcms_report.generate': {
    tool: 'tcms_report',
    input: { action: 'generate', type: 'run_summary', format: 'HTML' },
    path: '/reports/generate',
    routes: { 'POST /reports/generate': { status: 202, body: REPORT_JOB } },
  },
  'tcms_write_artifact.register': {
    tool: 'tcms_write_artifact',
    input: { action: 'register', title: 'Test strategy', category: 'Strategy', url: 'https://x.test/s' },
    path: '/artifacts',
    routes: { 'POST /artifacts': { status: 201, body: ARTIFACT } },
  },
  'tcms_write_artifact.edit': {
    tool: 'tcms_write_artifact',
    input: { action: 'edit', artifact: ARTIFACT.id, title: 'Test strategy v2' },
    path: `/artifacts/${ARTIFACT.id}`,
    routes: {
      ...ARTIFACTS,
      [`PATCH /artifacts/${ARTIFACT.id}`]: { status: 200, body: ARTIFACT },
    },
  },
  'tcms_write_artifact.status': {
    tool: 'tcms_write_artifact',
    input: { action: 'status', artifact: ARTIFACT.id, status: 'ARCHIVED' },
    path: `/artifacts/${ARTIFACT.id}/status`,
    routes: { [`PUT /artifacts/${ARTIFACT.id}/status`]: { status: 200, body: ARTIFACT } },
  },
  'tcms_write_artifact.delete': {
    tool: 'tcms_write_artifact',
    input: { action: 'delete', artifact: ARTIFACT.id },
    path: `/artifacts/${ARTIFACT.id}`,
    routes: { [`DELETE /artifacts/${ARTIFACT.id}`]: { status: 204 } },
  },
  'tcms_write_timesheet.log': {
    tool: 'tcms_write_timesheet',
    input: { action: 'log', issue: 'ACME-124', hours: 2, spentOn: '2026-09-01' },
    path: '/timesheets/entries',
    routes: { 'POST /timesheets/entries': { status: 201, body: timeEntry() } },
  },
  'tcms_write_timesheet.submit': {
    tool: 'tcms_write_timesheet',
    input: { action: 'submit', period: PERIOD_ID },
    path: `/timesheets/periods/${PERIOD_ID}/submit`,
    routes: {
      [`POST /timesheets/periods/${PERIOD_ID}/submit`]: {
        status: 200,
        body: timesheetPeriod({ state: 'SUBMITTED', editable: false, submittedAt: '2026-09-16T08:00:00Z' }),
      },
    },
  },
  'tcms_approve_timesheet.approve': {
    tool: 'tcms_approve_timesheet',
    input: { action: 'approve', period: PERIOD_ID },
    path: `/timesheets/periods/${PERIOD_ID}/decision`,
    routes: { [`POST /timesheets/periods/${PERIOD_ID}/decision`]: { status: 200, body: DECIDED } },
  },
  'tcms_approve_timesheet.reject': {
    tool: 'tcms_approve_timesheet',
    input: { action: 'reject', period: PERIOD_ID, note: 'the 8h on 24 Aug is against an epic that was on hold' },
    path: `/timesheets/periods/${PERIOD_ID}/decision`,
    routes: { [`POST /timesheets/periods/${PERIOD_ID}/decision`]: { status: 200, body: DECIDED } },
  },
  'tcms_approve_timesheet.withdraw-rejection': {
    tool: 'tcms_approve_timesheet',
    input: { action: 'withdraw-rejection', period: PERIOD_ID },
    path: `/timesheets/periods/${PERIOD_ID}/decision`,
    routes: { [`POST /timesheets/periods/${PERIOD_ID}/decision`]: { status: 200, body: DECIDED } },
  },
};
