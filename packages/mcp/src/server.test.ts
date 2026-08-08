/**
 * The protocol, end to end — a real MCP client on one end of an in-memory transport pair, this
 * server on the other, and a stubbed TCMS underneath.
 *
 * The other test files assert what a tool *did*. This one asserts what an MCP host actually
 * receives, which is a different question and the one nothing else can answer: `tools/list` is
 * filtered, the instructions are carried on `initialize` and never re-sent, and a refusal has to
 * arrive as a **result** rather than as a protocol error or the model never reads its text.
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import { describe, expect, it } from 'vitest';

import { TcmsApiError, TcmsTimeoutError } from '@tcms/client';

import { failed } from './result.js';
import { buildServer } from './server.js';
import { AGENT_KEYS, agentRoutes, api, permissions, sessionWith, withApi, type Api } from './test/harness.js';

async function connect(stub: Api): Promise<Client> {
  const session = await sessionWith(stub);
  const server = buildServer(session);
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();

  const client = new Client({ name: 'test-host', version: '0.0.0' });
  await Promise.all([
    server.connect(serverSide as Parameters<typeof server.connect>[0]),
    client.connect(clientSide as Parameters<typeof client.connect>[0]),
  ]);
  return client;
}

// `automationStatus: 'NONE'` and `stepFormat: 'STEPS'` were here too, and neither is a value
// this API has — see `writes.test.ts`. A stub agrees with everything, so nothing noticed.
const CASE_ROW = {
  id: 'c-1', key: 'TC-42', keySeq: 42, title: 'Login', suiteId: null, suiteName: null,
  priority: 'HIGH', lifecycleStatus: 'APPROVED', automationStatus: 'MANUAL',
  stepFormat: 'STRUCTURED',
  owner: null, createdBy: null, features: [], tags: [], healthSignals: [],
  updatedAt: '2026-08-01T10:00:00Z',
};

/**
 * The detail the edit arm reads before it writes.
 *
 * Separate from the row because it must carry `steps`: `caseInputFrom` projects them and does
 * **not** default a missing array to `[]` — a server that omitted `steps` would otherwise have
 * every step on the case deleted by the next edit, silently.
 */
const CASE_DETAIL = {
  ...CASE_ROW,
  description: null, objective: null, expectedOutcome: null, severity: null,
  gherkinSource: null, automationKey: null, automationTags: [], notAutomatableReason: null,
  estimatedDurationSec: null, classificationValueIds: [], tags: [], steps: [],
  preconditions: [], postconditions: [], attachments: [], flake: null,
  currentVersionNo: 1, lockVersion: 1,
};

describe('initialize', () => {
  it('carries the capability summary in the server instructions', async () => {
    const stub = api(agentRoutes());
    const client = await connect(stub);

    const instructions = client.getInstructions() ?? '';
    expect(instructions).toContain('Ana Engineer');
    expect(instructions).toContain('OLB');
  });

  it('advertises the tool-list-changed capability it actually emits', async () => {
    const client = await connect(api(agentRoutes()));
    expect(client.getServerCapabilities()?.tools).toEqual({ listChanged: true });
  });
});

describe('tools/list', () => {
  it('serves the whole surface to a credential that holds the Agent grant', async () => {
    const client = await connect(api(agentRoutes()));
    const { tools } = await client.listTools();

    expect(tools.map((tool) => tool.name)).toContain('tcms_write_case');
    // Not everything: the Agent grant holds no `run:close`, so nothing gated on it exists at all.
    expect(tools.every((tool) => tool.name.startsWith('tcms_'))).toBe(true);
  });

  it('hides what this credential cannot use, and says nothing about why', async () => {
    const client = await connect(api(agentRoutes({
      '/me/permissions': { status: 200, body: permissions(['project:read', 'case:read']) },
    })));
    const { tools } = await client.listTools();
    const names = tools.map((tool) => tool.name);

    expect(names).toContain('tcms_search_cases');
    expect(names).not.toContain('tcms_write_case');
    expect(names).not.toContain('tcms_doc_tree');
    // Always present, whatever else is: it is how the user finds out what is missing.
    expect(names).toContain('tcms_whoami');
  });

  it('carries the two _meta keys this surface uses, and only on the tools that earn them', async () => {
    const client = await connect(api(agentRoutes()));
    const { tools } = await client.listTools();

    const whoami = tools.find((tool) => tool.name === 'tcms_whoami');
    expect(whoami?._meta?.['anthropic/alwaysLoad']).toBe(true);

    const raised = tools.filter((tool) => tool._meta?.['anthropic/maxResultSizeChars']);
    expect(raised.map((tool) => tool.name).sort()).toEqual(['tcms_open_case', 'tcms_open_doc_page']);
  });
});

