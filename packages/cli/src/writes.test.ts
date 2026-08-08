/**
 * The write commands, and the check that decides whether the next one is safe.
 *
 * `header conformance` is the surface spec's §8.3 — *assert every create arm sends a key and
 * every edit arm sends `If-Match`, over the registry rather than per tool*. It walks `COMMANDS`
 * and drives each declared write through a recording `fetch`, so a twelfth write command that
 * forgets a header fails the build instead of quietly overwriting somebody's edit in the field.
 * The same shape as `RepositoryTransactionTest` deriving its repository list from the live
 * context, and for §108's reason: a check that reads a maintained list is a check on the list.
 */
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { COMMANDS } from './commands.js';
import { EXIT } from './main.js';
import { PROJECT, api, mainWith, recorder } from './test/harness.js';

const CASE = {
  id: 'c-9',
  key: 'TC-42',
  title: 'Login works',
  lifecycleStatus: 'DRAFT',
  priority: 'HIGH',
  tags: [],
  lockVersion: 3,
};

const PROPOSAL = {
  id: 'ap-1',
  status: 'PENDING',
  verdict: 'UNVERIFIED',
  verdictSource: 'NONE',
  createdAt: '2026-08-08T10:00:00Z',
  filedByAgent: true,
  testCase: { title: 'Login works' },
};

const SUITE = {
  id: 's-1',
  key: 'TS-7',
  name: 'Smoke',
  caseCount: 4,
  lifecycleStatus: 'DRAFT',
  tagRules: [],
  caseIds: [],
  conditions: [],
  lockVersion: 2,
};

const PAGE = {
  id: 'd-1',
  key: 'DOC-3',
  title: 'Payments spec',
  status: 'DRAFT',
  blocks: [{ id: 'b-1', blockType: 'paragraph', text: 'Refunds settle in 3 days.' }],
  lockVersion: 5,
};

const PLAN = {
  id: 'tp-1',
  key: 'TP-1',
  name: 'Release 4.2',
  status: 'DRAFT',
  caseCount: 12,
  lockVersion: 7,
};

const RUN = {
  id: 'tr-5',
  key: 'TR-5',
  name: 'Nightly',
  status: 'IN_PROGRESS',
  executions: [{ id: 'e-1', caseId: 'c-9', caseKey: 'TC-42', caseTitle: 'Login works' }],
};

const STATUSES = [
  { id: 'st-1', name: 'Passed', kind: 'PASS', color: '#0a0', countsAsPass: true, isFinal: true },
  { id: 'st-2', name: 'Failed', kind: 'FAIL', color: '#a00', countsAsPass: false, isFinal: true },
];

const EXECUTION = {
  id: 'e-1',
  caseId: 'c-9',
  caseKey: 'TC-42',
  caseTitle: 'Login works',
  status: STATUSES[0],
  lockVersion: 1,
};

const REVIEW = {
  id: 'rv-1',
  subjectType: 'CASE',
  subjectId: 'c-9',
  subjectVersionNo: 3,
  state: 'IN_REVIEW',
  createdAt: '2026-08-08T09:00:00Z',
  assignees: [],
  participations: [],
  comments: [],
};

/** The reads every command makes on its way to a write: project resolution and key lookup. */
function scaffolding(): Record<string, { status: number; body?: unknown }> {
  return {
    '/projects': { status: 200, body: [PROJECT] },
    'GET /cases': {
      status: 200,
      body: { page: 0, size: 1, total: 1, facets: [], items: [CASE] },
    },
    'GET /cases/c-9': { status: 200, body: CASE },
    'GET /suites': { status: 200, body: [SUITE] },
    'GET /suites/s-1': { status: 200, body: SUITE },
    'GET /doc-pages': { status: 200, body: [PAGE] },
    'GET /doc-pages/d-1': { status: 200, body: PAGE },
    'GET /plans': { status: 200, body: [PLAN] },
    'GET /plans/tp-1': { status: 200, body: PLAN },
    'GET /runs': { status: 200, body: [RUN] },
    'GET /runs/tr-5': { status: 200, body: RUN },
    'GET /executions/e-1': { status: 200, body: EXECUTION },
    'GET /statuses': { status: 200, body: STATUSES },
    'GET /reviews': { status: 200, body: [REVIEW] },
  };
}

function tempJson(name: string, value: unknown): string {
  const path = join(mkdtempSync(join(tmpdir(), 'tcms-cli-')), name);
  writeFileSync(path, JSON.stringify(value));
  return path;
}

