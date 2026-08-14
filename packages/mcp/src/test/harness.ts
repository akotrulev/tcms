/**
 * The server is tested through a real `Session` over a stubbed `fetch`, and through the SDK's own
 * in-memory transport pair where the protocol itself is what is under test.
 *
 * The stub is the CLI's, one package over, and deliberately so: both surfaces are asserted
 * against the same idea of what a request is. Two recorders that disagreed about whether a header
 * arrived would be the one thing these tests exist to notice.
 */
/*
  Reached by a relative path across the package boundary — the CLI harness carries the argument for
  why, in the same words. The comparison must exist once; `dist/` deliberately does not carry it.
*/
import { checkExchange } from '../../../client/src/test/contractExchange.js';
import { currentUser, permissions, project } from '../../../client/src/test/fixtures.js';
import { openSession, type Reach, type Session } from '../session.js';

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
  readonly sent: SentRequest[];
  /** Replaces a route mid-test — how a grant revoked during a session is simulated. */
  set(key: string, answer: Answer): void;
  write(suffix: string): SentRequest;
}

export function api(routes: Record<string, Answer>): Api {
  const live = { ...routes };
  const sent: SentRequest[] = [];
  /*
    Captured before this stub is installed, so a request to somewhere that is **not** TCMS still
    goes out for real. `http.test.ts` needs it: that test drives a listening HTTP server in this
    same process while the server's own calls to TCMS are stubbed, and a stub that answered 500
    to everything would break the test's own client rather than the code under test.
  */
  const passthrough = globalThis.fetch;

  const fetch: typeof globalThis.fetch = async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const url = new URL(request.url);
    if (url.origin !== new URL(BASE_URL).origin) return passthrough(input, init);
    const recorded: SentRequest = {
      path: url.pathname,
      search: url.search,
      method: request.method,
      // Off the init, not the Request: `http.ts` composes the credential and the write headers
      // into an explicit init and passes the URL as a string.
      headers: new Headers(init?.headers ?? request.headers),
      body: await bodyOf(init?.body),
    };
    sent.push(recorded);

    const entries = Object.entries(live).map(([key, answer]) => ({ ...parse(key), answer }));
    const match =
      entries.find(({ method, suffix }) => method === request.method && url.pathname.endsWith(suffix))
      ?? entries.find(({ method, suffix }) => method === undefined && url.pathname.endsWith(suffix));

    // Both directions against `openapi.yaml`, §203 — the CLI harness carries the note.
    checkExchange({
      method: recorded.method,
      pathname: recorded.path,
      requestBody: recorded.body,
      status: match?.answer.status ?? 500,
      responseBody: match?.answer.body,
    });

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
    sent,
    set: (key, answer) => { live[key] = answer; },
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
  return space === -1 ? { suffix: key } : { method: key.slice(0, space), suffix: key.slice(space + 1) };
}

/* ------------------------------------------------------------------ fixtures ---- */

export const BASE_URL = 'https://tcms.example.com';

/*
  Both from `@tcms/client`'s fixture factories since §203, rather than written out here.

  `ME` used to carry a `status: 'ACTIVE'`, which `CurrentUser` does not declare and the server has
  never sent — it was here, unnoticed, in front of **91 tests**. That is the undeclared-property
  finding in its typical form: not a typo, a field somebody reasonably expected the endpoint to have.
*/
export const ME = currentUser();

export const PROJECT = project();

/** The `Agent` grant as §A6a enumerates it — Test Engineer minus four. */
export const AGENT_KEYS = [
  'project:read', 'case:read', 'case:create', 'case:edit', 'case:comment',
  'case:review', 'suite:manage', 'suite:review', 'doc:read', 'doc:edit',
  'plan:create', 'plan:edit', 'plan:review', 'run:create', 'run:execute',
  'result:submit', 'evidence:attach', 'report:view', 'report:export',
  'artifact:read', 'artifact:edit', 'defect:create', 'workitem:manage',
] as const;

/** Re-exported so the test files that build one keep importing it from here. */
export { permissions };

/** The three reads every session's probe makes, answered for a well-formed `Agent` token. */
export function agentRoutes(extra: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    '/me': { status: 200, body: ME },
    '/projects': { status: 200, body: [PROJECT] },
    '/me/permissions': { status: 200, body: permissions([...AGENT_KEYS]) },
    ...extra,
  };
}

/** Opens a session against a stubbed `fetch`, with the global swapped for the duration. */
export async function sessionWith(stub: Api, project?: string): Promise<Session> {
  const original = globalThis.fetch;
  globalThis.fetch = stub.fetch;
  try {
    return await openSession({
      baseUrl: BASE_URL,
      token: 'tcms_abc',
      ...(project ? { project } : {}),
    });
  } finally {
    globalThis.fetch = original;
  }
}

/** Runs `body` with the stub installed, for the calls a tool makes after the probe. */
export async function withApi<T>(stub: Api, body: () => Promise<T>): Promise<T> {
  const original = globalThis.fetch;
  globalThis.fetch = stub.fetch;
  try {
    return await body();
  } finally {
    globalThis.fetch = original;
  }
}

export function reachOf(keys: readonly string[]): Pick<Reach, 'permissions' | 'qualifiers'> {
  return { permissions: new Set(keys), qualifiers: new Map(keys.map((key) => [key, 'ALL'])) };
}