describe('tools/call', () => {
  it('answers a read as JSON text the model can quote into the next call', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 25, total: 1 } },
    }));
    const client = await connect(stub);

    const result = await withApi(stub, () =>
      client.callTool({ name: 'tcms_search_cases', arguments: { q: 'login' } }));

    expect(result.isError).toBeFalsy();
    const text = (result.content as { type: string; text: string }[])[0]?.text ?? '';
    expect(JSON.parse(text).cases[0].key).toBe('TC-42');
  });

  it('returns a refusal as a result, not as a protocol error', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 403, body: { title: 'Forbidden', status: 403, detail: 'You do not have permission to do this.' } },
    }));
    const client = await connect(stub);

    /*
      The distinction is the whole reason `result.ts` exists: a JSON-RPC error means *the call
      could not be made*, and the model never reads its text as something to reason about. A 403
      is the opposite — the server answered, and the answer is what the user should be told.
    */
    const result = await withApi(stub, () =>
      client.callTool({ name: 'tcms_search_cases', arguments: {} }));

    expect(result.isError).toBe(true);
    expect((result.content as { text: string }[])[0]?.text).toContain('case:read');
  });

  it('refuses a hidden tool without confirming that it exists', async () => {
    const stub = api(agentRoutes({
      '/me/permissions': { status: 200, body: permissions(['project:read', 'case:read']) },
    }));
    const client = await connect(stub);

    const result = await withApi(stub, () =>
      client.callTool({ name: 'tcms_write_case', arguments: { action: 'edit', case: 'TC-1' } }));

    expect(result.isError).toBe(true);
    const text = (result.content as { text: string }[])[0]?.text ?? '';
    // A hidden tool that answered "you lack case:edit" would restore exactly the enumeration
    // hiding it removes — the model would learn the tool exists.
    expect(text).not.toContain('case:edit');
    expect(text).toContain('is available');
  });

  it('names the permission of the ARM that ran, not the tool\'s first gate key', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: { items: [CASE_ROW], page: 0, size: 1, total: 1 } },
      'GET /cases/c-1': { status: 200, body: CASE_DETAIL },
      'PATCH /cases/c-1': { status: 403, body: { title: 'Forbidden', status: 403, detail: 'You do not have permission to do this.' } },
    }));
    const client = await connect(stub);

    const result = await withApi(stub, () =>
      client.callTool({ name: 'tcms_write_case', arguments: { action: 'edit', case: 'TC-42', title: 'x' } }));

    const text = (result.content as { text: string }[])[0]?.text ?? '';
    // The tool's gate is [case:create, case:edit]; the edit arm was refused, so `case:edit` is
    // what the user takes to an administrator. Naming `case:create` would send them for the
    // wrong thing.
    expect(text).toContain('case:edit');
    expect(text).not.toContain('case:create');
  });
});

describe('refusal copy — surface spec §8.4', () => {
  /*
    The one test whose failure is a security finding rather than a defect. §6t's rule is that an
    unreachable project and a non-existent one answer identically, so the 404 branch must name
    neither a permission nor a role — a message saying "you lack case:read on this" would confirm
    the row exists to a caller in another company.
  */
  it('names nothing on the 404 branch', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 404, body: { title: 'Not found', status: 404, detail: 'No such case.' } },
    }));
    const client = await connect(stub);

    const result = await withApi(stub, () =>
      client.callTool({ name: 'tcms_search_cases', arguments: {} }));

    const text = (result.content as { text: string }[])[0]?.text ?? '';
    for (const key of [...AGENT_KEYS, 'Test Engineer', 'Test Lead', 'Company Admin', 'Viewer', 'Agent']) {
      expect(text, `404 copy mentioned ${key}`).not.toContain(key);
    }
    expect(text).toContain('not visible to this token');
  });

  it('tells a model a timed-out WRITE may have happened, and says nothing of the sort for a read', () => {
    const timeout = new TcmsTimeoutError('PATCH', '/cases/c-1', 15_000);

    const write = failed(timeout, { what: 'edit this case', permission: 'case:edit', write: true });
    expect(write.content[0]?.text).toContain('may or may not have happened');

    // A read that timed out changed nothing, and telling a model otherwise invites it to go and
    // check a resource it never wrote to.
    const read = failed(timeout, { what: 'read this case', permission: 'case:read', write: false });
    expect(read.content[0]?.text).not.toContain('may or may not');
  });

  it('names the fields a validation rejection refused, because the model cannot see a screen', () => {
    /*
      The live pass's finding. `detail` on a 400 is "Check the highlighted fields." — copy written
      for a form — and the fields themselves are in `errors`, which only the CLI was reading. A
      model given the sentence alone has been told to look at something it does not have, and its
      next move is to resend the same body.
    */
    const rejected = new TcmsApiError({
      status: 400,
      detail: 'Check the highlighted fields.',
      method: 'PATCH',
      path: '/cases/c-1',
      fieldErrors: {
        stepFormat: ['Choose a step format.'],
        automationStatus: ['Choose an automation status.'],
      },
    });

    const text = failed(rejected, { what: 'edit this case', permission: 'case:edit', write: true })
      .content[0]?.text ?? '';

    expect(text).toContain('stepFormat: Choose a step format.');
    expect(text).toContain('automationStatus: Choose an automation status.');
  });

  it('appends nothing when the refusal carries no field errors', () => {
    // A 403 has no `errors` map, and a trailing blank line would read as a truncated answer.
    const refused = new TcmsApiError({
      status: 403, detail: 'no', method: 'POST', path: '/cases',
    });

    expect(failed(refused, { what: 'x', permission: 'case:edit', write: true }).content[0]?.text)
      .not.toContain('\n');
  });

  it('does not report a cancellation as a failure', () => {
    const cancelled = Object.assign(new Error('aborted'), { name: 'AbortError' });
    const result = failed(cancelled, { what: 'read this case', permission: 'case:read', write: false });
    expect(result.content[0]?.text).toBe('Cancelled.');
  });
});
