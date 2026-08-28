/**
 * The write commands — what each one sends, and what it refuses to send.
 *
 * ~~`header conformance` is the surface spec's §8.3~~ → **it lives in `writeConformance.test.ts`
 * since §205**, which is the name four comments in this package had been giving it since §139 while
 * it sat here among forty behaviour tests. That file walks `COMMANDS`, drives each declared write
 * through a recording `fetch`, and asserts both halves of the rule — the header the `kind` implies,
 * and that the operation it wrote to declares one. The same shape as `RepositoryTransactionTest`
 * deriving its repository list from the live context, and for §108's reason: a check that reads a
 * maintained list is a check on the list.
 *
 * What stays here is the per-command behaviour: which fields a round-trip carries, what an empty
 * body is refused with, what a replay prints. The two are different questions and only one of them
 * is answerable over the registry.
 */
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  casePage,
  caseDetail,
  caseStatus,
  caseSummary,
  condition,
  docBlock,
  docPage,
  docPageSummary,
  execution,
  executionSummary,
  plan,
  proposal,
  review,
  run,
  runDetail,
  step,
  suite,
} from '../../client/src/test/fixtures.js';
import { CASE_SHAPE, fieldsFor, flagOf, spellableInArgv, type FieldSpec } from '@tcms/client';

import { EXIT } from './main.js';
import { PROJECT, api, mainWith, recorder } from './test/harness.js';

/** One flag at its catalogue-declared spelling, carrying a value the catalogue itself supplies. */
function argvFor(field: FieldSpec): string[] {
  const flag = `--${flagOf(field)}`;
  if (field.type === 'boolean') return [flag];
  return [flag, field.enum?.[0] ?? field.keyExample ?? field.example ?? 'x'];
}

/*
  A WHOLE case detail, not the four fields the assertions read.

  It was the four fields, and that is §143's second defect surviving in its own test: the edit
  arms send a delta, the stub answers 200 to whatever arrives, so a fixture missing everything
  the server requires agreed with a client that sent nothing. The arms now read this and send it
  back (`core/rewrite.ts`), which only proves anything if the fixture is shaped like the resource.
*/
const CASE = caseDetail({
  id: 'c-9',
  key: 'TC-42',
  keySeq: 42,
  title: 'Login works',
  suiteId: 's-1',
  suiteName: 'Smoke',
  lifecycleStatus: 'DRAFT',
  priority: 'HIGH',
  severity: 'MEDIUM',
  stepFormat: 'STRUCTURED',
  automationStatus: 'MANUAL',
  automationTags: ['@smoke'],
  description: 'The happy path.',
  objective: 'Prove a known user gets in.',
  expectedOutcome: 'The dashboard renders.',
  estimatedDurationSec: 90,
  classificationValueIds: ['cv-1'],
  tags: ['smoke'],
  steps: [
    step({
      id: 'st-1',
      action: 'Sign in',
      testData: 'ops@acme.test',
      expectedResult: 'The dashboard renders.',
    }),
  ],
  // A stored decline, so the round-trip assertions below are about a case that HAS one. With
  // both arrays empty, "the projection does not send declines" and "this case has none to send"
  // are the same green.
  declinedPreconditions: [
    condition({
      id: 'sc-2',
      conditionId: 'cond-2',
      title: 'Seed the ledger',
      inline: false,
      inherited: true,
      inheritedFrom: 'Billing',
    }),
  ],
  currentVersionNo: 3,
  lockVersion: 3,
});

/** The whole editable set as `caseInputFrom` projects it — what an unchanged round-trip sends. */
const CASE_ECHO = {
  title: 'Login works',
  suiteId: 's-1',
  description: 'The happy path.',
  objective: 'Prove a known user gets in.',
  expectedOutcome: 'The dashboard renders.',
  stepFormat: 'STRUCTURED',
  priority: 'HIGH',
  severity: 'MEDIUM',
  automationStatus: 'MANUAL',
  automationKey: null,
  notAutomatableReason: null,
  estimatedDurationSec: 90,
  classificationValueIds: ['cv-1'],
  tags: ['smoke'],
  steps: [
    {
      id: 'st-1',
      keyword: null,
      action: 'Sign in',
      testData: 'ops@acme.test',
      expectedResult: 'The dashboard renders.',
      docString: null,
      dataTable: null,
    },
  ],
};

