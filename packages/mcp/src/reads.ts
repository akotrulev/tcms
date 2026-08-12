/**
 * The read tools — ten of the twenty-one.
 *
 * Nouns match the CLI's exactly (surface spec §6), so a person who has read one surface can
 * predict the other even though the granularities are deliberately different: one command per
 * operation there, workflow-shaped tools here. Nothing in this file builds a URL, generates a key
 * or decides what a refusal may say; those are `@tcms/client`'s, once.
 *
 * ## Two tools fan out and the rest do not
 *
 * `tcms_orient` and `tcms_open_case` are the surface's only read composites (§3.1), and they
 * exist for §6w's own stated value: an agent must ground itself in the repository that exists
 * before proposing anything, and **an agent that has to assemble the project's shape from six
 * list calls will skip it and generate blind**. Every other tool that spans endpoints does so
 * along a single gate, per §2. A third composite needs the same argument made again, because a
 * composite's cost is an output nobody can bound.
 *
 * ## The page size bounds the model's context, not the server's work
 *
 * §4.6 asks for a default page size of 25 on every list tool. Four of the six lists here —
 * suites, plans, runs, doc pages — **have no pagination on the wire at all**; they answer a plain
 * array of the project's rows. `core/resolve.ts` records the same gap from the other side. So the
 * `page`/`size` on those is a client-side window over a response that already arrived whole: it
 * keeps the result under the 25K-token cap, which is what the budget is for, and it buys the
 * server nothing. Saying so beats implying a pagination the contract does not have.
 */
import { clampPageSize, resolveId } from '@tcms/client';

import { PAGING_PROPERTIES, bool, idProperty, num, requireStr, str, strings } from './input.js';
import type { ToolDefinition } from './tool.js';

/** A client-side window, for the four lists the contract does not paginate. See the header. */
function window<T>(rows: readonly T[], input: Record<string, unknown>): {
  items: T[];
  page: number;
  size: number;
  total: number;
  truncated: boolean;
} {
  const size = clampPageSize(num(input, 'size'));
  const page = Math.max(0, num(input, 'page') ?? 0);
  const items = rows.slice(page * size, page * size + size);
  return { items, page, size, total: rows.length, truncated: rows.length > items.length };
}

/* ------------------------------------------------------------------ orient ---- */

const orient: ToolDefinition = {
  name: 'tcms_orient',
  title: 'Get the shape of this project',
  description:
    'The project, its classification dimensions, its execution statuses, its suites, its tags '
    + 'and its case counts, in one call. Call this once before you read or write anything: the '
    + 'vocabulary here is per-project and configurable, so a priority, a status or a feature name '
    + 'guessed from another project will be refused. It is also how you learn the display-key '
    + 'prefixes this company uses.',
  gate: { mode: 'all', permissions: ['project:read'] },
  inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  run: async (session) => {
    const projectId = session.projectId();
    const path = { projectId };
    const tcms = session.request;

    /*
      Five reads, issued together. They are independent — no result feeds another — so serialising
      them would cost five round-trips to answer one question, and this is the call every session
      makes first. `Promise.all` rejects on the first failure, which is right: a partial shape is
      worse than none, because the model would ground itself in whichever half arrived.
    */
    const [project, classification, statuses, suites, tags] = await Promise.all([
      session.client.GET('/projects/{projectId}', { params: { path }, tcms }),
      session.client.GET('/projects/{projectId}/classification', { params: { path }, tcms }),
      session.client.GET('/projects/{projectId}/statuses', { params: { path }, tcms }),
      session.client.GET('/projects/{projectId}/suites', { params: { path }, tcms }),
      session.client.GET('/projects/{projectId}/tags', { params: { path }, tcms }),
    ]);

    return {
      project: { key: project.key, name: project.name, caseCount: project.caseCount },
      classification: classification.map((dimension) => ({
        name: dimension.name,
        cardinality: dimension.cardinality,
        hierarchical: dimension.hierarchical,
        // Paths, not bare names: a hierarchical dimension can hold two values called "Login"
        // under different parents, and the path is what tells them apart when a case names one.
        values: dimension.values.map((value) => value.path),
      })),
      executionStatuses: statuses.map((status) => ({
        name: status.name,
        kind: status.kind,
        countsAsPass: status.countsAsPass,
        isFinal: status.isFinal,
      })),
      suites: suites.map((suite) => ({
        key: suite.key,
        name: suite.name,
        caseCount: suite.caseCount,
        lifecycleStatus: suite.lifecycleStatus,
      })),
      // With their counts, because "which tags does this project actually use" is the question
      // a model asks before tagging anything, and a tag on two cases is a typo of one on 200.
      // Capped: this is the one field here that grows without bound, and an uncapped tag list is
      // how a result becomes the file reference the host writes to disk instead of an answer.
      tags: tags.slice(0, 100).map((row) => `${row.tag} (${row.caseCount})`),
      tagsTruncated: tags.length > 100,
    };
  },
};

