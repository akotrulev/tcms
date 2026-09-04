import { useColorScheme } from './useColorScheme';
import {
  Bell,
  Bot,
  Bug,
  ChartColumn,
  ChevronLeft,
  ChevronRight,
  CloudUpload,
  ClipboardList,
  FileText,
  Folder,
  FolderGit2,
  GitBranch,
  HelpCircle,
  History,
  Import,
  Kanban,
  Key,
  LayoutDashboard,
  LayoutGrid,
  ListChecks,
  LogIn,
  LogOut,
  Menu as MenuIcon,
  MessageSquareText,
  Moon,
  Network,
  Package,
  Rocket,
  Search,
  Settings,
  Shield,
  Sparkles,
  Sun,
  Tag,
  Timer,
  UserRoundSearch,
  Users2,
  UserCheck,
  Users,
} from 'lucide-react';
import { useState, type ReactNode } from 'react';
import {
  Link as RouterLink,
  NavLink,
  Outlet,
  useMatch,
  useNavigate,
  useParams,
} from 'react-router-dom';
import { useProjects } from '../api/queries';
import { useCan, useCanInCompany } from '../auth/useCan';
import { useRouteFocus } from '../a11y/useRouteFocus';
import { Identifier } from '../components/Identifier';
import { RouteErrorBoundary } from '../components/ErrorBoundaries';
import { SkipToContentLink } from '../components/SkipToContentLink';
import { useAuth } from '../auth/useAuth';
import { safeStorage } from '../lib/safeStorage';
import { TopStripProvider, TopStripTarget } from './topStripSlot';
import { cn } from '../lib/cn';
import {
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '../components/ui';
import type { Project } from '../api/types';

/**
 * The persistent application shell: project rail on the left, content on the right.
 *
 * It is a **layout**, not a template — it mounts once and stays mounted, so scroll
 * position, the open drawer, and anything else it holds survive navigation between pages.
 * Only `<Outlet/>` changes.
 *
 * Responsive strategy: the rail is a CSS visibility swap, not a JS breakpoint branch. The
 * permanent rail is hidden below `md` with `display`, and a temporary drawer takes over —
 * so there's no width-dependent JS deciding layout, and both variants carry their own
 * testid (`Nav.Rail` / `Nav.Drawer`) so a responsive test can assert which one is showing.
 * The hamburger is a small-screen affordance, never the desktop default.
 */

const RAIL_WIDTH = 232;

/**
 * Wide enough for a 40px touch target plus the rail's own padding, and narrow enough that the
 * collapse is worth doing. The rail stays in the flex flow at both widths, so the content area
 * reflows on its own — there is no width-dependent JS deciding the layout, which is the same
 * rule the `md` visibility swap above follows.
 */
const RAIL_WIDTH_COLLAPSED = 64;

/**
 * The collapsed/expanded preference, remembered across sessions.
 *
 * A collapse that resets on every reload is not a preference, it is a gesture — and this one is
 * made by people who want the extra 168px on every screen for the rest of the day. Written through
 * `safeStorage` rather than `localStorage`, so Safari private mode and blocked third-party
 * contexts degrade to in-memory instead of throwing inside a render.
 */
const RAIL_COLLAPSED_KEY = 'tcms.nav.railCollapsed';

/**
 * The routes where the rail collapses because of what the screen *is*, not because of what the
 * reader chose — `docs/specs/2026-08-28-design-language.md` §4.
 *
 * Both are surfaces where the reader is following a procedure at a measure, and the rail is
 * navigation they are demonstrably not doing: the case document editor (the case redesign's
 * decision — the document wants the width) and the execution runner (§4's addition, for the same
 * reason). §9.2 listed "the shell reads the route" as an open problem, because it did not; this
 * is the line that closes it.
 *
 * **Three patterns, not the two §4 counts**, because the case document editor is reachable at two
 * routes: `/cases/new` is the same editor with no case behind it yet, and it is exactly what the
 * origin render draws as frame B. Collapsing on `edit` but not on `new` would put the rail at two
 * widths either side of the first save.
 *
 * This forces the collapse; it does not overwrite the stored preference. Someone who has chosen
 * the collapsed rail everywhere still has it everywhere, and finds it unchanged when they leave.
 */
const COLLAPSED_RAIL_ROUTES = [
  '/projects/:projectId/cases/new',
  '/projects/:projectId/cases/:caseId/edit',
  '/projects/:projectId/runs/:runId/executions/:executionId',
] as const;

interface NavItem {
  label: string;
  to: string;
  icon: ReactNode;
  /** Marks the item active for descendant routes too, not just an exact match. */
  end?: boolean;
}

/**
 * The one rail item that is **not** about the project in the switcher (§83).
 *
 * It is rendered *above* the switcher rather than in a group below it, and the placement is the
 * whole argument: everything under the switcher is scoped by it, and §6l's My Work board spans
 * every project the caller can see. Listing it below would tell the reader that switching projects
 * changes it — the same misstatement the `Teams & roles` comment records for the Company group, one
 * level further out, and this one cannot be fixed by a heading because there is no project frame to
 * put a heading inside.
 *
 * It is also the only rail item that renders when **no project is resolved yet** — the rest of the
 * rail is empty until `useProjects` answers, and a personal board is exactly what a member holding
 * no project grants at all has.
 */
const MY_WORK: NavItem = {
  label: 'My work',
  to: '/my-work',
  icon: <UserCheck className="size-4" />,
  end: true,
};

/**
 * The project picker (`/`), and the second rail item outside the project frame.
 *
 * It sits in the ungrouped block for exactly the reason `MY_WORK` states — everything below the
 * switcher is scoped *by* the switcher, and this screen is the one that lists what the switcher
 * chooses between — but it is placed **last in that block, directly above the switcher**, because
 * the two are one thought: "all of them" and "this one". `MY_WORK` above it is personal and
 * belongs to neither.
 *
 * Until this existed the picker had no affordance at all once you were inside a project. It is the
 * route the app lands on after sign-in and the fallback when a URL names a project the caller
 * cannot see, so it was reachable only by editing the address bar — the one screen in the app that
 * every session starts on and no screen linked to. The wordmark above links here too, which is the
 * convention every reader already has; this entry is the discoverable, labelled, active-marked one.
 *
 * `end` states the intent — this item is current on `/` and nowhere else — but it is **belt and
 * braces rather than load-bearing**, which is worth recording because the opposite is the obvious
 * assumption: `/` is a prefix of every route in the app, so a prefix match would light this item up
 * on all of them. `NavLink` already refuses that specific case (it requires the next character of
 * the location to be `/`, and for `to="/"` there is none), so removing `end` here changes nothing
 * and the test below does not redden. Kept because relying on a special case in the router to make
 * a nav item behave is a worse reason than saying what you meant.
 */
const ALL_PROJECTS: NavItem = {
  label: 'All projects',
  to: '/',
  icon: <LayoutGrid className="size-4" />,
  end: true,
};

/**
 * The third rail item outside the project frame (016 M6), and the one whose placement is an
 * argument rather than a convenience.
 *
 * A timesheet period is per person and per fortnight and **spans projects** (016 D-i) — the
 * mockup's own example carries two clients' projects and a project-less meeting in one submission.
 * So listing it under the switcher would say the opposite of what a period is: that switching
 * projects changes which hours you are looking at. It belongs with `MY_WORK` above it, which is
 * personal for the same reason, and it is placed *after* that item and *before* `ALL_PROJECTS`
 * because the two personal screens read as a pair.
 *
 * <h2>No waiting-count badge, and that is a measured omission rather than an oversight</h2>
 *
 * `timesheet-approvals.html`'s rail shows a `4` beside this entry behind the mockup's
 * `SEES_APPROVALS` role gate, and the plan asked for it. There is no honest way to draw it here.
 * The count comes from `GET /timesheets/approvals`, which **403s** for any caller without
 * `time:approve`, and `time:approve` is a PROJECT-scope grant — so there is no company-wide
 * question this shell could ask first. `useCanInCompany` answers about COMPANY-scope grants and
 * would say no for the Project Managers the badge is for, and `useCan(activeProjectId)` would key a
 * cross-project count on whichever project happens to be in the switcher, which is the very
 * statement the placement above exists to avoid.
 *
 * Firing the read unconditionally is the option that was rejected: it would raise a refused-request
 * notification on every page load for the majority of this company, to draw a number on a rail.
 * The count is therefore on the approvals screen's own subtitle, where the data already is, and the
 * timesheet screen carries a labelled link to it. Revisit this if the contract ever grows a
 * company-wide "where may I approve" read; do not revisit it by making the rail guess.
 */
const TIMESHEETS: NavItem = {
  label: 'Timesheets',
  to: '/timesheets',
  icon: <Timer className="size-4" />,
  end: true,
};

/**
 * Which audit trails this reader may open.
 *
 * <strong>The two audit entries are the only permission-gated items in this rail, and they are
 * HIDDEN rather than shown-and-refused — which is the opposite of what every other entry here
 * does.</strong> That is a deliberate exception taken at the product owner's direction (§242), not
 * an oversight, and it is worth stating because five separate comments in this file argue the other
 * way and a later reader will find them first.
 *
 * The argument those comments make is that hiding an entry tells a lie about the *product* — "this
 * instance has no CI credentials" — which is worse than telling the truth about the reader's
 * *access*. An audit trail is the one place that trade runs the other way: the screen's whole
 * subject is who did what, so advertising it to somebody who may not read it invites exactly the
 * request the permission exists to refuse, and the entry itself becomes a standing hint that the
 * company is being watched in a way this person is not trusted to see.
 *
 * If this is ever revisited, revisit it here rather than one entry at a time — the pair must agree.
 */
interface AuditReach {
  project: boolean;
  company: boolean;
}

/**
 * The three **credential** entries, and whether this reader may open the page each one leads to
 * (005 C2, decided).
 *
 * ~~Family 7's second governing rule: a rail entry the caller can see leads to a page they can
 * read — so every entry here is listed for everybody, and the page explains its own refusal.~~ →
 * **superseded 2026-08-28: the rule is now conditional.** The three pages named here are the ones
 * where the argument the comments below make — that hiding an entry lies about the *product* —
 * is outweighed, because the page has **no read-only view at all**: the reads themselves cost
 * `integration:manage` / `token:issue` / `company:idp`, so what a caller without the key gets is a
 * whole screen that exists only to refuse them. `Audit trail` already worked this way, at the
 * product owner's direction, and the reasoning generalises to credentials: a standing entry into
 * a page whose entire content is withheld is an invitation to ask for exactly what the key exists
 * to refuse.
 *
 * The old reasoning is kept above rather than deleted because it is still right everywhere else in
 * this rail — `Review` and `Access & roles` are listed for everybody precisely because both render
 * read-only for a caller without the write key. **The test is whether the page can be read, not
 * whether it can be written.**
 *
 * `Project access` (`Access & roles`) is the fourth screen and it moves the *other* way: 005 Q2
 * relaxed `GET /projects/{id}/members` to `project:read`, so the page became readable and the
 * entry is listed *because* of that — the same rule applied correctly.
 */
interface CredentialReach {
  /** `integration:manage` — the project's notification channels **and** its tracker bindings. */
  integrations: boolean;
  /** `token:issue` — the company's API tokens. */
  tokens: boolean;
  /** `company:idp` — single sign-on. */
  sso: boolean;
}

function projectNav(
  projectId: string,
  audit: AuditReach,
  credentials: CredentialReach,
  /**
   * `issue:import` — the migration offramp (014 D-e). Its own parameter rather than a fourth field
   * on {@link CredentialReach}: that interface's whole subject is a *credential the product keeps*,
   * and an import connection is the opposite — used once for one run and never stored. The rule the
   * two share is `CredentialReach`'s, not its name: the page has no read-only view, so the entry is
   * listed only for a caller who has one.
   */
  canImport: boolean,
): { heading: string; items: NavItem[] }[] {
  const p = `/projects/${projectId}`;
  return [
    {
      heading: 'Repository',
      items: [
        { label: 'Overview', to: p, icon: <LayoutDashboard className="size-4" />, end: true },
        { label: 'Test cases', to: `${p}/cases`, icon: <Folder className="size-4" /> },
        { label: 'Documentation', to: `${p}/docs`, icon: <FileText className="size-4" /> },
      ],
    },
    {
      heading: 'Execution',
      items: [
        { label: 'Test plans', to: `${p}/plans`, icon: <ClipboardList className="size-4" /> },
        { label: 'Test runs', to: `${p}/runs`, icon: <ListChecks className="size-4" /> },
        /*
          §5's Work screen (011 D34) — **one entry, five views**, which is the decision this
          replaces the `Board` entry with. ~~`Board`, at `${p}/board`.~~ Board, Backlog, Table,
          Timeline and Calendar are one dataset with different renderers and one shared filter, so
          five rail entries would be five names for one place — and the filter a person set on one
          would be invisible on the next.

          The board route survives and is still linked from the Work screen's own Board tab, which
          names the module that folds it in. Only the rail entry is gone.
        */
        { label: 'Work', to: `${p}/work`, icon: <Kanban className="size-4" /> },
        /*
          013 M3's releases, immediately after Work — the mockup rail's own order
          (*Overview · Work · Releases · Documents · …*). Execution rather than Project because a
          release is what ships, not how the project is configured, and because the person asking
          "can we ship 2.4?" is the person who was just looking at the work.

          Ungated, like every item in this rail: `release:read` is held by every project-scoped role
          that renders a rail at all, so a gate would hide it from nobody who can reach it while
          teaching that rail items come and go by permission. The two writes are gated on their own
          controls (§31).

          **The Timeline and Calendar views are NOT rail entries.** They are `?view=` arms of Work
          (D34), for the reason the comment above gives about the five views.
        */
        { label: 'Releases', to: `${p}/releases`, icon: <Rocket className="size-4" /> },
        /*
          §6e's reports (§89). Execution rather than Project, because what a report is *of* is
          execution results — the only one that exists today summarises a run, and every one §6e
          names is computed over the fact log. Filing it under Project would put it beside the
          administration screens, which is where people would stop looking for it.

          **Ungated, like every other item in this rail.** The plan's F1 line asked for a gate on
          `report:view`; the `Review` item above records the argument against it, and it applies
          more strongly here — `report:view` is held by every project-scoped role that renders a
          rail at all, the one exception being Automation Service, which is API-token only and
          renders none. (`V2`'s Account Manager and Finance are company-scoped and hold it not, for
          the same reason they hold no `project:read`.) So the gate
          would hide the entry from nobody who can reach it, while teaching that rail items come
          and go by permission, which is the opposite of §31's disable-don't-hide.
        */
        { label: 'Reports', to: `${p}/reports`, icon: <ChartColumn className="size-4" /> },
        /*
          §119. Execution, beside Test runs, because an ingested report *is* a run's results
          arriving by another door — and because the person who goes looking for "why did my CI
          results not show up" is looking at the run that should have them.

          Ungated, like every item in this rail. The history needs only membership; the binding
          inside it costs `case:edit` and is disabled with a reason there (§31). Hiding the entry
          would teach a Test Engineer that this instance has no CI integration, which is the same
          lie an empty list would tell.
        */
        {
          label: 'CI ingestion',
          to: `${p}/ingestions`,
          icon: <CloudUpload className="size-4" />,
        },
      ],
    },
    {
      heading: 'Authoring',
      items: [
        /*
          §138. Its own heading rather than under Repository, because what is here is NOT in the
          repository — that is the entire point of the boundary — and a rail entry sitting beside
          `Test cases` would suggest otherwise before anybody clicked it.

          Ungated, like every item in this rail. The queue is readable by anyone who may read cases;
          the two decisions cost `case:create` and are disabled with a reason on the proposal itself
          (§31). Hiding the entry would teach a Viewer that this instance has no generation at all,
          which is the same lie an empty list would tell.
        */
        {
          label: 'Generated cases',
          to: `${p}/generated-cases`,
          icon: <Sparkles className="size-4" />,
        },
      ],
    },
    {
      heading: 'Project',
      items: [
        { label: 'Artifacts', to: `${p}/artifacts`, icon: <Package className="size-4" /> },
        /*
          `${p}/…`, and it earns the Project heading on exactly the test the note below sets: it is
          about the project in the picker and switching projects changes what it shows. It is the
          per-project view of the access model — what THIS project granted, and what it inherits
          from the company — while `Teams & roles` below stays the company-wide one. Two headings,
          two scopes, one implementation underneath.
        */
        { label: 'Access & roles', to: `${p}/access`, icon: <Users className="size-4" /> },
        /*
          The project's classification vocabulary. Project-headed on the same test: Feature belongs
          to this project and switching projects changes the tree — even though three of the four
          axes on that screen are the company's, which is what the page itself says.
        */
        /*
          011 T7's two. `General` is what the project is called, what prefixes its issues and the
          six issue types it holds; `Workflow` is story B1's statuses, their kinds and the legal
          moves. Both are ungated in the rail, like Classification and Review: the reads cost
          project visibility alone and each page renders read-only for a caller who cannot write,
          so hiding the entry would leave a reader with no way to find out what the statuses in
          their project even mean.

          `General` sits first because it is the settings screen a person looks for by that name,
          and it is where `settings-general.html` puts the Danger zone.
        */
        {
          label: 'General',
          to: `${p}/settings/general`,
          icon: <Settings className="size-4" />,
        },
        {
          label: 'Workflow',
          to: `${p}/settings/workflow`,
          icon: <GitBranch className="size-4" />,
        },
        /*
          012 T9's board settings, at `settings-boards.html`'s position in the mockup's subnav —
          between Workflow and the one entry this rail still does NOT have. That row reads
          *General · Workflow · Boards · Repositories · Imports*; ~~Repositories is M5's and Imports
          is M4's~~ → **both landed**: Imports in 014, Repositories in 015 T5, so that subnav row is
          complete and this rail matches it.

          Ungated, like the two above it: the reads cost `board:read`, which every role holds, and
          the page renders read-only without `board:configure`.
        */
        {
          label: 'Boards',
          to: `${p}/settings/boards`,
          icon: <Kanban className="size-4" />,
        },
        {
          label: 'Repositories',
          to: `${p}/settings/repositories`,
          icon: <FolderGit2 className="size-4" />,
        },
        /*
          014 T8's imports, at `settings-import.html`'s position in that same subnav — and **gated**,
          which is the opposite of the three entries above it and matches Notifications (005 C2).

          Those three are ungated because each renders read-only for a caller who cannot write, so a
          reader without the key still learns what their project's statuses mean. There is no
          read-only view of an import: every operation in the family costs `issue:import` (014 D-e,
          because a report names the source site, the account that connected to it and the person
          who ran the migration), so the entry would lead to a screen whose whole content is a
          refusal.
        */
        ...(canImport
          ? [
              {
                label: 'Imports',
                to: `${p}/imports`,
                icon: <Import className="size-4" />,
              },
            ]
          : []),
        {
          label: 'Classification',
          to: `${p}/settings/classification`,
          icon: <Tag className="size-4" />,
        },
        /*
          §6g's review policy. Shown to everybody who can see the project, not only to holders of
          `project:settings`: the page renders its current state read-only for the rest, which is
          §31's disable-don't-hide, and hiding the rail item would leave a reader with no way to
          find out why no artifact in the project shows a review panel.
        */
        {
          label: 'Review',
          to: `${p}/settings/review`,
          icon: <MessageSquareText className="size-4" />,
        },
        /*
          §218's channels. ~~Ungated like every item in this rail — a rail with no Notifications
          entry tells a test engineer that this instance cannot send notifications, which is a lie
          about the product rather than about their access.~~ → **superseded 2026-08-28 (005 C2):
          gated on `integration:manage` and un-listed without it.**

          The sentence above already conceded the thing that reverses it — "there is no read-only
          view to fall back on" — and that is exactly the condition `CredentialReach` now turns on.
          The entry led to a screen whose whole content is a refusal.
        */
        ...(credentials.integrations
          ? [
              {
                label: 'Notifications',
                to: `${p}/settings/notifications`,
                icon: <Bell className="size-4" />,
              },
              /*
                004 Phase 1's bindings, beside Notifications because they are the same module, the
                same key and the same argument: the reads cost `integration:manage`, so there is no
                read-only view and the entry is listed only for a caller who has one.

                A person's own tracker credential is NOT here — it is in the account menu, because
                it is not scoped by the project switcher this rail sits under.
              */
              {
                label: 'Issue tracker',
                to: `${p}/settings/trackers`,
                icon: <Bug className="size-4" />,
              },
            ]
          : []),
        // The project trail (§242). Gated on `project:audit` and HIDDEN without it — see
        // AuditReach above for why this pair breaks the rail's rule on purpose.
        ...(audit.project
          ? [
              {
                label: 'Audit trail',
                to: `${p}/audit`,
                icon: <History className="size-4" />,
              },
            ]
          : []),
      ],
    },
    /*
      Company, not Project — and the grouping was a real misstatement rather than a tidy-up.

      Every other item in this rail is `${p}/…`: it is about the project in the picker, and switching
      projects changes what it shows. `Teams & roles` is `/admin/teams`, which is company-wide and
      does not change when you switch projects. Listing it under "Project" told the reader that roles
      are granted per project — which is the opposite of the model (a role is held across the whole
      company), so the one place a person goes to understand permissions was mis-teaching them before
      they arrived.

      That remains true and `Access & roles` above does not undo it. A role is still *held* across
      the company; a project may grant its own and, per roles spec §A1.1, a project grant REPLACES
      the company-wide one there. Two entries because there are two genuinely different questions —
      "who is in this company and what do they hold" and "who can act on this project" — not because
      the same screen is listed twice.
    */
    {
      heading: 'Company',
      items: [
        /*
          The org chart (§6k), first under this heading because it is the one screen here every
          member can actually use — the other three lead to administration.

          **Ungated, like every item in this rail — and for the stronger reason.** The three below
          are ungated despite leading to screens that refuse a non-administrator, on §31's
          disable-don't-hide grounds. This one leads to a screen with no gate at all: there is
          nothing to refuse, so the entry is not a promise the page has to walk back.
        */
        { label: 'Org chart', to: '/org-chart', icon: <Network className="size-4" /> },
        /*
          Staffing and Directory (017 M7), after Org chart because they answer the same shape of
          question one step further on — who these people are, then who is free and who knows what.

          **Ungated, like every item in this rail, and here the argument has a second half.** Both
          screens cost `staffing:directory`, which a Project Manager does not hold (017 D-g), so a
          gate would hide them from one of the roles most likely to look. Hiding would teach a PM
          that this product cannot answer "who is free next month" — which it can, for the people
          who own that question — where the refusal they get instead names the screen that answers
          *theirs*: their own project's Staffing. That is the only refusal in this product that can
          point at the page the reader actually wants, and it is only reachable if the entry is
          shown.
        */
        { label: 'Staffing', to: '/staffing', icon: <Users2 className="size-4" /> },
        { label: 'Directory', to: '/directory', icon: <UserRoundSearch className="size-4" /> },
        { label: 'Teams & roles', to: '/admin/teams', icon: <Users className="size-4" /> },
        /*
          Company, beside Teams & roles, for the same reason that one is here: a token belongs to
          the company, and switching projects does not change the list.

          ~~**Shown to everyone, not gated** — hiding it would teach a Test Lead that this product
          has no CI credentials.~~ → **superseded 2026-08-28 (005 C2): gated on `token:issue`.**
          `ApiTokenService` argues that the *read* is refused rather than narrowed, because an empty
          array would be a claim about the company told to somebody who may not ask; an entry into
          that refusal is the same claim one click earlier.
        */
        ...(credentials.tokens
          ? [{ label: 'API tokens', to: '/admin/tokens', icon: <Key className="size-4" /> }]
          : []),
        /*
          ~~Ungated for the same reason the two above are.~~ → **superseded 2026-08-28 (005 C2):
          gated on `company:idp`.** This one is the strongest of the three: a readable SSO page
          would disclose issuer URLs, client ids and claimed email domains — reconnaissance for
          whoever is trying to get in — which is why `IdentityProviderService` treats them as the
          module's strictest credentials and why no read-only view was ever going to exist here.
        */
        ...(credentials.sso
          ? [{ label: 'Single sign-on', to: '/admin/sso', icon: <LogIn className="size-4" /> }]
          : []),
        // The company trail (§242). Gated on `audit:view` and HIDDEN without it — the pair to the
        // project entry above, and the same deliberate exception.
        ...(audit.company
          ? [
              {
                label: 'Audit trail',
                to: '/admin/audit',
                icon: <History className="size-4" />,
              },
            ]
          : []),
      ],
    },
  ];
}

export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false);

  /*
    Read once on mount rather than in an effect: an effect would render the expanded rail first and
    snap it closed on the next frame, which is a visible jump on every page load for the people who
    chose the collapsed rail. `=== 'true'` rather than a truthiness check so a corrupt entry reads
    as the default (expanded) instead of as collapsed.
  */
  const [railCollapsed, setRailCollapsed] = useState(
    () => safeStorage.get(RAIL_COLLAPSED_KEY) === 'true',
  );

  /*
    `useMatch` is a hook, so it is called once per pattern at the top level rather than inside a
    loop — the array is a module constant precisely so this list cannot go out of step with it.
  */
  const documentRoute = [
    useMatch(COLLAPSED_RAIL_ROUTES[0]),
    useMatch(COLLAPSED_RAIL_ROUTES[1]),
    useMatch(COLLAPSED_RAIL_ROUTES[2]),
  ].some((match) => match !== null);

  const collapsed = railCollapsed || documentRoute;

  const toggleRail = () => {
    setRailCollapsed((collapsed) => {
      const next = !collapsed;
      safeStorage.set(RAIL_COLLAPSED_KEY, String(next));
      return next;
    });
  };

  // Moves focus into the content area on every navigation after the first, so client-side
  // routing announces itself the way a real page load would.
  const mainRef = useRouteFocus<HTMLElement>();

  return (
    <TopStripProvider>
      <div className="flex min-h-full bg-ground">
        {/*
          First focusable element in the DOM, so a keyboard user's very first Tab offers a way past
          the rail. Without it, reaching the content on a page like the case list means tabbing
          through the project switcher and eleven nav links on every single navigation.
        */}
        <SkipToContentLink />
        {/* Desktop rail — shown wherever there is room for it. */}
        <nav
          id="nav-rail"
          aria-label="Main"
          data-testid="Nav.Rail"
          /*
            Read by the collapse toggle's own assertion, and by nothing else: `aria-expanded` on the
            button names a state, and a screen reader needs the element that state is *about*. The
            testid says which variant is showing; this says how wide it currently is.
          */
          data-collapsed={collapsed ? 'true' : 'false'}
          className={cn(
            'hidden md:flex flex-col shrink-0 border-r border-line bg-rail',
            'sticky top-0 h-screen',
            // A width the user asked to change should be seen to change. Motion is opt-out at the
            // theme level (`prefers-reduced-motion` is a hard floor), so this respects it.
            'transition-[width] duration-150 ease-out motion-reduce:transition-none',
          )}
          style={{ width: collapsed ? RAIL_WIDTH_COLLAPSED : RAIL_WIDTH }}
        >
          <RailContent collapsed={collapsed} onToggleCollapse={documentRoute ? undefined : toggleRail} />
        </nav>

        {/* Small-screen drawer — the same content, mounted only while open. */}
        <Dialog open={drawerOpen} onOpenChange={setDrawerOpen}>
          <DialogContent
            data-testid="Nav.Drawer"
            className="left-0 top-0 h-full max-w-none translate-x-0 translate-y-0 rounded-none border-l-0 p-0 flex md:hidden"
            style={{ width: RAIL_WIDTH }}
          >
            <DialogTitle className="sr-only">Navigation</DialogTitle>
            <nav aria-label="Main" className="flex h-full w-full flex-col">
              <RailContent onNavigate={() => setDrawerOpen(false)} />
            </nav>
          </DialogContent>
        </Dialog>

        <div className="flex flex-1 min-w-0 flex-col">
          <TopBar onOpenNav={() => setDrawerOpen(true)} />

          <main
            id="main-content"
            /*
              Focusable so the route-change hook can move focus here. `-1` keeps it out of the tab
              order — it is a programmatic focus target, not a stop the user tabs through.
            */
            tabIndex={-1}
            ref={mainRef}
            className={cn(
              // The render kit gives every strip on the stage the same 22px horizontal gutter —
              // `.top`, `.filters`, `.title-row`, `.bulk` and `.foot` all share it, and it is the
              // one spatial constant of the shell. The app drew 16px at desktop and 8px below,
              // so all forty-two screens sat tighter to the frame than anything they were drawn
              // from. Below `md` the gutter stays smaller than 22px on purpose: the renders are
              // desktop-only and a phone cannot spend 44px on margins.
              'flex-1 min-w-0 px-3 py-2 md:px-[22px] md:py-3',
              // Focusing a large container draws a full-width ring that reads as a rendering bug
              // rather than as a focus cue. The heading the user lands on is the signal; the skip
              // link and every real control keep their own visible indicators.
              'outline-none',
            )}
          >
            {/*
              The per-route boundary sits here, inside the shell, so a page that throws degrades to
              an alert in the content area while the rail, the project switcher, and the top bar
              keep working — the user can navigate away from a broken page instead of reloading.
            */}
            <RouteErrorBoundary>
              <Outlet />
            </RouteErrorBoundary>
          </main>

          <AppFooter />
        </div>
      </div>
    </TopStripProvider>
  );
}