/** The four the server requires unconditionally, plus the step a STRUCTURED case needs. */
const PROPOSABLE = {
  title: 'Login works',
  stepFormat: 'STRUCTURED',
  priority: 'HIGH',
  automationStatus: 'MANUAL',
  steps: [{ action: 'Sign in', expectedResult: 'The dashboard renders.' }],
};

const PROPOSAL = proposal({ createdAt: '2026-08-08T10:00:00Z' });

const SUITE = suite({
  key: 'TS-7',
  description: 'What we check on every build.',
  tagRules: ['smoke'],
  caseIds: ['c-9'],
  lockVersion: 2,
});

/** `suiteInputFrom(SUITE)` — the unchanged round-trip. */
const SUITE_ECHO = {
  name: 'Smoke',
  description: 'What we check on every build.',
  tagRules: ['smoke'],
  caseIds: ['c-9'],
};

const PAGE = docPage({
  id: 'd-1',
  key: 'DOC-3',
  title: 'Payments spec',
  slug: 'payments-spec',
  blocks: [docBlock({ id: 'b-1', text: 'Refunds settle in 3 days.' })],
  lockVersion: 5,
});

const PLAN = plan({
  description: 'The regression pass before GA.',
  milestoneName: 'GA',
  milestoneDate: '2026-09-01',
  // Reachable from no flag and no tool argument, which is what made them the sharpest half of
  // the plan defect: every `plan edit` emptied both and nobody could have avoided it.
  scopeText: 'Checkout, payments, refunds.',
  outOfScopeText: 'Anything behind the beta flag.',
  caseCount: 12,
  currentVersionNo: 2,
  lockVersion: 7,
});

/** `planInputFrom(PLAN)` — every scalar, and none of the lists. */
const PLAN_ECHO = {
  name: 'Release 4.2',
  description: 'The regression pass before GA.',
  milestoneName: 'GA',
  milestoneDate: '2026-09-01',
  scopeText: 'Checkout, payments, refunds.',
  outOfScopeText: 'Anything behind the beta flag.',
};

/*
  Two shapes, because the endpoints answer two. `GET /runs` and the two writes answer `TestRun`;
  only `GET /runs/{id}` answers the detail, and its `executions` are SUMMARIES — no steps, no
  evidence, no lock version. One constant served for all of them made every list look like the
  detail, which is the shape a caller would try to edit from.
*/
const RUN = run({ id: 'tr-5', key: 'TR-5', status: 'IN_PROGRESS' });

const RUN_DETAIL = runDetail({
  ...RUN,
  executions: [executionSummary({ id: 'e-1', caseId: 'c-9' })],
});

/*
  `PASSED` and `FAILED`, not ~~`PASS`/`FAIL`~~. `StatusKind` has never had the shorter spelling and
  this fixture carried it from §137 until §203 found it — the sharpest thing about it is that
  nothing downstream would have noticed either: `reads.ts` prints `entry.kind` verbatim, so the
  column would have read `PASS` in a test and `PASSED` in the field.
*/
const PASSED = caseStatus({ id: 'st-1', name: 'Passed', kind: 'PASSED', color: '#0a0' });
const STATUSES = [
  PASSED,
  caseStatus({ id: 'st-2', name: 'Failed', kind: 'FAILED', color: '#a00', countsAsPass: false }),
];

const EXECUTION = execution({ id: 'e-1', caseId: 'c-9', status: PASSED });

const REVIEW = review({ subjectId: 'c-9', subjectVersionNo: 3 });

