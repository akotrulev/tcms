import {
  Building2,
  ChartColumn,
  ClipboardList,
  FileText,
  Folder,
  Kanban,
  Key,
  LayoutDashboard,
  LayoutGrid,
  ListChecks,
  LogIn,
  Network,
  Receipt,
  Rocket,
  ScrollText,
  Settings,
  Shield,
  TrendingUp,
  UserCheck,
  UserRoundSearch,
  Users,
  Users2,
  type LucideIcon,
} from 'lucide-react';
import type { PermissionKey } from '../api/types';

/**
 * The shell's places, the tab strips and the settings sections — as **data**, in one file (021 D-h).
 *
 * The zones are now drawn as launcher sections and project tabs (023 D1): `You` and `Company` are
 * the launcher's columns, the project zone is the tab row plus More, and the rail-specific
 * arguments below are ~~about a rail~~ → superseded (023 D1) and kept for the gate reasoning they
 * also carry. `placement` and `hint` are the only fields 023 added; the zones, gates and `why`
 * clauses are unchanged, so the palette's Places and Your access still read them as they were.
 *
 * `TopBar` renders it, `AppShell.test.tsx` asserts against it, and T7's palette generates its
 * "places" index from it. A label therefore lives in exactly one place, which is what the twelve
 * §8.1 renames needed: before this, `All projects` was a string in a component and `Staffing` was
 * the same string twice, at two scopes, meaning two different screens.
 *
 * ## What this file is a transcription of
 *
 * `docs/mockups/ia-redesign/assets/shell-v2.js`'s `NAV`, `TABS` and `SETTINGS` constants, which
 * are the drawn form of `docs/specs/2026-09-06-navigation-ia-redesign-proposal.md` §1, §4 and §5.
 * Labels, order and gates are the proposal's, verbatim. Three shapes changed against plan 021's
 * §T6 sketch and each is stated here rather than left for a reader to diff:
 *
 *   1. **`icon` is a component, not an element.** The sketch wrote `<UserCheck className="size-4"/>`,
 *      which would make this a `.tsx` file holding markup. A `LucideIcon` reference keeps the file
 *      free of rendering decisions — the size class belongs to the rail, not to the model — and
 *      keeps it importable by the palette's index, which wants labels and routes and no JSX.
 *   2. **`gate` carries its scope.** `AppShell` reads two different endpoints for a verdict
 *      (`useCan(projectId)` and `useCanInCompany()`), and a bare permission key cannot say which
 *      one answers it. Every gate below happens to be COMPANY scope and every gate in
 *      {@link SETTINGS_SECTIONS} happens to be PROJECT scope — but "happens to be" is exactly the
 *      fact that stops being true the day somebody adds a row, and a gate asked at the wrong scope
 *      fails silently by hiding an entry from the person who holds the key.
 *   3. **`to` is a function of the project id**, because eleven of the twenty rows are inside the
 *      project frame and the other nine are not. A row that ignores its argument is a row outside
 *      the frame, which is a property worth being able to read off the data.
 *
 * ## The order is usage order, and that is the change (§1, R8)
 *
 * ~~`Repository` · `Execution` · `Authoring` · `Project` — four subject folders inside the project
 * frame.~~ → **superseded (021 R8).** Subject headings inside a scope zone were the confusion the
 * brief measured: they filed the most-used screens one click down and named a distinction nobody
 * working the board makes. The project zone is now FLAT and ordered by how often a screen is
 * opened — `Work` first because the day starts and ends on the board, `Overview` **seventh**
 * because it is the project's dashboard, read at stand-up rather than worked in.
 *
 * One folder survives anywhere in the rail, `Administration`, and it survives for a reason that
 * does not generalise: all three of its children are gated, so a closed folder is where their
 * absence is least conspicuous.
 */

/**
 * A permission key **and the scope it is answered at** — see the header, point 2.
 *
 * There is no third arm. An entry with no gate is listed for everybody, which is this rail's
 * standing rule (§31, disable-don't-hide); a gate here is one of the nine recorded exceptions,
 * each argued at the entry that carries it.
 */
