/**
 * The CLI is tested through `main()` with a fake output sink and a fake `fetch`, rather than by
 * spawning a process. That keeps the assertions on the two things a caller of a CLI actually
 * depends on — **what it printed and what it exited with** — while still exercising the real
 * argument parsing, the real command dispatch and the real client underneath.
 */
import { describe, expect, it } from 'vitest';

import { EXIT, main } from './main.js';
import type { Output } from './output.js';

interface Recorder extends Output {
  readonly stdout: string;
  readonly stderr: string;
}

function recorder(): Recorder {
  const out: string[] = [];
  const err: string[] = [];
  return {
    line: (text = '') => out.push(text),
    json: (value) => out.push(JSON.stringify(value, null, 2)),
    error: (text) => err.push(text),
    get stdout() { return out.join('\n'); },
    get stderr() { return err.join('\n'); },
  };
}

type Answer = { status: number; body?: unknown };

/** A `fetch` that answers by path suffix, so a test states only the calls it cares about. */
function api(routes: Record<string, Answer>): { fetch: typeof globalThis.fetch; urls: string[] } {
  const urls: string[] = [];
  const fetch: typeof globalThis.fetch = async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const url = new URL(request.url);
    urls.push(`${url.pathname}${url.search}`);

    const match = Object.entries(routes).find(([path]) => url.pathname.endsWith(path));
    if (!match) return new Response(JSON.stringify({ detail: `no stub for ${url.pathname}` }), {
      status: 500, headers: { 'content-type': 'application/json' },
    });

    const [, answer] = match;
    return new Response(answer.status === 204 ? null : JSON.stringify(answer.body ?? {}), {
      status: answer.status,
      headers: { 'content-type': 'application/json' },
    });
  };
  return { fetch, urls };
}

const env = { TCMS_URL: 'https://tcms.example.com', TCMS_TOKEN: 'tcms_abc' };

const PROJECT = { id: 'p-1', key: 'OLB', name: 'Online Banking', archived: false, caseCount: 3, myRole: 'Test Engineer' };

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

/**
 * Runs `main` against an injected `fetch`.
 *
 * The injection point is deliberately the environment rather than a parameter on `main`: the
 * production entry point takes only `argv`, and a test-only parameter threaded through it is a
 * seam that exists in the shipped binary. `connect()` reads the environment, so a test overrides
 * what a user would override.
 */
async function mainWith(
  argv: readonly string[],
  out: Recorder,
  fetch: typeof globalThis.fetch,
): Promise<number> {
  const original = globalThis.fetch;
  globalThis.fetch = fetch;
  try {
    return await main(argv, out, env);
  } finally {
    globalThis.fetch = original;
  }
}

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
