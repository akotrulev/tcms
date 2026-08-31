/**
 * The read commands — plan phase 5, `tcms <noun> <verb>`.
 *
 * Nouns match the MCP tool families exactly (surface spec §6), so a person who has read one
 * surface can predict the other even though the two are deliberately different granularities:
 * one command per operation here, ~21 workflow-shaped tools there.
 *
 * Every command is a plain function of (client, context) → void, with output written through the
 * injected sink. Nothing here builds a URL, generates a key, or decides what a refusal may say —
 * those are `@tcms/client`'s, once, which is the entire reason that package exists.
 */
import {
  SHAPES,
  clampPageSize,
  guideFor,
  resolveId,
  resolveProject,
  shapeOf,
  templateFor,
  usageFor,
  type components,
} from '@tcms/client';

import {
  UsageError,
  all,
  first,
  flag,
  number,
  projectOf,
  requireArg,
  type Command,
  type CommandContext,
} from './context.js';
import { fields, show, showDate, table } from './output.js';

const whoami: Command = {
  name: 'whoami',
  summary: 'Who this token acts as, and what it can reach',
  usage: 'tcms whoami [--project KEY]',
  run: async ({ client, out, json, project, request }) => {
    const me = await client.GET('/me', { tcms: request });
    const projects = await client.GET('/projects', { tcms: request });

    // The project term is only asked for when one is in scope. `GET /me/permissions` answers
    // SYSTEM+COMPANY without a project and SYSTEM+COMPANY+PROJECT with one — deliberately not
    // the same set, so passing a project the caller did not name would report a wider reach
    // than they have anywhere in particular.
    const scoped = project ? await resolveProject({ client, requested: project }) : undefined;
    const permissions = await client.GET('/me/permissions', {
      params: { query: scoped ? { projectId: scoped.id } : {} },
      tcms: request,
    });

    if (json) {
      out.json({ me, projects, permissions });
      return;
    }

    fields(out, [
      ['User', `${show(me.displayName)} <${show(me.email)}>`],
      ['Instance admin', me.isSystemAdmin ? 'yes' : 'no'],
      ['Projects', projects.map((item) => item.key).join(', ') || '(none)'],
      ['Scope', scoped ? `${scoped.key} — ${scoped.name}` : 'no project (company-wide terms only)'],
      ['Permissions', String(permissions.permissions.length)],
    ]);

    if (permissions.permissions.length > 0) {
      out.line();
      /*
        Sorted, and printed as keys rather than grouped by role — this client cannot name a
        role, and `core/refusal.ts` records why: the role→permission catalogue is behind
        `company:read`, which an agent token deliberately does not hold.

        **`.sort().join()` on the array itself printed `[object Object]` for every key**, which
        is what §137 shipped and §140 found while building the MCP server's capability summary
        against the same endpoint. `permissions` has carried `{key, qualifier}` pairs since §65,
        not bare keys, and the default `sort` compares their string forms — so the output was
        the same word repeated once per permission and the command still exited 0. The qualifier
        is printed too, and that is the point rather than a repair: `case:edit` and
        `case:edit (OWN)` are different answers to "may I change this", and this command exists
        to answer exactly that.
      */
      out.line(
        [...permissions.permissions]
          .sort((left, right) => left.key.localeCompare(right.key))
          .map((held) => (held.qualifier === 'ALL' ? held.key : `${held.key} (${held.qualifier})`))
          .join('  '),
      );
    }
  },
};

const projectList: Command = {
  name: 'project list',
  summary: 'Projects this token can see',
  usage: 'tcms project list',
  run: async ({ client, out, json, request }) => {
    const projects = await client.GET('/projects', { tcms: request });
    if (json) return out.json(projects);

    table(out, projects, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'NAME', value: (row) => show(row.name) },
      { header: 'CASES', value: (row) => show(row.caseCount) },
      { header: 'MY ROLE', value: (row) => show(row.myRole) },
      { header: 'ID', value: (row) => show(row.id) },
    ]);
  },
};

const caseList: Command = {
  name: 'case list',
  summary: 'Search and filter test cases',
  usage: 'tcms case list [--q TEXT] [--suite TS-n] [--tag T]... [--page N] [--size N]',
  // Every one of these was undeclared until §139, which meant `--q` and `--suite` parsed as
  // booleans and this command answered the unfiltered list. See `OptionSpec`.
  options: {
    q: { type: 'string' },
    suite: { type: 'string' },
    tag: { type: 'string', multiple: true },
    page: { type: 'string' },
    size: { type: 'string' },
  },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const suite = first(options['suite']);
    const q = first(options['q']);

    const result = await client.GET('/projects/{projectId}/cases', {
      params: {
        path: { projectId },
        query: {
          ...(q === undefined ? {} : { q }),
          ...(suite
            ? { suiteId: await resolveId({ client, projectId, value: suite, entity: 'SUITE' }) }
            : {}),
          ...(all(options['tag']).length > 0 ? { tag: all(options['tag']) } : {}),
          page: number(first(options['page'])) ?? 0,
          size: clampPageSize(number(first(options['size']))),
        },
      },
      tcms: request,
    });

    if (json) return out.json(result);

    table(out, result.items, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'TITLE', value: (row) => show(row.title) },
      { header: 'STATUS', value: (row) => show(row.lifecycleStatus) },
      { header: 'PRIORITY', value: (row) => show(row.priority) },
      { header: 'AUTOMATION', value: (row) => show(row.automationStatus) },
      { header: 'SUITE', value: (row) => show(row.suiteName) },
      { header: 'UPDATED', value: (row) => showDate(row.updatedAt) },
    ]);

    // The count is printed even when a page is full, because "25 of 25" and "25 of 812" look
    // identical on screen and mean very different things about whether the filter worked.
    out.line();
    out.line(
      `${result.items.length} of ${result.total} — page ${result.page + 1}`
        + `${result.size ? ` of ${Math.max(1, Math.ceil(result.total / result.size))}` : ''}`,
    );
  },
};

