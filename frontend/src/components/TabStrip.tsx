import { NavLink } from 'react-router-dom';
import type { TabEntry } from '../app/nav';
import { cn } from '../lib/cn';
import { segmentItemClass, segmentTrackClass } from './kit/Segmented';

/**
 * The segmented strip that carries the five screens which used to be rail rows (021 §1, §2.6).
 *
 * **One component for all three strips** — Test cases, Test runs, Documents — fed from
 * `app/nav.ts`'s `TABS`, so a tab and a rail row are drawn from the same model and a screen cannot
 * end up in both or in neither.
 *
 * ## Links, not a `<Tabs>`
 *
 * `components/ui/tabs.tsx` is a Radix tablist: one mounted panel per value, the active one held in
 * component state. That is the right shape for `ProposalsPage`'s Waiting/Accepted/Rejected, which
 * are three views of one query on one route. It is the wrong shape here, because each of these
 * tabs is a **different route with a different page behind it** — `…/cases`, `…/suites`,
 * `…/generated-cases`. Rendering them as a tablist would mean one URL for three screens, which is
 * the property the proposal is at pains to keep: *each tab keeps its own URL, so every pasted link
 * still lands and the active tab is read from the route rather than stored.*
 *
 * So this is a `<nav>` of real anchors. Middle-click opens a tab, "copy link address" copies
 * something that works, and a crawler — or a person reading the status bar — sees where the click
 * goes. `aria-current="page"` marks the active one, which is what a list of links uses; `role`
 * is deliberately not overridden to `tablist`, because these do not control panels on this page.
 *
 * ## The visual grammar is Work's
 *
 * `features/tracker/WorkTablePage.tsx` already draws this strip for the Work views — an 8px
 * `bg-wash-1` track, 6px items, 12px medium type, and the active item lifted onto `--e1` rather
 * than tinted. The kit calls it `.lens`. Matching it is the point: a reader who has learned the
 * board's view switcher has learned this one, and two idioms for "these are sibling views of one
 * subject" is exactly the inconsistency the IA work is undoing.
 */
export function TabStrip({
  tabs,
  projectId,
  label,
  testId,
}: {
  tabs: readonly TabEntry[];
  projectId: string;
  /** Names the strip for a screen reader — "Test case views", not "Tabs". */
  label: string;
  testId: string;
}) {
  return (
    <nav
      aria-label={label}
      data-testid={testId}
      className={cn(segmentTrackClass, 'flex-wrap')}
    >
      {tabs.map((tab) => (
        <NavLink
          key={tab.label}
          to={tab.to(projectId)}
          end={tab.end}
          data-testid={`${testId}.${tab.label.replace(/[^A-Za-z]/g, '')}`}
          className="no-underline"
        >
          {({ isActive }) => (
            <span
              /*
                On the inner element rather than the anchor, matching `RailLink`: `NavLink`'s render
                prop is what knows the active state, and putting the attribute on the anchor would
                mean computing the match twice.
              */
              aria-current={isActive ? 'page' : undefined}
              className={segmentItemClass(isActive)}
            >
              {tab.label}
            </span>
          )}
        </NavLink>
      ))}
    </nav>
  );
}
