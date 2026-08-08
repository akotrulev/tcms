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
  TcmsClientError,
  caseInputFrom,
  classify,
  createIntent,
  editIntent,
  planInputFrom,
  requireExplicitVersion,
  requireProposableCase,
  resolveId,
  suiteInputFrom,
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
 * The `read` a `--file` edit passes, which is never called.
 *
 * Written as a function that throws rather than as an optional parameter: an optional `read`
 * would let a *flag* edit omit it and fall through to some default, and the default that keeps
 * the code running is the unsafe one. This way the type still demands a reader and the one path
 * that legitimately has none says so out loud.
 */
const notRead = (): Promise<never> => {
  throw new TcmsClientError('A whole-body edit reads nothing; it carries its own version.');
};

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

    let body: Record<string, unknown>;
    let version: EditVersion;

    if (file) {
      body = objectFrom(file, 'a TestCaseInput');
      version = await versionFor(context, { fromFile: true, operation: 'edit this case', read: notRead });
    } else {
      const delta = requireSomethingToDo(
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

      /*
        Read-modify-write, because this `PATCH` is not partial — see `core/rewrite.ts`. The delta
        alone was a 400 naming `stepFormat`, `priority` and `automationStatus`, so
        `tcms case edit TC-1 --title x` had never worked once (§143), and the fields it did not
        name would have been cleared if it had.

        The version comes from THIS read, not a second one: base and precondition from the same
        instant is what makes the `If-Match` honest here.
      */
      const current = await client.GET('/projects/{projectId}/cases/{caseId}', {
        params: { path }, tcms: request,
      });
      version = await versionFor(context, {
        fromFile: false, operation: 'edit this case', read: async () => current,
      });
      body = { ...caseInputFrom(current), ...delta };
    }

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

    /*
      Refused here rather than filed, because filing it would WORK: this endpoint stores the
      payload unvalidated and the create rules run when a person accepts, so a missing field
      becomes a 400 on a reviewer's screen against a proposal they cannot edit. §143 drove that
      whole path. `core/proposal.ts` has the argument and the field list.
    */
    const submitted = proposal['testCase'];
    requireProposableCase(
      submitted && typeof submitted === 'object' && !Array.isArray(submitted)
        ? submitted as Record<string, unknown>
        : {},
      'That case',
    );

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

    let body: Record<string, unknown>;
    let version: EditVersion;

    if (file) {
      body = objectFrom(file, 'a SuiteInput');
      version = await versionFor(context, { fromFile: true, operation: 'edit this suite', read: notRead });
    } else {
      const delta = requireSomethingToDo(
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

      // The case arm's problem, one field wide: `SuiteInput` requires `name`, so
      // `--tag-rule smoke` alone was a 400 about a field nobody mentioned, and `--name X` alone
      // emptied the description. `core/rewrite.ts` has the argument.
      const current = await client.GET('/projects/{projectId}/suites/{suiteId}', {
        params: { path }, tcms: request,
      });
      version = await versionFor(context, {
        fromFile: false, operation: 'edit this suite', read: async () => current,
      });
      body = { ...suiteInputFrom(current), ...delta };
    }

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

/* ------------------------------------------------------------------- plans ---- */

const planCreate: Command = {
  name: 'plan create',
  summary: 'Create a test plan',
  usage:
    'tcms plan create --name N [--description D] [--milestone M] [--milestone-date YYYY-MM-DD]\n'
    + '                 [--suite TS-n]... [--case TC-n]... [--idempotency-key K]',
  options: {
    name: { type: 'string' },
    description: { type: 'string' },
    milestone: { type: 'string' },
    'milestone-date': { type: 'string' },
    suite: { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'plan:create' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);

    const created = await client.POST('/projects/{projectId}/plans', {
      params: { path: { projectId } },
      body: present({
        name: requireOption(context, 'name'),
        description: nullableText(options['description']),
        milestoneName: nullableText(options['milestone']),
        milestoneDate: nullableText(options['milestone-date']),
        suiteIds: await resolveMany(context, projectId, options['suite'], 'SUITE'),
        caseIds: await resolveMany(context, projectId, options['case'], 'CASE'),
      }) as components['schemas']['TestPlanInput'],
      tcms: { ...request, ...createIntent({ key: text(options['idempotency-key']) }) },
    });

    reportWrite(out, json, created, [
      ['Created', `${show(created.key)} — ${show(created.name)}`],
      ['Status', show(created.status)],
      ['Cases', show(created.caseCount)],
    ]);
  },
};

const planEdit: Command = {
  name: 'plan edit',
  summary: 'Change a test plan',
  usage:
    'tcms plan edit TP-1 [--name N] [--description D] [--milestone M] [--suite TS-n]...\n'
    + '                   [--case TC-n]... [--file BODY.json --lock-version N]',
  options: {
    name: { type: 'string' },
    description: { type: 'string' },
    milestone: { type: 'string' },
    'milestone-date': { type: 'string' },
    suite: { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    file: { type: 'string' },
    'lock-version': { type: 'string' },
  },
  write: { kind: 'edit', permission: 'plan:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const planId = await resolveId({
      client, projectId, value: requireArg(context, 'plan'), entity: 'PLAN',
    });

    const file = text(options['file']);
    const path = { projectId, planId };

    let body: Record<string, unknown>;
    let version: EditVersion;

    if (file) {
      body = objectFrom(file, 'a TestPlanUpdateInput');
      version = await versionFor(context, { fromFile: true, operation: 'edit this plan', read: notRead });
    } else {
      /*
        ~~`TestPlanUpdateInput` REQUIRES `name`, alone among the edit shapes on this surface~~ →
        it is not alone: `SuiteInput` requires one too and `TestCaseInput` requires four. This arm
        threaded `name` through from its own read and was right to; what it did not do was thread
        the rest, so every `plan edit` silently emptied the plan's `description`, `milestoneName`,
        `milestoneDate`, `scopeText` and `outOfScopeText` — the last two unreachable from any flag,
        so no invocation could avoid it. `PlanEdit.validateUpdate` writes every scalar straight
        through and resolves only the *lists* against what is stored. `core/rewrite.ts` now holds
        that split, shared with the case and suite arms and with the MCP server.

        Safe for the reason the old note gave, unchanged: the base and the version come from the
        *same* read, so the pair is consistent. A `--file` edit supplies both itself.
      */
      const current = await client.GET('/projects/{projectId}/plans/{planId}', {
        params: { path }, tcms: request,
      });
      version = await versionFor(context, {
        fromFile: false, operation: 'edit this plan', read: async () => current,
      });
      const delta = requireSomethingToDo(
        present({
          name: text(options['name']),
          description: nullableText(options['description']),
          milestoneName: nullableText(options['milestone']),
          milestoneDate: nullableText(options['milestone-date']),
          suiteIds: await resolveMany(context, projectId, options['suite'], 'SUITE'),
          caseIds: await resolveMany(context, projectId, options['case'], 'CASE'),
        }),
        'Name at least one field to change, or pass --file with a body.',
      );
      body = { ...planInputFrom(current), ...delta };
    }

    const updated = await client.PATCH('/projects/{projectId}/plans/{planId}', {
      params: { path },
      body: body as components['schemas']['TestPlanUpdateInput'],
      tcms: { ...request, ...editIntent(version, 'edit this plan') },
    });

    reportWrite(out, json, updated, [
      ['Updated', `${show(updated.key)} — ${show(updated.name)}`],
      ['Status', show(updated.status)],
      ['Lock version', show(updated.lockVersion)],
    ]);
  },
};

const planStatus: Command = {
  name: 'plan status',
  summary: 'Submit, approve or withdraw a test plan',
  usage: 'tcms plan status TP-1 --status DRAFT|IN_REVIEW|APPROVED',
  options: { status: { type: 'string' } },
  write: { kind: 'state', permission: 'plan:edit' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const projectId = await projectOf(context);
    const planId = await resolveId({
      client, projectId, value: requireArg(context, 'plan'), entity: 'PLAN',
    });

    const updated = await client.PUT('/projects/{projectId}/plans/{planId}/status', {
      params: { path: { projectId, planId } },
      body: { status: requireStatus(context) as 'DRAFT' | 'IN_REVIEW' | 'APPROVED' },
      tcms: request,
    });

    reportWrite(out, json, updated, [
      ['Plan', `${show(updated.key)} — ${show(updated.name)}`],
      ['Status', show(updated.status)],
    ]);
  },
};

/* -------------------------------------------------------------------- runs ---- */

const runCreate: Command = {
  name: 'run create',
  summary: 'Create a test run',
  usage:
    'tcms run create --name N [--plan TP-n] [--suite TS-n]... [--case TC-n]... [--tag T]...\n'
    + '                [--release V] [--build B] [--idempotency-key K]',
  options: {
    name: { type: 'string' },
    plan: { type: 'string' },
    suite: { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    tag: { type: 'string', multiple: true },
    release: { type: 'string' },
    build: { type: 'string' },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'run:create' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const plan = text(options['plan']);

    const created = await client.POST('/projects/{projectId}/runs', {
      params: { path: { projectId } },
      body: present({
        name: requireOption(context, 'name'),
        planId: plan === undefined
          ? undefined
          : await resolveId({ client, projectId, value: plan, entity: 'PLAN' }),
        suiteIds: await resolveMany(context, projectId, options['suite'], 'SUITE'),
        caseIds: await resolveMany(context, projectId, options['case'], 'CASE'),
        // A run's tags point the opposite way from a case filter's — every live case carrying
        // ANY of them, union not intersection. The contract says so on the field; naming it here
        // would be a second statement of the same rule.
        tags: tagsFrom(options['tag']),
        releaseVersion: nullableText(options['release']),
        buildNumber: nullableText(options['build']),
      }) as components['schemas']['TestRunInput'],
      tcms: { ...request, ...createIntent({ key: text(options['idempotency-key']) }) },
    });

    reportWrite(out, json, created, [
      ['Created', `${show(created.key)} — ${show(created.name)}`],
      ['Status', show(created.status)],
      ['Plan', show(created.planKey)],
    ]);
  },
};

const runStatus: Command = {
  name: 'run status',
  summary: 'Complete, abort or reopen a run',
  usage: 'tcms run status TR-1 --status COMPLETED|ABORTED|IN_PROGRESS|NOT_STARTED',
  options: { status: { type: 'string' } },
  // `run:close` is Lead and up and an Agent token does not hold it — the command exists because
  // this CLI is a person's tool too. Same call as `suite status`, one noun over.
  write: { kind: 'state', permission: 'run:close' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const projectId = await projectOf(context);
    const runId = await resolveId({
      client, projectId, value: requireArg(context, 'run'), entity: 'RUN',
    });

    const updated = await client.PUT('/projects/{projectId}/runs/{runId}/status', {
      params: { path: { projectId, runId } },
      body: { status: requireStatus(context) as components['schemas']['RunStatusInput']['status'] },
      tcms: request,
    });

    reportWrite(out, json, updated, [
      ['Run', `${show(updated.key)} — ${show(updated.name)}`],
      ['Status', show(updated.status)],
    ]);
  },
};

const resultRecord: Command = {
  name: 'result record',
  summary: 'Record a verdict against one case in a run',
  usage: 'tcms result record TR-1 TC-42 --status Passed [--comment TEXT]',
  options: {
    status: { type: 'string' },
    comment: { type: 'string' },
    'lock-version': { type: 'string' },
  },
  write: { kind: 'edit', permission: 'result:submit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const runId = await resolveId({
      client, projectId, value: requireArg(context, 'run'), entity: 'RUN',
    });

    /*
      An execution has **no display key** and never will (§6t) — it is identified by its UUID or
      by (runId, caseId), and "nobody says TE-4038 out loud". So this command takes the pair a
      person actually holds, `TR-1 TC-42`, and finds the execution in the run's own detail. That
      is a read the caller would otherwise do by hand and paste a UUID out of.
    */
    const caseKey = requireArg(context, 'case', 1);
    const detail = await client.GET('/projects/{projectId}/runs/{runId}', {
      params: { path: { projectId, runId } }, tcms: request,
    });

    const execution = detail.executions.find(
      (row) => row.caseKey === caseKey || row.caseId === caseKey,
    );
    if (!execution) {
      throw new UsageError(
        `${caseKey} is not in ${show(detail.key)}. A run covers the cases it was created with; `
          + 'adding one is a change to the run, not a result.',
      );
    }

    // The status is named, not typed as a UUID: `case_status` is per-project vocabulary and the
    // ids are nobody's to remember. An unknown name is refused here rather than sent, because
    // the server's answer would be a 400 on `statusId` naming an id the caller never chose.
    const wanted = requireOption(context, 'status');
    const statuses = await client.GET('/projects/{projectId}/statuses', {
      params: { path: { projectId } }, tcms: request,
    });
    const status = statuses.find(
      (row) => row.name.toLowerCase() === wanted.toLowerCase() || row.id === wanted,
    );
    if (!status) {
      throw new UsageError(
        `No status '${wanted}' in this project. It offers: `
          + `${statuses.map((row) => row.name).join(', ')}.`,
      );
    }

    const path = { projectId, runId, executionId: execution.id };
    const version = await versionFor(context, {
      fromFile: false,
      operation: 'record this result',
      // The run detail's execution rows are summaries and carry no `lockVersion` — only the
      // execution's own detail does. So this is a second read, and it is the right one: the
      // version must come from the resource being written, not from a list that mentions it.
      read: () => client.GET('/projects/{projectId}/runs/{runId}/executions/{executionId}', {
        params: { path }, tcms: request,
      }),
    });

    const recorded = await client.PATCH('/projects/{projectId}/runs/{runId}/executions/{executionId}', {
      params: { path },
      body: present({
        statusId: status.id,
        comment: nullableText(options['comment']),
      }) as components['schemas']['ExecutionResultInput'],
      tcms: { ...request, ...editIntent(version, 'record this result') },
    });

    reportWrite(out, json, recorded, [
      ['Recorded', `${show(recorded.caseKey)} — ${show(recorded.status?.name)}`],
      ['Run', show(detail.key)],
      ['Lock version', show(recorded.lockVersion)],
    ]);
  },
};

