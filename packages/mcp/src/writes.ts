/**
 * The write tools — ten of the twenty-one, *edits direct and creates as proposals*.
 *
 * Three rules hold across all of them and none of them is implemented here: a **create** carries
 * `Idempotency-Key`, an **edit** carries `If-Match`, and a **state** transition carries neither
 * because its precondition is the state rather than a version the client read. They live in
 * `@tcms/client`'s `core/mutation.ts` so this surface and the CLI cannot disagree about them, and
 * `writeConformance.test.ts` asserts each arm sends what its declaration implies — and, since §205,
 * that the operation it writes to declares the header at all.
 *
 * ## Where the version comes from, and the one place it must be demanded
 *
 * §139's rule: **a self-read `If-Match` is only honest when the request is not a delta.** Almost
 * everything here is a delta — the tool takes named fields, `PATCH` leaves the rest alone, so a
 * version read immediately before the write is a truthful precondition. `lockVersion` is accepted
 * everywhere anyway and is strictly better when the model has one, because it also catches a
 * change between the read that informed the edit and the edit itself.
 *
 * ~~"`PATCH` leaves the rest alone"~~ → **false of three of these endpoints, and §143 drove it.**
 * The case, suite and plan `PATCH`es take the whole editable set: an omitted required field is a
 * `400` about something the caller never named, and an omitted nullable scalar is *cleared*.
 * (**B1 narrowed this to two**: the case endpoint leaves an unmentioned field alone now. The suite
 * and the plan still clear, the four fields `TestCaseInput` requires are still required, and the
 * generated types demand them regardless — so all three arms still compose the whole set.) Those
 * three arms therefore read the resource and lay the model's change on top —
 * `@tcms/client`'s `core/rewrite.ts`, shared with the CLI, and it carries the argument for why
 * doing so does **not** reopen the `If-Match` hole above (the base and the version come from the
 * same read). The sentence stays true of the rest of this file.
 *
 * **`tcms_write_doc_page`'s `blocks` is the exception, and it is demanded rather than accepted.**
 * A block omitted from that array is *deleted*, so a caller sending blocks is necessarily echoing
 * back content it read earlier — the body's content came from a read, which is exactly §139's
 * whole-body case. A fresh version there would assert a state that body was never based on, the
 * server would accept it, and the concurrent author's paragraphs would be gone.
 *
 * ## Two arms the surface spec lists as buildable are not, and neither is a shortfall here
 *
 * **Evidence.** `tcms_record_result` was specified as `run:execute` + `result:submit` +
 * `evidence:attach`. `POST …/evidence` accepts **`multipart/form-data` only** — it takes a file —
 * and MCP tool arguments are JSON. A model has no bytes to send. So the tool ships the two arms
 * it can and declares the two permissions it uses; declaring the third would hide the tool from a
 * caller who can record results and cannot attach evidence, which is the failure §2's rule exists
 * to prevent, arriving through a permission the tool never exercises.
 *
 * **Assigning a reviewer.** `POST …/reviews/{id}/assignees` needs a `userId` or a `teamId`, and
 * this surface has no way to obtain either: §3.3 excludes everything under `/users` and `/teams`
 * because they are `COMPANY`-scope, and `V40` §6 withholds `company:read` from `Agent`
 * deliberately — *a token is not a member*. A tool taking a UUID nobody on this surface can look
 * up is a tool that can only be called with a UUID pasted in by a human, which is the CLI's job.
 * So `tcms_review_verdict` records verdicts and resolves threads, and does not assign.
 *
 * Both are consequences of decisions taken elsewhere and are listed as **Buildable** in the
 * surface spec's §3 table. They are recorded in §140's section file rather than quietly dropped.
 *
 * ## ~~The two delete keys this surface still cannot reach~~ → one, since C10 — §211
 *
 * `V52` gave the credential five (§210), and ~~three~~ **four** of them are arms here:
 * `case:delete`, `suite:delete`, `plan:delete` and now `artifact:delete`. ~~The other two are~~
 * The other one is absent by decision.
 *
 * ~~**`artifact:delete`** has no tool because *artifacts* have none — no read, no write, nothing.
 * Adding a delete alone would be a tool whose id can only come from somewhere else, which is the
 * reviewer-assignment shape above. It waits for a slice that gives the register a surface.~~
 * → **that slice is audit C10**, and the condition it named is what it satisfied rather than
 * waived: `tcms_list_artifacts` serves the ids, so the delete's argument exists on this surface
 * the way an execution's `evidence[].id` does and a `userId` does not. The register's whole
 * surface arrived together — read, register, edit, status, delete — for exactly that reason.
 *
 * Two shortfalls came with it and both are the multipart one again, not new decisions.
 * **`FILE` cannot be registered here** — the bytes travel with the registration and a tool call
 * carries none, the same wall `tcms_record_result` meets at evidence — so `register` derives its
 * kind from the three payloads it *can* name. And the **delete is hard for a `FILE`**: the bytes
 * go with the row. That is not the evidence argument repeated, because here the surface can also
 * *create* the row it removes, and archiving is offered beside it as the reversible act.
 *
 * **`evidence:delete`** is reachable — `tcms_open_run` serves an execution's `evidence[].id`, so
 * unlike a `userId` the argument exists — and is excluded anyway. Two facts decide it together.
 * The delete is **hard**: the bytes leave the disk, which the contract argues for deliberately
 * (a screenshot removed for privacy reasons must actually go). And this surface **cannot attach
 * evidence at all** — `POST …/evidence` is `multipart/form-data` and a tool call carries no bytes.
 * So the only evidence an agent could remove is a person's, through the one irreversible delete on
 * the surface, offered to the one actor that can never create the thing. The consent prompt does
 * not change that arithmetic; it asks a person to approve a loss nothing can undo. If it is ever
 * wanted, it wants the upload arm first.
 *
 * ## The replay these tools do not report, and why the CLI does
 *
 * §204 gave `createIntent` a reader for the 200-instead-of-201 that says an `Idempotency-Key` had
 * already carried the intent out. **The CLI uses it and these tools do not**, and the difference is
 * not laziness on this side: a CLI create *states in words* what it did (`Created  TS-7 — Smoke`),
 * so on a replay that word was false. These answer `ok(resource)` — JSON, no prose, by `result.ts`'s
 * argument that the reader is a model composing the next call — and the resource a replay returns is
 * the resource a create returns, identically and correctly. Nothing here says anything untrue.
 *
 * Adding a `replayed` key beside it would be this adapter putting a field the contract does not
 * declare into a contract-shaped body, which is what a model would then quote back into its next
 * call. If a tool ever grows prose, this is the fact it has to carry.
 */
import {
  CASE_SHAPE,
  caseInputFrom,
  createIntent,
  jsonSchemaFor,
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
  InputError,
  bool,
  idProperty,
  nullableNum,
  nullableStr,
  num,
  present,
  requireStr,
  str,
  strings,
} from './input.js';
import type { Session } from './session.js';
import type { ToolDefinition } from './tool.js';

/* ----------------------------------------------------------------- helpers ---- */

/**
 * The version an edit sends.
 *
 * `lockVersion` when the caller has one — the model almost always does, because it read the
 * resource to compose the edit — and a fresh self-read otherwise, which is honest for a delta.
 * See the module header for the one arm that may not take this route.
 */
async function versionFor(
  input: Record<string, unknown>,
  read: () => Promise<unknown>,
): Promise<EditVersion> {
  const explicit = num(input, 'lockVersion');
  if (explicit === undefined) return { fresh: await read() };
  if (!Number.isInteger(explicit) || explicit < 0) {
    throw new InputError(`'lockVersion' takes a whole number; ${String(explicit)} is not one.`);
  }
  return { explicit };
}

/** Display keys to ids, for the fields that take a complete set. Sequential — see the CLI's. */
async function resolveMany(
  session: Session,
  projectId: string,
  values: readonly string[] | undefined,
  entity: 'CASE' | 'SUITE',
): Promise<string[] | undefined> {
  if (values === undefined) return undefined;
  const ids: string[] = [];
  for (const value of values) {
    ids.push(await resolveId({ client: session.client, projectId, value, entity }));
  }
  return ids;
}

/** Library ids as the input the write takes — `conditionId` is the only field a caller may fill. */
function conditionsFrom(values: readonly string[] | undefined): { conditionId: string }[] | undefined {
  return values?.map((conditionId) => ({ conditionId }));
}

const LOCK_VERSION_PROPERTY = {
  type: 'integer',
  description:
    'The lockVersion from the read this edit is based on. Pass what tcms_open_case / '
    + 'tcms_list_suites / tcms_open_doc_page returned: it is what stops this overwriting a '
    + 'colleague who saved while you were composing.',
} as const;

