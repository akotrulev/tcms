import { matchPath } from 'react-router-dom';

/**
 * The breadcrumb trail for every route, in one file (021 §3, D-g).
 *
 * ## Why a map and not `handle` on the routes
 *
 * The proposal writes the trail as route metadata — `handle: { crumbs }` read back through
 * `useMatches()` — and that is a data-router API. `app/router.tsx` declares its 78 routes with
 * `<Routes>`/`<Route>` elements, where neither `handle` nor `useMatches` exists: converting to
 * `createBrowserRouter` is a rewrite of every route in the app, plus its guards, its `<Suspense>`
 * boundaries and its two catch-alls, to move one string per route into a different property.
 *
 * So the metadata lives here, keyed by the same path pattern the route declares, and is resolved
 * with `matchPath` against the location — the same match the router itself performs, from the same
 * strings. The outcome is identical: **no page passes a `crumbs` prop**, one file holds every
 * trail, and `ShellBreadcrumbs` renders it in the header. `routeMeta.test.ts` asserts the key set
 * equals `router.tsx`'s path set, both ways, so a route added without a trail is a red test rather
 * than a screen that quietly loses its breadcrumb.
 *
 * If the router is ever converted, this file becomes the body of each route's `handle` and nothing
 * else about the shell moves.
 *
 * ## What the trail says, and the one thing it does not
 *
 * `Projects / ACME / Test cases / …` — the project is the **first** crumb (R1), which is what pays
 * for the project switcher leaving the header, and it names the project by its KEY, from the
 * project the shell has already read.
 *
 * **A detail route's last crumb is the noun, not the record's key.** The mockup draws
 * `… / Test cases / TC-311`, and the shell cannot honestly write that: a case key is not in the
 * URL — the segment is a UUID — so producing it needs a read of the case, and a breadcrumb that
 * fires a query per navigation is a breadcrumb that can fail, load, and be stale. The one place
 * the key IS in the URL is `/issues/:key`, and there it is used. Everywhere else the leaf reads
 * `Case`, `Run`, `Release`; the record's own identifier is on the page, one line below, where the
 * screen has already loaded it. Resolving these from the query cache is a real option and is
 * deliberately not taken here — it would put a cache read behind every route in the app to
 * decorate a trail whose job is getting you back up it.
 */
export interface Crumb {
  label: string;
  /** Omit on the final crumb, and on a grouping segment that is not a destination. */
  to?: string;
  /** A longer form for a terse label — the project's name behind its key. */
  title?: string;
}

/** What the shell knows that the URL does not. */
export interface CrumbContext {
  /** The active project's key (`ACME`). Absent until `useProject` answers. */
  projectKey?: string;
  projectName?: string;
}

type CrumbsOf = (params: Record<string, string | undefined>, ctx: CrumbContext) => Crumb[];

/**
 * A route with no trail at all — the three public routes, which render outside `AppShell` and have
 * no header to draw one in.
 *
 * Declared rather than omitted: `routeMeta.test.ts` compares this file's key set against the
 * router's, so "this route deliberately has none" and "somebody forgot" have to be different
 * states or the rule cannot tell them apart.
 */
const none: CrumbsOf = () => [];

/** `Projects / <KEY>` — the head of every trail inside the project frame. */
function project(projectId: string | undefined, ctx: CrumbContext): Crumb[] {
  return [
    { label: 'Projects', to: '/' },
    {
      // The key, because that is what the rail's switcher and the mockups show, and it is what
      // somebody says out loud. The name is the `title`, so the full words are one hover away
      // without the trail growing to the width of a client's product name.
      label: ctx.projectKey ?? ctx.projectName ?? 'Project',
      title: ctx.projectName,
      to: `/projects/${projectId ?? ''}`,
    },
  ];
}

/** `Projects / <KEY> / <label>` — the shape every project index screen has. */
function inProject(label: string): CrumbsOf {
  return (q, ctx) => [...project(q.projectId, ctx), { label }];
}

/**
 * `Company / …` and `Account / …` lead with a **grouping crumb that is not a link**.
 *
 * There is no `/company` and no `/account` page to point either of them at, and inventing one so a
 * breadcrumb has somewhere to go is the tail wagging the product. A crumb with no `to` renders as
 * text, which is what these are: the zone the screen sits in, named so the trail says where you
 * are rather than only what you are looking at.
 */
