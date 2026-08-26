/**
 * `main()` against a fake sink and a fake `fetch` — see `test/harness.ts` for why that is the
 * seam. This file covers dispatch, argument handling and the reads; the writes are next door in
 * `writes.test.ts`.
 */
import { describe, expect, it } from 'vitest';

import { caseDetail, casePage, caseSummary, condition, currentUser, permissions, suite } from '../../client/src/test/fixtures.js';
import { EXIT, main } from './main.js';
import { PROJECT, api, env, mainWith, recorder } from './test/harness.js';

/**
 * A case detail with all four condition arrays, which is what the contract requires of one.
 *
 * `sc-1` and `sc-2` are `suite_condition` **attachment** ids — what `--decline` takes — and
 * `cond-1`/`cond-2` the library entries behind them. The two are different ids on purpose: the
 * same library entry attached at two suites is two attachments, and declining one must leave the
 * other in force.
 */
const CASE_DETAIL = caseDetail({
  id: 'c-9',
  key: 'TC-42',
  title: 'Login works',
  lifecycleStatus: 'APPROVED',
  preconditions: [
    condition({
      id: 'sc-1',
      conditionId: 'cond-1',
      title: 'Log in',
      body: ['Sign in as ops@acme.test'],
      inline: false,
      inherited: true,
      inheritedFrom: 'Billing',
    }),
  ],
  declinedPreconditions: [
    condition({
      id: 'sc-2',
      position: 2,
      conditionId: 'cond-2',
      title: 'Seed the ledger',
      body: ['Insert three settled invoices'],
      inline: false,
      inherited: true,
      inheritedFrom: 'Billing',
    }),
  ],
  lockVersion: 3,
});

describe('argument handling', () => {
  it('prints usage and exits 2 when given nothing', async () => {
    const out = recorder();
    expect(await main([], out, env)).toBe(EXIT.usage);
    expect(out.stdout).toContain('Usage: tcms <noun> <verb>');
  });

  it('prints usage and exits 0 for an explicit help', async () => {
    const out = recorder();
    expect(await main(['help'], out, env)).toBe(EXIT.ok);
  });

  it('exits 2 on an unknown command, and says so on stderr', async () => {
    const out = recorder();
    expect(await main(['case', 'invent'], out, env)).toBe(EXIT.usage);
    expect(out.stderr).toContain('Unknown command');
    // stdout stays clean, so `tcms ... --json | jq` never receives a diagnostic.
    expect(out.stdout).toBe('');
  });

  // The distinction scripts depend on: a mistyped flag and a refused request need different
  // reactions, and a pipeline that cannot tell them apart retries the one that never succeeds.
  it('exits 2 for a bad --timeout, not 1', async () => {
    const out = recorder();
    expect(await main(['project', 'list', '--timeout', 'soon'], out, env)).toBe(EXIT.usage);
  });

  it('refuses to run without an instance or a token, before making a request', async () => {
    const out = recorder();
    // `EXIT.usage`, not `EXIT.failed`: a missing `TCMS_URL` is the caller's own configuration and
    // no retry fixes it. Every one of these used to be `1`, which is the collapse the ten-code
    // table exists to undo — see `EXIT`.
    expect(await main(['project', 'list'], out, { TCMS_TOKEN: 'x' })).toBe(EXIT.usage);
    expect(out.stderr).toContain('TCMS_URL');

    const second = recorder();
    expect(await main(['project', 'list'], second, { TCMS_URL: env.TCMS_URL })).toBe(EXIT.usage);
    expect(second.stderr).toContain('TCMS_TOKEN');
  });
});

