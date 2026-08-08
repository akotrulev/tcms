/**
 * The write commands — plan phase 7, *edits direct, creates as proposals*.
 *
 * Three rules hold across all of them and none of them is implemented here:
 *
 *   - a **create** carries `Idempotency-Key`, because an agent retries immediately and a retried
 *     create that in fact succeeded must not make a second thing;
 *   - an **edit** carries `If-Match`, because omitting it is last-write-wins and this surface has
 *     no person looking at the screen it is overwriting;
 *   - a **state** transition carries neither, because its precondition is the state rather than a
 *     version the client read — the contract says so on each of the three.
 *
 * They live in `@tcms/client`'s `core/mutation.ts` so the CLI and the MCP server cannot disagree
 * about them, and `writeConformance.test.ts` asserts each command actually sent what its
 * declaration implies.
 *
 * ## `case create` is absent, and the absence is the feature
 *
 * §1.3 of the MCP plan is settled: **nothing generated writes to `test_case` without a human
 * accepting it.** So the create arm is `case propose`, which files an `ai_proposal` and creates
 * nothing. `case accept` is absent too, and for a stronger reason than policy: §138's gate is
 * `case:create` **and no `TokenContext`**, and every request this CLI makes carries a token — an
 * accept command would 403 on every invocation it could ever make. It is the one arm this surface
 * is structurally incapable of, rather than one it declines to offer.
 */
import {
  createIntent,
  editIntent,
  requireExplicitVersion,
  resolveId,
  type EditVersion,
  type components,
} from '@tcms/client';

import {
  first,
  flag,
  objectFrom,
  projectOf,
  requireArg,
  requireOption,
  requireSomethingToDo,
  strictNumber,
  all,
  type Command,
  type CommandContext,
} from './context.js';
import { fields, show, type Output } from './output.js';

type TestCaseInput = components['schemas']['TestCaseInput'];

/* ------------------------------------------------------------------ helpers ---- */

/**
 * A nullable text field, with the three states the contract distinguishes spelled as two flags'
 * worth of shell syntax.
 *
 * `--description` absent leaves the field alone, `--description ''` clears it, and any other
 * value sets it. The empty string is the clearing form because a shell has no way to type `null`
 * and because the alternative — a `--clear-description` beside every nullable field — is a flag
 * per field on a schema with nine of them. The contract's absent/null split is the whole reason
 * this needs saying: absent and null are different requests, and a client that collapsed them
 * would empty fields nobody mentioned.
 */
function nullableText(
  value: string | boolean | (string | boolean)[] | undefined,
): string | null | undefined {
  const text = first(value);
  if (text === undefined) return undefined;
  return text === '' ? null : text;
}

function text(value: string | boolean | (string | boolean)[] | undefined): string | undefined {
  return first(value);
}

/** Drops the keys nobody named, so a `PATCH` asks for exactly what the flags said. */
function present<T extends object>(body: T): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(body).filter(([, value]) => value !== undefined),
  );
}

/**
 * Where an edit's `lockVersion` comes from — the slice's one real decision, applied per command.
 *
 * A flag edit may read its own version: the flags *are* the whole intent, `PATCH` leaves every
 * unnamed field alone, and a version read immediately before the write is a truthful precondition.
 * A `--file` edit may not, because its body was composed from an **earlier** read and a fresh
 * version would assert a state that body was never based on. `core/mutation.ts` carries the
 * argument in full.
 */
async function versionFor(
  context: CommandContext,
  options: { fromFile: boolean; read: () => Promise<unknown>; operation: string },
): Promise<EditVersion> {
  const explicit = strictNumber(context.options['lock-version'], '--lock-version');
  if (explicit !== undefined) return { explicit };
  if (options.fromFile) return requireExplicitVersion(undefined, options.operation);
  return { fresh: await options.read() };
}

/** What a write prints when it worked: the key, and the fact that something happened. */
function reportWrite(out: Output, json: boolean, result: unknown, lines: readonly (readonly [string, string])[]): void {
  if (json) {
    out.json(result);
    return;
  }
  fields(out, lines);
}

/* ------------------------------------------------------------------- cases ---- */

