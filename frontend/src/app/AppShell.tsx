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
import AssignmentOutlinedIcon from '@mui/icons-material/AssignmentOutlined';
import DarkModeOutlinedIcon from '@mui/icons-material/DarkModeOutlined';
import DashboardOutlinedIcon from '@mui/icons-material/DashboardOutlined';
import FolderOutlinedIcon from '@mui/icons-material/FolderOutlined';
import RateReviewOutlinedIcon from '@mui/icons-material/RateReviewOutlined';
import LocalOfferOutlinedIcon from '@mui/icons-material/LocalOfferOutlined';
import GroupsOutlinedIcon from '@mui/icons-material/GroupsOutlined';
import InventoryOutlinedIcon from '@mui/icons-material/Inventory2Outlined';
import LightModeOutlinedIcon from '@mui/icons-material/LightModeOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import PlaylistAddCheckOutlinedIcon from '@mui/icons-material/PlaylistAddCheckOutlined';
import ViewKanbanOutlinedIcon from '@mui/icons-material/ViewKanbanOutlined';
import { useState, type ReactNode } from 'react';
import { NavLink, Outlet, useNavigate, useParams } from 'react-router-dom';
import { useProjects } from '../api/queries';
import { useRouteFocus } from '../a11y/useRouteFocus';
import { Identifier } from '../components/Identifier';
import { RouteErrorBoundary } from '../components/ErrorBoundaries';
import { SkipToContentLink } from '../components/SkipToContentLink';
import { useAuth } from '../auth/useAuth';

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

interface NavItem {
  label: string;
  to: string;
  icon: ReactNode;
  /** Marks the item active for descendant routes too, not just an exact match. */
  end?: boolean;
}

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
      ],
    },
  ];
}

export function AppShell() {
  const [drawerOpen, setDrawerOpen] = useState(false);

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
        aria-label="Main"
        data-testid="Nav.Rail"
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          width: RAIL_WIDTH,
          flexShrink: 0,
          borderRight: '1px solid',
          borderColor: 'divider',
          bgcolor: 'background.paper',
          position: 'sticky',
          top: 0,
          height: '100vh',
        }}
      >
        <RailContent />
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

function RailContent({ onNavigate }: { onNavigate?: () => void }) {
  const { projectId } = useParams();
  const navigate = useNavigate();
  const projects = useProjects();

  const activeProjectId = projectId ?? projects.data?.[0]?.id;
  const groups = activeProjectId ? projectNav(activeProjectId) : [];

  return (
    <>
      <Box sx={{ px: 2, py: 1.75, borderBottom: '1px solid', borderColor: 'divider' }}>
        <Identifier variant="strong" sx={{ fontSize: '1rem', letterSpacing: '0.08em' }}>
          TCMS
        </Identifier>
      </Box>

      {/* Project switcher: which project you're in is the frame for every key on screen. */}
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
          onChange={(event) => {
            navigate(`/projects/${event.target.value}`);
            onNavigate?.();
          }}
          data-testid="Nav.ProjectSwitcher"
        >
          {(projects.data ?? []).map((project) => (
            <option key={project.id} value={project.id}>
              {project.name}
            </option>
          ))}
        </Select>
      </Box>

      <Divider />

      <Box sx={{ flex: 1, overflowY: 'auto', py: 1 }}>
        {groups.map((group) => (
          <Box key={group.heading} sx={{ mb: 1.5 }}>
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
            <Stack component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
              {group.items.map((item) => (
                <li key={item.to}>
                  <RailLink item={item} onNavigate={onNavigate} />
                </li>
              ))}
            </Stack>
          </Box>
        ))}
      </Box>
    </>
  );
}

/**
 * The active item is marked three ways at once — an indicator bar, heavier weight, and
 * `aria-current="page"` — so the current location is never communicated by colour alone.
 */
function RailLink({ item, onNavigate }: { item: NavItem; onNavigate?: () => void }) {
  return (
    <NavLink
      to={item.to}
      end={item.end}
      onClick={onNavigate}
      data-testid={`Nav.Link.${item.label.replace(/[^A-Za-z]/g, '')}`}
      style={{ textDecoration: 'none' }}
    >
      {({ isActive }) => (
        <Box
          aria-current={isActive ? 'page' : undefined}
          sx={{
            display: 'flex',
            alignItems: 'center',
            gap: 1.25,
            px: 2,
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
          {item.label}
        </Box>
      )}
    </NavLink>
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
