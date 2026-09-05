/**
 * The read tools — ~~ten of the twenty-one~~ **twelve of the twenty-six**.
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
import { READ_ENUMS, clampPageSize, resolveId, type components } from '@tcms/client';

import { PAGING_PROPERTIES, bool, idProperty, num, requireStr, str, strings } from './input.js';
import type { Session } from './session.js';
import type { ToolDefinition } from './tool.js';

/**
 * **The one list shape on this surface.** A client-side window, for the lists the contract does not
 * paginate — see the header — and the envelope every other list is now rendered into as well.
 *
 * One shape and one has-more key, because the alternative was measured: `tcms_search_cases`
 * answered `{ cases, page, size, total, more }`, `tcms_list_suites` `{ items, …, truncated }`,
 * `tcms_list_library` the same thing nested one level deeper under `conditions`, and the
 * single-suite branch a bare upstream object. Every list a model paged cost it a shape to re-learn,
 * and the loop condition was not the same field twice in a row.
 *
 * `hasMore` and `truncated` are kept apart rather than merged, and §4.6's argument is why: on a
 * server-paginated list `hasMore` means *ask for page + 1*, while `truncated` means *the endpoint
 * handed over everything and this tool cut it to fit the budget*. Collapsing them would imply a
 * next page four of these endpoints do not have.
 */
function window<T>(rows: readonly T[], input: Record<string, unknown>): {
  items: T[];
  page: number;
  size: number;
  total: number;
  hasMore: boolean;
  truncated: boolean;
} {
  const size = clampPageSize(num(input, 'size'));
  const page = Math.max(0, num(input, 'page') ?? 0);
  const items = rows.slice(page * size, page * size + size);
  return {
    items,
    page,
    size,
    total: rows.length,
    hasMore: (page + 1) * size < rows.length,
    truncated: rows.length > items.length,
  };
}

/**
 * {@link window}'s envelope over a list the server paged, so both read the same to a caller.
 *
 * The page metadata and the rows are separate parameters because the rows are usually a PROJECTION
 * of what arrived — `tcms_search_cases` drops the ids and keeps the keys — so the two have
 * different element types and inferring one from the other would tie them together.
 */
function paged<T>(meta: { page: number; size: number; total: number }, items: T[]): {
  items: T[];
  page: number;
  size: number;
  total: number;
  hasMore: boolean;
} {
  return {
    items,
    page: meta.page,
    size: meta.size,
    total: meta.total,
    // Stated even when the page is full: "25 of 25" and "25 of 812" look identical and mean very
    // different things about whether the filter did anything.
    hasMore: (meta.page + 1) * meta.size < meta.total,
  };
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
      /*
        Enumerated, not described — §203's rule applied to the half it was stated for and never
        swept. A host validates the input schema before the call, so a wrong member is refused
        here rather than at the server, and a filter value is refused exactly as hard as a body
        value. The members come from the catalogue and are pinned to `openapi.yaml` there.
      */
      lifecycleStatus: { type: 'string', enum: [...READ_ENUMS.lifecycleStatus.values] },
      automationStatus: { type: 'string', enum: [...READ_ENUMS.automationStatus.values] },
      priority: { type: 'string', enum: [...READ_ENUMS.priority.values] },
      healthSignal: {
        type: 'string',
        enum: [...READ_ENUMS.healthSignal.values],
        description: 'One of the repository health signals — see tcms_triage.',
      },
      releaseVersion: { type: 'string' },
      // Open by design: the contract declares `field,dir` with an example and no enum, because
      // the orderings are the resource's columns. Saying the FORM is what a caller was missing.
      sort: { type: 'string', description: 'field,dir — e.g. keySeq,asc. Every ordering ends in the case key.' },
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

    /*
      `items`, not `cases`: one list shape for the whole surface — see `window`.

      High-signal fields only (§6's *"UUIDs resolved to display keys"*). The ids are dropped
      deliberately: every tool on this surface takes the key, so an id in the result is a second
      handle for the same row that costs tokens on every case.
    */
    return paged(result, result.items.map((row) => ({
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
    })));
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
  title: 'Read the reusable condition library',
  description:
    'The project\'s reusable pre/post conditions, which a case or suite may attach by id instead '
    + 'of repeating the text. **Read-only here by design**: writing costs library:manage, which '
    + 'this surface deliberately does not carry, because that permission is gated by an impact '
    + 'modal an API caller never sees. Reference an existing entry; do not invent one. There is no '
    + 'shared-step library — it was retired in §239 because nothing ever resolved an entry\'s body, '
    + 'so a reference propagated to nothing. Steps are written on the case that owns them.',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      q: { type: 'string', description: 'Free text over the condition titles.' },
      // `BOTH` is a real member and the prose here omitted it, so the one value that narrows
      // nothing was undocumented on the only surface offering the filter.
      type: { type: 'string', enum: [...READ_ENUMS.conditionType.values] },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const path = { projectId };
    const tcms = session.request;
    const q = str(input, 'q');
    const type = str(input, 'type');

    const conditions = await session.client.GET('/projects/{projectId}/conditions', {
      params: { path, query: { ...optional('q', q), ...optional('type', type) } }, tcms,
    });

    // Not `{ conditions: … }`: the extra key was one level of nesting no other list has, so a
    // caller that had just learned to read `items` had to learn it again here.
    return window(conditions, input);
  },
};