/* ------------------------------------------------------------------- cases ---- */

const searchCases: ToolDefinition = {
  name: 'tcms_search_cases',
  title: 'Search and filter test cases',
  description:
    'The project\'s cases, with the full filter block. Use `q` for free text — a key-shaped `q` '
    + '("TC-42") short-circuits to an exact lookup, so this is also how you find one case by key. '
    + 'Every filter narrows; none of them widen. Results are summaries: call tcms_open_case for '
    + 'the steps, the conditions and the version needed to edit it.',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      q: { type: 'string', description: 'Free text over title and description, or one display key.' },
      suite: idProperty('suite to search inside', 'TS-7'),
      tag: { type: 'array', items: { type: 'string' }, description: 'Cases carrying ALL of these.' },
      unfiled: { type: 'boolean', description: 'Only cases filed in no suite.' },
      lifecycleStatus: { type: 'string', description: 'DRAFT, IN_REVIEW, APPROVED, DEPRECATED.' },
      automationStatus: { type: 'string' },
      priority: { type: 'string' },
      healthSignal: { type: 'string', description: 'One of the repository health signals — see tcms_triage.' },
      releaseVersion: { type: 'string' },
      sort: { type: 'string' },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const suite = str(input, 'suite');

    const result = await session.client.GET('/projects/{projectId}/cases', {
      params: {
        path: { projectId },
        query: {
          ...optional('q', str(input, 'q')),
          ...(suite
            ? { suiteId: await resolveId({ client: session.client, projectId, value: suite, entity: 'SUITE' }) }
            : {}),
          ...optional('tag', strings(input, 'tag')),
          ...optional('unfiled', bool(input, 'unfiled')),
          ...optional('lifecycleStatus', str(input, 'lifecycleStatus')),
          ...optional('automationStatus', str(input, 'automationStatus')),
          ...optional('priority', str(input, 'priority')),
          ...optional('healthSignal', str(input, 'healthSignal')),
          ...optional('releaseVersion', str(input, 'releaseVersion')),
          ...optional('sort', str(input, 'sort')),
          page: num(input, 'page') ?? 0,
          size: clampPageSize(num(input, 'size')),
        },
      },
      tcms: session.request,
    });

    return {
      // High-signal fields only (§6's *"UUIDs resolved to display keys"*). The ids are dropped
      // deliberately: every tool on this surface takes the key, so an id in the result is a
      // second handle for the same row that costs tokens on every case.
      cases: result.items.map((row) => ({
        key: row.key,
        title: row.title,
        suite: row.suiteName,
        lifecycleStatus: row.lifecycleStatus,
        priority: row.priority,
        automationStatus: row.automationStatus,
        tags: row.tags,
        // THE CODES, not the {signal, severity} pairs the wire carries since §179. Two reasons,
        // and the first is this block's own rule: a pair doubles the tokens of a field that fires
        // on most cases of a young project (`unlinked`), for a word a model already knows how to
        // rank. The second is that the ranking is not lost — the server sends them WORST FIRST, so
        // position carries what the severity would have said. A tool that starts needing the
        // severity itself should send the pair rather than reintroduce a table of them here; that
        // table is exactly what §179 deleted from the SPA.
        healthSignals: row.healthSignals.map((raised) => raised.signal),
        updatedAt: row.updatedAt,
      })),
      page: result.page,
      size: result.size,
      total: result.total,
      // Printed even when the page is full: "25 of 25" and "25 of 812" look identical and mean
      // very different things about whether the filter did anything.
      more: (result.page + 1) * result.size < result.total,
    };
  },
};

