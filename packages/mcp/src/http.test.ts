/**
 * The remote shape: a real listening server, real HTTP, and a stubbed TCMS under it.
 *
 * This file exists for one claim, which is the claim the whole slice turns on: **the credential
 * arrives per request and the server built around it is not shared.** Everything else here —
 * the 401, the body cap, the method handling — is the surface that claim is reachable through,
 * and each of them is a way a caller could otherwise reach a session that is not theirs.
 */
import type { AddressInfo } from 'node:net';
import type { Server } from 'node:http';

import { afterEach, describe, expect, it } from 'vitest';

import { createHost, MAX_BODY_BYTES } from './http.js';
import { BASE_URL, ME, PROJECT, permissions, AGENT_KEYS } from './test/harness.js';

let host: Server | undefined;

afterEach(async () => {
  if (host) await new Promise<void>((resolve) => { host?.close(() => { resolve(); }); });
  host = undefined;
  globalThis.fetch = REAL_FETCH;
});

const REAL_FETCH = globalThis.fetch;

/**
 * A TCMS whose answers depend on the bearer, which is what makes the isolation testable at all.
 *
 * Two tokens, two people, two projects. A shared server object would serve the second caller the
 * first one's summary, and this is the only arrangement in which that shows up as a failure
 * rather than as a plausible-looking string.
 */
function tcmsByToken(): typeof globalThis.fetch {
  const people: Record<string, { me: typeof ME; project: typeof PROJECT }> = {
    'token-ana': { me: ME, project: PROJECT },
    'token-bo': {
      me: { ...ME, id: 'u-2', email: 'bo@acme.test', displayName: 'Bo Lead' },
      project: { ...PROJECT, id: 'p-9', key: 'PAY', name: 'Payments' },
    },
  };

  return async (input, init) => {
    const request = input instanceof Request ? input : new Request(input, init);
    const url = new URL(request.url);
    if (url.origin !== new URL(BASE_URL).origin) return REAL_FETCH(input, init);

    const bearer = new Headers(init?.headers ?? request.headers).get('Authorization')?.slice(7) ?? '';
    const person = people[bearer];
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

    if (!person) return json(401, { title: 'Unauthorized', status: 401, detail: 'Not accepted.' });
    if (url.pathname.endsWith('/me/permissions')) return json(200, permissions([...AGENT_KEYS]));
    if (url.pathname.endsWith('/me')) return json(200, person.me);
    if (url.pathname.endsWith('/projects')) return json(200, [person.project]);
    return json(500, { detail: `no stub for ${url.pathname}` });
  };
}

async function listen(): Promise<string> {
  host = createHost({ baseUrl: BASE_URL, port: 0, host: '127.0.0.1', path: '/mcp' });
  await new Promise<void>((resolve) => { host?.listen(0, '127.0.0.1', resolve); });
  const address = host.address() as AddressInfo;
  return `http://127.0.0.1:${address.port}/mcp`;
}

const INITIALIZE = {
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'test-host', version: '0.0.0' },
  },
};

async function initialize(url: string, token?: string): Promise<Response> {
  return REAL_FETCH(url, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(INITIALIZE),
  });
}

/** The transport answers as SSE by default; the result is the first `data:` frame. */
async function resultOf(response: Response): Promise<Record<string, unknown>> {
  const text = await response.text();
  const frame = text.split('\n').find((line) => line.startsWith('data:'));
  if (!frame) throw new Error(`No data frame in: ${text.slice(0, 200)}`);
  const message = JSON.parse(frame.slice(5).trim()) as { result?: Record<string, unknown> };
  return message.result ?? {};
}

describe('the credential', () => {
  it('is required, and the refusal tells a host to present one', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const response = await initialize(url);

    expect(response.status).toBe(401);
    // Without this a 401 reads to an MCP host as "the server is broken" rather than "sign in".
    expect(response.headers.get('www-authenticate')).toBe('Bearer');
  });

  it('is verified by TCMS and not here, so a revoked token and an unknown one look alike', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const response = await initialize(url, 'token-nobody');

    expect(response.status).toBe(401);
    // The body carries nothing about which of the two it was — WIP plan §4 requires exactly that,
    // and this server can only comply because it never made the distinction itself.
    expect(await response.text()).not.toContain('revoked');
  });

  it('gives each caller their own server, with their own capability summary', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const ana = await resultOf(await initialize(url, 'token-ana'));
    const bo = await resultOf(await initialize(url, 'token-bo'));

    /*
      The whole point. `instructions` is a constructor argument sent once during initialize, so a
      process-wide server would hand Bo the summary built for Ana — their name, their project,
      the size of their reach — in a field that reads like documentation and is never re-sent.
    */
    expect(String(ana['instructions'])).toContain('Ana Engineer');
    expect(String(ana['instructions'])).toContain('OLB');
    expect(String(bo['instructions'])).toContain('Bo Lead');
    expect(String(bo['instructions'])).toContain('PAY');
    expect(String(bo['instructions'])).not.toContain('Ana Engineer');
  });

  it('hands each session its own id', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const ana = await initialize(url, 'token-ana');
    const bo = await initialize(url, 'token-bo');

    expect(ana.headers.get('mcp-session-id')).toBeTruthy();
    expect(ana.headers.get('mcp-session-id')).not.toBe(bo.headers.get('mcp-session-id'));
  });
});

describe('the endpoint', () => {
  it('serves one path and 404s everything else', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const response = await REAL_FETCH(url.replace('/mcp', '/'), { method: 'POST', body: '{}' });
    expect(response.status).toBe(404);
  });

  it('refuses a GET that has no live session rather than opening one', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    // A session opened on a GET would be an SSE stream established before any `initialize`,
    // which is a connection the protocol has no state for.
    expect((await REAL_FETCH(url, { method: 'GET' })).status).toBe(405);
    expect((await REAL_FETCH(url, { method: 'GET', headers: { 'mcp-session-id': 'made-up' } })).status)
      .toBe(404);
  });

  it('refuses a POST that is not an initialize and names no session', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const response = await REAL_FETCH(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer token-ana' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/list' }),
    });

    expect(response.status).toBe(400);
  });

  it('answers an empty POST body with a 400 rather than hanging', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    // An empty body used to fall through every branch without a response being written — the
    // one request shape this endpoint simply never answered.
    const response = await REAL_FETCH(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: 'Bearer token-ana' },
      body: '',
    });

    expect(response.status).toBe(400);
  });

  it('caps the body on an existing session too, and answers exactly once', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const opened = await initialize(url, 'token-ana');
    const sessionId = opened.headers.get('mcp-session-id') ?? '';

    // The oversized branch responds 413 inside the body read; the handler must then stop rather
    // than hand the transport a consumed request on an ended response.
    const response = await REAL_FETCH(url, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        accept: 'application/json, text/event-stream',
        'mcp-session-id': sessionId,
      },
      body: JSON.stringify({ pad: 'x'.repeat(MAX_BODY_BYTES + 1) }),
    });

    expect(response.status).toBe(413);
  });

  it('caps the body, because this endpoint is reachable before any credential is checked', async () => {
    globalThis.fetch = tcmsByToken();
    const url = await listen();

    const response = await REAL_FETCH(url, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ pad: 'x'.repeat(MAX_BODY_BYTES + 1) }),
    });

    expect(response.status).toBe(413);
  });
});
