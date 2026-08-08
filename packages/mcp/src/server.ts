/**
 * One MCP server, bound to one session.
 *
 * ## Built per session, and it is not a style choice
 *
 * `instructions` is a constructor argument (SDK `ServerOptions`), sent once during
 * `initialize` and never re-sent. This surface puts the **capability summary** there (§6.2), and
 * the summary names the person, their project and the size of their reach. A process-wide server
 * object serving a remote HTTPS deployment — which is the deployment (WIP plan §4) — would
 * therefore hand every later caller the first caller's summary, through a field that looks like
 * documentation. `tools/list` has the same shape of problem one level down: it is filtered by
 * what *this* credential holds.
 *
 * So `session.ts` probes per connection and this builds a server around the answer. The cost is
 * one small object per session; the alternative is a cross-tenant disclosure in a field nobody
 * would think to audit.
 *
 * ## The dispatcher's two jobs
 *
 * Resolve the **arm** — so a refusal names the permission of the operation that actually ran
 * rather than the tool's first gate key — and hand every failure to `result.ts`, so §4.5's
 * 404-vs-403 boundary is applied in one place and never composed at a call site.
 */
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  type CallToolResult,
  type Tool,
} from '@modelcontextprotocol/sdk/types.js';

import { renderInstructions } from './instructions.js';
import { failed, ok } from './result.js';
import { MCP_VERSION, type Session } from './session.js';
import { describeFor, isListed, type ToolArm, type ToolDefinition } from './tool.js';
import { TOOLS, findTool } from './tools.js';
import { listedNames } from './whoami.js';

export const SERVER_NAME = 'tcms';

export function buildServer(session: Session, tools: readonly ToolDefinition[] = TOOLS): Server {
  const server = new Server(
    { name: SERVER_NAME, version: MCP_VERSION },
    {
      capabilities: {
        // `listChanged` is advertised because this server really does send the notification —
        // `tcms_whoami` re-probes and the listed set can change under a role change mid-session.
        // Advertising a capability nothing emits is the kind of claim a host plans around.
        tools: { listChanged: true },
      },
      instructions: renderInstructions(session.reach, tools),
    },
  );

  let listed = listedNames(tools, session.reach.permissions);
  session.onListChanged = () => {
    const now = listedNames(tools, session.reach.permissions);
    if (now.join('\n') === listed.join('\n')) return;
    listed = now;
    void server.sendToolListChanged();
  };

  server.setRequestHandler(ListToolsRequestSchema, () => ({
    tools: tools
      .filter((tool) => isListed(tool, session.reach.permissions))
      .map((tool) => describe(tool, session)),
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request, extra) => {
    const tool = findTool(request.params.name);
    /*
      An unknown or unlisted name is a **result**, not a protocol error, and it says nothing about
      why. A hidden tool that answered "you lack case:approve" would restore precisely the
      enumeration the filtering removes — the model would learn the tool exists, which is the fact
      hiding it was meant to withhold.
    */
    if (!tool || !isListed(tool, session.reach.permissions)) {
      return {
        content: [{ type: 'text', text: `No tool named '${request.params.name}' is available.` }],
        isError: true,
      } satisfies CallToolResult;
    }

    const input = (request.params.arguments ?? {}) as Record<string, unknown>;
    const arm = armFor(tool, input);

    try {
      const value = await tool.run(
        {
          ...session,
          request: { ...session.request, signal: extra.signal },
          // §4.2: the MCP adapter derives the idempotency key from the tool-call id. See
          // `Session.callId` for exactly which retry that covers and which it does not.
          callId: `mcp-${String(extra.requestId)}`,
        },
        input,
      );
      // The refresh a handler may have performed lands on the shared object, so the notification
      // is checked after every call rather than only after `tcms_whoami` — the shape survives a
      // second tool learning to re-probe later.
      session.onListChanged?.();
      return ok(value) as CallToolResult;
    } catch (error) {
      return failed(error, {
        what: `${tool.title.charAt(0).toLowerCase()}${tool.title.slice(1)}`,
        permission: arm?.permission ?? tool.gate.permissions[0] ?? 'a permission this credential does not hold',
        write: arm !== undefined || tool.arms !== undefined,
      }) as CallToolResult;
    }
  });

  return server;
}

function describe(tool: ToolDefinition, session: Session): Tool {
  return {
    name: tool.name,
    title: tool.title,
    description: describeFor(tool, session.reach.permissions, session.reach.qualifiers),
    inputSchema: tool.inputSchema as Tool['inputSchema'],
    ...(tool.meta ? { _meta: tool.meta } : {}),
  };
}

/**
 * Which arm an input selected.
 *
 * Read off `action`, which every multi-arm tool takes as a required enum, so this needs no
 * per-tool knowledge. A single-arm write tool declares one arm with the action it does not take,
 * and falls through to it.
 */
export function armFor(tool: ToolDefinition, input: Record<string, unknown>): ToolArm | undefined {
  if (!tool.arms || tool.arms.length === 0) return undefined;
  if (tool.arms.length === 1) return tool.arms[0];
  const action = input['action'];
  return tool.arms.find((candidate) => candidate.action === action);
}