export interface NavGate {
  key: PermissionKey;
  scope: 'project' | 'company';
  /**
   * Why this entry is **hidden** rather than shown-and-refused — one clause, no full stop.
   *
   * Each of the nine is argued at the entry that carries it, and every one of those arguments used
   * to live only in a comment: true, recorded, and invisible to the person the decision was made
   * about. `/account/access` (021 §4, T7) is where they are shown, so the clause is on the gate
   * beside the label rather than in a second table keyed by label — which is the table a rename
   * would silently orphan.
   *
   * It says why the ENTRY is absent, never what the screen behind it contains: a reason that
   * disclosed the thing the permission protects would defeat the hiding it explains.
   */
  why: string;
}

export interface NavItem {
  label: string;
  /** `projectId` is `''` when no project is resolved; a row outside the frame ignores it. */
  to: (projectId: string) => string;
  icon: LucideIcon;
  gate?: NavGate;
  /** Marks the item active for descendant routes too, not just an exact match. */
  end?: boolean;
  /** Project zone only: a top-bar tab, or an entry under More (023 §3). */
  placement?: 'tab' | 'more';
  /** The launcher's second line for the entry (023 §3). */
  hint?: string;
}

export interface NavFolder {
  label: string;
  icon: LucideIcon;
  items: NavItem[];
}

export type NavEntry = NavItem | NavFolder;

export function isFolder(entry: NavEntry): entry is NavFolder {
  return 'items' in entry;
}

export interface NavZone {
  id: 'you' | 'project' | 'company';
  label: string;
  /**
   * The Project zone's header is the **project switcher itself** (R1), not a text label.
   *
   * ~~The switcher lives in the header, where it states the frame at full width.~~ →
   * **superseded (021 R1):** the frame is still stated at full width by the breadcrumb's first
   * segment, and the switcher is worth more sitting on top of the eleven rows it changes than
   * beside a search field that deliberately escapes the frame. Collapsed, it is the project key
   * chip, which opens the same control.
   */
  switcher?: boolean;
  /** The whole zone is drawn only for a caller holding this. */
  needs?: NavGate;
  entries: NavEntry[];
}

