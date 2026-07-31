import { Box, Tooltip } from '@mui/material';
import type { ReactNode } from 'react';
import type { PermissionKey } from '../api/types';
import { useCan } from '../auth/useCan';

/**
 * The one place a permission the caller lacks becomes a disabled control with a reason.
 *
 * `ui-rules` calls for **disable, don't hide**: an action the backend will refuse renders
 * disabled with a why-tooltip, never render-then-reject and never quietly absent. Both halves
 * of that are mechanical enough to get wrong once per call site, so they live here:
 *
 *  1. **The child decides what "disabled" means for it.** A render prop, not a cloned element —
 *     a `Button` takes `disabled`, a `MenuItem` takes `disabled`, an `<a>` takes neither, and
 *     `cloneElement`-ing a prop onto whatever arrives is how a disabled-looking anchor ships.
 *  2. **The tooltip needs a wrapper, and forgetting it is silent.** MUI sets
 *     `pointer-events: none` on a disabled control, so no hover event ever reaches a `Tooltip`
 *     wrapped directly around one — the control greys out and explains nothing, which is the
 *     *worse* half of the original defect. The denied branch wraps the child in a span that
 *     still receives the hover.
 *
 * Deliberately NOT hiding anything: a Viewer seeing a greyed "Delete" learns the capability
 * exists and is worth asking for. A Viewer seeing no button at all learns the product cannot do
 * it. The second is a lie, and it is also why action cells stop shifting between rows.
 *
 * Every gated control keeps its `data-testid` — a disabled button is still the button under
 * test, and a test that can't find it can't assert it is disabled.
 */
export function PermissionGate({
  projectId,
  permission,
  wrapper = 'inline',
  children,
}: {
  projectId: string | undefined;
  permission: PermissionKey;
  /**
   * How the denied branch's tooltip anchor lays out. `inline` suits a flex row of buttons;
   * **`block` is required inside a `Menu`**, whose `MenuList` is a plain `<ul>` — an inline
   * anchor there makes the items flow along one line instead of stacking, which is what the
   * first browser pass of this component actually did to the suite tree's kebab menu.
   */
  wrapper?: 'inline' | 'block';
  /** Receives the verdict; renders the control with its own notion of `disabled`. */
  children: (state: { allowed: boolean; reason: string | undefined }) => ReactNode;
}) {
  const gate = useCan(projectId);
  const allowed = gate.can(permission);
  const reason = gate.reason(permission);

  const control = children({ allowed, reason });
  if (allowed || !reason) return <>{control}</>;

  return (
    <Tooltip title={reason}>
      {/*
        `inline-flex` rather than a bare span: the wrapper sits where the control sat, and a
        default inline box would collapse a full-width button and break the alignment of a row
        of them. `cursor: not-allowed` states the verdict before the tooltip has time to open.
      */}
      <Box
        component="span"
        /*
          Explicitly presentational. Inside a `Menu` this span sits between the `role="menu"`
          list and the `role="menuitem"` it wraps, and an unmarked generic there can break the
          parent/child relationship some screen readers rely on. `role="none"` says the wrapper
          is scaffolding. Outside a menu a span has no implicit role, so this costs nothing.
        */
        role="none"
        sx={{ display: wrapper === 'block' ? 'block' : 'inline-flex', cursor: 'not-allowed' }}
      >
        {control}
      </Box>
    </Tooltip>
  );
}
