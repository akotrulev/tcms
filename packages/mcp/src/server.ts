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
import { annotationsFor, describeFor, isListed, type ToolArm, type ToolDefinition } from './tool.js';
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

    if (arm?.kind === 'delete') {
      const refusal = await withheldConsent(server, tool, arm, input);
      if (refusal) return refusal;
    }

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
    annotations: annotationsFor(tool) as Tool['annotations'],
    ...(tool.meta ? { _meta: tool.meta } : {}),
  };
}

/**
 * Ask the user before removing something. Returns the refusal, or `undefined` to proceed.
 *
 * ## Why this exists, and what it is standing in for
 *
 * Until 2026-08-15 an agent credential could not delete at all: the `Agent` role withheld
 * `case:delete`, `evidence:delete` and `plan:delete`, and the roles spec's argument for putting
 * the ceiling in the *catalogue* was that a ceiling enforced at the tool layer controls what an
 * agent reaches **for**, not what the credential can **do**. `V52` (§210) made `Agent` equal to
 * Test Engineer and moved the gate here, over that objection — see roles spec §A6a, where it is
 * struck through as superseded rather than as wrong.
 *
 * So this is the whole of the remaining gate, and it is honest about its reach:
 *
 *   - it binds the **MCP transport only**. The `tcms_` bearer authenticates against `/api/*`, so
 *     the same credential driven by `curl`, the CLI, or any other MCP client deletes with nothing
 *     to ask. What still bounds it is the token intersection — an agent never exceeds the person
 *     who issued it — plus soft delete, the audit trail, the required `expiresAt` and Revoke.
 *   - it **refuses rather than proceeds** when the client declares no `elicitation` capability.
 *     That is the decision worth defending: a consent gate that silently degrades to "no prompt
 *     available, carry on" is not a gate, and it would fail open on exactly the minimal client an
 *     unattended agent is most likely to be. A host that wants agent deletes implements
 *     elicitation, which is a one-time cost paid by the integrator rather than a risk carried by
 *     the client's data.
 *   - a decline is a **result, not an error**. The model asked, a person said no, and that is a
 *     normal outcome of a well-behaved run — `isError` here would push the model to retry.
 *
 * The gate keys on {@link ToolArm.kind} rather than on a list of tool names, so a delete arm added
 * to any tool later is covered by construction — which §211 then relied on: three arms arrived and
 * not one line here changed. ~~Today the surface has exactly one: `tcms_write_doc_page`'s
 * `unlink` … the five delete keys `V52` gave the credential have no MCP tools at all yet.~~ →
 * **four arms now, over three of the five keys.** `evidence:delete` and `artifact:delete` are still
 * unreachable from this surface and for stated reasons rather than by omission; see
 * `writes.ts`'s module header.
 */
async function withheldConsent(
  server: Server,
  tool: ToolDefinition,
  arm: ToolArm,
  input: Record<string, unknown>,
): Promise<CallToolResult | undefined> {
  const what = `${tool.title.charAt(0).toLowerCase()}${tool.title.slice(1)} (${arm.action})`;
  const target = identifying(arm, input);

  if (!server.getClientCapabilities()?.elicitation) {
    return {
      content: [{
        type: 'text',
        text: `Refused: this client cannot ask for confirmation, and deletions on this server `
          + `require it. Nothing was ${arm.action === 'unlink' ? 'unlinked' : 'deleted'}.\n\n`
          + `Asked for: ${what}${target ? ` — ${target}` : ''}.\n\n`
          + `A host that supports MCP elicitation will prompt the user and proceed on approval. `
          + `Everything else this credential can do is unaffected.`,
      }],
      isError: true,
    };
  }

  const answer = await server.elicitInput({
    message: `Confirm deletion — ${what}${target ? `\n\n${target}` : ''}`,
    requestedSchema: {
      type: 'object',
      properties: {
        confirm: {
          type: 'boolean',
          title: 'Delete this?',
          description: 'This removes the item. It is a soft delete and is recorded in the audit trail.',
        },
      },
      required: ['confirm'],
    },
  });

  if (answer.action === 'accept' && answer.content?.['confirm'] === true) return undefined;

  return {
    content: [{
      type: 'text',
      text: `Not confirmed — nothing was ${arm.action === 'unlink' ? 'unlinked' : 'deleted'}. `
        + `Do not retry this without being asked to.`,
    }],
  };
}

/**
 * The fields that tell a person *which* row is about to go.
 *
 * A confirmation naming only the operation is one a user cannot answer, so the prompt echoes the
 * identifying arguments back — read off {@link ToolArm.targets}, which the arm declares.
 *
 * ~~Restricted to a known list rather than dumping the whole input.~~ → **the list was right about
 * the danger and wrong about every field name (§211).** It named `caseKey`, `planKey`, `suiteKey`,
 * `runKey`, `slug`, `id`, `targetId` and `projectKey`; this surface's arguments are `case`,
 * `plan`, `suite`, `run`, `page`, `link`. Not one of the eight is declared by any tool here, so
 * `unlink` — the only delete arm there was — prompted with an empty target for as long as the gate
 * existed. The restriction stands and its reason is unchanged; what changed is that the arm now
 * says which fields those are, and a test fails when it does not.
 */
function identifying(arm: ToolArm, input: Record<string, unknown>): string {
  return (arm.targets ?? [])
    .filter((field) => typeof input[field] === 'string' || typeof input[field] === 'number')
    .map((field) => `${field}: ${String(input[field])}`)
    .join(', ');
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