/**
 * A suite's condition list, as an array of **library** ids.
 *
 * Taken as bare ids rather than as the `{conditionId}` objects the contract's `CaseConditionInput`
 * declares, which is this surface's rule and not a shortcut: `additionalProperties: false` cannot
 * be stated usefully for a nested object a model has never seen, `inlineBody` is a key that exists
 * only to be refused, and position is the array's order and is never sent. So the object has
 * exactly one field a caller may fill, and asking for the wrapper buys a shape to get wrong.
 * `tcms_list_library` is where the ids come from, and it is the same read the CLI's
 * `tcms library list` makes.
 */
const CONDITION_PROPERTY = (kind: 'setup' | 'cleanup') => ({
  type: 'array',
  items: { type: 'string' },
  description:
    `COMPLETE set of ${kind} condition ids from tcms_list_library — every one this suite should `
    + 'impose after the save, so `[]` detaches them all and omitting the field leaves them alone. '
    + 'The suite\'s conditions reach the cases filed in it; a case may decline one through '
    + 'tcms_write_case. These are LIBRARY ids (`conditionId`), not the attachment ids a decline '
    + 'takes.',
});

const ACTION = (values: readonly string[], description: string) => ({
  type: 'string',
  enum: [...values],
  description,
});

/* ------------------------------------------------------------------- cases ---- */

const writeCase: ToolDefinition = {
  name: 'tcms_write_case',
  title: 'Propose a new test case, or change an existing one',
  description:
    'Three actions. `propose` files a GENERATED case for a person to accept — it does not create '
    + 'a test case, and nothing on this surface can: a credential cannot accept its own proposal. '
    + '`edit` changes an existing case directly. `submit` puts one into review. Use `edit` when '
    + 'you are correcting a case that exists and `propose` when you are adding coverage; the line '
    + 'is whether a display key would have to be allocated. `delete` removes one: it is SOFT, so '
    + 'past runs keep resolving what they covered and the key stays reserved for ever, and the '
    + 'host will ask a person before it happens.',
  // Three keys merged, and §134 is what makes the first two safe: `case:create` without
  // `case:edit` exists in exactly one role, `Automation Service`, which is non-assignable since
  // V41 and never carries this surface. Had the two token roles been collapsed, this merge would
  // list a create-only caller a tool whose edit arm 403s.
  //
  // `case:delete` joins them on the same test and it is `V52` that passes it (§210, §211): the
  // key was Test Engineer's already but `OWN`-qualified until `V51`, and no seeded role now holds
  // `case:edit` without it. Before `V52` this merge would have been wrong for a different key —
  // `suite:delete` was Lead-only while `suite:manage` was a contributor's, so folding the two
  // would have hidden `tcms_write_suite` from every Test Engineer. Widening the tier is what made
  // the deletes mergeable at all, rather than three tools nobody but a lead would ever see.
  gate: { mode: 'all', permissions: ['case:create', 'case:edit', 'case:delete'] },
  arms: [
    { action: 'propose', kind: 'create', permission: 'case:create' },
    { action: 'edit', kind: 'edit', permission: 'case:edit' },
    { action: 'submit', kind: 'state', permission: 'case:edit' },
    { action: 'delete', kind: 'delete', permission: 'case:delete', targets: ['case'] },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['propose', 'edit', 'submit', 'delete'], 'What to do.'),
      case: idProperty('case to edit, submit or delete — required for those, refused for propose', 'TC-42'),
      testCase: {
        ...jsonSchemaFor(CASE_SHAPE, 'propose', 'body'),
        description:
          'The generated case, for `propose`. Its fields are declared below rather than described '
          + 'here, which is the point of the change: a proposal is stored UNVALIDATED and checked '
          + 'only when a person accepts it, so a missing field is not refused here — it becomes a '
          + 'refusal on a screen you are not at, weeks later, that the reviewer cannot fix without '
          + 'rewriting your case. Call tcms_shape for the pitfalls that do not fit in a schema.',
      },
      verdict: {
        type: 'string',
        description:
          'For `propose`: your own claim about whether you checked this against the spec. Leave '
          + 'it out if you did not check — absent and UNVERIFIED are different answers, and the '
          + 'difference is recorded.',
      },
      /*
        The `edit` arm's fields, rendered from the catalogue rather than written out — which is
        how `automationKey` arrived. It was on the CLI's `case edit` and missing here, so this
        surface could set `automationStatus: AUTOMATED` and had no way to send the key the server
        requires alongside it: a guaranteed 400 that no test asked about, because both adapters
        were hand-maintained and nothing compared them.

        Every one of them is optional even though the server requires four, because the arm reads
        the case and sends what it already had for anything you do not name — see the run body.
      */
      ...(jsonSchemaFor(CASE_SHAPE, 'edit', 'body')['properties'] as Record<string, unknown>),
      lockVersion: LOCK_VERSION_PROPERTY,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');

    if (action === 'propose') {
      /*
        Two shapes accepted, because two callers exist: a model holds the case it generated, and
        the API takes an `AiProposalInput` wrapping it. Detecting the wrapper costs one key.
      */
      const raw = input['testCase'];
      if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
        throw new InputError("'testCase' is required for propose, as an object.");
      }
      const wrapped = ('testCase' in raw ? raw : { testCase: raw }) as Record<string, unknown>;

      /*
        Refused here rather than filed, because filing it WORKS: this endpoint stores the payload
        unvalidated and the create rules run at accept, so a missing field is not your 400 — it
        is a 400 on a reviewer's screen, weeks later, against a proposal they cannot edit, whose
        only remaining move is Reject. §143 drove exactly that. `core/proposal.ts` argues it.
      */
      const submitted = wrapped['testCase'];
      requireProposableCase(
        submitted && typeof submitted === 'object' && !Array.isArray(submitted)
          ? submitted as Record<string, unknown>
          : {},
        "'testCase'",
      );

      const verdict = str(input, 'verdict');

      const filed = await session.client.POST('/projects/{projectId}/ai-proposals', {
        params: { path: { projectId } },
        body: {
          ...wrapped,
          // Deliberately not defaulted: absent and `UNVERIFIED` are different, and they differ
          // in `verdictSource` — nobody claimed anything, against a model claiming it had not
          // checked. Filling this in would turn every silence into a claim.
          ...(verdict === undefined ? {} : { verdict }),
        } as components['schemas']['AiProposalInput'],
        tcms: { ...session.request, ...createIntent({ key: session.callId }) },
      });

      return {
        proposalId: filed.id,
        status: filed.status,
        verdict: filed.verdict,
        verdictSource: filed.verdictSource,
        // Printed together, always: a verdict without its source reads as a finding.
        note: 'Filed for review. It is not a test case, and this credential cannot accept it — '
          + 'the accept gate is "case:create AND no API token", so a person must.',
      };
    }

    const caseId = await resolveId({
      client: session.client, projectId, value: requireStr(input, 'case'), entity: 'CASE',
    });
    const path = { projectId, caseId };

    if (action === 'submit') {
      /*
        Submitting is `POST …/cases/bulk` with `SUBMIT_FOR_REVIEW`, which the server aliases to
        `SET_STATUS` at the parse boundary — there is no per-case status endpoint and
        `TestCaseInput` carries no `lifecycleStatus`, deliberately (a status an ordinary create
        could set is an approval its author granted themselves).

        §3.3 excludes *wrapping bulk* as a tool because its gate is decided by its payload. This
        is not that: the action is fixed at `SUBMIT_FOR_REVIEW`, whose gate is `case:edit`
        unconditionally, so nothing about the permission depends on what the caller sent. The
        approve moves stay unreachable, which is right — `Agent` holds no `*:approve`.
      */
      const result = await session.client.POST('/projects/{projectId}/cases/bulk', {
        params: { path: { projectId } },
        body: { action: 'SUBMIT_FOR_REVIEW', caseIds: [caseId], dryRun: false },
        tcms: session.request,
      });
      return result;
    }

    if (action === 'delete') {
      /*
        No `If-Match`, which the contract states on the operation and is worth reading before
        somebody "fixes" it: there is no lost-delete for a precondition to prevent, and a stale
        version would refuse the delete over an edit the caller does not care about.

        The consent prompt has already run by the time this is reached — `server.ts` gates on the
        arm's `kind`, before `run` — so a client that cannot be asked never gets here.
      */
      await session.client.DELETE('/projects/{projectId}/cases/{caseId}', {
        params: { path }, tcms: session.request,
      });
      return {
        deleted: caseId,
        note: 'Soft-deleted. No read returns it, past runs still resolve the version they '
          + 'executed, and the display and automation keys stay reserved. Nothing cascaded: its '
          + 'steps, conditions, tags and attachments are still on the row.',
      };
    }

    if (action !== 'edit') throw new InputError(`Unknown action '${action}'.`);

    const suite = nullableStr(input, 'suite');
    const delta = present({
      title: str(input, 'title'),
      description: nullableStr(input, 'description'),
      objective: nullableStr(input, 'objective'),
      expectedOutcome: nullableStr(input, 'expectedOutcome'),
      priority: str(input, 'priority'),
      severity: nullableStr(input, 'severity'),
      automationStatus: str(input, 'automationStatus'),
      automationKey: nullableStr(input, 'automationKey'),
      notAutomatableReason: nullableStr(input, 'notAutomatableReason'),
      // Null and absent are different requests and JSON can say both: null unfiles the case,
      // absent leaves it where it is. The CLI needed two flags to express this; here it is the
      // shape the caller already sent.
      suiteId: suite === null
        ? null
        : suite === undefined
          ? undefined
          : await resolveId({ client: session.client, projectId, value: suite, entity: 'SUITE' }),
      tags: strings(input, 'tags'),
      /*
        §6f's opt-out (§187's endpoint, §189's argument). A COMPLETE set on `tags`' rule: absent
        leaves the declines alone, `[]` re-imposes every inherited condition this case had turned
        off. `core/rewrite.ts` records why the round-trip does not carry it — a projection states
        what an unchanged save sends, and this is the one place the caller's intent is said.

        Attachment ids, not library condition ids, and nothing here can save a model from
        confusing them: the server validates each against the attachments that currently reach
        this case, so the wrong kind is a field error rather than a silent no-op. The description
        says which is which because that refusal costs a round trip.
      */
      declinedConditionIds: strings(input, 'declinedConditions'),
    });

    if (Object.keys(delta).length === 0) {
      throw new InputError('Nothing to change. Name at least one field.');
    }

    /*
      Read-modify-write: the delta alone was a 400 naming `stepFormat`, `priority` and
      `automationStatus` — fields the model never mentioned and, for `stepFormat`, cannot mention
      through this schema at all. §143 found it against a real server; the stubs answered 200 to
      whatever arrived. Still true after B1: what that changed is the fields nobody names (they are
      left alone rather than cleared), not the four the schema requires. `core/rewrite.ts` has the
      shape and the `If-Match` argument, and the version comes from THIS read.
    */
    const current = await session.client.GET('/projects/{projectId}/cases/{caseId}', {
      params: { path }, tcms: session.request,
    });
    const version = await versionFor(input, async () => current);

    return session.client.PATCH('/projects/{projectId}/cases/{caseId}', {
      params: { path },
      body: { ...caseInputFrom(current), ...delta } as components['schemas']['TestCaseInput'],
      tcms: { ...session.request, ...editIntent(version, 'edit this case') },
    });
  },
};