const listArtifacts: ToolDefinition = {
  name: 'tcms_list_artifacts',
  title: 'Read the project artifact register',
  description:
    'The register of documents a project keeps: test strategies, environment guides, sign-off '
    + 'records, pinned reports. A row is one of four kinds — a documentation page reference, an '
    + 'external link, an uploaded file, or a generated report frozen as it was produced. Rows sit '
    + 'at PROJECT scope (every plan inherits them, marked `inherited`) or at one plan. `url` is '
    + 'where the document is, and what it means depends on `kind`: an external site, a download, '
    + 'or nothing at all for a page reference, which is reached by `docPageId`. `writeOnce` marks '
    + 'a sign-off record, which cannot be corrected or removed — it is superseded instead. The '
    + 'ids here are what tcms_write_artifact takes.',
  gate: { mode: 'all', permissions: ['artifact:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      scope: {
        type: 'string',
        enum: [...READ_ENUMS.artifactScope.values],
        description: 'Omit for the project register.',
      },
      plan: idProperty('plan whose register to read — implies scope PLAN', 'OLB-P1'),
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const plan = str(input, 'plan');
    // The plan is resolved by key like everywhere else on this surface, and naming one is taken
    // to mean its register — asking a caller to send `scope: 'PLAN'` beside a plan they already
    // named is a second way to say one thing, and the two can disagree.
    const planId = plan
      ? await resolveId({ client: session.client, projectId, value: plan, entity: 'PLAN' })
      : undefined;
    const scope = planId ? 'PLAN' : str(input, 'scope');

    const artifacts = await session.client.GET('/projects/{projectId}/artifacts', {
      params: {
        path: { projectId },
        query: { ...optional('scope', scope), ...optional('planId', planId) },
      },
      tcms: session.request,
    });
    return window(artifacts, input);
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

/* ------------------------------------------------------------------- issues ---- */

/**
 * A status, type or transition spelled the way both surfaces take it — as a **name**.
 *
 * The read half of `tcms_write_issue`'s decision. `allowedTransitions` arrives as
 * `IssueStatusRef` objects carrying a UUID nothing on this surface accepts, and the tool that
 * consumes them takes a name — so handing back the objects would make the model translate between
 * two spellings of one thing, which is the step it gets wrong.
 */
function nameOf(row: { name: string }): string {
  return row.name;
}

/**
 * One issue as this surface hands it back — ids traded for the names every write arm takes.
 *
 * Extracted in 015 T4 rather than written twice: `agent-context` carries a whole `Issue` inside it,
 * and a second copy of this mapping is how the two branches come to answer `allowedTransitions` as
 * names in one and as `IssueStatusRef` objects in the other.
 */
function projected(detail: components['schemas']['Issue']): Record<string, unknown> {
  return {
    ...detail,
    // The ids are dropped and the names kept, §6's rule: an id here is a second handle for a row
    // that no tool on this surface takes one for.
    type: detail.type.name,
    level: detail.type.level,
    status: detail.status.name,
    statusKind: detail.status.kind,
    allowedTransitions: detail.allowedTransitions.map(nameOf),
    watchers: detail.watchers.map((watcher) => watcher.displayName),
    children: detail.children.map((child) => ({
      key: child.key,
      title: child.title,
      type: child.type.name,
      status: child.status.name,
    })),
  };
}

const readIssue: ToolDefinition = {
  name: 'tcms_read_issue',
  title: 'List the project\'s issues, or open one',
  description:
    'Epics, stories, bugs, spikes and tasks. Omit `issue` to list them, with `level`, `epic`, '
    + '`parent` and `unparented` to narrow; name one to open it with its children, watchers and '
    + '**allowedTransitions** — the statuses it may legally move to from where it stands, spelled '
    + 'as the NAMES tcms_write_issue takes. Read those before moving anything: a move the workflow '
    + 'forbids is refused, and an Epic can never be moved because its status is derived from its '
    + 'stories. Ask for `comments` to see what people have said and `docLinks` for the '
    + 'specification blocks it is bound to. **Start here when you have been asked to do some '
    + 'work**: `queue` lists what has been flagged for you, and `context` on one of them fetches '
    + 'everything needed to do it in a single read.',
  gate: { mode: 'all', permissions: ['issue:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      issue: idProperty('issue to open — omit to list them', 'ACME-124'),
      level: {
        type: 'string',
        enum: ['EPIC', 'STORY', 'TASK'],
        description: 'Where a type sits in the hierarchy. Story, Bug and Spike are all STORY.',
      },
      epic: idProperty('epic whose stories to list', 'ACME-3'),
      parent: idProperty('issue whose children to list', 'ACME-12'),
      unparented: {
        type: 'boolean',
        description:
          'Story-level issues with no epic — the unscheduled backlog. A flag rather than an empty '
          + '`epic`, because an absent filter already means "do not filter".',
      },
      queue: {
        type: 'boolean',
        description:
          'Your AI-eligible queue — the tasks flagged for an assistant AND assigned to you. Takes '
          + 'no owner: this credential acts as its owner and reads that person\'s queue, so there '
          + 'is nobody else to name. Combine with nothing; `issue` is refused beside it.',
      },
      context: {
        type: 'boolean',
        description:
          'For one `issue`: everything needed to work it, in one read — the task, its parent '
          + 'story with the acceptance criteria, the epic\'s goal, the target release, the linked '
          + 'specification excerpts, and the project\'s repositories with their build commands. '
          + 'A project-scoped token gets nothing from another project. Read this BEFORE writing '
          + 'code: the criteria and the spec excerpts are what the work is judged against.',
      },
      sort: {
        type: 'string',
        enum: ['key', 'title', 'status', 'dueDate', 'estimateHours'],
        description: 'Newest key first when omitted.',
      },
      comments: { type: 'boolean', description: 'For one issue: include its comment thread.' },
      docLinks: { type: 'boolean', description: 'For one issue: include the spec blocks it links to.' },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const tcms = session.request;
    const key = str(input, 'issue');
    const queue = bool(input, 'queue');

    if (queue && key) {
      throw new InputMismatch(
        'queue lists the tasks assigned to you and `issue` opens one, so the two together name no '
          + 'request. Call it with queue alone, then open one of the keys it answers.',
      );
    }

    if (!key) {
      const rows = await session.client.GET('/issues', {
        params: {
          query: {
            projectId,
            ...optional('level', str(input, 'level')),
            ...optional('epicKey', str(input, 'epic')),
            ...optional('parentKey', str(input, 'parent')),
            ...optional('unparented', bool(input, 'unparented')),
            ...optional('sort', str(input, 'sort')),
            /*
              The queue is TWO terms and neither is optional. `agentEligible` alone is the
              project's whole AI-eligible pool, which is not "mine"; `assigneeId` alone is
              everything assigned to the owner, flagged or not. D30 has the flag assign nothing,
              so the intersection is the only thing that means *the work waiting for me*.
            */
            ...(queue
              ? { agentEligible: true, assigneeId: [session.reach.user.id] }
              : {}),
          },
        },
        tcms,
      });

      // The list is not paginated on the wire, so this is the client-side window — see `window`.
      return window(
        rows.map((row) => ({
          key: row.key,
          title: row.title,
          type: row.type.name,
          status: row.status.name,
          statusKind: row.status.kind,
          parentKey: row.parentKey,
          epicKey: row.epicKey,
          assignee: row.assignee?.displayName,
          dueDate: row.dueDate,
          overdue: row.overdue,
          estimateHours: row.estimateHours,
          // On every row rather than only the queue's: a marker the caller can see everywhere is
          // what stops "is this one mine to take" being a second call.
          agentEligible: row.agentEligible,
        })),
        input,
      );
    }

    /*
      The composite, and §3.1's third — its whole argument is the grounding one that section
      already makes: an agent that has to assemble a task's story, criteria, epic goal, spec
      excerpts and repository from six calls will skip it and generate blind. Served by ONE
      endpoint, so the fan-out is the server's and this stays a single call.

      Returned as it arrives except for the projection below, which is the same one the plain
      detail read applies — a caller that got names from one branch and `IssueStatusRef` objects
      from the other would have to translate between two spellings of one thing.
    */
    if (bool(input, 'context')) {
      const context = await session.client.GET('/issues/{key}/agent-context', {
        params: { path: { key } }, tcms,
      });
      return { ...context, issue: projected(context.issue) };
    }

    const detail = await session.client.GET('/issues/{key}', { params: { path: { key } }, tcms });

    return {
      ...projected(detail),
      ...(bool(input, 'comments')
        ? {
          comments: await session.client.GET('/issues/{key}/comments', {
            params: { path: { key } }, tcms,
          }),
        }
        : {}),
      ...(bool(input, 'docLinks')
        ? {
          docLinks: await session.client.GET('/issues/{key}/doc-links', {
            params: { path: { key } }, tcms,
          }),
        }
        : {}),
    };
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
    const board = await session.client.GET('/board', {
      params: { query: { projectId } }, tcms,
    });

    return {
      dashboard,
      quarantined: board
        .filter((item) => item.kind === 'QUARANTINE')
        // `status` is an object since 011 M1 — a card's status is a per-project row, not a fixed
        // enum — and the name is the half a reader of this list wants.
        .map((item) => ({ title: item.title, description: item.description, status: item.status.name })),
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

/**
 * The other end of `tcms_write_case propose` — without it the flagship write is a queue an agent
 * files into and can never read.
 *
 * §5 routes every generated case through review, the server instructions tell the model so
 * (*"filed as a proposal for a person to accept"*), and until this tool existed neither surface
 * offered `GET …/ai-proposals`. So an agent could not report to its user whether yesterday's twelve
 * proposals were accepted, could not learn from a rejection, and could not tell a duplicate from a
 * gap before filing again. Unlike the two exclusions the surface spec argues — reviewer assignment
 * and evidence upload — that one was never a decision.
 *
 * Gated with the sibling reads rather than on a key of its own: the endpoint enforces **project
 * visibility only** (`AiProposalService.list` goes through `readVisible` and asks for no
 * permission), so any narrower gate here would be this surface inventing one and hiding a tool
 * from a caller the server would have answered.
 */
const listProposals: ToolDefinition = {
  name: 'tcms_list_proposals',
  title: 'Read the review queue of proposed cases',
  description:
    'The proposals filed by tcms_write_case propose and by anything else generating cases here, '
    + 'newest first, PENDING by default — a decided queue is not a queue. Read it to find out what '
    + 'became of what you filed: ACCEPTED means a person turned it into a real test case, REJECTED '
    + 'means they did not, and the reason is on the proposal. Name one by id for its full payload '
    + 'and its grounding evidence. `verdict` is what the FILER claimed about grounding and '
    + '`verdictSource` is what that claim is worth — reading the first without the second reports '
    + 'a claim as a finding. You cannot accept a proposal: no credential can, by design.',
  gate: { mode: 'all', permissions: ['case:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      proposal: { type: 'string', description: "One proposal's id, for its payload and grounding." },
      status: {
        type: 'string',
        enum: [...READ_ENUMS.proposalStatus.values],
        description: 'Defaults to PENDING.',
      },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const proposalId = str(input, 'proposal');
    const tcms = session.request;

    if (proposalId) {
      return session.client.GET('/projects/{projectId}/ai-proposals/{proposalId}', {
        params: { path: { projectId, proposalId } }, tcms,
      });
    }

    const result = await session.client.GET('/projects/{projectId}/ai-proposals', {
      params: {
        path: { projectId },
        query: {
          ...optional('status', str(input, 'status')),
          page: num(input, 'page') ?? 0,
          size: clampPageSize(num(input, 'size')),
        },
      },
      tcms,
    });

    return paged(result, result.items);
  },
};

/* -------------------------------------------------------------------- board ---- */

/**
 * The board and backlog reads — three branches, one gate, one tool (surface spec §3.5).
 *
 * `board:read` is held by every one of the seven roles, so the branches merge under §2 with
 * nothing to check: there is no reachable intersection that reaches one of them and not the
 * others. The writes are elsewhere for the opposite reason — `board:rank` and `board:configure`
 * are Project Manager and up, and `move-card` costs `issue:edit`, so a single board tool would be
 * listed to a caller who 403s on most of it.
 *
 * **Columns and lanes come back with their cards inside them**, not flattened with a `columnId` to
 * join on: the caller is reading a board, and a join it has to perform itself is a turn spent
 * rebuilding a shape the endpoint already had. `rank` is passed through as the string it is on the
 * wire and is never parsed — see `RankResult.rank` in the contract for why it is not a number.
 */
const readBoard: ToolDefinition = {
  name: 'tcms_read_board',
  title: 'Read a board, the backlog, or the project\'s labels',
  description:
    'What the team is working on, and in what order. Omit everything to list the boards; name a '
    + '`board` for its columns with their cards inside them; ask for `backlog` for every story and '
    + 'task that is not done, in the order the team will pick them up; ask for `labels` for the '
    + 'names tcms_write_issue takes. A board is a LENS over the project\'s issues — it owns '
    + 'nothing, and two boards over one project show the same work cut a different way, so a card '
    + 'moved on one moves on all of them. `label`, `assignee` and `epic` filter by NAME or key, '
    + 'never by id; several of one kind mean "any of these" and different kinds are combined with '
    + 'AND. Each card carries `allowedTransitions` and its column, which is what tcms_write_issue '
    + 'move-card needs, and `rank`, which is a decimal STRING to sort on and never to do '
    + 'arithmetic with.',
  gate: { mode: 'all', permissions: ['board:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      board: {
        type: 'string',
        description: 'The board to open, by NAME — omit to list the boards this project has.',
      },
      backlog: {
        type: 'boolean',
        description:
          'Read the ranked backlog instead of a board: every STORY and TASK that is not done, in '
          + 'rank order, with the 1-based position each row shows.',
      },
      labels: { type: 'boolean', description: 'Read the project\'s labels instead.' },
      label: {
        type: 'array',
        items: { type: 'string' },
        description: 'Label NAMES. Any of these — an unknown one is refused with the list.',
      },
      assignee: {
        type: 'array',
        items: { type: 'string' },
        description: 'Assignee display NAMES, from the people this project may give work to.',
      },
      epic: idProperty('epic whose stories to show', 'ACME-3'),
      level: {
        type: 'string',
        enum: ['EPIC', 'STORY', 'TASK'],
        description: 'Where a type sits in the hierarchy. Story, Bug and Spike are all STORY.',
      },
      priority: {
        type: 'array',
        items: { type: 'string', enum: [...READ_ENUMS.priority.values] },
        description: 'Any of these priorities.',
      },
      unparented: { type: 'boolean', description: 'Only issues with no epic — the "No epic" lane.' },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const projectId = session.projectId();
    const tcms = session.request;
    const path = { projectId };

    if (bool(input, 'labels')) {
      return window(
        await session.client.GET('/projects/{projectId}/labels', { params: { path }, tcms }),
        input,
      );
    }

    if (bool(input, 'backlog')) {
      const rows = await session.client.GET('/projects/{projectId}/backlog', {
        params: { path, query: await cardFilter(session, input) },
        tcms,
      });
      // The list is not paginated on the wire — the same client-side window every other list here
      // uses, bounding the model's context rather than the server's work.
      return window(rows.map(backlogRow), input);
    }

    const boards = await session.client.GET('/projects/{projectId}/boards', {
      params: { path }, tcms,
    });

    const wanted = str(input, 'board');
    if (wanted === undefined) return window(boards, input);

    const found = boards.find(
      (row) => row.name.toLowerCase() === wanted.toLowerCase() || row.id === wanted,
    );
    if (!found) {
      throw new InputMismatch(
        `No board '${wanted}' in this project. It has: ${boards.map((row) => row.name).join(', ')}.`,
      );
    }

    const boardPath = { boardId: found.id };
    const [config, contents] = await Promise.all([
      session.client.GET('/boards/{boardId}', { params: { path: boardPath }, tcms }),
      session.client.GET('/boards/{boardId}/cards', {
        params: { path: boardPath, query: await cardFilter(session, input) },
        tcms,
      }),
    ]);

    const columnOf = new Map(config.columns.map((column) => [column.id, column]));

    return {
      board: config.name,
      swimlaneBy: config.swimlaneBy,
      columns: config.columns.map((column) => ({
        name: column.name,
        wipLimit: column.wipLimit,
        // The names, for the same reason every other tool here drops ids: a status id is a second
        // handle for a row nothing on this surface takes one for. `move-card` names the COLUMN.
        statuses: column.statuses.map(nameOf),
        overLimit: contents.breaches.some((breach) => breach.columnId === column.id),
      })),
      lanes: contents.lanes.map((lane) => ({
        lane: lane.label,
        completion: lane.completion,
        cards: lane.cards.map((card) => ({
          key: card.key,
          title: card.title,
          type: card.typeName,
          level: card.typeLevel,
          column: columnOf.get(card.columnId)?.name ?? card.columnId,
          status: card.status.name,
          rank: card.rank,
          priority: card.priority,
          assignee: card.assignee?.displayName,
          dueDate: card.dueDate,
          overdue: card.overdue,
          epicKey: card.epicKey,
          labels: card.labels.map(nameOf),
          allowedTransitions: card.allowedTransitions.map(nameOf),
          subtasks: `${String(card.subtaskDone)}/${String(card.subtaskTotal)}`,
        })),
      })),
      // Reported, never enforced (story B3): a column over its limit still accepts the drop, and
      // the caller is told so it can say so rather than so it can refuse.
      breaches: contents.breaches.map((breach) => ({
        column: columnOf.get(breach.columnId)?.name ?? breach.columnId,
        limit: breach.limit,
        count: breach.count,
      })),
    };
  },
};

/** A backlog row with its ids dropped, §6's rule — the key and the position are the handles. */
function backlogRow(row: {
  key: string; title: string; typeName: string; typeLevel: string; position: number; rank: string;
  status: { name: string }; assignee?: { displayName: string } | null;
  estimateHours?: number | null; epicKey?: string | null; labels: { name: string }[];
}): Record<string, unknown> {
  return {
    position: row.position,
    key: row.key,
    title: row.title,
    type: row.typeName,
    level: row.typeLevel,
    status: row.status.name,
    rank: row.rank,
    assignee: row.assignee?.displayName,
    estimateHours: row.estimateHours,
    epicKey: row.epicKey,
    labels: row.labels.map(nameOf),
  };
}
/**
 * The card filter §12.2 defines, with every name resolved to the id the query takes.
 *
 * **Names in, ids out, and both halves are §4.4's rule.** `labelId` and `assigneeId` are UUIDs of
 * per-project rows; a model handed a bare UUID field invents one and the server answers a 400 about
 * an id nobody chose. So the tool takes what the board itself shows — a label's name, a person's
 * display name — and an unknown one is refused here, naming what the project has, rather than sent
 * as a filter that silently matches nothing and reads as an empty board.
 *
 * Semantics are the endpoint's and are worth copying exactly because they are learnable: **OR
 * within a category, AND across categories.**
 */
async function cardFilter(session: Session, input: Record<string, unknown>): Promise<{
  labelId?: string[];
  assigneeId?: string[];
  epicKey?: string;
  level?: 'EPIC' | 'STORY' | 'TASK';
  priority?: components['schemas']['Priority'][];
  unparented?: boolean;
}> {
  const labels = strings(input, 'label') ?? [];
  const assignees = strings(input, 'assignee') ?? [];
  const priority = strings(input, 'priority') ?? [];

  return {
    ...(labels.length > 0 ? { labelId: await labelIdsIn(session, labels) } : {}),
    ...(assignees.length > 0 ? { assigneeId: await memberIdsIn(session, assignees) } : {}),
    ...optional('epicKey', str(input, 'epic')),
    ...optional('level', str(input, 'level') as 'EPIC' | 'STORY' | 'TASK' | undefined),
    ...(priority.length > 0
      ? { priority: priority as components['schemas']['Priority'][] }
      : {}),
    ...optional('unparented', bool(input, 'unparented')),
  };
}

/**
 * Label names to ids, against the project's own list.
 *
 * Exported because `writes.ts` resolves the same way for the same reason — the label arm of
 * `tcms_write_issue` and the three arms of `tcms_write_backlog` all name a label — and two copies
 * of *which label did they mean* would be two refusal messages for one mistake.
 */
export async function labelIdsIn(
  session: Session,
  names: readonly string[],
): Promise<string[]> {
  const labels = await session.client.GET('/projects/{projectId}/labels', {
    params: { path: { projectId: session.projectId() } },
    tcms: session.request,
  });

  return names.map((name) => {
    const found = labels.find(
      (row) => row.name.toLowerCase() === name.toLowerCase() || row.id === name,
    );
    if (!found) {
      throw new InputMismatch(
        `No label '${name}' in this project. It has: `
          + `${labels.map((row) => row.name).join(', ') || '(none yet)'}.`,
      );
    }
    return found.id;
  });
}

/**
 * Assignee display names to ids, against the people this project may give work to.
 *
 * `GET …/members` rather than anything under `/users`: §3.3 puts the company directory outside this
 * surface, and this endpoint costs `project:read` and answers exactly the set the write side
 * validates against — so a filter built from it can never name somebody the server would refuse.
 */
async function memberIdsIn(session: Session, names: readonly string[]): Promise<string[]> {
  const members = await session.client.GET('/projects/{projectId}/members', {
    params: { path: { projectId: session.projectId() } },
    tcms: session.request,
  });

  return names.map((name) => {
    const found = members.find(
      (row) => row.displayName.toLowerCase() === name.toLowerCase() || row.id === name,
    );
    if (!found) {
      throw new InputMismatch(
        `Nobody called '${name}' can be given work in this project. It has: `
          + `${members.map((row) => row.displayName).join(', ') || '(nobody)'}.`,
      );
    }
    return found.id;
  });
}

/**
 * One person's id, from a display name, an email, or the id itself.
 *
 * {@link memberIdsIn}'s lookup for a single person, exported because `tcms_write_allocation` needs
 * it and a second copy would be a second answer to *who may this project name*.
 *
 * **What it bounds is worth stating**: this resolves people who already hold a grant on the project.
 * Allocating somebody who does not is legal — the endpoint validates against COMPANY membership —
 * and is spelled by passing their id, which a directory read prints. So a value shaped like a UUID
 * goes straight through and only an unresolvable NAME is refused.
 */
export async function memberIdIn(session: Session, name: string): Promise<string> {
  const members = await session.client.GET('/projects/{projectId}/members', {
    params: { path: { projectId: session.projectId() } },
    tcms: session.request,
  });

  const found = members.find(
    (row) => row.displayName.toLowerCase() === name.toLowerCase()
      || row.email.toLowerCase() === name.toLowerCase()
      || row.id === name,
  );
  if (found) return found.id;

  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(name)) return name;

  throw new InputMismatch(
    `Nobody called '${name}' holds a grant on this project. It has: `
      + `${members.map((row) => row.displayName).join(', ') || '(nobody)'}. `
      + 'A company member with no grant here can still be allocated — pass their id, which a '
      + 'directory read prints.',
  );
}

/**
 * The board a card verb acts on, named or defaulted.
 *
 * Omitting it is right for the common case — every project is seeded one board, and naming it
 * would be an argument with one possible value — and wrong the moment a project grows a second,
 * where defaulting silently would move a card on a board nobody was looking at. So the refusal
 * lists what the project has and the caller names one.
 */
export async function boardIn(
  session: Session,
  wanted: string | undefined,
): Promise<{ id: string; name: string }> {
  const boards = await session.client.GET('/projects/{projectId}/boards', {
    params: { path: { projectId: session.projectId() } },
    tcms: session.request,
  });

  if (wanted !== undefined) {
    const found = boards.find(
      (row) => row.name.toLowerCase() === wanted.toLowerCase() || row.id === wanted,
    );
    if (!found) {
      throw new InputMismatch(
        `No board '${wanted}' in this project. It has: ${boards.map((row) => row.name).join(', ')}.`,
      );
    }
    return found;
  }

  const only = boards[0];
  if (!only || boards.length > 1) {
    throw new InputMismatch(
      `This project has ${String(boards.length)} boards — name one in 'board': `
        + `${boards.map((row) => row.name).join(', ')}.`,
    );
  }
  return only;
}

/* ----------------------------------------------------------------- releases ---- */

/**
 * The release a tool acts on, named by its **version**.
 *
 * `release.id` is a UUID and a model handed a bare UUID field invents one — `tcms_read_board`'s
 * label-and-assignee rule, and the reason bites harder here: a version passed through as a filter
 * matching nothing reads to a model as *an empty release* rather than as a mistake, so it would
 * report that 2.4 has no stories rather than that there is no 2.4.
 *
 * Exported because `writes.ts` resolves the same way for the same reason — every arm of
 * `tcms_write_release` names a version — and two copies of *which release did they mean* would be
 * two refusal messages for one mistake.
 */
export async function releaseIn(
  session: Session,
  wanted: string,
): Promise<components['schemas']['ReleaseSummary']> {
  const releases = await session.client.GET('/projects/{projectId}/releases', {
    params: { path: { projectId: session.projectId() } },
    tcms: session.request,
  });

  const found = releases.find(
    (row) => row.version.toLowerCase() === wanted.toLowerCase() || row.id === wanted,
  );
  if (!found) {
    throw new InputMismatch(
      `No release '${wanted}' in this project. It has: `
        + `${releases.map((row) => row.version).join(', ') || '(none yet)'}.`,
    );
  }
  return found;
}

/**
 * The release reads — three branches, one gate, one tool (surface spec §3.6).
 *
 * **`release:read` is the first gate on this surface an Automation Service credential does not
 * hold** (roles spec §A3), so this is the first read tool that is not listed to every credential
 * which can reach the project at all. That is correct rather than awkward — a CI token posts
 * results and has no business reading a delivery plan — and `server.test.ts` asserts it by name
 * rather than by a tool count, because a narrowing nothing tests is a narrowing that comes back.
 *
 * The writes are elsewhere for §2's reason: `release:manage` and `release:publish` are Project
 * Manager and up, and linking test cases to a story costs `issue:edit`.
 */
const readRelease: ToolDefinition = {
  name: 'tcms_read_release',
  title: 'Read the releases, one release\'s readiness, or the roadmap',
  description:
    'What is planned to ship and whether it can. Omit everything to list the releases in version '
    + 'order with their readiness inline; name a `release` by VERSION for the same row; add '
    + '`readiness` for the full answer — the story counts, the test counts, and the stories that '
    + 'stand in the way with the cases that failed under each; ask for `roadmap` for the releases '
    + 'on a timeline with their epics, plus the epics that cannot be drawn under one. '
    + '**hasRun false means NOTHING has been executed against this version, and passed/failed are '
    + 'then null and not zero.** Report that as "no run yet"; reading a null as 0 turns "nothing '
    + 'has been tested" into "nothing failed". A verdict of NOT_TESTED is that state and is not '
    + 'NOT_READY. Versions are ordered segment by segment, so 2.10 comes after 2.5. An empty list '
    + 'is the ordinary state of a project nobody has planned a release for yet, not an error.',
  gate: { mode: 'all', permissions: ['release:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      release: {
        type: 'string',
        description: 'The release to read, by VERSION (`2.4`) — omit to list them.',
      },
      readiness: {
        type: 'boolean',
        description:
          'With `release`: the full readiness answer rather than the row-sized summary — story '
          + 'counts, test counts, the blocking stories and the epics targeting it.',
      },
      roadmap: {
        type: 'boolean',
        description: 'Read the timeline instead of the list: releases with their epics, then the unscheduled epics.',
      },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const path = { projectId: session.projectId() };
    const tcms = session.request;

    if (bool(input, 'roadmap')) {
      return session.client.GET('/projects/{projectId}/roadmap', { params: { path }, tcms });
    }

    const wanted = str(input, 'release');
    if (wanted === undefined) {
      const rows = await session.client.GET('/projects/{projectId}/releases', {
        params: { path }, tcms,
      });
      // Not paginated on the wire — the client-side window every other list here uses.
      return window(rows, input);
    }

    const found = await releaseIn(session, wanted);
    if (!bool(input, 'readiness')) return found;

    return session.client.GET('/releases/{releaseId}/readiness', {
      params: { path: { releaseId: found.id } },
      tcms,
    });
  },
};

/* --------------------------------------------------------------------- time ---- */

/**
 * The one `any`-gated READ tool on this surface, and the gate is the honest shape of the question.
 *
 * A timesheet has two readers with two keys. Its owner reads their own period with `time:log`,
 * which every role that can log hours holds; an approver reads the queue with `time:approve`,
 * which is Project Manager and Company Admin (roles spec §A3 — and 016 D-f records that Test Lead
 * deliberately does not hold it). Gating this `all` would hide the tool from everybody who can
 * only log, and splitting it in two would put one noun on two tools whose arguments differ by a
 * boolean. So it is listed on either key and {@link narrow} says which scope this credential
 * actually reaches — `tcms_review_verdict`'s arrangement, one surface over.
 */
const readTimesheet: ToolDefinition = {
  name: 'tcms_read_timesheet',
  title: 'Read your timesheet, your period history, or the approvals queue',
  description:
    'Where the hours went. With nothing, the caller\'s OWN open period for today — its entries, '
    + 'its totals and whether it is still editable; `on` reads the period a date falls in; '
    + '`history` lists their recent periods newest first. `queue` reads the periods waiting on '
    + 'this caller to approve instead, which costs time:approve. '
    + '**loggedHours and approvedHours are different numbers and must never be added or swapped**: '
    + 'a period nobody has approved has logged hours and zero approved ones, and only approved '
    + 'hours reach a rollup. A period is OPEN (its owner may edit it), SUBMITTED (nobody may), '
    + 'APPROVED (final) or REJECTED (editable again, carrying the note that sent it back). The '
    + 'queue is not only the submitted ones — an unsubmitted period appears with its hours so far, '
    + 'and is something to remind about rather than to approve. Periods are semi-monthly, 1-15 and '
    + '16 to the end of the month, and nothing configures that. Ids here are what tcms_write_timesheet '
    + 'and tcms_approve_timesheet take. **Nothing on this surface returns a rate, a cost or an '
    + 'invoice value.**',
  gate: { mode: 'any', permissions: ['time:log', 'time:approve'] },
  narrow: (held) => {
    const own = held.has('time:log');
    const queue = held.has('time:approve');
    if (own && queue) return undefined;
    return own
      ? 'This credential holds time:log and not time:approve, so `queue` will be refused — it reads only this user\'s own timesheet.'
      : 'This credential holds time:approve and not time:log, so it reads the approvals `queue`; it has no timesheet of its own to read.';
  },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      on: {
        type: 'string',
        description: 'Any date inside the wanted period, `YYYY-MM-DD`. Defaults to today; the bounds are derived from it.',
      },
      history: {
        type: 'boolean',
        description: 'List this user\'s recent periods newest first, rather than one period in full.',
      },
      queue: {
        type: 'boolean',
        description: 'Read the approvals queue instead of this user\'s own timesheet. Costs time:approve.',
      },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const tcms = session.request;
    const on = str(input, 'on');
    const query = on === undefined ? {} : { on };

    if (bool(input, 'queue')) {
      const rows = await session.client.GET('/timesheets/approvals', { params: { query }, tcms });
      // Not paginated on the wire — the client-side window every other list here uses.
      return window(rows, input);
    }

    if (bool(input, 'history')) {
      const rows = await session.client.GET('/timesheets/periods', { params: { query: {} }, tcms });
      return window(rows, input);
    }

    return session.client.GET('/timesheets/periods/current', { params: { query }, tcms });
  },
};

/**
 * Estimate against approved and pending hours, at the issue grain or the epic grain.
 *
 * `issue:read` rather than a time key, because it is a property OF the issue and the panels that
 * show it are the story's and the epic's. There is no project grain and there will not be one:
 * the project figure is a budget, which is a different module behind a different gate.
 */
const readTimeRollup: ToolDefinition = {
  name: 'tcms_read_time_rollup',
  title: 'Estimate against approved and pending hours for an issue or an epic',
  description:
    'What an issue was estimated at and what has actually been spent on it. Name an `issue` for '
    + 'the issue grain; add `epic` for the epic grain, which also returns a row per child that '
    + 'counts toward completion, each flagged `overEstimate` when the approved hours have passed '
    + 'the estimate. **approvedHours and pendingHours are separate and must never be summed**: '
    + 'pending hours are logged and not yet approved, and only approved ones count as spent. '
    + '**estimateHours is null when nobody estimated**, which is not the same as an estimate of '
    + 'zero — report it as unestimated. There is no project grain on this surface.',
  gate: { mode: 'all', permissions: ['issue:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    required: ['issue'],
    properties: {
      issue: idProperty('issue or epic to read', 'ACME-118'),
      epic: {
        type: 'boolean',
        description: 'Read `issue` as an EPIC: its totals plus a row per counted child.',
      },
    },
  },
  run: async (session, input) => {
    const key = requireStr(input, 'issue');
    const tcms = session.request;
    return bool(input, 'epic')
      ? session.client.GET('/time/rollup/epics/{key}', { params: { path: { key } }, tcms })
      : session.client.GET('/time/rollup/issues/{key}', { params: { path: { key } }, tcms });
  },
};

/* ----------------------------------------------------------------- staffing ---- */

/**
 * The second `any`-gated READ, and the gate is the shape of the two questions this noun carries.
 *
 * *Who in the company has this skill and when are they free* is `staffing:directory`, a COMPANY-scope
 * key held by Instance Admin, Company Admin, Account Manager and Finance. *Who is on this project and
 * at what percentage* is `staffing:read`, a PROJECT-scope key held by Project Manager and up. They are
 * different questions with different answers and neither role holds the other's key, so gating this
 * `all` would hide the whole noun from both of them — which is §2's failure arriving through the gate
 * rather than through a merge. {@link ToolDefinition.narrow} says which half this credential reaches.
 *
 * `skill` takes NAMES and is ANDed, resolved against the company vocabulary first (§4.4): nobody has
 * a skill UUID, and an unresolved name filtered server-side would answer *nobody matches* for a typo.
 */
const readStaffing: ToolDefinition = {
  name: 'tcms_read_staffing',
  title: 'Who is allocated where, who has which skills, and when anybody is free',
  description:
    'Two questions, two gates. With `project`, who is allocated to THIS project and at what '
    + 'percentage, which costs staffing:read; each person also carries their COMPANY-WIDE total and '
    + 'the over-allocation flag and deliberately **names no other project**, so do not report the '
    + 'total as if it were all on this one. With nothing, the company directory — every member, '
    + 'their skills, their capacity and when they are next free — which costs staffing:directory; '
    + '`skill` filters by skill NAME and repeats, ANDed, and `freeFrom` keeps only people with a day '
    + 'under 100% on or after it. `person` reads one person in full. '
    + '**A null hours figure means nobody has recorded that person\'s contracted hours — report it '
    + 'as unknown, never as 0 and never as 40.** utilisationPercent is null, not zero, when nobody '
    + 'has allocated them at all. capacityHours is ALREADY net of absence; do not subtract absence '
    + 'from it again. Over-allocation is the highest single DAY (peakPercent, peakOn), not a month\'s '
    + 'sum: 100% for the first half of a month and 100% for the second is not over-allocated. '
    + '**Nothing on this surface returns a rate, a cost or an invoice value.**',
  gate: { mode: 'any', permissions: ['staffing:directory', 'staffing:read'] },
  narrow: (held) => {
    const company = held.has('staffing:directory');
    const project = held.has('staffing:read');
    if (company && project) return undefined;
    return company
      ? 'This credential holds staffing:directory and not staffing:read, so `project` will be refused — it reads the company directory and capacity.'
      : 'This credential holds staffing:read and not staffing:directory, so only `project` will answer; the company directory, the skills list and one person in full are all refused.';
  },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      project: {
        type: 'boolean',
        description: 'Read THIS project\'s allocations instead of the company directory. Costs staffing:read.',
      },
      person: {
        type: 'string',
        description: 'One person in full — profile, skills, allocations, absences and capacity. Their id, from a directory read. Costs staffing:directory unless it is this caller.',
      },
      skill: {
        type: 'array',
        items: { type: 'string' },
        description: 'Skill NAMES, repeated and ANDed — a person must hold every one. Unknown names are refused with the vocabulary.',
      },
      freeFrom: {
        type: 'string',
        description: 'Keep only people with a day under 100% allocated on or after this date, `YYYY-MM-DD`.',
      },
      from: { type: 'string', description: 'Window start, `YYYY-MM-DD`. Defaults to the first of this month.' },
      to: { type: 'string', description: 'Window end, `YYYY-MM-DD`. Defaults to three months on.' },
      ...PAGING_PROPERTIES,
    },
  },
  run: async (session, input) => {
    const tcms = session.request;
    const from = str(input, 'from');
    const to = str(input, 'to');
    const range = { ...(from === undefined ? {} : { from }), ...(to === undefined ? {} : { to }) };

    if (bool(input, 'project')) {
      return session.client.GET('/projects/{projectId}/staffing', {
        params: { path: { projectId: session.projectId() }, query: range },
        tcms,
      });
    }

    const person = str(input, 'person');
    if (person !== undefined) {
      return session.client.GET('/staffing/people/{userId}', {
        params: { path: { userId: person }, query: range },
        tcms,
      });
    }

    const names = strings(input, 'skill') ?? [];
    const freeFrom = str(input, 'freeFrom');
    const people = await session.client.GET('/staffing/people', {
      params: {
        query: {
          ...range,
          ...(names.length === 0 ? {} : { skill: await skillIdsIn(session, names) }),
          ...(freeFrom === undefined ? {} : { freeFrom }),
        },
      },
      tcms,
    });
    // Not paginated on the wire — the client-side window every other list here uses.
    return window(people, input);
  },
};

/**
 * Skill names to the ids the filter takes, against the company's own vocabulary.
 *
 * An unknown name is refused with the list rather than sent through, because a directory answering
 * *nobody matches* for a misspelling is indistinguishable from a real answer — and a model that
 * receives it will report that the company has nobody with the skill.
 */
export async function skillIdsIn(session: Session, names: readonly string[]): Promise<string[]> {
  const vocabulary = await session.client.GET('/staffing/skills', { tcms: session.request });

  return names.map((name) => {
    const found = vocabulary.find(
      (row) => row.name.toLowerCase() === name.toLowerCase() || row.id === name,
    );
    if (!found) {
      throw new InputMismatch(
        `This company has recorded no skill called '${name}'. It has: `
          + `${vocabulary.map((row) => row.name).join(', ') || '(none)'}.`,
      );
    }
    return found.id;
  });
}

/* ------------------------------------------------------------------ clients ---- */

/** The contract's `status` enum, so a value the model invented does not reach the server. */
const CLIENT_STATUSES = ['ACTIVE', 'ARCHIVED', 'ALL'] as const;

/**
 * The company's clients, one client in full, and — the thing an agent most needs told — **what this
 * noun deliberately does not carry.**
 *
 * One gate, `client:read`, unlike its two `any`-gated neighbours: both questions here are COMPANY
 * scope and the same key answers both, so there is no half of this tool a holder is refused.
 *
 * **Zero clients is a first-class answer** (scope D1 rule 2) and the description says so, because an
 * empty array is the exact shape an agent is most likely to report as a failure or a permissions
 * problem. A company building its own products never creates one, `project.client` is null for every
 * project it has, and nothing in the product marks that as missing.
 *
 * **No hours, no rate, no invoice.** An Account Manager holds `client:read` and does not hold
 * `billing:*`; what a client's work is worth is M8's. The sentence is in the description for
 * `tcms_read_timesheet`'s reason — a tool that stays silent about a boundary invites an agent to go
 * looking for a field that is gated away from its caller on purpose.
 */
const readClient: ToolDefinition = {
  name: 'tcms_read_client',
  title: "The company's clients, their contacts and the projects attached to them",
  description:
    'With nothing, every client in the company — name, location, status, how many projects name it '
    + 'and how many contacts it has. With `client` (an id from a list read), that client in full: '
    + 'its contacts and its projects. `status` filters and defaults to ACTIVE; pass ALL to include '
    + 'archived ones. '
    + '**An empty list is a complete answer and not a failure.** A company that builds its own '
    + 'products has no clients, every project it owns has a null client, and nothing anywhere marks '
    + 'that as missing — do not report it as an error, a permissions problem or unfinished setup. '
    + 'A client is a RECORD of an organisation, never an account: nobody at a client can sign in, '
    + 'and a contact is a name, a role, an email and a phone rather than a user. '
    + '**Nothing on this surface returns a rate, a cost, an hour or an invoice value** — those are '
    + 'gated on billing:* and ratecard:read, which the holder of this key need not have.',
  gate: { mode: 'all', permissions: ['client:read'] },
  inputSchema: {
    type: 'object',
    additionalProperties: false,
    properties: {
      client: {
        type: 'string',
        description: 'One client in full, by id — from a list read. Carries its contacts and its projects.',
      },
      status: {
        type: 'string',
        enum: ['ACTIVE', 'ARCHIVED', 'ALL'],
        description: 'Which clients to list. Defaults to ACTIVE, as the screen does.',
      },
    },
  },
  run: async (session, input) => {
    const tcms = session.request;
    const clientId = str(input, 'client');
    if (clientId !== undefined) {
      return session.client.GET('/clients/{clientId}', { params: { path: { clientId } }, tcms });
    }
    /*
      Narrowed against the contract's enum rather than passed through. `inputSchema` declares the
      three, but the schema is advice to the model and not a validator on this side — a tool that
      trusted it would send whatever arrived and turn a hallucinated value into a 400 the agent has
      to decode.
    */
    const asked = str(input, 'status');
    const status = CLIENT_STATUSES.find((value) => value === asked);
    return session.client.GET('/clients', {
      params: { query: status === undefined ? {} : { status } },
      tcms,
    });
  },
};

export const READ_TOOLS: readonly ToolDefinition[] = [
  orient,
  searchCases,
  openCase,
  listSuites,
  listLibrary,
  listArtifacts,
  docTree,
  openDocPage,
  listPlans,
  openRun,
  readIssue,
  readBoard,
  readRelease,
  listProposals,
  triage,
  readTimesheet,
  readTimeRollup,
  readStaffing,
  readClient,
];
