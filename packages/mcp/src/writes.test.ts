/**
 * The write tools — what each arm sends, and the shape the registry itself has to have.
 *
 * ~~`header conformance` is the surface spec's §8.3 — *assert every create arm sends a key and
 * every edit arm sends `If-Match`, over the tool registry rather than per tool*. It is the same
 * check the CLI runs over its command registry, deliberately: the two surfaces share a command core
 * precisely so that a rule proved on one holds on the other, and two conformance tests that
 * disagreed about what a create is would be the thing that lets them drift.~~
 *
 * → **§205: that paragraph was true of its intent and false of its content.** What sat here under
 * that name read the registry's *declarations* — that every arm's `kind` is one the table knows,
 * that every permission is well-formed and inside its tool's gate — and asserted **nothing about a
 * header**, while the CLI's drove each command and read what arrived. So the two did disagree, in
 * exactly the way the sentence warned about, for sixty-five sections. §8.3 now lives in
 * `writeConformance.test.ts`, one file in each package, and what remains below is the declaration
 * check under a name that says so.
 */
import { describe, expect, it } from 'vitest';

import {
  board,
  boardSummary,
  cardMoveResult,
  caseDetail,
  bulkResult,
  label,
  rankResult,
  wipBreach,
  caseStatus,
  casePage,
  caseSummary,
  condition,
  docBlock,
  docPage,
  docPageSummary,
  execution,
  executionSummary,
  issue,
  issueTestCase,
  plan,
  projectWorkflow,
  release as releaseFixture,
  releaseRef,
  releaseSummary,
  proposal,
  review,
  run as runFixture,
  runDetail,
  step,
  suite,
} from '../../client/src/test/fixtures.js';
import { SHAPES, fieldsFor } from '@tcms/client';

import { roleMatrix, type Role } from '../../client/src/test/roleMatrix.js';

import { armFor } from './server.js';
import { TOOLS } from './tools.js';
import { WRITE_TOOLS } from './writes.js';
import { deletes, isListed, type ToolDefinition } from './tool.js';
import { AGENT_KEYS, agentRoutes, api, sessionWith, withApi, type Api } from './test/harness.js';

function tool(name: string): ToolDefinition {
  const found = WRITE_TOOLS.find((candidate) => candidate.name === name);
  if (!found) throw new Error(`No write tool ${name}`);
  return found;
}

async function run(
  stub: Api,
  name: string,
  input: Record<string, unknown>,
): Promise<unknown> {
  const session = await sessionWith(stub);
  return withApi(stub, () => tool(name).run({ ...session, callId: 'mcp-7' }, input));
}

/*
  `automationStatus: 'NONE'` and `stepFormat: 'STEPS'` — neither is a value this API has. The
  fixture said both, and nothing noticed, because a stub answers 200 to whatever arrives. §143
  sent a real `NONE` at a real server and got a 400 (dressed as a 401, which was the other half
  of that slice). A fixture is a claim about the server and this one was false in three places.
*/
const CASE_ROW = caseSummary({
  title: 'Login',
  suiteName: 'Auth',
  updatedAt: '2026-08-01T10:00:00Z',
});
/** A WHOLE detail — the edit arm reads this and sends it back, so its shape is the assertion. */
const CASE_DETAIL = caseDetail({
  ...CASE_ROW,
  description: 'The happy path.',
  severity: null,
  automationTags: ['@smoke'],
  tags: ['auth'],
  steps: [step({ id: 'st-1', expectedResult: 'In.' })],
  // A stored decline, so "the round-trip does not assert declines" is a claim about a case that
  // has one rather than about an empty array (§189).
  declinedPreconditions: [
    condition({
      id: 'sc-2', conditionId: 'cond-2', title: 'Seed the ledger',
      inline: false, inherited: true, inheritedFrom: 'Billing',
    }),
  ],
  currentVersionNo: 4,
  lockVersion: 4,
});
/** `caseInputFrom(CASE_DETAIL)` — the unchanged round-trip, minus the two refused fields. */
const CASE_ECHO = {
  title: 'Login',
  suiteId: 's-1',
  description: 'The happy path.',
  objective: null,
  expectedOutcome: null,
  stepFormat: 'STRUCTURED',
  priority: 'HIGH',
  automationStatus: 'MANUAL',
  automationKey: null,
  notAutomatableReason: null,
  estimatedDurationSec: null,
  classificationValueIds: [],
  tags: ['auth'],
  steps: [
    {
      id: 'st-1', keyword: null, action: 'Sign in',
      testData: null, expectedResult: 'In.', docString: null, dataTable: null,
    },
  ],
};
/** The four the accept demands unconditionally, plus the step a STRUCTURED case needs. */
const PROPOSABLE = {
  title: 'Sign out clears the session',
  stepFormat: 'STRUCTURED',
  priority: 'HIGH',
  automationStatus: 'MANUAL',
  steps: [{ action: 'Sign out', expectedResult: 'The session is gone.' }],
};
/**
 * ~~`conditions: []`~~ → **the two arrays §186 split it into**, corrected in §189. The single
 * undifferentiated array left the wire three sections earlier and this fixture kept describing a
 * suite the server has not served since — the shape of stale fixture `writes.test.ts` opens by
 * warning about, in a file whose whole subject is round-tripping a resource faithfully.
 */
const SUITE_ROW = suite({ key: 'TS-7', name: 'Auth', caseCount: 2, lockVersion: 2 });
const RUN = runFixture({ id: 'r-1', key: 'TR-1', name: 'Regression', status: 'IN_PROGRESS' });

const PASSED = caseStatus({ id: 'st-1', color: '#0a0' });

const PLAN_ROW = plan({
  milestoneName: 'GA', milestoneDate: '2026-09-01', caseCount: 3,
  description: 'The regression pass before GA.',
  // Reachable from no tool argument at all, which is what made them the sharpest half of the
  // plan defect: EVERY edit this server made emptied both, and no caller could have avoided it.
  scopeText: 'Checkout, payments, refunds.',
  outOfScopeText: 'Anything behind the beta flag.',
  currentVersionNo: 2, lockVersion: 7,
});
/** `planInputFrom(PLAN_ROW)` — every scalar, and none of the lists. */
const PLAN_ECHO = {
  name: 'Release 4.2',
  description: 'The regression pass before GA.',
  milestoneName: 'GA',
  milestoneDate: '2026-09-01',
  scopeText: 'Checkout, payments, refunds.',
  outOfScopeText: 'Anything behind the beta flag.',
};
/** The LIST row, which carries no blocks and no lock version — a different shape from the detail. */
const PAGE_ROW = docPageSummary({ id: 'd-1', key: 'DOC-1', title: 'Spec', slug: 'spec' });

const PAGE = docPage({
  id: 'd-1', key: 'DOC-1', title: 'Spec', slug: 'spec', lockVersion: 5,
  blocks: [docBlock({ id: 'b-1', text: 'The user signs in.' })],
});

