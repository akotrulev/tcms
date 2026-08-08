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
  UsageError,
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

/**
 * Display keys to ids, for the fields that take a **complete set**.
 *
 * Sequential rather than concurrent, and deliberately: `resolveId` for a suite, a plan, a run or
 * a page fetches the project's whole list (there is no `q` on those four — `core/resolve.ts`
 * records it), so firing ten in parallel is ten copies of the same list. The cases are the one
 * kind with a server-side lookup and they are cheap either way.
 */
async function resolveMany(
  context: CommandContext,
  projectId: string,
  values: string | boolean | (string | boolean)[] | undefined,
  entity: 'CASE' | 'SUITE',
): Promise<string[] | undefined> {
  const keys = all(values).filter((key) => key !== '');
  if (all(values).length === 0) return undefined;

  const ids: string[] = [];
  for (const value of keys) {
    ids.push(await resolveId({ client: context.client, projectId, value, entity }));
  }
  return ids;
}

/**
 * The target state of a status transition.
 *
 * **Not validated against a list here.** Which moves are legal is a property of the artifact's
 * current state, which the server holds; a client-side ladder would be a second copy of §6g's,
 * and each status endpoint's own description refuses exactly that for exactly this reason —
 * "reviving a deprecated suite is spelled DRAFT, not APPROVED" is the kind of rule that does not
 * survive being written down twice.
 */
