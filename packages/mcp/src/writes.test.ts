/**
 * The write tools, and the two properties that must hold over the whole registry.
 *
 * `header conformance` is the surface spec's §8.3 — *assert every create arm sends a key and every
 * edit arm sends `If-Match`, over the tool registry rather than per tool*. It is the same check
 * the CLI runs over its command registry, deliberately: the two surfaces share a command core
 * precisely so that a rule proved on one holds on the other, and two conformance tests that
 * disagreed about what a create is would be the thing that lets them drift.
 */
import { describe, expect, it } from 'vitest';

import { armFor } from './server.js';
import { TOOLS } from './tools.js';
import { WRITE_TOOLS } from './writes.js';
import type { ToolDefinition } from './tool.js';
import { agentRoutes, api, sessionWith, withApi, type Api } from './test/harness.js';

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
const CASE_ROW = {
  id: 'c-1', key: 'TC-42', keySeq: 42, title: 'Login', suiteId: 's-1', suiteName: 'Auth',
  priority: 'HIGH', lifecycleStatus: 'DRAFT', automationStatus: 'MANUAL', stepFormat: 'STRUCTURED',
  owner: null, createdBy: null, features: [], tags: [], healthSignals: [],
  updatedAt: '2026-08-01T10:00:00Z',
};
/** A WHOLE detail — the edit arm reads this and sends it back, so its shape is the assertion. */
const CASE_DETAIL = {
  ...CASE_ROW,
  description: 'The happy path.',
  objective: null,
  expectedOutcome: null,
  severity: null,
  gherkinSource: null,
  automationKey: null,
  automationTags: ['@smoke'],
  ownerId: 'u-1',
  notAutomatableReason: null,
  estimatedDurationSec: null,
  classificationValueIds: [],
  tags: ['auth'],
  steps: [
    {
      id: 'st-1', position: 1, keyword: null, action: 'Sign in',
      testData: null, expectedResult: 'In.', sharedStepId: null,
    },
  ],
  preconditions: [],
  postconditions: [],
  // A stored decline, so "the round-trip does not assert declines" is a claim about a case that
  // has one rather than about an empty array (§189).
  declinedPreconditions: [
    {
      id: 'sc-2', position: 1, conditionId: 'cond-2', title: 'Seed the ledger',
      body: [], inline: false, inherited: true, inheritedFrom: 'Billing',
    },
  ],
  declinedPostconditions: [],
  attachments: [],
  flake: null,
  currentVersionNo: 4,
  lockVersion: 4,
};
/** `caseInputFrom(CASE_DETAIL)` — the unchanged round-trip, minus the two refused fields. */
const CASE_ECHO = {
  title: 'Login',
  suiteId: 's-1',
  description: 'The happy path.',
  objective: null,
  expectedOutcome: null,
  stepFormat: 'STRUCTURED',
  gherkinSource: null,
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
      testData: null, expectedResult: 'In.', sharedStepId: null,
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
const SUITE_ROW = {
  id: 's-1', key: 'TS-7', name: 'Auth', description: null, caseCount: 2,
  lifecycleStatus: 'DRAFT', tagRules: [], caseIds: [],
  preconditions: [], postconditions: [], lockVersion: 2,
};
const PLAN_ROW = {
  id: 'tp-1', key: 'TP-1', name: 'Release 4.2', status: 'DRAFT',
  milestoneName: 'GA', milestoneDate: '2026-09-01', caseCount: 3, passRate: null, coverage: null,
  description: 'The regression pass before GA.',
  // Reachable from no tool argument at all, which is what made them the sharpest half of the
  // plan defect: EVERY edit this server made emptied both, and no caller could have avoided it.
  scopeText: 'Checkout, payments, refunds.',
  outOfScopeText: 'Anything behind the beta flag.',
  entryCriteria: [], exitCriteria: [], configurations: [],
  suiteIds: [], caseIds: [], tags: [], sections: [],
  currentVersionNo: 2, lockVersion: 7,
};
/** `planInputFrom(PLAN_ROW)` — every scalar, and none of the lists. */
const PLAN_ECHO = {
  name: 'Release 4.2',
  description: 'The regression pass before GA.',
  milestoneName: 'GA',
  milestoneDate: '2026-09-01',
  scopeText: 'Checkout, payments, refunds.',
  outOfScopeText: 'Anything behind the beta flag.',
};
const PAGE = {
  id: 'd-1', key: 'DOC-1', title: 'Spec', status: 'DRAFT', lockVersion: 5,
  blocks: [{ id: 'b-1', blockType: 'PARAGRAPH', text: 'The user signs in.' }],
};

describe('tcms_write_case', () => {
  it('files a proposal rather than creating a case, and carries an idempotency key', async () => {
    const stub = api(agentRoutes({
      'POST /ai-proposals': {
        status: 201,
        body: { id: 'ap-1', status: 'PENDING', verdict: 'UNVERIFIED', verdictSource: null },
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
      'POST /ai-proposals': { status: 201, body: { id: 'ap-1', status: 'PENDING' } },
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
      'POST /ai-proposals': { status: 201, body: { id: 'ap-1', status: 'PENDING' } },
    }));

    await expect(run(stub, 'tcms_write_case', {
      action: 'propose',
      testCase: { title: 'Sign out clears the session' },
    })).rejects.toThrow(/stepFormat.*priority.*automationStatus/s);

    expect(stub.sent.some((request) => request.method === 'POST')).toBe(false);
  });

  it('edits with an If-Match read for itself when no lockVersion was given', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
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
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
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
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
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
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
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
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
    }));

    // A `PATCH` with an empty body is accepted by the contract and changes nothing while
    // answering 200 — so a call whose fields were all misspelled would report success.
    await expect(run(stub, 'tcms_write_case', { action: 'edit', case: 'TC-42' }))
      .rejects.toThrow(/Nothing to change/);
  });

  it('submits for review through the one bulk action whose gate does not depend on its payload', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
      'POST /cases/bulk': { status: 200, body: { changed: 1 } },
    }));

    await run(stub, 'tcms_write_case', { action: 'submit', case: 'TC-42' });

    expect(JSON.parse(stub.write('/cases/bulk').body ?? '{}'))
      .toEqual({ action: 'SUBMIT_FOR_REVIEW', caseIds: ['c-1'], dryRun: false });
  });
});