describe('tcms_write_case', () => {
  it('files a proposal rather than creating a case, and carries an idempotency key', async () => {
    const stub = api(agentRoutes({
      'POST /ai-proposals': {
        status: 201,
        body: proposal(),
      },
    }));

    const answer = await run(stub, 'tcms_write_case', {
      action: 'propose',
      testCase: PROPOSABLE,
    }) as Record<string, unknown>;

    const sent = stub.write('/ai-proposals');
    expect(sent.headers.get('Idempotency-Key')).toBe('mcp-7');
    // The bare case is wrapped for the caller: a model holds what it generated, and the API takes
    // an AiProposalInput around it.
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ testCase: PROPOSABLE });
    expect(answer['proposalId']).toBe('ap-1');
    expect(String(answer['note'])).toContain('cannot accept it');
  });

  it('does not default the verdict, because silence and UNVERIFIED are different claims', async () => {
    const stub = api(agentRoutes({
      'POST /ai-proposals': { status: 201, body: proposal() },
    }));

    await run(stub, 'tcms_write_case', { action: 'propose', testCase: PROPOSABLE });
    expect(JSON.parse(stub.write('/ai-proposals').body ?? '{}')).not.toHaveProperty('verdict');
  });

  /*
    Filing it WORKS — 201, PENDING — and the refusal arrives weeks later on a reviewer who cannot
    edit the proposal to fix it and whose only remaining move is Reject. So the model is told
    while it is still holding the thing. §143 drove exactly that path end to end.
  */
  it('refuses a proposal the accept could never take, and files nothing', async () => {
    const stub = api(agentRoutes({
      'POST /ai-proposals': { status: 201, body: proposal() },
    }));

    await expect(run(stub, 'tcms_write_case', {
      action: 'propose',
      testCase: { title: 'Sign out clears the session' },
    })).rejects.toThrow(/stepFormat.*priority.*automationStatus/s);

    expect(stub.sent.some((request) => request.method === 'POST')).toBe(false);
  });

  it('edits with an If-Match read for itself when no lockVersion was given', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'GET /cases/c-1': { status: 200, body: CASE_DETAIL },
      'PATCH /cases/c-1': { status: 200, body: { ...CASE_DETAIL, lockVersion: 5 } },
    }));

    await run(stub, 'tcms_write_case', { action: 'edit', case: 'TC-42', title: 'Login again' });

    const sent = stub.write('/cases/c-1');
    expect(sent.method).toBe('PATCH');
    expect(sent.headers.get('If-Match')).toBe('4');
    /*
      ~~A delta: only what was named~~ → **the whole editable set with one field changed.** This
      `PATCH` is partial by verb only: three required fields absent is a 400 about fields the
      model never mentioned — one of which, `stepFormat`, this tool's schema gives it no way to
      mention — and every nullable scalar absent is *cleared*. §143 drove it; this assertion
      previously agreed with the client because the stub agrees with everything.
    */
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ ...CASE_ECHO, title: 'Login again' });
    // Served by the GET, REFUSED by the PATCH. A round-trip that echoed them would 400 every time.
    expect(JSON.parse(sent.body ?? '{}')).not.toHaveProperty('ownerId');
    expect(JSON.parse(sent.body ?? '{}')).not.toHaveProperty('automationTags');
    // `severity` is null on this case and `TestCaseInput.severity` is not nullable, so "unset"
    // has no spelling on the write and OMITTING it is what reproduces it.
    expect(JSON.parse(sent.body ?? '{}')).not.toHaveProperty('severity');
  });

  it('prefers a lockVersion the caller holds, and still reads the case for its body', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'GET /cases/c-1': { status: 200, body: CASE_DETAIL },
      'PATCH /cases/c-1': { status: 200, body: CASE_DETAIL },
    }));

    await run(stub, 'tcms_write_case', { action: 'edit', case: 'TC-42', title: 'x', lockVersion: 2 });

    expect(stub.write('/cases/c-1').headers.get('If-Match')).toBe('2');
    /*
      ~~It did not read the case at all~~ → **it must**, because the body is the whole editable
      set and only the resource has it. The version is still the caller's, not the one this read
      returned, and that is the safe direction: a stale `lockVersion` 409s and the base is never
      used, while a current one describes exactly the state this read saw.
    */
    expect(stub.sent.filter((request) => request.method === 'GET' && request.path.endsWith('/cases/c-1')))
      .toHaveLength(1);
  });

  it('keeps null and absent apart on the suite, because they are different requests', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'GET /cases/c-1': { status: 200, body: CASE_DETAIL },
      'PATCH /cases/c-1': { status: 200, body: CASE_DETAIL },
    }));

    await run(stub, 'tcms_write_case', { action: 'edit', case: 'TC-42', suite: null, lockVersion: 1 });
    // The explicit null survives the merge: the base files this case in `s-1`, and a merge that
    // dropped nulls would put it straight back and report success.
    expect(JSON.parse(stub.write('/cases/c-1').body ?? '{}')).toEqual({ ...CASE_ECHO, suiteId: null });
  });

  /*
    §189. `case_condition_optout` got its endpoint in §187 and neither machine surface could send
    to it, so the state was reachable from one screen and nowhere else.
  */
  it('declines the attachments named, as a complete set, and asserts nothing when none are named', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'GET /cases/c-1': { status: 200, body: CASE_DETAIL },
      'PATCH /cases/c-1': { status: 200, body: CASE_DETAIL },
    }));

    await run(stub, 'tcms_write_case', {
      action: 'edit', case: 'TC-42', declinedConditions: ['sc-2'], lockVersion: 4,
    });
    expect(JSON.parse(stub.write('/cases/c-1').body ?? '{}'))
      .toEqual({ ...CASE_ECHO, declinedConditionIds: ['sc-2'] });

    // `[]` is a request and JSON can type it, unlike the CLI's `--decline ''`: it re-imposes
    // every inherited condition this case had turned off.
    await run(stub, 'tcms_write_case', {
      action: 'edit', case: 'TC-42', declinedConditions: [], lockVersion: 4,
    });
    expect(JSON.parse(stub.write('/cases/c-1').body ?? '{}'))
      .toEqual({ ...CASE_ECHO, declinedConditionIds: [] });

    // And the projection stays out of it: a title-only edit says nothing about the decline this
    // case has, because a list absent leaves it alone and an echo would state as the caller's
    // intent a set they never mentioned. `core/rewrite.ts`.
    await run(stub, 'tcms_write_case', { action: 'edit', case: 'TC-42', title: 'x', lockVersion: 4 });
    expect(JSON.parse(stub.write('/cases/c-1').body ?? '{}'))
      .not.toHaveProperty('declinedConditionIds');
  });

  it('refuses an edit that would send nothing', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
    }));

    // A `PATCH` with an empty body is accepted by the contract and changes nothing while
    // answering 200 — so a call whose fields were all misspelled would report success.
    await expect(run(stub, 'tcms_write_case', { action: 'edit', case: 'TC-42' }))
      .rejects.toThrow(/Nothing to change/);
  });

  it('submits for review through the one bulk action whose gate does not depend on its payload', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'POST /cases/bulk': { status: 200, body: bulkResult({ total: 1, changed: ['TC-42'] }) },
    }));

    await run(stub, 'tcms_write_case', { action: 'submit', case: 'TC-42' });

    expect(JSON.parse(stub.write('/cases/bulk').body ?? '{}'))
      .toEqual({ action: 'SUBMIT_FOR_REVIEW', caseIds: ['c-1'], dryRun: false });
  });
});