const openCase: ToolDefinition = {
  name: 'tcms_open_case',
  title: 'Read one test case in full',
  description:
    'One case with its steps, conditions, tags, classification and lockVersion. **The lockVersion '
    + 'is what you must send back to edit it** — an edit without it is last-write-wins over '
    + "whoever is editing the same case right now. Ask for `history` to see how it has been "
    + 'executed, and `versions` to see how it has changed.',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['case'],
    properties: {
      case: idProperty('test case', 'TC-42'),
      history: { type: 'boolean', description: 'Include its pass/fail execution history.' },
      versions: { type: 'boolean', description: 'Include its version list.' },
    },
  },
  // One of exactly two tools allowed above the default ceiling (§4.6). A case is one authored
  // document, and a truncated one is silently misleading grounding — the model would propose an
  // edit against steps it did not see the end of.
  meta: { 'anthropic/maxResultSizeChars': 200_000 },
  run: async (session, input) => {
    const projectId = session.projectId();
    const caseId = await resolveId({
      client: session.client, projectId, value: requireStr(input, 'case'), entity: 'CASE',
    });
    const path = { projectId, caseId };
    const tcms = session.request;

    const detail = await session.client.GET('/projects/{projectId}/cases/{caseId}', { params: { path }, tcms });

    return {
      ...detail,
      ...(bool(input, 'history')
        ? { history: await session.client.GET('/projects/{projectId}/cases/{caseId}/history', { params: { path }, tcms }) }
        : {}),
      ...(bool(input, 'versions')
        ? { versions: await session.client.GET('/projects/{projectId}/cases/{caseId}/versions', { params: { path }, tcms }) }
        : {}),
    };
  },
};

/* ------------------------------------------------------------------ suites ---- */

const listSuites: ToolDefinition = {
  name: 'tcms_list_suites',
  title: 'List the suites, or open one',
  description:
    'Suites are FLAT — a named collection, not a folder in a tree. A suite\'s members are three '
    + 'arms together: cases filed in it, cases added to it explicitly, and cases matched by its '
    + 'tag rules. Open one to see which arm each member came from, and its lockVersion.',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      suite: idProperty('suite to open — omit to list them all', 'TS-7'),
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const suite = str(input, 'suite');
    const tcms = session.request;

    if (suite) {
      const suiteId = await resolveId({ client: session.client, projectId, value: suite, entity: 'SUITE' });
      return session.client.GET('/projects/{projectId}/suites/{suiteId}', {
        params: { path: { projectId, suiteId } }, tcms,
      });
    }

    const suites = await session.client.GET('/projects/{projectId}/suites', {
      params: { path: { projectId } }, tcms,
    });
    return window(suites, input);
  },
};

const listLibrary: ToolDefinition = {
  name: 'tcms_list_library',
  title: 'Read the reusable condition and step libraries',
  description:
    'The project\'s reusable pre/post conditions and shared steps, which a case may reference '
    + 'instead of repeating. **Read-only here by design**: writing to either costs library:manage, '
    + 'which this surface deliberately does not carry, because that permission is gated by an '
    + 'impact modal an API caller never sees. Reference an existing entry; do not invent one.',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      q: { type: 'string', description: 'Free text over both libraries.' },
      type: { type: 'string', description: 'PRE or POST, for conditions.' },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const path = { projectId };
    const tcms = session.request;
    const q = str(input, 'q');
    const type = str(input, 'type');

    const [conditions, sharedSteps] = await Promise.all([
      session.client.GET('/projects/{projectId}/conditions', {
        params: { path, query: { ...optional('q', q), ...optional('type', type) } }, tcms,
      }),
      session.client.GET('/projects/{projectId}/shared-steps', {
        params: { path, query: { ...optional('q', q) } }, tcms,
      }),
    ]);

    return {
      conditions: window(conditions, input),
      sharedSteps: window(sharedSteps, input),
    };
  },
};

