import type { ReactNode } from 'react';

/**
 * Lets content wider than its container **scroll** rather than be clipped.
 *
 * Found by driving §52's suite history in a browser at 810 px: the last column — the date every
 * row is sorted by — was simply not there, and no scrollbar offered it. `Panel` sets
 * `overflow: hidden`, which it needs for its rounded corners, so a table wider than the panel is
 * cut off at the border with nothing to say so. **The page itself did not scroll**, which is the
 * rule working; the content had nowhere to scroll *inside*, which is the other half of the same
 * rule and was missing.
 *
 * It is a component rather than a `scrollX` prop on `Panel` because the rule is about the wide
 * content, not about the surface around it: a panel holding a paragraph must keep clipping, and
 * the eight tables in this app are the things that need to scroll. Wrapping at the table also
 * keeps the panel's title row still while the columns move under it.
 *
 * `overflow-x: auto` forces the computed `overflow-y` to `auto` as well — that is CSS, not a
 * choice — so this is deliberately used around content that grows sideways, never around a
 * region with its own vertical scrolling (~~the DataGrid manages its own~~ → superseded: the
 * grid is TanStack Table since T9, and it too must not be wrapped).
 */
export function ScrollX({ children }: { children: ReactNode }) {
  return <div className="max-w-full overflow-x-auto">{children}</div>;
}
