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
import { clampPageSize, resolveId, resolveProject } from '@tcms/client';

import {
  all,
  first,
  number,
  projectOf,
  requireArg,
  type Command,
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
      // Sorted, and printed as keys rather than grouped by role — this client cannot name a
      // role, and `core/refusal.ts` records why: the role→permission catalogue is behind
      // `company:read`, which an agent token deliberately does not hold.
      out.line([...permissions.permissions].sort().join('  '));
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

    if (found.description) {
      out.line();
      out.line(found.description);
    }
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

export const READS: readonly Command[] = [
  whoami,
  projectList,
  caseList,
  caseShow,
  suiteList,
  planList,
  runList,
  docList,
  docShow,
];
