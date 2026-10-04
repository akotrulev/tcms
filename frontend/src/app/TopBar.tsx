import { Inbox, Moon, Sun } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import { Identifier } from '../components/Identifier';
import { Button, Tooltip, TooltipContent, TooltipTrigger } from '../components/ui';
import { CommandPalette } from './CommandPalette';
import { CreateMenu } from './CreateMenu';
import { LauncherMenu } from './LauncherMenu';
import { ProjectSwitcher } from './ProjectSwitcher';
import { ProjectTabs } from './ProjectTabs';
import { useActiveProject } from './shellNav';
import { useColorScheme } from './useColorScheme';
import { UserMenu } from './UserMenu';

/**
 * The one 48px bar on every authenticated route (023 §3, A1): launcher, wordmark, project switcher,
 * project tabs and More, then search, Create, My work, theme and the user menu.
 *
 * The switcher always renders; the tabs, More and Create render only when a project is resolved
 * (`useActiveProject`), so a member holding no project grants sees the launcher and no empty frame.
 * At 390px the bar must not scroll sideways: below `lg` the tabs fold into More, below `md` search
 * and Create are icons, below `sm` the switcher is the key chip alone and the wordmark hides.
 */
export function TopBar() {
  const { resolved, setMode } = useColorScheme();
  const { activeProjectId } = useActiveProject();

  /**
   * The toggle reflects the scheme the user is *looking at*, not the stored setting: the default
   * mode is `system`, so on a dark machine `mode === 'dark'` is false while the page is plainly
   * dark. `resolved` is the provider's own computation of exactly that.
   */
  const isDark = resolved === 'dark';

  return (
    <header className="sticky top-0 z-20 flex h-12 shrink-0 items-center gap-1 border-b border-line bg-bar px-2.5">
      <LauncherMenu />
      <RouterLink
        to="/"
        aria-label="TCMS — all projects"
        data-testid="Nav.Wordmark"
        className="mx-1 hidden no-underline sm:inline"
      >
        <Identifier variant="strong" className="text-[13px] tracking-[.08em]">
          TCMS
        </Identifier>
      </RouterLink>
      <span aria-hidden className="mx-1 h-5 w-px shrink-0 bg-line" />
      <ProjectSwitcher />
      {activeProjectId && <ProjectTabs projectId={activeProjectId} />}

      <div className="min-w-0 flex-1" />

      <CommandPalette projectId={activeProjectId} />
      {activeProjectId && <CreateMenu projectId={activeProjectId} />}

      <Tooltip>
        <TooltipTrigger asChild>
          <Button variant="quiet" size="icon" asChild>
            <RouterLink to="/my-work" aria-label="My work" data-testid="TopBar.MyWork">
              <Inbox aria-hidden className="size-4" />
            </RouterLink>
          </Button>
        </TooltipTrigger>
        <TooltipContent>My work</TooltipContent>
      </Tooltip>

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

      <UserMenu />
    </header>
  );
}