const caseEdit: Command = {
  name: 'case edit',
  summary: 'Change a test case',
  usage:
    'tcms case edit TC-42 [--title T] [--description D] [--objective O] [--expected-outcome E]\n'
    + '                    [--priority P] [--severity S] [--automation-status S] [--suite TS-3]\n'
    + '                    [--unfile] [--tag T]... [--file BODY.json --lock-version N]',
  options: {
    title: { type: 'string' },
    description: { type: 'string' },
    objective: { type: 'string' },
    'expected-outcome': { type: 'string' },
    priority: { type: 'string' },
    severity: { type: 'string' },
    'automation-status': { type: 'string' },
    'automation-key': { type: 'string' },
    suite: { type: 'string' },
    unfile: { type: 'boolean' },
    tag: { type: 'string', multiple: true },
    file: { type: 'string' },
    'lock-version': { type: 'string' },
  },
  write: { kind: 'edit', permission: 'case:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const caseId = await resolveId({
      client, projectId, value: requireArg(context, 'case'), entity: 'CASE',
    });

    const file = text(options['file']);
    const path = { projectId, caseId };

    const body = file
      ? objectFrom(file, 'a TestCaseInput')
      : requireSomethingToDo(
        present({
          title: text(options['title']),
          description: nullableText(options['description']),
          objective: nullableText(options['objective']),
          expectedOutcome: nullableText(options['expected-outcome']),
          priority: text(options['priority']) as TestCaseInput['priority'],
          severity: text(options['severity']) as TestCaseInput['severity'],
          automationStatus: text(options['automation-status']) as TestCaseInput['automationStatus'],
          automationKey: nullableText(options['automation-key']),
          // Both spellings of "move this case", and they are different requests: a suite key
          // files it there, `--unfile` sends an explicit null. Omitting both leaves it where it
          // is — the third state, and the one a client that collapsed null and absent would lose.
          suiteId: flag(options['unfile'])
            ? null
            : await optionalSuite(context, projectId),
          // A complete set, per the contract: absent leaves the tags alone, `--tag ''` clears
          // them. Not a merge — `--tag smoke` on a case tagged `smoke, slow` leaves it tagged
          // `smoke`, which is what the field means everywhere else in this product.
          tags: tagsFrom(options['tag']),
        }),
        'Name at least one field to change, or pass --file with a body.',
      );

    const version = await versionFor(context, {
      fromFile: file !== undefined,
      operation: 'edit this case',
      read: () => client.GET('/projects/{projectId}/cases/{caseId}', { params: { path }, tcms: request }),
    });

    const updated = await client.PATCH('/projects/{projectId}/cases/{caseId}', {
      params: { path },
      body: body as TestCaseInput,
      tcms: { ...request, ...editIntent(version, 'edit this case') },
    });

    reportWrite(out, json, updated, [
      ['Updated', `${show(updated.key)} — ${show(updated.title)}`],
      ['Status', show(updated.lifecycleStatus)],
      ['Lock version', show(updated.lockVersion)],
    ]);
  },
};

async function optionalSuite(
  context: CommandContext,
  projectId: string,
): Promise<string | undefined> {
  const suite = first(context.options['suite']);
  if (suite === undefined) return undefined;
  return resolveId({ client: context.client, projectId, value: suite, entity: 'SUITE' });
}

/**
 * A complete tag set, or undefined for "leave them alone".
 *
 * `--tag ''` is the clearing form, matching `nullableText`'s rule on the fields that take null:
 * an array field's empty value is `[]`, and a shell cannot type that either.
 */
function tagsFrom(value: string | boolean | (string | boolean)[] | undefined): string[] | undefined {
  const values = all(value);
  if (values.length === 0) return undefined;
  return values.filter((tag) => tag !== '');
}

const caseProposal: Command = {
  name: 'case propose',
  summary: 'File a generated test case for a person to review',
  usage: 'tcms case propose --file CASE.json [--verdict V] [--idempotency-key K]',
  options: {
    file: { type: 'string' },
    verdict: { type: 'string' },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'case:create' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);

    const raw = objectFrom(requireOption(context, 'file'), 'a test case or a proposal');

    /*
      Two shapes are accepted, because two callers exist. An agent holds a `TestCaseInput` — the
      thing it generated — and the API takes an `AiProposalInput` wrapping it. Requiring the
      wrapper would make every caller write `{"testCase": …}` around a file they already have,
      and detecting it costs one key: a body carrying `testCase` is already a proposal.
    */
    const proposal = 'testCase' in raw ? raw : { testCase: raw };

    const verdict = text(options['verdict']);
    const body = {
      ...proposal,
      // Deliberately not defaulted. Absent and `UNVERIFIED` are **different**: both store the
      // verdict UNVERIFIED and they differ in `verdictSource` — nobody claimed anything, against
      // an agent claiming it had not checked. A client that filled this in would turn every
      // silence into a claim.
      ...(verdict === undefined ? {} : { verdict }),
    } as components['schemas']['AiProposalInput'];

    const filed = await client.POST('/projects/{projectId}/ai-proposals', {
      params: { path: { projectId } },
      body,
      tcms: { ...request, ...createIntent({ key: text(options['idempotency-key']) }) },
    });

    reportWrite(out, json, filed, [
      ['Filed', show(filed.id)],
      ['Title', show(filed.testCase?.title)],
      ['Status', show(filed.status)],
      // Printed together, always, for the reason `VerdictChip` takes both props and has no way to
      // render one without the other (§138): a verdict without its source reads as a finding.
      ['Verdict', `${show(filed.verdict)} (${show(filed.verdictSource)})`],
    ]);

    if (!json) {
      out.line();
      out.line('Waiting for a person. A credential cannot accept its own proposal.');
    }
  },
};

export const WRITES: readonly Command[] = [
  caseEdit,
  caseProposal,
];
