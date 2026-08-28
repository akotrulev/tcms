/**
 * A Gherkin-lens case, created and edited through the MCP tools against the §255 contract.
 *
 * The CLI's twin file one package over carries the argument in full and it is the same argument:
 * a scenario used to travel as a free text blob on `TestCaseInput` and now travels as the same
 * step rows a STRUCTURED case has, each with a `keyword` and at most one of `testData` /
 * `docString` / `dataTable`. Both surfaces render that shape from one catalogue
 * (`client/src/core/shapes.ts`), so this file is here to prove the rendering reaches the wire on
 * this adapter too — a shared source that only one adapter is driven through is a shared source in
 * name.
 *
 * The assertion that matters is the negative one: the payload carries **no `gherkinSource`**. The
 * server would not refuse one (an unknown property is ignored, which is what keeps an old client
 * working), so nothing downstream of this line can notice a client that still sends it.
 */
import { describe, expect, it } from 'vitest';

import { caseDetail, casePage, caseSummary, proposal, step } from '../../client/src/test/fixtures.js';

import { READ_TOOLS } from './reads.js';
import { shape } from './shape.js';
import { WRITE_TOOLS } from './writes.js';
import { agentRoutes, api, sessionWith, withApi, type Api } from './test/harness.js';

const DOC_STRING = 'Dear customer,\nYour order shipped.';
const DATA_TABLE = [['name', 'qty'], ['widget', '2']];

/** Three keyword rows, one doc string, one table — the whole of what a `.feature` scenario holds. */
const SCENARIO = [
  { keyword: 'GIVEN', action: 'a customer with a full cart' },
  { keyword: 'WHEN', action: 'they check out with these lines', dataTable: DATA_TABLE },
  { keyword: 'THEN', action: 'they receive this email', docString: DOC_STRING },
];

const PROPOSABLE = {
  title: 'Checkout emails the customer',
  stepFormat: 'GHERKIN',
  priority: 'HIGH',
  automationStatus: 'MANUAL',
  steps: SCENARIO,
};

/** The stored case the edit arm reads and projects back — the same scenario, with step ids. */
const CASE = caseDetail({
  id: 'c-9',
  key: 'TC-42',
  keySeq: 42,
  title: 'Checkout emails the customer',
  suiteId: 's-1',
  suiteName: 'Smoke',
  stepFormat: 'GHERKIN',
  severity: null,
  steps: [
    step({ id: 'st-1', position: 1, keyword: 'GIVEN', action: 'a customer with a full cart', expectedResult: null }),
    step({
      id: 'st-2', position: 2, keyword: 'WHEN', action: 'they check out with these lines',
      expectedResult: null, dataTable: DATA_TABLE,
    }),
    step({
      id: 'st-3', position: 3, keyword: 'THEN', action: 'they receive this email',
      expectedResult: null, docString: DOC_STRING,
    }),
  ],
  currentVersionNo: 3,
  lockVersion: 3,
});

function routes(extra: Record<string, { status: number; body?: unknown }> = {}) {
  return agentRoutes({
    '/cases': {
      status: 200,
      body: casePage([caseSummary({ id: 'c-9', key: 'TC-42', stepFormat: 'GHERKIN' })], { size: 1 }),
    },
    'GET /cases/c-9': { status: 200, body: CASE },
    ...extra,
  });
}

async function writeCase(stub: Api, input: Record<string, unknown>): Promise<unknown> {
  const tool = WRITE_TOOLS.find((candidate) => candidate.name === 'tcms_write_case')!;
  const session = await sessionWith(stub);
  return withApi(stub, () => tool.run({ ...session, callId: 'mcp-7' }, input));
}

async function openCase(stub: Api, key: string): Promise<Record<string, unknown>> {
  const tool = READ_TOOLS.find((candidate) => candidate.name === 'tcms_open_case')!;
  const session = await sessionWith(stub);
  return await withApi(stub, () => tool.run(session, { case: key })) as Record<string, unknown>;
}