/* ------------------------------------------------------------------ suites ---- */

const writeSuite: ToolDefinition = {
  name: 'tcms_write_suite',
  title: 'Create a suite, change one, or move it through review',
  description:
    'A suite is a FLAT named collection, not a folder. Its members are three arms together: cases '
    + 'filed in it, cases added explicitly (`cases`), and cases matched by its tag rules. It also '
    + 'imposes pre/postconditions on the cases filed in it. EVERY list here is a COMPLETE SET — '
    + 'sending `cases` replaces the membership rather than adding to it, and the same holds for '
    + '`tagRules`, `preconditions` and `postconditions`. `status` moves it to DRAFT or IN_REVIEW; '
    + 'APPROVED and DEPRECATED need suite:approve, which this credential does not hold. `delete` '
    + 'removes the collection and is refused while any case is FILED in it — the refusal names '
    + 'how many. A person is asked first.',
  gate: { mode: 'all', permissions: ['suite:manage', 'suite:delete'] },
  arms: [
    { action: 'create', kind: 'create', permission: 'suite:manage' },
    { action: 'edit', kind: 'edit', permission: 'suite:manage' },
    { action: 'status', kind: 'state', permission: 'suite:manage' },
    { action: 'delete', kind: 'delete', permission: 'suite:delete', targets: ['suite'] },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['create', 'edit', 'status', 'delete'], 'What to do.'),
      suite: idProperty('suite — required for edit, status and delete', 'TS-7'),
      name: { type: 'string' },
      description: { type: ['string', 'null'] },
      tagRules: { type: 'array', items: { type: 'string' }, description: 'COMPLETE set of tag rules.' },
      cases: { type: 'array', items: { type: 'string' }, description: 'COMPLETE set of member case keys.' },
      preconditions: CONDITION_PROPERTY('setup'),
      postconditions: CONDITION_PROPERTY('cleanup'),
      status: { type: 'string', description: 'For `status`: DRAFT or IN_REVIEW.' },
      lockVersion: LOCK_VERSION_PROPERTY,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');

    if (action === 'create') {
      return session.client.POST('/projects/{projectId}/suites', {
        params: { path: { projectId } },
        body: present({
          name: requireStr(input, 'name'),
          description: nullableStr(input, 'description'),
          tagRules: strings(input, 'tagRules'),
          caseIds: await resolveMany(session, projectId, strings(input, 'cases'), 'CASE'),
          // On the create as well as the edit: a suite's conditions reach the cases filed in it,
          // so "create it, then attach them" is a window in which those cases run without a
          // setup step their author intended.
          preconditions: conditionsFrom(strings(input, 'preconditions')),
          postconditions: conditionsFrom(strings(input, 'postconditions')),
        }) as components['schemas']['SuiteInput'],
        tcms: { ...session.request, ...createIntent({ key: session.callId }) },
      });
    }

    const suiteId = await resolveId({
      client: session.client, projectId, value: requireStr(input, 'suite'), entity: 'SUITE',
    });
    const path = { projectId, suiteId };

    if (action === 'status') {
      return session.client.PUT('/projects/{projectId}/suites/{suiteId}/status', {
        params: { path },
        body: { status: requireStr(input, 'status') as components['schemas']['LifecycleStatus'] },
        // No `If-Match` and no key: the request names an absolute target state rather than a
        // delta against something the client read, and setting the status a suite already has
        // writes nothing. The contract says so on this endpoint.
        tcms: session.request,
      });
    }

    if (action === 'delete') {
      await session.client.DELETE('/projects/{projectId}/suites/{suiteId}', {
        params: { path }, tcms: session.request,
      });
      /*
        The 409 is not handled here on purpose. `result.ts` renders a refusal from the server's
        `Problem`, whose `detail` names the live filed-case count — which is the thing actually in
        the way and the thing a model needs in order to compose the next call. Catching it to say
        "the suite is not empty" would replace a number with a sentence.
      */
      return {
        deleted: suiteId,
        note: 'Soft-deleted. Nothing was filed in it — that is what the delete checks — and the '
          + 'cases it merely ADDED or matched by a tag rule were never held here, so they are '
          + 'unaffected. Soft rather than hard because past runs key their per-suite summaries on '
          + 'this id.',
      };
    }

    if (action !== 'edit') throw new InputError(`Unknown action '${action}'.`);

    const delta = present({
      name: str(input, 'name'),
      description: nullableStr(input, 'description'),
      tagRules: strings(input, 'tagRules'),
      caseIds: await resolveMany(session, projectId, strings(input, 'cases'), 'CASE'),
      // §186's two arrays, reachable from this surface for the first time. `suiteInputFrom`
      // cannot project them back — the read serves `CaseConditionRef` and the write takes
      // `CaseConditionInput` — so what the caller names here is the whole of what the suite
      // imposes. `core/rewrite.ts`.
      preconditions: conditionsFrom(strings(input, 'preconditions')),
      postconditions: conditionsFrom(strings(input, 'postconditions')),
    });
    if (Object.keys(delta).length === 0) {
      throw new InputError('Nothing to change. Name at least one field.');
    }

    // The case arm's problem, one field wide: `SuiteInput` requires `name`, so editing only the
    // tag rules was a 400 about a field nobody mentioned, and editing only the name emptied the
    // description. `core/rewrite.ts`.
    const current = await session.client.GET('/projects/{projectId}/suites/{suiteId}', {
      params: { path }, tcms: session.request,
    });
    const version = await versionFor(input, async () => current);

    return session.client.PATCH('/projects/{projectId}/suites/{suiteId}', {
      params: { path },
      body: { ...suiteInputFrom(current), ...delta } as components['schemas']['SuiteInput'],
      tcms: { ...session.request, ...editIntent(version, 'edit this suite') },
    });
  },
};

/* --------------------------------------------------------------------- docs ---- */