export const NAV: NavZone[] = [
  {
    id: 'you',
    label: 'You',
    entries: [
      /*
        The personal board (§6l). It spans every project the caller can see, which is why it is in
        a zone of its own above the switcher rather than under it — listing it below would say that
        switching projects changes it.

        It is also the only row that renders when NO project is resolved, which is exactly the
        state a member holding no project grants at all is in.
      */
      { label: 'My work', to: () => '/my-work', icon: UserCheck, end: true, hint: 'Assigned to you, every project' },
      /*
        The picker. ~~`All projects`~~ → **`Projects`** (§8.1) — the mockup's word, and the one the
        breadcrumb's first segment uses, so the rail and the trail now agree.

        `end` states the intent — current on `/` and nowhere else. It is belt and braces rather
        than load-bearing: `NavLink` already refuses a prefix match for `to="/"` because it
        requires the next character of the location to be `/` and there is none. Kept because
        relying on a special case in the router to make a nav item behave is a worse reason than
        saying what you meant.
      */
      { label: 'Projects', to: () => '/', icon: LayoutGrid, end: true, hint: 'Every project you can open' },
      /*
        ~~`Timesheets`, third in this block, because a period is personal and spans projects.~~ →
        **superseded (021 R11): it left the rail and is in the user menu**, with `Timesheet
        approvals` under it. The premise survives and the conclusion does not: the rail's rule is
        *a place in the product scoped by the project switcher or by the company*, and "personal
        and spanning projects" fails that test rather than qualifying for a zone of its own. The
        user menu's rule is the person's own things, which a fortnight of hours is.

        ~~`Billing`, fourth, gated on `billing:read`.~~ → **superseded (021 §1): it is a Company
        zone row**, which is what it always was — an invoice is the company's, and the ungrouped
        block only existed because there was no company zone to put it in. The gate is unchanged.
      */
    ],
  },
  {
    id: 'project',
    label: 'Project',
    switcher: true,
    /*
      ~~`needs: project:read`~~ — **the zone is gated on having a project, not on a permission**,
      and the two are the same answer arrived at without a round trip.

      §1 draws the zone behind `project:read` so that Finance and Account Manager, who hold none
      deliberately (at company scope either key would open every project in the company, present
      and future), do not get an empty project frame. But a caller who holds no `project:read`
      anywhere gets **nothing back from `GET /projects`**, so `useActiveProject` resolves no
      project and `AppShell` draws no project zone — the same outcome, from a read the shell makes
      anyway.

      Asking the permission instead would cost the thing every gate in this file is careful about:
      `can()` fails closed while pending, and `/me/permissions` for a project cannot even be
      REQUESTED until `/projects` has said which project is active. So the zone would be absent on
      first paint and appear a round trip later — eleven rows arriving after the fact, on every
      cold navigation, for every member of the company. A gate that hides one row while it waits
      is a flicker; one that hides the whole frame is the rail rebuilding itself under the cursor.
    */
    entries: [
      /*
        First, because the boards are where the day starts and ends. One entry and seven views
        (011 D34, extended to three boards by 021 §2.4): the views switch on `?view=`/`?board=` so
        the filter travels between them in the query string rather than in a store the URL cannot
        restore.
      */
      { label: 'Work', to: (p) => `/projects/${p}/work`, icon: Kanban, placement: 'tab' },
      /* Three tabs — Cases · Suites · Proposals (see {@link TABS}). */
      { label: 'Test cases', to: (p) => `/projects/${p}/cases`, icon: Folder, placement: 'tab' },
      { label: 'Test plans', to: (p) => `/projects/${p}/plans`, icon: ClipboardList, placement: 'tab' },
      /* Three tabs — Runs · Schedules · CI results. */
      { label: 'Test runs', to: (p) => `/projects/${p}/runs`, icon: ListChecks, placement: 'tab' },
      { label: 'Releases', to: (p) => `/projects/${p}/releases`, icon: Rocket, placement: 'tab' },
      /*
        ~~`Documentation`~~ → **`Documents`** (§8.1). The old label collided with the user menu's
        `Help & documentation`, so one shell named two destinations the same thing and a reader had
        to guess which one held the CLI instructions. Both ends of that collision move: this is
        `Documents`, and the help entry is `User guide` (R6).

        Two tabs — Pages · Artifacts.
      */
      { label: 'Documents', to: (p) => `/projects/${p}/docs`, icon: FileText, placement: 'tab' },
      /*
        **Seventh, and that is the decision** (R8). ~~First, because it is the project's front
        door.~~ → **superseded:** it is the project's *dashboard* — read at stand-up, not the place
        work is done — and the six rows above it are where the day is spent. A screen's position in
        this list is a claim about how often it is opened, and this one was wrong.
      */
      { label: 'Overview', to: (p) => `/projects/${p}`, icon: LayoutDashboard, end: true, placement: 'more' },
      /*
        Delivery analytics (020 M9). ~~Filed under `Project` where `Reports` is under `Execution`,
        because what a report is *of* is execution results and what this is *of* is whether the
        project's work is converging on a release.~~ → **superseded (021 R8): there are no subject
        folders left to file it under.** The distinction the split expressed was real and is now
        carried by the two labels — `Analytics` and `Test reports` — instead of by a heading, which
        is what R5 chose over merging them (020 D-l, closed by naming).
      */
      { label: 'Analytics', to: (p) => `/projects/${p}/analytics`, icon: TrendingUp, placement: 'more' },
      /*
        ~~`Reports`~~ → **`Test reports`** (§8.1, R5). Two entries were kept and one was renamed:
        the pair reads "reports of what?" beside `Analytics` otherwise, which is 020 D-l's recorded
        divergence closed by naming rather than by merging two screens that answer different
        questions.
      */
      { label: 'Test reports', to: (p) => `/projects/${p}/reports`, icon: ChartColumn, placement: 'more' },
      /*
        ~~`Staffing`, under `Execution`.~~ → **`Team`** (§8.1). The rail carried the word twice, at
        two scopes, for two screens — this one and the company's — which is the duplicate-label
        defect the brief found. The route does not move: it is still `…/staffing`.
      */
      { label: 'Team', to: (p) => `/projects/${p}/staffing`, icon: Users2, placement: 'more' },
      /*
        **One row where eleven stood** (§5). `General`, `Workflow`, `Boards`, `Repositories`,
        `Imports`, `Classification`, `Review`, `Notifications`, `Issue tracker`, `Access & roles`
        and `Audit trail` were eleven siblings in a `Project` folder; they are now
        {@link SETTINGS_SECTIONS}, rendered as a landing page and a subnav. **No route moves** —
        every pasted `/settings/*`, `/access`, `/imports` and `/audit` link still lands where it
        always did.
      */
      { label: 'Settings', to: (p) => `/projects/${p}/settings`, icon: Settings, end: true, placement: 'more' },
    ],
  },
  {
    id: 'company',
    label: 'Company',
    entries: [
      /*
        The org chart (§6k), first because it is the one screen here every member can use — and it
        is gated on nothing at all, so the entry is not a promise the page has to walk back.
      */
      { label: 'Org chart', to: () => '/org-chart', icon: Network, hint: 'Who reports to whom' },
      { label: 'Directory', to: () => '/directory', icon: UserRoundSearch, hint: 'Find a colleague' },
      /*
        ~~`Staffing`~~ → **`Capacity`** (§8.1), the other half of the duplicate-label repair. This
        is the company-wide question — who is free next month, across every project a person is on
        — where `Team` above is *who is on this project*. The route is still `/staffing`.

        Ungated, and the argument has a second half worth keeping: both company screens cost
        `staffing:directory`, which a Project Manager does not hold (017 D-g), so a gate would hide
        them from one of the roles most likely to look. The refusal they get instead names the
        screen that answers *their* question — their own project's `Team` — and that refusal is
        only reachable if the entry is shown.
      */
      { label: 'Capacity', to: () => '/staffing', icon: Users2, hint: 'Who is free, across projects' },
      { label: 'Teams & roles', to: () => '/admin/teams', icon: Users, hint: 'Teams and what they hold' },
      /*
        GATED (018 Mc). `ApiTokenService`'s argument: the read is **refused rather than narrowed**,
        because an empty array would be a claim about the company told to somebody who may not ask
        — and an entry into that refusal is the same claim one click earlier. A Test Engineer has
        no business learning from a sidebar how many customers their employer has.
      */
      {
        label: 'Clients',
        hint: 'Companies you deliver for',
        to: () => '/clients',
        icon: Building2,
        gate: {
          key: 'client:read',
          scope: 'company',
          why: 'a sidebar should not teach you how many customers your employer has',
        },
      },
      /*
        GATED (019 D-o), and sharpest of the nine: the whole screen behind this entry exists only
        to say no to a caller without the key. `billing.html` says it in a callout on the screen
        itself — *"this entry disappears from the sidebar entirely — not greyed out, not empty. It
        is not their screen."* The `/billing` **route** stays open to everybody and refuses with a
        sentence naming who holds the key; this rail simply does not offer the trip.
      */
      {
        label: 'Billing',
        hint: 'Invoices and rates',
        to: () => '/billing',
        icon: Receipt,
        gate: {
          key: 'billing:read',
          scope: 'company',
          why: 'what a colleague costs is not advertised to the colleague',
        },
      },
      /*
        **The one folder left anywhere in the rail** (§1), and it survives on a test the four
        withdrawn subject folders failed: all three children are gated, so for most callers the
        folder is not drawn at all, and for the rest a closed folder is where the absence of the
        others is least conspicuous. A folder whose every child is hidden is itself hidden.
      */
      {
        label: 'Administration',
        icon: Shield,
        items: [
          /*
            ~~Shown to everyone — hiding it would teach a Test Lead that this product has no CI
            credentials.~~ → **superseded 2026-08-28 (005 C2): gated on `token:issue`.** The read
            is refused rather than narrowed, so the page has no read-only view to fall back on.
          */
          {
            label: 'API tokens',
            hint: 'CI and agent credentials',
            to: () => '/admin/tokens',
            icon: Key,
            gate: {
              key: 'token:issue',
              scope: 'company',
              why: 'the read is refused, not narrowed — an empty list would still be a claim',
            },
          },
          /*
            The strongest of the three: a readable SSO page would disclose issuer URLs, client ids
            and claimed email domains — reconnaissance for whoever is trying to get in — which is
            why no read-only view was ever going to exist here.
          */
          {
            label: 'Single sign-on',
            hint: 'Identity providers',
            to: () => '/admin/sso',
            icon: LogIn,
            gate: {
              key: 'company:idp',
              scope: 'company',
              why: 'a readable page would disclose issuer URLs, client ids and claimed domains',
            },
          },
          /*
            ~~`Audit trail`~~ → **`Company audit`** (§8.1). The rail carried that label twice, at
            two scopes, exactly as `Staffing` did; the project half is a settings section now and
            is `Project audit`. The route does not move.

            HIDDEN rather than shown-and-refused, at the product owner's direction (§242): the
            screen's whole subject is who did what, so advertising it to somebody who may not read
            it invites the request the permission exists to refuse. The pair must agree — if this
            is ever revisited, revisit `Project audit` in {@link SETTINGS_SECTIONS} with it.
          */
          {
            label: 'Company audit',
            hint: 'Who changed what',
            to: () => '/admin/audit',
            icon: ScrollText,
            gate: {
              key: 'audit:view',
              scope: 'company',
              why:
                'advertising an audit trail to somebody who may not read it invites the request it '
                + 'exists to refuse',
            },
          },
        ],
      },
    ],
  },
];