const caseShow: Command = {
  name: 'case show',
  summary: 'One test case in full',
  usage: 'tcms case show TC-42',
  run: async (context) => {
    const { client, out, json, request } = context;
    const projectId = await projectOf(context);
    const caseId = await resolveId({
      client, projectId, value: requireArg(context, 'case'), entity: 'CASE',
    });

    const found = await client.GET('/projects/{projectId}/cases/{caseId}', {
      params: { path: { projectId, caseId } },
      tcms: request,
    });

    if (json) return out.json(found);

    fields(out, [
      ['Key', show(found.key)],
      ['Title', show(found.title)],
      ['Status', show(found.lifecycleStatus)],
      ['Priority', show(found.priority)],
      ['Automation', show(found.automationStatus)],
      ['Owner', show(found.owner?.displayName)],
      ['Tags', found.tags.join(', ') || '—'],
      ['Updated', showDate(found.updatedAt)],
      // Printed because every edit needs it, and `If-Match` is what stops this surface
      // overwriting a colleague's concurrent change with no error.
      ['Lock version', show(found.lockVersion)],
    ]);

    /*
      The conditions, with their **attachment** ids — the half of this command §189 added, and
      the ids are the point rather than the titles.

      `case edit --decline` takes `suite_condition` row ids, which are exactly what `id` is on
      these four arrays and are **not** library `condition` ids: "Log in" attached at *Billing*
      and again at *Billing › Refunds* is two attachments, and declining one must leave the other
      in force. Before this, a person at a terminal could reach those ids only through
      `--json`, so the flag the same slice built would have had no way to learn its argument.

      Declined entries are listed **beside** the live ones rather than merged into them: the
      server serves them in separate arrays precisely because a declined condition is not part of
      what this case does, and a single list with a marker column would be one careless `grep`
      away from reading as though it were.
    */
    const conditions = [
      ...found.preconditions.map((row) => ({ row, kind: 'PRE', declined: false })),
      ...found.postconditions.map((row) => ({ row, kind: 'POST', declined: false })),
      ...found.declinedPreconditions.map((row) => ({ row, kind: 'PRE', declined: true })),
      ...found.declinedPostconditions.map((row) => ({ row, kind: 'POST', declined: true })),
    ];

    if (conditions.length > 0) {
      out.line();
      table(out, conditions, [
        { header: 'CONDITION', value: (entry) => show(entry.row.id) },
        { header: 'KIND', value: (entry) => entry.kind },
        { header: 'STATE', value: (entry) => (entry.declined ? 'declined' : 'in force') },
        // Where an inherited entry came from, which is what makes a decline a considered act:
        // declining is refusing a rule a suite imposed, and the suite's name is the whole
        // context for that decision.
        { header: 'FROM', value: (entry) => (entry.row.inherited ? show(entry.row.inheritedFrom) : 'this case') },
        { header: 'TITLE', value: (entry) => show(entry.row.title).slice(0, 40) },
      ]);
    }

    if (found.description) {
      out.line();
      out.line(found.description);
    }
  },
};