/** The reads every command makes on its way to a write: project resolution and key lookup. */
function scaffolding(): Record<string, { status: number; body?: unknown }> {
  return {
    '/projects': { status: 200, body: [PROJECT] },
    'GET /cases': {
      status: 200,
      body: casePage([caseSummary({ id: 'c-9', key: 'TC-42' })], { size: 1 }),
    },
    'GET /cases/c-9': { status: 200, body: CASE },
    'GET /suites': { status: 200, body: [SUITE] },
    'GET /suites/s-1': { status: 200, body: SUITE },
    // The LIST answers `DocPageSummary`, which carries neither blocks nor a lock version — the
    // detail is a different shape and serving it here made the list look richer than it is.
    'GET /doc-pages': { status: 200, body: [docPageSummary({ id: 'd-1', key: 'DOC-3', title: 'Payments spec', slug: 'payments-spec' })] },
    'GET /doc-pages/d-1': { status: 200, body: PAGE },
    'GET /plans': { status: 200, body: [PLAN] },
    'GET /plans/tp-1': { status: 200, body: PLAN },
    'GET /runs': { status: 200, body: [RUN] },
    'GET /runs/tr-5': { status: 200, body: RUN_DETAIL },
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
  /*
    `format.test.ts` pins this command's FLAGS to the catalogue by comparing the spread to itself
    — an assertion that can never fail on its own. This is the half that was missing, and the door §253 cut into
    the defect it was closing: the **body** is still read back by hand, one named key at a time, so
    a field added to `CASE_FIELDS` produces a `--flag` that `parseArgs` accepts in strict mode and
    a builder that silently ignores it. The command then reports success having sent nothing for
    it, and `requireSomethingToDo` does not notice whenever any other flag was also given.

    That is §137/§139's silently-dropped-flag defect exactly. So this drives the command with every
    flag the catalogue declares and asserts each one reaches the wire.
  */
  it('sends every catalogue field it accepts a flag for', async () => {
    const fields = fieldsFor(CASE_SHAPE, 'edit').filter(spellableInArgv);
    expect(fields.length).toBeGreaterThanOrEqual(8);

    const out = recorder();
    const stub = api({
      ...scaffolding(),
      'GET /suites': { status: 200, body: [SUITE] },
      'PATCH /cases/c-9': { status: 200, body: CASE },
    });

    const argv = ['case', 'edit', 'TC-42', ...fields.flatMap(argvFor)];
    expect(await mainWith(argv, out, stub.fetch)).toBe(EXIT.ok);

    const body = JSON.parse(stub.write('/cases/c-9').body ?? '{}') as Record<string, unknown>;
    const missing = fields
      .map((field) => field.contract ?? field.name)
      .filter((key) => !(key in body));

    expect(missing, 'case edit accepts a flag for these and sends nothing').toEqual([]);
  });

  it('reads the case for its lockVersion and sends it as If-Match', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: { ...CASE, lockVersion: 4 } } });

    expect(await mainWith(['case', 'edit', 'TC-42', '--title', 'Login still works'], out, stub.fetch))
      .toBe(EXIT.ok);

    const sent = stub.write('/cases/c-9');
    expect(sent.method).toBe('PATCH');
    expect(sent.headers.get('If-Match')).toBe('3');
    /*
      The WHOLE editable set with one field changed, not `{ title }`. This `PATCH` is partial by
      verb only: `stepFormat`, `priority` and `automationStatus` absent is a 400 about fields the
      user never mentioned, and every nullable scalar absent is *cleared*. §143 drove it against
      a real server — `tcms case edit TC-1 --title x` had never worked once.
    */
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ ...CASE_ECHO, title: 'Login still works' });
    // `ownerId` and `automationTags` are served by the GET and REFUSED by the PATCH, so the
    // round-trip has to drop them. Echoing them back is the one way this fix could be worse
    // than the delta it replaces.
    expect(JSON.parse(sent.body ?? '{}')).not.toHaveProperty('ownerId');
    expect(JSON.parse(sent.body ?? '{}')).not.toHaveProperty('automationTags');
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
      'GET /suites': { status: 200, body: [{ id: 's-1', key: 'TS-7', name: 'Smoke', caseCount: 1, lifecycleStatus: 'DRAFT', tagRules: [], caseIds: [], preconditions: [], postconditions: [], lockVersion: 1 }] },
      'PATCH /cases/c-9': { status: 200, body: CASE },
    });

    await mainWith(
      ['case', 'edit', 'TC-42', '--description', '', '--suite', 'TS-7', '--tag', 'smoke'],
      out,
      stub.fetch,
    );

    expect(JSON.parse(stub.write('/cases/c-9').body ?? '{}')).toEqual({
      ...CASE_ECHO,
      description: null,
      suiteId: 's-1',
      tags: ['smoke'],
    });
  });

  /*
    §189. `case_condition_optout` got its endpoint in §187 and neither machine surface could send
    to it: `--decline` is the flag, and the round-trip is where it could have gone wrong, because
    the base body this delta lands on is composed from the same read that served the declines.
  */
  it('declines the attachments named, and only those', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: CASE } });

    await mainWith(['case', 'edit', 'TC-42', '--decline', 'sc-2', '--decline', 'sc-3'], out, stub.fetch);

    // A COMPLETE set, like `tags`: these two and no others, not "add these two".
    expect(JSON.parse(stub.write('/cases/c-9').body ?? '{}')).toEqual({
      ...CASE_ECHO,
      declinedConditionIds: ['sc-2', 'sc-3'],
    });
  });

  it('re-imposes every declined condition on --decline ""', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: CASE } });

    await mainWith(['case', 'edit', 'TC-42', '--decline', ''], out, stub.fetch);

    // The clearing form every complete-set flag on this surface shares. An empty array is a
    // request; a shell cannot type one, and a flag with no clearing form can never empty a field.
    expect(JSON.parse(stub.write('/cases/c-9').body ?? '{}')).toEqual({
      ...CASE_ECHO,
      declinedConditionIds: [],
    });
  });

  /*
    The projection stays out of it, which is `core/rewrite.ts`'s decision rather than an
    oversight: a list absent leaves it alone, so an edit that says nothing about declines asserts
    nothing about them. Echoing the read's ids back would be the same VALUE and a different
    claim — a `--title` edit stating the complete set of the author's opt-outs.
  */
  it('says nothing about declines when no flag named them, on a case that has one', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: CASE } });

    await mainWith(['case', 'edit', 'TC-42', '--title', 'Login still works'], out, stub.fetch);

    expect(JSON.parse(stub.write('/cases/c-9').body ?? '{}')).not.toHaveProperty('declinedConditionIds');
  });

  it('sends an explicit null for --unfile, which is not the same as omitting the suite', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: CASE } });

    await mainWith(['case', 'edit', 'TC-42', '--unfile'], out, stub.fetch);

    // The null is the point and it survives the merge: the base carries `suiteId: 's-1'` and
    // the delta lays an explicit null over it. A merge that dropped nulls would file the case
    // back where it was and report success.
    expect(JSON.parse(stub.write('/cases/c-9').body ?? '{}')).toEqual({ ...CASE_ECHO, suiteId: null });
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
    // A complete input, because that is what a person's file is: the `--file` arm sends it
    // verbatim, so a four-field literal here would be a payload the server refuses.
    const file = tempJson('case.json', { ...PROPOSABLE, title: 'From a file' });

    // `usage`, not the old catch-all `1`: the command was driven with an argument it cannot use,
    // and no retry of it fixes that. See `EXIT`.
    expect(await mainWith(['case', 'edit', 'TC-42', '--file', file], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toMatch(/silently overwrites whoever saved in between/);
    expect(stub.sent.some((request) => request.method === 'PATCH')).toBe(false);
  });

  it('sends the version the caller names, without reading a fresher one', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /cases/c-9': { status: 200, body: CASE } });
    // A complete input, because that is what a person's file is: the `--file` arm sends it
    // verbatim, so a four-field literal here would be a payload the server refuses.
    const file = tempJson('case.json', { ...PROPOSABLE, title: 'From a file' });

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
    const file = tempJson('case.json', PROPOSABLE);

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
    const file = tempJson('case.json', PROPOSABLE);

    expect(await mainWith(['case', 'propose', '--file', file], out, stub.fetch)).toBe(EXIT.ok);

    const sent = stub.write('/ai-proposals');
    expect(sent.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/);
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ testCase: PROPOSABLE });
  });

  it('takes a body that is already a proposal without wrapping it twice', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /ai-proposals': { status: 201, body: PROPOSAL } });
    const file = tempJson('proposal.json', {
      testCase: PROPOSABLE,
      grounding: { steps: [] },
    });

    await mainWith(['case', 'propose', '--file', file], out, stub.fetch);

    expect(JSON.parse(stub.write('/ai-proposals').body ?? '{}')).toEqual({
      testCase: PROPOSABLE,
      grounding: { steps: [] },
    });
  });

  it('sends no verdict when none was given — absent and UNVERIFIED are different claims', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /ai-proposals': { status: 201, body: PROPOSAL } });
    const file = tempJson('case.json', PROPOSABLE);

    await mainWith(['case', 'propose', '--file', file], out, stub.fetch);

    expect(JSON.parse(stub.write('/ai-proposals').body ?? '{}')).not.toHaveProperty('verdict');
  });

  it('reuses the key the caller supplied, so a scripted retry is the same intent', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /ai-proposals': { status: 200, body: PROPOSAL } });
    const file = tempJson('case.json', PROPOSABLE);

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
    const file = tempJson('case.json', PROPOSABLE);

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

  /*
    Filing it would WORK — 201, PENDING — and the refusal would land weeks later on a reviewer
    who cannot edit the proposal to fix it and whose only move is Reject. So the author is told
    while they are still holding the thing. §143 drove exactly that path end to end.
  */
  it('refuses a case the accept could never take, and makes no request', async () => {
    const out = recorder();
    const stub = api(scaffolding());
    const file = tempJson('case.json', { title: 'Login works' });

    expect(await mainWith(['case', 'propose', '--file', file], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toContain('stepFormat');
    expect(out.stderr).toContain('automationStatus');
    expect(stub.sent.some((request) => request.method === 'POST')).toBe(false);
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

  /*
    §204. `POST …/suites` declares 200 **and** 201: the 201 created it, the 200 says the
    `Idempotency-Key` had already created it and — the contract's own words — nothing was created by
    this request. Both printed `Created  TS-7 — Smoke`, because `@tcms/client` returned `result.data`
    and dropped the status, so no consumer in this tree could tell them apart. The population that
    meets it is `--idempotency-key`, which is the scripted retry the header exists for.

    Asserted as a pair, in one test, because the interesting claim is that the two differ — a test
    that only pinned the replay would pass just as well if every create had started saying Replayed.
  */
  it('says Replayed rather than Created when the key had already created it', async () => {
    const replay = recorder();
    await mainWith(
      ['suite', 'create', '--name', 'Smoke', '--idempotency-key', 'batch-7'],
      replay,
      api({ ...scaffolding(), 'POST /suites': { status: 200, body: SUITE } }).fetch,
    );

    const created = recorder();
    await mainWith(
      ['suite', 'create', '--name', 'Smoke', '--idempotency-key', 'batch-7'],
      created,
      api({ ...scaffolding(), 'POST /suites': { status: 201, body: SUITE } }).fetch,
    );

    expect(replay.stdout).toContain('Replayed');
    expect(replay.stdout).not.toContain('Created');
    expect(created.stdout).toContain('Created');
    expect(created.stdout).not.toContain('Replayed');
    // The key is what a caller acts on and it is the same either way, which is the whole point of
    // the header — the word is the only thing that says which request allocated it.
    expect(replay.stdout).toContain('TS-7');
  });

  /*
    `--json` prints the resource, and the resource is byte-identical on a replay. That is not an
    oversight to fix later: a machine reader consumes ids, and inventing a `replayed` key in the JSON
    would be this client adding a field to a contract-shaped body (§165's shape from the wrong side).
  */
  it('leaves the --json form alone, because the resource is the same resource', async () => {
    const out = recorder();
    await mainWith(
      ['suite', 'create', '--name', 'Smoke', '--idempotency-key', 'batch-7', '--json'],
      out,
      api({ ...scaffolding(), 'POST /suites': { status: 200, body: SUITE } }).fetch,
    );

    expect(JSON.parse(out.stdout)).toEqual(SUITE);
  });

  /*
    The case arm's defect, one field wide. `SuiteInput` requires `name`, so editing only the tag
    rules was a 400 about a field nobody mentioned; and `description` absent is *cleared*, so a
    rename emptied it. Both invisible to a stub, which answers 200 to whatever arrives.
  */
  it('sends the whole suite with the change applied, not the flags alone', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /suites/s-1': { status: 200, body: SUITE } });

    await mainWith(['suite', 'edit', 'TS-7', '--tag-rule', 'nightly'], out, stub.fetch);

    const sent = stub.write('/suites/s-1');
    expect(sent.headers.get('If-Match')).toBe('2');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({ ...SUITE_ECHO, tagRules: ['nightly'] });
  });

  /*
    §189. §186 gave a suite its two condition arrays and §187 gave a case the decline; neither
    was reachable from this surface. The suite half takes LIBRARY ids — the write's shape is
    `CaseConditionInput`, which carries `conditionId` and nothing else, so the projection cannot
    produce it from the `CaseConditionRef` the read serves and the caller's flag is the only
    source (`core/rewrite.ts`).
  */
  it('attaches conditions by library id, wrapped as the input the write takes', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /suites/s-1': { status: 200, body: SUITE } });

    await mainWith(
      ['suite', 'edit', 'TS-7', '--precondition', 'cond-1', '--postcondition', 'cond-9'],
      out,
      stub.fetch,
    );

    expect(JSON.parse(stub.write('/suites/s-1').body ?? '{}')).toEqual({
      ...SUITE_ECHO,
      // Order is the content and is assigned from the array — never sent, because a
      // client-supplied position can duplicate or skip and no constraint would refuse it.
      preconditions: [{ conditionId: 'cond-1' }],
      postconditions: [{ conditionId: 'cond-9' }],
    });
  });

  it('detaches every condition on --precondition ""', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /suites/s-1': { status: 200, body: SUITE } });

    await mainWith(['suite', 'edit', 'TS-7', '--precondition', ''], out, stub.fetch);

    const sent = JSON.parse(stub.write('/suites/s-1').body ?? '{}');
    expect(sent.preconditions).toEqual([]);
    // The other list is untouched, not emptied alongside it: two flags, two fields, and a
    // suite's cleanup rules are not collateral of a change to its setup ones.
    expect(sent).not.toHaveProperty('postconditions');
  });

  it('attaches them on the create, so no case is filed into a suite whose setup is half applied', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'POST /suites': { status: 201, body: SUITE } });

    await mainWith(
      ['suite', 'create', '--name', 'Smoke', '--precondition', 'cond-1'],
      out,
      stub.fetch,
    );

    expect(JSON.parse(stub.write('/suites').body ?? '{}')).toEqual({
      name: 'Smoke',
      preconditions: [{ conditionId: 'cond-1' }],
    });
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
    // `denied`, which is the whole point of the table: a CI step can now tell this from a 429 and
    // stop rather than retrying what will never succeed.
    expect(await mainWith(['suite', 'status', 'TS-7', '--status', 'APPROVED'], out, stub.fetch))
      .toBe(EXIT.denied);
    expect(stub.write('/suites/s-1/status').body).toContain('APPROVED');
  });
});

