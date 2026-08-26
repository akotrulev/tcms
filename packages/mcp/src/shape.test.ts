/**
 * `tcms_shape`, and the enriched schemas it exists beside.
 *
 * What is asserted here is mostly *reachability*: that the format arrives at a host through all
 * three carriers, and that the two which are cheap to get wrong — the schema a model reads at call
 * time, and the entity list — say the same thing as the catalogue. The catalogue's agreement with
 * `openapi.yaml` is `shapesContract.test.ts`'s question, one package over, and is not re-asked.
 */
import { describe, expect, it } from 'vitest';

import { CASE_SHAPE } from '@tcms/client';

import { shape } from './shape.js';
import { findTool } from './tools.js';
import { agentRoutes, api, sessionWith, withApi, type Api } from './test/harness.js';

async function run(stub: Api, input: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
  const session = await sessionWith(stub);
  return await withApi(stub, () => shape.run(session, input)) as Record<string, unknown>;
}

describe('tcms_shape', () => {
  it('needs no permission and no project, which is the whole reason it is a separate tool', async () => {
    // `tcms_orient` opens with `session.projectId()`. A credential that has connected and has not
    // resolved a project is exactly the caller who needs the format, so an empty gate is the
    // point rather than an oversight.
    expect(shape.gate.permissions).toEqual([]);
    const source = shape.run.toString();
    expect(source).not.toContain('projectId');
    expect(source).not.toContain('session.client');
  });

  it('lists every entity and marks the actions this surface does not expose', async () => {
    const answer = await run(api(agentRoutes())) as {
      entities: { entity: string; actions: { action: string; onThisSurface: boolean }[] }[];
    };

    expect(answer.entities.map((entity) => entity.entity)).toContain('case');
    const actions = answer.entities.find((entity) => entity.entity === 'case')!.actions;
    expect(actions.map((action) => action.action)).toEqual(['propose', 'edit', 'submit', 'delete']);
    // Every case action IS on this surface; the flag exists so that one that is not shows up
    // rather than leaving a model looking for it.
    expect(actions.every((action) => action.onThisSurface)).toBe(true);
  });

  it('answers one entity with a template and the long-form guide', async () => {
    const answer = await run(api(agentRoutes()), { entity: 'case' }) as {
      template: Record<string, unknown>; guide: string;
    };

    // The four the accept refuses without, and no more: a template carrying plausible optional
    // content is one a model files verbatim.
    expect(Object.keys(answer.template).sort())
      .toEqual(['automationStatus', 'priority', 'stepFormat', 'title']);

    // The pitfalls are the reason the guide exists — none of them fit in a tool schema.
    expect(answer.guide).toContain('ATTACHMENT ids');
    expect(answer.guide).toContain('COMPLETE set');
    expect(answer.guide).toContain('required when `stepFormat` is STRUCTURED');
  });

  it('refuses a mistyped entity as a failure, and names what does exist', async () => {
    // It used to answer through the success path, so a host rendered a failure as data and a model
    // read `{ error: … }` as a successful answer about a shape.
    await expect(run(api(agentRoutes()), { entity: 'testcase' })).rejects.toThrow(/testcase/);
    await expect(run(api(agentRoutes()), { entity: 'testcase' })).rejects.toThrow(/case/);
  });

  it('does not restate the vocabulary tcms_orient owns', async () => {
    // Two copies of a per-project vocabulary is one that goes stale the moment a project adds a
    // dimension. The description says which tool owns which half; this checks it stays true.
    const answer = await run(api(agentRoutes()), { entity: 'case' }) as { guide: string };
    expect(answer.guide).not.toContain('executionStatuses');
    expect(shape.description).toContain('tcms_orient');
  });
});

describe('the enriched write schema — the defect this slice was opened by', () => {
  it('declares the case body\'s properties rather than describing them in prose', () => {
    const writeCase = findTool('tcms_write_case')!;
    const testCase = writeCase.inputSchema.properties['testCase'] as {
      properties: Record<string, unknown>; required: string[];
    };

    /*
      It was `{ type: 'object' }` with no `properties` at all, and everything below was prose a
      model had to parse and then guess past. The step shape in particular was unobtainable: an
      agent could not know that a step is `{keyword, action, testData, expectedResult}` or that
      `action` is the only required one.
    */
    expect(Object.keys(testCase.properties).length).toBeGreaterThan(10);
    expect(testCase.required.sort())
      .toEqual(['automationStatus', 'priority', 'stepFormat', 'title']);

    const steps = testCase.properties['steps'] as { items: { properties: object; required: string[] } };
    expect(Object.keys(steps.items.properties).sort())
      .toEqual(['action', 'expectedResult', 'keyword', 'testData']);
    expect(steps.items.required).toEqual(['action']);
  });

  it('keeps a conditionally-required field out of `required`', () => {
    const writeCase = findTool('tcms_write_case')!;
    const testCase = writeCase.inputSchema.properties['testCase'] as { required: string[]; properties: Record<string, { description: string }> };

    // A host that validated `required: [steps]` would reject a GHERKIN case, which is legal. The
    // condition is stated in the description instead — the same trade `openapi.yaml` records
    // making and declining, for the same reason.
    expect(testCase.required).not.toContain('steps');
    expect(testCase.properties['steps']!.description).toContain('Required when stepFormat');
  });

  it('offers automationKey on the edit arm — the asymmetry the catalogue found', () => {
    /*
      The CLI's `case edit` took `--automation-key` and this surface did not, so an agent could
      set `automationStatus: AUTOMATED` and had no way to send the key the server requires
      alongside it. A guaranteed 400 that no test asked about, because both adapters were
      hand-maintained and nothing compared them.
    */
    const writeCase = findTool('tcms_write_case')!;
    expect(Object.keys(writeCase.inputSchema.properties)).toContain('automationKey');
    expect(CASE_SHAPE.fields.some((field) => field.name === 'automationKey')).toBe(true);
  });
});

describe('the shape resources', () => {
  it('serves one markdown guide per entity, gated on nothing', async () => {
    const { Client } = await import('@modelcontextprotocol/sdk/client/index.js');
    const { InMemoryTransport } = await import('@modelcontextprotocol/sdk/inMemory.js');
    const { buildServer } = await import('./server.js');

    const session = await sessionWith(api(agentRoutes()));
    const server = buildServer(session);
    const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: 'test-host', version: '0.0.0' });
    await Promise.all([
      server.connect(serverSide as Parameters<typeof server.connect>[0]),
      client.connect(clientSide as Parameters<typeof client.connect>[0]),
    ]);

    // Advertised without `listChanged`: the set is compiled into the build and cannot change
    // within a session, so claiming the notification would be a claim about behaviour that will
    // never occur.
    expect(client.getServerCapabilities()?.resources).toEqual({});

    const { resources } = await client.listResources();
    expect(resources.map((resource) => resource.uri)).toContain('tcms://shape/case');

    const read = await client.readResource({ uri: 'tcms://shape/case' });
    const content = read.contents[0];
    expect(content?.mimeType).toBe('text/markdown');
    // Narrowed rather than cast: the SDK's contents union has a `blob` arm, and a guide that
    // arrived as base64 would be a real defect rather than a typing inconvenience.
    expect(content && 'text' in content ? content.text : '').toContain('# Test case');
  });
});