const writeDocPage: ToolDefinition = {
  name: 'tcms_write_doc_page',
  title: 'Write documentation, and link cases to it',
  description:
    'Five actions: create a page, edit one, link a case or suite to a block, unlink, and `publish` '
    + '— which moves the page between DRAFT, PUBLISHED and ARCHIVED. **A block omitted from '
    + '`blocks` is DELETED** — the array is the whole page in order — so read the page first and '
    + 'send back everything you are keeping, together with the lockVersion that read returned. '
    + 'Linking is how a case is tied to a requirement: this product has no separate criterion '
    + 'record, so the block IS the requirement. Publishing is a separate act from writing and '
    + 'costs a separate key; it is not an approval and does not stand in for a review.',
  /*
    Two keys merged, and it is `V52` (§210) that makes it legal — `doc:publish` was Lead-only as
    "the human's signal that a document is ready" while `doc:edit` is held by everyone who writes,
    so this merge would have hidden the whole tool from every contributor. Checked against §2's
    set rather than the catalogue: no role among `Agent ∩ the five assignable` now holds one of
    these without the other — Viewer holds neither and Test Engineer up hold both — so the arms
    merge, exactly as `suite:delete` did in §211 and for the same reason.

    The keys stay two. Publishing writes no content and editing moves no state, and a company that
    wants them held by different people has the catalogue to arrange it.
  */
  gate: { mode: 'all', permissions: ['doc:edit', 'doc:publish'] },
  arms: [
    { action: 'create', kind: 'create', permission: 'doc:edit' },
    { action: 'edit', kind: 'edit', permission: 'doc:edit' },
    // A link is a create by shape, and the contract offers no `Idempotency-Key` here and needs
    // none: linking the same source to the same block twice writes nothing and answers 200 with
    // the existing link, which is the property a key would have bought.
    { action: 'link', kind: 'state', permission: 'doc:edit' },
    { action: 'unlink', kind: 'delete', permission: 'doc:edit', targets: ['page', 'link'] },
    { action: 'publish', kind: 'state', permission: 'doc:publish' },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['create', 'edit', 'link', 'unlink', 'publish'], 'What to do.'),
      page: idProperty('documentation page — required for everything but create', 'DOC-1'),
      status: {
        type: 'string',
        description:
          'For `publish`: PUBLISHED, DRAFT (unpublish) or ARCHIVED. Setting the status a page '
          + 'already has is not an error.',
      },
      title: { type: 'string' },
      parent: idProperty('parent page, for create', 'DOC-1'),
      blocks: {
        type: 'array',
        items: { type: 'object' },
        description:
          'The WHOLE page in order. A block you omit is deleted. Requires lockVersion.',
      },
      block: { type: 'string', description: 'For `link`: the block id from tcms_open_doc_page.' },
      case: idProperty('case to link', 'TC-42'),
      suite: idProperty('suite to link', 'TS-7'),
      relationship: { type: 'string', description: 'Default VERIFIES.' },
      link: { type: 'string', description: 'For `unlink`: the link id.' },
      lockVersion: LOCK_VERSION_PROPERTY,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');
    const tcms = session.request;

    if (action === 'create') {
      const parent = str(input, 'parent');
      return session.client.POST('/projects/{projectId}/doc-pages', {
        params: { path: { projectId } },
        body: present({
          title: requireStr(input, 'title'),
          parentId: parent === undefined
            ? undefined
            : await resolveId({ client: session.client, projectId, value: parent, entity: 'DOC_PAGE' }),
          blocks: input['blocks'],
        }) as components['schemas']['DocPageInput'],
        tcms: { ...tcms, ...createIntent({ key: session.callId }) },
      });
    }

    const pageId = await resolveId({
      client: session.client, projectId, value: requireStr(input, 'page'), entity: 'DOC_PAGE',
    });
    const path = { projectId, pageId };

    if (action === 'publish') {
      return session.client.PUT('/projects/{projectId}/doc-pages/{pageId}/status', {
        params: { path },
        // Neither header, and the contract is explicit about why this is a separate endpoint
        // rather than a field on the `PATCH`: one endpoint whose gate depends on which fields the
        // body happened to carry is one whose 403 no client can predict. The state is absolute,
        // so there is no delta for an `If-Match` to guard.
        body: {
          status: requireStr(input, 'status') as components['schemas']['DocPageStatusInput']['status'],
        },
        tcms,
      });
    }

    if (action === 'link') {
      const caseKey = str(input, 'case');
      const suiteKey = str(input, 'suite');
      if ((caseKey === undefined) === (suiteKey === undefined)) {
        throw new InputError("Name exactly one of 'case' or 'suite'.");
      }

      return session.client.POST('/projects/{projectId}/doc-pages/{pageId}/blocks/{blockId}/links', {
        params: { path: { projectId, pageId, blockId: requireStr(input, 'block') } },
        body: {
          ...(caseKey === undefined
            ? {
              sourceType: 'SUITE' as const,
              sourceId: await resolveId({ client: session.client, projectId, value: suiteKey ?? '', entity: 'SUITE' }),
            }
            : {
              sourceType: 'TEST_CASE' as const,
              sourceId: await resolveId({ client: session.client, projectId, value: caseKey, entity: 'CASE' }),
            }),
          // Sent explicitly rather than omitted: the generated type makes it required, because
          // `openapi-typescript` reads a schema default as "the server always has one" — true of
          // the stored row and not of the request. This asks for what an omission would have got.
          relationship: (str(input, 'relationship') ?? 'VERIFIES') as
            components['schemas']['BlockLinkInput']['relationship'],
        },
        tcms,
      });
    }

    if (action === 'unlink') {
      const linkId = requireStr(input, 'link');
      await session.client.DELETE('/projects/{projectId}/doc-pages/{pageId}/links/{linkId}', {
        params: { path: { projectId, pageId, linkId } },
        tcms,
      });
      // A 204 has no body, and `undefined` is not something a caller can read.
      return { removed: linkId };
    }

    if (action !== 'edit') throw new InputError(`Unknown action '${action}'.`);

    const blocks = input['blocks'];
    const body = present({ title: str(input, 'title'), blocks });
    if (Object.keys(body).length === 0) {
      throw new InputError("Nothing to change. Pass 'title', or 'blocks' with the page body.");
    }

    /*
      **The one arm that demands a version rather than reading one.** A `blocks` array is the
      whole page and its content came from an earlier read — the caller is echoing back the
      paragraphs it is keeping — so a fresh version would assert a state that body was never
      based on, the server would accept it, and whoever saved in between would lose their words.
      §139's rule, applied to the one place on this surface where it bites. A title-only edit is
      a delta and takes the ordinary route.
    */
    const version = blocks === undefined
      ? await versionFor(input, () =>
        session.client.GET('/projects/{projectId}/doc-pages/{pageId}', { params: { path }, tcms }))
      : requireExplicitVersion(num(input, 'lockVersion'), 'Replacing a page\'s blocks', 'lockVersion');

    return session.client.PATCH('/projects/{projectId}/doc-pages/{pageId}', {
      params: { path },
      body: body as components['schemas']['DocPageInput'],
      tcms: { ...tcms, ...editIntent(version, 'edit this page') },
    });
  },
};

/* -------------------------------------------------------------------- plans ---- */

