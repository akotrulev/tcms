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
  CASE_SHAPE,
  ISSUE_SHAPE,
  TcmsClientError,
  caseInputFrom,
  classify,
  createIntent,
  editIntent,
  planInputFrom,
  requireExplicitVersion,
  optionSpecFor,
  requireProposableCase,
  resolveId,
  usageFor,
  suiteInputFrom,
  type CreateIntent,
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
import { boardOf, labelIds, releaseOf } from './reads.js';
import { fields, show, showAuthor, type Output } from './output.js';

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

/**
 * {@link nullableText}'s shape for a nullable number, with the same empty-string clearing form.
 *
 * The unit stays whatever the contract's unit is — `--duration-ms` says milliseconds in its own
 * name rather than converting from seconds here. The SPA converts because a tester types seconds;
 * a second conversion in a second client is how two callers come to disagree about one column,
 * which is the argument `frontend/src/lib/format.ts` carries at length.
 */
function nullableNumber(
  value: string | boolean | (string | boolean)[] | undefined,
  flag: string,
): number | null | undefined {
  const raw = first(value);
  if (raw === undefined) return undefined;
  if (raw === '') return null;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new UsageError(`${flag} takes a whole number of milliseconds, not '${raw}'.`);
  }
  return parsed;
}

/**
 * {@link nullableNumber}'s shape for a value the contract declares `format: double`.
 *
 * Separate rather than a flag on that one, because the check is the difference: an estimate of
 * `2.5` hours is legitimate and a duration of 2.5 milliseconds is a typo. Rejecting a fraction
 * here would make the flag refuse the commonest real value; accepting one there would send the
 * server a number it stores as an integer column.
 */
function nullableFraction(
  value: string | boolean | (string | boolean)[] | undefined,
  flagName: string,
): number | null | undefined {
  const raw = first(value);
  if (raw === undefined) return undefined;
  if (raw === '') return null;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed < 0) {
    throw new UsageError(`${flagName} takes a number of hours, not '${raw}'.`);
  }
  return parsed;
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

/**
 * The verb a create's first row carries — the one it did, or `Replayed` when it did not do it.
 *
 * Sixteen create operations answer **200 instead of 201** when the `Idempotency-Key` had already
 * carried the intent out, and the contract's own words for that are *"nothing was created by this
 * request"*. Every create arm here printed `Created` regardless, because `@tcms/client` dropped the
 * status before any caller could see it (§204). That is a sentence a CI log keeps: a retried job
 * claiming it created the run its earlier attempt created.
 *
 * The row appears in practice only for `--idempotency-key`, which is the whole population that can
 * be replayed — a bare invocation generates a fresh key and is therefore a fresh intent. `--json` is
 * untouched: it prints the resource, and the resource is identical either way, which is precisely the
 * fact this word exists to state.
 */
function outcome(intent: CreateIntent, verb: string): string {
  return intent.wasReplay() ? 'Replayed' : verb;
}

/* ------------------------------------------------------------------- cases ---- */