describe('tcms_write_doc_page', () => {
  it('demands the caller\'s own lockVersion when replacing blocks', async () => {
    const stub = api(agentRoutes({ '/doc-pages': { status: 200, body: [PAGE_ROW] } }));

    /*
      The one place §139's rule bites on this surface: a `blocks` array is the whole page and a
      block omitted from it is deleted, so its content necessarily came from an earlier read. A
      fresh version would assert a state that body was never based on.
    */
    await expect(run(stub, 'tcms_write_doc_page', {
      action: 'edit', page: 'DOC-1', blocks: [{ blockType: 'PARAGRAPH', text: 'Rewritten.' }],
    })).rejects.toThrow(/lockVersion of the read that body came from/);
  });

  it('reads its own version for a title-only edit, which is a delta', async () => {
    const stub = api(agentRoutes({
      '/doc-pages': { status: 200, body: [PAGE_ROW] },
      'GET /doc-pages/d-1': { status: 200, body: PAGE },
      'PATCH /doc-pages/d-1': { status: 200, body: { ...PAGE, title: 'Renamed' } },
    }));

    await run(stub, 'tcms_write_doc_page', { action: 'edit', page: 'DOC-1', title: 'Renamed' });

    const sent = stub.write('/doc-pages/d-1');
    expect(sent.headers.get('If-Match')).toBe('5');
    // `blocks` must not appear: sending the key at all would empty somebody's specification.
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ title: 'Renamed' });
  });

  it('links a case to a block with the contract\'s default relationship stated', async () => {
    const stub = api(agentRoutes({
      '/doc-pages': { status: 200, body: [PAGE_ROW] },
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'POST /links': {
        status: 201,
        body: {
          id: 'l-1', sourceType: 'TEST_CASE', sourceId: 'c-1', sourceKey: 'TC-42',
          sourceTitle: 'Login', relationship: 'VERIFIES',
        },
      },
    }));

    await run(stub, 'tcms_write_doc_page', { action: 'link', page: 'DOC-1', block: 'b-1', case: 'TC-42' });

    expect(JSON.parse(stub.write('/links').body ?? '{}'))
      .toEqual({ sourceType: 'TEST_CASE', sourceId: 'c-1', relationship: 'VERIFIES' });
  });

  it('refuses a link that names both a case and a suite, or neither', async () => {
    const stub = api(agentRoutes({ '/doc-pages': { status: 200, body: [PAGE_ROW] } }));

    await expect(run(stub, 'tcms_write_doc_page', { action: 'link', page: 'DOC-1', block: 'b-1' }))
      .rejects.toThrow(/exactly one/);
  });
});

describe('tcms_write_suite', () => {
  it('creates with a key, and sends the membership as the complete set it is', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'POST /suites': { status: 201, body: SUITE_ROW },
    }));

    await run(stub, 'tcms_write_suite', { action: 'create', name: 'Auth', cases: ['TC-42'] });

    const sent = stub.write('/suites');
    expect(sent.headers.get('Idempotency-Key')).toBe('mcp-7');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ name: 'Auth', caseIds: ['c-1'] });
  });

  /*
    The case arm's defect, one field wide: `SuiteInput` requires `name`, so editing only the tag
    rules was a 400 about a field the model never named, and editing only the name emptied the
    description. Invisible to a stub, which answers 200 to whatever arrives.
  */
  it('sends the whole suite with the change applied, not the named fields alone', async () => {
    const stub = api(agentRoutes({
      '/suites': { status: 200, body: [SUITE_ROW] },
      'GET /suites/s-1': { status: 200, body: SUITE_ROW },
      'PATCH /suites/s-1': { status: 200, body: SUITE_ROW },
    }));

    await run(stub, 'tcms_write_suite', { action: 'edit', suite: 'TS-7', tagRules: ['nightly'] });

    const sent = stub.write('/suites/s-1');
    expect(sent.headers.get('If-Match')).toBe('2');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({
      name: 'Auth', description: null, caseIds: [], tagRules: ['nightly'],
    });
  });

  /*
    §189, the suite half. Bare library ids in, `CaseConditionInput` objects out — the wrapper is
    this surface's to build, because the object has exactly one field a caller may fill and
    asking a model for the shape buys a shape to get wrong.
  */
  it('wraps condition ids as the input the write takes, on both arms', async () => {
    const stub = api(agentRoutes({
      '/suites': { status: 200, body: [SUITE_ROW] },
      'GET /suites/s-1': { status: 200, body: SUITE_ROW },
      'PATCH /suites/s-1': { status: 200, body: SUITE_ROW },
      'POST /suites': { status: 201, body: SUITE_ROW },
    }));

    await run(stub, 'tcms_write_suite', {
      action: 'edit', suite: 'TS-7', preconditions: ['cond-1'], postconditions: ['cond-9'],
    });
    expect(JSON.parse(stub.write('/suites/s-1').body ?? '{}')).toEqual({
      name: 'Auth',
      description: null,
      caseIds: [],
      tagRules: [],
      preconditions: [{ conditionId: 'cond-1' }],
      postconditions: [{ conditionId: 'cond-9' }],
    });

    // On the create too: a suite's conditions reach the cases filed in it, so attaching them in
    // a second call is a window in which those cases run without a setup step.
    await run(stub, 'tcms_write_suite', { action: 'create', name: 'Auth', preconditions: ['cond-1'] });
    expect(JSON.parse(stub.write('/suites').body ?? '{}'))
      .toEqual({ name: 'Auth', preconditions: [{ conditionId: 'cond-1' }] });
  });

  it('sends a status transition with neither header, because its precondition is the state', async () => {
    const stub = api(agentRoutes({
      '/suites': { status: 200, body: [SUITE_ROW] },
      'PUT /status': { status: 200, body: { ...SUITE_ROW, lifecycleStatus: 'IN_REVIEW' } },
    }));

    await run(stub, 'tcms_write_suite', { action: 'status', suite: 'TS-7', status: 'IN_REVIEW' });

    const sent = stub.write('/status');
    expect(sent.headers.get('If-Match')).toBeNull();
    expect(sent.headers.get('Idempotency-Key')).toBeNull();
  });
});

describe('tcms_write_plan', () => {
  /*
    This arm already threaded `name` through from its own read and was right to. What it did not
    thread was every other scalar: `PlanEdit.validateUpdate` writes each one straight through and
    resolves only the LISTS against what is stored, so an edit naming the milestone emptied the
    description, and both prose fields were emptied by every edit ever made here.
  */
  it('sends every scalar back, and no list it was not given', async () => {
    const stub = api(agentRoutes({
      '/plans': { status: 200, body: [PLAN_ROW] },
      'GET /plans/tp-1': { status: 200, body: PLAN_ROW },
      'PATCH /plans/tp-1': { status: 200, body: PLAN_ROW },
    }));

    await run(stub, 'tcms_write_plan', { action: 'edit', plan: 'TP-1', milestoneName: 'GA2' });

    const sent = stub.write('/plans/tp-1');
    expect(sent.headers.get('If-Match')).toBe('7');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ ...PLAN_ECHO, milestoneName: 'GA2' });
  });
});