const libraryList: Command = {
  name: 'library list',
  summary: 'The reusable condition library',
  usage: 'tcms library list [--q TEXT] [--type PRE|POST|BOTH]',
  options: {
    q: { type: 'string' },
    type: { type: 'string' },
  },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const projectId = await projectOf(context);
    const path = { projectId };
    const q = first(options['q']);
    const type = first(options['type']);

    /*
      Read-only: writing costs `library:manage`, which this surface deliberately does not carry
      (§A3 gates it by an impact modal an API caller never sees), so `tcms library create` is a
      command that could only ever 403. The same shape and the same name as `tcms_list_library`,
      which is the rule the two surfaces share — one command per operation here, one
      workflow-shaped tool there, and the same word for the same thing.

      It exists because `suite edit --precondition` takes a **library** id and, until this
      command, no read on this surface served one. A write whose argument cannot be learned from
      the surface that offers it is a write nobody can make.

      The noun stayed `library` rather than narrowing to `condition` when §239 retired the
      shared-step half: conditions are the only library left, but the command name is a locator
      for anything scripted against it, and renaming it to say so would break every one of those
      to describe an absence.
    */
    const conditions = await client.GET('/projects/{projectId}/conditions', {
      params: {
        path,
        query: {
          ...(q === undefined ? {} : { q }),
          ...(type === undefined ? {} : { type: type as 'PRE' | 'POST' | 'BOTH' }),
        },
      },
      tcms: request,
    });

    if (json) return out.json({ conditions });

    out.line('Conditions');
    table(out, conditions, [
      // First column, because it is what a write takes. Titles are how a person finds the row;
      // the id is what they then have to type.
      { header: 'ID', value: (row) => show(row.id) },
      { header: 'TYPE', value: (row) => show(row.type) },
      { header: 'TITLE', value: (row) => show(row.title) },
      // Two numbers rather than one: a condition's usage spans `case_condition` and
      // `suite_condition`, and blending them would be a guess (the contract says so).
      { header: 'CASES', value: (row) => show(row.usageCount) },
      { header: 'SUITES', value: (row) => show(row.suiteUsageCount) },
    ]);
  },
};

const suiteList: Command = {
  name: 'suite list',
  summary: 'Suites in the project',
  usage: 'tcms suite list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const suites = await client.GET('/projects/{projectId}/suites', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(suites);

    // Flat, not a tree: a suite has been a named collection rather than a folder since §98.
    table(out, suites, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'NAME', value: (row) => show(row.name) },
      { header: 'CASES', value: (row) => show(row.caseCount) },
      { header: 'STATUS', value: (row) => show(row.lifecycleStatus) },
    ]);
  },
};

const suiteShow: Command = {
  name: 'suite show',
  summary: 'One suite, with the conditions it imposes',
  usage: 'tcms suite show TS-7',
  run: async (context) => {
    const { client, out, json, request } = context;
    const projectId = await projectOf(context);
    const suiteId = await resolveId({
      client, projectId, value: requireArg(context, 'suite'), entity: 'SUITE',
    });

    const suite = await client.GET('/projects/{projectId}/suites/{suiteId}', {
      params: { path: { projectId, suiteId } },
      tcms: request,
    });

    if (json) return out.json(suite);

    fields(out, [
      ['Key', show(suite.key)],
      ['Name', show(suite.name)],
      ['Status', show(suite.lifecycleStatus)],
      ['Cases', show(suite.caseCount)],
      ['Tag rules', suite.tagRules.join(', ') || '—'],
      ['Lock version', show(suite.lockVersion)],
    ]);

    /*
      The suite's own conditions, printed with the id `suite edit --precondition` takes — which is
      the **library** `conditionId`, not the attachment `id` beside it. The two are one column
      apart here and they are the two ids this slice's flags take, in opposite directions: a suite
      attaches a library entry (`conditionId`), and a case declines an attachment (`id`). Printing
      only one of them would make one of the two flags unusable, and printing them unlabelled
      would make it worse — a wrong id here is a field error, and a wrong id that happens to name
      a real row is a save nobody notices.

      `conditionId` is nullable on the wire for the inline form, which §95 ended and no writer can
      produce; it prints as an em-dash if one is ever found rather than as the word "null".
    */
    const conditions = [
      ...suite.preconditions.map((row) => ({ row, kind: 'PRE' })),
      ...suite.postconditions.map((row) => ({ row, kind: 'POST' })),
    ];

    if (conditions.length > 0) {
      out.line();
      table(out, conditions, [
        { header: 'CONDITION', value: (entry) => show(entry.row.conditionId) },
        { header: 'KIND', value: (entry) => entry.kind },
        { header: 'ATTACHMENT', value: (entry) => show(entry.row.id) },
        { header: 'TITLE', value: (entry) => show(entry.row.title).slice(0, 40) },
      ]);
    }

    if (suite.description) {
      out.line();
      out.line(suite.description);
    }
  },
};

const planList: Command = {
  name: 'plan list',
  summary: 'Test plans in the project',
  usage: 'tcms plan list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const plans = await client.GET('/projects/{projectId}/plans', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(plans);

    table(out, plans, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'NAME', value: (row) => show(row.name) },
      { header: 'STATUS', value: (row) => show(row.status) },
      { header: 'MILESTONE', value: (row) => show(row.milestoneName) },
      { header: 'CASES', value: (row) => show(row.caseCount) },
      { header: 'PASS RATE', value: (row) => show(row.passRate) },
    ]);
  },
};

const runList: Command = {
  name: 'run list',
  summary: 'Test runs in the project',
  usage: 'tcms run list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const runs = await client.GET('/projects/{projectId}/runs', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(runs);

    table(out, runs, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'NAME', value: (row) => show(row.name) },
      { header: 'STATUS', value: (row) => show(row.status) },
      { header: 'PLAN', value: (row) => show(row.planKey) },
      { header: 'SOURCE', value: (row) => show(row.source) },
      { header: 'STARTED', value: (row) => showDate(row.startedAt) },
    ]);
  },
};

