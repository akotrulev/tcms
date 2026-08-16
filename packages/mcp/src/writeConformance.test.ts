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

import { contractPathOfRequest, declaredHeadersOf } from '../../client/src/test/contractBodies.js';
import {
  artifact,
  bulkResult,
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
  suite,
  workItem,
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
  'tcms_write_artifact.edit':
    'PATCH …/artifacts/{artifactId} declares no If-Match, so two callers correcting one register '
    + 'row race and the later write wins silently. It cannot simply be added: the endpoint would '
    + 'need a version to compare, and `Artifact` does not carry `lockVersion` on the wire at all — '
    + 'the column exists and is deliberately unserved (§229). So closing this is a contract change '
    + 'on both the response and the request, not a header here. What it costs is bounded by what '
    + 'the edit can reach: three metadata fields, no payload and no scope, on a row whose real '
    + 'corrections go through supersede.',
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
    expect(arms.length).toBeGreaterThanOrEqual(25);
    expect(WRITE_TOOLS.every((tool) => (tool.arms ?? []).length > 0)).toBe(true);
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
  'tcms_write_work_item.create': {
    tool: 'tcms_write_work_item',
    input: { action: 'create', title: 'Repair the flaky sign-in case' },
    path: '/work-items',
    routes: { 'POST /work-items': { status: 201, body: workItem() } },
  },
  'tcms_write_work_item.move': {
    tool: 'tcms_write_work_item',
    input: { action: 'move', workItemId: 'wi-1', status: 'IN_PROGRESS' },
    path: '/work-items/wi-1',
    routes: { 'PATCH /work-items/wi-1': { status: 200, body: workItem({ status: 'IN_PROGRESS' }) } },
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
};