describe('case edit', () => {
  it('reads the case for its lockVersion and sends it as If-Match', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: { ...CASE, lockVersion: 4 } } });

    expect(await mainWith(['case', 'edit', 'TC-42', '--title', 'Login still works'], out, stub.fetch))
      .toBe(EXIT.ok);

    const sent = stub.write('/cases/c-9');
    expect(sent.method).toBe('PATCH');
    expect(sent.headers.get('If-Match')).toBe('3');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ title: 'Login still works' });
    expect(out.stdout).toContain('Lock version: 4');
  });

  /*
    The absent/null/empty split the contract draws on every one of these fields, and the reason
    the CLI needs a spelling for each: a client that collapsed absent and null would empty fields
    nobody mentioned, and one with no clearing form could never empty a field at all.
  */
  it('leaves unnamed fields alone, clears an empty one, and files by suite key', async () => {
    const out = recorder();
    const stub = api({
      ...scaffolding(),
      'GET /suites': { status: 200, body: [{ id: 's-1', key: 'TS-7', name: 'Smoke', caseCount: 1, lifecycleStatus: 'DRAFT', tagRules: [], caseIds: [], conditions: [], lockVersion: 1 }] },
      'PATCH /cases/c-9': { status: 200, body: CASE },
    });

    await mainWith(
      ['case', 'edit', 'TC-42', '--description', '', '--suite', 'TS-7', '--tag', 'smoke'],
      out,
      stub.fetch,
    );

    expect(JSON.parse(stub.write('/cases/c-9').body ?? '{}')).toEqual({
      description: null,
      suiteId: 's-1',
      tags: ['smoke'],
    });
  });

  it('sends an explicit null for --unfile, which is not the same as omitting the suite', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: CASE } });

    await mainWith(['case', 'edit', 'TC-42', '--unfile'], out, stub.fetch);

    expect(JSON.parse(stub.write('/cases/c-9').body ?? '{}')).toEqual({ suiteId: null });
  });

  it('exits 2 rather than sending an empty body that reports success', async () => {
    const out = recorder();
    const stub = api(scaffolding());

    // A `PATCH` of `{}` is legal by the contract — every field is optional — so it answers 200
    // having changed nothing, which is "no flags" reported as a save.
    expect(await mainWith(['case', 'edit', 'TC-42'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toContain('Nothing to change');
    expect(stub.sent.some((request) => request.method === 'PATCH')).toBe(false);
  });

  it('refuses a misspelled flag instead of ignoring it', async () => {
    const out = recorder();
    const stub = api(scaffolding());

    // The parser is strict since §139. Before that every undeclared flag parsed as `true` and
    // its argument became a positional, so a typo was a silent no-op on a command that had
    // already reported success.
    expect(await mainWith(['case', 'edit', 'TC-42', '--titel', 'x'], out, stub.fetch)).toBe(EXIT.usage);
    expect(stub.sent.some((request) => request.method === 'PATCH')).toBe(false);
  });

  /*
    THE SLICE'S FINDING. A whole-body edit's body came from an EARLIER read, so a version read
    here would assert a state that body was never based on — the server would accept it and the
    concurrent author's words would be gone. Last-write-wins arriving through the header that
    exists to prevent it, with every party's code looking correct.
  */
  it('refuses a --file edit that names no --lock-version, and makes no request', async () => {
    const out = recorder();
    const stub = api(scaffolding());
    const file = tempJson('case.json', { title: 'From a file' });

    expect(await mainWith(['case', 'edit', 'TC-42', '--file', file], out, stub.fetch)).toBe(EXIT.failed);
    expect(out.stderr).toMatch(/silently overwrites whoever saved in between/);
    expect(stub.sent.some((request) => request.method === 'PATCH')).toBe(false);
  });

  it('sends the version the caller names, without reading a fresher one', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: CASE } });
    const file = tempJson('case.json', { title: 'From a file' });

    expect(await mainWith(
      ['case', 'edit', 'TC-42', '--file', file, '--lock-version', '2'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    expect(stub.write('/cases/c-9').headers.get('If-Match')).toBe('2');
    // The detail read is not made at all: nothing in this command needs the case's current
    // state, and reading it would only invite somebody to use it.
    expect(stub.sent.filter((request) => request.path.endsWith('/cases/c-9')))
      .toHaveLength(1);
  });

  it('refuses a --lock-version that is not a number, rather than falling back to a fresh read', async () => {
    const out = recorder();
    const stub = api(scaffolding());
    const file = tempJson('case.json', { title: 'x' });

    expect(await mainWith(
      ['case', 'edit', 'TC-42', '--file', file, '--lock-version', 'latest'],
      out,
      stub.fetch,
    )).toBe(EXIT.usage);
  });
});

