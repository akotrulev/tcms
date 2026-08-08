import {
  Box,
  Button,
  Divider,
  Drawer,
  IconButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Select,
  Stack,
  Tooltip,
  Typography,
  useColorScheme,
} from '@mui/material';
import ArticleOutlinedIcon from '@mui/icons-material/ArticleOutlined';
import AssignmentIndOutlinedIcon from '@mui/icons-material/AssignmentIndOutlined';
import AssessmentOutlinedIcon from '@mui/icons-material/AssessmentOutlined';
import AutoAwesomeOutlinedIcon from '@mui/icons-material/AutoAwesomeOutlined';
import CloudUploadOutlinedIcon from '@mui/icons-material/CloudUploadOutlined';
import AssignmentOutlinedIcon from '@mui/icons-material/AssignmentOutlined';
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined';
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import FolderOutlinedIcon from '@mui/icons-material/FolderOutlined';
import GridViewOutlinedIcon from '@mui/icons-material/GridViewOutlined';
import RateReviewOutlinedIcon from '@mui/icons-material/RateReviewOutlined';
import LocalOfferOutlinedIcon from '@mui/icons-material/LocalOfferOutlined';
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined';
import InventoryOutlinedIcon from '@mui/icons-material/Inventory2Outlined';
import KeyOutlinedIcon from '@mui/icons-material/KeyOutlined';
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import PlaylistAddCheckOutlinedIcon from '@mui/icons-material/PlaylistAddCheckOutlined';
import ViewKanbanOutlinedIcon from '@mui/icons-material/ViewKanbanOutlined';
import { useState, type ReactNode } from 'react';
import { Link as RouterLink, NavLink, Outlet, useNavigate, useParams } from 'react-router-dom';
import { useProjects } from '../api/queries';
import { useRouteFocus } from '../a11y/useRouteFocus';
import { Identifier } from '../components/Identifier';
import { RouteErrorBoundary } from '../components/ErrorBoundaries';
import { SkipToContentLink } from '../components/SkipToContentLink';
import { useAuth } from '../auth/useAuth';
import { safeStorage } from '../lib/safeStorage';
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
  icon: <AssignmentIndOutlinedIcon fontSize="small" />,
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
  icon: <GridViewOutlinedIcon fontSize="small" />,
  end: true,
};