function group(zone: string, ...rest: Crumb[]): CrumbsOf {
  return () => [{ label: zone }, ...rest];
}

export const ROUTE_META: Record<string, CrumbsOf> = {
  /* The three public routes — no shell, no header, no trail. */
  '/login': none,
  '/accept-invitation': none,
  '/sso/callback': none,

  /* The You zone (§1). `/` is the picker the first crumb of every project trail points back at. */
  '/': () => [{ label: 'Projects' }],
  '/projects': none, // A redirect to `/`; it never renders.
  '/my-work': () => [{ label: 'My work' }],
  '/search': () => [{ label: 'Search test cases' }],
  /* Both left the rail for the user menu (R11), so neither has a zone crumb above it. */
  '/timesheets': () => [{ label: 'Timesheets' }],
  '/timesheets/approvals': () => [
    { label: 'Timesheets', to: '/timesheets' },
    { label: 'Timesheet approvals' },
  ],

  /* The account group, in the user menu's own order (§4). */
  '/account/security': group('Account', { label: 'Security' }),
  '/account/trackers': group('Account', { label: 'Tracker connections' }),
  '/account/assistants': group('Account', { label: 'AI assistants' }),
  '/account/assistants/new': group(
    'Account',
    { label: 'AI assistants', to: '/account/assistants' },
    { label: 'Connect an assistant' },
  ),
  '/account/access': group('Account', { label: 'Your access' }),
  /* ~~`Help & documentation`~~ → **`User guide`** (§8.1, R6) — the other half of the collision the
     project zone's `Documents` rename fixed. */
  '/help': () => [{ label: 'User guide' }],
  '/help/:sectionId': () => [{ label: 'User guide', to: '/help' }, { label: 'Section' }],

  /*
    The Company zone. `Administration` appears as a crumb for the same three screens the rail keeps
    behind its one surviving folder, so the trail and the rail agree about where they live.
  */
  '/org-chart': group('Company', { label: 'Org chart' }),
  '/directory': group('Company', { label: 'Directory' }),
  '/directory/:userId': group(
    'Company',
    { label: 'Directory', to: '/directory' },
    { label: 'Person' },
  ),
  '/staffing': group('Company', { label: 'Capacity' }),
  '/admin/teams': group('Company', { label: 'Teams & roles' }),
  '/teams/:teamId/board': group(
    'Company',
    { label: 'Teams & roles', to: '/admin/teams' },
    { label: 'Team board' },
  ),
  '/clients': group('Company', { label: 'Clients' }),
  '/clients/:clientId': group('Company', { label: 'Clients', to: '/clients' }, { label: 'Client' }),
  '/billing': group('Company', { label: 'Billing' }),
  '/billing/invoices/:invoiceId': group(
    'Company',
    { label: 'Billing', to: '/billing' },
    { label: 'Invoice' },
  ),
  '/admin/tokens': group('Company', { label: 'Administration' }, { label: 'API tokens' }),
  '/admin/sso': group('Company', { label: 'Administration' }, { label: 'Single sign-on' }),
  '/admin/audit': group('Company', { label: 'Administration' }, { label: 'Company audit' }),

  /*
    An issue by its display key — the ONE detail route whose identifier is in the URL, so the one
    whose leaf crumb is the record rather than the noun. See this file's header.
  */
  '/issues/:key': (q) => [{ label: q.key ?? 'Issue' }],

  /* ── The project frame, in the rail's own order (§1, R8) ───────────────── */

  '/projects/:projectId': (q, ctx) => project(q.projectId, ctx),

  '/projects/:projectId/work': inProject('Work'),
  '/projects/:projectId/work/new': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Work', to: `/projects/${q.projectId}/work` },
    { label: 'New issue' },
  ],
  /*
    ~~The composite board (brief §8's orphan). T8 retires the route; until it does, a screen that is
    reachable needs a trail, and it files under `Work` because that is the entry it duplicates.~~ →
    **the route is gone (021 R9, T8)**, and so is its entry: `routeMeta.test.ts` derives the key set
    from `router.tsx`, so a trail for a route nobody can reach is a stale entry the test will not
    let stand. The board's trail is `Work`'s, which is where its two halves went.
  */

  /* Test cases and its three tabs. A tab is a sibling of its strip's first tab, not a child of it
     — `Suites` files under `Test cases` because that is the rail row it extends (§2.6). */
  '/projects/:projectId/cases': inProject('Test cases'),
  '/projects/:projectId/cases/new': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test cases', to: `/projects/${q.projectId}/cases` },
    { label: 'New case' },
  ],
  '/projects/:projectId/cases/import': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test cases', to: `/projects/${q.projectId}/cases` },
    { label: 'Import a feature file' },
  ],
  '/projects/:projectId/cases/:caseId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test cases', to: `/projects/${q.projectId}/cases` },
    { label: 'Case' },
  ],
  '/projects/:projectId/cases/:caseId/edit': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test cases', to: `/projects/${q.projectId}/cases` },
    { label: 'Case', to: `/projects/${q.projectId}/cases/${q.caseId}` },
    { label: 'Edit' },
  ],
  '/projects/:projectId/cases/:caseId/history': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test cases', to: `/projects/${q.projectId}/cases` },
    { label: 'Case', to: `/projects/${q.projectId}/cases/${q.caseId}` },
    { label: 'History' },
  ],
  '/projects/:projectId/suites': inProject('Suites'),
  '/projects/:projectId/suites/:suiteId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Suites', to: `/projects/${q.projectId}/suites` },
    { label: 'Suite' },
  ],
  '/projects/:projectId/suites/:suiteId/history': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Suites', to: `/projects/${q.projectId}/suites` },
    { label: 'Suite', to: `/projects/${q.projectId}/suites/${q.suiteId}` },
    { label: 'History' },
  ],
  /* ~~`Generated cases`~~ → **`Proposals`** (§8.1, R4). The ROUTE keeps `generated-cases`. */
  '/projects/:projectId/generated-cases': inProject('Proposals'),
  '/projects/:projectId/generated-cases/:proposalId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Proposals', to: `/projects/${q.projectId}/generated-cases` },
    { label: 'Proposal' },
  ],

  '/projects/:projectId/plans': inProject('Test plans'),
  '/projects/:projectId/plans/:planId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test plans', to: `/projects/${q.projectId}/plans` },
    { label: 'Plan' },
  ],

  /* Test runs and its three tabs. */
  '/projects/:projectId/runs': inProject('Test runs'),
  '/projects/:projectId/runs/:runId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test runs', to: `/projects/${q.projectId}/runs` },
    { label: 'Run' },
  ],
  '/projects/:projectId/runs/:runId/executions/:executionId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test runs', to: `/projects/${q.projectId}/runs` },
    { label: 'Run', to: `/projects/${q.projectId}/runs/${q.runId}` },
    { label: 'Execution' },
  ],
  '/projects/:projectId/run-schedules': inProject('Schedules'),
  /* ~~`CI ingestion`~~ → **`CI results`** (§8.1) — the system's verb becomes the reader's noun. */
  '/projects/:projectId/ingestions': inProject('CI results'),
  '/projects/:projectId/ingestions/:jobId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'CI results', to: `/projects/${q.projectId}/ingestions` },
    { label: 'Job' },
  ],

  '/projects/:projectId/releases': inProject('Releases'),
  '/projects/:projectId/releases/new': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Releases', to: `/projects/${q.projectId}/releases` },
    { label: 'New release' },
  ],
  '/projects/:projectId/releases/:releaseId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Releases', to: `/projects/${q.projectId}/releases` },
    { label: 'Release' },
  ],

  /* ~~`Documentation`~~ → **`Documents`** (§8.1), and its two tabs. */
  '/projects/:projectId/docs': inProject('Documents'),
  '/projects/:projectId/docs/:pageId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Documents', to: `/projects/${q.projectId}/docs` },
    { label: 'Page' },
  ],
  '/projects/:projectId/docs/:pageId/edit': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Documents', to: `/projects/${q.projectId}/docs` },
    { label: 'Page', to: `/projects/${q.projectId}/docs/${q.pageId}` },
    { label: 'Edit' },
  ],
  '/projects/:projectId/artifacts': inProject('Artifacts'),

  '/projects/:projectId/analytics': inProject('Analytics'),
  /* ~~`Reports`~~ → **`Test reports`** (§8.1, R5). */
  '/projects/:projectId/reports': inProject('Test reports'),
  '/projects/:projectId/reports/jobs/:jobId': (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Test reports', to: `/projects/${q.projectId}/reports` },
    { label: 'Report' },
  ],

  /* ~~`Staffing`~~ → **`Team`** (§8.1). The route does not move. */
  '/projects/:projectId/staffing': inProject('Team'),

  /*
    Settings — the eleven rail rows that became one landing plus a subnav (§5). All eleven file
    under `Settings` in the trail even though three of them are not under `/settings/` in the URL,
    which is the point of §5's "no route moves": the grouping is a navigation fact, and the trail
    is where a navigation fact belongs.
  */
  '/projects/:projectId/settings': inProject('Settings'),
  '/projects/:projectId/settings/general': settingsSection('General'),
  '/projects/:projectId/settings/workflow': settingsSection('Workflow'),
  '/projects/:projectId/settings/boards': settingsSection('Boards'),
  '/projects/:projectId/settings/repositories': settingsSection('Code repositories'),
  '/projects/:projectId/settings/classification': settingsSection('Classification'),
  '/projects/:projectId/settings/review': settingsSection('Review policy'),
  '/projects/:projectId/settings/notifications': settingsSection('Notifications'),
  '/projects/:projectId/settings/trackers': settingsSection('Issue tracker'),
  '/projects/:projectId/imports': settingsSection('Imports'),
  '/projects/:projectId/imports/new': settingsSection('Imports', { label: 'Start an import' }),
  '/projects/:projectId/imports/:importRunId': settingsSection('Imports', { label: 'Import' }),
  '/projects/:projectId/access': settingsSection('Access & roles'),
  '/projects/:projectId/audit': settingsSection('Project audit'),
};

