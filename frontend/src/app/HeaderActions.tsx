import { MoreHorizontal } from 'lucide-react';
import { Children, type ReactNode } from 'react';

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
/**
 * **One primary plus one overflow, and the cap is enforced** (021 §8.2, T7).
 *
 * ~~"Keep it to three at the labels this shell uses."~~ → **superseded (021 §8.2).** A comment is
 * not a cap. `CaseDetailPage` had five controls in here — the exact group whose wrap through the
 * header this component was built to stop — and every one of them was added by somebody who had
 * read the note and judged their action to be the important one. The convention is now the shape
 * of the API: what stays visible is at most two children, and everything else is `overflow`.
 *
 * **Two, not one.** §8.2 says "one primary per screen", and one primary is what the five
 * document-mode screens now carry — but the runner's strip is a status cluster beside a layout
 * switch and neither is an action, so a hard cap of one would push a mode control into a menu to
 * satisfy a rule about action groups. Two visible children, at most one of which is a primary
 * button, is the shape all five fit without an argument.
 *
 * It warns rather than throws, and only in dev. A `console.error` reaches the person adding the
 * third child on the first render, which is when the decision is being made; a throw would turn a
 * layout convention into a blank screen in production, and this is not a correctness rule.
 */
function assertCapped(children: ReactNode, testId: string) {
  if (!import.meta.env.DEV) return;
  const count = Children.count(children);
  if (count <= 2) return;
  console.error(
    `${testId}: ${String(count)} visible header controls. The shell's header is fixed at 52px and `
      + 'does not collapse, so a group that outgrows it wraps THROUGH the header onto the page '
      + '(2026-08-30 renders audit, finding 2.1). One primary plus `overflow` — 021 §8.2. Group '
      + 'anything that is one cluster rather than one action into a single child.',
  );
}

export interface HeaderActionsProps {
  /**
   * The controls that stay visible — **at most two**, and see {@link assertCapped} for why that is
   * checked rather than asked for. Everything past them goes in `overflow`.
   */
  children: ReactNode;
  /** `DropdownMenuItem`s for everything else. Omitted means there is no overflow control. */
  overflow?: ReactNode;
  'data-testid'?: string;
}

export function HeaderActions({ children, overflow, ...rest }: HeaderActionsProps) {
  const testId = rest['data-testid'] ?? 'Header.Actions';
  assertCapped(children, testId);
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