describe('case propose', () => {
  it('files a bare TestCaseInput by wrapping it, and carries an idempotency key', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /ai-proposals': { status: 201, body: PROPOSAL } });
    const file = tempJson('case.json', { title: 'Login works', priority: 'HIGH' });

    expect(await mainWith(['case', 'propose', '--file', file], out, stub.fetch)).toBe(EXIT.ok);

    const sent = stub.write('/ai-proposals');
    expect(sent.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(sent.body ?? '{}')).toEqual({
      testCase: { title: 'Login works', priority: 'HIGH' },
    });
  });

  it('takes a body that is already a proposal without wrapping it twice', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /ai-proposals': { status: 201, body: PROPOSAL } });
    const file = tempJson('proposal.json', {
      testCase: { title: 'Login works' },
      grounding: { steps: [] },
    });

    await mainWith(['case', 'propose', '--file', file], out, stub.fetch);

    expect(JSON.parse(stub.write('/ai-proposals').body ?? '{}')).toEqual({
      testCase: { title: 'Login works' },
      grounding: { steps: [] },
    });
  });

  it('sends no verdict when none was given — absent and UNVERIFIED are different claims', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /ai-proposals': { status: 201, body: PROPOSAL } });
    const file = tempJson('case.json', { title: 'x' });

    await mainWith(['case', 'propose', '--file', file], out, stub.fetch);

    expect(JSON.parse(stub.write('/ai-proposals').body ?? '{}')).not.toHaveProperty('verdict');
  });

  it('reuses the key the caller supplied, so a scripted retry is the same intent', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /ai-proposals': { status: 200, body: PROPOSAL } });
    const file = tempJson('case.json', { title: 'x' });

    await mainWith(['case', 'propose', '--file', file, '--idempotency-key', 'batch-7'], out, stub.fetch);

    expect(stub.write('/ai-proposals').headers.get('Idempotency-Key')).toBe('batch-7');
  });

  it('prints the verdict and its source together, never one alone', async () => {
    const out = recorder();
    const stub = api({
      ...scaffolding(),
      'POST /ai-proposals': {
        status: 201,
        body: { ...PROPOSAL, verdict: 'GROUNDED', verdictSource: 'EXTERNAL' },
      },
    });
    const file = tempJson('case.json', { title: 'x' });

    await mainWith(['case', 'propose', '--file', file, '--verdict', 'GROUNDED'], out, stub.fetch);

    // §138's rule, one client over: a verdict without its provenance reads as a finding rather
    // than as somebody else's critic's claim.
    expect(out.stdout).toContain('GROUNDED (EXTERNAL)');
  });

  it('exits 2 when no body was named', async () => {
    const out = recorder();
    const stub = api(scaffolding());

    expect(await mainWith(['case', 'propose'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toContain('--file');
  });
});

describe('suite writes', () => {
  it('resolves case keys to ids before sending the membership', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /suites': { status: 201, body: SUITE } });

    expect(await mainWith(
      ['suite', 'create', '--name', 'Smoke', '--case', 'TC-42', '--tag-rule', 'smoke'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    expect(JSON.parse(stub.write('/suites').body ?? '{}')).toEqual({
      name: 'Smoke',
      caseIds: ['c-9'],
      tagRules: ['smoke'],
    });
    expect(out.stdout).toContain('TS-7');
  });

  it('sends no If-Match and no key on a status transition', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PUT /suites/s-1/status': { status: 200, body: SUITE } });

    await mainWith(['suite', 'status', 'TS-7', '--status', 'IN_REVIEW'], out, stub.fetch);

    const sent = stub.write('/suites/s-1/status');
    // The contract's own reason: the request names an absolute target state rather than a delta
    // against something the client read, so there is no lost update for a precondition to guard.
    expect(sent.headers.get('If-Match')).toBeNull();
    expect(sent.headers.get('Idempotency-Key')).toBeNull();
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ status: 'IN_REVIEW' });
  });

  it('does not filter the ladder client-side — the server owns which move is legal', async () => {
    const out = recorder();
    const stub = api({
      ...scaffolding(),
      'PUT /suites/s-1/status': { status: 403, body: { detail: 'You do not have permission to do this.', status: 403 } },
    });

    // APPROVED costs `suite:approve`, which an Agent token does not hold. The command sends it
    // anyway: a client-side ladder would be a second copy of §6g's, and the person running this
    // CLI may hold a Test Lead token.
    expect(await mainWith(['suite', 'status', 'TS-7', '--status', 'APPROVED'], out, stub.fetch))
      .toBe(EXIT.failed);
    expect(stub.write('/suites/s-1/status').body).toContain('APPROVED');
  });
});

