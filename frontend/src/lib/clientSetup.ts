/**
 * How a user points a client at *this* instance — the MCP host configuration an agent is pasted
 * into, and the CLI's install line — rendered in one place, for the screens that hand them over.
 *
 * ## Why a module and not two literals in the JSX
 *
 * The help page describes how to connect an agent, and the issue-token dialog is where the
 * credential that connects it comes into existence. Both have to print the same JSON, and they are
 * in different features written months apart — which is exactly the shape that drifts. It has
 * already drifted once in prose: the guide offered the hosted endpoint as a bare URL and the stdio
 * shape as a block, so the shape it calls *preferred* was the one a reader had to assemble
 * themselves. Rendering both from here means a change to the transport, the header or an
 * environment variable name lands on every surface at once, and can be unit-tested without a DOM.
 *
 * ## `JSON.stringify`, never a template literal
 *
 * The output is pasted into a file a host parses, and a hand-written block is one missing comma
 * away from an agent that fails to start with a message about column 14. Building an object and
 * serialising it makes a syntactically invalid config unrepresentable, and it is why
 * {@link mcpServerConfig} takes a project *value* rather than an optional line of text.
 *
 * ## The token is a parameter, and both callers are honest about which one they pass
 *
 * The help page has no credential and passes {@link TOKEN_PLACEHOLDER}; the reveal step of the
 * issue dialog has the real one and passes it, because that is the one moment in the product where
 * the plaintext exists. Nothing here stores, logs or defaults it — a config rendered with no token
 * would be a config that silently connects as nobody.
 */

/** The instance's hosted endpoint. `deploy/shanks/Caddyfile` routes this exact path, not a prefix. */
export const MCP_PATH = '/mcp';

/** What stands in for the secret on a surface that does not have one. */
export const TOKEN_PLACEHOLDER = '<your token>';

/**
 * How the host reaches the server.
 *
 *   - `hosted` — Streamable HTTP against the instance, nothing installed. The shape to prefer, and
 *     off unless the operator enabled it (`docs/specs/client-distribution.md`).
 *   - `stdio` — the server run as a local process by the host.
 */
export type McpTransport = 'hosted' | 'stdio';

export interface McpConfigInput {
  transport: McpTransport;
  /** This instance's origin. A trailing slash is tolerated and stripped. */
  origin: string;
  /** The bearer, or {@link TOKEN_PLACEHOLDER}. */
  token: string;
  /**
   * A project **key** (`OLB`), binding every session to one project — `TCMS_PROJECT`, which
   * `packages/client/src/core/project.ts` matches on key first and id second.
   *
   * `stdio` only, and that is not an omission. Over HTTP the server is the operator's process and
   * its environment is theirs; a session there resolves the project from the token, which for a
   * token pinned to one project is already the single row `GET /projects` answers.
   */
  project?: string | undefined;
}

/** The origin with any trailing slash removed, so `${origin}${MCP_PATH}` never doubles it. */
function normalizeOrigin(origin: string): string {
  return origin.replace(/\/+$/, '');
}

/**
 * The `mcpServers` entry, pretty-printed for pasting.
 *
 * Named `tcms` because that is what the tools are prefixed with (`tcms_whoami`), and a host that
 * namespaces tools by server would otherwise produce `myinstance__tcms_whoami`.
 */
export function mcpServerConfig(input: McpConfigInput): string {
  const origin = normalizeOrigin(input.origin);

  const server =
    input.transport === 'hosted'
      ? {
          type: 'http',
          url: `${origin}${MCP_PATH}`,
          // The header the server actually reads — `packages/mcp/src/http.ts` matches
          // `^Bearer\s+(.+)$` and answers 401 with `WWW-Authenticate: Bearer` when it does not.
          headers: { Authorization: `Bearer ${input.token}` },
        }
      : {
          command: 'tcms-mcp',
          env: {
            TCMS_URL: origin,
            TCMS_TOKEN: input.token,
            ...(input.project ? { TCMS_PROJECT: input.project } : {}),
          },
        };

  return JSON.stringify({ mcpServers: { tcms: server } }, null, 2);
}

/**
 * The install line for the local shape.
 *
 * The tarball comes from the instance being configured rather than a registry, and which instance
 * is load-bearing: both clients are adapters over that instance's contract, built by the release
 * that shipped the server beside them.
 */
export function mcpInstallCommand(origin: string): string {
  return `npm install -g ${normalizeOrigin(origin)}/downloads/tcms-mcp-latest.tgz`;
}

/**
 * The one line that connects a Claude Code host to this instance as an assistant (015 A1).
 *
 * `assistant-connect.html`'s snippet, and it is one line rather than a config block on purpose:
 * the reader has just pressed a button and holds a secret that dies when they leave the page, so
 * what they need is something to paste, not a file to author. {@link mcpServerConfig} stays the
 * shape for a host that wants the JSON.
 */
export function assistantSetupCommand(origin: string, token: string = TOKEN_PLACEHOLDER): string {
  const base = normalizeOrigin(origin);
  return [
    'claude mcp add tcms --transport http \\',
    `  ${base}${MCP_PATH} \\`,
    `  --header "Authorization: Bearer ${token}"`,
  ].join('\n');
}

/**
 * Installing and configuring the CLI, as the block a reader copies whole.
 *
 * The environment is exported rather than written to a file because there is no config file and no
 * keychain: `TCMS_URL` and `TCMS_TOKEN` are the entire configuration, which is what makes the tool
 * safe to run from a pipeline that has a secret store and nothing else.
 */
export function cliSetupCommands(origin: string, token: string = TOKEN_PLACEHOLDER): string {
  const base = normalizeOrigin(origin);
  return [
    `npm install -g ${base}/downloads/tcms-cli-latest.tgz`,
    '',
    `export TCMS_URL=${base}`,
    `export TCMS_TOKEN=${token}`,
    '',
    'tcms whoami',
  ].join('\n');
}