const caseEdit: Command = {
  name: 'case edit',
  summary: 'Change a test case',
  /*
    Rendered from the shape catalogue since §253, both of them, rather than written out twice —
    which is how `--not-automatable-reason` arrived: the flag was missing here while
    `automationStatus: NOT_AUTOMATABLE` was accepted, so the one spelling the server refuses was
    the only one this command could produce.

    `--file` is added by hand and deliberately is NOT derived. It is on six of the ten
    body-carrying commands across this registry and missing from four, which reads as an accident
    rather than a decision; deriving it would silently widen the CLI surface, and straightening it
    is a slice of its own. `mechanicsFor` has the argument, and the conformance check reports the
    four.
  */
  usage: `tcms case edit TC-42\n\n${usageFor(CASE_SHAPE, 'edit')}\n\n`
    + '  --file BODY.json\n      A whole TestCaseInput, instead of the flags. Needs --lock-version.',
  options: { ...optionSpecFor(CASE_SHAPE, 'edit'), file: { type: 'string' } },
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
          notAutomatableReason: nullableText(options['not-automatable-reason']),
          // Both spellings of "move this case", and they are different requests: a suite key
          // files it there, `--unfile` sends an explicit null. Omitting both leaves it where it
          // is — the third state, and the one a client that collapsed null and absent would lose.
          suiteId: flag(options['unfile'])
            ? null
            : await optionalSuite(context, projectId),
          // A complete set, per the contract: absent leaves the tags alone, `--tag ''` clears
          // them. Not a merge — `--tag smoke` on a case tagged `smoke, slow` leaves it tagged
          // `smoke`, which is what the field means everywhere else in this product.
          tags: completeSet(options['tag']),
          /*
            §6f's opt-out, and the arm §187 built the endpoint for without giving either machine
            surface a way to send it. A complete set on the same rule as `tags`, so
            `--decline ''` re-imposes every inherited condition this case had turned off.

            The ids are `suite_condition` **attachments** — the CONDITION column of
            `tcms case show` — and not library `condition` ids: the same entry attached at two
            suites is two attachments, and declining one must leave the other in force. The wrong
            kind is a field error rather than a silent no-op, because the server validates every
            id against the attachments that currently reach this case.
          */
          declinedConditionIds: completeSet(options['decline']),
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

/**
 * The three states of `--parent`, in the spelling the rest of this file uses for a nullable key.
 *
 * Absent leaves the page where it is; `--root` sends an explicit null and moves it to the top;
 * a key resolves. The same shape as `--suite` / `--unfile` on a case, deliberately — two nullable
 * key fields that behaved differently would be two rules a person has to remember which of.
 */
async function parentIdFrom(
  context: CommandContext,
  projectId: string,
): Promise<string | null | undefined> {
  if (flag(context.options['root'])) return null;
  const parent = text(context.options['parent']);
  if (parent === undefined) return undefined;
  return resolveId({ client: context.client, projectId, value: parent, entity: 'DOC_PAGE' });
}

async function optionalSuite(
  context: CommandContext,
  projectId: string,
): Promise<string | undefined> {
  const suite = first(context.options['suite']);
  if (suite === undefined) return undefined;
  return resolveId({ client: context.client, projectId, value: suite, entity: 'SUITE' });
}

/**
 * A complete set of whatever a repeatable flag names, or undefined for "leave it alone".
 *
 * `--tag ''` is the clearing form, matching `nullableText`'s rule on the fields that take null:
 * an array field's empty value is `[]`, and a shell cannot type that either.
 *
 * ~~`tagsFrom`~~ → renamed in §189, when the third and fourth callers arrived and neither is a
 * tag. Every complete-set field on this surface has the same three states and they must be
 * spelled the same way, or `--tag ''` clearing the tags while `--decline ''` did nothing would
 * be two rules a person has to remember which of.
 */
function completeSet(value: string | boolean | (string | boolean)[] | undefined): string[] | undefined {
  const values = all(value);
  if (values.length === 0) return undefined;
  return values.filter((entry) => entry !== '');
}

/**
 * The condition ids a suite attaches, as the input the write takes.
 *
 * **Library ids, not attachment ids** — `Suite.preconditions[].conditionId`, which
 * `tcms suite show` prints in its first column for this reason. `CaseConditionInput` carries
 * nothing else: `inlineBody` exists in the schema only so it can be refused, and order is the
 * content, assigned from this array rather than sent.
 */
function conditionsFrom(
  value: string | boolean | (string | boolean)[] | undefined,
): { conditionId: string }[] | undefined {
  return completeSet(value)?.map((conditionId) => ({ conditionId }));
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

    const intent = createIntent({ key: text(options['idempotency-key']) });
    const filed = await client.POST('/projects/{projectId}/ai-proposals', {
      params: { path: { projectId } },
      body,
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, filed, [
      [outcome(intent, 'Filed'), show(filed.id)],
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
    + '                  [--precondition ID]... [--postcondition ID]... [--idempotency-key K]',
  options: {
    name: { type: 'string' },
    description: { type: 'string' },
    'tag-rule': { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    precondition: { type: 'string', multiple: true },
    postcondition: { type: 'string', multiple: true },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'suite:manage' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);

    const intent = createIntent({ key: text(options['idempotency-key']) });
    const created = await client.POST('/projects/{projectId}/suites', {
      params: { path: { projectId } },
      body: present({
        name: requireOption(context, 'name'),
        description: nullableText(options['description']),
        tagRules: completeSet(options['tag-rule']),
        caseIds: await resolveMany(context, projectId, options['case'], 'CASE'),
        // Offered on the create as well as the edit, and not merely for symmetry: a suite's
        // conditions reach the cases filed in it, so "create it, then attach the conditions" is
        // a window in which those cases run without a setup step their author intended. One
        // request is the only spelling with no window.
        preconditions: conditionsFrom(options['precondition']),
        postconditions: conditionsFrom(options['postcondition']),
      }) as components['schemas']['SuiteInput'],
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, created, [
      // The key is server-allocated per company (§6t), so it is the first thing a caller learns
      // and the only handle every later command takes.
      [outcome(intent, 'Created'), `${show(created.key)} — ${show(created.name)}`],
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
    + '                    [--precondition ID]... [--postcondition ID]...\n'
    + '                    [--file BODY.json --lock-version N]',
  options: {
    name: { type: 'string' },
    description: { type: 'string' },
    'tag-rule': { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    precondition: { type: 'string', multiple: true },
    postcondition: { type: 'string', multiple: true },
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
          tagRules: completeSet(options['tag-rule']),
          caseIds: await resolveMany(context, projectId, options['case'], 'CASE'),
          /*
            §186's two arrays, reachable from this surface for the first time. Complete sets on
            the same rule as the two above, and `core/rewrite.ts` is why they can only be that:
            `suiteInputFrom` cannot project them back (the read serves `CaseConditionRef` and the
            write takes `CaseConditionInput`), so what the caller names here is the whole of what
            the suite will impose — `--precondition ''` detaches every one of them.

            Library ids, which `tcms suite show` prints in its first column. Its second column is
            the *attachment* id, which is what `case edit --decline` takes; the two live one
            column apart because they are the two halves of §6f and neither is usable in the
            other's flag.
          */
          preconditions: conditionsFrom(options['precondition']),
          postconditions: conditionsFrom(options['postcondition']),
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
  usage: 'tcms doc create --title T [--parent DOC-n | --root] [--file BLOCKS.json] [--idempotency-key K]',
  options: {
    title: { type: 'string' },
    parent: { type: 'string' },
    // Explicit here and load-bearing on `doc edit`: absent and null are different requests.
    root: { type: 'boolean' },
    file: { type: 'string' },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'doc:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
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

    const intent = createIntent({ key: text(options['idempotency-key']) });
    const created = await client.POST('/projects/{projectId}/doc-pages', {
      params: { path: { projectId } },
      body: present({
        ...fromFile,
        title,
        parentId: await parentIdFrom(context, projectId),
      }) as components['schemas']['DocPageInput'],
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, created, [
      [outcome(intent, 'Created'), `${show(created.key)} — ${show(created.title)}`],
      ['Status', show(created.status)],
      ['Blocks', show(created.blocks.length)],
      ['Lock version', show(created.lockVersion)],
    ]);
  },
};

const docEdit: Command = {
  name: 'doc edit',
  summary: 'Retitle a page, or replace its blocks',
  usage: 'tcms doc edit DOC-1 [--title T] [--parent DOC-n | --root] [--file BODY.json --lock-version N]',
  options: {
    title: { type: 'string' },
    /*
      Re-parenting had no spelling here at all until the catalogue↔surface pin asked for one: the
      field is on `DocPageInput`, `tcms_write_doc_page` accepts it on its edit arm, and `tcms
      format docPage` printed it — so this command was the only place that said a page's position
      could not be changed, and said it by omission.
    */
    parent: { type: 'string' },
    root: { type: 'boolean' },
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
        present({
          title: text(options['title']),
          parentId: await parentIdFrom(context, projectId),
        }),
        'Pass --title or --parent, or --file with the page body.',
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

    const intent = createIntent({ key: text(options['idempotency-key']) });
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
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, created, [
      [outcome(intent, 'Created'), `${show(created.key)} — ${show(created.name)}`],
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
    + '                [--release V] [--build B] [--branch B] [--commit SHA]\n'
    + '                [--idempotency-key K]',
  options: {
    name: { type: 'string' },
    plan: { type: 'string' },
    suite: { type: 'string', multiple: true },
    case: { type: 'string', multiple: true },
    tag: { type: 'string', multiple: true },
    release: { type: 'string' },
    build: { type: 'string' },
    // §177's Git traceability. `--commit` is the field a CI job filling this in from a shell has
    // ready (`--commit "$(git rev-parse HEAD)"`), which is most of why the flags exist here at all:
    // this command is the operator-and-pipeline path, and the browser form is the other one.
    branch: { type: 'string' },
    commit: { type: 'string' },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'run:create' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const plan = text(options['plan']);

    const intent = createIntent({ key: text(options['idempotency-key']) });
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
        tags: completeSet(options['tag']),
        releaseVersion: nullableText(options['release']),
        buildNumber: nullableText(options['build']),
        commitSha: nullableText(options['commit']),
        branch: nullableText(options['branch']),
      }) as components['schemas']['TestRunInput'],
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, created, [
      [outcome(intent, 'Created'), `${show(created.key)} — ${show(created.name)}`],
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
  // ~~`run:close` is Lead and up and an Agent token does not hold it — the command exists because
  // this CLI is a person's tool too.~~ → **false since `V52`** (§210): `run:close` is a
  // contributor's and an `Agent` token holds it. The command needed no change, which is the point
  // of gating on the server; what was wrong was the sentence. Same call as `suite status`, one
  // noun over.
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
  usage: 'tcms result record TR-1 TC-42 --status Passed [--comment TEXT] [--duration-ms N]',
  options: {
    status: { type: 'string' },
    comment: { type: 'string' },
    // Milliseconds, and named so. Absent leaves the duration alone; `--duration-ms ''` clears it,
    // the same two-flags-in-one shape every nullable field on this CLI uses.
    'duration-ms': { type: 'string' },
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
        durationMs: nullableNumber(options['duration-ms'], '--duration-ms'),
      }) as components['schemas']['ExecutionResultInput'],
      tcms: { ...request, ...editIntent(version, 'record this result') },
    });

    reportWrite(out, json, recorded, [
      ['Recorded', `${show(recorded.caseKey)} — ${show(recorded.status?.name)}`],
      ['Run', show(detail.key)],
      ['Duration', show(recorded.durationMs)],
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

/* ------------------------------------------------------------------- issues ---- */

/**
 * A type or status **named**, resolved against the project's own workflow.
 *
 * `IssueInput.typeId` and `IssueTransitionInput.statusId` are UUIDs of per-project rows and
 * nobody at a terminal has one to paste — the same argument `result record --status` already
 * makes for `case_status`. An unknown name is refused here rather than sent, because the
 * server's answer would be a 400 about an id the caller never chose.
 *
 * Only the vocabulary is checked. Which moves are legal from where the issue stands is
 * {@link requireStatus}'s rule one noun over: a client-side ladder would be a second copy of the
 * one rule `tracker` exists to enforce, and the server's 409 names the moves that are allowed.
 */
function namedInWorkflow<T extends { id: string; name: string }>(
  rows: readonly T[],
  wanted: string,
  what: string,
): T {
  const found = rows.find(
    (row) => row.name.toLowerCase() === wanted.toLowerCase() || row.id === wanted,
  );
  if (!found) {
    throw new UsageError(
      `No ${what} '${wanted}' in this project's workflow. It offers: `
        + `${rows.map((row) => row.name).join(', ')}.`,
    );
  }
  return found;
}

const issueCreate: Command = {
  name: 'issue create',
  summary: 'Raise an epic, story, bug, spike or task',
  usage: `tcms issue create --type Story --title "Sign-out clears the session"\n\n${usageFor(ISSUE_SHAPE, 'create')}`,
  // Rendered from the catalogue, so a field added there cannot be a flag this command refuses in
  // strict mode — the defect `format.test.ts`'s header records finding on three commands at once.
  options: optionSpecFor(ISSUE_SHAPE, 'create'),
  write: { kind: 'create', permission: 'issue:create' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);

    const workflow = await client.GET('/projects/{projectId}/workflow', {
      params: { path: { projectId } }, tcms: request,
    });
    const type = namedInWorkflow(
      workflow.types, requireOption(context, 'type'), 'issue type',
    );

    const intent = createIntent({ key: text(options['idempotency-key']) });
    const created = await client.POST('/issues', {
      body: present({
        projectId,
        typeId: type.id,
        title: requireOption(context, 'title'),
        description: nullableText(options['description']),
        parentKey: nullableText(options['parent']),
        priority: text(options['priority']) as components['schemas']['Priority'] | undefined,
        estimateHours: nullableFraction(options['estimate-hours'], '--estimate-hours'),
        startDate: nullableText(options['start-date']),
        targetDate: nullableText(options['target-date']),
        dueDate: nullableText(options['due-date']),
        acceptanceCriteria: completeSet(options['acceptance-criterion']),
        // Rendered as a flag by `optionSpecFor` from the catalogue field; absent means false, and
        // `present` drops it rather than sending `false` explicitly — the contract defaults it.
        agentEligible: flag(options['agent-eligible']) ? true : undefined,
      }) as components['schemas']['IssueInput'],
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, created, [
      // Server-allocated from the project's own counter — `ACME-1`, `ACME-2` — so it is the first
      // thing the caller learns and the handle every later command takes.
      [outcome(intent, 'Created'), `${show(created.key)} — ${show(created.title)}`],
      ['Type', show(created.type.name)],
      ['Status', show(created.status.name)],
    ]);
  },
};

const issueMove: Command = {
  name: 'issue move',
  summary: 'Move an issue to another status',
  usage: 'tcms issue move ACME-124 --status "In Review"',
  options: { status: { type: 'string' } },
  // There is no `board:move`: dragging a card changes the issue's status and costs exactly this.
  write: { kind: 'state', permission: 'issue:edit' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const key = requireArg(context, 'issue');

    const workflow = await client.GET('/projects/{projectId}/workflow', {
      params: { path: { projectId: await projectOf(context) } }, tcms: request,
    });
    const status = namedInWorkflow(
      workflow.statuses, requireOption(context, 'status'), 'status',
    );

    const moved = await client.POST('/issues/{key}/transitions', {
      params: { path: { key } },
      body: { statusId: status.id },
      tcms: request,
    });

    reportWrite(out, json, moved, [
      ['Issue', `${show(moved.key)} — ${show(moved.title)}`],
      ['Status', show(moved.status.name)],
      ['Can move to', moved.allowedTransitions.map((row) => row.name).join(', ') || '(none)'],
    ]);
  },
};

const issueComment: Command = {
  name: 'issue comment',
  summary: 'Say something about an issue, and start watching it',
  usage: 'tcms issue comment ACME-124 --body TEXT',
  options: { body: { type: 'string' } },
  // `state`, not `create`: the endpoint declares no `Idempotency-Key`, so a create kind would send
  // a header nothing reads. `review comment` is declared the same way for the same reason.
  write: { kind: 'state', permission: 'issue:comment' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const key = requireArg(context, 'issue');

    const written = await client.POST('/issues/{key}/comments', {
      params: { path: { key } },
      body: { body: requireOption(context, 'body') },
      tcms: request,
    });

    reportWrite(out, json, written, [
      ['Commented on', key],
      ['Author', showAuthor(written)],
      // Story H8: commenting makes you a watcher, so a person who has said something hears about
      // what happens next. Printed because it is a side effect nobody asked for by name.
      ['Watching', 'yes — you are notified of status and assignee changes'],
    ]);
  },
};

/* -------------------------------------------------------------------- board ---- */

/**
 * `tcms board move ACME-124 --column "In flight"` — a card to a column, on this project's board.
 *
 * Which board is `boardOf`'s answer, shared with the read verbs rather than re-derived: a second
 * copy of *which board did they mean* is a second answer, and the sentence a person reads when
 * they have two boards has to be the same one from both verbs.
 */
const boardMove: Command = {
  name: 'board move',
  summary: 'Move a card to a column, and report a WIP breach it caused',
  usage: 'tcms board move ACME-124 --column "In flight" [--board "Team board"]',
  options: { column: { type: 'string' }, board: { type: 'string' } },
  // `issue:edit`, and there is no `board:move`: dragging a card changes the issue's status and a
  // second key for the same act would be one somebody could hold without being able to use it.
  write: { kind: 'state', permission: 'issue:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const key = requireArg(context, 'issue');
    const chosen = await boardOf(context, first(options['board']));

    const config = await client.GET('/boards/{boardId}', {
      params: { path: { boardId: chosen.id } },
      tcms: request,
    });
    const wanted = requireOption(context, 'column');
    const column = config.columns.find(
      (candidate) => candidate.name.toLowerCase() === wanted.toLowerCase() || candidate.id === wanted,
    );
    if (!column) {
      throw new UsageError(
        `No column '${wanted}' on ${chosen.name}. It has: `
          + `${config.columns.map((candidate) => candidate.name).join(', ')}.`,
      );
    }

    const moved = await client.POST('/boards/{boardId}/cards/{key}/move', {
      params: { path: { boardId: chosen.id, key } },
      // The column, never a status: which of the statuses a coarse column claims the card takes is
      // the server's to resolve (D2), and a client that picked one would need the mapping this
      // endpoint exists to keep server-side.
      body: { toColumnId: column.id },
      tcms: request,
    });

    reportWrite(out, json, moved, [
      ['Moved', `${show(moved.card.key)} — ${show(moved.card.title)}`],
      ['Column', show(column.name)],
      ['Status', show(moved.card.status.name)],
    ]);

    /*
      **Printed, and the command still exits 0.** The move succeeded — a breach is a 200 carrying a
      fact — and a non-zero exit would make a limit somebody's script fails on, which is story B3's
      rejected behaviour in its automation spelling: a limit people route around by not using the
      board. Written through `error` so a `--json | jq` pipeline is not poisoned by it.
    */
    if (moved.breach) {
      out.error(
        `Over the limit: ${show(column.name)} now holds ${String(moved.breach.count)} of a limit of `
          + `${String(moved.breach.limit)}. The move was accepted.`,
      );
    }
  },
};

/**
 * `tcms rank ACME-124 --after ACME-99` — place an issue between two neighbours.
 *
 * **Exactly one of the two flags, refused locally rather than sent.** The endpoint answers 400 for
 * both-or-neither, and a round trip to learn that is one a `--help` line could have saved; more to
 * the point, the server's message names `afterKey` and `beforeKey`, which are fields of a JSON body
 * nobody at a terminal typed. The top of the list is reachable — it is `--before` the current first
 * key — so requiring one flag closes nothing off.
 */
/**
 * Exactly one neighbour, as the body the contract takes — and the refusal, before any request.
 *
 * The endpoint answers 400 for both-or-neither, so nothing is closed off by insisting here; what is
 * gained is the wording. The server's message names `afterKey` and `beforeKey`, which are fields of
 * a JSON body a person at a terminal never composed. The top of the list is still reachable, as
 * `--before` the current first key.
 */
function placementFor(
  after: string | undefined,
  before: string | undefined,
): { afterKey: string } | { beforeKey: string } {
  if (after !== undefined && before === undefined) return { afterKey: after };
  if (before !== undefined && after === undefined) return { beforeKey: before };
  throw new UsageError('Give exactly one of --after or --before.');
}

const rank: Command = {
  name: 'rank',
  summary: 'Place an issue immediately after or before another in the backlog order',
  usage: 'tcms rank ACME-124 --after ACME-99 | --before ACME-98',
  options: { after: { type: 'string' }, before: { type: 'string' } },
  write: { kind: 'state', permission: 'board:rank' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const key = requireArg(context, 'issue');
    const after = first(options['after']);
    const before = first(options['before']);

    const placed = await client.PUT('/issues/{key}/rank', {
      params: { path: { key } },
      body: placementFor(after, before),
      tcms: request,
    });

    reportWrite(out, json, placed, [
      ['Placed', show(placed.key)],
      [after === undefined ? 'Before' : 'After', show(after ?? before)],
    ]);

    /*
      A **success** carrying an instruction, not a failure: the gap ran out of precision and the
      project's ranks were respaced, so every other row's rank is now stale. Exits 0 for the same
      reason a WIP breach does — the placement happened and it is where it was asked to be.
    */
    if (placed.rebalanced) {
      out.error(
        'The backlog was respaced to make room, so every other row\'s rank has changed. '
          + 'Read the list again before ranking anything else against it.',
      );
    }
  },
};

const labelAdd: Command = {
  name: 'label add',
  summary: 'Create a label',
  usage: 'tcms label add --name Flaky --color label-3',
  options: { name: { type: 'string' }, color: { type: 'string' } },
  // No `Idempotency-Key`: `POST …/labels` declares none, and a duplicate name is a 409 rather than
  // a second row, which is the property a key would have bought.
  write: { kind: 'state', permission: 'board:configure' },
  run: async (context) => {
    const { client, out, json, request } = context;

    const created = await client.POST('/projects/{projectId}/labels', {
      params: { path: { projectId: await projectOf(context) } },
      // A design-token name — `label-1` … `label-10` — never a hex value: a stored `#RRGGBB` is
      // invisible in the other colour scheme, which is the argument the contract makes on the field.
      body: { name: requireOption(context, 'name'), color: requireOption(context, 'color') },
      tcms: request,
    });

    reportWrite(out, json, created, [['Created', show(created.name)], ['Colour', show(created.color)]]);
  },
};

/**
 * `tcms label set ACME-124 --labels Flaky,Regression` — the labels the issue should carry.
 *
 * **A replacement, not an addition.** Omitting a label removes it, and `--labels ''` clears them
 * all. That is the endpoint's own shape and its reason: an add/remove pair over a multi-select lets
 * a stale client remove a label somebody else has just added.
 */
const labelSet: Command = {
  name: 'label set',
  summary: 'Replace an issue\'s labels with exactly the ones named',
  usage: 'tcms label set ACME-124 --labels Flaky,Regression',
  options: { labels: { type: 'string', multiple: true } },
  write: { kind: 'state', permission: 'issue:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const key = requireArg(context, 'issue');
    const names = all(options['labels']).flatMap((value) => value.split(','))
      .map((name) => name.trim())
      .filter((name) => name !== '');

    const after = await client.PUT('/issues/{key}/labels', {
      params: { path: { key } },
      body: { labelIds: await labelIds(context, names) },
      tcms: request,
    });

    reportWrite(out, json, after, [
      ['Issue', key],
      ['Labels', after.map((one) => one.name).join(', ') || '(none)'],
    ]);
  },
};

/* ----------------------------------------------------------------- releases ---- */

const releaseCreate: Command = {
  name: 'release create',
  summary: 'Plan a version',
  usage: 'tcms release create --version 2.6 [--date 2026-11-14] [--description TEXT]',
  options: {
    version: { type: 'string' },
    date: { type: 'string' },
    description: { type: 'string' },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'release:manage' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);

    const intent = createIntent({ key: text(options['idempotency-key']) });
    const created = await client.POST('/projects/{projectId}/releases', {
      params: { path: { projectId } },
      body: present({
        version: requireOption(context, 'version'),
        description: nullableText(options['description']),
        // A date in the past is accepted by the contract on purpose — a release that slipped is a
        // real state — so nothing here checks it.
        targetDate: nullableText(options['date']),
      }) as components['schemas']['ReleaseInput'],
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, created, [
      [outcome(intent, 'Created'), show(created.version)],
      ['Target date', show(created.targetDate)],
      ['Status', show(created.status)],
    ]);
  },
};

const releaseEdit: Command = {
  name: 'release edit',
  summary: 'Change a release\'s version, date or description',
  usage: 'tcms release edit 2.6 [--version 2.6.1] [--date 2026-11-21] [--description TEXT]',
  options: {
    version: { type: 'string' },
    date: { type: 'string' },
    description: { type: 'string' },
  },
  /*
    An `edit`, and it sends **no `If-Match`**: `PATCH /releases/{releaseId}` declares no such
    parameter and `Release` carries no `lockVersion` on the wire, so there is nothing to
    precondition on. Recorded in `writeConformance.test.ts`'s CREATE_WITHOUT_A_KEY with what it
    costs — the same wall `tcms_write_issue.update` meets, and bounded the same way, because the
    endpoint is a true delta and an absent key means *leave it*.
  */
  write: { kind: 'edit', permission: 'release:manage' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const chosen = await releaseOf(context, requireArg(context, 'release', 0));

    const updated = await client.PATCH('/releases/{releaseId}', {
      params: { path: { releaseId: chosen.id } },
      body: requireSomethingToDo(
        present({
          version: text(options['version']),
          description: nullableText(options['description']),
          targetDate: nullableText(options['date']),
        }),
        'Give --version, --date or --description.',
      ) as components['schemas']['ReleaseUpdate'],
      tcms: request,
    });

    reportWrite(out, json, updated, [
      ['Updated', show(updated.version)],
      ['Target date', show(updated.targetDate)],
      // Renaming the version recomputes the ordering key, so the row moves in the list rather
      // than staying where its old text sorted it. Worth a line because nothing else shows it.
      ['Order', 'recomputed from the version — the row may have moved in the list'],
    ]);
  },
};

/**
 * `tcms release publish 2.4` — mark a version released.
 *
 * A `state`, and it carries no `Idempotency-Key` because the endpoint declares none and does not
 * need one: publishing an already-released release answers 200 with it unchanged and does **not**
 * restamp `releasedAt`. A second press of a button is not a second shipment.
 *
 * **Readiness does not gate it.** A team may ship a version with a known failure and record that
 * they did, so this command sends without reading readiness first — a client-side refusal would be
 * asserting a release policy this product has no way to know.
 */
const releasePublish: Command = {
  name: 'release publish',
  summary: 'Mark a release released',
  usage: 'tcms release publish 2.4',
  write: { kind: 'state', permission: 'release:publish' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const chosen = await releaseOf(context, requireArg(context, 'release', 0));

    const released = await client.POST('/releases/{releaseId}/publish', {
      params: { path: { releaseId: chosen.id } },
      tcms: request,
    });

    reportWrite(out, json, released, [
      ['Released', show(released.version)],
      ['At', show(released.releasedAt)],
    ]);
  },
};

/**
 * `tcms release delete 2.6` — and a `delete` kind, which is what makes it ask first.
 *
 * The issues pointing at it keep existing and lose their target release: the foreign key is
 * `ON DELETE SET NULL`, deliberately, because a release is a *plan* and deleting a plan must not
 * require reassigning the work first. Said on the way out, because a caller who expected the
 * issues to go with it has no other way to find out that they did not.
 */
const releaseDelete: Command = {
  name: 'release delete',
  summary: 'Delete a release, leaving its issues unscheduled',
  usage: 'tcms release delete 2.6 [--yes]',
  write: { kind: 'delete', permission: 'release:manage' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const chosen = await releaseOf(context, requireArg(context, 'release', 0));

    await client.DELETE('/releases/{releaseId}', {
      params: { path: { releaseId: chosen.id } },
      tcms: request,
    });

    // A 204 has no body, so there is nothing verbatim to answer with under `--json`; saying what
    // went beats an empty success a caller cannot tell from a no-op.
    reportWrite(out, json, { deleted: chosen.version }, [
      ['Deleted', show(chosen.version)],
      ['Its issues', 'kept, and now unscheduled — the plan went, the work did not'],
    ]);
  },
};

/**
 * `tcms issue release ACME-118 --release 2.4 | --none` — target a story at a version.
 *
 * A command of its own rather than a flag on a `tcms issue edit`, because there is no such command:
 * §6 records that the field-by-field update stays on the MCP side, where it is composed from a read
 * the model has already made. This is the one field of `IssueUpdate` a person reaches for at a
 * terminal, so it gets the one verb.
 *
 * `--none` rather than `--release ''`: the empty-string clearing form {@link nullableText} uses is
 * right for free text and wrong for a resolved reference, where `''` would have to be resolved
 * against the project's list before it could be recognised as a clear.
 */
const issueRelease: Command = {
  name: 'issue release',
  summary: 'Target an issue at a release, or take it off one',
  usage: 'tcms issue release ACME-118 --release 2.4 | --none',
  options: { release: { type: 'string' }, none: { type: 'boolean' } },
  write: { kind: 'edit', permission: 'issue:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const key = requireArg(context, 'issue');
    const wanted = first(options['release']);
    const clear = flag(options['none']);

    if ((wanted === undefined) === !clear) {
      throw new UsageError('Give exactly one of --release VERSION or --none.');
    }

    const releaseId = clear ? null : (await releaseOf(context, wanted ?? '')).id;

    const updated = await client.PATCH('/issues/{key}', {
      params: { path: { key } },
      body: { releaseId } as components['schemas']['IssueUpdate'],
      tcms: request,
    });

    reportWrite(out, json, updated, [
      ['Issue', `${show(updated.key)} — ${show(updated.title)}`],
      ['Release', updated.release === null || updated.release === undefined
        ? '(unscheduled)'
        : show(updated.release.version)],
    ]);
  },
};

/**
 * `tcms issue cases set ACME-118 --case TC-311 --case TC-308` — the cases the story is proven by.
 *
 * **A replacement, not an addition**, matching the endpoint: the flags are the cases the story
 * should carry afterwards, and `--case` given none clears them. That is `label set`'s shape and
 * its reason — an add/remove pair over a multi-select lets a stale client unlink a case somebody
 * else has just linked.
 *
 * The keys are resolved here, per §4.4: the endpoint takes `caseIds` as UUIDs and neither a person
 * nor a script has one — `TC-311` is what a story lists and what a test plan names.
 */
const issueCasesSet: Command = {
  name: 'issue cases set',
  summary: 'Replace the test cases a story is proven by',
  usage: 'tcms issue cases set ACME-118 --case TC-311 --case TC-308',
  options: { case: { type: 'string', multiple: true } },
  // `issue:edit`: linking a case to a story edits the story, and there is no `link:*` key.
  write: { kind: 'state', permission: 'issue:edit' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const key = requireArg(context, 'issue');
    const projectId = await projectOf(context);

    const caseIds: string[] = [];
    for (const value of all(options['case']).flatMap((one) => one.split(','))) {
      const trimmed = value.trim();
      if (trimmed === '') continue;
      caseIds.push(await resolveId({ client, projectId, value: trimmed, entity: 'CASE' }));
    }

    const after = await client.PUT('/issues/{key}/test-cases', {
      params: { path: { key } },
      body: { caseIds },
      tcms: request,
    });

    reportWrite(out, json, after, [
      ['Issue', key],
      ['Proven by', after.map((one) => one.caseKey).join(', ') || '(nothing)'],
    ]);
  },
};

/**
 * `tcms issue attach-pr ACME-124 --url https://…` — report the pull request the work went into.
 *
 * A command of its own for `issue release`'s reason: there is no `tcms issue edit`, and this is the
 * second field of `IssueUpdate` somebody reaches for at a terminal. **This product never opens a
 * pull request** (D25) — the developer's own git does — so the URL arriving here is the only thing
 * that puts the work on the issue's history, where it lands as `issue.pr_url_set`.
 *
 * **`--pr-url`, not `--url`** — and that is not a style choice. `--url` is a GLOBAL flag naming
 * the instance (`config.ts`), so `attach-pr --url https://github.com/…/pull/482` parses as a
 * command pointed at GitHub, and the request goes to `PATCH /northwind/…/pull/482/api/v1/issues/…`.
 * It exits non-zero, so nothing is silently written; it is still a flag nobody could use, and the
 * contract exchange check is what named it.
 */
const issueAttachPr: Command = {
  name: 'issue attach-pr',
  summary: 'Report the pull request opened for an issue',
  usage: 'tcms issue attach-pr ACME-124 --pr-url https://github.com/northwind/acme-storefront/pull/482',
  options: { 'pr-url': { type: 'string' } },
  write: { kind: 'edit', permission: 'issue:edit' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const key = requireArg(context, 'issue');

    const updated = await client.PATCH('/issues/{key}', {
      params: { path: { key } },
      body: { prUrl: requireOption(context, 'pr-url') } as components['schemas']['IssueUpdate'],
      tcms: request,
    });

    reportWrite(out, json, updated, [
      ['Issue', `${show(updated.key)} — ${show(updated.title)}`],
      ['Pull request', show(updated.prUrl)],
      // Printed because it is the next decision: the work is reported, and where it may go from
      // here is the workflow's answer — which under the review gate will not include a Done status.
      ['Can move to', updated.allowedTransitions.map((row) => row.name).join(', ') || '(none)'],
    ]);
  },
};

/* --------------------------------------------------------------------- time ---- */

/**
 * `tcms time log --issue ACME-124 --hours 2` — an hour of work, against whatever it went into.
 *
 * **Not everything is an issue**, which is why `--against` exists: a meeting, a day of leave,
 * training and internal work are logged in the same place and counted the same way. `timesheets.html`
 * says so on the form. `TEST_RUN` is deliberately not a member (016 D-b) — the approvals mockup
 * displays such a row and the log form cannot produce one, and the form wins because it is the only
 * screen that creates an entry.
 *
 * The period is derived from the date server-side and opened if it does not exist, so nothing has to
 * be started first. A period already submitted refuses the write with a 409 rather than logging into
 * a timesheet somebody is approving.
 */
const timeLog: Command = {
  name: 'time log',
  summary: 'Log hours against an issue, a meeting, leave, training or internal work',
  usage: 'tcms time log --issue ACME-124 --hours 2 [--on 2026-09-01] [--note TEXT]\n'
    + '       tcms time log --against MEETING --hours 1 --note "2.4 readiness review"',
  options: {
    issue: { type: 'string' },
    against: { type: 'string' },
    hours: { type: 'string' },
    on: { type: 'string' },
    note: { type: 'string' },
    'idempotency-key': { type: 'string' },
  },
  write: { kind: 'create', permission: 'time:log' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const subjectType = (first(options['against']) ?? 'ISSUE').toUpperCase();
    const issue = first(options['issue']);

    /*
      Refused here rather than by the server, for the reason `rank` and `doc link` are: the endpoint
      answers 400 either way, and what is gained is a message naming the FLAGS a person typed
      instead of the JSON fields they never composed. Both directions, because sending `--issue`
      with `--against MEETING` is the mistake that would otherwise silently drop the issue.
    */
    if (subjectType === 'ISSUE' && issue === undefined) {
      throw new UsageError('--issue names what the hours went into. For a meeting or a day off, pass --against MEETING | LEAVE | TRAINING | INTERNAL.');
    }
    if (subjectType !== 'ISSUE' && issue !== undefined) {
      throw new UsageError(`--issue is for an issue entry. --against ${subjectType} takes no issue.`);
    }

    const intent = createIntent({ key: text(options['idempotency-key']) });
    const logged = await client.POST('/timesheets/entries', {
      body: present({
        subjectType: subjectType as components['schemas']['TimeSubjectType'],
        issueKey: issue,
        hours: requireHours(context),
        // Today, in the caller's own local date. The period the entry lands in is derived from it
        // server-side (016 D-a), so this is the only thing that decides which timesheet it joins.
        spentOn: first(options['on']) ?? new Date().toISOString().slice(0, 10),
        note: text(options['note']),
      }) as components['schemas']['TimeEntryInput'],
      tcms: { ...request, ...intent },
    });

    reportWrite(out, json, logged, [
      [outcome(intent, 'Logged'), `${show(logged.hours)}h on ${show(logged.spentOn)}`],
      ['Against', logged.subject ? `${show(logged.subject.issueKey)} — ${show(logged.subject.title)}` : show(logged.subjectType)],
      ['Entry', show(logged.id)],
      // Printed because it is the next decision: an entry in a period that is still open can be
      // amended or removed, and one in a submitted period cannot.
      ['Editable', logged.editable ? 'yes' : 'no — the period is submitted'],
    ]);
  },
};

/** The hours flag, refused locally when it is not a number the contract would accept. */
function requireHours(context: CommandContext): number {
  const raw = requireOption(context, 'hours');
  const parsed = Number(raw);
  if (!Number.isFinite(parsed) || parsed < 0.25 || parsed > 24) {
    throw new UsageError(`--hours takes a number of hours between 0.25 and 24, not '${raw}'.`);
  }
  return parsed;
}

/**
 * `tcms time submit <period>` — the period stops being editable, by anybody.
 *
 * `time:log` rather than a fifth permission key (016 D-e): submitting is the same act as logging,
 * one step later, and a key nobody would ever grant separately is a key nobody should have to hold.
 * The id comes from `tcms time list`, which prints it for exactly this.
 */
const timeSubmit: Command = {
  name: 'time submit',
  summary: 'Submit your period for approval — it stops being editable',
  usage: 'tcms time submit tsp-1',
  write: { kind: 'state', permission: 'time:log' },
  run: async (context) => {
    const { client, out, json, request } = context;
    const periodId = requireArg(context, 'period', 0);

    const submitted = await client.POST('/timesheets/periods/{periodId}/submit', {
      params: { path: { periodId } },
      tcms: request,
    });

    reportWrite(out, json, submitted, [
      ['Submitted', `${show(submitted.startsOn)} → ${show(submitted.endsOn)}`],
      ['Hours', `${show(submitted.loggedHours)}h`],
      ['State', show(submitted.state)],
      // Said out loud because it is the thing a person is about to discover: there is no unsubmit.
      // The way back is an approver rejecting it, which unlocks it and leaves their note on it.
      ['Editable', 'no — an approver rejecting it is what unlocks it again'],
    ]);
  },
};

/**
 * `tcms time approve <period>` — approve, reject with a note, or withdraw a rejection.
 *
 * One command over the contract's three decisions because they are one act with three outcomes, and
 * the id is the same handle for all three. **A rejection is never silent**: `--reject` demands
 * `--note`, which is what the period's owner sees at the top of their unlocked timesheet.
 *
 * The period is approved WHOLE, including entries against projects this caller does not hold
 * `time:approve` for (016 D-i). That is stated in the output rather than hidden: `time:approve` is a
 * project-scope grant and a period spans projects, and per-project partial approval is a state no
 * screen can render.
 */
const timeApprove: Command = {
  name: 'time approve',
  summary: 'Approve a submitted period, reject it with a note, or withdraw a rejection',
  usage: 'tcms time approve tsp-1\n'
    + '       tcms time approve tsp-1 --reject --note "the 8h on 24 Aug is against an epic on hold"\n'
    + '       tcms time approve tsp-1 --withdraw',
  options: { reject: { type: 'boolean' }, withdraw: { type: 'boolean' }, note: { type: 'string' } },
  write: { kind: 'state', permission: 'time:approve' },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const periodId = requireArg(context, 'period', 0);
    const reject = flag(options['reject']);
    const withdraw = flag(options['withdraw']);

    // Exactly-one-of, refused before the request — `rank`'s rule. Approving and rejecting in one
    // invocation is not a request the server can disambiguate, and it must not pick one.
    if (reject && withdraw) throw new UsageError('Pass one of --reject or --withdraw, not both.');

    const decided = await client.POST('/timesheets/periods/{periodId}/decision', {
      params: { path: { periodId } },
      body: present({
        decision: (reject ? 'REJECT' : withdraw ? 'WITHDRAW_REJECTION' : 'APPROVE') as 'APPROVE' | 'REJECT' | 'WITHDRAW_REJECTION',
        note: reject ? requireOption(context, 'note') : undefined,
      }) as components['schemas']['TimesheetDecisionInput'],
      tcms: request,
    });

    reportWrite(out, json, decided, [
      ['Period', `${show(decided.personName)} — ${show(decided.startsOn)} → ${show(decided.endsOn)}`],
      ['State', show(decided.state)],
      ['Hours', `${show(decided.loggedHours)}h logged, ${show(decided.approvedHours)}h approved`],
      [
        'Effect',
        decided.state === 'APPROVED'
          ? 'posted onto each issue and rolled up to the story, the epic and the project — the whole period, every project in it'
          : decided.state === 'REJECTED'
            ? 'unlocked for its owner, with your note at the top of it'
            : 'back with its owner as submitted; the note stays as history',
      ],
    ]);
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
  planCreate,
  planEdit,
  planStatus,
  runCreate,
  runStatus,
  resultRecord,
  reviewComment,
  issueCreate,
  issueMove,
  issueComment,
  boardMove,
  rank,
  labelAdd,
  labelSet,
  releaseCreate,
  releaseEdit,
  releasePublish,
  releaseDelete,
  issueRelease,
  issueCasesSet,
  issueAttachPr,
  timeLog,
  timeSubmit,
  timeApprove,
];