// ---------------------------------------------------------------- rail content ----

/**
 * `collapsed` is the icon-only rail; `onToggleCollapse` is what renders the toggle at all.
 *
 * Both are optional and the **drawer passes neither**, which is the whole reason they are props
 * rather than a hook read: a temporary drawer is already a deliberate reveal that costs a tap and
 * covers the page, so collapsing it to icons would save nothing and remove the labels from the one
 * variant whose users are on the smallest screens. The drawer is therefore always expanded, and
 * has no toggle to mislead anyone into thinking otherwise.
 */
function RailContent({
  onNavigate,
  collapsed = false,
  onToggleCollapse,
}: {
  onNavigate?: () => void;
  collapsed?: boolean;
  onToggleCollapse?: () => void;
}) {
  // See `useActiveProject` for why the path segment alone is not enough to name the project.
  const { activeProjectId } = useActiveProject();

  /*
    The rail's only permission reads, and the only two entries any verdict here decides — see
    `AuditReach`. Both gates fail CLOSED while pending, which is `useCan`'s `can()` and the right
    default for an entry that is hidden rather than disabled: the item appears once the answer
    arrives, where a wrongly-shown one would flash a link that then vanishes.

    Both hooks read `/me/permissions`, which every screen behind this rail already fetches, so the
    keys are warm and this costs no extra round trip on navigation.
  */
  const projectGate = useCan(activeProjectId);
  const companyGate = useCanInCompany();
  const groups = activeProjectId
    ? projectNav(
        activeProjectId,
        {
          project: projectGate.can('project:audit'),
          company: companyGate.can('audit:view'),
        },
        /*
          The three credential entries (005 C2). Read from the same two warm gates, so this costs
          no request — and read through `can()`, which falls OPEN when `/me/permissions` itself
          failed: an entry wrongly shown leads to a page that refuses, while an entry wrongly
          hidden takes an administrator's own screens away with nothing on screen to say why.
        */
        {
          integrations: projectGate.can('integration:manage'),
          tokens: companyGate.can('token:issue'),
          sso: companyGate.can('company:idp'),
        },
        projectGate.can('issue:import'),
      )
    : [];

  return (
    <>
      {/*
        The wordmark is a link to the picker, which is the convention every reader arrives with —
        and the reason it can be one now is that `/` finally has a labelled entry below it. A logo
        that is the *only* way home is a guess the user has to make; a logo that agrees with a
        visible nav item is a shortcut.
      */}
      <div
        className={cn(
          'flex items-center gap-2 border-b border-line py-[14px]',
          collapsed ? 'flex-col justify-center px-0' : 'justify-between px-4',
        )}
      >
        <RouterLink
          to="/"
          onClick={onNavigate}
          aria-label="TCMS — all projects"
          data-testid="Nav.Wordmark"
          className="no-underline"
        >
          <Identifier
            variant="strong"
            className={cn(collapsed ? 'text-[13px] tracking-normal' : 'text-[14px] tracking-[.08em]')}
          >
            TCMS
          </Identifier>
        </RouterLink>

        {/*
          Top of the rail, in the wordmark row. ~~Bottom of the rail, below the scrolling nav
          list.~~ → **superseded 2026-08-29:** the foot put the control furthest from the edge it
          moves and furthest from the header the switcher and the search field now live in, so the
          one gesture that changes the rail's width sat at the opposite end of the screen from
          everything else that frames it. The wordmark row is the rail's own header, it never
          scrolls, and it is where the reader's eye already is on arrival — the original note's
          real requirement ("a toggle that scrolls away with the links is one the user cannot find
          to undo") is satisfied here too, and better.

          Rendered only where it does something — the drawer omits it, and so does a route that
          forced the collapse.
        */}
        {onToggleCollapse && (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="quiet"
                size="icon"
                onClick={onToggleCollapse}
                /*
                  The accessible name states the *action*, and `aria-expanded` states the current
                  state of the thing it controls. Naming it "Navigation" and leaving the state to
                  the attribute alone would read as a second way to open the drawer.
                */
                aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
                aria-expanded={!collapsed}
                aria-controls="nav-rail"
                data-testid="Nav.CollapseToggle"
              >
                {collapsed ? <ChevronRight className="size-4" /> : <ChevronLeft className="size-4" />}
              </Button>
            </TooltipTrigger>
            <TooltipContent side="right">
              {collapsed ? 'Expand navigation' : 'Collapse navigation'}
            </TooltipContent>
          </Tooltip>
        )}
      </div>

      {/*
        Outside the project frame below it, all three of them. `MY_WORK` and `TIMESHEETS` are
        personal; `ALL_PROJECTS` is what the switcher chooses between — see each one's own note.
      */}
      <ul className={cn('m-0 list-none py-2', collapsed ? 'px-0' : 'px-2')}>
        <li>
          <RailLink item={MY_WORK} onNavigate={onNavigate} collapsed={collapsed} />
        </li>
        <li>
          <RailLink item={TIMESHEETS} onNavigate={onNavigate} collapsed={collapsed} />
        </li>
        <li>
          <RailLink item={ALL_PROJECTS} onNavigate={onNavigate} collapsed={collapsed} />
        </li>
      </ul>

      {/*
        ~~The project switcher sat here, between the ungrouped block and the project groups it
        frames.~~ → **superseded 2026-08-29: it lives in the header.** The frame is now stated once,
        across the full width of the page, beside the search field that reaches across every project
        — and it no longer has to be two controls, because the header does not collapse to 64px.

        What the rail loses is the adjacency argument the old note made: the groups below are scoped
        by the switcher, and the switcher used to sit directly above them saying so. The headings
        still say it (`Repository`, `Execution`, `Project`), and the header switcher is the more
        prominent of the two placements, so a reader is more likely to see it, not less.
      */}
      <hr className="m-0 border-t border-line" />

      <div className="flex-1 overflow-y-auto py-2">
        {groups.map((group, index) => (
          <div key={group.heading} className="mb-3">
            {/*
              Collapsed, the heading is replaced by a rule rather than dropped. The grouping is the
              only thing telling a reader that Reports is about execution and Classification is
              about the project, and four icons in one undifferentiated column lose it entirely.
              The first group needs no rule — there is already a divider above it.
            */}
            {collapsed ? (
              index > 0 && <hr className="mx-3 mb-2 border-t border-line" />
            ) : (
              <p className="m-0 px-[18px] py-1 font-mono text-[10px] uppercase tracking-[.1em] text-ink-4">
                {group.heading}
              </p>
            )}
            {/*
              8px of inset so the rows read as pills inside the rail rather than as full-bleed
              bands — `.rail .grp` in the render kit. With the row's own 10px that puts label text
              18px from the rail edge, which is where the group heading above it sits too.
            */}
            <ul className={cn('m-0 list-none', collapsed ? 'p-0' : 'px-2 py-0')}>
              {group.items.map((item) => (
                <li key={item.to}>
                  <RailLink item={item} onNavigate={onNavigate} collapsed={collapsed} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

    </>
  );
}

/**
 * Which project the shell is currently framed by, and the list to switch between.
 *
 * Shared by the rail (which needs the id to build its project groups) and the header switcher
 * (which needs the list as well) — one resolution rule, read twice, rather than two that can drift.
 *
 * A `projectId` in the URL is not evidence that the project exists. The rail used to furnish the
 * whole project context — every nav group, the switcher, the breadcrumb root — straight off the
 * path segment, so `/projects/1/cases` rendered a complete, interactive project that was never
 * there. `ProjectGuard` now diverts a wrong address before the pages render; this is the other
 * half, because the shell draws outside the routed area and would otherwise still be asserting it
 * while the not-found screen says the opposite.
 */
function useActiveProject(): { projects: Project[]; activeProjectId: string | undefined } {
  const { projectId } = useParams();
  const projects = useProjects();

  const known = projects.data?.some((p) => p.id === projectId) ?? true;

  return {
    projects: projects.data ?? [],
    activeProjectId: (known ? projectId : undefined) ?? projects.data?.[0]?.id,
  };
}

/**
 * The project switcher, in the header.
 *
 * ~~The switcher in both widths — collapsed it becomes the project's key in a button, because the
 * one thing this control must answer at a glance is *which project am I in* and that is the
 * question a 64px rail is most likely to drop.~~ → **superseded 2026-08-29: one control, in the
 * header.** The header has a fixed height and does not collapse, so the two-variant design and the
 * question it answered both go away — the switcher now states the frame at full width on every
 * screen, at the same altitude as the search field that deliberately escapes that frame.
 *
 * It still renders with **no project to switch to**, which is the one part of the old design that
 * survives unchanged: a member holding no project grants should see that the control exists and is
 * empty, not conclude the app has no projects in it.
 *
 * The project's **key** rides beside its name in the list for the reason the collapsed variant
 * existed at all — the key is what the rest of the product identifies a project by, so it needs no
 * legend — but as a companion to the name rather than a replacement for it.
 */
function ProjectSwitcher() {
  const navigate = useNavigate();
  const { projects, activeProjectId } = useActiveProject();

  return (
    <Select value={activeProjectId ?? ''} onValueChange={(id) => navigate(`/projects/${id}`)}>
      <SelectTrigger
        id="project-switcher"
        aria-label="Project"
        data-testid="Nav.ProjectSwitcher"
        className="h-8 w-[168px] shrink-0 md:w-[212px]"
      >
        <SelectValue placeholder="Select a project" />
      </SelectTrigger>
      <SelectContent>
        {projects.map((project) => (
          <SelectItem key={project.id} value={project.id}>
            <span className="flex items-center gap-2">
              <Identifier variant="muted">{project.key}</Identifier>
              <span>{project.name}</span>
            </span>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/**
 * §249's cross-project case search, as a **field** rather than a link to one.
 *
 * ~~A rail entry beside `My work`, ungated.~~ → **superseded 2026-08-29:** the entry was a link to
 * a screen whose first act was to ask for a query, so the reader paid a navigation to reach an
 * empty input. The field is that input, hoisted one level: type, press Enter, land on the results.
 *
 * Two properties of the old entry are kept deliberately, because both were argued and neither
 * changed:
 *
 * - **No permission gate at all** — every company member may search, and a member holding no
 *   project grants gets an honest empty result rather than a refusal. Gating it would need a key,
 *   and a key invented for this would be seeded to nobody.
 * - **It is not scoped by the switcher beside it.** The search's bound is the caller's project
 *   visibility, computed server-side per request — not knowing which project holds the case is the
 *   entire premise of the screen. That is exactly why it does not pass `projectId`, and why the two
 *   controls sit side by side rather than the search sitting inside the project frame.
 *
 * It is a `<form>` with a submit, not an input that navigates per keystroke: routing on every
 * character would push a history entry per letter and re-run the query behind the reader's typing.
 * `CaseSearchPage` owns the live filtering once you are there — this only has to get you there with
 * the query you meant.
 */
function HeaderCaseSearch() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');

  return (
    <form
      role="search"
      className="flex min-w-0 flex-1 items-center"
      onSubmit={(event) => {
        event.preventDefault();
        const q = query.trim();
        // `URLSearchParams` rather than `encodeURIComponent`, so the query this writes is
        // byte-identical to the one `CaseSearchPage` writes back through `useSearchParams` — two
        // spellings of the same search would show as two history entries and two cache keys.
        navigate(q ? `/search?${new URLSearchParams({ q })}` : '/search');
      }}
    >
      <div className="relative w-full max-w-[420px]">
        <Search
          aria-hidden
          className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-ink-4"
        />
        <Input
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          /*
            The accessible name says what is searched, because "Search" alone in a product with a
            case list, a document tree and a board is three different promises. The placeholder is
            not the name — it disappears the moment anyone types.
          */
          aria-label="Search test cases"
          placeholder="Search test cases"
          data-testid="TopBar.CaseSearch"
          className="h-8 pl-8"
        />
      </div>
    </form>
  );
}

/**
 * The active item is marked three ways at once — an indicator bar, heavier weight, and
 * `aria-current="page"` — so the current location is never communicated by colour alone.
 */
function RailLink({
  item,
  onNavigate,
  collapsed = false,
}: {
  item: NavItem;
  onNavigate?: () => void;
  collapsed?: boolean;
}) {
  const link = (
    <NavLink
      to={item.to}
      end={item.end}
      onClick={onNavigate}
      /*
        Collapsed, the label is gone from the DOM, so the link needs its name back — an icon-only
        link with no `aria-label` is announced as its href. The tooltip is not a substitute: it is
        a hover affordance, and it never reaches a screen reader that is not hovering.
      */
      aria-label={collapsed ? item.label : undefined}
      data-testid={`Nav.Link.${item.label.replace(/[^A-Za-z]/g, '')}`}
      className="block no-underline"
    >
      {({ isActive }) => (
        <div
          aria-current={isActive ? 'page' : undefined}
          className={cn(
            // `.rail .n` in the render kit: an inset pill, `6px 10px`, 5px radius, 9px gap —
            // and the active state is the tinted fill ALONE. The build kept the fill and added a
            // 3px steel bar at the row's left edge, which the kit does not draw; two active
            // treatments on the most-looked-at component in the product.
            'relative flex items-center rounded-[5px] py-1.5 text-[13px]',
            collapsed ? 'justify-center gap-0 px-0' : 'justify-start gap-[9px] px-2.5',
            isActive ? 'bg-steel/12 font-medium text-ink' : 'text-ink-2 hover:bg-wash-1',
          )}
        >
          <span className={cn('flex', isActive ? 'text-steel' : 'text-ink-3')}>{item.icon}</span>
          {!collapsed && item.label}
        </div>
      )}
    </NavLink>
  );

  /*
    The tooltip is the collapsed rail's label, so it exists only there — wrapping the expanded link
    too would put a tooltip on text that already says the same word, which is noise on every hover
    across the whole rail.
  */
  return collapsed ? (
    <Tooltip>
      <TooltipTrigger asChild>{link}</TooltipTrigger>
      <TooltipContent side="right">{item.label}</TooltipContent>
    </Tooltip>
  ) : (
    link
  );
}

// --------------------------------------------------------------------- top bar ----

function TopBar({ onOpenNav }: { onOpenNav: () => void }) {
  const { user, logout } = useAuth();
  const { resolved, setMode } = useColorScheme();

  /**
   * The toggle must reflect the scheme the user is *looking at*, not the stored setting.
   * The default mode is `system`, so on a machine set to dark, `mode === 'dark'` is false
   * while the page is plainly dark — reading the setting alone shows a "switch to dark"
   * control on an already-dark page, and the first click then appears to do nothing because
   * it only moves `system` to the equivalent explicit value.
   *
   * `resolved` is `app/colorScheme.tsx`'s name for exactly that computation, which is why this
   * reads one value where it used to combine two: the provider owns the resolution now, so the
   * rule cannot be re-derived differently by a second caller.
   */
  const isDark = resolved === 'dark';

  return (
    <header className="sticky top-0 z-10 flex h-[52px] shrink-0 items-center gap-1 border-b border-line bg-paper px-3 md:px-[22px]">
      <Button
        variant="quiet"
        size="icon"
        onClick={onOpenNav}
        aria-label="Open navigation"
        data-testid="Nav.HamburgerButton"
        className="inline-flex md:hidden"
      >
        <MenuIcon className="size-4" />
      </Button>

      {/*
        The frame, then the thing that escapes it — in that order, and adjacent on purpose. The
        switcher says which project every screen below is about; the search reaches across all of
        them. Reading them left to right is the whole model of the app in two controls.
      */}
      <ProjectSwitcher />
      <HeaderCaseSearch />

      {/*
        Where a page puts its own commit bar — the case editor's Save sits here rather than in a
        footer bar of its own (design language rule 1, 007 §0 item 7). Empty on every route that
        fills nothing, so it doubles as the spacer this header already needed.

        `flex-1` alongside the search field's own `flex-1`: with nothing in the strip it collapses
        to zero and the search keeps its `max-w`, and when a page fills it the two share what is
        left. It stays right-aligned either way.
      */}
      {/*
        `min-w-0` and no `flex-wrap`. The audit's finding 2.1 was a page filling this slot with a
        `flex-wrap` group, which in a header this file fixes at 52px wraps THROUGH the header onto
        the content. The slot cannot stop a page doing that on its own, but it can stop being the
        thing that refuses to shrink: with `min-w-0` the breadcrumb and the search give way before
        the actions do. `app/HeaderActions.tsx` is the group shape that belongs in here.
      */}
      <TopStripTarget className="flex min-w-0 flex-1 items-center justify-end gap-2" />

      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant="quiet"
            size="icon"
            onClick={() => setMode(isDark ? 'light' : 'dark')}
            aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
            data-testid="TopBar.ThemeToggle"
          >
            {isDark ? <Sun className="size-4" /> : <Moon className="size-4" />}
          </Button>
        </TooltipTrigger>
        <TooltipContent>{isDark ? 'Switch to light theme' : 'Switch to dark theme'}</TooltipContent>
      </Tooltip>

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="quiet" size="sm" data-testid="TopBar.UserMenuButton" className="text-ink">
            {user?.displayName ?? 'Account'}
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <div className="px-2 py-1.5">
            <p className="m-0 text-[13px] font-semibold text-ink">{user?.displayName}</p>
            <p className="m-0 text-[12px] text-ink-3">{user?.email}</p>
          </div>
          <div className="my-1 h-px bg-line" />
          {/* The account's own sign-in identities (V58) — self-service, so no gate. */}
          <DropdownMenuItem asChild>
            <RouterLink to="/account/security" data-testid="TopBar.SecurityLink">
              <Shield className="size-4" />
              <span>Security</span>
            </RouterLink>
          </DropdownMenuItem>
          {/*
            The account's own tracker credentials (004 D2) — beside Security, self-service, no gate.

            **Not in the rail**, and that is the D2 inversion made visible: everything in the rail is
            scoped by the project switcher, and a tracker connection is not — one credential serves
            every project bound to that host. Putting it under `Project` would teach exactly the
            per-project reading the plan inverted the model to avoid.
          */}
          <DropdownMenuItem asChild>
            <RouterLink to="/account/trackers" data-testid="TopBar.TrackersLink">
              <Bug className="size-4" />
              <span>Tracker connections</span>
            </RouterLink>
          </DropdownMenuItem>
          {/*
            The caller's own AI assistants (015 D26) — beside Tracker connections, self-service, no
            gate, and for the same reason: an assistant is a credential a *person* owns, and the
            rail is scoped by the project switcher, which this is not. Connecting one costs no
            permission at all — it grants nothing its owner does not already hold.
          */}
          <DropdownMenuItem asChild>
            <RouterLink to="/account/assistants" data-testid="TopBar.AssistantsLink">
              <Bot className="size-4" />
              <span>AI assistants</span>
            </RouterLink>
          </DropdownMenuItem>
          {/*
            The user guide (§252). Beside Security rather than in the rail: the rail's every item is
            a place in the *product* — a project's cases, a company's teams — and its grouping tells
            the reader whether the project switcher changes what they are looking at. Help is about
            the product rather than a place in it, so it belongs with the two other entries whose
            subject is the session itself. The footer link below is the second, more discoverable
            door.
          */}
          <DropdownMenuItem asChild>
            <RouterLink to="/help" data-testid="TopBar.HelpLink">
              <HelpCircle className="size-4" />
              <span>Help &amp; documentation</span>
            </RouterLink>
          </DropdownMenuItem>
          <DropdownMenuItem onClick={logout} data-testid="TopBar.SignOutButton">
            <LogOut className="size-4" />
            <span>Sign out</span>
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </header>
  );
}

/**
 * A slim utility footer — this is a focused internal tool, so it carries the build it is running,
 * not a marketing fat footer. Semantic `<footer>`, so it forms a `contentinfo` landmark.
 *
 * It used to also carry "Documentation" and "Contact support", both `href="#"`. Neither
 * destination exists yet, and a link that goes nowhere is worse than an absent one: it costs a
 * click, teaches the user the footer is decorative, and sits in the tab order of every page in the
 * app. ~~**They come back when they have somewhere to point**~~ — **Documentation has somewhere to
 * point since §252**, and this is the condition being satisfied rather than waived: `/help` is a
 * real route with real content, so the link costs a click and delivers a page.
 *
 * **Contact support stays out.** Its destination is still per-deployment configuration rather than
 * a constant — a self-hosted instance's support contact is not something this repository knows —
 * so it belongs with the instance settings that release, not hard-coded here.
 *
 * The link reads ~~"Documentation"~~ **"Help & documentation"**, the account menu's wording, because
 * the rail already spends that word on the project's own doc pages (`Repository → Documentation`,
 * `/projects/:projectId/docs`) and the render fixes that label. One shell cannot name two
 * destinations the same thing and expect a reader to guess which one holds the CLI instructions.
 *
 * The gutter is the shell's 22px, matching `main` and the kit's `.foot` rather than the 16px it
 * drew before — a footer inset differently from every strip above it reads as a rendering slip.
 */
function AppFooter() {
  return (
    <footer
      data-testid="AppFooter"
      className="border-t border-line px-3 py-2.5 text-ink-3 md:px-[22px]"
    >
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-[12px]">
          TCMS <Identifier variant="muted">{__APP_VERSION__}</Identifier>
        </span>
        <RouterLink
          to="/help"
          data-testid="AppFooter.HelpLink"
          className="text-[12px] text-steel underline-offset-4 hover:underline"
        >
          Help &amp; documentation
        </RouterLink>
      </div>
    </footer>
  );
}