const docList: Command = {
  name: 'doc list',
  summary: 'Documentation pages in the project',
  usage: 'tcms doc list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const pages = await client.GET('/projects/{projectId}/doc-pages', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(pages);

    table(out, pages, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'TITLE', value: (row) => show(row.title) },
      { header: 'STATUS', value: (row) => show(row.status) },
      { header: 'UPDATED', value: (row) => showDate(row.updatedAt) },
    ]);
  },
};

const docShow: Command = {
  name: 'doc show',
  summary: 'One documentation page, with its blocks',
  usage: 'tcms doc show DOC-1',
  run: async (context) => {
    const { client, out, json, request } = context;
    const projectId = await projectOf(context);
    const pageId = await resolveId({
      client, projectId, value: requireArg(context, 'page'), entity: 'DOC_PAGE',
    });

    const page = await client.GET('/projects/{projectId}/doc-pages/{pageId}', {
      params: { path: { projectId, pageId } },
      tcms: request,
    });

    if (json) return out.json(page);

    fields(out, [
      ['Key', show(page.key)],
      ['Title', show(page.title)],
      ['Status', show(page.status)],
      ['Lock version', show(page.lockVersion)],
    ]);

    /*
      Block ids are printed, and that is the point of this command rather than a nicety. This
      product resolved ERD question #18 by deciding a **requirement is a doc block** — there is
      no `acceptance_criterion` table and there never will be — so `(pageId, blockId)` is the
      thing a case is linked to, and an agent grounding itself in the spec needs the id it will
      later cite. The surface spec's §0.2 is this, and it is why `list_criteria` was dropped.
    */
    out.line();
    table(out, page.blocks, [
      { header: 'BLOCK', value: (row) => show(row.id) },
      { header: 'TYPE', value: (row) => show(row.blockType) },
      // `links` is optional on the wire, so its absence and an empty array are two different
      // answers — "not asked for" against "asked for, and there are none". Printed as 0 either
      // way here, because this column is a hint to look closer and not a claim about coverage.
      { header: 'LINKS', value: (row) => show(row.links?.length ?? 0) },
      { header: 'TEXT', value: (row) => show(row.text).slice(0, 60) },
    ]);
  },
};

/**
 * `tcms format` — the authoring format at a terminal, and the CLI half of `tcms_shape`.
 *
 * It is filed with the reads and it reads **nothing**: no project, no token, no request. That is
 * worth stating because every other command here needs at least a token, and this one is usable
 * before a person has one — which is when somebody writing their first `--file` body needs it.
 *
 * The long form is behind a flag rather than being the default. The template plus the flag list is
 * what a person wants at the moment they are assembling a command; the guide is four screens of
 * prose about attachment ids and complete-set semantics, and printing it every time would train
 * people to pipe this through `head`.
 */
const format: Command = {
  name: 'format',
  summary: 'The fields and rules for a thing you are about to write',
  offline: true,
  usage:
    'tcms format [ENTITY] [--long]\n\n'
    + '  --long\n      The full guide, including the pitfalls that cost a round trip.\n'
    + '  --json\n      The template alone, ready to fill in and pass to --file.',
  options: { long: { type: 'boolean' } },
  run: async ({ out, json, args, options }) => {
    const requested = args[0];

    if (!requested) {
      for (const entity of Object.values(SHAPES)) {
        out.line(`${entity.entity} — ${entity.title}`);
        for (const action of entity.actions) {
          // The adapter columns are the point: an action the CLI does not expose is one a person
          // would otherwise keep looking for in `--help`.
          const where = [action.cli ? 'cli' : undefined, action.mcp ? 'mcp' : undefined]
            .filter(Boolean).join('+');
          out.line(`    ${action.name.padEnd(10)} ${action.permission.padEnd(16)} ${where}`);
        }
      }
      out.line('');
      out.line('tcms format ENTITY for its fields, --long for the guide.');
      return;
    }

    const shape = shapeOf(requested);
    if (!shape) {
      throw new UsageError(
        `No entity named '${requested}'. There is: ${Object.keys(SHAPES).sort().join(', ')}.`,
      );
    }

    if (json) {
      out.json(templateFor(shape, shape.actions[0]!.name));
      return;
    }

    if (flag(options['long'])) {
      out.line(guideFor(shape));
      return;
    }

    /*
      Every body-carrying action the CLI exposes, not just the first. They spell the same fields
      differently — `case propose` takes a `--file` body and `case edit` takes flags — and a
      person reading one and running the other is the mistake this whole command exists to stop.
    */
    const authored = shape.actions.filter(
      (action) => action.cli && (action.kind === 'create' || action.kind === 'edit'),
    );
    for (const action of authored) {
      out.line(usageFor(shape, action.name));
      out.line('');
    }

    out.line('Template for --file:');
    out.line(JSON.stringify(templateFor(shape, authored[0]?.name ?? shape.actions[0]!.name), null, 2));
  },
};