/* --------------------------------------------------------------------- docs ---- */

const docTree: ToolDefinition = {
  name: 'tcms_doc_tree',
  title: 'List the documentation pages',
  description:
    'The project\'s specification pages. **This is where requirements live** — this product has '
    + 'no separate acceptance-criterion record; a requirement IS a documentation block, and a '
    + 'case is linked to a block. Open a page to get its block ids, which are what a link cites '
    + 'and what survives an edit to the text.',
  gate: { mode: 'all', permissions: ['doc:read'] },
  inputSchema: { type: 'object', additionalProperties: false, properties: { ...PAGING_PROPERTIES } },
  run: async (session, input) => {
    const pages = await session.client.GET('/projects/{projectId}/doc-pages', {
      params: { path: { projectId: session.projectId() } }, tcms: session.request,
    });
    return window(pages, input);
  },
};

const openDocPage: ToolDefinition = {
  name: 'tcms_open_doc_page',
  title: 'Read one documentation page',
  description:
    'A page with its ordered blocks, each carrying the id you cite when linking a case to it, and '
    + 'the page lockVersion needed to edit it. **A block omitted from an edit is deleted**, so '
    + 'read the whole page before writing any of it.',
  gate: { mode: 'all', permissions: ['doc:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['page'],
    properties: { page: idProperty('documentation page', 'DOC-1') },
  },
  // The second of the two raised ceilings (§4.6): a page is one authored specification, and
  // grounding a generated case in a truncated one is the failure this surface exists to avoid.
  meta: { 'anthropic/maxResultSizeChars': 200_000 },
  run: async (session, input) => {
    const projectId = session.projectId();
    const pageId = await resolveId({
      client: session.client, projectId, value: requireStr(input, 'page'), entity: 'DOC_PAGE',
    });
    return session.client.GET('/projects/{projectId}/doc-pages/{pageId}', {
      params: { path: { projectId, pageId } }, tcms: session.request,
    });
  },
};

/* -------------------------------------------------------------------- plans ---- */

const listPlans: ToolDefinition = {
  name: 'tcms_list_plans',
  title: 'List the test plans, or open one',
  description:
    'Test plans, or one plan with its cases and its current statistics. Statistics come from the '
    + 'latest execution of each case, so they answer "where does this plan stand now" rather than '
    + '"what happened in run TR-3".',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      plan: idProperty('plan to open — omit to list them all', 'TP-1'),
      statistics: { type: 'boolean', description: 'Include the plan\'s current pass/fail state.' },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const plan = str(input, 'plan');
    const tcms = session.request;

    if (!plan) {
      const plans = await session.client.GET('/projects/{projectId}/plans', {
        params: { path: { projectId } }, tcms,
      });
      return window(plans, input);
    }

    const planId = await resolveId({ client: session.client, projectId, value: plan, entity: 'PLAN' });
    const path = { projectId, planId };
    const detail = await session.client.GET('/projects/{projectId}/plans/{planId}', { params: { path }, tcms });

    return {
      ...detail,
      ...(bool(input, 'statistics')
        ? {
          statistics: await session.client.GET('/projects/{projectId}/plans/{planId}/statistics', {
            params: { path }, tcms,
          }),
        }
        : {}),
    };
  },
};

/* --------------------------------------------------------------------- runs ---- */