describe('tcms_record_result', () => {
  it('finds the execution from the run and the case, and names the status by name', async () => {
    const stub = api(agentRoutes({
      '/runs': { status: 200, body: [RUN] },
      'GET /runs/r-1': {
        status: 200,
        body: runDetail({ ...RUN, executions: [executionSummary({ id: 'e-1' })] }),
      },
      '/statuses': { status: 200, body: [PASSED] },
      'GET /executions/e-1': { status: 200, body: execution({ id: 'e-1', lockVersion: 3 }) },
      'PATCH /executions/e-1': { status: 200, body: execution({ id: 'e-1', lockVersion: 4, status: PASSED }) },
    }));

    await run(stub, 'tcms_record_result', { run: 'TR-1', case: 'TC-42', status: 'passed' });

    const sent = stub.write('/executions/e-1');
    // The version comes from the execution's own detail, not from the run's summary rows — a
    // list that mentions a resource is not the resource.
    expect(sent.headers.get('If-Match')).toBe('3');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ statusId: 'st-1' });
  });

  it('refuses a status this project does not have, and lists the ones it does', async () => {
    const stub = api(agentRoutes({
      '/runs': { status: 200, body: [RUN] },
      'GET /runs/r-1': { status: 200, body: runDetail({ ...RUN, executions: [executionSummary({ id: 'e-1' })] }) },
      '/statuses': { status: 200, body: [PASSED] },
    }));

    await expect(run(stub, 'tcms_record_result', { run: 'TR-1', case: 'TC-42', status: 'Green' }))
      .rejects.toThrow(/It offers: Passed/);
  });

  it('passes durationMs through, and tells null apart from absent', async () => {
    const routes = {
      '/runs': { status: 200, body: [RUN] },
      'GET /runs/r-1': {
        status: 200,
        body: runDetail({ ...RUN, executions: [executionSummary({ id: 'e-1' })] }),
      },
      '/statuses': { status: 200, body: [PASSED] },
      'GET /executions/e-1': { status: 200, body: execution({ id: 'e-1', lockVersion: 3 }) },
      'PATCH /executions/e-1': { status: 200, body: execution({ id: 'e-1', lockVersion: 4, status: PASSED }) },
    };

    const set = api(agentRoutes(routes));
    await run(set, 'tcms_record_result', {
      run: 'TR-1', case: 'TC-42', status: 'passed', durationMs: 4500,
    });
    expect(JSON.parse(set.write('/executions/e-1').body ?? '{}'))
      .toEqual({ statusId: 'st-1', durationMs: 4500 });

    // An explicit null is a CLEAR and must survive `present()`, which drops only undefined.
    const cleared = api(agentRoutes(routes));
    await run(cleared, 'tcms_record_result', {
      run: 'TR-1', case: 'TC-42', status: 'passed', durationMs: null,
    });
    expect(JSON.parse(cleared.write('/executions/e-1').body ?? '{}'))
      .toEqual({ statusId: 'st-1', durationMs: null });

    // Zero is a duration. A falsy check anywhere on this path turns "instant" into "not timed".
    const zero = api(agentRoutes(routes));
    await run(zero, 'tcms_record_result', {
      run: 'TR-1', case: 'TC-42', status: 'passed', durationMs: 0,
    });
    expect(JSON.parse(zero.write('/executions/e-1').body ?? '{}'))
      .toEqual({ statusId: 'st-1', durationMs: 0 });
  });

  it('does not declare evidence:attach, because it cannot attach evidence', () => {
    // The endpoint is multipart/form-data only and a tool call carries JSON. Declaring the
    // permission would hide the tool from a caller who can record results and cannot attach.
    expect(tool('tcms_record_result').gate.permissions).toEqual(['run:execute', 'result:submit']);
  });
});

describe('tcms_review_verdict', () => {
  it('acts on the OPEN review, not the newest one', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'GET /reviews': {
        status: 200,
        body: [
          review({ id: 'rv-2', state: 'APPROVED' }),
          review({ id: 'rv-1', state: 'IN_REVIEW' }),
        ],
      },
      'POST /participations': { status: 201, body: review({ id: 'rv-1' }) },
    }));

    // ~~`APPROVED`~~ → `LGTM`, §203: the enum has two members and that was not one of them. The
    // tool's own schema said APPROVED, so this test pinned the word that earned a 400.
    await run(stub, 'tcms_review_verdict', { action: 'verdict', subject: 'TC-42', verdict: 'LGTM' });

    // A review is history once it closes — the endpoint answers 409 on a closed one — so "the
    // latest row" and "the one that can still be written to" are different questions.
    expect(stub.write('/participations').path).toContain('/reviews/rv-1/');
  });

  it('says which subjects this credential may act on, rather than being hidden', () => {
    const verdict = tool('tcms_review_verdict');
    expect(verdict.gate.mode).toBe('any');
    expect(verdict.narrow?.(new Set(['case:review']))).toContain('case');
    expect(verdict.narrow?.(new Set(['case:review']))).not.toContain('plan');
  });

  it('explains that a closed review cannot be joined', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
      'GET /reviews': { status: 200, body: [review({ id: 'rv-2', state: 'APPROVED' })] },
    }));

    await expect(run(stub, 'tcms_review_comment', { subject: 'TC-42', body: 'hi' }))
      .rejects.toThrow(/review has ended/);
  });
});

