/**
 * `tcms_process`, and the four carriers the catalogue reaches a caller through.
 *
 * `shape.test.ts`'s question, asked of the other catalogue: **is the sequence reachable**, and does
 * every step name something this surface actually has. The catalogue's own content — the five, what
 * each step says — is `client/src/core/processes.test.ts`'s; the cross-check that no step invents a
 * tool is here, because this is the package that knows what the registry holds.
 */
import { describe, expect, it } from 'vitest';

import { PROCESSES } from '@tcms/client';

import { process } from './process.js';
import { findTool, TOOLS } from './tools.js';
import { agentRoutes, api, sessionWith, withApi, type Api } from './test/harness.js';

async function run(stub: Api, input: Record<string, unknown> = {}): Promise<Record<string, unknown>> {
  const session = await sessionWith(stub);
  return await withApi(stub, () => process.run(session, input)) as Record<string, unknown>;
}

describe('tcms_process', () => {
  it('needs no permission and no project, on tcms_shape\'s reasoning', async () => {
    expect(process.gate.permissions).toEqual([]);
    const source = process.run.toString();
    expect(source).not.toContain('projectId');
    expect(source).not.toContain('session.client');
  });

  it('answers the catalogue when asked for no process in particular', async () => {
    const answer = await run(api(agentRoutes())) as {
      processes: { process: string; steps: number }[];
    };

    expect(answer.processes.map((entry) => entry.process))
      .toEqual(['ingest-spec', 'cover-spec', 'plan-spec', 'revise-spec', 'report-spec']);
    expect(answer.processes.every((entry) => entry.steps > 0)).toBe(true);
  });

  it('serves the long guide for one, with its arguments', async () => {
    const answer = await run(api(agentRoutes()), { process: 'ingest-spec' }) as {
      arguments: { name: string; required: boolean }[]; guide: string;
    };

    expect(answer.arguments.filter((argument) => argument.required).map((argument) => argument.name))
      .toEqual(['spec', 'title']);
    expect(answer.guide).toContain('tcms_write_doc_page');
    expect(answer.guide).toContain('Trap:');
  });

  it('refuses a name it does not have, and says what it does have', async () => {
    /*
      A throw rather than a result carrying an `error` key. The `enum` on the input means a
      validating host refuses this first, and `input.ts`'s rule is that a server trusting a peer's
      validation is trusting a peer it did not write.
    */
    await expect(run(api(agentRoutes()), { process: 'invent-spec' }))
      .rejects.toThrow(/ingest-spec/);
  });

  it('names no tool this server does not serve', () => {
    /*
      The cross-check `core/processes.ts` could not make from its own package: a step naming a tool
      the registry does not have is a dead end a model would follow, and the whole argument for a
      computed catalogue is that such a step fails the build instead. The tool is read out of the
      step's spelling, which carries its arguments — `tcms_write_doc_page {action: "create", …}`.
    */
    const named = PROCESSES.flatMap((candidate) => candidate.steps)
      .flatMap((step) => step.tool?.match(/\btcms_[a-z_]+\b/g) ?? []);
    expect(named.length).toBeGreaterThan(0);

    for (const name of new Set(named)) {
      expect(findTool(name), `${name} is named by a process step and is in no registry`).toBeDefined();
    }
  });

  it('is in the registry, which is what makes it reachable from a minimal host', () => {
    // The resource and the prompts are optional client capabilities; `tools/list` is not.
    expect(TOOLS.map((candidate) => candidate.name)).toContain('tcms_process');
  });
});