const openRun: ToolDefinition = {
  name: 'tcms_open_run',
  title: 'List the test runs, or open one',
  description:
    'Runs, one run with its executions, or one execution in full. **An execution has no display '
    + 'key and never will** — name it by the run and the case (TR-1 plus TC-42) and this will find '
    + 'it. The execution detail carries the lockVersion tcms_record_result needs.',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      run: idProperty('run to open — omit to list them all', 'TR-1'),
      case: idProperty('case whose execution in that run to open', 'TC-42'),
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const run = str(input, 'run');
    const tcms = session.request;

    if (!run) {
      const runs = await session.client.GET('/projects/{projectId}/runs', {
        params: { path: { projectId } }, tcms,
      });
      return window(runs, input);
    }

    const runId = await resolveId({ client: session.client, projectId, value: run, entity: 'RUN' });
    const detail = await session.client.GET('/projects/{projectId}/runs/{runId}', {
      params: { path: { projectId, runId } }, tcms,
    });

    const caseKey = str(input, 'case');
    if (!caseKey) return detail;

    const execution = detail.executions.find(
      (row) => row.caseKey === caseKey || row.caseId === caseKey,
    );
    if (!execution) {
      throw new InputMismatch(
        `${caseKey} is not in ${detail.key}. A run covers the cases it was created with; adding `
          + 'one is a change to the run, not a result.',
      );
    }

    return session.client.GET('/projects/{projectId}/runs/{runId}/executions/{executionId}', {
      params: { path: { projectId, runId, executionId: execution.id } }, tcms,
    });
  },
};

/* ------------------------------------------------------------------- triage ---- */

const triage: ToolDefinition = {
  name: 'tcms_triage',
  title: 'Review what the repository needs attention on',
  description:
    'The health dashboard, the quarantined cases, what CI has recently posted, and the learned '
    + 'test-name mappings — the four reads that answer "what is wrong here". Use it before '
    + 'proposing new coverage: a stale or unmatched case is a repair, not a gap.',
  // Two keys, and the merge is safe under §2 because every reachable intersection holds both:
  // `Viewer` contributes neither, and the one role holding `case:read` without `report:view` is
  // `Automation Service`, which is non-assignable since V41 and never an owner.
  gate: { mode: 'all', permissions: ['case:read', 'report:view'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      ingestions: { type: 'boolean', description: 'Include the recent CI ingestion history.' },
      mappings: { type: 'boolean', description: 'Include the learned test-name mappings.' },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const path = { projectId };
    const tcms = session.request;

    const dashboard = await session.client.GET('/projects/{projectId}/dashboard', { params: { path }, tcms });

    /*
      Quarantine comes off the board rather than off a list of its own, because that is where it
      lives: §121's `QUARANTINE` cards are generated from `case_flake`'s authored half, and there
      is no "quarantined cases" endpoint to read instead. The board is gated by project
      visibility, which this caller already has past `case:read`.
    */
    const board = await session.client.GET('/work-items', {
      params: { query: { projectId } }, tcms,
    });

    return {
      dashboard,
      quarantined: board
        .filter((item) => item.kind === 'QUARANTINE')
        .map((item) => ({ title: item.title, description: item.description, status: item.status })),
      ...(bool(input, 'ingestions')
        ? {
          ingestions: window(
            await session.client.GET('/projects/{projectId}/ingestions', { params: { path }, tcms }),
            input,
          ),
        }
        : {}),
      ...(bool(input, 'mappings')
        ? {
          mappings: window(
            await session.client.GET('/projects/{projectId}/automation-mappings', { params: { path }, tcms }),
            input,
          ),
        }
        : {}),
    };
  },
};

/**
 * A named thing the caller asked for that is not in the thing they named it inside.
 *
 * Not a `TcmsApiError`, so `describeRefusal` sends it down the silent branch — which is right:
 * the server was never asked, so nothing about permissions has been learned and nothing about
 * them may be said. The message is the useful half and it names only rows this caller can
 * already see.
 */
class InputMismatch extends Error {}

/** Adds a query key only when it has a value, so an absent filter is absent and not empty. */
function optional<T>(name: string, value: T | undefined): Record<string, T> {
  return value === undefined ? {} : { [name]: value };
}

export const READ_TOOLS: readonly ToolDefinition[] = [
  orient,
  searchCases,
  openCase,
  listSuites,
  listLibrary,
  docTree,
  openDocPage,
  listPlans,
  openRun,
  triage,
];