describe('tcms_write_issue', () => {
  const WORKFLOW = { 'GET /workflow': { status: 200, body: projectWorkflow() } } as const;
  const BOARDS = {
    'GET /boards': { status: 200, body: [boardSummary()] },
    'GET /boards/bd-1': { status: 200, body: board() },
  } as const;
  const LABELS = { 'GET /labels': { status: 200, body: [label()] } } as const;

  it('resolves the type NAME to the id the contract takes', async () => {
    /*
      The whole design of this tool. `IssueInput.typeId` is a UUID of a per-project row, and an
      agent handed a bare UUID field invents one — so the surface takes `Story` and looks it up.
    */
    const stub = api(agentRoutes({ ...WORKFLOW, 'POST /issues': { status: 201, body: issue() } }));

    await run(stub, 'tcms_write_issue', {
      action: 'create', typeName: 'bug', title: 'Sign-out leaves the session live',
    });

    expect(JSON.parse(stub.write('/issues').body ?? '{}'))
      // Matched case-insensitively: a model that writes `bug` for `Bug` has named the right row.
      .toMatchObject({ typeId: 'it-2', title: 'Sign-out leaves the session live' });
  });

  it('refuses a type this project does not have, and lists the ones it does', async () => {
    const stub = api(agentRoutes(WORKFLOW));

    await expect(run(stub, 'tcms_write_issue', { action: 'create', typeName: 'Initiative', title: 'x' }))
      .rejects.toThrow(/It offers: Epic, Story, Bug, Task/);
  });

  it('refuses a status this project does not have, and never sends the transition', async () => {
    const stub = api(agentRoutes(WORKFLOW));

    await expect(run(stub, 'tcms_write_issue', { action: 'move', issue: 'ACME-124', statusName: 'Shipped' }))
      .rejects.toThrow(/It offers: To Do, In Progress, In Review, Done/);
    expect(stub.sent.some((request) => request.path.endsWith('/transitions'))).toBe(false);
  });

  it('leaves a legal-move ladder to the server, and sends a known status straight through', async () => {
    // The division of labour: this side checks the VOCABULARY, the server checks the LEGALITY.
    // `In Review` is not reachable from `To Do` in the fixture's transition list and is still sent
    // — the 409 that comes back names the moves that are allowed, which a ladder here could not.
    const stub = api(agentRoutes({
      ...WORKFLOW,
      'POST /issues/ACME-124/transitions': { status: 200, body: issue() },
    }));

    await run(stub, 'tcms_write_issue', { action: 'move', issue: 'ACME-124', statusName: 'In Review' });

    expect(JSON.parse(stub.write('/transitions').body ?? '{}')).toEqual({ statusId: 'is-3' });
  });

  it('sends only the fields an update named, because the PATCH is a true delta', async () => {
    const stub = api(agentRoutes({ 'PATCH /issues/ACME-124': { status: 200, body: issue() } }));

    await run(stub, 'tcms_write_issue', { action: 'update', issue: 'ACME-124', dueDate: null });

    // No read first, unlike the case, suite and plan arms: `IssueUpdate` says an absent key means
    // leave it, so echoing the whole resource back would be this surface inventing a whole-set
    // write the endpoint does not ask for. `null` is a real value here and clears the date.
    expect(JSON.parse(stub.write('/issues/ACME-124').body ?? '{}')).toEqual({ dueDate: null });
    expect(stub.sent.filter((request) => request.method === 'GET' && request.path.endsWith('/issues/ACME-124')))
      .toHaveLength(0);
  });

  it('carries the AI-eligible flag on a create, so the field is not invisible on this surface', async () => {
    const stub = api(agentRoutes({ ...WORKFLOW, 'POST /issues': { status: 201, body: issue() } }));

    await run(stub, 'tcms_write_issue', {
      action: 'create', typeName: 'Task', title: 'Repair the flaky sign-in case', agentEligible: true,
    });

    expect(JSON.parse(stub.write('/issues').body ?? '{}')).toMatchObject({ agentEligible: true });
  });

  it('attaches a PR as one field on the PATCH, and nothing else', async () => {
    /*
      D25: this product never opens a pull request — the developer's own git does, and the URL is
      how the work reaches the issue's history (`issue.pr_url_set`). So the arm sends exactly the
      field it was given; anything else here would be a write nobody asked for.
    */
    const stub = api(agentRoutes({ 'PATCH /issues/ACME-124': { status: 200, body: issue() } }));

    await run(stub, 'tcms_write_issue', {
      action: 'attach-pr',
      issue: 'ACME-124',
      prUrl: 'https://github.com/northwind/acme-storefront/pull/482',
    });

    expect(JSON.parse(stub.write('/issues/ACME-124').body ?? '{}'))
      .toEqual({ prUrl: 'https://github.com/northwind/acme-storefront/pull/482' });
  });

  it('refuses attach-pr with no URL rather than sending an empty one', async () => {
    const stub = api(agentRoutes({ 'PATCH /issues/ACME-124': { status: 200, body: issue() } }));

    await expect(run(stub, 'tcms_write_issue', { action: 'attach-pr', issue: 'ACME-124' }))
      .rejects.toThrow(/prUrl/);
    expect(stub.sent.some((request) => request.method === 'PATCH')).toBe(false);
  });

  it('names a COLUMN and lets the server pick the status it means', async () => {
    /*
      The whole of why `move-card` is not `move` with extra steps. `In flight` claims In Progress
      and In Review, and the card takes the column's DEFAULT — which the server resolves, because
      the caller does not know the mapping and would have to hold a copy of it to send a status.
    */
    const stub = api(agentRoutes({
      ...BOARDS,
      'POST /cards/ACME-124/move': { status: 200, body: cardMoveResult() },
    }));

    await run(stub, 'tcms_write_issue', {
      action: 'move-card', issue: 'ACME-124', column: 'in flight',
    });

    expect(JSON.parse(stub.write('/cards/ACME-124/move').body ?? '{}')).toEqual({ toColumnId: 'bc-2' });
  });

  it('answers the WIP breach as part of a success, because the card still landed', async () => {
    const stub = api(agentRoutes({
      ...BOARDS,
      'POST /cards/ACME-124/move': {
        status: 200,
        body: cardMoveResult({ breach: wipBreach() }),
      },
    }));

    // Story B3's automation spelling: a breach is a 200 carrying a fact, so it reaches the model as
    // data on a successful call rather than as a refusal it would retry.
    const answered = await run(stub, 'tcms_write_issue', {
      action: 'move-card', issue: 'ACME-124', column: 'In flight',
    }) as { breach?: { count: number } };

    expect(answered.breach?.count).toBe(3);
  });

  it('refuses a column this board does not have, and never sends the move', async () => {
    const stub = api(agentRoutes(BOARDS));

    await expect(run(stub, 'tcms_write_issue', {
      action: 'move-card', issue: 'ACME-124', column: 'Blocked',
    })).rejects.toThrow(/It has: To Do, In flight, Done/);
    expect(stub.sent.some((request) => request.path.endsWith('/move'))).toBe(false);
  });

  it('replaces the whole label set, so an empty array takes them all off', async () => {
    const stub = api(agentRoutes({
      ...LABELS,
      'PUT /issues/ACME-124/labels': { status: 200, body: [] },
    }));

    await run(stub, 'tcms_write_issue', { action: 'label', issue: 'ACME-124', labels: [] });

    expect(JSON.parse(stub.write('/issues/ACME-124/labels').body ?? '{}')).toEqual({ labelIds: [] });
  });

  it('resolves case KEYS and replaces the whole set a story is proven by', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([caseSummary()], { size: 1 }) },
      'PUT /issues/ACME-124/test-cases': { status: 200, body: [issueTestCase()] },
    }));

    await run(stub, 'tcms_write_issue', { action: 'cases', issue: 'ACME-124', cases: ['TC-42'] });

    // Display keys in both directions (§4.4): the endpoint takes UUIDs and no model has one.
    expect(JSON.parse(stub.write('/issues/ACME-124/test-cases').body ?? '{}'))
      .toEqual({ caseIds: ['c-1'] });
  });

  it('unlinks every case on an empty array, because the write is a replacement', async () => {
    const stub = api(agentRoutes({ 'PUT /issues/ACME-124/test-cases': { status: 200, body: [] } }));

    await run(stub, 'tcms_write_issue', { action: 'cases', issue: 'ACME-124', cases: [] });

    expect(JSON.parse(stub.write('/issues/ACME-124/test-cases').body ?? '{}')).toEqual({ caseIds: [] });
  });

  it('resolves a release VERSION to the id the PATCH takes', async () => {
    const stub = api(agentRoutes({
      'GET /releases': { status: 200, body: [releaseSummary()] },
      'PATCH /issues/ACME-124': { status: 200, body: issue({ release: releaseRef() }) },
    }));

    await run(stub, 'tcms_write_issue', { action: 'release', issue: 'ACME-124', release: '2.4' });

    expect(JSON.parse(stub.write('/issues/ACME-124').body ?? '{}')).toEqual({ releaseId: 'rl-1' });
  });

  it('clears the release on an explicit null, and refuses the field being absent', async () => {
    const stub = api(agentRoutes({ 'PATCH /issues/ACME-124': { status: 200, body: issue() } }));

    await run(stub, 'tcms_write_issue', { action: 'release', issue: 'ACME-124', release: null });
    expect(JSON.parse(stub.write('/issues/ACME-124').body ?? '{}')).toEqual({ releaseId: null });

    /*
      Absent is not the same request as null and must not become one: on a PATCH whose absent key
      means *leave it*, a caller who named neither would otherwise have the issue silently taken
      off whatever release it was on.
    */
    await expect(run(stub, 'tcms_write_issue', { action: 'release', issue: 'ACME-124' }))
      .rejects.toThrow(/required/);
  });

  it('refuses a release this project does not have, and never sends the PATCH', async () => {
    const stub = api(agentRoutes({ 'GET /releases': { status: 200, body: [releaseSummary()] } }));

    await expect(run(stub, 'tcms_write_issue', {
      action: 'release', issue: 'ACME-124', release: '9.9',
    })).rejects.toThrow(/It has: 2\.4/);
    expect(stub.sent.some((request) => request.method === 'PATCH')).toBe(false);
  });

  it('is listed for a Viewer, and says which actions they may use', () => {
    /*
      The `any` gate, and the reason for it: Viewer holds `issue:comment` and neither of the other
      two keys (roles spec §A3). An `all` gate would hide this tool from them entirely, taking a
      reader's only way of contributing away — `tcms_review_verdict`'s argument, one noun over.
    */
    const writeIssue = tool('tcms_write_issue');
    expect(writeIssue.gate.mode).toBe('any');

    const viewer = writeIssue.narrow?.(new Set(['issue:comment']));
    expect(viewer).toContain('comment');
    expect(viewer).not.toContain('create');
  });
});