const writePlan: ToolDefinition = {
  name: 'tcms_write_plan',
  title: 'Create a test plan, change one, or move it through review',
  description:
    'A plan is a named selection of suites and cases against a milestone. `suites` and `cases` '
    + 'are COMPLETE SETS. `status` moves it to DRAFT or IN_REVIEW; APPROVED needs plan:approve, '
    + 'which this credential does not hold. `delete` removes one and is refused while any live '
    + 'run was created from it, naming how many — a plan a run names cannot be deleted and has no '
    + 'terminal state either, so leave it. A person is asked first.',
  gate: { mode: 'all', permissions: ['plan:create', 'plan:edit', 'plan:delete'] },
  arms: [
    { action: 'create', kind: 'create', permission: 'plan:create' },
    { action: 'edit', kind: 'edit', permission: 'plan:edit' },
    { action: 'status', kind: 'state', permission: 'plan:edit' },
    { action: 'delete', kind: 'delete', permission: 'plan:delete', targets: ['plan'] },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['create', 'edit', 'status', 'delete'], 'What to do.'),
      plan: idProperty('plan — required for edit, status and delete', 'TP-1'),
      name: { type: 'string' },
      description: { type: ['string', 'null'] },
      milestoneName: { type: ['string', 'null'] },
      milestoneDate: { type: ['string', 'null'], description: 'YYYY-MM-DD.' },
      suites: { type: 'array', items: { type: 'string' }, description: 'COMPLETE set of suite keys.' },
      cases: { type: 'array', items: { type: 'string' }, description: 'COMPLETE set of case keys.' },
      status: { type: 'string', description: 'For `status`: DRAFT or IN_REVIEW.' },
      lockVersion: LOCK_VERSION_PROPERTY,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');
    const tcms = session.request;

    if (action === 'create') {
      return session.client.POST('/projects/{projectId}/plans', {
        params: { path: { projectId } },
        body: present({
          name: requireStr(input, 'name'),
          description: nullableStr(input, 'description'),
          milestoneName: nullableStr(input, 'milestoneName'),
          milestoneDate: nullableStr(input, 'milestoneDate'),
          suiteIds: await resolveMany(session, projectId, strings(input, 'suites'), 'SUITE'),
          caseIds: await resolveMany(session, projectId, strings(input, 'cases'), 'CASE'),
        }) as components['schemas']['TestPlanInput'],
        tcms: { ...tcms, ...createIntent({ key: session.callId }) },
      });
    }

    const planId = await resolveId({
      client: session.client, projectId, value: requireStr(input, 'plan'), entity: 'PLAN',
    });
    const path = { projectId, planId };

    if (action === 'status') {
      return session.client.PUT('/projects/{projectId}/plans/{planId}/status', {
        params: { path },
        body: { status: requireStr(input, 'status') as 'DRAFT' | 'IN_REVIEW' | 'APPROVED' },
        tcms,
      });
    }

    if (action === 'delete') {
      await session.client.DELETE('/projects/{projectId}/plans/{planId}', { params: { path }, tcms });
      return {
        deleted: planId,
        note: 'Soft-deleted. The suites and cases its scope named are untouched — a plan is a '
          + 'selection, not a container. A second delete answers 404 rather than a second 204.',
      };
    }

    if (action !== 'edit') throw new InputError(`Unknown action '${action}'.`);

    /*
      ~~`TestPlanUpdateInput` REQUIRES `name`, alone among the edit shapes on this surface~~ → it
      is not alone, and threading the name alone was not enough: every scalar
      `PlanEdit.validateUpdate` sees is written straight through, so an edit that named only the
      milestone silently emptied the plan's description, and `scopeText`/`outOfScopeText` — which
      no tool argument can reach — were emptied by *every* plan edit this server ever made.
      `core/rewrite.ts` holds the scalar/list split. Safe for the old note's reason, unchanged:
      the base and the version come from the same read.
    */
    const current = await session.client.GET('/projects/{projectId}/plans/{planId}', { params: { path }, tcms });
    const version = await versionFor(input, async () => current);

    const delta = present({
      name: str(input, 'name'),
      description: nullableStr(input, 'description'),
      milestoneName: nullableStr(input, 'milestoneName'),
      milestoneDate: nullableStr(input, 'milestoneDate'),
      suiteIds: await resolveMany(session, projectId, strings(input, 'suites'), 'SUITE'),
      caseIds: await resolveMany(session, projectId, strings(input, 'cases'), 'CASE'),
    });
    if (Object.keys(delta).length === 0) {
      throw new InputError('Nothing to change. Name at least one field.');
    }

    return session.client.PATCH('/projects/{projectId}/plans/{planId}', {
      params: { path },
      body: { ...planInputFrom(current), ...delta } as components['schemas']['TestPlanUpdateInput'],
      tcms: { ...tcms, ...editIntent(version, 'edit this plan') },
    });
  },
};

/* --------------------------------------------------------------------- runs ---- */

const writeRun: ToolDefinition = {
  name: 'tcms_write_run',
  title: 'Create a test run, clone one, or end it',
  description:
    'A run is a fixed selection of cases to execute. `tags` on a run point the opposite way from a '
    + 'case filter: every live case carrying ANY of them, union not intersection. Cloning takes '
    + "the original's selection under a new name, which is how a re-test of the same scope is "
    + 'made. `status` ends a run — COMPLETED or ABORTED — or reopens one with IN_PROGRESS; a '
    + 'closed run refuses further results with a 409 until it is reopened. A run STARTS by itself '
    + 'when the first result lands and never ends by itself, so finishing the last case is not '
    + 'finishing the run.',
  /*
    Two keys merged, and it is `V52` that makes it legal — §211 named this gap and called it "one
    `state` arm", which it is only because the tier moved. Before `V52` `run:close` was Lead-only
    while `run:create` is a contributor's *and a CI credential's*, so the merge would have hidden
    the create tool from everybody below a lead.

    The intersection to check is §2's, not the catalogue's: `Agent ∩ each of the five ASSIGNABLE
    roles`. `Automation Service` holds `run:create` without `run:close` and is the one role this
    merge would break — and it is not in that set, because `V41` made it non-assignable and an MCP
    token carries `Agent`. §134 is load-bearing here for the fourth separate time on this surface;
    collapse the two token roles and this merge takes `tcms_write_run` away from CI.
  */
  gate: { mode: 'all', permissions: ['run:create', 'run:close'] },
  arms: [
    { action: 'create', kind: 'create', permission: 'run:create' },
    { action: 'clone', kind: 'create', permission: 'run:create' },
    { action: 'status', kind: 'state', permission: 'run:close' },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['create', 'clone', 'status'], 'What to do.'),
      name: { type: 'string', description: 'The new run\'s name. Required for create and clone.' },
      status: {
        type: 'string',
        description:
          'For `status`: COMPLETED or ABORTED to end the run, IN_PROGRESS or NOT_STARTED to '
          + 'reopen it. The two reopening spellings mean the same thing — the server derives the '
          + 'live state from whether the run ever started — and setting the status a run already '
          + 'has is not an error.',
      },
      run: idProperty('run to clone or to set the status of', 'TR-1'),
      plan: idProperty('plan to run', 'TP-1'),
      suites: { type: 'array', items: { type: 'string' } },
      cases: { type: 'array', items: { type: 'string' } },
      tags: { type: 'array', items: { type: 'string' }, description: 'Cases carrying ANY of these.' },
      releaseVersion: { type: ['string', 'null'] },
      buildNumber: { type: ['string', 'null'] },
      // §177. Described rather than bare, unlike the two above, because the server refuses a value
      // that is not a git object name and an agent that learns the bound from a 400 has already
      // burned a turn. `clone` accepts NEITHER — a fresh clone has run nothing, so there is no
      // coordinate to carry — which is why the description says create.
      commitSha: {
        type: ['string', 'null'],
        description: 'On create: the commit under test, 7 to 40 hex characters.',
      },
      branch: {
        type: ['string', 'null'],
        description: 'On create: the branch under test.',
      },
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');
    const tcms = session.request;

    if (action === 'status') {
      const runId = await resolveId({
        client: session.client, projectId, value: requireStr(input, 'run'), entity: 'RUN',
      });
      return session.client.PUT('/projects/{projectId}/runs/{runId}/status', {
        params: { path: { projectId, runId } },
        // Neither header, for the reason the contract states on this endpoint and `tcms_write_suite`
        // states one noun over: the request names an absolute target state rather than a delta
        // against something the client read, so there is no version for an `If-Match` to assert
        // and nothing for an `Idempotency-Key` to deduplicate — setting the status a run already
        // has answers 200 and writes no second row.
        body: {
          status: requireStr(input, 'status') as components['schemas']['RunStatusInput']['status'],
        },
        tcms,
      });
    }

    if (action === 'clone') {
      const runId = await resolveId({
        client: session.client, projectId, value: requireStr(input, 'run'), entity: 'RUN',
      });
      return session.client.POST('/projects/{projectId}/runs/{runId}/clone', {
        params: { path: { projectId, runId } },
        body: present({
          name: requireStr(input, 'name'),
          releaseVersion: nullableStr(input, 'releaseVersion'),
          buildNumber: nullableStr(input, 'buildNumber'),
        }) as components['schemas']['TestRunCloneInput'],
        tcms: { ...tcms, ...createIntent({ key: session.callId }) },
      });
    }

    if (action !== 'create') throw new InputError(`Unknown action '${action}'.`);

    const plan = str(input, 'plan');
    return session.client.POST('/projects/{projectId}/runs', {
      params: { path: { projectId } },
      body: present({
        name: requireStr(input, 'name'),
        planId: plan === undefined
          ? undefined
          : await resolveId({ client: session.client, projectId, value: plan, entity: 'PLAN' }),
        suiteIds: await resolveMany(session, projectId, strings(input, 'suites'), 'SUITE'),
        caseIds: await resolveMany(session, projectId, strings(input, 'cases'), 'CASE'),
        tags: strings(input, 'tags'),
        releaseVersion: nullableStr(input, 'releaseVersion'),
        buildNumber: nullableStr(input, 'buildNumber'),
        commitSha: nullableStr(input, 'commitSha'),
        branch: nullableStr(input, 'branch'),
      }) as components['schemas']['TestRunInput'],
      tcms: { ...tcms, ...createIntent({ key: session.callId }) },
    });
  },
};

