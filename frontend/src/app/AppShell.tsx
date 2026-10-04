import { Outlet } from 'react-router-dom';
import { useRouteFocus } from '../a11y/useRouteFocus';
import { RouteErrorBoundary } from '../components/ErrorBoundaries';
import { SkipToContentLink } from '../components/SkipToContentLink';
import { ShellFrameProvider } from './shellFrame';
import { useActiveProject } from './shellNav';
import { TopBar } from './TopBar';
import { TopStripProvider } from './topStripSlot';

/**
 * The persistent application shell: one 48px top bar over the content (023 §3).
 *
 * It is a **layout**, not a template — it mounts once and stays mounted, so the bar and anything it
 * holds survive navigation between pages. Only `<Outlet/>` changes. There is no rail, drawer or
 * footer: navigation is the bar's launcher, tabs and menus, and the page below is the work.
 *
 * The bar's responsiveness is CSS visibility on the app's breakpoint contract, not a JS branch, so
 * no width-dependent code decides layout.
 *
 * The gate behaviour the bar is drawn from (`useNavGates`, `visibleEntries`, `useActiveProject`)
 * lives in `app/shellNav.ts`, because the bar's parts all read it and this file imports them.
 */
export function AppShell() {
  const { projects, activeProjectId } = useActiveProject();
  const activeProject = projects.find((project) => project.id === activeProjectId);

  const mainRef = useRouteFocus<HTMLElement>();

  return (
    <TopStripProvider>
      <ShellFrameProvider
        value={{
          projectId: activeProjectId,
          projectKey: activeProject?.key,
          projectName: activeProject?.name,
        }}
      >
        <div className="flex min-h-full flex-col bg-paper">
          <SkipToContentLink />
          <TopBar />
          <main
            id="main-content"
            tabIndex={-1}
            ref={mainRef}
            className="min-w-0 flex-1 px-3 pb-6 outline-none md:px-4"
          >
            <RouteErrorBoundary>
              <Outlet />
            </RouteErrorBoundary>
          </main>
        </div>
      </ShellFrameProvider>
    </TopStripProvider>
  );
}