describe('tcms_write_backlog', () => {
  const LABELS = { 'GET /labels': { status: 200, body: [label()] } } as const;

  it('refuses after and before together without sending a request', async () => {
    const stub = api(agentRoutes());

    await expect(run(stub, 'tcms_write_backlog', {
      action: 'rank', issue: 'ACME-124', after: 'ACME-99', before: 'ACME-98',
    })).rejects.toThrow(/exactly one/);
    expect(stub.sent.some((request) => request.path.endsWith('/rank'))).toBe(false);
  });

  it('refuses neither of them the same way, because an empty body is not "to the top"', async () => {
    // D-q. The endpoint answers 400 for both-or-neither and the top of the list is reachable as
    // `before` the current first row, so requiring exactly one closes nothing off.
    const stub = api(agentRoutes());

    await expect(run(stub, 'tcms_write_backlog', { action: 'rank', issue: 'ACME-124' }))
      .rejects.toThrow(/exactly one/);
    expect(stub.sent.some((request) => request.path.endsWith('/rank'))).toBe(false);
  });

  it('answers a rebalance as a success carrying an instruction to read again', async () => {
    const stub = api(agentRoutes({
      'PUT /issues/ACME-124/rank': { status: 200, body: rankResult({ rebalanced: true }) },
    }));

    const answered = await run(stub, 'tcms_write_backlog', {
      action: 'rank', issue: 'ACME-124', before: 'ACME-98',
    }) as { rebalanced: boolean };

    // Not an error: the placement happened. What changed is every OTHER row's rank, which is why
    // the flag has to reach the model rather than being swallowed as a detail of the write.
    expect(answered.rebalanced).toBe(true);
    expect(JSON.parse(stub.write('/rank').body ?? '{}')).toEqual({ beforeKey: 'ACME-98' });
  });

  it('carries the colour it was not given, because LabelInput requires both fields', async () => {
    const stub = api(agentRoutes({
      ...LABELS,
      'PATCH /labels/lb-1': { status: 200, body: label({ name: 'Flaky again' }) },
    }));

    await run(stub, 'tcms_write_backlog', {
      action: 'label-update', label: 'Flaky', name: 'Flaky again',
    });

    // A rename that sent only the name would clear the colour — `LabelInput` requires both, so the
    // arm reads the current row and lays the change on top.
    expect(JSON.parse(stub.write('/labels/lb-1').body ?? '{}'))
      .toEqual({ name: 'Flaky again', color: 'label-3' });
  });

  it('is hidden from a Test Engineer, who holds neither of its keys', () => {
    // §2's rule doing its job in the direction that is easy to get wrong. Ordering the backlog is
    // Project Manager and up (roles spec §A3), so an Agent credential — Test Engineer entire — is
    // not shown a tool every arm of which it would be refused on.
    expect(isListed(tool('tcms_write_backlog'), new Set(AGENT_KEYS))).toBe(false);
    expect(isListed(tool('tcms_write_issue'), new Set(AGENT_KEYS))).toBe(true);
  });
});

describe('tcms_write_release', () => {
  const RELEASES = { 'GET /releases': { status: 200, body: [releaseSummary()] } } as const;

  it('names the release by VERSION and resolves it before every arm but create', async () => {
    const stub = api(agentRoutes({
      ...RELEASES,
      'POST /releases/rl-1/publish': {
        status: 200,
        body: releaseFixture({ status: 'RELEASED', releasedAt: '2026-09-01T10:00:00Z' }),
      },
    }));

    await run(stub, 'tcms_write_release', { action: 'publish', release: '2.4' });

    // A version passed through as an id would have reached `/releases/2.4/publish` and 404ed —
    // which a model reads as "there is no such release" rather than as its own mistake.
    expect(stub.write('/publish').path).toContain('/releases/rl-1/publish');
    expect(stub.write('/publish').body).toBe(null);
  });

  it('refuses an unknown version with the list, and sends nothing', async () => {
    const stub = api(agentRoutes(RELEASES));

    await expect(run(stub, 'tcms_write_release', { action: 'delete', release: '9.9' }))
      .rejects.toThrow(/It has: 2\.4/);
    expect(stub.sent.some((request) => request.method === 'DELETE')).toBe(false);
  });

  it('sends only the fields an update named, because ReleaseUpdate is a true delta', async () => {
    const stub = api(agentRoutes({ ...RELEASES, 'PATCH /releases/rl-1': { status: 200, body: releaseFixture() } }));

    await run(stub, 'tcms_write_release', { action: 'update', release: '2.4', description: null });

    expect(JSON.parse(stub.write('/releases/rl-1').body ?? '{}')).toEqual({ description: null });
  });

  it('says the issues survived a delete, because a 204 cannot', async () => {
    const stub = api(agentRoutes({ ...RELEASES, 'DELETE /releases/rl-1': { status: 204 } }));

    const answered = await run(stub, 'tcms_write_release', { action: 'delete', release: '2.4' }) as
      { deleted: string; issues: string };

    // The FK is ON DELETE SET NULL: the plan goes and the work does not. An empty success would
    // leave a model to report that six stories had been deleted with it.
    expect(answered.deleted).toBe('2.4');
    expect(answered.issues).toContain('unscheduled');
  });

  it('declares delete as a delete, which is the only thing that makes a host prompt', () => {
    /*
      Both `delete` and `state` imply no header, so every assertion in `writeConformance.test.ts`
      passes either way and nothing else in this tree would notice the difference. What the kind
      decides is `annotationsFor`'s `destructiveHint` and `server.ts`'s consent gate — so an arm
      that removes a row and is declared `state` loses its prompt silently.
    */
    const arms = tool('tcms_write_release').arms ?? [];
    expect(arms.find((arm) => arm.action === 'delete')?.kind).toBe('delete');
    expect(deletes(tool('tcms_write_release'))).toBe(true);
  });

  it('merges two keys on one tool, and is legal only because one audience holds both', () => {
    /*
      §2's rule in the direction it permits a merge. `release:manage` and `release:publish` are
      granted to the same three roles (§A3), so there is no reachable credential holding one and
      not the other — which is the caller a merge is forbidden from producing. `matrixCopies` and
      `SeedDataTest` are what would notice the day that stops being true.
    */
    const matrix = roleMatrix();
    for (const role of ['IA', 'CA', 'PM', 'TL', 'TE', 'AS', 'V'] as const) {
      expect(matrix[role].has('release:manage'), role).toBe(matrix[role].has('release:publish'));
    }

    // And the tool is not shown to an Agent credential, which holds neither.
    expect(isListed(tool('tcms_write_release'), new Set(AGENT_KEYS))).toBe(false);
  });

  it('leaves linking a story to a release or a case to tcms_write_issue', () => {
    // Both cost `issue:edit`, and `Agent ∩ Test Engineer` holds that and neither release key — so
    // folding them in here is exactly §2's failure rather than a tidier tool.
    const actions = (tool('tcms_write_release').arms ?? []).map((arm) => arm.action);
    expect(actions).toEqual(['create', 'update', 'delete', 'publish']);

    const issueActions = (tool('tcms_write_issue').arms ?? []).map((arm) => arm.action);
    expect(issueActions).toContain('cases');
    expect(issueActions).toContain('release');
  });
});

describe('registry declarations', () => {
  /*
    Derived from the registry, so a tool added later is checked without anybody remembering to
    add it here. It fails on a tool it does not recognise rather than skipping it: a conformance
    check that silently ignores what it cannot classify is a check that stops working the first
    time somebody adds a shape it did not anticipate.

    **These are questions about the declaration, not about the request** — which is the distinction
    §205 had to draw the hard way, because this block was named for the request and answered none
    of it. A `kind` this table knows and a `run` that never sends the header are perfectly
    consistent with each other. `writeConformance.test.ts` is the half that drives.
  */
  const HEADERS: Record<string, { idempotency: boolean; ifMatch: boolean }> = {
    create: { idempotency: true, ifMatch: false },
    edit: { idempotency: false, ifMatch: true },
    state: { idempotency: false, ifMatch: false },
    delete: { idempotency: false, ifMatch: false },
  };

  it('classifies every arm of every write tool', () => {
    for (const candidate of WRITE_TOOLS) {
      expect(candidate.arms, `${candidate.name} declares no arms`).toBeDefined();
      for (const arm of candidate.arms ?? []) {
        expect(HEADERS[arm.kind], `${candidate.name}.${arm.action}`).toBeDefined();
        expect(arm.permission).toMatch(/^[a-z]+:[a-z]+$/);
      }
    }
  });

  it('declares every arm\'s permission inside the tool\'s own gate', () => {
    for (const candidate of WRITE_TOOLS) {
      for (const arm of candidate.arms ?? []) {
        // Otherwise the list would show a caller a tool one of whose arms 403s — the failure
        // §2's rule exists to prevent, arriving through the declaration rather than the merge.
        expect(candidate.gate.permissions, `${candidate.name}.${arm.action}`)
          .toContain(arm.permission);
      }
    }
  });

  it('gives every delete arm the fields its confirmation prompt has to name — §211', () => {
    /*
      The prompt asks a person to approve removing a row, so it must say WHICH row. Nothing
      derived it: `server.ts` carried a hand-written list of eight field names, not one of which
      any tool on this surface declares, so `unlink` prompted with the operation and an empty
      target for as long as the gate had existed. The rule is two questions — the arm names
      fields, and the fields are real — because the first alone is satisfied by a typo.
    */
    const deletes = WRITE_TOOLS.flatMap((candidate) =>
      (candidate.arms ?? []).filter((arm) => arm.kind === 'delete').map((arm) => ({ candidate, arm })));
    expect(deletes.length).toBeGreaterThan(0);

    for (const { candidate, arm } of deletes) {
      const id = `${candidate.name}.${arm.action}`;
      expect(arm.targets ?? [], `${id} names no target`).not.toHaveLength(0);
      for (const field of arm.targets ?? []) {
        expect(Object.keys(candidate.inputSchema.properties), id).toContain(field);
      }
    }
  });

  it('routes an action to its own arm, so a refusal names the permission that was refused', () => {
    const writeCase = tool('tcms_write_case');
    expect(armFor(writeCase, { action: 'propose' })?.permission).toBe('case:create');
    expect(armFor(writeCase, { action: 'edit' })?.permission).toBe('case:edit');
    // An unrecognised action selects no arm, and the dispatcher falls back to the tool's gate
    // rather than guessing — the server is about to refuse it anyway.
    expect(armFor(writeCase, { action: 'invented' })).toBeUndefined();
  });
});

