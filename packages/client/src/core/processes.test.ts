/**
 * The process catalogue against the surface it describes.
 *
 * A process is only worth serving if every step of it can be taken, so the check that matters is
 * that each named tool and each named command exists. **It is pinned against a list written here
 * rather than imported from `@tcms/mcp`**, and that is a build fact rather than a preference: the
 * adapters depend on this package and nothing in this package may depend on them, so importing the
 * registry would be a cycle in the workspace and would put `client`'s typecheck behind `mcp`'s
 * build. The list below is the client's half; T3b adds the cross-check on the `mcp` side, where
 * the registry is, so a tool renamed there fails against the real thing.
 */
import { describe, expect, it } from 'vitest';

import { PROCESSES, processCatalogue, processGuideFor, processNamed } from './processes.js';
import { specRows } from '../test/roleMatrix.js';

/**
 * The tools the processes reach, as of the surface's 44 (45 once `tcms_process` lands).
 *
 * Deliberately not "every tool that exists" — this is the reachable set a process may name, and a
 * step naming something outside it is either a typo or a capability nobody checked was there.
 */
const TOOLS = [
  'tcms_whoami',
  'tcms_orient',
  'tcms_shape',
  'tcms_open_doc_page',
  'tcms_write_doc_page',
  'tcms_write_case',
  'tcms_write_plan',
  'tcms_report',
];

/** The CLI commands the processes reach. `findCommand` resolves three words, then two, then one. */
const COMMANDS = [
  'whoami',
  'format',
  'doc create',
  'doc edit',
  'doc show',
  'doc adopt',
  'case propose',
  'plan create',
  'plan status',
];

describe('the process catalogue', () => {
  it('names five processes, each findable by its own name', () => {
    expect(PROCESSES.map((process) => process.name)).toEqual([
      'ingest-spec', 'cover-spec', 'plan-spec', 'revise-spec', 'report-spec',
    ]);

    for (const process of PROCESSES) {
      expect(processNamed(process.name)).toBe(process);
    }
    expect(processNamed('no-such-process')).toBeUndefined();
  });

  it('names only tools this surface has', () => {
    for (const process of PROCESSES) {
      for (const step of process.steps) {
        if (!step.tool) continue;
        // A step spells the call, not just the tool — `tcms_open_doc_page {as: "markdown"}`.
        const name = step.tool.split(/[\s{]/)[0]!;
        expect(TOOLS, `${process.name}: ${step.tool}`).toContain(name);
      }
    }
  });

  it('names only permissions the roles spec catalogues', () => {
    // A step's `permission` is the sentence an agent hands to an administrator when it is refused,
    // so a key the catalogue does not hold is an ask nobody can grant. The first cut named
    // `report:generate` — a plausible spelling of a key that has never existed (the report tool's
    // arms cost `report:view` and `report:export`) — and nothing here read it until this did.
    const catalogued = new Set(specRows().flatMap((row) => row.keys));
    for (const process of PROCESSES) {
      for (const step of process.steps) {
        if (!step.permission) continue;
        expect(catalogued, `${process.name}: '${step.permission}' is not in §A3`)
          .toContain(step.permission);
      }
    }
  });

  it('names only commands this surface has', () => {
    for (const process of PROCESSES) {
      for (const step of process.steps) {
        if (!step.cli) continue;
        expect(step.cli.startsWith('tcms '), `${process.name}: ${step.cli}`).toBe(true);
        const words = step.cli.split(' ').slice(1);
        const candidates = [words.slice(0, 3), words.slice(0, 2), words.slice(0, 1)]
          .map((parts) => parts.join(' '));
        expect(
          candidates.some((candidate) => COMMANDS.includes(candidate)),
          `${process.name}: '${step.cli}' resolves to no command`,
        ).toBe(true);
      }
    }
  });

  it('gives every step something to read before the next one', () => {
    for (const process of PROCESSES) {
      expect(process.steps.length, process.name).toBeGreaterThan(2);
      for (const step of process.steps) {
        expect(step.does.length, `${process.name}: ${step.does}`).toBeGreaterThan(20);
        expect(step.check.length, `${process.name}: ${step.does}`).toBeGreaterThan(20);
      }
    }
  });

  it('states the permission a step costs wherever it costs one', () => {
    for (const process of PROCESSES) {
      for (const step of process.steps) {
        if (!step.permission) continue;
        expect(step.permission, `${process.name}: ${step.permission}`).toMatch(/^[a-z]+:[a-z]+$/);
      }
    }
  });

  it('ends every process before the act that is a person\'s', () => {
    /*
      The product's rule is structural: nothing generated becomes a test case, no page publishes
      itself and no plan approves itself. A process is the one artifact that could quietly cross
      that line by listing one more step, so the line is asserted rather than remembered.
    */
    const forbidden = /"?(publish|accept|APPROVED)"?/;
    for (const process of PROCESSES) {
      for (const step of process.steps) {
        expect(
          forbidden.test(step.tool ?? '') || forbidden.test(step.cli ?? ''),
          `${process.name}: ${step.tool ?? step.cli}`,
        ).toBe(false);
      }
    }
  });
});

describe('the renderers', () => {
  it('renders a process as Markdown with its arguments, steps, checks and traps', () => {
    const guide = processGuideFor(processNamed('ingest-spec')!);

    expect(guide).toContain('# Turn a client specification into a documentation page');
    expect(guide).toContain('## Arguments');
    expect(guide).toContain('- `spec` **required**');
    expect(guide).toContain('### 1.');
    expect(guide).toContain('- MCP: `tcms_whoami`');
    expect(guide).toContain('- Costs `doc:edit`');
    expect(guide).toContain('- Trap:');
  });

  it('says so where the CLI has no command for a step, rather than leaving it looking complete', () => {
    const guide = processGuideFor(processNamed('report-spec')!);
    expect(guide).toContain('no command reaches this step; it is MCP-only.');
  });

  it('lists every process in the catalogue, so a sixth cannot arrive unserved', () => {
    const catalogue = processCatalogue();
    for (const process of PROCESSES) {
      expect(catalogue).toContain(`**${process.name}**`);
      expect(catalogue).toContain(process.summary);
    }
  });
});
