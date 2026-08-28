/**
 * A Gherkin-lens case, created and edited through the CLI against the §255 contract.
 *
 * The other write tests drive a STRUCTURED case, which is the lens that did not change. This file
 * is the one that would have caught the shape §255 replaced: a scenario used to travel as a free
 * text blob on `TestCaseInput`, and it now travels as the same step rows a STRUCTURED case has,
 * each carrying a `keyword` and at most one of `testData` / `docString` / `dataTable`.
 *
 * The negative assertion is the one worth having. That the payload carries the new keys is easy to
 * see; that it carries **no `gherkinSource`** is not, because a client still sending one would not
 * be refused by the server — an unknown property is ignored, which is what keeps an old client
 * working — so nothing downstream of here could notice. The harness's comparison against
 * `openapi.yaml` reports it as undeclared, and this file says it in as many words too, because the
 * field is gone from the write contract and not from anybody's memory.
 */
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { caseDetail, casePage, caseSummary, proposal, step } from '../../client/src/test/fixtures.js';

import { EXIT } from './main.js';
import { PROJECT, api, mainWith, recorder } from './test/harness.js';

/** The block argument, in the one shape a JSON payload can carry it: the text between the fences. */
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

function routes(): Record<string, { status: number; body?: unknown }> {
  return {
    '/projects': { status: 200, body: [PROJECT] },
    'GET /cases': {
      status: 200,
      body: casePage([caseSummary({ id: 'c-9', key: 'TC-42', stepFormat: 'GHERKIN' })], { size: 1 }),
    },
    'GET /cases/c-9': { status: 200, body: CASE },
  };
}

function tempJson(value: unknown): string {
  const path = join(mkdtempSync(join(tmpdir(), 'tcms-cli-gherkin-')), 'case.json');
  writeFileSync(path, JSON.stringify(value));
  return path;
}

describe('a Gherkin-lens case, end to end', () => {
  it('files a scenario as step rows with keywords, a doc string and a table', async () => {
    const out = recorder();
    const stub = api({ ...routes(), 'POST /ai-proposals': { status: 201, body: proposal() } });

    expect(await mainWith(['case', 'propose', '--file', tempJson(PROPOSABLE)], out, stub.fetch))
      .toBe(EXIT.ok);

    const body = stub.write('/ai-proposals').body ?? '{}';
    expect(JSON.parse(body)).toEqual({ testCase: PROPOSABLE });
    // The retired field, named as a string rather than as a key, because its absence is the point.
    expect(body).not.toContain('gherkinSource');
  });

  it('round-trips every step field on an edit, and sends no retired scenario field', async () => {
    const out = recorder();
    const stub = api({
      ...routes(),
      'PATCH /cases/c-9': { status: 200, body: { ...CASE, title: 'Checkout emails the buyer', lockVersion: 4 } },
    });

    expect(await mainWith(
      ['case', 'edit', 'TC-42', '--title', 'Checkout emails the buyer'], out, stub.fetch,
    )).toBe(EXIT.ok);

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
      trip can be lossless. `--json`, because `case show`'s TEXT rendering prints no steps at all —
      it never has, for either lens, and giving it a step table is a surface change this slice was
      not asked for. The `--json` body is the whole resource, so nothing is lost, and the harness's
      comparison against `openapi.yaml` is what makes that a claim rather than a hope.
    */
    const out = recorder();
    const stub = api(routes());

    expect(await mainWith(['case', 'show', 'TC-42', '--json'], out, stub.fetch)).toBe(EXIT.ok);

    const served = JSON.parse(out.stdout) as {
      steps: { keyword: string; docString: string | null; dataTable: string[][] | null }[];
    };
    expect(served.steps.map((row) => row.keyword)).toEqual(['GIVEN', 'WHEN', 'THEN']);
    expect(served.steps[1]!.dataTable).toEqual(DATA_TABLE);
    expect(served.steps[2]!.docString).toBe(DOC_STRING);
  });

  it('shows all three new step fields, with an example each, in tcms format case --long', async () => {
    /*
      The criterion T2 carries, asked of the carrier that can answer it. `case edit --help` cannot:
      `steps` is a `propose` field on this surface (neither adapter's edit arm takes one), and argv
      cannot carry an object array in any case. `tcms format case --long` prints the same guide the
      MCP's `tcms_shape` returns, from the same catalogue, which is what makes one source one
      answer rather than one file.
    */
    const out = recorder();
    const stub = api(routes());

    expect(await mainWith(['format', 'case', '--long'], out, stub.fetch)).toBe(EXIT.ok);

    expect(out.stdout).toContain('`keyword`');
    expect(out.stdout).toContain('Given, When, Then, And, But');
    expect(out.stdout).toContain('`docString`');
    expect(out.stdout).toContain('`dataTable` — string[][]');
    expect(out.stdout).toContain('e.g. `[["name","qty"],["widget","2"]]`');
  });

  it('refuses a scenario with no steps, because the lens no longer has a second body', async () => {
    /*
      Before §255 this payload was legal — a GHERKIN case carried its scenario in a text field and
      `steps` was a STRUCTURED-only requirement. It is now a case with no procedure at all, and
      both non-SPA clients refuse one at the boundary rather than filing a proposal a reviewer
      cannot accept (`core/proposal.ts`).
    */
    const out = recorder();
    const stub = api({ ...routes(), 'POST /ai-proposals': { status: 201, body: proposal() } });
    const file = tempJson({ ...PROPOSABLE, steps: [] });

    expect(await mainWith(['case', 'propose', '--file', file], out, stub.fetch)).not.toBe(EXIT.ok);
    expect(out.stderr).toContain('steps');
    expect(stub.sent.some((request) => request.method === 'POST')).toBe(false);
  });
});