/**
 * The CLI end of the review queue — the counterpart to `tcms_list_proposals`.
 *
 * `case propose` is the CLI's one create and it files into a queue this surface could not read, so
 * a person driving an agent from a terminal had no way to see what became of what it filed. The
 * ~~three~~ **two** nouns surface spec §6 promises and does not deliver (`report`, `triage`) are a
 * recorded asymmetry; this one was an omission on both surfaces at once.
 *
 * ~~`work-item`~~ was the third and **011 T5 delivered it, renamed**: `work_item` is gone, and
 * `issue list`, `issue show`, `issue create`, `issue move` and `issue comment` are below. A comment
 * that goes on naming a gap after it has closed is the failure `citedFiles.test.ts` exists over,
 * arriving through a noun instead of a filename.
 */
const proposalList: Command = {
  name: 'proposal list',
  summary: 'The review queue of proposed cases',
  usage: 'tcms proposal list [--status PENDING|ACCEPTED|REJECTED] [--page N] [--size N]',
  options: {
    status: { type: 'string' },
    page: { type: 'string' },
    size: { type: 'string' },
  },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const status = first(options['status']);

    const result = await client.GET('/projects/{projectId}/ai-proposals', {
      params: {
        path: { projectId: await projectOf(context) },
        query: {
          // Defaulted by the server to PENDING, and left to it: a queue is a list of things
          // waiting, and a client second-guessing that default is a second answer to what a
          // queue is.
          ...(status === undefined ? {} : { status: status as 'PENDING' | 'ACCEPTED' | 'REJECTED' }),
          page: number(first(options['page'])) ?? 0,
          size: clampPageSize(number(first(options['size']))),
        },
      },
      tcms: request,
    });

    if (json) return out.json(result);

    table(out, result.items, [
      { header: 'ID', value: (row) => show(row.id) },
      { header: 'TITLE', value: (row) => show(row.title) },
      { header: 'STATUS', value: (row) => show(row.status) },
      // Both, never one: a verdict rendered without its source shows a filer's CLAIM as a
      // finding, which is the inflation §6w.1a exists to prevent one layer up.
      { header: 'VERDICT', value: (row) => show(row.verdict) },
      { header: 'SOURCE', value: (row) => show(row.verdictSource) },
      { header: 'FILED', value: (row) => showDate(row.createdAt) },
    ]);

    out.line();
    out.line(
      `${result.items.length} of ${result.total} — page ${result.page + 1}`
        + `${result.size ? ` of ${Math.max(1, Math.ceil(result.total / result.size))}` : ''}`,
    );
  },
};

const proposalShow: Command = {
  name: 'proposal show',
  summary: 'One proposal, with its payload and its grounding',
  usage: 'tcms proposal show PROPOSAL_ID',
  run: async (context) => {
    const { client, out, json, request } = context;
    // By id, not by key: a proposal is not a case and has none — there is no `TC-n` until a
    // person accepts it, which is the whole point of the queue.
    const proposalId = requireArg(context, 'proposal');

    const found = await client.GET('/projects/{projectId}/ai-proposals/{proposalId}', {
      params: { path: { projectId: await projectOf(context), proposalId } },
      tcms: request,
    });

    if (json) return out.json(found);

    fields(out, [
      ['Id', show(found.id)],
      ['Title', show(found.title)],
      ['Status', show(found.status)],
      ['Verdict', `${show(found.verdict)} (${show(found.verdictSource)})`],
      ['Filed', showDate(found.createdAt)],
    ]);
    out.line();
    // Nullable, and null means UNREADABLE — a payload stored before a field left `TestCaseInput`.
    // The proposal still renders and can still be rejected, which is a decidable state.
    out.line(found.testCase ? JSON.stringify(found.testCase, null, 2) : '(the payload cannot be read)');

    if (found.grounding) {
      out.line();
      // Free-form and DISPLAYED, never interpreted — it is somebody else's critic's evidence, and
      // the contract declines to schema it for that reason.
      out.line('Grounding:');
      out.line(JSON.stringify(found.grounding, null, 2));
    }
  },
};

/* ------------------------------------------------------------------- issues ---- */