function requireStatus(context: CommandContext): string {
  return requireOption(context, 'status');
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

/* ------------------------------------------------------------------ suites ---- */

const suiteCreate: Command = {
  name: 'suite create',
  summary: 'Create a suite',
  usage:
    'tcms suite create --name N [--description D] [--tag-rule R]... [--case TC-n]...\n'
    + '                  [--idempotency-key K]',
  options: {
    name: { type: 'string' },
    description: { type: 'string' },
    'tag-rule': { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'suite:manage' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);

    const created = await client.POST('/projects/{projectId}/suites', {
      params: { path: { projectId } },
      body: present({
        name: requireOption(context, 'name'),
        description: nullableText(options['description']),
        tagRules: tagsFrom(options['tag-rule']),
        caseIds: await resolveMany(context, projectId, options['case'], 'CASE'),
      }) as components['schemas']['SuiteInput'],
      tcms: { ...request, ...createIntent({ key: text(options['idempotency-key']) }) },
    });

    reportWrite(out, json, created, [
      // The key is server-allocated per company (§6t), so it is the first thing a caller learns
      // and the only handle every later command takes.
      ['Created', `${show(created.key)} — ${show(created.name)}`],
      ['Status', show(created.lifecycleStatus)],
      ['Lock version', show(created.lockVersion)],
    ]);
  },
};

const suiteEdit: Command = {
  name: 'suite edit',
  summary: 'Rename a suite, or change what it collects',
  usage:
    'tcms suite edit TS-7 [--name N] [--description D] [--tag-rule R]... [--case TC-n]...\n'
    + '                    [--file BODY.json --lock-version N]',
  options: {
    name: { type: 'string' },
    description: { type: 'string' },
    'tag-rule': { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    file: { type: 'string' },
    'lock-version': { type: 'string' },
  },
  write: { kind: 'edit', permission: 'suite:manage' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const suiteId = await resolveId({
      client, projectId, value: requireArg(context, 'suite'), entity: 'SUITE',
    });

    const file = text(options['file']);
    const path = { projectId, suiteId };

    const body = file
      ? objectFrom(file, 'a SuiteInput')
      : requireSomethingToDo(
        present({
          name: text(options['name']),
          description: nullableText(options['description']),
          // Both are complete sets, per the contract, and both matter more here than on a case:
          // a suite's membership IS these lists, so reading a partial list as a merge would make
          // "collect these three" mean "add these three" and never let anything be removed.
          tagRules: tagsFrom(options['tag-rule']),
          caseIds: await resolveMany(context, projectId, options['case'], 'CASE'),
        }),
        'Name at least one field to change, or pass --file with a body.',
      );

    const version = await versionFor(context, {
      fromFile: file !== undefined,
      operation: 'edit this suite',
      read: () => client.GET('/projects/{projectId}/suites/{suiteId}', { params: { path }, tcms: request }),
    });

    const updated = await client.PATCH('/projects/{projectId}/suites/{suiteId}', {
      params: { path },
      body: body as components['schemas']['SuiteInput'],
      tcms: { ...request, ...editIntent(version, 'edit this suite') },
    });

    reportWrite(out, json, updated, [
      ['Updated', `${show(updated.key)} — ${show(updated.name)}`],
      ['Cases', show(updated.caseCount)],
      ['Lock version', show(updated.lockVersion)],
    ]);
  },
};

const suiteStatus: Command = {
  name: 'suite status',
  summary: 'Submit, approve, withdraw, deprecate or revive a suite',
  usage: 'tcms suite status TS-7 --status DRAFT|IN_REVIEW|APPROVED|DEPRECATED',
  options: { status: { type: 'string' } },
  // `suite:manage` carries the forward moves an Agent can reach; APPROVED and DEPRECATED cost
  // `suite:approve`, which the server checks per edge. The declaration names the lower key
  // because it is what the command needs to be *listed*, and the refusal for the rest is the
  // server's to make — §6g's ladder is not copied here. See the note beside `LADDER`.
  write: { kind: 'state', permission: 'suite:manage' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const projectId = await projectOf(context);
    const suiteId = await resolveId({
      client, projectId, value: requireArg(context, 'suite'), entity: 'SUITE',
    });

    const updated = await client.PUT('/projects/{projectId}/suites/{suiteId}/status', {
      params: { path: { projectId, suiteId } },
      body: { status: requireStatus(context) as components['schemas']['LifecycleStatus'] },
      // No `If-Match` and no key, and the contract says why on this endpoint in as many words:
      // the request names an absolute target state rather than a delta against something the
      // client read, so there is no lost update to protect — and setting the status a suite
      // already has writes nothing.
      tcms: request,
    });

    reportWrite(out, json, updated, [
      ['Suite', `${show(updated.key)} — ${show(updated.name)}`],
      ['Status', show(updated.lifecycleStatus)],
    ]);
  },
};

/* -------------------------------------------------------------------- docs ---- */

const docCreate: Command = {
  name: 'doc create',
  summary: 'Create a documentation page',
  usage: 'tcms doc create --title T [--parent DOC-n] [--file BLOCKS.json] [--idempotency-key K]',
  options: {
    title: { type: 'string' },
    parent: { type: 'string' },
    file: { type: 'string' },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'doc:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const parent = text(options['parent']);
    const file = text(options['file']);

    /*
      A page is a title plus an ordered list of blocks, and blocks are not expressible as flags —
      a heading's level, a list's depth and a table's rows are three different shapes. So `--file`
      here carries a whole `DocPageInput` (or just its `blocks` array), and `--title` overrides
      whatever title it names.
    */
    const fromFile = file ? objectFrom(file, 'a DocPageInput') : {};
    const title = text(options['title']) ?? (typeof fromFile['title'] === 'string' ? fromFile['title'] : undefined);
    if (title === undefined) throw new UsageError('--title is required, or name one in --file.');

    const created = await client.POST('/projects/{projectId}/doc-pages', {
      params: { path: { projectId } },
      body: present({
        ...fromFile,
        title,
        parentId: parent === undefined
          ? undefined
          : await resolveId({ client, projectId, value: parent, entity: 'DOC_PAGE' }),
      }) as components['schemas']['DocPageInput'],
      tcms: { ...request, ...createIntent({ key: text(options['idempotency-key']) }) },
    });

    reportWrite(out, json, created, [
      ['Created', `${show(created.key)} — ${show(created.title)}`],
      ['Status', show(created.status)],
      ['Blocks', show(created.blocks.length)],
      ['Lock version', show(created.lockVersion)],
    ]);
  },
};

const docEdit: Command = {
  name: 'doc edit',
  summary: 'Retitle a page, or replace its blocks',
  usage: 'tcms doc edit DOC-1 [--title T] [--file BODY.json --lock-version N]',
  options: {
    title: { type: 'string' },
    file: { type: 'string' },
    'lock-version': { type: 'string' },
  },
  write: { kind: 'edit', permission: 'doc:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const pageId = await resolveId({
      client, projectId, value: requireArg(context, 'page'), entity: 'DOC_PAGE',
    });

    const file = text(options['file']);
    const path = { projectId, pageId };

    /*
      `blocks` is the whole page, in order, and a block OMITTED is DELETED — so a `--title`-only
      edit must not send the key at all. `present()` drops it, which is the difference between a
      retitle and emptying somebody's specification. The contract states the rule and this is the
      one command where forgetting it destroys content rather than failing.
    */
    const body = file
      ? objectFrom(file, 'a DocPageInput')
      : requireSomethingToDo(
        present({ title: text(options['title']) }),
        'Pass --title, or --file with the page body.',
      );

    const version = await versionFor(context, {
      fromFile: file !== undefined,
      operation: 'edit this page',
      read: () => client.GET('/projects/{projectId}/doc-pages/{pageId}', { params: { path }, tcms: request }),
    });

    const updated = await client.PATCH('/projects/{projectId}/doc-pages/{pageId}', {
      params: { path },
      body: body as components['schemas']['DocPageInput'],
      tcms: { ...request, ...editIntent(version, 'edit this page') },
    });

    reportWrite(out, json, updated, [
      ['Updated', `${show(updated.key)} — ${show(updated.title)}`],
      ['Blocks', show(updated.blocks.length)],
      ['Lock version', show(updated.lockVersion)],
    ]);
  },
};

