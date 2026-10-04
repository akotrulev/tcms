import { ChevronDown } from 'lucide-react';
import { Link as RouterLink, NavLink, useLocation } from 'react-router-dom';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '../components/ui';
import { cn } from '../lib/cn';
import { NAV, isFolder, type NavItem } from './nav';
import { navTestId, useNavGates, visibleEntries } from './shellNav';

const tabClass = (active: boolean) =>
  cn(
    'flex h-8 items-center rounded-md px-2.5 text-[13px] font-medium whitespace-nowrap no-underline',
    'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[color:var(--color-given)]',
    active ? 'bg-steel/12 font-semibold text-ink' : 'text-ink-3 hover:bg-wash-1 hover:text-ink-2',
  );

/**
 * The project's tabs and More (023 §3). Work is always a tab; the other tabs fold into More below
 * `lg`, so More lists the `more` entries and, on a narrow bar, the folded tabs too (as `lg:hidden`
 * items). More is labelled with the active entry when one of its entries is the current place, and
 * is then drawn active, so the current place is never nowhere.
 */
export function ProjectTabs({ projectId }: { projectId: string }) {
  const { pathname } = useLocation();
  const allows = useNavGates(projectId);
  const zone = NAV.find((candidate) => candidate.id === 'project');
  const entries = zone ? visibleEntries(zone, allows).filter((entry): entry is NavItem => !isFolder(entry)) : [];

  const tabs = entries.filter((entry) => entry.placement === 'tab');
  const more = entries.filter((entry) => entry.placement === 'more');
  const overflow = [...tabs.filter((tab) => tab.label !== 'Work'), ...more];

  const isCurrent = (item: NavItem) => {
    const to = item.to(projectId);
    return item.end ? pathname === to : pathname === to || pathname.startsWith(`${to}/`);
  };
  const activeInMore = overflow.find(isCurrent);

  return (
    <>
      <nav aria-label="Project" data-testid="Nav.ProjectTabs" className="flex items-center gap-0.5">
        {tabs.map((tab) => (
          <NavLink
            key={tab.label}
            to={tab.to(projectId)}
            end={tab.end}
            data-testid={navTestId(tab.label)}
            className={({ isActive }) => cn(tabClass(isActive), tab.label !== 'Work' && 'max-lg:hidden')}
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
      {overflow.length > 0 && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              data-testid="Nav.More"
              aria-label={activeInMore ? `More: ${activeInMore.label}` : 'More'}
              className={cn(tabClass(Boolean(activeInMore)), 'cursor-pointer gap-1 border-0 bg-transparent')}
            >
              {activeInMore ? activeInMore.label : 'More'}
              <ChevronDown aria-hidden className="size-3.5 shrink-0" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="min-w-[200px]">
            {overflow.map((item) => {
              const Icon = item.icon;
              const current = isCurrent(item);
              return (
                <DropdownMenuItem
                  key={item.label}
                  asChild
                  className={cn(item.placement === 'tab' && 'lg:hidden')}
                >
                  <RouterLink
                    to={item.to(projectId)}
                    data-testid={navTestId(item.label)}
                    aria-current={current ? 'page' : undefined}
                  >
                    <Icon aria-hidden className="size-4 text-ink-3" />
                    <span className={cn(current && 'font-semibold text-ink')}>{item.label}</span>
                  </RouterLink>
                </DropdownMenuItem>
              );
            })}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </>
  );
}
