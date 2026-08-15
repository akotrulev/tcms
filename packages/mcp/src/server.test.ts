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
import { ElicitRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import { describe, expect, it } from 'vitest';

import { TcmsApiError, TcmsTimeoutError } from '@tcms/client';

import { caseDetail, casePage, caseSummary, docPage, docPageSummary } from '../../client/src/test/fixtures.js';
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
const CASE_ROW = caseSummary({
  title: 'Login',
  suiteId: null,
  suiteName: null,
  lifecycleStatus: 'APPROVED',
  updatedAt: '2026-08-01T10:00:00Z',
});

/**
 * The detail the edit arm reads before it writes.
 *
 * Separate from the row because it must carry `steps`: `caseInputFrom` projects them and does
 * **not** default a missing array to `[]` — a server that omitted `steps` would otherwise have
 * every step on the case deleted by the next edit, silently.
 */
const CASE_DETAIL = caseDetail({ ...CASE_ROW, severity: null });

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
    // ~~Not everything: the Agent grant holds no `run:close`.~~ → §210: it does. The grant is Test
    // Engineer entire now, so every tool on this surface is listed for it and the interesting
    // filtering case is the reduced credential in the next test.
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

  it('marks the tools that remove something, because that is what makes a host prompt', async () => {
    const client = await connect(api(agentRoutes()));
    const { tools } = await client.listTools();

    /*
      The declaration half of the gate §210 moved here from the catalogue. Asserted as an exact set
      rather than "the doc-page tool is destructive", because the failure that matters is a delete
      arm added to some other tool and NOT annotated — which is invisible from the tool it was added
      to and shows up here as a name this list does not have.

      One name today, and that is not a measure of the change: the five delete keys V52 gave the
      credential have no MCP tools yet, so they are reachable over REST and the CLI and not through
      this surface at all.
    */
    expect(tools.filter((tool) => tool.annotations?.destructiveHint).map((tool) => tool.name))
      .toEqual(['tcms_write_doc_page']);

    const search = tools.find((tool) => tool.name === 'tcms_search_cases');
    expect(search?.annotations?.readOnlyHint).toBe(true);
    expect(search?.annotations?.destructiveHint).toBe(false);

    // A create arm allocates, so re-sending is not a no-op — which is why those arms carry
    // `Idempotency-Key`. The annotation must agree with the header the arm sends.
    expect(tools.find((tool) => tool.name === 'tcms_write_case')?.annotations?.idempotentHint)
      .toBe(false);
  });
});

describe('consent before a delete — §210', () => {
  /** A host that can be asked, answering however the test says. */
  async function hostThatAnswers(stub: Api, answer: 'accept' | 'decline'): Promise<Client> {
    const session = await sessionWith(stub);
    const server = buildServer(session);
    const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();

    const client = new Client(
      { name: 'test-host', version: '0.0.0' },
      { capabilities: { elicitation: {} } },
    );
    client.setRequestHandler(ElicitRequestSchema, () =>
      answer === 'accept'
        ? { action: 'accept' as const, content: { confirm: true } }
        : { action: 'decline' as const });

    await Promise.all([
      server.connect(serverSide as Parameters<typeof server.connect>[0]),
      client.connect(clientSide as Parameters<typeof client.connect>[0]),
    ]);
    return client;
  }

  const UNLINK = {
    name: 'tcms_write_doc_page',
    arguments: { action: 'unlink', page: 'DOC-1', link: 'l-1' },
  };

  const ROUTES = agentRoutes({
    'GET /doc-pages': { status: 200, body: [docPageSummary({ key: 'DOC-1', title: 'Spec' })] },
    'DELETE /links/l-1': { status: 204 },
  });

  it('deletes once a person has said yes', async () => {
    const stub = api(ROUTES);
    const client = await hostThatAnswers(stub, 'accept');

    const result = await withApi(stub, () => client.callTool(UNLINK));

    expect(result.isError).toBeFalsy();
    expect(stub.sent.some((request) => request.method === 'DELETE')).toBe(true);
  });

  it('sends nothing when the person declines, and does not report it as an error', async () => {
    const stub = api(ROUTES);
    const client = await hostThatAnswers(stub, 'decline');

    const result = await withApi(stub, () => client.callTool(UNLINK));

    /*
      Two assertions and the second is the one with teeth. A decline is a NORMAL outcome — the model
      asked, a person said no — so flagging `isError` would read to the model as a fault to work
      around, and the obvious way around a failed delete is to try it again.
    */
    expect(stub.sent.some((request) => request.method === 'DELETE')).toBe(false);
    expect(result.isError).toBeFalsy();
    expect((result.content as { text: string }[])[0]?.text).toContain('Not confirmed');
  });

  it('refuses outright when the client cannot ask — it does not fall through to the delete', async () => {
    const stub = api(ROUTES);
    // `connect` builds a client declaring no elicitation capability, which is the default.
    const client = await connect(stub);

    const result = await withApi(stub, () => client.callTool(UNLINK));

    /*
      THE decision in this gate, and the one to read before relaxing it. A consent gate that
      degrades to "no prompt available, carry on" is not a gate — and it would fail open on exactly
      the minimal client an unattended agent is most likely to be running. Refusing costs an
      integrator one capability declaration; falling through costs the client their data.

      This is also the honest limit of §210's design. The gate binds the MCP transport and nothing
      else: the same `tcms_` bearer deletes over `curl` with nobody to ask. See roles spec §A6a,
      where the objection is recorded as superseded rather than as wrong.
    */
    expect(stub.sent.some((request) => request.method === 'DELETE')).toBe(false);
    expect(result.isError).toBe(true);
    expect((result.content as { text: string }[])[0]?.text).toContain('cannot ask for confirmation');
  });

  it('asks for nothing on a write that is not a delete', async () => {
    const stub = api(agentRoutes({
      'POST /doc-pages': { status: 201, body: docPage({ key: 'DOC-2', title: 'New' }) },
    }));
    // No elicitation handler is installed, so a prompt raised here would fail the call rather than
    // hang — which is the point: the gate must key on the ARM, not on the tool.
    const client = await connect(stub);

    const result = await withApi(stub, () =>
      client.callTool({ name: 'tcms_write_doc_page', arguments: { action: 'create', title: 'New' } }));

    expect(result.isError).toBeFalsy();
  });
});

describe('tools/call', () => {
  it('answers a read as JSON text the model can quote into the next call', async () => {
    const stub = api(agentRoutes({
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 25 }) },
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
      '/cases': { status: 200, body: casePage([CASE_ROW], { size: 1 }) },
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
