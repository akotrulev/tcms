import { LayoutGrid } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import {
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '../components/ui';
import { NAV, isFolder, type NavItem } from './nav';
import { navTestId, useActiveProject, useNavGates, visibleEntries } from './shellNav';

/**
 * The launcher — the company's and the person's places, in two columns (023 §3).
 *
 * **Company-name slot (021 §3), still reserved and empty.** The outermost frame — instance →
 * company → project → screen — would sit beside the wordmark. No endpoint names the caller's
 * company: `GET /me` answers a `CurrentUser` with no company on it, so filling it needs one
 * contract field, which is another stack's task. Nothing is drawn rather than a constant, because
 * a bar that says "Acme Ltd" to every company on the instance is worse than one that says nothing.
 *
 * An entry is listed or absent by the same gates the rail applied (`useNavGates`), and a section
 * with no visible entries is not drawn.
 */
export function LauncherMenu() {
  const { activeProjectId } = useActiveProject();
  const allows = useNavGates(activeProjectId);

  const visible = (id: 'you' | 'company') => {
    const zone = NAV.find((candidate) => candidate.id === id);
    if (!zone || !allows(zone.needs)) return [];
    return visibleEntries(zone, allows);
  };

  const you = visible('you').filter((entry): entry is NavItem => !isFolder(entry));
  const company = visible('company');
  const companyItems = company.filter((entry): entry is NavItem => !isFolder(entry));
  const administration = company.flatMap((entry) => (isFolder(entry) ? entry.items : []));

  return (
    <DropdownMenu>
      <Tooltip>
        <TooltipTrigger asChild>
          <DropdownMenuTrigger asChild>
            <Button
              variant="quiet"
              size="icon"
              aria-label="Your work and company"
              data-testid="Nav.Launcher"
            >
              <LayoutGrid aria-hidden className="size-4" />
            </Button>
          </DropdownMenuTrigger>
        </TooltipTrigger>
        <TooltipContent>Your work and company</TooltipContent>
      </Tooltip>
      <DropdownMenuContent align="start" className="grid w-[500px] max-w-[calc(100vw-1rem)] grid-cols-2 gap-x-2.5 p-2">
        <div className="min-w-0">
          <LauncherSection label="You" items={you} />
          <LauncherSection label="Administration" items={administration} />
        </div>
        <div className="min-w-0">
          <LauncherSection label="Company" items={companyItems} />
        </div>
        <p className="col-span-2 m-0 mt-1 flex items-center justify-between gap-2 border-t border-line px-2.5 pt-2 text-[11.5px] text-ink-3">
          <span>Only entries you can open are listed.</span>
          <RouterLink
            to="/account/access"
            data-testid="Nav.Launcher.AccessLink"
            className="text-steel underline-offset-4 hover:underline"
          >
            Your access
          </RouterLink>
        </p>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function LauncherSection({ label, items }: { label: string; items: NavItem[] }) {
  if (!items.length) return null;
  return (
    <section aria-label={label} data-testid={`Nav.Launcher.${label}`}>
      <DropdownMenuLabel>{label}</DropdownMenuLabel>
      {items.map((item) => {
        const Icon = item.icon;
        return (
          <DropdownMenuItem key={item.label} asChild>
            <RouterLink to={item.to('')} data-testid={navTestId(item.label)} className="items-start">
              <Icon aria-hidden className="mt-0.5 size-4 shrink-0 text-ink-3" />
              <span className="flex min-w-0 flex-col">
                <span className="text-[13px] font-medium text-ink">{item.label}</span>
                {item.hint && <span className="text-[11.5px] text-ink-3">{item.hint}</span>}
              </span>
            </RouterLink>
          </DropdownMenuItem>
        );
      })}
    </section>
  );
}
