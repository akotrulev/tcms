import { useColorScheme } from '@mui/material';
import {
  Bell,
  Bug,
  ChartColumn,
  ChevronLeft,
  ChevronRight,
  CloudUpload,
  ClipboardList,
  FileText,
  Folder,
  HelpCircle,
  History,
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
  Search,
  Shield,
  Sparkles,
  Sun,
  Tag,
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
 * §249's cross-project case search — beside My work, and ungated.
 *
 * **No permission gate at all**, which is the same call `MY_WORK` makes: every company member may
 * open it, and a member holding no project grants gets an honest empty result rather than a
 * refusal. Gating it would need a key, and a key invented for this would be seeded to nobody —
 * `BoardService.teamBoard`'s argument, and §67/§79/§80's.
 *
 * It renders before `useProjects` answers, for `MY_WORK`'s reason: the search's own bound is the
 * caller's project visibility, computed server-side per request, so the rail does not need to know
 * it to offer the screen.
 */
const CASE_SEARCH: NavItem = {
  label: 'Search cases',
  to: '/search',
  icon: <Search className="size-4" />,
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
        { label: 'Board', to: `${p}/board`, icon: <Kanban className="size-4" /> },
        /*
          §6e's reports (§89). Execution rather than Project, because what a report is *of* is
          execution results — the only one that exists today summarises a run, and every one §6e
          names is computed over the fact log. Filing it under Project would put it beside the
          administration screens, which is where people would stop looking for it.

          **Ungated, like every other item in this rail.** The plan's F1 line asked for a gate on
          `report:view`; the `Review` item above records the argument against it, and it applies
          more strongly here — `V3` grants `report:view` to five of the six built-in roles, and the
          sixth is Automation Service, which is API-token only and never renders a rail. So the gate
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
            'hidden md:flex flex-col shrink-0 border-r border-white/8 bg-[#0c1119]',
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
              'flex-1 min-w-0 px-2 py-2 md:px-4 md:py-3',
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
  const { projectId } = useParams();
  const projects = useProjects();

  /*
    A `projectId` in the URL is not evidence that the project exists. The rail used to furnish the
    whole project context — every nav group, the switcher, the breadcrumb root — straight off the
    path segment, so `/projects/1/cases` rendered a complete, interactive project that was never
    there. `ProjectGuard` now diverts a wrong address before the pages render; the rail is the
    other half, because it draws outside the routed area and would otherwise still be asserting it
    while the not-found screen says the opposite.
  */
  const known = projects.data?.some((p) => p.id === projectId) ?? true;
  const activeProjectId = (known ? projectId : undefined) ?? projects.data?.[0]?.id;

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
          'flex items-center border-b border-white/8 py-[14px]',
          collapsed ? 'justify-center px-0' : 'justify-start px-4',
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
            sx={{ fontSize: collapsed ? '0.8125rem' : '1rem', letterSpacing: collapsed ? 0 : '0.08em' }}
          >
            TCMS
          </Identifier>
        </RouterLink>
      </div>

      {/*
        Outside the project frame below it, both of them. `MY_WORK` is personal; `ALL_PROJECTS` is
        what the switcher chooses between — see each one's own note.
      */}
      <ul className="m-0 list-none p-0 py-2">
        <li>
          <RailLink item={MY_WORK} onNavigate={onNavigate} collapsed={collapsed} />
          <RailLink item={CASE_SEARCH} onNavigate={onNavigate} collapsed={collapsed} />
        </li>
        <li>
          <RailLink item={ALL_PROJECTS} onNavigate={onNavigate} collapsed={collapsed} />
        </li>
      </ul>

      <hr className="m-0 border-t border-white/8" />

      {/* Project switcher: which project you're in is the frame for every key on screen. */}
      <ProjectSwitcher
        projects={projects.data ?? []}
        activeProjectId={activeProjectId}
        collapsed={collapsed}
        onNavigate={onNavigate}
      />

      <hr className="m-0 border-t border-white/8" />

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
              index > 0 && <hr className="mx-3 mb-2 border-t border-white/8" />
            ) : (
              <p className="m-0 px-4 py-1 font-mono text-[10px] uppercase tracking-[.1em] text-ink-4">
                {group.heading}
              </p>
            )}
            <ul className="m-0 list-none p-0">
              {group.items.map((item) => (
                <li key={item.to}>
                  <RailLink item={item} onNavigate={onNavigate} collapsed={collapsed} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      {/*
        Bottom of the rail, below the scrolling nav list rather than inside it: it is a control on
        the rail, not a destination in it, and a toggle that scrolls away with the links is one the
        user cannot find to undo. Rendered only where it does something — the drawer omits it.
      */}
      {onToggleCollapse && (
        <>
          <hr className="m-0 border-t border-white/8" />
          <div className={cn('flex px-2 py-1.5', collapsed ? 'justify-center' : 'justify-end')}>
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
          </div>
        </>
      )}
    </>
  );
}

/**
 * The switcher in both widths.
 *
 * Collapsed it becomes the project's **key** in a button — not a generic folder icon — because the
 * one thing this control must answer at a glance is *which project am I in*, and that is the
 * question a 64px rail is most likely to drop. The key is what the rest of the product identifies a
 * project by, so it needs no legend.
 *
 * It renders even with no project to switch to, matching the expanded picker, which renders empty
 * rather than disappearing: a member holding no project grants should see that the control exists
 * and is empty, not conclude the app has no projects in it.
 */
function ProjectSwitcher({
  projects,
  activeProjectId,
  collapsed,
  onNavigate,
}: {
  projects: Project[];
  activeProjectId: string | undefined;
  collapsed: boolean;
  onNavigate?: () => void;
}) {
  const navigate = useNavigate();

  const active = projects.find((project) => project.id === activeProjectId);

  const go = (id: string) => {
    navigate(`/projects/${id}`);
    onNavigate?.();
  };

  if (collapsed) {
    return (
      <div className="flex justify-center py-3">
        <DropdownMenu>
          <Tooltip>
            <TooltipTrigger asChild>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={active ? `Switch project — current project ${active.name}` : 'Switch project'}
                  data-testid="Nav.ProjectSwitcher"
                >
                  {active ? (
                    <Identifier variant="strong" sx={{ fontSize: '0.75rem' }}>
                      {active.key}
                    </Identifier>
                  ) : (
                    <Folder className="size-4" />
                  )}
                </Button>
              </DropdownMenuTrigger>
            </TooltipTrigger>
            <TooltipContent side="right">{active ? `Project: ${active.name}` : 'Project'}</TooltipContent>
          </Tooltip>
          <DropdownMenuContent align="start" side="right">
            {projects.map((project) => (
              <DropdownMenuItem
                key={project.id}
                onClick={() => go(project.id)}
                className={cn(project.id === activeProjectId && 'bg-white/5 text-ink')}
              >
                <Identifier variant="muted">{project.key}</Identifier>
                <span>{project.name}</span>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    );
  }

  return (
    <div className="px-3 py-3">
      <label htmlFor="project-switcher" className="mb-1 block text-xs text-ink-3">
        Project
      </label>
      <Select value={activeProjectId ?? ''} onValueChange={go}>
        <SelectTrigger id="project-switcher" data-testid="Nav.ProjectSwitcher">
          <SelectValue placeholder="Select a project" />
        </SelectTrigger>
        <SelectContent>
          {projects.map((project) => (
            <SelectItem key={project.id} value={project.id}>
              {project.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
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
            'relative flex items-center py-[7px] text-[13px]',
            collapsed ? 'justify-center gap-0 px-0' : 'justify-start gap-2.5 px-4',
            isActive ? 'bg-steel/12 font-medium text-ink' : 'text-ink-2 hover:bg-white/4',
          )}
        >
          {isActive && (
            <span
              aria-hidden
              className="absolute left-0 top-[6px] bottom-[6px] w-[3px] rounded-r-sm bg-steel"
            />
          )}
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
  const { mode, systemMode, setMode } = useColorScheme();

  /**
   * The toggle must reflect the scheme the user is *looking at*, not the stored setting.
   * The default mode is `system`, so on a machine set to dark, `mode === 'dark'` is false
   * while the page is plainly dark — reading the setting alone shows a "switch to dark"
   * control on an already-dark page, and the first click then appears to do nothing because
   * it only moves `system` to the equivalent explicit value.
   */
  const isDark = mode === 'dark' || (mode === 'system' && systemMode === 'dark');

  return (
    <header className="sticky top-0 z-10 flex h-[52px] shrink-0 items-center gap-1 border-b border-white/8 bg-paper px-2 md:px-4">
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
        Where a page puts its own commit bar — the case editor's Save sits here rather than in a
        footer bar of its own (design language rule 1, 007 §0 item 7). Empty on every route that
        fills nothing, so it doubles as the spacer this header already needed.
      */}
      <TopStripTarget className="flex flex-1 items-center justify-end gap-2" />

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
            <p className="m-0 text-xs text-ink-3">{user?.email}</p>
          </div>
          <div className="my-1 h-px bg-white/8" />
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
 */
function AppFooter() {
  return (
    <footer
      data-testid="AppFooter"
      className="border-t border-white/8 px-2 py-2 text-ink-3 md:px-4"
    >
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-xs">
          TCMS <Identifier variant="muted">{__APP_VERSION__}</Identifier>
        </span>
        <RouterLink
          to="/help"
          data-testid="AppFooter.HelpLink"
          className="text-xs text-steel underline-offset-4 hover:underline"
        >
          Documentation
        </RouterLink>
      </div>
    </footer>
  );
}