/**
 * The project settings sections — the eleven rail rows that became one (§5).
 *
 * Rendered twice from this one list: as the landing page's cards, each carrying **one live fact**,
 * and as the subnav row every settings subpage now shows. Both filter by the same predicate the
 * rail applied to the same entries — *can the page be read?* — so a section a caller cannot open
 * is absent from both, and the nine recorded hide-don't-disable exceptions are unchanged.
 *
 * **No route moves.** `Imports`, `Access & roles` and `Project audit` are not under `/settings/`
 * and are not moved there: three of the eleven predate the prefix, links to them exist, and the
 * grouping this list expresses is a navigation fact rather than a URL one.
 *
 * Every gate here is PROJECT scope — see {@link NavGate} for why that is stated rather than
 * assumed.
 */
export interface SettingsSection {
  label: string;
  to: (projectId: string) => string;
  icon: LucideIcon;
  gate?: NavGate;
}

export const SETTINGS_SECTIONS: readonly SettingsSection[] = [
  { label: 'General', to: (p) => `/projects/${p}/settings/general`, icon: Settings },
  { label: 'Workflow', to: (p) => `/projects/${p}/settings/workflow`, icon: Kanban },
  { label: 'Boards', to: (p) => `/projects/${p}/settings/boards`, icon: LayoutDashboard },
  /* ~~`Repositories`~~ → **`Code repositories`** (§8.1) — it says what they are. */
  {
    label: 'Code repositories',
    to: (p) => `/projects/${p}/settings/repositories`,
    icon: Folder,
  },
  { label: 'Classification', to: (p) => `/projects/${p}/settings/classification`, icon: ListChecks },
  /* ~~`Review`~~ → **`Review policy`** (§8.1) — the page title already said it. */
  { label: 'Review policy', to: (p) => `/projects/${p}/settings/review`, icon: ClipboardList },
  /*
    GATED on `issue:import` (014 D-e). Every operation in the family costs it — the report names
    the source site, the account that connected to it and the person who ran the migration — so
    there is no read-only view and the card would lead to a screen whose whole content is a
    refusal. `/imports`, not `/settings/imports`.
  */
  {
    label: 'Imports',
    to: (p) => `/projects/${p}/imports`,
    icon: FileText,
    gate: {
      key: 'issue:import',
      scope: 'project',
      why: 'the report names the source site, the connecting account and the operator',
    },
  },
  {
    label: 'Notifications',
    to: (p) => `/projects/${p}/settings/notifications`,
    icon: ChartColumn,
    gate: {
      key: 'integration:manage',
      scope: 'project',
      why: 'the reads are refused, so the page would be entirely refusal',
    },
  },
  {
    label: 'Issue tracker',
    to: (p) => `/projects/${p}/settings/trackers`,
    icon: Network,
    gate: {
      key: 'integration:manage',
      scope: 'project',
      why: 'the same module, the same key and the same argument as Notifications',
    },
  },
  /*
    Ungated, and it is the counter-example the gated three are measured against: 005 Q2 relaxed
    `GET /projects/{id}/members` to `project:read`, so the page renders read-only for a caller
    without the write key. **The test is whether the page can be read, not whether it can be
    written.** `/access`, not `/settings/access`.
  */
  { label: 'Access & roles', to: (p) => `/projects/${p}/access`, icon: Users },
  /*
    ~~`Audit trail`~~ → **`Project audit`** (§8.1). Gated on `project:audit` and HIDDEN without it
    — the pair to `Company audit` in {@link NAV}, and the same deliberate exception to
    disable-don't-hide. `/audit`, not `/settings/audit`.
  */
  {
    label: 'Project audit',
    to: (p) => `/projects/${p}/audit`,
    icon: ScrollText,
    gate: {
      key: 'project:audit',
      scope: 'project',
      why: 'the pair to Company audit — the two must agree',
    },
  },
];