describe('plan edit', () => {
  it('sends no list it was not given, because absent leaves a list alone', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /plans/tp-1': { status: 200, body: PLAN } });

    await mainWith(['plan', 'edit', 'TP-1', '--name', 'Release 4.3'], out, stub.fetch);

    const body = JSON.parse(stub.write('/plans/tp-1').body ?? '{}') as Record<string, unknown>;
    expect(body['name']).toBe('Release 4.3');
    for (const list of ['suiteIds', 'caseIds', 'tags', 'sections', 'entryCriteria', 'exitCriteria']) {
      expect(body).not.toHaveProperty(list);
    }
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
        body: {
          id: 'l-1',
          sourceType: 'TEST_CASE',
          sourceId: 'c-9',
          sourceKey: 'TC-42',
          sourceTitle: 'Login works',
          relationship: 'VERIFIES',
        },
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

    // `--yes`: this is the surface's one delete arm and it now asks first. Without a terminal to
    // ask at the command refuses rather than proceeding, which is the MCP gate's rule brought over.
    expect(await mainWith(['doc', 'unlink', 'DOC-3', '--link', 'l-1', '--json', '--yes'], out, stub.fetch))
      .toBe(EXIT.ok);
    expect(JSON.parse(out.stdout)).toEqual({ deleted: 'l-1' });
  });
});