/** `Projects / <KEY> / Settings / <section>`, with `Settings` linking to the landing (§5). */
function settingsSection(label: string, ...rest: Crumb[]): CrumbsOf {
  return (q, ctx) => [
    ...project(q.projectId, ctx),
    { label: 'Settings', to: `/projects/${q.projectId}/settings` },
    rest.length > 0
      ? { label, to: sectionHref(label, q.projectId) }
      : { label },
    ...rest,
  ];
}

/**
 * Where a settings section's own crumb points when it is not the leaf.
 *
 * Only `Imports` needs it today — it is the one section with pages under it — and it is the one
 * whose route is not under `/settings/`, so the href cannot be derived from the label.
 */
function sectionHref(label: string, projectId: string | undefined): string | undefined {
  return label === 'Imports' ? `/projects/${projectId}/imports` : undefined;
}

/**
 * The trail for a location, or `[]` where a route declares none.
 *
 * `end: true` on every pattern, matching `<Route path>`'s own exactness: without it
 * `/projects/:projectId` would match every screen in the project and every trail would be two
 * crumbs long.
 *
 * **More than one pattern can match, and declaration order must not decide which wins.**
 * `/projects/x/cases/new` matches both `…/cases/new` and `…/cases/:caseId` — a static segment and
 * a dynamic one are both legal readings of `new` — so a trail read in map order would say
 * `Test cases / Case` for the New case screen the day somebody sorted this object alphabetically.
 * The router does not have that problem because it ranks its matches; this ranks the same way it
 * does, on the only axis that matters here: **the fewest dynamic segments wins**, because a
 * pattern that spells a segment out is a more specific claim about the URL than one that captures
 * it. Ties are impossible — two patterns with the same segments and the same dynamic positions are
 * the same pattern — so no second criterion is needed.
 */
export function crumbsFor(pathname: string, ctx: CrumbContext): Crumb[] {
  let best: { crumbs: CrumbsOf; params: Record<string, string | undefined>; dynamic: number } | null = null;

  for (const [pattern, crumbs] of Object.entries(ROUTE_META)) {
    const match = matchPath({ path: pattern, end: true }, pathname);
    if (!match) continue;
    const dynamic = Object.keys(match.params).length;
    if (best === null || dynamic < best.dynamic) best = { crumbs, params: match.params, dynamic };
  }

  return best === null ? [] : best.crumbs(best.params, ctx);
}