describe('doc writes', () => {
  /*
    The one command in this slice where forgetting the contract's rule destroys content rather
    than failing: `blocks` is the whole page in order, and a block OMITTED is DELETED. A retitle
    must therefore send no `blocks` key at all.
  */
  it('retitles without sending blocks, which would empty the page', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /doc-pages/d-1': { status: 200, body: PAGE } });

    await mainWith(['doc', 'edit', 'DOC-3', '--title', 'Payments spec v2'], out, stub.fetch);

    const body = JSON.parse(stub.write('/doc-pages/d-1').body ?? '{}') as Record<string, unknown>;
    expect(body).toEqual({ title: 'Payments spec v2' });
    expect(body).not.toHaveProperty('blocks');
  });

  it('links a case to a block by key, and defaults the relationship the contract defaults', async () => {
    const out = recorder();
    const stub = api({
      ...scaffolding(),
      'POST /blocks/b-1/links': {
        status: 201,
        body: { id: 'l-1', sourceType: 'TEST_CASE', sourceKey: 'TC-42', relationship: 'VERIFIES' },
      },
    });

    expect(await mainWith(
      ['doc', 'link', 'DOC-3', '--block', 'b-1', '--case', 'TC-42'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    expect(JSON.parse(stub.write('/blocks/b-1/links').body ?? '{}')).toEqual({
      sourceType: 'TEST_CASE',
      sourceId: 'c-9',
      relationship: 'VERIFIES',
    });
  });

  it('refuses a link that names both a case and a suite, or neither', async () => {
    const both = recorder();
    const stub = api(scaffolding());
    expect(await mainWith(
      ['doc', 'link', 'DOC-3', '--block', 'b-1', '--case', 'TC-42', '--suite', 'TS-7'],
      both,
      stub.fetch,
    )).toBe(EXIT.usage);

    const neither = recorder();
    expect(await mainWith(['doc', 'link', 'DOC-3', '--block', 'b-1'], neither, stub.fetch))
      .toBe(EXIT.usage);
  });

  it('prints something a jq pipeline can read for a 204', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'DELETE /links/l-1': { status: 204 } });

    expect(await mainWith(['doc', 'unlink', 'DOC-3', '--link', 'l-1', '--json'], out, stub.fetch))
      .toBe(EXIT.ok);
    expect(JSON.parse(out.stdout)).toEqual({ deleted: 'l-1' });
  });
});