describe('plan writes', () => {
  /*
    ~~`TestPlanUpdateInput` requires `name`, alone among the edit shapes on this surface~~ → it is
    not alone, and threading the name alone was not enough. `PlanEdit.validateUpdate` writes every
    SCALAR straight through and resolves only the LISTS against what is stored, so this edit used
    to empty the milestone and both prose fields while correctly preserving the name. The command
    now sends every scalar back, from the same read that gave it the version.
  */
  it('carries every scalar through an edit that does not mention them', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /plans/tp-1': { status: 200, body: PLAN } });

    expect(await mainWith(
      ['plan', 'edit', 'TP-1', '--description', 'Regression for 4.2'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    const sent = stub.write('/plans/tp-1');
    expect(JSON.parse(sent.body ?? '{}')).toEqual({
      ...PLAN_ECHO,
      description: 'Regression for 4.2',
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

  it('sends --duration-ms as given, and omits the field when the flag is absent', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /executions/e-1': { status: 200, body: EXECUTION } });

    expect(await mainWith(
      ['result', 'record', 'TR-5', 'TC-42', '--status', 'passed', '--duration-ms', '4500'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    expect(JSON.parse(stub.write('/executions/e-1').body ?? '{}'))
      .toEqual({ statusId: 'st-1', durationMs: 4500 });
  });

  it("clears the duration with --duration-ms '', the shape every nullable flag here uses", async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /executions/e-1': { status: 200, body: EXECUTION } });

    expect(await mainWith(
      ['result', 'record', 'TR-5', 'TC-42', '--status', 'passed', '--duration-ms', ''],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    // null, not an absent key: a shell cannot type `null`, and absent means "leave it alone".
    expect(JSON.parse(stub.write('/executions/e-1').body ?? '{}'))
      .toEqual({ statusId: 'st-1', durationMs: null });
  });

  it('refuses a duration that is not a whole number of milliseconds', async () => {
    const out = recorder();
    const stub = api(scaffolding());

    expect(await mainWith(
      ['result', 'record', 'TR-5', 'TC-42', '--status', 'passed', '--duration-ms', '-1'],
      out,
      stub.fetch,
    )).toBe(EXIT.usage);
    expect(out.stderr).toContain('--duration-ms');
  });

  it('sends a zero duration rather than dropping it as falsy', async () => {
    const out = recorder();
    const stub = api({ ...scaffolding(), 'PATCH /executions/e-1': { status: 200, body: EXECUTION } });

    expect(await mainWith(
      ['result', 'record', 'TR-5', 'TC-42', '--status', 'passed', '--duration-ms', '0'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    // `present()` drops undefined and must not drop 0 — "it was instant" is not "not timed".
    expect(JSON.parse(stub.write('/executions/e-1').body ?? '{}'))
      .toEqual({ statusId: 'st-1', durationMs: 0 });
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
