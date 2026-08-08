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

/** The reads every command makes on its way to a write: project resolution and key lookup. */
function scaffolding(): Record<string, { status: number; body?: unknown }> {
  return {
    '/projects': { status: 200, body: [PROJECT] },
    'GET /cases': {
      status: 200,
      body: { page: 0, size: 1, total: 1, facets: [], items: [CASE] },
    },
    'GET /cases/c-9': { status: 200, body: CASE },
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
};
