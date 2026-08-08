/**
 * The CLI is tested through `main()` with a fake output sink and a fake `fetch`, rather than by
 * spawning a process. That keeps the assertions on the two things a caller of a CLI actually
 * depends on — **what it printed and what it exited with** — while still exercising the real
 * argument parsing, the real command dispatch and the real client underneath.
 *
 * Extracted from `main.test.ts` when the write tests arrived: `writeConformance.test.ts` asserts
 * over what was *sent* — the headers a create and an edit must carry — so the recorder has to
 * keep the whole request, and two recorders that disagree about what a request is would be the
 * one thing these tests exist to notice.
 */
import { main } from '../main.js';
import type { Output } from '../output.js';

export interface Recorder extends Output {
  readonly stdout: string;
  readonly stderr: string;
}

export function recorder(): Recorder {
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

export interface SentRequest {
  path: string;
  search: string;
  method: string;
  headers: Headers;
  body: string | null;
}

export type Answer = { status: number; body?: unknown };

export interface Api {
  fetch: typeof globalThis.fetch;
  readonly urls: string[];
  readonly sent: SentRequest[];
  /** The last request whose path ends with `suffix` and whose method is not GET. */
  write(suffix: string): SentRequest;
}

/**
 * A `fetch` that answers by path suffix, so a test states only the calls it cares about.
 *
 * Routes may be keyed by `METHOD path` to answer a `GET` and a `PATCH` on the same path
 * differently, which every edit command needs: it reads the resource for its `lockVersion` and
 * then writes to the same URL.
 */
export function api(routes: Record<string, Answer>): Api {
  const urls: string[] = [];
  const sent: SentRequest[] = [];

  const fetch: typeof globalThis.fetch = async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const url = new URL(request.url);
    urls.push(`${url.pathname}${url.search}`);
    sent.push({
      path: url.pathname,
      search: url.search,
      method: request.method,
      // Read off the init, not the Request: `http.ts` composes the credential, the user agent and
      // the two write headers into an explicit init and passes the URL as a string, so a reader
      // that trusted the Request object would see the headers openapi-fetch built and none of the
      // ones this package adds.
      headers: new Headers(init?.headers ?? request.headers),
      // The body arrives as a **stream**, not a string: `http.ts` forwards `request.body`
      // unbuffered so an evidence upload is not held in this process. A recorder that read only
      // the string form saw `null` on every write and every body assertion passed vacuously.
      body: await bodyOf(init?.body),
    });

    // Method-keyed routes win, so `'PATCH /cases/c-9'` can answer differently from the `GET` the
    // same command makes at the same URL for its lockVersion.
    const entries = Object.entries(routes).map(([key, answer]) => ({ ...parse(key), answer }));
    const match =
      entries.find(({ method, suffix }) => method === request.method && url.pathname.endsWith(suffix))
      ?? entries.find(({ method, suffix }) => method === undefined && url.pathname.endsWith(suffix));

    if (!match) {
      return new Response(JSON.stringify({ detail: `no stub for ${request.method} ${url.pathname}` }), {
        status: 500, headers: { 'content-type': 'application/json' },
      });
    }

    return new Response(match.answer.status === 204 ? null : JSON.stringify(match.answer.body ?? {}), {
      status: match.answer.status,
      headers: { 'content-type': 'application/json' },
    });
  };

  return {
    fetch,
    urls,
    sent,
    write(suffix) {
      const found = [...sent].reverse().find(
        (request) => request.method !== 'GET' && request.path.endsWith(suffix),
      );
      if (!found) {
        throw new Error(
          `No write to a path ending '${suffix}'. Sent: `
            + sent.map((request) => `${request.method} ${request.path}`).join(', '),
        );
      }
      return found;
    },
  };
}

async function bodyOf(body: RequestInit['body']): Promise<string | null> {
  if (body === null || body === undefined) return null;
  if (typeof body === 'string') return body;
  return new Response(body, { status: 200 }).text();
}

function parse(key: string): { method?: string; suffix: string } {
  const space = key.indexOf(' ');
  return space === -1
    ? { suffix: key }
    : { method: key.slice(0, space), suffix: key.slice(space + 1) };
}

export const env = { TCMS_URL: 'https://tcms.example.com', TCMS_TOKEN: 'tcms_abc' };

export const PROJECT = {
  id: 'p-1',
  key: 'OLB',
  name: 'Online Banking',
  archived: false,
  caseCount: 3,
  myRole: 'Test Engineer',
};

/**
 * Runs `main` against an injected `fetch`.
 *
 * The injection point is deliberately the environment rather than a parameter on `main`: the
 * production entry point takes only `argv`, and a test-only parameter threaded through it is a
 * seam that exists in the shipped binary. `connect()` reads the environment, so a test overrides
 * what a user would override.
 */
export async function mainWith(
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