const issueList: Command = {
  name: 'issue list',
  summary: 'The project\'s issues',
  usage:
    'tcms issue list [--level EPIC|STORY|TASK] [--epic ACME-3] [--parent ACME-12]\n'
    + '                [--unparented] [--sort key|title|status|dueDate|estimateHours]\n'
    + '                [--page N] [--size N]',
  options: {
    level: { type: 'string' },
    epic: { type: 'string' },
    parent: { type: 'string' },
    unparented: { type: 'boolean' },
    sort: { type: 'string' },
    page: { type: 'string' },
    size: { type: 'string' },
  },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const level = first(options['level']);

    const rows = await client.GET('/issues', {
      params: {
        query: {
          projectId: await projectOf(context),
          ...(level === undefined ? {} : { level: level as 'EPIC' | 'STORY' | 'TASK' }),
          ...(first(options['epic']) === undefined ? {} : { epicKey: first(options['epic'])! }),
          ...(first(options['parent']) === undefined ? {} : { parentKey: first(options['parent'])! }),
          ...(flag(options['unparented']) ? { unparented: true } : {}),
          ...(first(options['sort']) === undefined
            ? {}
            : { sort: first(options['sort']) as 'key' | 'title' | 'status' | 'dueDate' | 'estimateHours' }),
        },
      },
      tcms: request,
    });

    if (json) return out.json(rows);

    // A client-side window: `/issues` is not paginated on the wire, the same as the four lists
    // `reads.ts` in the MCP package records. It bounds a terminal's scrollback, not the server.
    const size = clampPageSize(number(first(options['size'])));
    const page = Math.max(0, number(first(options['page'])) ?? 0);
    const shown = rows.slice(page * size, page * size + size);

    table(out, shown, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'TYPE', value: (row) => show(row.type.name) },
      { header: 'TITLE', value: (row) => show(row.title) },
      { header: 'STATUS', value: (row) => show(row.status.name) },
      { header: 'EPIC', value: (row) => show(row.epicKey) },
      { header: 'ASSIGNEE', value: (row) => show(row.assignee?.displayName) },
      { header: 'DUE', value: (row) => show(row.dueDate) },
    ]);

    out.line();
    out.line(`${shown.length} of ${rows.length} — page ${page + 1}`);
  },
};

const issueShow: Command = {
  name: 'issue show',
  summary: 'One issue, with its children, comments and the moves it may make',
  usage: 'tcms issue show ACME-124',
  run: async (context) => {
    const { client, out, json, request } = context;
    // By key. `project.key` is unique per company and the counter is per project, so the pair is
    // unique in the only scope RLS lets this caller see — which is why the contract addresses an
    // issue this way rather than by UUID.
    const key = requireArg(context, 'issue');
    const path = { key };

    const issue = await client.GET('/issues/{key}', { params: { path }, tcms: request });
    const comments = await client.GET('/issues/{key}/comments', { params: { path }, tcms: request });
    const docLinks = await client.GET('/issues/{key}/doc-links', { params: { path }, tcms: request });

    if (json) return out.json({ ...issue, comments, docLinks });

    fields(out, [
      ['Issue', `${show(issue.key)} — ${show(issue.title)}`],
      ['Type', `${show(issue.type.name)} (${show(issue.type.level)})`],
      ['Status', `${show(issue.status.name)} — ${show(issue.status.kind)}`],
      ['Parent', show(issue.parentKey)],
      ['Epic', show(issue.epicKey)],
      ['Assignee', show(issue.assignee?.displayName)],
      ['Priority', show(issue.priority)],
      ['Estimate', show(issue.estimateHours)],
      ['Due', show(issue.dueDate)],
      // The NAMES, which is what `issue move --status` takes. An id here would be a second
      // spelling of one thing and the one this surface does not accept.
      ['Can move to', issue.allowedTransitions.map((status) => status.name).join(', ') || '(none)'],
      ['Watchers', issue.watchers.map((watcher) => watcher.displayName).join(', ') || '(none)'],
    ]);

    if (issue.completion) {
      out.line();
      // Epics only, and the basis is printed beside the number: a percentage that changed meaning
      // silently is the two-contradictory-figures failure one screen over.
      out.line(
        `Completion  ${show(issue.completion.percent)}% by ${show(issue.completion.basis)} `
          + `(${show(issue.completion.done)} of ${show(issue.completion.total)})`,
      );
    }

    if (issue.children.length > 0) {
      out.line();
      table(out, issue.children, [
        { header: 'KEY', value: (row) => show(row.key) },
        { header: 'TYPE', value: (row) => show(row.type.name) },
        { header: 'TITLE', value: (row) => show(row.title) },
        { header: 'STATUS', value: (row) => show(row.status.name) },
      ]);
    }

    if (docLinks.length > 0) {
      out.line();
      table(out, docLinks, [
        { header: 'SPEC', value: (row) => show(row.pageKey ?? row.externalUrl) },
        { header: 'RELATION', value: (row) => show(row.relationship) },
        // The whole reason the projection carries the excerpt: `true` means the page moved on
        // after this issue last did, so what was linked is not what the page says now.
        { header: 'STALE', value: (row) => (row.stale ? 'yes' : 'no') },
      ]);
    }

    for (const comment of comments) {
      out.line();
      out.line(`${show(comment.author.displayName)} — ${showDate(comment.createdAt)}`);
      out.line(comment.body);
    }
  },
};

/* -------------------------------------------------------------------- board ---- */

/**
 * The board a `board` command acts on, named or defaulted.
 *
 * §6's table spells `board move ACME-124 --column "In flight"` with no board in it, and that is
 * right for the common case: every project is seeded one board, and naming it would be a word
 * with one possible value. It is not right for a project that has grown a second, so `--board`
 * disambiguates and the refusal lists what the project has. Defaulting silently to the first of
 * several would move a card on a board the caller was not looking at.
 */
