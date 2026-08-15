#!/usr/bin/env node
/**
 * The entry point: two transports, one server.
 *
 * **HTTP is the deployment** (WIP plan §4). **Stdio is the developer's loop** — `npx @tcms/mcp`
 * in a local host config, one credential from the environment, nothing to deploy. Both build the
 * same server over the same session, so a tool that behaves differently in one of them is a
 * defect rather than a mode.
 *
 * The stdio shape has one property the HTTP shape does not, and it is why it survives §4's
 * reversal rather than being deleted: the credential never leaves the machine that holds it. That
 * is the better arrangement whenever it is available, and it is unavailable only because the
 * Messages API connector cannot spawn a process.
 *
 * ## Nothing may be written to stdout under stdio
 *
 * stdout **is** the protocol channel. A stray `console.log` — a diagnostic, a deprecation notice,
 * a library's banner — is framed as a JSON-RPC message and the host disconnects on the parse
 * failure, which presents as "the server crashed" with nothing in any log. Every diagnostic here
 * goes to stderr for that reason, including the ones that look like output.
 */
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { parseArgs } from 'node:util';

import { createHost, BIND_DEFAULT, PATH_DEFAULT } from './http.js';
import { buildServer } from './server.js';
import { ENV_PROJECT, ENV_TOKEN, ENV_URL, openSession } from './session.js';

export const EXIT = { ok: 0, failed: 1, usage: 2 } as const;

export async function main(
  argv: readonly string[],
  env: NodeJS.ProcessEnv = process.env,
  log: (text: string) => void = (text) => process.stderr.write(`${text}\n`),
): Promise<number> {
  let parsed;
  try {
    parsed = parseArgs({
      args: [...argv],
      options: {
        http: { type: 'boolean', default: false },
        port: { type: 'string' },
        host: { type: 'string' },
        path: { type: 'string' },
        url: { type: 'string' },
        help: { type: 'boolean', default: false },
      },
      strict: true,
      allowPositionals: false,
    });
  } catch (error) {
    log(error instanceof Error ? error.message : String(error));
    return EXIT.usage;
  }

  if (parsed.values.help) {
    log(USAGE);
    return EXIT.ok;
  }

  const baseUrl = parsed.values.url ?? env[ENV_URL];
  if (!baseUrl) {
    log(`No TCMS instance. Set ${ENV_URL}=https://tcms.example.com, or pass --url.`);
    return EXIT.usage;
  }

  const project = env[ENV_PROJECT];

  if (parsed.values.http) {
    const port = Number(parsed.values.port ?? 8081);
    if (!Number.isInteger(port) || port < 1 || port > 65_535) {
      log('--port takes a TCP port.');
      return EXIT.usage;
    }

    const host = createHost({
      baseUrl,
      port,
      host: parsed.values.host ?? BIND_DEFAULT,
      path: parsed.values.path ?? PATH_DEFAULT,
      ...(project ? { project } : {}),
    });

    await new Promise<void>((resolve) => {
      host.listen(port, parsed.values.host ?? BIND_DEFAULT, resolve);
    });
    log(`tcms-mcp listening on ${parsed.values.host ?? BIND_DEFAULT}:${port}${parsed.values.path ?? PATH_DEFAULT} → ${baseUrl}`);

    /*
      The deployment is a container, and a container stops with SIGTERM. Without this the signal
      kills the process mid-request; with it the listener closes and in-flight requests finish.
      Open SSE streams would hold `close` open indefinitely, so the unref'd timer puts a ceiling
      on the wait without itself keeping the process alive.
    */
    const shutdown = (signal: string) => {
      log(`tcms-mcp: ${signal}, closing`);
      host.close(() => { process.exit(EXIT.ok); });
      setTimeout(() => { process.exit(EXIT.ok); }, 5000).unref();
    };
    process.once('SIGTERM', () => { shutdown('SIGTERM'); });
    process.once('SIGINT', () => { shutdown('SIGINT'); });
    return EXIT.ok;
  }

  const token = env[ENV_TOKEN];
  if (!token) {
    log(`No API token. Set ${ENV_TOKEN} to a token issued from Settings → API tokens.`);
    return EXIT.usage;
  }

  /*
    The probe happens **before** the transport is connected, so a bad credential or an unreachable
    instance is a startup failure with a sentence in it. Connecting first and failing on the first
    tool call would put the same failure inside a tool result, where a host renders it as the
    model being unable to do something rather than as the server being misconfigured.
  */
  const session = await openSession({ baseUrl, token, ...(project ? { project } : {}) });
  await buildServer(session).connect(new StdioServerTransport());
  return EXIT.ok;
}

const USAGE = `tcms-mcp — the TCMS MCP server

  tcms-mcp                 stdio; the credential comes from ${ENV_TOKEN}
  tcms-mcp --http [--port N] [--host H] [--path P]
                           Streamable HTTP; the credential comes from each request's
                           Authorization header. Put TLS in front of it.

Environment:
  ${ENV_URL}         the instance origin, e.g. https://tcms.example.com
  ${ENV_TOKEN}       an API token — stdio only
  ${ENV_PROJECT}     bind every session to one project, when a token can see several`;

if (process.argv[1] && import.meta.url === `file://${process.argv[1]}`) {
  main(process.argv.slice(2))
    .then((code) => { if (code !== EXIT.ok) process.exitCode = code; })
    .catch((error: unknown) => {
      process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = EXIT.failed;
    });
}