describe('a Gherkin-lens case, end to end', () => {
  it('files a scenario as step rows with keywords, a doc string and a table', async () => {
    const stub = api(routes({ 'POST /ai-proposals': { status: 201, body: proposal() } }));

    await writeCase(stub, { action: 'propose', testCase: PROPOSABLE });

    const body = stub.write('/ai-proposals').body ?? '{}';
    expect(JSON.parse(body)).toEqual({ testCase: PROPOSABLE });
    // The retired field, named as a string rather than as a key, because its absence is the point.
    expect(body).not.toContain('gherkinSource');
  });

  it('round-trips every step field on an edit, and sends no retired scenario field', async () => {
    const stub = api(routes({
      'PATCH /cases/c-9': { status: 200, body: { ...CASE, title: 'Checkout emails the buyer', lockVersion: 4 } },
    }));

    await writeCase(stub, { action: 'edit', case: 'TC-42', title: 'Checkout emails the buyer' });

    const sent = stub.write('/cases/c-9');
    expect(sent.method).toBe('PATCH');
    // From the same read the body was projected from, which is what makes the precondition honest.
    expect(sent.headers.get('If-Match')).toBe('3');

    /*
      The WHOLE editable set, asserted as a whole rather than key by key: this `PATCH` is partial
      by verb only, so a field the projection drops is a field this edit CLEARS. `docString` and
      `dataTable` are the two that did not exist before §255 and are the two a hand-written
      projection would have missed.
    */
    expect(JSON.parse(sent.body ?? '{}')).toEqual({
      title: 'Checkout emails the buyer',
      suiteId: 's-1',
      description: null,
      objective: null,
      expectedOutcome: null,
      stepFormat: 'GHERKIN',
      priority: 'HIGH',
      automationStatus: 'MANUAL',
      automationKey: null,
      notAutomatableReason: null,
      estimatedDurationSec: null,
      classificationValueIds: [],
      tags: [],
      steps: [
        {
          id: 'st-1', keyword: 'GIVEN', action: 'a customer with a full cart',
          testData: null, expectedResult: null, docString: null, dataTable: null,
        },
        {
          id: 'st-2', keyword: 'WHEN', action: 'they check out with these lines',
          testData: null, expectedResult: null, docString: null, dataTable: DATA_TABLE,
        },
        {
          id: 'st-3', keyword: 'THEN', action: 'they receive this email',
          testData: null, expectedResult: null, docString: DOC_STRING, dataTable: null,
        },
      ],
    });
    expect(sent.body).not.toContain('gherkinSource');
  });

  it('serves the doc string and the table back on the read', async () => {
    /*
      The third leg of create -> edit -> read, and the one that decides whether a `.feature` round
      trip can be lossless: a scenario a caller filed is only recoverable if the read carries every
      argument it carried. `tcms_open_case` spreads the whole detail, so this asserts the CONTRACT
      served it rather than the tool reshaping it — which the harness's own comparison against
      `openapi.yaml` is what makes true.
    */
    const answer = await openCase(api(routes()), 'TC-42') as {
      steps: { keyword: string; docString: string | null; dataTable: string[][] | null }[];
    };

    expect(answer.steps.map((row) => row.keyword)).toEqual(['GIVEN', 'WHEN', 'THEN']);
    expect(answer.steps[1]!.dataTable).toEqual(DATA_TABLE);
    expect(answer.steps[2]!.docString).toBe(DOC_STRING);
  });

  it('shows all three new step fields, with an example each, through tcms_shape', async () => {
    /*
      The criterion T2 carries, asked of the carrier that can answer it. `tool-mcp-builder` step 4
      — every field description ends with its example, because that is the agent's first-call
      template — and `dataTable`'s nesting is exactly what a caller guesses wrong without one.

      The long-form guide is where a NESTED field's example can appear at all: a tool schema
      carries it too, and `usageFor` lists an object array's inner fields by name only.
    */
    const stub = api(agentRoutes());
    const session = await sessionWith(stub);
    const answer = (await withApi(stub, () => shape.run(session, { entity: 'case' }))) as {
      guide: string;
    };

    expect(answer.guide).toContain('`keyword`');
    expect(answer.guide).toContain('Given, When, Then, And, But');
    expect(answer.guide).toContain('`docString`');
    expect(answer.guide).toContain('`dataTable` — string[][]');
    expect(answer.guide).toContain('e.g. `[["name","qty"],["widget","2"]]`');
  });

  it('refuses a scenario with no steps, because the lens no longer has a second body', async () => {
    /*
      Before §255 this payload was legal — a GHERKIN case carried its scenario in a text field and
      `steps` was a STRUCTURED-only requirement. It is now a case with no procedure at all, and
      both non-SPA clients refuse one at the boundary rather than filing a proposal a reviewer
      cannot accept (`core/proposal.ts`).
    */
    const stub = api(routes({ 'POST /ai-proposals': { status: 201, body: proposal() } }));

    await expect(writeCase(stub, { action: 'propose', testCase: { ...PROPOSABLE, steps: [] } }))
      .rejects.toThrow(/steps/);
    expect(stub.sent.some((request) => request.method === 'POST')).toBe(false);
  });
});