function projectNav(projectId: string): { heading: string; items: NavItem[] }[] {
  const p = `/projects/${projectId}`;
  return [
    {
      heading: 'Repository',
      items: [
        { label: 'Overview', to: p, icon: <DashboardOutlinedIcon fontSize="small" />, end: true },
        { label: 'Test cases', to: `${p}/cases`, icon: <FolderOutlinedIcon fontSize="small" /> },
        { label: 'Documentation', to: `${p}/docs`, icon: <ArticleOutlinedIcon fontSize="small" /> },
      ],
    },
    {
      heading: 'Execution',
      items: [
        { label: 'Test plans', to: `${p}/plans`, icon: <AssignmentOutlinedIcon fontSize="small" /> },
        { label: 'Test runs', to: `${p}/runs`, icon: <PlaylistAddCheckOutlinedIcon fontSize="small" /> },
        { label: 'Board', to: `${p}/board`, icon: <ViewKanbanOutlinedIcon fontSize="small" /> },
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
        { label: 'Reports', to: `${p}/reports`, icon: <AssessmentOutlinedIcon fontSize="small" /> },
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
          icon: <CloudUploadOutlinedIcon fontSize="small" />,
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
          icon: <AutoAwesomeOutlinedIcon fontSize="small" />,
        },
      ],
    },
    {
      heading: 'Project',
      items: [
        { label: 'Artifacts', to: `${p}/artifacts`, icon: <InventoryOutlinedIcon fontSize="small" /> },
        /*
          `${p}/…`, and it earns the Project heading on exactly the test the note below sets: it is
          about the project in the picker and switching projects changes what it shows. It is the
          per-project view of the access model — what THIS project granted, and what it inherits
          from the company — while `Teams & roles` below stays the company-wide one. Two headings,
          two scopes, one implementation underneath.
        */
        { label: 'Access & roles', to: `${p}/access`, icon: <GroupsOutlinedIcon fontSize="small" /> },
        /*
          The project's classification vocabulary. Project-headed on the same test: Feature belongs
          to this project and switching projects changes the tree — even though three of the four
          axes on that screen are the company's, which is what the page itself says.
        */
        {
          label: 'Classification',
          to: `${p}/settings/classification`,
          icon: <LocalOfferOutlinedIcon fontSize="small" />,
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
          icon: <RateReviewOutlinedIcon fontSize="small" />,
        },
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
        { label: 'Teams & roles', to: '/admin/teams', icon: <GroupsOutlinedIcon fontSize="small" /> },
        /*
          Company, beside Teams & roles, for the same reason that one is here: a token belongs to
          the company, and switching projects does not change the list.

          **Shown to everyone, not gated.** Every other item in this rail leads to a screen a
          member can read; this one leads to a screen that tells a non-administrator the list is
          not theirs to see. That is §31's rule at rail altitude — hiding it would teach a Test
          Lead that this product has no CI credentials, which is the same lie a filtered-to-empty
          list would have told, and the screen itself already says who to ask.
        */
        { label: 'API tokens', to: '/admin/tokens', icon: <KeyOutlinedIcon fontSize="small" /> },
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
    <Box sx={{ display: 'flex', minHeight: '100%', bgcolor: 'background.default' }}>
      {/*
        First focusable element in the DOM, so a keyboard user's very first Tab offers a way past
        the rail. Without it, reaching the content on a page like the case list means tabbing
        through the project switcher and eleven nav links on every single navigation.
      */}
      <SkipToContentLink />
      {/* Desktop rail — shown wherever there is room for it. */}
      <Box
        component="nav"
        id="nav-rail"
        aria-label="Main"
        data-testid="Nav.Rail"
        /*
          Read by the collapse toggle's own assertion, and by nothing else: `aria-expanded` on the
          button names a state, and a screen reader needs the element that state is *about*. The
          testid says which variant is showing; this says how wide it currently is.
        */
        data-collapsed={railCollapsed ? 'true' : 'false'}
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          width: railCollapsed ? RAIL_WIDTH_COLLAPSED : RAIL_WIDTH,
          flexShrink: 0,
          borderRight: '1px solid',
          borderColor: 'divider',
          bgcolor: 'background.paper',
          position: 'sticky',
          top: 0,
          height: '100vh',
          // A width the user asked to change should be seen to change. Motion is opt-out at the
          // theme level (`prefers-reduced-motion` is a hard floor), so this respects it.
          transition: 'width 160ms ease',
          '@media (prefers-reduced-motion: reduce)': { transition: 'none' },
        }}
      >
        <RailContent collapsed={railCollapsed} onToggleCollapse={toggleRail} />
      </Box>

      {/* Small-screen drawer — the same content, mounted only while open. */}
      <Drawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        data-testid="Nav.Drawer"
        slotProps={{ paper: { sx: { width: RAIL_WIDTH } } }}
        sx={{ display: { xs: 'block', md: 'none' } }}
      >
        <Box component="nav" aria-label="Main" sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <RailContent onNavigate={() => setDrawerOpen(false)} />
        </Box>
      </Drawer>

      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <TopBar onOpenNav={() => setDrawerOpen(true)} />

        <Box
          component="main"
          id="main-content"
          /*
            Focusable so the route-change hook can move focus here. `-1` keeps it out of the tab
            order — it is a programmatic focus target, not a stop the user tabs through.
          */
          tabIndex={-1}
          ref={mainRef}
          sx={{
            flex: 1,
            px: { xs: 2, md: 4 },
            py: { xs: 2, md: 3 },
            minWidth: 0,
            // Focusing a large container draws a full-width ring that reads as a rendering bug
            // rather than as a focus cue. The heading the user lands on is the signal; the skip
            // link and every real control keep their own visible indicators.
            outline: 'none',
          }}
        >
          {/*
            The per-route boundary sits here, inside the shell, so a page that throws degrades to
            an alert in the content area while the rail, the project switcher, and the top bar
            keep working — the user can navigate away from a broken page instead of reloading.
          */}
          <RouteErrorBoundary>
            <Outlet />
          </RouteErrorBoundary>
        </Box>

        <AppFooter />
      </Box>
    </Box>
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

  const activeProjectId = projectId ?? projects.data?.[0]?.id;
  const groups = activeProjectId ? projectNav(activeProjectId) : [];

  return (
    <>
      {/*
        The wordmark is a link to the picker, which is the convention every reader arrives with —
        and the reason it can be one now is that `/` finally has a labelled entry below it. A logo
        that is the *only* way home is a guess the user has to make; a logo that agrees with a
        visible nav item is a shortcut.
      */}
      <Box
        sx={{
          px: collapsed ? 0 : 2,
          py: 1.75,
          display: 'flex',
          justifyContent: collapsed ? 'center' : 'flex-start',
          borderBottom: '1px solid',
          borderColor: 'divider',
        }}
      >
        <RouterLink
          to="/"
          onClick={onNavigate}
          aria-label="TCMS — all projects"
          data-testid="Nav.Wordmark"
          style={{ textDecoration: 'none' }}
        >
          <Identifier
            variant="strong"
            sx={{ fontSize: collapsed ? '0.8125rem' : '1rem', letterSpacing: collapsed ? 0 : '0.08em' }}
          >
            TCMS
          </Identifier>
        </RouterLink>
      </Box>

      {/*
        Outside the project frame below it, both of them. `MY_WORK` is personal; `ALL_PROJECTS` is
        what the switcher chooses between — see each one's own note.
      */}
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0, py: 1 }}>
        <li>
          <RailLink item={MY_WORK} onNavigate={onNavigate} collapsed={collapsed} />
        </li>
        <li>
          <RailLink item={ALL_PROJECTS} onNavigate={onNavigate} collapsed={collapsed} />
        </li>
      </Box>

      <Divider />

      {/* Project switcher: which project you're in is the frame for every key on screen. */}
      <ProjectSwitcher
        projects={projects.data ?? []}
        activeProjectId={activeProjectId}
        collapsed={collapsed}
        onNavigate={onNavigate}
      />

      <Divider />

      <Box sx={{ flex: 1, overflowY: 'auto', py: 1 }}>
        {groups.map((group, index) => (
          <Box key={group.heading} sx={{ mb: 1.5 }}>
            {/*
              Collapsed, the heading is replaced by a rule rather than dropped. The grouping is the
              only thing telling a reader that Reports is about execution and Classification is
              about the project, and four icons in one undifferentiated column lose it entirely.
              The first group needs no rule — there is already a divider above it.
            */}
            {collapsed ? (
              index > 0 && <Divider sx={{ mx: 1.5, mb: 1 }} />
            ) : (
              <Typography
                variant="caption"
                sx={{
                  display: 'block',
                  px: 2,
                  py: 0.5,
                  color: 'text.secondary',
                  textTransform: 'uppercase',
                  letterSpacing: '0.06em',
                  fontSize: '0.6875rem',
                  fontWeight: 600,
                }}
              >
                {group.heading}
              </Typography>
            )}
            <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
              {group.items.map((item) => (
                <li key={item.to}>
                  <RailLink item={item} onNavigate={onNavigate} collapsed={collapsed} />
                </li>
              ))}
            </Stack>
          </Box>
        ))}
      </Box>

      {/*
        Bottom of the rail, below the scrolling nav list rather than inside it: it is a control on
        the rail, not a destination in it, and a toggle that scrolls away with the links is one the
        user cannot find to undo. Rendered only where it does something — the drawer omits it.
      */}
      {onToggleCollapse && (
        <>
          <Divider />
          <Box sx={{ display: 'flex', justifyContent: collapsed ? 'center' : 'flex-end', px: 1, py: 0.75 }}>
            <Tooltip title={collapsed ? 'Expand navigation' : 'Collapse navigation'} placement="right">
              <IconButton
                onClick={onToggleCollapse}
                size="small"
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
                {collapsed ? <ChevronRightIcon fontSize="small" /> : <ChevronLeftIcon fontSize="small" />}
              </IconButton>
            </Tooltip>
          </Box>
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
 * It renders even with no project to switch to, matching the expanded `Select`, which renders empty
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
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  const active = projects.find((project) => project.id === activeProjectId);

  const go = (id: string) => {
    navigate(`/projects/${id}`);
    onNavigate?.();
  };

  if (collapsed) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 1.5 }}>
        <Tooltip title={active ? `Project: ${active.name}` : 'Project'} placement="right">
          <IconButton
            onClick={(event) => setAnchor(event.currentTarget)}
            size="small"
            aria-label={active ? `Switch project — current project ${active.name}` : 'Switch project'}
            aria-haspopup="menu"
            aria-expanded={anchor ? 'true' : undefined}
            data-testid="Nav.ProjectSwitcher"
            sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, px: 1 }}
          >
            {active ? (
              <Identifier variant="strong" sx={{ fontSize: '0.75rem' }}>
                {active.key}
              </Identifier>
            ) : (
              <FolderOutlinedIcon fontSize="small" />
            )}
          </IconButton>
        </Tooltip>
        <Menu anchorEl={anchor} open={!!anchor} onClose={() => setAnchor(null)}>
          {projects.map((project) => (
            <MenuItem
              key={project.id}
              selected={project.id === activeProjectId}
              onClick={() => {
                setAnchor(null);
                go(project.id);
              }}
            >
              <ListItemIcon>
                <Identifier variant="muted">{project.key}</Identifier>
              </ListItemIcon>
              <ListItemText slotProps={{ primary: { variant: 'body2' } }}>{project.name}</ListItemText>
            </MenuItem>
          ))}
        </Menu>
      </Box>
    );
  }

  return (
    <Box sx={{ px: 1.5, py: 1.5 }}>
      <Typography
        variant="caption"
        component="label"
        htmlFor="project-switcher"
        sx={{ display: 'block', color: 'text.secondary', mb: 0.5 }}
      >
        Project
      </Typography>
      <Select
        native
        id="project-switcher"
        size="small"
        fullWidth
        value={activeProjectId ?? ''}
        onChange={(event) => go(event.target.value)}
        data-testid="Nav.ProjectSwitcher"
      >
        {projects.map((project) => (
          <option key={project.id} value={project.id}>
            {project.name}
          </option>
        ))}
      </Select>
    </Box>
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
      style={{ textDecoration: 'none' }}
    >
      {({ isActive }) => (
        <Box
          aria-current={isActive ? 'page' : undefined}
          sx={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: collapsed ? 'center' : 'flex-start',
            gap: collapsed ? 0 : 1.25,
            px: collapsed ? 0 : 2,
            py: 0.875,
            position: 'relative',
            color: isActive ? 'primary.main' : 'text.primary',
            fontWeight: isActive ? 600 : 400,
            fontSize: '0.875rem',
            bgcolor: isActive ? 'action.selected' : 'transparent',
            '&:hover': { bgcolor: 'action.hover' },
            '&::before': isActive
              ? {
                  content: '""',
                  position: 'absolute',
                  left: 0,
                  top: 6,
                  bottom: 6,
                  width: 3,
                  borderRadius: '0 2px 2px 0',
                  bgcolor: 'primary.main',
                }
              : undefined,
          }}
        >
          <Box sx={{ display: 'flex', color: isActive ? 'primary.main' : 'text.secondary' }}>{item.icon}</Box>
          {!collapsed && item.label}
        </Box>
      )}
    </NavLink>
  );

  /*
    The tooltip is the collapsed rail's label, so it exists only there — wrapping the expanded link
    too would put a tooltip on text that already says the same word, which is noise on every hover
    across the whole rail.
  */
  return collapsed ? (
    <Tooltip title={item.label} placement="right">
      {link}
    </Tooltip>
  ) : (
    link
  );
}

