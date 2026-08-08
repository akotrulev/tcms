/**
 * The server is tested through a real `Session` over a stubbed `fetch`, and through the SDK's own
 * in-memory transport pair where the protocol itself is what is under test.
 *
 * The stub is the CLI's, one package over, and deliberately so: both surfaces are asserted
 * against the same idea of what a request is. Two recorders that disagreed about whether a header
 * arrived would be the one thing these tests exist to notice.
 */
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

  const fetch: typeof globalThis.fetch = async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const url = new URL(request.url);
    sent.push({
      path: url.pathname,
      search: url.search,
      method: request.method,
      // Off the init, not the Request: `http.ts` composes the credential and the write headers
      // into an explicit init and passes the URL as a string.
      headers: new Headers(init?.headers ?? request.headers),
      body: await bodyOf(init?.body),
    });

    const entries = Object.entries(live).map(([key, answer]) => ({ ...parse(key), answer }));
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

export const ME = {
  id: 'u-1',
  email: 'ana@acme.test',
  displayName: 'Ana Engineer',
  isSystemAdmin: false,
  status: 'ACTIVE',
};

export const PROJECT = {
  id: 'p-1',
  key: 'OLB',
  name: 'Online Banking',
  archived: false,
  caseCount: 3,
  myRole: 'Test Engineer',
};

/** The `Agent` grant as §A6a enumerates it — Test Engineer minus four. */
export const AGENT_KEYS = [
  'project:read', 'case:read', 'case:create', 'case:edit', 'case:comment',
  'case:review', 'suite:manage', 'suite:review', 'doc:read', 'doc:edit',
  'plan:create', 'plan:edit', 'plan:review', 'run:create', 'run:execute',
  'result:submit', 'evidence:attach', 'report:view', 'report:export',
  'artifact:read', 'artifact:edit', 'defect:create', 'workitem:manage',
] as const;

export function permissions(
  keys: readonly string[],
  qualifiers: Readonly<Record<string, 'ALL' | 'OWN' | 'PLAN_SCOPE'>> = {},
): { projectId: string | null; permissions: { key: string; qualifier: string }[] } {
  return {
    projectId: PROJECT.id,
    permissions: keys.map((key) => ({ key, qualifier: qualifiers[key] ?? 'ALL' })),
  };
}

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