describe('the surface as a whole — surface spec §2 and §4.1', () => {
  it('is 37 tools', () => {
    // The count is an OUTCOME of "a tool never spans two permissions on its write path", not a
    // target. It is asserted so that a merge or a split has to be a decision somebody records.
    // ~~21~~ → 23 with audit C10: `tcms_list_artifacts` and `tcms_write_artifact`, which arrived
    // together because a delete arm needs a read that serves the ids it takes (`writes.ts`).
    // ~~23~~ → 24 with `tcms_shape` (§253), which is the one tool on this surface that reads
    // nothing: it answers what a payload must look like, which is a property of the build rather
    // than of the instance, and so has no gate and no project.
    // ~~24~~ → 25 with `tcms_list_proposals`: `tcms_write_case propose` is the flagship write and
    // its queue was on neither surface, so an agent filed into a list it could never read.
    // ~~25~~ → 26 with `tcms_read_issue` (011 T5). It is a SPLIT, not a merge: the tracker's
    // reads cost `issue:read` and its writes cost three other keys, so folding them into
    // `tcms_write_issue` would gate a read on a write permission and hide the project's issues
    // from every Viewer.
    // ~~26~~ → 28 with 012 T5's board verbs, and they are TWO tools rather than one for §2's
    // reason: `tcms_read_board` is `board:read`, which every role holds, and `tcms_write_backlog`
    // is `board:rank` + `board:configure`, which is Project Manager and up. Moving a card and
    // labelling an issue are neither — they cost `issue:edit` and are arms of `tcms_write_issue`,
    // so a Test Engineer reaches them without being shown a tool two thirds of which 403s.
    // ~~28~~ → 30 with 013 T4's release verbs, and again TWO rather than one: `tcms_read_release`
    // is `release:read`, which six of the seven roles hold — Automation Service is the first
    // credential on this surface a READ tool is withheld from — and `tcms_write_release` is
    // `release:manage` + `release:publish`, which is Project Manager and up. Linking a story to a
    // release or to the cases that prove it is neither: both cost `issue:edit` and are arms of
    // `tcms_write_issue`, for the same reason `move-card` and `label` are.
    // ~~30~~ → 34 with 016 T1's time verbs, and the gates split two ways twice.
    // `tcms_read_timesheet` is the surface's first `any`-gated READ: `time:log` reads your own
    // period and `time:approve` reads the queue, and gating it `all` would hide a timesheet from
    // everybody who can only log one. `tcms_read_time_rollup` is `issue:read`, because
    // estimate-against-actual is a property of the issue and the panels that show it are the
    // story's and the epic's. On the write half, logging and submitting are both `time:log`
    // (016 D-e — submitting is logging one step later, not a fifth key) and deciding is
    // `time:approve`, which Test Lead does not hold (D-f), so one tool over both would be listed
    // to every Test Engineer and 403 on half of itself.
    // ~~34~~ → 37 with 017 T1's staffing verbs, and the gates split two ways again.
    // `tcms_read_staffing` is the surface's SECOND `any`-gated read: "who is on this project" costs
    // the PROJECT-scope `staffing:read` and "who in the company has this skill" costs the
    // COMPANY-scope `staffing:directory`, and neither of the two roles that ask those questions
    // holds the other's key — so gating it `all` would hide the whole noun from both of them.
    // On the write half, allocating is `staffing:allocate` (PROJECT) and recording somebody else's
    // absence is `staffing:absence` (COMPANY), which is a scope boundary and not merely a second
    // key: one tool over both would be listed to a Project Manager and 403 on half of itself.
    // Maintaining profiles and the skill vocabulary (`staffing:profile`) has NO tool — the writes
    // are a person's HR-shaped record, nothing on this surface can obtain the ids they take, and a
    // tool nobody could reach is the shape §3.3 excludes rather than a gap.
    // ~~37~~ → 39 with 018 T1's client verbs, and this pair splits for a THIRD reason — neither of
    // the two above. `tcms_read_client` is `client:read` alone, so its gate needs no `any`: both
    // questions it answers are COMPANY scope and one key answers both. `tcms_write_client` is
    // `any` of `client:create` and `client:edit`, which is the first time §2's rule is satisfied
    // by the ARM table rather than by splitting the tool: `record` costs the first key and the
    // other four cost the second, and `tools/list`'s filter and the refusal copy both read the
    // arm, so a credential holding one of them is told by `narrow` which half it reaches instead
    // of being shown a tool that 403s on part of itself. Splitting would have produced a
    // one-arm `tcms_record_client` beside a four-arm sibling over the same noun.
    // Deleting a CLIENT has no arm at all: the endpoint exists and refuses while any project
    // names the client, archiving is what that refusal points at, and an agent-drivable delete of
    // an organisation record that is meant to outlive the work is a capability nobody asked for.
    expect(TOOLS).toHaveLength(39);
  });

  it('names every tool in the tcms_ namespace', () => {
    for (const candidate of TOOLS) expect(candidate.name).toMatch(/^tcms_[a-z_]+$/);
  });

  it('takes no project or company argument anywhere', () => {
    for (const candidate of TOOLS) {
      const properties = Object.keys(candidate.inputSchema.properties);
      expect(properties, candidate.name).not.toContain('projectId');
      expect(properties, candidate.name).not.toContain('companyId');
    }
  });

  it('keeps every description inside the host\'s 2KB ceiling', () => {
    for (const candidate of TOOLS) {
      // Truncation is silent and takes the last sentence, which is where the caveats are.
      expect(new TextEncoder().encode(candidate.description).length, candidate.name)
        .toBeLessThanOrEqual(2000);
    }
  });

  it('never tells the model it lacks a key this credential holds — §211', () => {
    /*
      §210's own closing lesson, made mechanical: *"copy that names a role is a claim about the
      catalogue, and nothing checks it."* It found three such sentences in `backend/` and
      `frontend/` and swept neither `packages/`, where two were live — `tcms_write_run` saying
      run:close was out of reach and `tcms_record_result` saying the same of result:amend, both
      granted by `V52` in the very slice that wrote the lesson.

      **This is worse than a stale comment.** A tool description is read by the model as the
      statement of what it may attempt, so a false one does not mislead a maintainer later, it
      stops the agent trying something it is allowed to do, now, every session.

      The instrument is AGENT_KEYS. ~~This package's copy of what the `Agent` role is seeded with —
      a test constant and not the seed, so it is only as current as the last slice that moved a
      grant remembered to update it.~~ → **§213: it is read out of the roles spec's §A3**, by the
      same parse `tierClaims.test.ts` uses, so this rule and that one now answer from one source
      instead of two. `SeedDataTest.agentIsTestEngineerMinusNothingItCouldUse` is still what pins
      the real thing; this pins the prose against the spec.
    */
    const held = new Set<string>(AGENT_KEYS);
    for (const candidate of TOOLS) {
      for (const sentence of candidate.description.split(/(?<=[.!])\s+/)) {
        if (!sentence.includes('does not hold')) continue;
        for (const key of sentence.match(/\b[a-z]+:[a-z]+\b/g) ?? []) {
          expect(held.has(key), `${candidate.name} says the credential does not hold '${key}', `
            + 'and it does. Say what is actually out of reach — a permission it lacks, or a tool '
            + 'this surface has not built.')
            .toBe(false);
        }
      }
    }
  });

  it('gives every tool a schema that refuses what it does not declare', () => {
    for (const candidate of TOOLS) {
      expect(candidate.inputSchema.additionalProperties, candidate.name).toBe(false);
    }
  });
});

