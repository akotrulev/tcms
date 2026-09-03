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
  DEFAULT_PAGE_SIZE,
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
import { fields, show, showAuthor, showDate, table } from './output.js';

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
      out.line(`${showAuthor(comment)} — ${showDate(comment.createdAt)}`);
      out.line(comment.body);
    }
  },
};

/**
 * `tcms issue queue` — the AI-eligible tasks assigned to whoever this token acts as.
 *
 * Two terms, and both are required for the answer to mean anything. `agentEligible` alone is the
 * project's whole flagged pool, which is not *mine*; `assigneeId` alone is everything on my plate,
 * flagged or not. D30 has the flag assign nothing and D26 has a token act as its owner, so the
 * intersection is the only spelling of *the work waiting for me*.
 *
 * The owner is read from `/me` rather than taken as a flag. A `--assignee` here would let a token
 * read somebody else's queue, and there is no version of that this command needs.
 */
const issueQueue: Command = {
  name: 'issue queue',
  summary: 'The AI-eligible tasks assigned to you',
  usage: 'tcms issue queue [--page N] [--size N]',
  options: { page: { type: 'string' }, size: { type: 'string' } },
  run: async (context) => {
    const { client, out, json, options, request } = context;
    const me = await client.GET('/me', { tcms: request });

    const rows = await client.GET('/issues', {
      params: {
        query: {
          projectId: await projectOf(context),
          agentEligible: true,
          assigneeId: [me.id],
        },
      },
      tcms: request,
    });

    if (json) return out.json(rows);

    // The same client-side window `issue list` uses, and for the same reason: `/issues` is not
    // paginated on the wire.
    const size = clampPageSize(number(first(options['size'])));
    const page = Math.max(0, number(first(options['page'])) ?? 0);
    const shown = rows.slice(page * size, page * size + size);

    table(out, shown, [
      { header: 'KEY', value: (row) => show(row.key) },
      { header: 'TYPE', value: (row) => show(row.type.name) },
      { header: 'TITLE', value: (row) => show(row.title) },
      { header: 'STATUS', value: (row) => show(row.status.name) },
      { header: 'DUE', value: (row) => show(row.dueDate) },
    ]);

    out.line();
    if (rows.length === 0) {
      // An empty queue is the ordinary answer and it is not an error, so it says what would put
      // something in it rather than printing a bare `0 of 0`.
      out.line('Nothing flagged for an assistant is assigned to you in this project.');
      return;
    }
    out.line(`${shown.length} of ${rows.length} — page ${page + 1}`);
  },
};

/**
 * `tcms issue context ACME-124` — everything needed to work one task, in one read.
 *
 * The composite, and §3.1's grounding argument is the whole of why it is one endpoint rather than
 * five commands piped together: the story, its acceptance criteria, the epic's goal, the linked
 * specification excerpts and the repository to check out are what the work is judged against, and
 * an assistant that has to assemble them itself will skip it.
 */