const recordResult: ToolDefinition = {
  name: 'tcms_record_result',
  title: 'Record a verdict against one case in a run',
  description:
    'Name the run and the case — an execution has no display key and never will. The status is a '
    + "per-project name from tcms_orient's executionStatuses, not a UUID. **Attaching evidence is "
    + 'not available here**: evidence is a file upload and there is no way to send bytes through '
    + 'a tool call. Recording over a result that is already there is an AMEND and costs '
    + 'result:amend, which this credential holds — the run must still be open either way.',
  // Two keys, not the three §3 lists — the `evidence:attach` arm cannot exist on this transport.
  // See the module header: declaring a permission the tool never exercises would hide it from a
  // caller who can record results and cannot attach evidence.
  //
  // **`result:amend` is deliberately not a third key here, and the arm still declares
  // `result:submit`.** The server decides which of the two this call costs by whether a result is
  // already recorded, and a `ToolArm` cannot say "one permission or the other depending on state".
  // Declaring the stricter one would hide the tool from a caller who can record and not amend —
  // the failure §2's rule exists to prevent — so the tool is listed on what a first record needs
  // and an amend refusal is left to the server. The cost is that such a refusal names
  // `result:submit`; the description above is what carries the true answer.
  gate: { mode: 'all', permissions: ['run:execute', 'result:submit'] },
  arms: [{ action: 'record', kind: 'edit', permission: 'result:submit' }],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['run', 'case', 'status'],
    properties: {
      run: idProperty('run', 'TR-1'),
      case: idProperty('case whose execution to record', 'TC-42'),
      status: { type: 'string', description: 'A status NAME from tcms_orient, e.g. "Passed".' },
      comment: { type: ['string', 'null'] },
      durationMs: {
        type: ['integer', 'null'],
        minimum: 0,
        description:
          'How long the test took, in MILLISECONDS. Optional. null CLEARS it, and so does '
          + 'omitting it — this body states the whole result rather than patching it, the same '
          + 'rule comment carries.',
      },
      lockVersion: LOCK_VERSION_PROPERTY,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const tcms = session.request;
    const runId = await resolveId({
      client: session.client, projectId, value: requireStr(input, 'run'), entity: 'RUN',
    });

    const caseKey = requireStr(input, 'case');
    const detail = await session.client.GET('/projects/{projectId}/runs/{runId}', {
      params: { path: { projectId, runId } }, tcms,
    });
    const execution = detail.executions.find(
      (row) => row.caseKey === caseKey || row.caseId === caseKey,
    );
    if (!execution) {
      throw new InputError(
        `${caseKey} is not in ${detail.key}. A run covers the cases it was created with; adding `
          + 'one is a change to the run, not a result.',
      );
    }

    // The status is named, not typed as a UUID: `case_status` is per-project vocabulary and the
    // ids are nobody's to remember. An unknown name is refused here rather than sent, because
    // the server's answer would be a 400 on a `statusId` the caller never chose.
    const wanted = requireStr(input, 'status');
    const statuses = await session.client.GET('/projects/{projectId}/statuses', {
      params: { path: { projectId } }, tcms,
    });
    const status = statuses.find(
      (row) => row.name.toLowerCase() === wanted.toLowerCase() || row.id === wanted,
    );
    if (!status) {
      throw new InputError(
        `No status '${wanted}' in this project. It offers: `
          + `${statuses.map((row) => row.name).join(', ')}.`,
      );
    }

    const path = { projectId, runId, executionId: execution.id };
    const version = await versionFor(input, () =>
      // The run detail's execution rows are summaries and carry no `lockVersion` — only the
      // execution's own detail does, and the version must come from the resource being written.
      session.client.GET('/projects/{projectId}/runs/{runId}/executions/{executionId}', {
        params: { path }, tcms,
      }));

    return session.client.PATCH('/projects/{projectId}/runs/{runId}/executions/{executionId}', {
      params: { path },
      body: present({
        statusId: status.id,
        comment: nullableStr(input, 'comment'),
        durationMs: nullableNum(input, 'durationMs'),
      }) as components['schemas']['ExecutionResultInput'],
      tcms: { ...tcms, ...editIntent(version, 'record this result') },
    });
  },
};

/* ------------------------------------------------------------------ reviews ---- */

/** Display-key entity to the wire's `ReviewSubjectType`, for the three that have reviews. */
const SUBJECTS = {
  'TC': { entity: 'CASE', wire: 'CASE', permission: 'case:review' },
  'TS': { entity: 'SUITE', wire: 'SUITE', permission: 'suite:review' },
  'TP': { entity: 'PLAN', wire: 'PLAN', permission: 'plan:review' },
} as const satisfies Record<string, {
  entity: 'CASE' | 'SUITE' | 'PLAN';
  wire: components['schemas']['ReviewSubjectType'];
  permission: string;
}>;

/**
 * Which review permission a display key costs — `TS-7` is `suite:review`, not `case:review`.
 *
 * Declared apart from {@link openReview} because the refusal needs the answer *without* a round
 * trip: the dispatcher composes "ask an administrator for X" in a catch block, and the subject's
 * kind is legible from two characters. See {@link ToolArm.permissionFor}.
 */
function reviewPermissionFor(input: Record<string, unknown>): string | undefined {
  const key = input['subject'];
  if (typeof key !== 'string') return undefined;
  return SUBJECTS[key.slice(0, 2).toUpperCase() as keyof typeof SUBJECTS]?.permission;
}

/**
 * The open review of a subject named by its display key.
 *
 * Shared by both review tools, and the "open" is load-bearing: a review is **history** once it
 * closes — the endpoint answers 409 on a closed one, and re-submitting opens a NEW record rather
 * than reopening the old — so "the latest row" and "the one that can still be written to" are
 * different questions, and picking the wrong one is a refusal the caller cannot act on.
 */
async function openReview(session: Session, key: string): Promise<{ id: string }> {
  const projectId = session.projectId();
  const prefix = key.slice(0, 2).toUpperCase() as keyof typeof SUBJECTS;
  const subject = SUBJECTS[prefix];
  if (!subject) {
    throw new InputError(
      'Reviews are kept for cases, suites and plans, and the display key is what says which. '
        + 'Name one — TC-42, TS-7, TP-1. A bare UUID cannot say what it is.',
    );
  }

  const subjectId = await resolveId({ client: session.client, projectId, value: key, entity: subject.entity });
  const reviews = await session.client.GET('/projects/{projectId}/reviews', {
    params: { path: { projectId }, query: { subjectType: subject.wire, subjectId } },
    tcms: session.request,
  });

  const open = reviews.find((review) => review.state === 'IN_REVIEW');
  if (!open) {
    throw new InputError(
      reviews.length === 0
        ? `${key} has never been submitted for review, so there is no thread to join.`
        : `${key}'s review has ended (${String(reviews[0]?.state)}). Submitting it again opens a `
          + 'new conversation; the closed record stays as it is.',
    );
  }

  return { id: open.id };
}

const reviewComment: ToolDefinition = {
  name: 'tcms_review_comment',
  title: 'Comment on the open review of a case, suite or plan',
  description:
    'Writes a remark on the review thread of whatever the display key names. One permission '
    + 'covers all three subjects. **Resolving a thread is not commenting** — that is a statement '
    + "about the review's progress and costs the subject's review permission, so it lives in "
    + 'tcms_review_verdict.',
  gate: { mode: 'all', permissions: ['case:comment'] },
  arms: [{ action: 'comment', kind: 'state', permission: 'case:comment' }],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['subject', 'body'],
    properties: {
      subject: idProperty('case, suite or plan under review — by display key', 'TC-42'),
      body: { type: 'string' },
      replyTo: { type: 'string', description: 'A comment id, to reply in-thread.' },
    },
  },
  run: async (session, input) => {
    const review = await openReview(session, requireStr(input, 'subject'));
    return session.client.POST('/projects/{projectId}/reviews/{reviewId}/comments', {
      params: { path: { projectId: session.projectId(), reviewId: review.id } },
      body: present({
        body: requireStr(input, 'body'),
        parentId: str(input, 'replyTo'),
      }) as components['schemas']['ReviewCommentInput'],
      tcms: session.request,
    });
  },
};

