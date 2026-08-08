/**
 * `main()` against a fake sink and a fake `fetch` — see `test/harness.ts` for why that is the
 * seam. This file covers dispatch, argument handling and the reads; the writes are next door in
 * `writes.test.ts`.
 */
import { describe, expect, it } from 'vitest';

import { EXIT, main } from './main.js';
import { PROJECT, api, env, mainWith, recorder } from './test/harness.js';

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
    expect(await main(['project', 'list'], out, { TCMS_TOKEN: 'x' })).toBe(EXIT.failed);
    expect(out.stderr).toContain('TCMS_URL');

    const second = recorder();
    expect(await main(['project', 'list'], second, { TCMS_URL: env.TCMS_URL })).toBe(EXIT.failed);
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

    expect(code).toBe(EXIT.failed);
    expect(out.stderr).toContain('That is not valid.');
    expect(out.stderr).toContain('key: is required');
  });

  it('says nothing about permissions on a 404', async () => {
    const out = recorder();
    const stub = api({ '/projects': { status: 404, body: { detail: 'Not found.', status: 404 } } });

    expect(await mainWith(['project', 'list'], out, stub.fetch)).toBe(EXIT.failed);
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
      '/me': { status: 200, body: { id: 'u-1', email: 'ana@acme.test', displayName: 'Ana', isSystemAdmin: false, status: 'ACTIVE' } },
      '/projects': { status: 200, body: [PROJECT] },
      '/me/permissions': {
        status: 200,
        body: {
          projectId: null,
          permissions: [
            { key: 'case:read', qualifier: 'ALL' },
            { key: 'case:delete', qualifier: 'OWN' },
          ],
        },
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
      '/suites': { status: 200, body: [{ id: 's-1', key: 'TS-1', name: 'Smoke', caseCount: 4, lifecycleStatus: 'APPROVED' }] },
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

    expect(await mainWith(['suite', 'list'], out, stub.fetch)).toBe(EXIT.failed);
    expect(out.stderr).toContain('--project');
  });

  it('resolves a display key before reading the case', async () => {
    const out = recorder();
    const stub = api({
      '/projects': { status: 200, body: [PROJECT] },
      '/cases': { status: 200, body: { page: 0, size: 1, total: 1, facets: [], items: [{ id: 'c-9', key: 'TC-42' }] } },
      '/cases/c-9': {
        status: 200,
        body: { id: 'c-9', key: 'TC-42', title: 'Login works', lifecycleStatus: 'APPROVED', tags: [], lockVersion: 3 },
      },
    });

    expect(await mainWith(['case', 'show', 'TC-42'], out, stub.fetch)).toBe(EXIT.ok);
    expect(out.stdout).toContain('Login works');
    // The lock version is printed because every edit needs it — omitting If-Match is
    // last-write-wins, which is the worst thing this surface can do silently.
    expect(out.stdout).toContain('Lock version: 3');
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
      '/cases': { status: 200, body: { page: 0, size: 25, total: 0, facets: [], items: [] } },
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

    expect(await mainWith(['case', 'show', 'TS-7'], out, stub.fetch)).toBe(EXIT.failed);
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