const issueContext: Command = {
  name: 'issue context',
  summary: 'Everything needed to work one task — story, criteria, epic goal, specs, repositories',
  usage: 'tcms issue context ACME-124',
  run: async (context) => {
    const { client, out, json, request } = context;
    const key = requireArg(context, 'issue');

    const found = await client.GET('/issues/{key}/agent-context', {
      params: { path: { key } }, tcms: request,
    });

    if (json) return out.json(found);

    fields(out, [
      ['Task', `${show(found.issue.key)} — ${show(found.issue.title)}`],
      ['Status', show(found.issue.status.name)],
      ['Release', show(found.issue.release?.version)],
      // The NAMES again, because `issue move --status` is the next command a reader types. Under
      // the review gate a DONE status is simply absent from this list rather than present and
      // refused — see the server's 409 detail, which names the move to make instead.
      ['Moves allowed', found.issue.allowedTransitions.map((row) => row.name).join(', ') || '(none)'],
      ['Story', found.story ? `${show(found.story.key)} — ${show(found.story.title)}` : '(none)'],
      ['Epic goal', show(found.epic?.goal)],
    ]);

    if (found.story && found.story.acceptanceCriteria.length > 0) {
      out.line();
      out.line('Acceptance criteria');
      for (const criterion of found.story.acceptanceCriteria) {
        out.line(`  [${criterion.done ? 'x' : ' '}] ${criterion.text}`);
      }
    }

    if (found.specExcerpts.length > 0) {
      out.line();
      out.line('Specification');
      for (const excerpt of found.specExcerpts) {
        out.line(`  ${show(excerpt.title)}`);
        out.line(`    ${excerpt.excerpt}`);
      }
    }

    out.line();
    if (found.repositories.length === 0) {
      // Reference data (D16): a project with none is not degraded, and saying so is what stops a
      // reader treating an empty list as a failed read.
      out.line('Repositories  (none recorded — this project has not listed any)');
      return;
    }
    table(out, found.repositories, [
      { header: 'REPOSITORY', value: (row) => show(row.name) },
      { header: 'URL', value: (row) => show(row.url) },
      { header: 'BRANCH', value: (row) => show(row.defaultBranch) },
      { header: 'DIR', value: (row) => show(row.subdirectory) },
      { header: 'BUILD', value: (row) => show(row.buildCommand) },
      { header: 'PRIMARY', value: (row) => (row.primary ? 'yes' : 'no') },
    ]);
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

/* ------------------------------------------------------------------ releases ---- */

/**
 * The badge a readiness answer is printed as — plan 013 D-e.
 *
 * A **released** version is reported by its status and everything else by the verdict the server
 * computed, so the column never means "a person set this" on one row and "the server worked this
 * out" on the next. `satisfies` is the point: a fourth verdict member is a compile error here.
 */
const VERDICT = {
  READY: 'Ready',
  NOT_READY: 'Not ready',
  NOT_TESTED: 'Not tested',
} satisfies Record<components['schemas']['ReleaseVerdict'], string>;

/**
 * The tests column, and the one cell in this surface that must not round.
 *
 * `casesPassed` and `casesFailed` are **null and not zero** when nothing has run against the
 * version, and the words are the answer rather than a placeholder for it: `0/0` says every test
 * passed, which is the one sentence this module exists to stop the product saying. A `?? 0`
 * anywhere between the wire and here reintroduces it and no type would notice.
 */
function tests(readiness: components['schemas']['ReadinessSummary']): string {
  return readiness.hasRun
    ? `${show(readiness.casesPassed)}/${String(readiness.linkedCases)}`
    : 'no run yet';
}

/** How a readiness answer reads, whether it came inline on a row or from `/readiness`. */
function badge(
  status: components['schemas']['ReleaseStatus'],
  verdict: components['schemas']['ReleaseVerdict'],
): string {
  return status === 'RELEASED' ? 'Released' : VERDICT[verdict];
}

/**
 * The release a command acts on, named by its **version**.
 *
 * `release.id` is a UUID and every command here is spelled `tcms release publish 2.4`, so the
 * version is resolved against the project's own list and an unknown one is refused by printing
 * what the project has. `labelIds` makes the same argument for the same reason; the sharper one
 * here is that a version is *text a person typed*, so a typo is the expected input rather than an
 * unlikely one.
 *
 * Exported because `writes.ts` resolves the same way — four of the six release writes name a
 * version — and two copies of *which release did they mean* would be two refusals for one mistake.
 */
export async function releaseOf(
  context: CommandContext,
  requested: string,
): Promise<components['schemas']['ReleaseSummary']> {
  const releases = await context.client.GET('/projects/{projectId}/releases', {
    params: { path: { projectId: await projectOf(context) } },
    tcms: context.request,
  });

  const found = releases.find(
    (row) => row.version.toLowerCase() === requested.toLowerCase() || row.id === requested,
  );
  if (!found) {
    throw new UsageError(
      `No release '${requested}' in this project. It has: `
        + `${releases.map((row) => row.version).join(', ') || '(none yet — tcms release create)'}.`,
    );
  }
  return found;
}

const releaseList: Command = {
  name: 'release list',
  summary: 'The project\'s releases, in version order',
  usage: 'tcms release list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const rows = await client.GET('/projects/{projectId}/releases', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(rows);

    table(out, rows, [
      { header: 'VERSION', value: (row) => show(row.version) },
      { header: 'TARGET', value: (row) => show(row.targetDate) },
      { header: 'STORIES', value: (row) => `${String(row.readiness.storiesDone)}/${String(row.readiness.storiesTotal)}` },
      { header: 'TESTS', value: (row) => tests(row.readiness) },
      { header: 'READINESS', value: (row) => badge(row.status, row.readiness.verdict) },
    ]);

    out.line();
    // Said rather than implied: the order is a computed key, so a reader who expected the text
    // order has no way to tell a sorted list from an unsorted one by looking at this one.
    out.line('2.10 sorts after 2.5 — versions are compared segment by segment, not as text.');
    if (rows.length === 0) {
      // An empty list is the ordinary state of a fresh instance (D14), not a failure to read one.
      out.line('No releases yet. Nothing is migrated into one — the first is one somebody types.');
    }
  },
};

const releaseShow: Command = {
  name: 'release show',
  summary: 'One release, and whether it can ship',
  usage: 'tcms release show 2.4',
  run: async (context) => {
    const { client, out, json, request } = context;
    const chosen = await releaseOf(context, requireArg(context, 'release', 0));

    const readiness = await client.GET('/releases/{releaseId}/readiness', {
      params: { path: { releaseId: chosen.id } },
      tcms: request,
    });

    if (json) return out.json(readiness);

    fields(out, [
      ['Release', show(readiness.version)],
      ['Target date', show(chosen.targetDate)],
      ['Readiness', badge(readiness.status, readiness.verdict)],
      [
        'Stories',
        `${String(readiness.stories.done)}/${String(readiness.stories.total)} done — `
          + `${String(readiness.stories.inProgress)} in progress, `
          + `${String(readiness.stories.notStarted)} not started`
          // Served so the screen can say why the total does not match the backlog (D8).
          + `, ${String(readiness.stories.excludedSpikes)} spikes not counted`,
      ],
      [
        'Tests',
        readiness.tests.hasRun
          ? `${show(readiness.tests.passed)} passed, ${show(readiness.tests.failed)} failed, `
            + `${show(readiness.tests.blocked)} blocked, ${show(readiness.tests.untested)} untested `
            + `of ${String(readiness.tests.linkedCases)} linked`
          : `no run yet — ${String(readiness.tests.linkedCases)} linked cases, none executed `
            + 'against this version',
      ],
      ['Last run', readiness.tests.hasRun ? show(readiness.tests.latestRunName) : '—'],
      // From `issue_fact`, which is event-fed, so it can lag the rest of this answer by a tick
      // (D-f). Labelled rather than printed as a bare number beside numbers that cannot lag.
      ['Scope added since created', `${String(readiness.scopeAddedAfterCreation)} (projected)`],
    ]);

    if (readiness.blockers.length > 0) {
      out.line();
      out.line('What stands between here and shipping:');
      table(out, readiness.blockers, [
        { header: 'KEY', value: (row) => show(row.key) },
        { header: 'TITLE', value: (row) => show(row.title) },
        // The status by NAME, because it is what separates "nobody has started" from "this is
        // finished and its test says otherwise" — the case this module exists for.
        { header: 'STATUS', value: (row) => show(row.status.name) },
        { header: 'PASSED', value: (row) => String(row.casesPassed) },
        { header: 'FAILED', value: (row) => String(row.casesFailed) },
        { header: 'UNTESTED', value: (row) => String(row.casesUntested) },
        { header: 'FAILED CASES', value: (row) => row.failedCaseKeys.join(', ') || '—' },
      ]);
    }

    if (readiness.epics.length > 0) {
      out.line();
      table(out, readiness.epics, [
        { header: 'EPIC', value: (row) => show(row.key) },
        { header: 'TITLE', value: (row) => show(row.title) },
        { header: 'DONE', value: (row) => (row.completionPercent === null ? '—' : `${String(row.completionPercent)}%`) },
      ]);
    }
  },
};

/**
 * `tcms roadmap` — the releases in version order with their epics under them, then the lane of
 * epics that cannot be drawn under one.
 *
 * The unscheduled lane prints **last and always**, even when it is empty, because it is the answer
 * to *where did my epic go*: an epic with no dates or no release does not disappear from the
 * roadmap, and a lane that appeared only when it had members would leave a reader to guess that.
 */
const roadmap: Command = {
  name: 'roadmap',
  summary: 'Releases on a timeline, with the epics targeting each',
  usage: 'tcms roadmap',
  run: async (context) => {
    const { client, out, json, request } = context;
    const plan = await client.GET('/projects/{projectId}/roadmap', {
      params: { path: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(plan);

    if (plan.releases.length === 0 && plan.unscheduled.length === 0) {
      out.line('The roadmap is empty. Nothing is migrated into a release — create one with');
      out.line('  tcms release create --version 2.4 --date 2026-11-14');
      return;
    }

    for (const one of plan.releases) {
      out.line(
        `${show(one.version)}  ${one.targetDate === null ? '(no target date)' : `target ${show(one.targetDate)}`}`
          + `  —  ${badge(one.status, one.verdict)}`,
      );
      for (const epic of one.epics) out.line(`    ${epicLine(epic)}`);
      if (one.epics.length === 0) out.line('    (no epics targeting it)');
      out.line();
    }

    out.line(`Unscheduled — ${String(plan.unscheduled.length)} epic(s) with no dates or no release:`);
    for (const epic of plan.unscheduled) out.line(`    ${epicLine(epic)}`);
  },
};

function epicLine(epic: components['schemas']['RoadmapEpic']): string {
  const dates = epic.startDate === null && epic.targetDate === null
    ? 'no dates'
    : `${show(epic.startDate)} → ${show(epic.targetDate)}`;
  const done = epic.completionPercent === null ? '' : `  ${String(epic.completionPercent)}%`;
  return `${show(epic.key)}  ${show(epic.title)}  [${dates}]  ${show(epic.statusKind)}${done}`;
}

/**
 * `tcms issue cases ACME-118` — the test cases that prove a story, with their latest result.
 *
 * The result is the latest the case has **anywhere**, deliberately not scoped to a release: this
 * answers *does this case pass*, and `release show` answers *did it pass for the version we are
 * shipping*. The two are different questions and the contract serves them from different places.
 */
const issueCases: Command = {
  name: 'issue cases',
  summary: 'The test cases a story is proven by, with their latest result',
  usage: 'tcms issue cases ACME-118',
  run: async (context) => {
    const { client, out, json, request } = context;
    const key = requireArg(context, 'issue');

    const rows = await client.GET('/issues/{key}/test-cases', {
      params: { path: { key } },
      tcms: request,
    });

    if (json) return out.json(rows);

    table(out, rows, [
      { header: 'CASE', value: (row) => show(row.caseKey) },
      { header: 'TITLE', value: (row) => show(row.title) },
      // Null for a case nothing has ever executed, and printed as such: three nulls is a real
      // state the contract requires be distinguishable from an endpoint that serves nothing.
      { header: 'LAST RESULT', value: (row) => show(row.lastResultKind) },
      { header: 'EXECUTED', value: (row) => showDate(row.lastExecutedAt) },
      { header: 'VERSION', value: (row) => show(row.lastReleaseVersion) },
    ]);

    if (rows.length === 0) {
      out.line();
      out.line(`${key} has no linked test cases. Link them with: tcms issue cases set ${key} --case TC-311`);
    }
  },
};

/**
 * `tcms import list` — the migrations this project has been through, newest first.
 *
 * **No `--project` flag of its own**, and the endpoint's `projectId` is a query parameter rather
 * than a path segment, which makes that easy to get wrong: no command in this client takes a
 * project id (surface spec §4.1), so it comes from `projectOf` like every other project-scoped
 * read and `--project` stays the one context flag.
 *
 * There is no `tcms import run` and no `tcms import preview` to pair with these, and the absence is
 * a decision rather than a gap — see §3.7 and plan 014 D-l. The credential a run needs would arrive
 * as an argument or an environment variable, which is shell history and a CI log; and the mapping a
 * run needs is a confirmation somebody makes **having read a preview**, which a flag file is that
 * confirmation with the reading removed.
 */
const importList: Command = {
  name: 'import list',
  summary: 'Migrations into this project, newest first',
  usage: 'tcms import list',
  run: async (context) => {
    const { client, out, json, request } = context;
    const runs = await client.GET('/imports', {
      params: { query: { projectId: await projectOf(context) } },
      tcms: request,
    });

    if (json) return out.json(runs);

    table(out, runs, [
      { header: 'STARTED', value: (row) => showDate(row.startedAt) },
      { header: 'PROVIDER', value: (row) => show(row.provider) },
      { header: 'SOURCE', value: (row) => `${show(row.sourceProjectKey)} @ ${show(row.siteUrl)}` },
      { header: 'STATUS', value: (row) => show(row.status) },
      { header: 'ISSUES', value: (row) => show(row.issuesImported) },
      { header: 'STATUSES', value: (row) => show(row.statusesMapped) },
      { header: 'LABELS', value: (row) => show(row.labelsCreated) },
      { header: 'DROPPED', value: (row) => show(row.droppedFieldCount) },
      { header: 'ID', value: (row) => show(row.id) },
    ]);

    out.line();
    if (runs.length === 0) {
      // Never imported is the ordinary state of a project, not a failure to read one — and a
      // reader who typed this because they expected a row needs to know where the other verb is.
      out.line('This project has imported nothing. An import is started in the web app, at');
      out.line('  Settings → Import — the connection is entered there and kept nowhere.');
      return;
    }
    out.line(`tcms import show ${show(runs[0]?.id)} — the report, with what was not imported.`);
  },
};

/**
 * `tcms import show <importRunId>` — one report, a year later, with the source system gone.
 *
 * **The dropped-field table is never truncated.** Every other list on this surface is windowed to
 * §4.6's budget, and this one is the exception on purpose: it is the artifact the whole module
 * exists to produce, and a report that elides the thing it records is worse than no command —
 * somebody reading it is deciding whether a field they remember survived, and an answer that
 * stops at twenty-five rows answers *no* and *not shown* in the same words. The budget applies to
 * the status mapping, which is a listing of what did arrive and carries a *"n more"* line.
 *
 * Taken by id and not by anything friendlier, unlike `release show 2.4`: a run has no name a
 * person typed, and `import list` prints the ids beside the dates that distinguish them.
 */
const importShow: Command = {
  name: 'import show',
  summary: 'One import report — the counts, the mapping, and every field that was dropped',
  usage: 'tcms import show <importRunId>',
  run: async (context) => {
    const { client, out, json, request } = context;
    const report = await client.GET('/imports/{importRunId}', {
      params: { path: { importRunId: requireArg(context, 'import run', 0) } },
      tcms: request,
    });

    if (json) return out.json(report);

    fields(out, [
      ['Source', `${show(report.sourceProjectKey)} @ ${show(report.siteUrl)} (${show(report.provider)})`],
      ['Account', show(report.accountEmail)],
      ['Status', show(report.status)],
      ['Started', `${showDate(report.startedAt)} by ${show(report.startedBy?.displayName)}`],
      ['Finished', showDate(report.finishedAt)],
      ['Run id', show(report.id)],
    ]);

    // Printed before anything is counted: a FAILED run wrote nothing (the whole read is one
    // all-or-nothing transaction), so its zeroes are the transaction rolling back rather than a
    // source project that was empty, and the two look identical in a table.
    if (report.status === 'FAILED') {
      out.line();
      out.line(`This run failed and the project was left unchanged: ${show(report.failureReason)}`);
    }
    if (report.status === 'RUNNING') {
      out.line();
      out.line('This run is still going. The counts below are what has been read, not what arrived —');
      out.line('nothing is written until it finishes.');
    }

    out.line();
    fields(out, [
      ['Issues', show(report.issuesImported)],
      ['Releases', `${show(report.releasesCreated)} (one per fix version)`],
      ['Comments', show(report.commentsImported)],
      ['Labels', show(report.labelsCreated)],
      ['Values dropped', show(report.droppedValueCount)],
      ['Sprints', report.labelPerSprint ? 'one label per sprint name' : 'dropped, no label kept'],
      [
        'Backlog order',
        report.backlogOrderPreserved
          ? "the source's own order"
          // Said rather than left blank (D-i): an unpreserved order is insertion order, which
          // nobody chose, and a reader who is not told that reads the backlog as imported.
          : 'insertion order — arbitrary, chosen by nobody',
      ],
      ['Authors', peopleLine(report.people)],
    ]);

    out.line();
    out.line('Status mapping, as it was applied:');
    // The names are the ones captured at the time (D-g). A status renamed or removed since is
    // still printed as what it was called when these issues were put in it.
    const shown = report.statusMapping.slice(0, DEFAULT_PAGE_SIZE);
    table(out, shown, [
      { header: 'SOURCE', value: (row) => show(row.sourceStatusName) },
      { header: 'BECAME', value: (row) => show(row.targetStatusName) },
      { header: 'KIND', value: (row) => show(row.targetStatusKind) },
      { header: 'ISSUES', value: (row) => show(row.issueCount) },
    ]);
    const rest = report.statusMapping.length - shown.length;
    if (rest > 0) out.line(`… ${String(rest)} more — tcms import show ${show(report.id)} --json`);

    out.line();
    out.line(`Not imported — ${String(report.droppedFields.length)} field(s):`);
    table(out, report.droppedFields, [
      { header: 'FIELD', value: (row) => show(row.name) },
      { header: 'TYPE', value: (row) => show(row.fieldType) },
      { header: 'VALUES', value: (row) => show(row.valueCount) },
      { header: 'WHY', value: (row) => show(row.reason) },
    ]);
  },
};

/**
 * The authors, as a count and then the names nobody here matched.
 *
 * The unmatched ones are named rather than counted, because that is the actionable half: their
 * comments carry the source's own string on `externalAuthor` and were **not** reassigned to
 * somebody who did not write them (D-j), and a reader deciding whether that is acceptable needs
 * to see which people it happened to.
 */
function peopleLine(people: readonly components['schemas']['ImportedPerson'][]): string {
  if (people.length === 0) return 'none';
  const unmatched = people.filter((person) => !person.matched);
  const matched = `${String(people.length - unmatched.length)} of ${String(people.length)} matched`;
  return unmatched.length === 0
    ? matched
    : `${matched} — kept as the source's own text: ${unmatched.map((person) => person.sourceName).join(', ')}`;
}

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
  issueQueue,
  issueContext,
  boardList,
  boardShow,
  backlog,
  labelList,
  releaseList,
  releaseShow,
  roadmap,
  issueCases,
  importList,
  importShow,
];