const reviewVerdict: ToolDefinition = {
  name: 'tcms_review_verdict',
  title: 'Record a peer verdict, or resolve a thread',
  description:
    'A verdict is LGTM or changes-requested on an open review; resolving marks a comment thread '
    + 'dealt with. Both cost the review permission for the subject, which is why they are here '
    + 'and commenting is not. This does NOT approve anything — approval is a separate permission '
    + 'this credential does not hold, and author-cannot-approve-own-work is on by default.',
  /*
    The one tool listed on an `any` gate, and §2 explains why it is not merged with commenting:
    the effective set is an intersection, so a **Viewer**-owned agent token comes out holding
    `case:comment` and no `*:review` at all. A merged tool would be hidden from a caller who can
    legitimately comment.
  */
  gate: { mode: 'any', permissions: ['case:review', 'suite:review', 'plan:review'] },
  arms: [
    { action: 'verdict', kind: 'state', permission: 'case:review', permissionFor: reviewPermissionFor },
    { action: 'resolve', kind: 'state', permission: 'case:review', permissionFor: reviewPermissionFor },
  ],
  narrow: (held) => {
    const subjects = Object.values(SUBJECTS)
      .filter((subject) => held.has(subject.permission))
      .map((subject) => subject.entity.toLowerCase());
    return `This credential may do this for: ${subjects.join(', ')}. Other subjects will be refused.`;
  },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action', 'subject'],
    properties: {
      action: ACTION(['verdict', 'resolve'], 'What to do.'),
      subject: idProperty('case, suite or plan under review — by display key', 'TC-42'),
      /*
        ~~`APPROVED`~~ → **`LGTM`**, §203. `ReviewParticipationInput.verdict` has been
        `LGTM | CHANGES_REQUESTED` since the schema was written and this property told every caller
        to send a member that does not exist — so the one word a model would most likely pick for
        "I approve" earned a 400 from the server, on a tool whose own description said *"a verdict is
        LGTM or changes-requested"* two lines up. The value is forwarded verbatim by `requireStr`,
        so the description was the whole of the contract the caller had.

        Declared as an `enum` rather than corrected in prose: an MCP host validates the input schema
        before the call, so a wrong member is now refused here rather than at the server, and the
        next word that drifts is a compile-adjacent failure instead of a sentence nobody re-reads.
      */
      verdict: {
        type: 'string',
        enum: ['LGTM', 'CHANGES_REQUESTED'],
        description: 'For `verdict`: LGTM or CHANGES_REQUESTED.',
      },
      comment: { type: 'string' },
      commentId: { type: 'string', description: 'For `resolve`: the thread to mark.' },
      resolved: { type: 'boolean', description: 'For `resolve`: false re-opens it. Default true.' },
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');
    const review = await openReview(session, requireStr(input, 'subject'));

    if (action === 'verdict') {
      return session.client.POST('/projects/{projectId}/reviews/{reviewId}/participations', {
        params: { path: { projectId, reviewId: review.id } },
        body: present({
          verdict: requireStr(input, 'verdict'),
          comment: str(input, 'comment'),
        }) as components['schemas']['ReviewParticipationInput'],
        tcms: session.request,
      });
    }

    if (action !== 'resolve') throw new InputError(`Unknown action '${action}'.`);

    return session.client.PUT('/projects/{projectId}/reviews/{reviewId}/comments/{commentId}/resolution', {
      params: {
        path: { projectId, reviewId: review.id, commentId: requireStr(input, 'commentId') },
      },
      body: { resolved: bool(input, 'resolved') ?? true },
      tcms: session.request,
    });
  },
};

/* ---------------------------------------------------------------- work items ---- */

const writeWorkItem: ToolDefinition = {
  name: 'tcms_write_work_item',
  title: 'Put a task on the board, or move a card',
  description:
    'Real tasks only. The board also shows DERIVED cards — outstanding executions, review '
    + 'requests, case-health repairs, quarantined cases — which are computed on every read from '
    + 'their source and cannot be created or moved here; fix the source instead. Use this to '
    + 'record follow-up work a person should pick up.',
  gate: { mode: 'all', permissions: ['workitem:manage', 'defect:create'] },
  arms: [
    { action: 'create', kind: 'create', permission: 'workitem:manage' },
    { action: 'move', kind: 'state', permission: 'workitem:manage' },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['create', 'move'], 'What to do.'),
      title: { type: 'string' },
      description: { type: 'string' },
      priority: { type: 'string' },
      dueDate: { type: 'string', description: 'YYYY-MM-DD.' },
      workItemId: { type: 'string', description: 'For `move`: the card id.' },
      status: { type: 'string', description: 'For `move`: the column to move it to.' },
    },
  },
  run: async (session, input) => {
    const action = requireStr(input, 'action');

    if (action === 'move') {
      return session.client.PATCH('/work-items/{workItemId}', {
        params: { path: { workItemId: requireStr(input, 'workItemId') } },
        body: { status: requireStr(input, 'status') as components['schemas']['WorkItemStatus'] },
        tcms: session.request,
      });
    }

    if (action !== 'create') throw new InputError(`Unknown action '${action}'.`);

    return session.client.POST('/work-items', {
      body: present({
        // The one body on this surface that carries a `projectId`, and §4.1 is untouched by it:
        // the rule is that the parameter does not exist in any *tool schema*. It comes from the
        // session here exactly as it does for every path parameter.
        projectId: session.projectId(),
        title: requireStr(input, 'title'),
        description: str(input, 'description'),
        priority: str(input, 'priority'),
        dueDate: str(input, 'dueDate'),
      }) as components['schemas']['WorkItemInput'],
      tcms: { ...session.request, ...createIntent({ key: session.callId }) },
    });
  },
};

/* ------------------------------------------------------------------ reports ---- */

/* ----------------------------------------------------------------- artifacts ---- */

const writeArtifact: ToolDefinition = {
  name: 'tcms_write_artifact',
  title: 'Register a document in the project register, correct one, or retire one',
  description:
    'The register records the documents a project stands behind. `register` adds a reference: a '
    + 'documentation page (`docPage`), an external link (`url`), or a report you generated '
    + '(`reportJob` — poll it to COMPLETED first, and what is kept is the rendered output, frozen). '
    + 'Uploading a FILE is not possible here; a tool call carries no bytes. `edit` corrects the '
    + 'title, category and description and NOTHING else — a row that points at the wrong thing is '
    + 'replaced, not repointed, by registering a new one with `supersedes`. `status` archives or '
    + 'restores. `delete` removes the row and its file; a person is asked first. A row with '
    + '`writeOnce` refuses edit and delete: correct it by superseding it.',
  gate: { mode: 'all', permissions: ['artifact:edit', 'artifact:delete'] },
  arms: [
    { action: 'register', kind: 'create', permission: 'artifact:edit' },
    { action: 'edit', kind: 'edit', permission: 'artifact:edit' },
    { action: 'status', kind: 'state', permission: 'artifact:edit' },
    { action: 'delete', kind: 'delete', permission: 'artifact:delete', targets: ['artifact'] },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['register', 'edit', 'status', 'delete'], 'What to do.'),
      artifact: {
        type: 'string',
        description:
          'The artifact id, for edit, status and delete. Artifacts carry no display key — take '
          + 'the id from tcms_list_artifacts.',
      },
      title: { type: 'string' },
      category: {
        type: 'string',
        description:
          'Strategy · TestData · Environment · SignOff · Compliance · Tooling · Template · Other. '
          + 'Free text — the register groups by it, so match what the project already uses.',
      },
      description: { type: ['string', 'null'] },
      scope: { type: 'string', description: 'For `register`: PROJECT (default) or PLAN.' },
      plan: idProperty('plan — required when scope is PLAN', 'OLB-P1'),
      docPage: idProperty('documentation page to point at', 'DOC-4'),
      url: { type: 'string', description: 'For `register`: an http(s) link to point at.' },
      reportJob: {
        type: 'string',
        description:
          'For `register`: the id of a COMPLETED report job (tcms_report). What is registered is '
          + 'the rendered output as it stands; regenerating later makes a new report and leaves '
          + 'this entry alone.',
      },
      supersedes: {
        type: 'string',
        description:
          'The artifact this one replaces. It is kept and marked SUPERSEDED — this is how a '
          + 'sign-off record or a wrongly-pointed row is corrected. Must sit at the same scope, '
          + 'and in the same plan, as the row it replaces.',
      },
      writeOnce: {
        type: 'boolean',
        description:
          'For `register`: a sign-off record. It can never be edited or removed afterwards, by '
          + 'anyone, and this cannot be undone. Do not set it unless the caller asked for a '
          + 'sign-off record.',
      },
      status: { type: 'string', description: 'For `status`: ACTIVE or ARCHIVED.' },
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');

    if (action === 'register') {
      const scope = str(input, 'scope') ?? 'PROJECT';
      const docPage = str(input, 'docPage');
      const reportJob = str(input, 'reportJob');
      const url = str(input, 'url');

      /*
        The kind is DERIVED from which payload was named rather than taken as a field.

        The server refuses a payload belonging to another kind rather than ignoring it, so a
        `kind` argument would be a second statement of something the arguments already say — and
        the disagreement between the two is a 400 about a field the model did set correctly. One
        payload, one kind; naming none, or more than one, is refused here with a sentence rather
        than by the server with a field error about `kind`.
      */
      const named = [docPage, url, reportJob].filter(Boolean);
      if (named.length !== 1) {
        throw new InputError(
          'Name exactly one of `docPage`, `url` or `reportJob` — that is what decides the kind. '
          + 'Uploading a file is not possible on this surface.',
        );
      }

      return session.client.POST('/projects/{projectId}/artifacts', {
        params: { path: { projectId } },
        body: present({
          scope,
          planId: scope === 'PLAN'
            ? await resolveId({
              client: session.client, projectId, value: requireStr(input, 'plan'), entity: 'PLAN',
            })
            : undefined,
          category: requireStr(input, 'category'),
          kind: docPage ? 'DOC_PAGE_REF' : reportJob ? 'GENERATED_REPORT' : 'EXTERNAL_URL',
          title: requireStr(input, 'title'),
          description: nullableStr(input, 'description'),
          docPageId: docPage
            ? await resolveId({
              client: session.client, projectId, value: docPage, entity: 'DOC_PAGE',
            })
            : undefined,
          url,
          reportJobId: reportJob,
          // Absent is false server-side, and it is sent explicitly so the request states the
          // choice: this flag cannot be added later or taken back, so a caller reading the
          // payload back should see that it was decided.
          writeOnce: bool(input, 'writeOnce') ?? false,
          supersedes: str(input, 'supersedes'),
        }) as components['schemas']['ArtifactInput'],
        tcms: { ...session.request, ...createIntent({ key: session.callId }) },
      });
    }

    const artifactId = requireStr(input, 'artifact');
    const path = { projectId, artifactId };

    if (action === 'status') {
      return session.client.PUT('/projects/{projectId}/artifacts/{artifactId}/status', {
        params: { path },
        body: { status: requireStr(input, 'status') as 'ACTIVE' | 'ARCHIVED' },
        // The suite's rule: an absolute target state, not a delta against something read.
        tcms: session.request,
      });
    }

    if (action === 'delete') {
      await session.client.DELETE('/projects/{projectId}/artifacts/{artifactId}', {
        params: { path }, tcms: session.request,
      });
      /*
        Both 409s are left to `result.ts` for `writeSuite`'s reason — the server's `detail` names
        which of the two applies (a sign-off record, or a row something else supersedes) and what
        to do instead, which is more than a sentence composed here could say.
      */
      return {
        deleted: artifactId,
        note: 'Removed from the register. A FILE artifact\'s bytes go with it — this is the one '
          + 'delete on this surface that is not soft. Archiving instead keeps the row and takes '
          + 'it out of circulation.',
      };
    }

    if (action !== 'edit') throw new InputError(`Unknown action '${action}'.`);

    /*
      `ArtifactEditInput` requires `title` and `category`, so this is the whole-set shape the
      module header describes and not a delta — the same reason the suite and plan arms read
      first. `core/rewrite.ts` is not used because the register has no `*InputFrom` projection:
      three fields, read from the row the register serves.

      No `If-Match`: the endpoint declares none. The row's own `lockVersion` is on the wire and
      the server does not read it here, so sending one would be a header nothing checks —
      `writeConformance.test.ts` asserts exactly that agreement.
    */
    const current = await session.client.GET('/projects/{projectId}/artifacts', {
      params: { path: { projectId } }, tcms: session.request,
    }) as components['schemas']['Artifact'][];
    const row = current.find((artifact) => artifact.id === artifactId);
    if (!row) {
      throw new InputError(
        `No artifact ${artifactId} in this project's register. Ids come from tcms_list_artifacts, `
        + 'and a plan-scope row is only in that plan\'s register.',
      );
    }

    return session.client.PATCH('/projects/{projectId}/artifacts/{artifactId}', {
      params: { path },
      body: present({
        title: str(input, 'title') ?? row.title,
        category: str(input, 'category') ?? row.category,
        description: 'description' in input ? nullableStr(input, 'description') : row.description,
      }) as components['schemas']['ArtifactEditInput'],
      tcms: session.request,
    });
  },
};