export async function boardOf(context: CommandContext, requested: string | undefined): Promise<{
  id: string;
  name: string;
}> {
  const boards = await context.client.GET('/projects/{projectId}/boards', {
    params: { path: { projectId: await projectOf(context) } },
    tcms: context.request,
  });

  if (requested !== undefined) {
    const found = boards.find(
      (row) => row.name.toLowerCase() === requested.toLowerCase() || row.id === requested,
    );
    if (!found) {
      throw new UsageError(
        `No board '${requested}' in this project. It has: ${boards.map((row) => row.name).join(', ')}.`,
      );
    }
    return found;
  }

  const only = boards[0];
  if (!only || boards.length > 1) {
    throw new UsageError(
      `This project has ${String(boards.length)} boards — name one with --board: `
        + `${boards.map((row) => row.name).join(', ')}.`,
    );
  }
  return only;
}

/** The card filter §12.2 defines — OR within a category, AND across them — as query parameters. */
async function cardFilter(
  context: CommandContext,
): Promise<{
  labelId?: string[];
  assigneeId?: string[];
  epicKey?: string;
  level?: 'EPIC' | 'STORY' | 'TASK';
  priority?: components['schemas']['Priority'][];
  unparented?: boolean;
}> {
  const { options } = context;
  const labels = all(options['label']);
  const assignees = all(options['assignee']);
  const level = first(options['level']);
  const epic = first(options['epic']);
  const priorities = all(options['priority']);

  return {
    ...(labels.length > 0 ? { labelId: await labelIds(context, labels) } : {}),
    ...(assignees.length > 0 ? { assigneeId: await assigneeIds(context, assignees) } : {}),
    ...(epic === undefined ? {} : { epicKey: epic }),
    ...(level === undefined ? {} : { level: level as 'EPIC' | 'STORY' | 'TASK' }),
    ...(priorities.length > 0
      ? { priority: priorities as components['schemas']['Priority'][] }
      : {}),
    ...(flag(options['unparented']) ? { unparented: true } : {}),
  };
}

/**
 * Label names to the ids the filter takes.
 *
 * Shared with `tcms label set`, which resolves the same way for the same reason: `Label.id` is a
 * UUID and nobody at a terminal has one, so every label on this surface is spelled by name and an
 * unknown one is refused here rather than sent as a filter that silently matches nothing.
 */
export async function labelIds(
  context: CommandContext,
  names: readonly string[],
): Promise<string[]> {
  const labels = await context.client.GET('/projects/{projectId}/labels', {
    params: { path: { projectId: await projectOf(context) } },
    tcms: context.request,
  });

  return names.map((name) => {
    const found = labels.find(
      (row) => row.name.toLowerCase() === name.toLowerCase() || row.id === name,
    );
    if (!found) {
      throw new UsageError(
        `No label '${name}' in this project. It has: `
          + `${labels.map((row) => row.name).join(', ') || '(none yet)'}.`,
      );
    }
    return found.id;
  });
}

/**
 * Assignee display names to ids, against the people this project may assign work to.
 *
 * `GET …/members` rather than `/users`: it costs `project:read`, it is the same predicate the
 * write side enforces, and the company directory is out of reach of an agent credential anyway.
 */
async function assigneeIds(
  context: CommandContext,
  names: readonly string[],
): Promise<string[]> {
  const members = await context.client.GET('/projects/{projectId}/members', {
    params: { path: { projectId: await projectOf(context) } },
    tcms: context.request,
  });

  return names.map((name) => {
    const found = members.find(
      (row) => row.displayName.toLowerCase() === name.toLowerCase() || row.id === name,
    );
    if (!found) {
      throw new UsageError(
        `Nobody called '${name}' can be given work in this project. It has: `
          + `${members.map((row) => row.displayName).join(', ') || '(nobody)'}.`,
      );
    }
    return found.id;
  });
}

/** The five filter flags every card-shaped read takes, declared once so the two cannot drift. */
const FILTER_OPTIONS = {
  label: { type: 'string', multiple: true },
  assignee: { type: 'string', multiple: true },
  epic: { type: 'string' },
  level: { type: 'string' },
  priority: { type: 'string', multiple: true },
  unparented: { type: 'boolean' },
} as const;

const boardList: Command = {
  name: 'board list',
  summary: 'The boards over this project',
  usage: 'tcms board list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const boards = await client.GET('/projects/{projectId}/boards', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(boards);

    table(out, boards, [
      { header: 'NAME', value: (row) => show(row.name) },
      { header: 'COLUMNS', value: (row) => show(row.columnCount) },
      { header: 'SWIMLANES', value: (row) => show(row.swimlaneBy) },
      { header: 'ID', value: (row) => show(row.id) },
    ]);
  },
};

/**
 * `tcms board show "Team board"` — the cards, in lanes.
 *
 * Rendered as one line per card with its column and its lane as **columns of the line**, not as a
 * nested structure under headings. The caller is answering *what is in flight*, and a shape it has
 * to walk costs it a parse it did not need; a lane with three cards is three lines that share a
 * value, which is what a table is for.
 */