describe('tcms_write_doc_page', () => {
  it('demands the caller\'s own lockVersion when replacing blocks', async () => {
    const stub = api(agentRoutes({ '/doc-pages': { status: 200, body: [PAGE] } }));

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
      '/doc-pages': { status: 200, body: [PAGE] },
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
      '/doc-pages': { status: 200, body: [PAGE] },
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
      'POST /links': { status: 201, body: { id: 'l-1', sourceType: 'TEST_CASE', sourceKey: 'TC-42', relationship: 'VERIFIES' } },
    }));

    await run(stub, 'tcms_write_doc_page', { action: 'link', page: 'DOC-1', block: 'b-1', case: 'TC-42' });

    expect(JSON.parse(stub.write('/links').body ?? '{}'))
      .toEqual({ sourceType: 'TEST_CASE', sourceId: 'c-1', relationship: 'VERIFIES' });
  });

  it('refuses a link that names both a case and a suite, or neither', async () => {
    const stub = api(agentRoutes({ '/doc-pages': { status: 200, body: [PAGE] } }));

    await expect(run(stub, 'tcms_write_doc_page', { action: 'link', page: 'DOC-1', block: 'b-1' }))
      .rejects.toThrow(/exactly one/);
  });
});

describe('tcms_write_suite', () => {
  it('creates with a key, and sends the membership as the complete set it is', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
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
      '/runs': { status: 200, body: [{ id: 'r-1', key: 'TR-1', name: 'Regression', status: 'IN_PROGRESS' }] },
      'GET /runs/r-1': {
        status: 200,
        body: { id: 'r-1', key: 'TR-1', name: 'Regression', executions: [{ id: 'e-1', caseId: 'c-1', caseKey: 'TC-42' }] },
      },
      '/statuses': { status: 200, body: [{ id: 'st-1', name: 'Passed', kind: 'PASSED', color: '#0a0', countsAsPass: true, isFinal: true }] },
      'GET /executions/e-1': { status: 200, body: { id: 'e-1', caseKey: 'TC-42', lockVersion: 3 } },
      'PATCH /executions/e-1': { status: 200, body: { id: 'e-1', caseKey: 'TC-42', lockVersion: 4, status: { name: 'Passed' } } },
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
      '/runs': { status: 200, body: [{ id: 'r-1', key: 'TR-1', name: 'R', status: 'IN_PROGRESS' }] },
      'GET /runs/r-1': { status: 200, body: { id: 'r-1', key: 'TR-1', name: 'R', executions: [{ id: 'e-1', caseId: 'c-1', caseKey: 'TC-42' }] } },
      '/statuses': { status: 200, body: [{ id: 'st-1', name: 'Passed', kind: 'PASSED', color: '#0a0', countsAsPass: true, isFinal: true }] },
    }));

    await expect(run(stub, 'tcms_record_result', { run: 'TR-1', case: 'TC-42', status: 'Green' }))
      .rejects.toThrow(/It offers: Passed/);
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
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
      'GET /reviews': {
        status: 200,
        body: [
          { id: 'rv-2', state: 'APPROVED', comments: [] },
          { id: 'rv-1', state: 'IN_REVIEW', comments: [] },
        ],
      },
      'POST /participations': { status: 201, body: { id: 'rv-1', state: 'IN_REVIEW' } },
    }));

    await run(stub, 'tcms_review_verdict', { action: 'verdict', subject: 'TC-42', verdict: 'APPROVED' });

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
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
      'GET /reviews': { status: 200, body: [{ id: 'rv-2', state: 'APPROVED', comments: [] }] },
    }));

    await expect(run(stub, 'tcms_review_comment', { subject: 'TC-42', body: 'hi' }))
      .rejects.toThrow(/review has ended/);
  });
});

describe('header conformance — surface spec §8.3', () => {
  /*
    Derived from the registry, so a tool added later is checked without anybody remembering to
    add it here. It fails on a tool it does not recognise rather than skipping it: a conformance
    check that silently ignores what it cannot classify is a check that stops working the first
    time somebody adds a shape it did not anticipate.
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
  it('is 21 tools', () => {
    // The count is an OUTCOME of "a tool never spans two permissions on its write path", not a
    // target. It is asserted so that a merge or a split has to be a decision somebody records.
    expect(TOOLS).toHaveLength(21);
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

  it('gives every tool a schema that refuses what it does not declare', () => {
    for (const candidate of TOOLS) {
      expect(candidate.inputSchema.additionalProperties, candidate.name).toBe(false);
    }
  });
});