const report: ToolDefinition = {
  name: 'tcms_report',
  title: 'Generate a report, and poll it',
  description:
    'Generation is asynchronous by contract: `generate` returns a job, `poll` says where it got '
    + "to. **Call `capabilities` first** — it names the report types and output formats THIS "
    + 'deployment can produce, and both are per-install: a type it cannot compute is a 400 and a '
    + "format it cannot render is another. `definitions` lists your own saved reports, which is "
    + 'where a `definitionId` comes from. A report\'s own parameters are validated inside the '
    + 'worker, so a bad one surfaces as a FAILED job carrying the sentence rather than as an error '
    + 'on the request. The finished artifact is a file with a download URL — this returns the URL; '
    + 'fetching it needs the same credential and is not something a tool call can hand you.',
  gate: { mode: 'all', permissions: ['report:view', 'report:export'] },
  arms: [
    /*
      A generation allocates a job row, so it is a create and carries a key: an agent that retries
      a lost 202 must not queue the work twice.

      ~~It carried one and the endpoint never read it (§205)~~ — for two slices this arm sent
      nothing, because a header the server ignores is the mistake the parameter's own description
      names, and sending it made the shortfall look closed to anybody reading this file. → **The
      server reads it as of `V66` (audit E2)**: a replayed key answers 200 with the job it already
      queued. The key goes back on, and the entry `writeConformance.test.ts` kept for this arm is
      gone with it.

      What the gap actually cost is worth keeping: THIS transport is the one that pays for a
      duplicate report and cannot see it. A retry here refreshed `execution_fact` for the whole
      company a second time, computed, rendered and wrote two more artifacts, with no job list on
      screen for anybody to notice.
    */
    { action: 'generate', kind: 'create', permission: 'report:export' },
  ],
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['action'],
    properties: {
      action: ACTION(['generate', 'poll', 'capabilities', 'definitions'], 'What to do.'),
      /*
        Open by contract, and the catalogue is now ANSWERABLE rather than only discoverable by
        refusal. It stays an open string here because a deployment's report set is whichever
        computations it registers — enumerating three type names in this schema would be a copy of
        a server-side registry in the one file that never talks to the server it is pointed at,
        which is `describeRefusal`'s recorded argument for not baking the role catalogue.
      */
      type: {
        type: 'string',
        description: 'The report type — one of the `types` from `capabilities`. Omit when naming '
          + 'a saved definition.',
      },
      /*
        The enum is stated rather than left open, because a format this deployment cannot render
        is a 400 the model would have to discover by being refused — and the enum is the set that
        may be ASKED for, which is not the set installed here. `MD` and `HTML` are in every image;
        `PDF` needs a renderer most deployments do not have.

        ~~*"the SPA asks `GET …/reports/capabilities` instead; this surface has no read tool to
        hang that on, and adding one to answer a two-element list is a tool for a sentence."*~~ →
        **the answer moved onto this tool as the `capabilities` action**, and it is no longer a
        two-element list: the endpoint now carries the report TYPE catalogue as well, which was
        the half no caller could obtain at all.
      */
      format: {
        type: 'string',
        enum: ['PDF', 'MD', 'HTML'],
        description:
          'For `generate`: the output format. `MD` and `HTML` render everywhere; `PDF` is refused '
          + 'unless this deployment has installed a renderer, and the refusal names what it can '
          + 'produce.',
      },
      definitionId: {
        type: 'string',
        description: 'Regenerate a saved report, by an id from `definitions`.',
      },
      parameters: { type: 'object', description: 'The report\'s own parameters.' },
      jobId: { type: 'string', description: 'For `poll`.' },
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const action = requireStr(input, 'action');

    if (action === 'poll') {
      return session.client.GET('/projects/{projectId}/reports/jobs/{jobId}', {
        params: { path: { projectId, jobId: requireStr(input, 'jobId') } },
        tcms: session.request,
      });
    }

    /*
      Two reads on a write tool, and they are here rather than in a read tool of their own for the
      reason the old comment gave about the formats half — *"adding one to answer a two-element
      list is a tool for a sentence"*. What changed is that the sentence was not answerable at all:
      `type` is an open string whose catalogue lived only inside a refusal, and `definitionId` took
      an id nothing on this surface could produce. A `report:export`-gated capability an agent can
      neither address correctly nor read the result of is a dead end, however honestly described.
    */
    if (action === 'capabilities') {
      return session.client.GET('/projects/{projectId}/reports/capabilities', {
        params: { path: { projectId } }, tcms: session.request,
      });
    }

    if (action === 'definitions') {
      return session.client.GET('/projects/{projectId}/reports', {
        params: { path: { projectId } }, tcms: session.request,
      });
    }

    if (action !== 'generate') throw new InputError(`Unknown action '${action}'.`);

    return session.client.POST('/projects/{projectId}/reports/generate', {
      params: { path: { projectId } },
      body: present({
        format: requireStr(input, 'format'),
        type: str(input, 'type'),
        definitionId: str(input, 'definitionId'),
        parameters: input['parameters'],
      }) as { format: 'PDF' | 'MD' | 'HTML' },
      // One key per CALL, like every other create arm: `session.callId` is the intent, so a
      // transport-level retry of one tool call replays rather than queues a second job (V66).
      tcms: { ...session.request, ...createIntent({ key: session.callId }) },
    });
  },
};

export const WRITE_TOOLS: readonly ToolDefinition[] = [
  writeCase,
  writeSuite,
  writeDocPage,
  writePlan,
  writeRun,
  recordResult,
  reviewComment,
  reviewVerdict,
  writeWorkItem,
  writeArtifact,
  report,
];
