import { MoreHorizontal } from 'lucide-react';
import type { ReactNode } from 'react';

import { Button } from '../components/ui';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '../components/ui/dropdown-menu';

/**
 * The shell's header action slot, which collapses rather than wrapping.
 *
 * `AppShell.tsx` states that the header has a fixed 52px height and does not collapse. A
 * `flex-wrap` group portalled into it therefore does not wrap *inside* the header — it wraps
 * *through* it: the 2026-08-30 renders audit measured `CaseDetailPage`'s five actions putting a
 * second row of buttons 5.5px below the header's own bottom border, on top of the page content,
 * with the breadcrumb truncated to `Test cases / Checko…` behind them. The runner's top strip
 * loses the same way at 1280px, where the case indicator is clipped off the top of the viewport.
 *
 * Growing the header instead was considered and rejected: the 52px is the one measurement the
 * whole shell is laid out against, and a variable one turns every sticky offset into a guess.
 *
 * ## Why this takes elements rather than a list of actions
 *
 * The obvious shape is `actions: {label, onSelect}[]`. It does not survive contact with the call
 * sites: these actions are a router link, a menu, and three `PermissionGate` children whose denied
 * branch is a *different element* with a why-tooltip — a distinction `CaseDetailPage` spends four
 * paragraphs defending, and one a `{label, onSelect}` array would quietly delete. So the caller
 * keeps building its own controls and this owns only the two things that were wrong: the group
 * does not wrap, and everything past the first few lives behind one menu.
 *
 * `overflow` is menu content — `DropdownMenuItem`s, gated the same way the inline ones are
 * (`PermissionGate`'s docblock covers a `MenuItem`, which takes `disabled` like a `Button`).
 */
export interface HeaderActionsProps {
  /** The controls that stay visible. Keep it to three at the labels this shell uses. */
  children: ReactNode;
  /** `DropdownMenuItem`s for everything else. Omitted means there is no overflow control. */
  overflow?: ReactNode;
  'data-testid'?: string;
}

export function HeaderActions({ children, overflow, ...rest }: HeaderActionsProps) {
  const testId = rest['data-testid'] ?? 'Header.Actions';
  return (
    <div className="flex min-w-0 items-center gap-2" data-testid={rest['data-testid']}>
      {children}
      {overflow && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              size="sm"
              aria-label="More actions"
              data-testid={`${testId}.More`}
            >
              <MoreHorizontal aria-hidden className="size-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">{overflow}</DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  );
}