const docLink: Command = {
  name: 'doc link',
  summary: 'Record that a case or suite verifies a block',
  usage: 'tcms doc link DOC-1 --block UUID --case TC-42 [--relationship VERIFIES]',
  options: {
    block: { type: 'string' },
    case: { type: 'string' },
    suite: { type: 'string' },
    relationship: { type: 'string' },
  },
  // A create by shape — it allocates a `doc_link` — but the contract offers no `Idempotency-Key`
  // here and needs none: linking the same source to the same block twice writes nothing and
  // answers 200 with the existing link, which is the property a key would have bought.
  write: { kind: 'state', permission: 'doc:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const pageId = await resolveId({
      client, projectId, value: requireArg(context, 'page'), entity: 'DOC_PAGE',
    });

    const caseKey = text(options['case']);
    const suiteKey = text(options['suite']);
    if ((caseKey === undefined) === (suiteKey === undefined)) {
      throw new UsageError('Name exactly one of --case or --suite.');
    }

    const link = await client.POST('/projects/{projectId}/doc-pages/{pageId}/blocks/{blockId}/links', {
      params: { path: { projectId, pageId, blockId: requireOption(context, 'block') } },
      body: {
        ...(caseKey === undefined
          ? {
            sourceType: 'SUITE' as const,
            sourceId: await resolveId({ client, projectId, value: suiteKey ?? '', entity: 'SUITE' }),
          }
          : {
            sourceType: 'TEST_CASE' as const,
            sourceId: await resolveId({ client, projectId, value: caseKey, entity: 'CASE' }),
          }),
        // Sent explicitly rather than omitted, because the generated type makes it required —
        // `openapi-typescript` reads a schema default as "the server will always have one",
        // which is true of the stored row and not of the request. The value is the contract's
        // own default, so this asks for what an omission would have got.
        relationship: (text(options['relationship']) ?? 'VERIFIES') as
          components['schemas']['BlockLinkInput']['relationship'],
      },
      tcms: request,
    });

    reportWrite(out, json, link, [
      ['Linked', show(link.id)],
      ['Source', `${show(link.sourceType)} ${show(link.sourceKey)}`],
      ['Relationship', show(link.relationship)],
    ]);
  },
};

const docUnlink: Command = {
  name: 'doc unlink',
  summary: 'Withdraw a link from a block',
  usage: 'tcms doc unlink DOC-1 --link UUID',
  options: { link: { type: 'string' } },
  write: { kind: 'delete', permission: 'doc:edit' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const projectId = await projectOf(context);
    const pageId = await resolveId({
      client, projectId, value: requireArg(context, 'page'), entity: 'DOC_PAGE',
    });
    const linkId = requireOption(context, 'link');

    await client.DELETE('/projects/{projectId}/doc-pages/{pageId}/links/{linkId}', {
      params: { path: { projectId, pageId, linkId } },
      tcms: request,
    });

    // A 204 has no body, so there is nothing verbatim to print under `--json`. Saying so as an
    // object beats printing `undefined`, which a `jq` pipeline cannot read.
    reportWrite(out, json, { deleted: linkId }, [['Removed', linkId]]);
  },
};

export const WRITES: readonly Command[] = [
  caseEdit,
  caseProposal,
  suiteCreate,
  suiteEdit,
  suiteStatus,
  docCreate,
  docEdit,
  docLink,
  docUnlink,
];
