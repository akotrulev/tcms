import { Bot, Bug, HelpCircle, LogOut, Shield, Stamp, Timer } from 'lucide-react';
import { Link as RouterLink } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { Identifier } from '../components/Identifier';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '../components/ui';

const initialsOf = (name: string | undefined) => {
  const words = (name ?? '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '?';
  const letters = words.length === 1 ? words[0].slice(0, 2) : words[0][0] + words[words.length - 1][0];
  return letters.toUpperCase();
};

/**
 * The person's own things: their timesheets, their credentials, their access, the guide, and the
 * build they are running. The rule that put each row here (021 R11) is unchanged — a place in the
 * product belongs to the bar's tabs and launcher, and a fortnight of one person's hours, or a
 * credential they own, belongs to them.
 *
 * `Timesheet approvals` is listed for **everybody** and carries **no count**: the page explains its
 * own refusal (§31), and the count would come from a read that 403s for anyone without
 * `time:approve`, a PROJECT-scope grant, so there is no company-wide question to ask first.
 *
 * `Your access` answers "why is your navigation different from mine?": nine entries are hidden
 * rather than disabled, each for a recorded reason, and this page names them and who holds the key.
 *
 * `Keyboard shortcuts` is still not here: there is no shortcuts page to point at, and a menu entry
 * that lands on the 404 is worse than an absent one. The palette draws its own keys in its footer.
 */
export function UserMenu() {
  const { user, logout } = useAuth();
  const name = user?.displayName;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          data-testid="TopBar.UserMenuButton"
          aria-label={`Account: ${name ?? 'Account'}`}
          className={
            'ml-0.5 flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-full ' +
            'border-0 bg-steel font-mono text-[11px] font-semibold text-rail ' +
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]'
          }
        >
          {initialsOf(name)}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <div className="px-2 py-1.5">
          <p className="m-0 text-[13px] font-semibold text-ink">{user?.displayName}</p>
          <p className="m-0 text-[12px] text-ink-3">{user?.email}</p>
        </div>
        <DropdownMenuSeparator />
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
        <DropdownMenuLabel>Account</DropdownMenuLabel>
        <DropdownMenuItem asChild>
          <RouterLink to="/account/security" data-testid="TopBar.SecurityLink">
            <Shield className="size-4" />
            <span>Security</span>
          </RouterLink>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <RouterLink to="/account/trackers" data-testid="TopBar.TrackersLink">
            <Bug className="size-4" />
            <span>Tracker connections</span>
          </RouterLink>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <RouterLink to="/account/assistants" data-testid="TopBar.AssistantsLink">
            <Bot className="size-4" />
            <span>AI assistants</span>
          </RouterLink>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <RouterLink to="/account/access" data-testid="TopBar.AccessLink">
            <Shield className="size-4" />
            <span>Your access</span>
          </RouterLink>
        </DropdownMenuItem>
        <DropdownMenuItem asChild>
          <RouterLink to="/help" data-testid="TopBar.HelpLink">
            <HelpCircle className="size-4" />
            <span>User guide</span>
          </RouterLink>
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <p className="m-0 px-2.5 py-1 text-[11.5px] text-ink-3" data-testid="TopBar.Version">
          TCMS <Identifier variant="muted">{__APP_VERSION__}</Identifier>
        </p>
        <DropdownMenuItem onClick={logout} data-testid="TopBar.SignOutButton">
          <LogOut className="size-4" />
          <span>Sign out</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