/**
 * The catalogue against the SURFACE — §253's other half, and the one where a disagreement is
 * invisible.
 *
 * `shapesContract.test.ts` pins `core/shapes.ts` to `openapi.yaml`, five questions deep and mostly
 * both ways, so the catalogue cannot become a fifth copy *of the contract*. It asks nothing of the
 * ~~eleven~~ twelve write tools, and eleven of them hand-write their `inputSchema` — only `tcms_write_case`
 * renders one through `jsonSchemaFor`. So `tcms_shape` could tell a model one thing about a suite
 * and `tcms_write_suite` declare another, and nothing in the repository would fire.
 *
 * §253's own rule is *"hand-written is fine; hand-written **and unpinned** is the defect."* This is
 * the pin. It does not force the ten tools to be rendered — a tool schema legitimately carries
 * wrapper arguments the entity has none of (`action`, the display key, `lockVersion`) — it asserts
 * only the direction that matters: **a field the catalogue tells a caller to send must be a field
 * the tool accepts.** The other direction is the wrapper set, and `additionalProperties: false`
 * plus the exchange check already cover a property the contract does not have.
 */
describe('the shape catalogue against the tools that declare it', () => {
  /*
    Explicit rather than derived from the name: `result`'s tool is `tcms_record_result`, not
    `tcms_write_result`, and a convention that happened to hold for seven of eight would quietly
    skip the eighth. A missing entry fails below rather than being ignored.
  */
  const TOOL_FOR: Record<string, string> = {
    artifact: 'tcms_write_artifact',
    case: 'tcms_write_case',
    docPage: 'tcms_write_doc_page',
    plan: 'tcms_write_plan',
    result: 'tcms_record_result',
    run: 'tcms_write_run',
    issue: 'tcms_write_issue',
    suite: 'tcms_write_suite',
  };

  /**
   * Every property a caller may send, one nesting level down included.
   *
   * `tcms_write_case`'s `propose` arm carries the whole case under `testCase`, because a proposal
   * is a wrapper around one — so a flat read of the schema would report eight of that action's
   * fields as missing when every one of them is declared.
   */
  function acceptedBy(schema: { properties: Record<string, unknown> }): Set<string> {
    const names = new Set<string>();
    for (const [name, property] of Object.entries(schema.properties)) {
      names.add(name);
      const nested = (property as { properties?: Record<string, unknown> }).properties;
      for (const inner of Object.keys(nested ?? {})) names.add(inner);
    }
    return names;
  }

  it('declares every field the catalogue offers, for every body-carrying arm', () => {
    const gaps: string[] = [];

    for (const [entity, shape] of Object.entries(SHAPES)) {
      const toolName = TOOL_FOR[entity];
      const tool = TOOLS.find((candidate) => candidate.name === toolName);
      expect(tool, `no tool is mapped for the '${entity}' shape`).toBeDefined();
      const accepted = acceptedBy(tool!.inputSchema);

      for (const action of shape.actions) {
        // A `state` or `delete` arm carries no body, so the entity's fields say nothing about it.
        if (!action.mcp || (action.kind !== 'create' && action.kind !== 'edit')) continue;

        for (const field of fieldsFor(shape, action.name)) {
          if (!accepted.has(field.name)) {
            gaps.push(`${toolName} (${action.name}) does not accept '${field.name}'`);
          }
        }
      }
    }

    /*
      Listed rather than asserted one at a time, because the useful failure is the whole list: this
      check exists to turn silence into a list, the same way question four of
      `shapesContract.test.ts` did.
    */
    expect(gaps).toEqual([]);
  });

  it('maps every entity in the catalogue, so a ninth cannot arrive unpinned', () => {
    expect(Object.keys(TOOL_FOR).sort()).toEqual(Object.keys(SHAPES).sort());
  });
});

/**
 * The merge rule against the ROLE CATALOGUE — surface spec §8.2, specified in §140 and not built.
 *
 * §2's rule is that arms merge into one tool only when every reachable intersection holds all of
 * them, because `tools/list` filters on the whole gate: a role holding two of a three-key gate does
 * not get a narrowed tool, it gets **no tool**, and then goes looking for a capability the surface
 * says it has. The nearest existing check (`registry declarations`, above) asserts each arm's
 * permission is inside its own tool's gate — self-consistency, which a gate wrong in both places
 * satisfies.
 *
 * The intersection to check is §2's: `Agent ∩ each ASSIGNABLE role`, because an MCP credential
 * carries `Agent` and a person's grants narrow it. `Automation Service` is excluded for the reason
 * §134 keeps having to be restated — `V41` made it non-assignable, and `tcms_write_run`'s two-key
 * merge is legal only because of that.
 *
 * Every merge is legal today, which is what makes this worth writing rather than skipping: nothing
 * fails now, and a migration that narrows one key would otherwise take a whole tool away from every
 * contributor with the build staying green.
 */
describe('the merge rule against the role catalogue — surface spec §8.2', () => {
  /** `AS` is non-assignable (`V41`); `IA` is the instance tier and holds everything below it. */
  const ASSIGNABLE: readonly Role[] = ['CA', 'TL', 'TE', 'V'];

  it('gives no assignable role a strict subset of any tool\'s arms', () => {
    const matrix = roleMatrix();
    const agent = new Set(AGENT_KEYS);
    const partial: string[] = [];

    for (const candidate of TOOLS) {
      const gate = candidate.gate;
      // `any` is the narrowed shape this rule is the alternative to — `tcms_review_verdict` is
      // listed for a caller holding one of its three keys, and says so in its own description.
      if (gate.mode !== 'all' || gate.permissions.length < 2) continue;

      for (const role of ASSIGNABLE) {
        const held = new Set([...matrix[role]].filter((key) => agent.has(key)));
        const has = gate.permissions.filter((key) => held.has(key));
        if (has.length > 0 && has.length < gate.permissions.length) {
          const short = gate.permissions.filter((key) => !held.has(key));
          partial.push(`${candidate.name} is hidden from ${role}, which holds `
            + `${has.join(', ')} and not ${short.join(', ')}`);
        }
      }
    }

    expect(partial).toEqual([]);
  });

  it('finds a multi-key gate to ask about', () => {
    // Vacuity: eight tools gate `all` over two or three keys. A registry this could not see would
    // pass by having nothing to assert on.
    const merged = TOOLS.filter((candidate) =>
      candidate.gate.mode === 'all' && candidate.gate.permissions.length >= 2);
    expect(merged.length).toBeGreaterThanOrEqual(6);
  });
});