describe('plan writes', () => {
  /*
    `TestPlanUpdateInput` requires `name`, alone among the edit shapes on this surface. So a
    `--description`-only edit is not expressible on the wire, and the command threads the current
    name through from the same read that gave it the version — same read, so the pair is
    consistent. Sending nothing would be a 400 about a field the user never mentioned.
  */
  it('carries the plan’s current name through an edit that does not mention it', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /plans/tp-1': { status: 200, body: PLAN } });

    expect(await mainWith(
      ['plan', 'edit', 'TP-1', '--description', 'Regression for 4.2'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    const sent = stub.write('/plans/tp-1');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({
      description: 'Regression for 4.2',
      name: 'Release 4.2',
    });
    expect(sent.headers.get('If-Match')).toBe('7');
  });

  it('resolves suite and case keys into the plan’s scope', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /plans': { status: 201, body: PLAN } });

    await mainWith(
      ['plan', 'create', '--name', 'Release 4.2', '--suite', 'TS-7', '--case', 'TC-42'],
      out,
      stub.fetch,
    );

    expect(JSON.parse(stub.write('/plans').body ?? '{}')).toEqual({
      name: 'Release 4.2',
      suiteIds: ['s-1'],
      caseIds: ['c-9'],
    });
  });
});

describe('result record', () => {
  it('finds the execution by case key and the status by name', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /executions/e-1': { status: 200, body: EXECUTION } });

    expect(await mainWith(
      ['result', 'record', 'TR-5', 'TC-42', '--status', 'passed'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    const sent = stub.write('/executions/e-1');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ statusId: 'st-1' });
    // The version comes from the execution's own detail, not from the run's list of summaries —
    // `TestExecutionSummary` carries no lockVersion at all.
    expect(sent.headers.get('If-Match')).toBe('1');
  });

  it('says what the project offers when the status name is not one of them', async () => {
    const out = recorder();
    const stub = api(scaffolding());

    expect(await mainWith(
      ['result', 'record', 'TR-5', 'TC-42', '--status', 'Green'],
      out,
      stub.fetch,
    )).toBe(EXIT.usage);
    expect(out.stderr).toContain('Passed, Failed');
  });

  it('refuses a case the run does not cover, rather than sending a guess', async () => {
    const out = recorder();
    const stub = api(scaffolding());

    expect(await mainWith(
      ['result', 'record', 'TR-5', 'TC-99', '--status', 'Passed'],
      out,
      stub.fetch,
    )).toBe(EXIT.usage);
    expect(out.stderr).toContain('is not in');
  });
});

describe('review comment', () => {
  it('reads the subject type off the key and joins the open review', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /comments': { status: 201, body: REVIEW } });

    expect(await mainWith(
      ['review', 'comment', 'TC-42', '--body', 'Step 3 does not match the spec.'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    const asked = stub.urls.find((url) => url.includes('/reviews?')) ?? '';
    expect(asked).toContain('subjectType=CASE');
    expect(asked).toContain('subjectId=c-9');
    expect(JSON.parse(stub.write('/comments').body ?? '{}')).toEqual({
      body: 'Step 3 does not match the spec.',
    });
  });

  /*
    A review is history once it closes: the endpoint answers 409, and re-submitting opens a NEW
    record rather than reopening the old one. So "the newest review" and "the one that can still
    be commented on" are different questions, and answering the first would produce a refusal the
    user cannot act on.
  */
  it('refuses a closed review here, rather than earning a 409 from the server', async () => {
    const out = recorder();
    const stub = api({
      ...scaffolding(),
      'GET /reviews': { status: 200, body: [{ ...REVIEW, state: 'APPROVED' }] },
    });

    expect(await mainWith(['review', 'comment', 'TC-42', '--body', 'x'], out, stub.fetch))
      .toBe(EXIT.usage);
    expect(out.stderr).toContain('has ended (APPROVED)');
    expect(stub.sent.some((request) => request.method === 'POST')).toBe(false);
  });

  it('refuses a key that names something reviews are not kept for', async () => {
    const out = recorder();
    const stub = api(scaffolding());

    expect(await mainWith(['review', 'comment', 'DOC-3', '--body', 'x'], out, stub.fetch))
      .toBe(EXIT.usage);
  });
});

/* ------------------------------------------------------- the computed check ---- */

describe('header conformance', () => {
  const writes = COMMANDS.filter((command) => command.write !== undefined);

  it('finds every write command in the registry', () => {
    expect(writes.length).toBeGreaterThan(0);
  });

  it.each(writes.map((command) => [command.name, command] as const))(
    '%s sends what its declaration implies',
    async (_name, command) => {
      const invocation = INVOCATIONS[command.name];
      if (!invocation) {
        throw new Error(
          `'${command.name}' declares a write and this test does not know how to invoke it. `
            + 'Add it to INVOCATIONS — the check exists so a new write command cannot ship '
            + 'without its headers being asserted.',
        );
      }

      const out = recorder();
      const stub = api({ ...scaffolding(), ...invocation.routes });
      const code = await mainWith(invocation.argv(), out, stub.fetch);
      expect(`${String(code)} ${out.stderr}`).toBe('0 ');

      const sent = stub.write(invocation.path);
      const key = sent.headers.get('Idempotency-Key');
      const ifMatch = sent.headers.get('If-Match');

      switch (command.write?.kind) {
        case 'create':
          expect(key, `${command.name} must carry Idempotency-Key`).toBeTruthy();
          break;
        case 'edit':
          expect(ifMatch, `${command.name} must carry If-Match`).toBeTruthy();
          break;
        case 'state':
        case 'delete':
          // Neither, and asserted rather than left unsaid: the contract records that these are
          // preconditioned on the state, so a header here would refuse a request over a version
          // the caller never claimed to have based anything on.
          expect([key, ifMatch]).toEqual([null, null]);
          break;
      }
    },
  );

  it('declares a permission for every write, since the 403 body names none', () => {
    for (const command of writes) {
      expect(command.write?.permission, command.name).toMatch(/^[a-z]+:[a-z]+$/);
    }
  });
});

/**
 * How to invoke each write command, keyed by name.
 *
 * A table, and it is the one part of this check that is hand-maintained — which is why the test
 * above **fails on an unknown command** rather than skipping it. A conformance check that
 * silently ignores what it does not recognise is a check on the table.
 */
interface Invocation {
  argv: () => string[];
  path: string;
  routes: Record<string, { status: number; body?: unknown }>;
}

const INVOCATIONS: Record<string, Invocation> = {
  'case edit': {
    argv: () => ['case', 'edit', 'TC-42', '--title', 'x'],
    path: '/cases/c-9',
    routes: { 'PATCH /cases/c-9': { status: 200, body: CASE } },
  },
  'case propose': {
    argv: () => ['case', 'propose', '--file', tempJson('case.json', { title: 'x' })],
    path: '/ai-proposals',
    routes: { 'POST /ai-proposals': { status: 201, body: PROPOSAL } },
  },
  'suite create': {
    argv: () => ['suite', 'create', '--name', 'Smoke'],
    path: '/suites',
    routes: { 'POST /suites': { status: 201, body: SUITE } },
  },
  'suite edit': {
    argv: () => ['suite', 'edit', 'TS-7', '--name', 'Smoke again'],
    path: '/suites/s-1',
    routes: { 'PATCH /suites/s-1': { status: 200, body: SUITE } },
  },
  'suite status': {
    argv: () => ['suite', 'status', 'TS-7', '--status', 'IN_REVIEW'],
    path: '/suites/s-1/status',
    routes: { 'PUT /suites/s-1/status': { status: 200, body: SUITE } },
  },
  'doc create': {
    argv: () => ['doc', 'create', '--title', 'Payments spec'],
    path: '/doc-pages',
    routes: { 'POST /doc-pages': { status: 201, body: PAGE } },
  },
  'doc edit': {
    argv: () => ['doc', 'edit', 'DOC-3', '--title', 'Payments spec v2'],
    path: '/doc-pages/d-1',
    routes: { 'PATCH /doc-pages/d-1': { status: 200, body: PAGE } },
  },
  'doc link': {
    argv: () => ['doc', 'link', 'DOC-3', '--block', 'b-1', '--case', 'TC-42'],
    path: '/blocks/b-1/links',
    routes: {
      'POST /blocks/b-1/links': {
        status: 201,
        body: { id: 'l-1', sourceType: 'TEST_CASE', sourceKey: 'TC-42', relationship: 'VERIFIES' },
      },
    },
  },
  'doc unlink': {
    argv: () => ['doc', 'unlink', 'DOC-3', '--link', 'l-1'],
    path: '/links/l-1',
    routes: { 'DELETE /links/l-1': { status: 204 } },
  },
  'plan create': {
    argv: () => ['plan', 'create', '--name', 'Release 4.2'],
    path: '/plans',
    routes: { 'POST /plans': { status: 201, body: PLAN } },
  },
  'plan edit': {
    argv: () => ['plan', 'edit', 'TP-1', '--name', 'Release 4.3'],
    path: '/plans/tp-1',
    routes: { 'PATCH /plans/tp-1': { status: 200, body: PLAN } },
  },
  'plan status': {
    argv: () => ['plan', 'status', 'TP-1', '--status', 'IN_REVIEW'],
    path: '/plans/tp-1/status',
    routes: { 'PUT /plans/tp-1/status': { status: 200, body: PLAN } },
  },
  'run create': {
    argv: () => ['run', 'create', '--name', 'Nightly'],
    path: '/runs',
    routes: { 'POST /runs': { status: 201, body: RUN } },
  },
  'run status': {
    argv: () => ['run', 'status', 'TR-5', '--status', 'COMPLETED'],
    path: '/runs/tr-5/status',
    routes: { 'PUT /runs/tr-5/status': { status: 200, body: RUN } },
  },
  'result record': {
    argv: () => ['result', 'record', 'TR-5', 'TC-42', '--status', 'Passed'],
    path: '/executions/e-1',
    routes: { 'PATCH /executions/e-1': { status: 200, body: EXECUTION } },
  },
  'review comment': {
    argv: () => ['review', 'comment', 'TC-42', '--body', 'Looks right.'],
    path: '/comments',
    routes: { 'POST /comments': { status: 201, body: REVIEW } },
  },
};