const boardShow: Command = {
  name: 'board show',
  summary: 'One board — its columns, its cards, and any column over its WIP limit',
  usage:
    'tcms board show "Team board" [--label L]... [--assignee A]... [--epic ACME-3]\n'
    + '                             [--level EPIC|STORY|TASK] [--priority P]... [--unparented]',
  options: FILTER_OPTIONS,
  run: async (context) => {
    const { client, out, json, request } = context;
    const chosen = await boardOf(context, requireArg(context, 'board'));
    const path = { boardId: chosen.id };

    const config = await client.GET('/boards/{boardId}', { params: { path }, tcms: request });
    const contents = await client.GET('/boards/{boardId}/cards', {
      params: { path, query: await cardFilter(context) },
      tcms: request,
    });

    if (json) return out.json({ ...config, ...contents });

    const columnName = new Map(config.columns.map((column) => [column.id, column.name]));

    fields(out, [
      ['Board', `${show(config.name)} — ${String(config.columns.length)} columns`],
      ['Swimlanes', show(config.swimlaneBy)],
      [
        'Columns',
        config.columns
          .map((column) => `${column.name}${column.wipLimit === null ? '' : ` (WIP ${String(column.wipLimit)})`}`)
          .join('  |  '),
      ],
    ]);

    out.line();
    table(
      out,
      contents.lanes.flatMap((lane) => lane.cards.map((card) => ({ lane, card }))),
      [
        { header: 'LANE', value: (row) => show(row.lane.label) },
        { header: 'COLUMN', value: (row) => show(columnName.get(row.card.columnId) ?? row.card.columnId) },
        { header: 'KEY', value: (row) => show(row.card.key) },
        { header: 'TYPE', value: (row) => show(row.card.typeName) },
        { header: 'TITLE', value: (row) => show(row.card.title) },
        { header: 'STATUS', value: (row) => show(row.card.status.name) },
        { header: 'ASSIGNEE', value: (row) => show(row.card.assignee?.displayName) },
        { header: 'LABELS', value: (row) => row.card.labels.map((one) => one.name).join(', ') || '—' },
      ],
    );

    // Reported, never enforced (story B3). A column over its limit is a fact about the board, so
    // it prints here as well as on the move that caused it, and neither is an error.
    if (contents.breaches.length > 0) {
      out.line();
      for (const breach of contents.breaches) {
        out.line(
          `Over the limit: ${show(columnName.get(breach.columnId) ?? breach.columnId)} holds `
            + `${String(breach.count)} of a limit of ${String(breach.limit)}.`,
        );
      }
    }
  },
};

const backlog: Command = {
  name: 'backlog',
  summary: 'The project\'s backlog, in rank order',
  usage:
    'tcms backlog [--label L]... [--assignee A]... [--epic ACME-3] [--level STORY|TASK]\n'
    + '             [--priority P]... [--unparented] [--page N] [--size N]',
  options: { ...FILTER_OPTIONS, page: { type: 'string' }, size: { type: 'string' } },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const rows = await client.GET('/projects/{projectId}/backlog', {
      params: { path: { projectId: await projectOf(context) }, query: await cardFilter(context) },
      tcms: request,
    });

    if (json) return out.json(rows);

    // A client-side window, the same as `issue list`: the endpoint answers the whole ranked list
    // and this bounds a terminal's scrollback rather than the server's work.
    const size = clampPageSize(number(first(options['size'])));
    const page = Math.max(0, number(first(options['page'])) ?? 0);
    const shown = rows.slice(page * size, page * size + size);

    table(out, shown, [
      { header: '#', value: (row) => show(row.position) },
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'TYPE', value: (row) => show(row.typeName) },
      { header: 'TITLE', value: (row) => show(row.title) },
      { header: 'STATUS', value: (row) => show(row.status.name) },
      { header: 'ASSIGNEE', value: (row) => show(row.assignee?.displayName) },
      { header: 'EPIC', value: (row) => show(row.epicKey) },
      { header: 'LABELS', value: (row) => row.labels.map((one) => one.name).join(', ') || '—' },
    ]);

    out.line();
    out.line(`${String(shown.length)} of ${String(rows.length)} — page ${String(page + 1)}`);
  },
};

const labelList: Command = {
  name: 'label list',
  summary: 'The project\'s labels',
  usage: 'tcms label list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const labels = await client.GET('/projects/{projectId}/labels', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(labels);

    table(out, labels, [
      { header: 'NAME', value: (row) => show(row.name) },
      // The design-token name, which is what `label add --color` takes. A hex value is not a
      // colour this product stores — see `Label.color` in the contract.
      { header: 'COLOUR', value: (row) => show(row.color) },
      { header: 'ID', value: (row) => show(row.id) },
    ]);
  },
};

export const READS: readonly Command[] = [
  whoami,
  format,
  projectList,
  caseList,
  caseShow,
  libraryList,
  suiteList,
  suiteShow,
  planList,
  runList,
  docList,
  docShow,
  proposalList,
  proposalShow,
  issueList,
  issueShow,
  boardList,
  boardShow,
  backlog,
  labelList,
];