describe('failures', () => {
  it('prints the server’s own detail and the field errors under it', async () => {
    const out = recorder();
    const stub = api({
      '/projects': {
        status: 400,
        body: { title: 'Validation failed', status: 400, detail: 'That is not valid.', errors: { key: ['is required'] } },
      },
    });

    const code = await mainWith(['project', 'list'], out, stub.fetch);

    // A payload the server rejected is a usage mistake found one hop later than a mistyped flag.
    expect(code).toBe(EXIT.usage);
    expect(out.stderr).toContain('That is not valid.');
    expect(out.stderr).toContain('key: is required');
  });

  it('says nothing about permissions on a 404', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 404, body: { detail: 'Not found.', status: 404 } } });

    expect(await mainWith(['project', 'list'], out, stub.fetch)).toBe(EXIT.notFound);
    // The copy is the core's now, so this asserts §4.5's boundary on the CLI as well as the MCP:
    // a 404 must not distinguish "exists but you cannot see it" from "does not exist".
    expect(out.stderr).not.toMatch(/permission|role|Viewer|Test Lead/i);
  });
});

describe('whoami', () => {
  /*
    §137 shipped this command with **no test at all**, and it printed `[object Object]` once per
    permission — `GET /me/permissions` has answered `{key, qualifier}` pairs since §65, not bare
    keys, and `[...permissions].sort().join('  ')` stringifies each object. The command exited 0.
    §140 found it while building the MCP server's capability summary against the same endpoint.
  */
  it('prints permission keys, with the qualifier when it narrows them', async () => {
    const out = recorder();
    const stub = api({
      '/me': { status: 200, body: currentUser() },
      '/projects': { status: 200, body: [PROJECT] },
      '/me/permissions': {
        status: 200,
        body: permissions(['case:read', 'case:delete'], { 'case:delete': 'OWN' }, null),
      },
    });

    expect(await mainWith(['whoami'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).not.toContain('[object Object]');
    expect(out.stdout).toContain('case:read');
    // The qualifier is the difference between "may I change this" and "may I change this one",
    // which is the question the command exists to answer.
    expect(out.stdout).toContain('case:delete (OWN)');
  });
});

describe('reads', () => {
  it('lists projects', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['project', 'list'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('OLB');
    expect(out.stdout).toContain('Online Banking');
  });

  it('prints the response verbatim under --json', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['project', 'list', '--json'], out, stub.fetch)).toBe(EXIT.ok);
    // Verbatim: the parsed output is the API's array, not a reshaping of it, because a `--json`
    // that reshaped would be a second undocumented schema for `jq` scripts to depend on.
    expect(JSON.parse(out.stdout)).toEqual([PROJECT]);
  });

  it('infers the project when the token can see exactly one', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/suites': { status: 200, body: [suite({ key: 'TS-1', lifecycleStatus: 'APPROVED' })] },
    });

    expect(await mainWith(['suite', 'list'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('TS-1');
    expect(stub.urls.some((url) => url.includes('/projects/p-1/suites'))).toBe(true);
  });

  it('asks for --project when several are visible', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT, { ...PROJECT, id: 'p-2', key: 'PAY' }] },
    });

    expect(await mainWith(['suite', 'list'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toContain('--project');
  });

  it('resolves a display key before reading the case', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/cases': { status: 200, body: casePage([caseSummary({ id: 'c-9', key: 'TC-42' })], { size: 1 }) },
      '/cases/c-9': { status: 200, body: CASE_DETAIL },
    });

    expect(await mainWith(['case', 'show', 'TC-42'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('Login works');
    // The lock version is printed because every edit needs it — omitting If-Match is
    // last-write-wins, which is the worst thing this surface can do silently.
    expect(out.stdout).toContain('Lock version: 3');
  });

  /*
    §189. The ids are the reason this table exists: `case edit --decline` takes `suite_condition`
    ATTACHMENT ids, and until this command printed them the only way to learn one at a terminal
    was `--json`. A flag whose argument the surface cannot tell you is a flag nobody can use.

    The fixture is the whole resource for the reason `writes.test.ts` states over its own: this
    stub used to carry six fields, none of the four condition arrays among them, and a partial
    fixture agrees with a client that reads nothing.
  */
  it('prints each condition with its attachment id, and says which are declined', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/cases': { status: 200, body: casePage([caseSummary({ id: 'c-9', key: 'TC-42' })], { size: 1 }) },
      '/cases/c-9': { status: 200, body: CASE_DETAIL },
    });

    expect(await mainWith(['case', 'show', 'TC-42'], out, stub.fetch)).toBe(EXIT.ok);

    expect(out.stdout).toContain('sc-1');
    expect(out.stdout).toContain('Log in');
    // Declined entries are printed BESIDE the live ones, never merged into them: the server
    // serves two arrays because a declined condition is not part of what the case does.
    expect(out.stdout).toMatch(/sc-2.*declined/);
    expect(out.stdout).toMatch(/sc-1.*in force/);
    // Where an inherited rule came from is the context for refusing it.
    expect(out.stdout).toContain('Billing');
  });

  /*
    §189's other half. `suite edit --precondition` takes a LIBRARY id, and `case edit --decline`
    takes an ATTACHMENT id; the two are one column apart in this table and neither works in the
    other's flag. Printing only one of them makes one of the two writes unusable.
  */
  it('prints a suite’s conditions with the library id first and the attachment id beside it', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/suites': { status: 200, body: [suite({ key: 'TS-7', caseCount: 2, lockVersion: 4 })] },
      '/suites/s-1': {
        status: 200,
        body: suite({
          key: 'TS-7',
          description: 'What we check on every build.',
          caseCount: 2,
          tagRules: ['smoke'],
          preconditions: [condition({ id: 'sc-1', conditionId: 'cond-1', title: 'Log in', inline: false })],
          lockVersion: 4,
        }),
      },
    });

    expect(await mainWith(['suite', 'show', 'TS-7'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toMatch(/cond-1.*PRE.*sc-1/);
    expect(out.stdout).toContain('Lock version: 4');
  });

  it('reads the library and passes both filters through', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/conditions': { status: 200, body: [{ id: 'cond-1', title: 'Log in', type: 'PRE', body: [], usageCount: 4, suiteUsageCount: 1, lockVersion: 1 }] },
    });

    expect(await mainWith(['library', 'list', '--q', 'log', '--type', 'PRE'], out, stub.fetch)).toBe(EXIT.ok);

    // The id is the first column because it is what a write takes; the title is how a person
    // finds the row and the id is what they then have to type.
    expect(out.stdout).toContain('cond-1');
    const asked = stub.urls.find((url) => url.includes('/conditions')) ?? '';
    expect(asked).toContain('q=log');
    expect(asked).toContain('type=PRE');
    // ONE endpoint since §239. This command read `/shared-steps` too until the shared-step
    // library was retired, and the assertion that used to sit here — that `type`, a
    // conditions-only filter, did not leak onto that second read — went with it.
    expect(stub.urls.filter((url) => url.includes('/shared-steps'))).toEqual([]);
  });

  /*
    §139's regression, and it was live from §137 until then: an option a command had not declared
    was parsed as `true` and its value pushed into the positionals, so every string filter on
    every read command was dropped. `case list --q login` answered the whole project's cases,
    with a 200, a full table and no diagnostic anywhere.
  */
  it('sends the filter values, rather than parsing them as booleans', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/cases': { status: 200, body: casePage([], { size: 25 }) },
    });

    expect(await mainWith(
      ['case', 'list', '--q', 'login', '--tag', 'smoke', '--tag', 'slow', '--size', '5'],
      out,
      stub.fetch,
    )).toBe(EXIT.ok);

    const asked = stub.urls.find((url) => url.includes('/cases')) ?? '';
    expect(asked).toContain('q=login');
    expect(asked).toContain('tag=smoke');
    expect(asked).toContain('tag=slow');
    expect(asked).toContain('size=5');
  });

  it('refuses a key of the wrong kind without making a request', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['case', 'show', 'TS-7'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toMatch(/is a suite key, but a case was expected/);
    expect(stub.urls.filter((url) => url.includes('/cases'))).toEqual([]);
  });

  it('exits 2 when a show command is given no argument', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 200, body: [PROJECT] } });

    expect(await mainWith(['case', 'show'], out, stub.fetch)).toBe(EXIT.usage);
    expect(out.stderr).toContain('tcms case show TC-42');
  });
});
