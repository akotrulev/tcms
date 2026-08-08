/**
 * The remote HTTPS shape — WIP plan §4, which reverses §6w open question 5's stdio-first answer.
 *
 * *"Local STDIO servers cannot be connected directly"* by the Messages API connector, and that is
 * the whole justification for this transport existing: Claude Desktop and hosted agents reach an
 * HTTPS endpoint or nothing.
 *
 * ## TLS is deliberately not here
 *
 * This listens on plain HTTP and is meant to sit behind the Caddy that already terminates TLS for
 * `/api/*` on the lab box (WIP plan §4: *"one container and one `handle` block"*). A second TLS
 * implementation in this process would be a second certificate lifecycle, a second HSTS decision,
 * and a second place to get a cipher list wrong. {@link BIND_DEFAULT} is loopback for the same
 * reason — a process that binds `0.0.0.0` is reachable without the proxy the moment a firewall
 * rule is wrong, and this one carries other people's credentials.
 *
 * ## Auth is a static bearer, per §4, and it is forwarded rather than validated
 *
 * The `Authorization` header is the TCMS API token. This server does **not** verify it: it builds
 * a client with it and asks TCMS who it is. That is what keeps the two answers — "is this token
 * usable" and "what may it do" — in the one place that owns them, and it is why a revoked token
 * and an unknown one are indistinguishable here (§4 requires that): both are whatever `GET /me`
 * says, and this file never sees a difference to leak.
 *
 * OAuth 2.1 buys Claude Code's `/mcp` browser sign-in and Directory eligibility, and §4 is
 * explicit that it is **a slice of its own**.
 */
import { randomUUID } from 'node:crypto';
import { createServer, type IncomingMessage, type Server as HttpServer, type ServerResponse } from 'node:http';

import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { isInitializeRequest } from '@modelcontextprotocol/sdk/types.js';

import { buildServer } from './server.js';
import { isCredentialFailure, openSession } from './session.js';

export const BIND_DEFAULT = '127.0.0.1';
export const PATH_DEFAULT = '/mcp';

/**
 * A body ceiling, because this endpoint is reachable by anyone who can reach the proxy.
 *
 * The largest legitimate request on this surface is a doc page's blocks or a generated case, both
 * kilobytes. Rate limiting proper is phase 9; this is the one limit that cannot wait for it,
 * because without it a single unauthenticated POST can hold as much of the box's memory as it
 * likes — on the machine that is also running Postgres (§4's own note).
 */
export const MAX_BODY_BYTES = 1_000_000;

export interface HostOptions {
  baseUrl: string;
  port: number;
  host?: string;
  path?: string;
  /** `TCMS_PROJECT` — the operator binding every session to one project. See `session.ts`. */
  project?: string | undefined;
}

interface Live {
  transport: StreamableHTTPServerTransport;
  close: () => Promise<void>;
}

export function createHost(options: HostOptions): HttpServer {
  const path = options.path ?? PATH_DEFAULT;
  const sessions = new Map<string, Live>();

  return createServer((req, res) => {
    void handle(req, res).catch((error: unknown) => {
      fail(res, 500, error instanceof Error ? error.message : 'Internal error');
    });
  });

  async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? '/', 'http://localhost');
    if (url.pathname !== path) return fail(res, 404, 'Not found');

    const sessionId = header(req, 'mcp-session-id');
    const existing = sessionId ? sessions.get(sessionId) : undefined;

    if (existing) {
      await existing.transport.handleRequest(req, res, await bodyOf(req, res));
      return;
    }

    // A GET or DELETE with no live session is the resumption case, and it must not open one:
    // opening a session on a GET would mean an SSE stream established before any `initialize`,
    // which is a connection the protocol has no state for.
    if (req.method !== 'POST') {
      return fail(res, sessionId ? 404 : 405, sessionId ? 'No such session' : 'Method not allowed');
    }

    const body = await bodyOf(req, res);
    if (body === undefined) return;
    if (!isInitializeRequest(body)) {
      return fail(res, 400, 'No session. Send an initialize request first.');
    }

    const token = bearer(req);
    if (!token) return unauthorized(res);

    let live: Live;
    try {
      const session = await openSession({
        baseUrl: options.baseUrl,
        token,
        ...(options.project ? { project: options.project } : {}),
      });
      /*
        The whole server is built **after** the probe, because its instructions are this
        credential's capability summary and its tool list is filtered by this credential's
        permissions. `session.ts` carries the argument; the consequence is here, where a shared
        instance would have been the obvious thing to write.
      */
      const server = buildServer(session);
      const transport = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (id) => { sessions.set(id, live); },
        onsessionclosed: (id) => { sessions.delete(id); },
      });
      transport.onclose = () => {
        if (transport.sessionId) sessions.delete(transport.sessionId);
      };
      live = { transport, close: () => server.close() };
      /*
        The cast is `exactOptionalPropertyTypes` and nothing else: the SDK's `Transport` declares
        `onclose?: () => void` while this class exposes it as `(() => void) | undefined`, which
        under that flag are different types. Widening the flag for the package would be the wrong
        trade — it is what makes "absent" and "present and undefined" distinguishable, and this
        surface distinguishes them on `description`, `suiteId` and every other nullable field
        where a `PATCH` must not clear what nobody mentioned.
      */
      await server.connect(transport as Parameters<typeof server.connect>[0]);
    } catch (error) {
      // A bad credential answers 401 and says nothing else — a revoked token and an unknown one
      // must be indistinguishable (§4), and anything more specific here would be the difference.
      if (isCredentialFailure(error)) return unauthorized(res);
      throw error;
    }

    await live.transport.handleRequest(req, res, body);
  }

  async function bodyOf(req: IncomingMessage, res: ServerResponse): Promise<unknown> {
    if (req.method !== 'POST') return undefined;

    let size = 0;
    const chunks: Buffer[] = [];
    for await (const chunk of req) {
      const buffer = chunk as Buffer;
      size += buffer.length;
      if (size > MAX_BODY_BYTES) {
        fail(res, 413, 'Request too large');
        return undefined;
      }
      chunks.push(buffer);
    }

    const text = Buffer.concat(chunks).toString('utf8');
    if (text.length === 0) return undefined;
    try {
      return JSON.parse(text);
    } catch {
      fail(res, 400, 'Body is not JSON');
      return undefined;
    }
  }
}

function header(req: IncomingMessage, name: string): string | undefined {
  const value = req.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

function bearer(req: IncomingMessage): string | undefined {
  const value = header(req, 'authorization');
  if (!value) return undefined;
  const match = /^Bearer\s+(.+)$/i.exec(value.trim());
  return match?.[1];
}

function unauthorized(res: ServerResponse): void {
  // `WWW-Authenticate` is what tells an MCP host it should present a credential rather than that
  // the server is broken. Without it a 401 is just a failure to the client.
  res.writeHead(401, { 'content-type': 'application/json', 'www-authenticate': 'Bearer' });
  res.end(JSON.stringify({ error: 'A TCMS API token is required.' }));
}

function fail(res: ServerResponse, status: number, message: string): void {
  if (res.headersSent) return;
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify({ error: message }));
}
