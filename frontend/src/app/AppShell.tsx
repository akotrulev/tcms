import { useColorScheme } from './useColorScheme';
import {
  Bot,
  Bug,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  HelpCircle,
  LogOut,
  Menu as MenuIcon,
  Moon,
  Search,
  Shield,
  Sun,
  Timer,
  Stamp,
} from 'lucide-react';
import { useState } from 'react';
import {
  Link as RouterLink,
  NavLink,
  Outlet,
  useLocation,
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
  NAV,
  isFolder,
  type NavEntry,
  type NavFolder,
  type NavGate,
  type NavItem,
  type NavZone,
} from './nav';
import {
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
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

// ------------------------------------------------------------------- the model ----

/**
 * ~~`projectNav()` — four subject folders inside the project frame, and four ungrouped items
 * above them, each built here as a literal.~~ → **superseded (021 D-h): the rail is data, in
 * `app/nav.ts`.**
 *
 * Six hundred lines of this file were a nav model interleaved with the arguments for it, and the
 * arguments are the half worth keeping — they are in `nav.ts` now, beside the entries they are
 * about, struck through where 021 reversed them. What is left here is the *rendering*: which gate
 * answers a row, how a zone is drawn at two widths, and what the one surviving folder does when
 * the labels are gone.
 *
 * The gate behaviour is carried over **unchanged**, which is the part that had to not drift:
 *
 *   - a row with no gate is listed for everybody (§31, disable-don't-hide);
 *   - a gated row is ABSENT rather than disabled — the nine recorded exceptions, each argued in
 *     `nav.ts` at the entry that carries it;
 *   - the verdict comes from `can()`, which fails **closed while pending** (the item appears once
 *     the answer arrives, where a wrongly-shown one would flash a link that then vanishes) and
 *     **open when `/me/permissions` itself failed** (an entry wrongly shown leads to a page that
 *     refuses; an entry wrongly hidden takes an administrator's own screens away with nothing on
 *     screen to say why).
 */
function useNavGates(activeProjectId: string | undefined): (gate: NavGate | undefined) => boolean {
  /*
    The rail's only permission reads, and both are warm: every screen behind this rail already
    fetches `/me/permissions`, so asking again costs no round trip on navigation.

    Two hooks rather than one with a scope argument, because the two read different endpoints'
    answers — a COMPANY-scope question asked of a project (or the reverse) is answered confidently
    and wrongly. `NavGate.scope` is what stops a row asking the wrong one.
  */
  const projectGate = useCan(activeProjectId);
  const companyGate = useCanInCompany();

  return (gate) => {
    if (!gate) return true;
    return gate.scope === 'company' ? companyGate.can(gate.key) : projectGate.can(gate.key);
  };
}

/**
 * An entry survives its own gate; **a folder whose every child is hidden is itself hidden.**
 *
 * The second half is what keeps `Administration` honest. All three of its children are gated, so
 * for most callers there is nothing behind it — and a folder that opens onto nothing is worse than
 * an absent one, because it advertises the existence of exactly the screens the permissions exist
 * to withhold.
 */
function visibleEntries(
  zone: NavZone,
  allows: (gate: NavGate | undefined) => boolean,
): NavEntry[] {
  return zone.entries.flatMap<NavEntry>((entry) => {
    if (!isFolder(entry)) return allows(entry.gate) ? [entry] : [];
    const items = entry.items.filter((child) => allows(child.gate));
    return items.length ? [{ ...entry, items }] : [];
  });
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
 *
 * ## Three zones, and the collapse is now a label swap and nothing else
 *
 * The proposal's finding 10 asked for a 64px rail that is *the expanded rail with the labels
 * removed* — no row changing depth, nothing appearing or disappearing, muscle memory surviving the
 * collapse. The flat project zone is what makes that true: the only depth left anywhere in this
 * rail is `Administration`, and it keeps its behaviour at both widths (a disclosure when there is
 * room for labels, a labelled flyout when there is not).
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
  const allows = useNavGates(activeProjectId);

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
          moves and furthest from the header the search field lives in, so the one gesture that
          changes the rail's width sat at the opposite end of the screen from everything else that
          frames it. The wordmark row is the rail's own header, it never scrolls, and it is where
          the reader's eye already is on arrival.

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

      <div className="flex-1 overflow-y-auto py-2">
        {NAV.map((zone, index) => {
          /*
            The zone-level gate (`Project` needs `project:read`) and the row-level ones are the
            same predicate asked at two altitudes. A zone that survives its own gate but has no
            visible rows is not drawn either — an empty heading is a claim that something is there.

            The project zone additionally needs a project to be *about*. A member holding no
            grants at all sees `You` and `Company` and no empty frame in between.
          */
          if (!allows(zone.needs)) return null;
          if (zone.id === 'project' && !activeProjectId) return null;
          const entries = visibleEntries(zone, allows);
          if (!entries.length) return null;

          const headingId = `nav-zone-${zone.id}`;
          return (
            <div key={zone.id} data-testid={`Nav.Zone.${zone.id}`} className="mb-3">
              {/*
                Collapsed, the heading is replaced by a rule rather than dropped. The three zones
                are the only structure left in this rail, and three undifferentiated columns of
                icons lose it entirely. The first zone needs no rule — the wordmark row's border
                is already above it.
              */}
              {index > 0 && collapsed && <hr className="mx-3 mb-2 border-t border-line" />}
              {zone.switcher ? (
                /*
                  **The switcher is the zone header** (R1) — the control that changes the eleven
                  rows below it, sitting on top of them. Collapsed it is the project key chip,
                  which opens the same `Select`.
                */
                <div className={cn('mb-1', collapsed ? 'px-1.5' : 'px-2')}>
                  <ProjectSwitcher collapsed={collapsed} onNavigate={onNavigate} />
                </div>
              ) : (
                !collapsed && (
                  <p
                    id={headingId}
                    className="m-0 px-[18px] py-1 font-mono text-[10px] uppercase tracking-[.1em] text-ink-4"
                  >
                    {zone.label}
                  </p>
                )
              )}
              {/*
                8px of inset so the rows read as pills inside the rail rather than as full-bleed
                bands — `.rail .grp` in the render kit. With the row's own 10px that puts label
                text 18px from the rail edge, which is where the zone heading above it sits too.
              */}
              <ul
                aria-label={zone.switcher || collapsed ? zone.label : undefined}
                aria-labelledby={zone.switcher || collapsed ? undefined : headingId}
                className={cn('m-0 list-none', collapsed ? 'p-0' : 'px-2 py-0')}
              >
                {entries.map((entry) => (
                  <li key={entry.label}>
                    {isFolder(entry) ? (
                      <RailFolder
                        folder={entry}
                        projectId={activeProjectId ?? ''}
                        onNavigate={onNavigate}
                        collapsed={collapsed}
                      />
                    ) : (
                      <RailLink
                        item={entry}
                        projectId={activeProjectId ?? ''}
                        onNavigate={onNavigate}
                        collapsed={collapsed}
                      />
                    )}
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
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
 * The project switcher — **the Project zone's header, inside the rail** (021 R1).
 *
 * ~~The switcher in both widths — collapsed it becomes the project's key in a button, because the
 * one thing this control must answer at a glance is *which project am I in* and that is the
 * question a 64px rail is most likely to drop.~~ → ~~**superseded 2026-08-29: one control, in the
 * header.** The header has a fixed height and does not collapse, so the two-variant design and the
 * question it answered both go away.~~ → **superseded again (021 R1): back in the rail, as the
 * zone header, and the two-variant design comes back with it.**
 *
 * The 2026-08-29 note traded adjacency for width and the trade did not pay. What it bought — the
 * frame stated at full width — is now stated by the breadcrumb's first segment (R2), which is
 * shell-owned and on every route; what it cost was the one thing this control is for. Everything
 * under this switcher is scoped BY it, and a control that changes eleven rows belongs on top of
 * those eleven rows rather than across the page beside a search field that deliberately escapes
 * the frame. Moving it out also left the header with nowhere to put the breadcrumbs and the
 * company slot, which is the second half of what R1 pays for.
 *
 * **Collapsed, it is the project key chip** — the render's `ACME` — and it opens the same `Select`.
 * That is the variant the old note argued for and then removed: at 64px there is no room for a
 * name, and the key is what the rest of the product identifies a project by, so it needs no legend.
 *
 * It still renders with **no project to switch to**, which is the one part of the original design
 * that has survived both reversals: a member holding no project grants should see that the control
 * exists and is empty, not conclude the app has no projects in it.
 */
function ProjectSwitcher({
  collapsed = false,
  onNavigate,
}: {
  collapsed?: boolean;
  onNavigate?: () => void;
}) {
  const navigate = useNavigate();
  const { projects, activeProjectId } = useActiveProject();
  const active = projects.find((project) => project.id === activeProjectId);

  return (
    <Select
      value={activeProjectId ?? ''}
      onValueChange={(id) => {
        onNavigate?.();
        navigate(`/projects/${id}`);
      }}
    >
      <SelectTrigger
        id="project-switcher"
        /*
          The name says the current value AND the action, because collapsed there is no label
          beside it and the visible text is a two-to-ten character key. "Project" alone would
          announce a control whose purpose a reader has to infer from a code.
        */
        aria-label={active ? `Project: ${active.name}. Switch project` : 'Project'}
        data-testid="Nav.ProjectSwitcher"
        className={cn(
          'h-8',
          collapsed
            // No room for a name and none for a chevron: at 64px the chip IS the control.
            ? 'w-full justify-center px-0 [&>span]:text-center [&>svg]:hidden'
            : 'w-full [&>span]:text-left',
        )}
      >
        {collapsed ? (
          <Identifier variant="strong" className="text-[11px]">
            {active?.key ?? '—'}
          </Identifier>
        ) : (
          <SelectValue placeholder="Select a project" />
        )}
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
  projectId,
  onNavigate,
  collapsed = false,
}: {
  item: NavItem;
  /** `''` outside the project frame; a row that does not use it ignores it. */
  projectId: string;
  onNavigate?: () => void;
  collapsed?: boolean;
}) {
  const Icon = item.icon;
  const link = (
    <NavLink
      to={item.to(projectId)}
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
            // and the active state is the tinted fill ALONE.
            'relative flex items-center rounded-[5px] py-1.5 text-[13px]',
            collapsed ? 'justify-center gap-0 px-0' : 'justify-start gap-[9px] px-2.5',
            isActive ? 'bg-steel/12 font-medium text-ink' : 'text-ink-2 hover:bg-wash-1',
          )}
        >
          <span className={cn('flex', isActive ? 'text-steel' : 'text-ink-3')}>
            <Icon className="size-4" />
          </span>
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

/**
 * The one folder left anywhere in the rail (021 §1) — `Administration`, and its two widths.
 *
 * Expanded it is a disclosure: a button carrying `aria-expanded` and `aria-controls`, and a nested
 * list that starts **open when the current route is inside it**, so a reader who navigated to
 * `/admin/sso` does not find the trail they are standing on shut. Collapsed there is no room for a
 * nested list, so it is a menu — the render's labelled flyout — and the label is carried into it
 * as a heading rather than dropped, because three icons with no group name is exactly the state
 * the folder exists to avoid.
 *
 * There is no roll-up badge, and the absence is measured rather than missing: §8.3's badge table
 * lists seven, and none of them is behind this folder. A folder that acquires a badged child later
 * needs one, and this is the note that says so.
 */
function RailFolder({
  folder,
  projectId,
  onNavigate,
  collapsed = false,
}: {
  folder: NavFolder;
  projectId: string;
  onNavigate?: () => void;
  collapsed?: boolean;
}) {
  const { pathname } = useLocation();
  const Icon = folder.icon;
  /*
    Prefix match with a boundary, which is what `NavLink` itself does: `/admin/tokens` must claim
    `/admin/tokens/new` and must not claim `/admin/tokensomething`.
  */
  const holdsActive = folder.items.some((child) => {
    const to = child.to(projectId);
    return pathname === to || pathname.startsWith(`${to}/`);
  });
  const [open, setOpen] = useState(holdsActive);

  if (collapsed) {
    return (
      <DropdownMenu>
        <Tooltip>
          <TooltipTrigger asChild>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                aria-label={folder.label}
                data-testid={`Nav.Folder.${folder.label.replace(/[^A-Za-z]/g, '')}`}
                className={cn(
                  'relative flex w-full cursor-pointer items-center justify-center rounded-[5px] ' +
                    'border-0 bg-transparent py-1.5 text-[13px]',
                  holdsActive ? 'bg-steel/12 text-ink' : 'text-ink-2 hover:bg-wash-1',
                )}
              >
                <span className={cn('flex', holdsActive ? 'text-steel' : 'text-ink-3')}>
                  <Icon className="size-4" />
                </span>
              </button>
            </DropdownMenuTrigger>
          </TooltipTrigger>
          <TooltipContent side="right">{folder.label}</TooltipContent>
        </Tooltip>
        <DropdownMenuContent side="right" align="start">
          <DropdownMenuLabel>{folder.label}</DropdownMenuLabel>
          {folder.items.map((child) => {
            const ChildIcon = child.icon;
            return (
              <DropdownMenuItem key={child.label} asChild>
                <RouterLink
                  to={child.to(projectId)}
                  onClick={onNavigate}
                  data-testid={`Nav.Link.${child.label.replace(/[^A-Za-z]/g, '')}`}
                >
                  <ChildIcon className="size-4" />
                  <span>{child.label}</span>
                </RouterLink>
              </DropdownMenuItem>
            );
          })}
        </DropdownMenuContent>
      </DropdownMenu>
    );
  }

  const listId = `nav-folder-${folder.label.replace(/[^A-Za-z]/g, '').toLowerCase()}`;
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((was) => !was)}
        aria-expanded={open}
        aria-controls={listId}
        data-testid={`Nav.Folder.${folder.label.replace(/[^A-Za-z]/g, '')}`}
        className={cn(
          'relative flex w-full cursor-pointer items-center justify-start gap-[9px] rounded-[5px] ' +
            'border-0 bg-transparent px-2.5 py-1.5 text-left text-[13px]',
          holdsActive ? 'text-ink' : 'text-ink-2 hover:bg-wash-1',
        )}
      >
        <span className={cn('flex', holdsActive ? 'text-steel' : 'text-ink-3')}>
          <Icon className="size-4" />
        </span>
        <span className="flex-1">{folder.label}</span>
        <ChevronDown
          aria-hidden
          className={cn('size-3.5 shrink-0 text-ink-4 transition-transform', !open && '-rotate-90')}
        />
      </button>
      <ul id={listId} hidden={!open} className="m-0 list-none p-0 pl-3">
        {folder.items.map((child) => (
          <li key={child.label}>
            <RailLink item={child} projectId={projectId} onNavigate={onNavigate} />
          </li>
        ))}
      </ul>
    </div>
  );
}

// --------------------------------------------------------------------- top bar ----

/**
 * The company slot (021 §3) — **reserved, and empty until the contract can fill it.**
 *
 * The proposal puts the company's name at the far left of the header, ahead of the breadcrumbs,
 * as the outermost frame: instance → **company** → project → screen. It is drawn as static text
 * now and becomes the company switcher at backend M6, when a session can hold more than one.
 *
 * **It renders nothing today, and that is the honest state rather than an oversight.** There is no
 * company name on the wire: `GET /me` answers a `CurrentUser`, which is a `UserRef` plus
 * `isSystemAdmin`, `hasPassword` and `teams`, and no endpoint in `openapi.yaml` names the caller's
 * company at all. Filling this slot needs one field on that response, which is a contract change
 * and therefore a different stack's task — so this side reserves the space and shows nothing,
 * rather than inventing a name or hard-coding one from the mockup. A header that says "Acme Ltd"
 * to every company on the instance is worse than a header that says nothing.
 *
 * The element itself exists so the layout, the locator and the reservation are real: when the
 * field lands, one expression changes here and no other file moves.
 */
function CompanySlot() {
  return (
    <span
      data-testid="TopBar.CompanySlot"
      className="hidden shrink-0 truncate text-[13px] font-medium text-ink-2 md:inline"
    >
      {/* `CurrentUser.company?.name`, when `GET /me` carries one. */}
    </span>
  );
}

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
        ~~The frame, then the thing that escapes it — the project switcher and the search, adjacent
        on purpose.~~ → **superseded (021 R1): the switcher is the rail's Project zone header.**
        What the header holds instead is the OUTER frame — the company — and, from T7, the
        breadcrumb trail whose first segment restates the project at full width. That is the trade
        R1 makes: the header stops repeating what the rail says and starts saying the thing the
        rail cannot, which is where in the product this particular screen sits.
      */}
      <CompanySlot />
      <HeaderCaseSearch />

      {/*
        Where a page puts its own commit bar — the case editor's Save sits here rather than in a
        footer bar of its own (design language rule 1, 007 §0 item 7). Empty on every route that
        fills nothing, so it doubles as the spacer this header already needed.

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
          <DropdownMenuSeparator />
          {/*
            **The two rows that moved** (021 R11), and they are first in the menu because they are
            the ones that moved.

            ~~`Timesheets` is a rail item, above the project frame, because a timesheet period is
            per person and per fortnight and SPANS PROJECTS — listing it under the switcher would
            say that switching projects changes which hours you are looking at.~~ → **superseded
            (021 R11): the premise is right and the conclusion was wrong.** The rail's rule is *a
            place in the product scoped by the project switcher or by the company*; "personal, and
            spanning projects" fails that test rather than earning an ungrouped block of its own.
            The user menu's rule is the person's own things, and a fortnight of one person's hours
            is exactly that — the same rule that already put their tracker credential here.

            `Timesheet approvals` comes with it, listed for **everybody** and carrying **no count**.
            Both are the standing decisions, unchanged: it is listed because the page explains its
            own refusal (§31, disable-don't-hide), and it is countless because the count comes from
            `GET /timesheets/approvals`, which 403s for any caller without `time:approve` — a
            PROJECT-scope grant, so there is no company-wide "where may I approve" read this shell
            could ask first. §8.3's badge table says `none` on that row, alone among its seven.
            Firing the read unconditionally would raise a refused-request notification on every
            page load for the majority of this company, to draw a number on a menu.
          */}
          <DropdownMenuItem asChild>
            <RouterLink to="/timesheets" data-testid="TopBar.TimesheetsLink">
              <Timer className="size-4" />
              <span>Timesheets</span>
            </RouterLink>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <RouterLink to="/timesheets/approvals" data-testid="TopBar.TimesheetApprovalsLink">
              <Stamp className="size-4" />
              <span>Timesheet approvals</span>
            </RouterLink>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          {/*
            The account group (§4). Three credentials that belong to the PERSON rather than to a
            project or the company — which is the same test that moved the two rows above, applied
            to things that were already here.
          */}
          <DropdownMenuLabel>Account</DropdownMenuLabel>
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
          <DropdownMenuSeparator />
          {/*
            ~~`Help & documentation`~~ → **`User guide`** (021 §8.1, R6). The placement stands and
            only the collision goes: the rail spent the word `Documentation` on the project's own
            doc pages, so one shell named two destinations the same thing and a reader had to guess
            which one held the CLI instructions. Both ends move — the rail row is `Documents` now —
            and this one says what it is: the eleven-section guide.

            Beside Security rather than in the rail, which is unchanged: the rail's every item is a
            place in the *product*, and its zone tells the reader whether the project switcher
            changes what they are looking at. Help is about the product rather than a place in it.

            §4 lists `Your access` and `Keyboard shortcuts` above this row. Neither is here yet:
            `/account/access` is a route T7 builds, and a menu entry pointing at a route that does
            not exist is worse than an absent one — it costs a click and lands on the 404.
          */}
          <DropdownMenuItem asChild>
            <RouterLink to="/help" data-testid="TopBar.HelpLink">
              <HelpCircle className="size-4" />
              <span>User guide</span>
            </RouterLink>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
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
 * The link reads ~~"Documentation"~~ ~~"Help & documentation"~~ **"User guide"** (021 §8.1, R6),
 * the account menu's wording, because the rail already spends the word `Documents` on the
 * project's own doc pages (`/projects/:projectId/docs`). One shell cannot name two destinations
 * the same thing and expect a reader to guess which one holds the CLI instructions — and the
 * previous repair only moved the collision, since `Help & documentation` still contained the word
 * the rail row was called.
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
          User guide
        </RouterLink>
      </div>
    </footer>
  );
}