// --------------------------------------------------------------------- top bar ----

function TopBar({ onOpenNav }: { onOpenNav: () => void }) {
  const { user, logout } = useAuth();
  const { mode, systemMode, setMode } = useColorScheme();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);

  /**
   * The toggle must reflect the scheme the user is *looking at*, not the stored setting.
   * The default mode is `system`, so on a machine set to dark, `mode === 'dark'` is false
   * while the page is plainly dark — reading the setting alone shows a "switch to dark"
   * control on an already-dark page, and the first click then appears to do nothing because
   * it only moves `system` to the equivalent explicit value.
   */
  const isDark = mode === 'dark' || (mode === 'system' && systemMode === 'dark');

  return (
    <Box
      component="header"
      sx={{
        display: 'flex',
        alignItems: 'center',
        gap: 1,
        px: { xs: 1, md: 2 },
        height: 52,
        flexShrink: 0,
        borderBottom: '1px solid',
        borderColor: 'divider',
        bgcolor: 'background.paper',
        position: 'sticky',
        top: 0,
        zIndex: 10,
      }}
    >
      <IconButton
        onClick={onOpenNav}
        aria-label="Open navigation"
        data-testid="Nav.HamburgerButton"
        sx={{ display: { xs: 'inline-flex', md: 'none' } }}
      >
        <MenuIcon />
      </IconButton>

      <Box sx={{ flex: 1 }} />

      <Tooltip title={isDark ? 'Switch to light theme' : 'Switch to dark theme'}>
        <IconButton
          onClick={() => setMode(isDark ? 'light' : 'dark')}
          aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
          data-testid="TopBar.ThemeToggle"
          size="small"
        >
          {isDark ? <LightModeOutlinedIcon fontSize="small" /> : <DarkModeOutlinedIcon fontSize="small" />}
        </IconButton>
      </Tooltip>

      <Button
        onClick={(event) => setAnchor(event.currentTarget)}
        data-testid="TopBar.UserMenuButton"
        size="small"
        sx={{ color: 'text.primary' }}
        aria-haspopup="menu"
        aria-expanded={anchor ? 'true' : undefined}
      >
        {user?.displayName ?? 'Account'}
      </Button>

      <Menu anchorEl={anchor} open={!!anchor} onClose={() => setAnchor(null)}>
        <Box sx={{ px: 2, py: 1, minWidth: 200 }}>
          <Typography variant="body2" sx={{ fontWeight: 600 }}>
            {user?.displayName}
          </Typography>
          <Typography variant="caption" sx={{ color: 'text.secondary' }}>
            {user?.email}
          </Typography>
        </Box>
        <Divider />
        <MenuItem onClick={logout} data-testid="TopBar.SignOutButton">
          <ListItemIcon>
            <LogoutIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText slotProps={{ primary: { variant: 'body2' } }}>Sign out</ListItemText>
        </MenuItem>
      </Menu>
    </Box>
  );
}

/**
 * A slim utility footer — this is a focused internal tool, so it carries the build it is running,
 * not a marketing fat footer. Semantic `<footer>`, so it forms a `contentinfo` landmark.
 *
 * It used to also carry "Documentation" and "Contact support", both `href="#"`. Neither
 * destination exists yet, and a link that goes nowhere is worse than an absent one: it costs a
 * click, teaches the user the footer is decorative, and sits in the tab order of every page in the
 * app. **They come back when they have somewhere to point** — a self-hosted instance's support
 * contact is per-deployment configuration, not a constant, so it belongs with the instance
 * settings that release rather than hard-coded here.
 */
function AppFooter() {
  return (
    <Box
      component="footer"
      data-testid="AppFooter"
      sx={{
        px: { xs: 2, md: 4 },
        py: 2,
        borderTop: '1px solid',
        borderColor: 'divider',
        color: 'text.secondary',
      }}
    >
      <Typography variant="caption">
        TCMS <Identifier variant="muted">{__APP_VERSION__}</Identifier>
      </Typography>
    </Box>
  );
}
