/**
 * `tcms_process` — the sequence, as a tool. `tcms_shape`'s argument applied to a whole process.
 *
 * Carrier 1 of four (`docs/specs/2026-09-07-agent-specification-formatter.md` §3.1). The other
 * three are the `tcms://process/{name}` resource, the MCP prompts, and the CLI's `tcms process`.
 * This one exists for the host that surfaces neither resources nor prompts: those are optional
 * client capabilities and `tools/list` is not, so a minimal host would otherwise reach the
 * sequence only through the one sentence in the server's `instructions`.
 *
 * ## No gate, no project, no endpoint — `tcms_shape`'s three reasons, unchanged
 *
 * It calls nothing, so §3.1's composite rule is not engaged; it needs no project, so it is
 * callable before one is resolved, which is exactly when a model needs to know what the sequence
 * is; and it carries no gate, because a process description is the same document whatever a
 * credential holds. Each step still names the permission it costs, so a caller reads the refusal
 * it is about to get instead of discovering it three steps in.
 *
 * The division of labour with `tcms_shape` is the one that file already states: **the shape
 * catalogue owns what an object looks like and this owns the order they are written in.** Neither
 * repeats the other, and the steps name the tools rather than describing them.
 */
import { PROCESSES, processCatalogue, processGuideFor, processNamed } from '@tcms/client';

import { InputError } from './input.js';
import type { ToolDefinition } from './tool.js';

const NAMES = PROCESSES.map((process) => process.name);

export const process: ToolDefinition = {
  name: 'tcms_process',
  title: 'Get the ordered steps for a job this surface is meant to do',
  description:
    'The sequence — which tool, in what order, what to read in the answer before the next call, '
    + `and the trap at each step. Processes: ${NAMES.join(', ')}. Call it before a multi-step job: `
    + 'a specification becomes a page (ingest-spec), its statements get proposed cases '
    + '(cover-spec), a plan adopts it (plan-spec), it is revised without breaking its links '
    + '(revise-spec), and its coverage is reported (report-spec). It reads nothing and needs no '
    + 'project, so it is safe to call first. Every process ENDS where a person\'s act begins — '
    + 'nothing here publishes a page, accepts a proposal or approves a plan, and no step will tell '
    + 'you to. tcms_shape is the other half: this is the order, that is what each object looks '
    + 'like.',
  gate: { mode: 'all', permissions: [] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      process: {
        type: 'string',
        enum: [...NAMES],
        description: 'Which one. Omit for the catalogue — one line each.',
      },
    },
  },
  // A process guide is one authored document, and a truncated one is worse than none: the traps
  // are at the end of each step. `tcms_shape` raised the same ceiling for the same reason.
  meta: { 'anthropic/maxResultSizeChars': 200_000 },
  run: async (_session, input) => {
    const requested = typeof input['process'] === 'string' ? input['process'] : undefined;

    if (requested === undefined) {
      return {
        processes: PROCESSES.map((candidate) => ({
          process: candidate.name,
          title: candidate.title,
          summary: candidate.summary,
          steps: candidate.steps.length,
        })),
        catalogue: processCatalogue(),
        note: 'Call tcms_process with a name for its steps, and tcms_shape for what each object '
          + 'in them looks like.',
      };
    }

    const found = processNamed(requested);
    /*
      A throw rather than an `ok()` carrying an `error` key — `tcms_shape` records what the other
      shape cost: a host renders a result with no `isError` as data, and a model reads an object
      with an `error` key as a successful answer. The `enum` above means a validating host refuses
      this first, and this server does not rely on a peer it did not write.
    */
    if (!found) throw new InputError(`No process named '${requested}'. There is: ${NAMES.join(', ')}.`);

    return {
      process: found.name,
      title: found.title,
      summary: found.summary,
      arguments: found.arguments,
      guide: processGuideFor(found),
    };
  },
};