/* ----------------------------------------------------------------- reviews ---- */

const reviewComment: Command = {
  name: 'review comment',
  summary: 'Write a remark on the open review of a case, suite or plan',
  usage: 'tcms review comment TC-42 --body TEXT [--reply COMMENT_UUID]',
  options: {
    body: { type: 'string' },
    reply: { type: 'string' },
  },
  // One key for all three subjects: §A3 has `case:comment` and deliberately no `suite:comment`
  // or `plan:comment`. It is also why commenting and verdicts are two tools on the MCP side —
  // a Viewer-owned agent token holds this and no `*:review` at all.
  write: { kind: 'state', permission: 'case:comment' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);

    // The subject type is read off the key's own prefix rather than asked for: `TC-42` already
    // says it is a case, and a `--subject` flag would let the two disagree.
    const key = requireArg(context, 'case, suite or plan');
    const identifier = classify(key);
    // A bare UUID cannot say what it is, and the review list requires the type — so this is one
    // of the few places on the surface where a display key is not merely a convenience.
    const subject = identifier.kind === 'key' ? SUBJECTS[identifier.key.entity] : undefined;
    if (!subject) {
      throw new UsageError(
        'Reviews are kept for cases, suites and plans, and the key is what says which. '
          + 'Name one — TC-42, TS-7, TP-1.',
      );
    }

    const subjectId = await resolveId({ client, projectId, value: key, entity: subject.entity });
    const reviews = await client.GET('/projects/{projectId}/reviews', {
      params: { query: { subjectType: subject.wire, subjectId }, path: { projectId } },
      tcms: request,
    });

    /*
      Newest first, and a comment goes on the open one. A review is **history** once it closes —
      the endpoint answers 409 on a closed review, and re-submitting opens a NEW record rather
      than reopening the old — so "the latest row" is not the same question as "the one that can
      still be commented on", and picking the wrong one is a refusal the user cannot act on.
    */
    const open = reviews.find((review) => review.state === 'IN_REVIEW');
    if (!open) {
      throw new UsageError(
        reviews.length === 0
          ? `${key} has never been submitted for review, so there is no thread to join.`
          : `${key}'s review has ended (${show(reviews[0]?.state)}). Submit it again to reopen a `
            + 'conversation; the closed record stays as it is.',
      );
    }

    const updated = await client.POST('/projects/{projectId}/reviews/{reviewId}/comments', {
      params: { path: { projectId, reviewId: open.id } },
      body: present({
        body: requireOption(context, 'body'),
        parentId: text(options['reply']),
      }) as components['schemas']['ReviewCommentInput'],
      tcms: request,
    });

    reportWrite(out, json, updated, [
      ['Commented on', `${key} — review ${show(updated.id)}`],
      ['State', show(updated.state)],
      ['Comments', show(updated.comments.length)],
    ]);
  },
};

/** Display-key entity to the wire's `ReviewSubjectType`, for the three that have reviews. */
const SUBJECTS: Partial<Record<
  'CASE' | 'SUITE' | 'PLAN' | 'RUN' | 'DOC_PAGE',
  { entity: 'CASE' | 'SUITE' | 'PLAN'; wire: components['schemas']['ReviewSubjectType'] }
>> = {
  CASE: { entity: 'CASE', wire: 'CASE' },
  SUITE: { entity: 'SUITE', wire: 'SUITE' },
  PLAN: { entity: 'PLAN', wire: 'PLAN' },
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
  planCreate,
  planEdit,
  planStatus,
  runCreate,
  runStatus,
  resultRecord,
  reviewComment,
];