/**
 * The tail that is **not** in the rail (§1, §2.6) — five screens that were rail rows and are now a
 * segmented strip on the screen they extend.
 *
 * A tab is not a rail row, and this is where that is stated once. Each tab keeps **its own URL**,
 * so every pasted link still lands and the active tab is read from the route rather than stored —
 * which is the property that makes this a strip of links and not a `<Tabs>` with state.
 *
 * The pattern is the one Work already ships for its views (`features/tracker/WorkTablePage.tsx`),
 * and `components/TabStrip.tsx` is the single component all three strips render through.
 *
 * | Was a rail row | Now a tab on | Route (unchanged) |
 * |---|---|---|
 * | Suites *(had none — brief §8.6)* | Test cases | `…/suites` |
 * | Generated cases | Test cases | `…/generated-cases` |
 * | Run schedules *(an orphan — no entry, no inbound link)* | Test runs | `…/run-schedules` |
 * | CI ingestion | Test runs | `…/ingestions` |
 * | Artifacts | Documents | `…/artifacts` |
 */
export interface TabEntry {
  label: string;
  to: (projectId: string) => string;
  /** Set where a sibling tab's path is a prefix of this one, or where only the index matches. */
  end?: boolean;
}

export const TABS: Record<'cases' | 'runs' | 'docs', readonly TabEntry[]> = {
  cases: [
    { label: 'Cases', to: (p) => `/projects/${p}/cases` },
    { label: 'Suites', to: (p) => `/projects/${p}/suites` },
    /*
      ~~`Generated cases`~~ → **`Proposals`** (§8.1, R4). §138 gave the queue its own rail heading
      so a reader could not mistake it for the repository; the tab label and the screen's own
      banner make that same denial one level cheaper — *these are not in the repository yet* — and
      the heading cost a whole rail row to say it.

      The **route** keeps `generated-cases`, and the page file keeps its name.
    */
    { label: 'Proposals', to: (p) => `/projects/${p}/generated-cases` },
  ],
  runs: [
    { label: 'Runs', to: (p) => `/projects/${p}/runs` },
    /*
      The orphan (brief §8): a shipped feature with no rail entry and no inbound link from any
      screen, reachable only by typing the URL. `/run-schedules`, NOT `/runs/schedules` — a
      schedule is not a run and is not nested under one; it outlives every run it makes, and the
      runs outlive it.
    */
    { label: 'Schedules', to: (p) => `/projects/${p}/run-schedules` },
    /* ~~`CI ingestion`~~ → **`CI results`** (§8.1) — the system's verb becomes the reader's noun. */
    { label: 'CI results', to: (p) => `/projects/${p}/ingestions` },
  ],
  docs: [
    { label: 'Pages', to: (p) => `/projects/${p}/docs` },
    { label: 'Artifacts', to: (p) => `/projects/${p}/artifacts` },
  ],
};
